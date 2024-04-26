/**
 * Copyright 2023 Valery Silaev (http://vsilaev.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.tascalate.memory;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


public class MemoryResourcePool<T> {
    private final ReentrantLock lock = new ReentrantLock();
    // Store buckets in reversed index order, i.e. large chunks will be released first
    private final NavigableMap<Long, Bucket<T>> buckets = new TreeMap<>(Comparator.<Long>naturalOrder().reversed());
    private final Deque<Condition> waiters = new ArrayDeque<>();
    
    private final MemoryResourceHandler<T> handler;
    private final long totalCapacity;
    private final long poolableCapacity;
    private final BucketSizer bucketSizer;

    private long notPooledCapacity;
    private boolean closed;
    
    public MemoryResourcePool(MemoryResourceHandler<T> handler, long totalCapacity) {
        this(handler, totalCapacity, -1);
    }
    
    public MemoryResourcePool(MemoryResourceHandler<T> handler, long totalCapacity, BucketSizer bucketSizer) {
        this(handler, totalCapacity, -1, bucketSizer);
    }
    
    public MemoryResourcePool(MemoryResourceHandler<T> handler, long totalCapacity, double bucketCapacityFactor) {
        this(handler, totalCapacity, totalCapacity, bucketCapacityFactor);
    }
    
    public MemoryResourcePool(MemoryResourceHandler<T> handler, long totalCapacity, long poolableCapacity, double bucketCapacityFactor) {
        this(handler, totalCapacity, poolableCapacity, 
             BucketSizer.exponential(bucketCapacityFactor < 0 ? 
                                         suggestBucketFactor(poolableCapacity, poolableCapacity <= 1024 * 1024 ? 16 : 32, 2.0) 
                                         : 
                                         bucketCapacityFactor)
                        .withAlignment(64));
    }
    
    public MemoryResourcePool(MemoryResourceHandler<T> handler, long totalCapacity, long poolableCapacity, BucketSizer bucketSizer) {
        if (totalCapacity <= 0) {
            throw new IllegalArgumentException("Invalid total pool capacity: " + totalCapacity);
        }
        if (poolableCapacity < 0) {
            poolableCapacity = totalCapacity;
        }
        if (poolableCapacity > totalCapacity) {
            throw new IllegalArgumentException("Invalid poolable pool capacity: " + poolableCapacity + "(bigger than total capacity: " + totalCapacity + ")");
        }
        
        this.totalCapacity = totalCapacity;
        this.poolableCapacity = poolableCapacity;
        this.bucketSizer = bucketSizer;
        this.notPooledCapacity = totalCapacity;
        this.handler = new MemoryResourceHandler.Delegate<T>(handler) {
            public T create(long size, long capacity) {
                boolean error = true;
                try {
                    T resource = delegate.create(size, capacity);
                    error = false;
                    return resource;
                } finally {
                    if (error) {
                        lock.lock();
                        try {
                            notPooledCapacity += size;
                            signalFirstWaiter(true);
                        } finally {
                            lock.unlock();
                        }
                    }
                }
            }
        };
    }
    
    public T acquire(long size) throws InterruptedException {
        return acquire(size, ConditionWaiter.unlimited());
    }
    
    public T acquire(long size, long maxTimeToWaitMillis) throws InterruptedException {
        return acquire(size, ConditionWaiter.withMaxWaitTimeMillis(maxTimeToWaitMillis));
    }
    
    public T acquire(long size, long maxTimeToWait, TimeUnit timeUnit) throws InterruptedException {
        return acquire(size, ConditionWaiter.withMaxWaitTime(maxTimeToWait, timeUnit));
    }
    
    T acquire(long size, ConditionWaiter conditionWaiter) throws InterruptedException {
        long originalSize = size; // save actual value for the later use
        
        if (size > totalCapacity) {
            throw new IllegalArgumentException(
                "Unable to allocate requested " + size + " bytes in the pool of total capacity " + totalCapacity
            );
        }
        
        size = adjustAllocationSize(size);
        if (size < originalSize) {
            throw new IllegalArgumentException(
                "Invalid implementation, adjsuted size (" + size + ") may not be less that originally requested size (" + originalSize + ")"
            );
        }
        
        if (size > totalCapacity) {
            throw new IllegalArgumentException(
                "Unable to allocate " + size + " bytes (adjusted, originally requested " + originalSize +" bytes) in the pool of total capacity " + totalCapacity
            );
        }

        T resource = null;
        lock.lock();

        if (closed) {
            lock.unlock();
            throw new IllegalStateException(getClass().getSimpleName() + " is closed");
        }

        Bucket<T> bucket = bucketFor(size); 
        long alignedSize = bucket.entryCapacity(); // alignedSize >= size
        try {
            // check if we have a free buffer of the right size pooled
            resource = bucket.acquire(originalSize, false);
            if (null != resource) {
                return resource;
            }

            // now check if the request is immediately satisfiable with the
            // memory on hand or if we need to block
            long availableCapacity = availableCapacityUnsafe(); // notPooledCapacity + pooledCapacity
            if (availableCapacity >= alignedSize) {
                // Prefer to have aligned size - this way we can pool
                size = alignedSize;
            }
            if (availableCapacity >= size) {
                // we have enough unallocated or pooled memory to immediately
                // satisfy the request, but need to allocate the buffer
                releasePooledEntries(size);
                notPooledCapacity -= size;
            } else {
                // we are out of memory and will have to block
                long accumulated = 0;
                Condition needSpace = lock.newCondition();
                try {
                    waiters.addLast(needSpace);
                    // loop over and over until we have a buffer or have reserved
                    // enough memory to allocate one
                    while (accumulated < size) {
                        conditionWaiter.awaitNext(needSpace);

                        if (closed)
                            throw new IllegalStateException(getClass().getSimpleName() + " was closed concurrently");
                        
                        conditionWaiter.checkTimeElapsed();

                        // check if we can satisfy this request from the free list,
                        // otherwise allocate memory
                        if (accumulated == 0L) {
                            resource = bucket.acquire(originalSize, false);
                        }
                        if (resource != null) {
                            accumulated = size;
                        } else {
                            // we'll need to allocate memory, but we may only get
                            // part of what we need on this iteration
                            releasePooledEntries(size - accumulated);
                            int got = (int) Math.min(size - accumulated, notPooledCapacity);
                            notPooledCapacity -= got;
                            accumulated += got;
                        }
                    }
                    // Don't reclaim memory on throwable since nothing was thrown
                    accumulated = 0;
                } finally {
                    // When this loop was not able to successfully terminate don't loose available memory
                    notPooledCapacity += accumulated;
                    waiters.remove(needSpace);
                }
            }
        } finally {
            // signal any additional waiters 
            // if there is more capacity left available
            try {
                signalFirstWaiter(false);
            } finally {
                lock.unlock();
            }
        }

        if (resource == null) {
            return bucket.acquire(originalSize, true);
        }
        return resource;
    }
    
    public void release(T resource) {
        if (resource == null) {
            return;
        }
        lock.lock();
        long capacity = handler.capacityOf(resource);
        try {
            Bucket<T> bucket = bucketFor(capacity);
            if (capacity == bucket.entryCapacity() && bucket.release(resource, mayPool(resource, capacity))) {
                // pooled
            } else {
                notPooledCapacity += capacity;
            }
            signalFirstWaiter(true);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * You may adjust the requested allocation size in "not smaller that" manner, or make it aligned by certain bytes boundary.
     * By default adjusted size equals to the requested size
     * @param requestedSize the size requested by the pool client
     * @return
     */
    protected long adjustAllocationSize(long requestedSize) {
        return requestedSize;
    }
    
    protected boolean mayPool(T resource, long capacity) {
        return totalCapacity - notPooledCapacity + capacity <= poolableCapacity;
    }
    
    /**
     * The number of threads blocked waiting on memory
     */
    public int queued() {
        lock.lock();
        try {
            return waiters.size();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * the total free memory both unallocated and in the free list
     */
    public long availableCapacity() {
        lock.lock();
        try {
            return availableCapacityUnsafe();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get the free memory capacity (not in use or not pooled)
     */
    public long unusedCapacity() {
        lock.lock();
        try {
            return notPooledCapacity;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The max possible capacity of resources stored in the pool
     */
    public long poolableCapacity() {
        return poolableCapacity;
    }

    /**
     * The total capacity of the pool
     */
    public long totalCapacity() {
        return totalCapacity;
    }

    /**
     * Closes the pool. Memory resources may not be longer allocated after this call, but may be deallocated. 
     * All threads awaiting for free memory will be notified to abort.
     */
    public void close() {
        close(true);
    }
    
    protected void close(boolean destroyPool) {
        lock.lock();
        closed = true;
        try {
            waiters.forEach(Condition::signal);
            if (destroyPool) {
                releasePooledEntries(totalCapacity);
                buckets.clear();
            }
        } finally {
            lock.unlock();
        }
    }
    
    private Bucket<T> bucketFor(long capacity) {
        long idx = bucketSizer.sizeToIndex(capacity);
        return buckets.computeIfAbsent(idx, v -> new Bucket<>(handler, bucketSizer.indexToCapacity(v.longValue())));
    }

    /**
     * Attempt to ensure we have at least the requested number of bytes of memory for allocation by deallocating pooled
     * buffers (if needed)
     */
    private void releasePooledEntries(long requiredCapacity) {
        long capacityShortage = requiredCapacity - notPooledCapacity;
        if (capacityShortage >= 0) {
            for (Bucket<T> b : buckets.values()) {
                if (notPooledCapacity >= requiredCapacity) {
                    break;
                }
                notPooledCapacity += b.clear(capacityShortage);
            }
        }
    }
    
    private void signalFirstWaiter(boolean forceSignal) {
        Condition needSpace = waiters.peekFirst(); 
        // We have waiters
        if (needSpace != null) {
            // ...and we have some free capacity for them
            if (forceSignal || notPooledCapacity > 0 || hasPooledEntries()) { 
                needSpace.signal();
            }
        }        
    }
    
    private boolean hasPooledEntries() {
        return buckets.values()
                      .stream()
                      .filter(b -> b.totalCapacity() > 0)
                      .findAny().isPresent();
    }
    
    private long availableCapacityUnsafe() {
        return notPooledCapacity + 
               buckets.values()
                      .stream()
                      .mapToLong(Bucket::totalCapacity)
                      .sum();        
    }
    
    private static double suggestBucketFactor(long poolableCapacity, int steps, double minFactor) {
        long normalizedMaxValue = Math.min(poolableCapacity, Integer.MAX_VALUE);
        double factor = Math.log(normalizedMaxValue) / Math.log(steps);
        return Math.max(factor, minFactor);
    }

}
