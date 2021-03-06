/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.driver.internal.pool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.neo4j.driver.internal.util.Clock;
import org.neo4j.driver.internal.util.Consumer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * A general pool implementation, heavily inspired by Chris Vests "stormpot" pool, but without a background thread
 * managing allocation.
 *
 * @param <T>
 */
public class ThreadCachingPool<T> implements AutoCloseable
{
    /**
     * Keeps a reference to a locally cached pool slot, to avoid global lookups.
     * Other threads may still access the slot in here, if they cannot acquire an object from the global pool.
     */
    private final ThreadLocal<Slot<T>> local = new ThreadLocal<>();

    /** Keeps references to slots that are likely (but not necessarily) live */
    private final BlockingQueue<Slot<T>> live = new LinkedBlockingQueue<>();

    /** Keeps references to slots that have been disposed of. Used when re-allocating. */
    private final BlockingQueue<Slot<T>> disposed = new LinkedBlockingQueue<>();

    /**
     * All slots in the pool, used when we shut down to dispose of all instances, as well as when there are no known
     * live pool objects, when we use this array to find slots cached by other threads
     */
    private final Slot<T>[] all;

    /** Max number of slots in the pool */
    private final int maxSize;

    /** While the pool is initially populating, this tracks indexes into the {@link #all} array */
    private final AtomicInteger nextSlotIndex = new AtomicInteger( 0 );

    /** Shutdown flag */
    private final AtomicBoolean stopped = new AtomicBoolean( false );

    private final Allocator<T> allocator;
    private final ValidationStrategy<T> validationStrategy;
    private final Clock clock;

    public ThreadCachingPool( int targetSize, Allocator<T> allocator, ValidationStrategy<T> validationStrategy,
            Clock clock )
    {
        this.maxSize = targetSize;
        this.allocator = allocator;
        this.validationStrategy = validationStrategy;
        this.clock = clock;
        this.all = new Slot[targetSize];
    }

    public T acquire( long timeout, TimeUnit unit ) throws InterruptedException
    {
        long deadline = clock.millis() + unit.toMillis( timeout );

        // 1. Try and get an object from our local slot
        Slot<T> slot = local.get();

        if ( slot != null && slot.availableToClaimed() )
        {
            if ( slot.isValid( validationStrategy ) )
            {
                allocator.onAcquire( slot.value );
                return slot.value;
            }
            else
            {
                // We've acquired the slot, but the validation strategy says it's time for it to die. Dispose of it,
                // and go to the global pool.
                dispose( slot );
            }
        }

        // 2. If that fails, acquire from big pool
        return acquireFromGlobal( deadline );
    }

    private T acquireFromGlobal( long deadline ) throws InterruptedException
    {
        Slot<T> slot = live.poll();

        for (; ; )
        {
            if ( stopped.get() )
            {
                throw new IllegalStateException( "Pool has been closed, cannot acquire new values." );
            }

            // 1. Check if the slot we pulled from the live queue is viable
            if ( slot != null )
            {
                // Yay, got a slot - can we keep it?
                if ( slot.availableToClaimed() )
                {
                    if ( slot.isValid( validationStrategy ) )
                    {
                        break;
                    }
                    else
                    {
                        // We've acquired the slot, but the validation strategy says it's time for it to die.
                        dispose( slot );
                    }
                }
            }
            else
            {
                // 2. Exhausted the likely-to-be-live list, are there any disposed-of slots we can recycle?
                slot = disposed.poll();
                if ( slot != null )
                {
                    // Got a hold of a previously disposed slot, it's place in the world for our purposes
                    all[slot.index] = slot = allocateNew( slot.index );
                    break;
                }

                // 3. Can we expand the pool?
                int index = nextSlotIndex.get();
                if ( maxSize > index && nextSlotIndex.compareAndSet( index, index + 1 ) )
                {
                    all[index] = slot = allocateNew( index );
                    break;
                }
            }

            // Enforce max wait time
            long timeLeft = deadline - clock.millis();
            if ( timeLeft <= 0 )
            {
                return null;
            }

            // Wait for a bit to see if someone releases something to the live queue
            slot = live.poll( Math.min( timeLeft, 10 ), MILLISECONDS );
        }

        // Keep this slot cached with our thread, so that we can grab this value quickly next time,
        // assuming threads generally availableToClaimed one instance at a time
        local.set( slot );
        allocator.onAcquire( slot.value );
        return slot.value;
    }

    private void dispose( Slot<T> slot )
    {
        if ( !slot.claimedToDisposed() )
        {
            throw new IllegalStateException( "Cannot dispose unclaimed pool object: " + slot );
        }

        // Done before below, in case dispose call fails. This is safe since objects on the
        // pool are used for read-only operations
        disposed.add( slot );
        allocator.onDispose( slot.value );

    }

    private Slot<T> allocateNew( int index )
    {
        final Slot<T> slot = new Slot<>( index, clock );

        slot.set( allocator.create( new Consumer<T>()
        {
            @Override
            public void accept( T t )
            {
                slot.updateUsageTimestamp();
                if ( !slot.isValid( validationStrategy ) )
                {
                    // The value has for some reason become invalid, dispose of it
                    dispose( slot );
                    return;
                }

                if ( !slot.claimedToAvailable() )
                {
                    throw new IllegalStateException( "Failed to release pooled object: " + slot );
                }

                // Make sure the pool isn't being stopped in the middle of all these shenanigans
                if ( !stopped.get() )
                {
                    // All good, as you were.
                    live.add( slot );
                }
                else
                {
                    // Another thread concurrently closing the pool may have started closing before we
                    // set our slot to "available". In that case, the slot will not be disposed of by the closing thread
                    // We mitigate this by trying to claim the slot back - if we are able to, we dispose the slot.
                    // If we can't claim the slot back, that means another thread is dealing with it.
                    if ( slot.availableToClaimed() )
                    {
                        dispose( slot );
                    }
                }
            }
        } ) );
        return slot;
    }

    @Override
    public void close()
    {
        if ( !stopped.compareAndSet( false, true ) )
        {
            return;
        }
        for ( Slot<T> slot : all )
        {
            if ( slot != null && slot.availableToClaimed() )
            {
                dispose( slot );
            }
        }
    }
}

/**
 * Stores one pooled resource, along with pooling metadata about it. Every instance the pool manages
 * has one of these objects, independent of if it's currently in use or if it is idle in the pool.
 */
class Slot<T>
{
    enum State
    {
        AVAILABLE,
        CLAIMED,
        DISPOSED
    }

    final AtomicReference<State> state = new AtomicReference<>( State.CLAIMED );
    final int index;
    final Clock clock;

    long lastUsed;
    T value;

    /**
     * @param index the index into the {@link ThreadCachingPool#all all} array, used to re-use that slot when this is
     * disposed
     */
    Slot( int index, Clock clock )
    {
        this.index = index;
        this.clock = clock;
        this.lastUsed = 0;
    }

    public void set( T value )
    {
        this.value = value;
    }

    public boolean availableToClaimed()
    {
        return state.compareAndSet( State.AVAILABLE, State.CLAIMED );
    }

    public boolean claimedToAvailable()
    {
        updateUsageTimestamp();
        return state.compareAndSet( State.CLAIMED, State.AVAILABLE );
    }

    public boolean claimedToDisposed()
    {
        return state.compareAndSet( State.CLAIMED, State.DISPOSED );
    }

    public void updateUsageTimestamp()
    {
        lastUsed = clock.millis();
    }

    boolean isValid( ValidationStrategy<T> strategy )
    {
        return strategy.isValid( value, clock.millis() - lastUsed );
    }

    @Override
    public String toString()
    {
        return "Slot{" +
               "value=" + value +
               ", lastUsed=" + lastUsed +
               ", index=" + index +
               ", state=" + state.get() +
               '}';
    }
}