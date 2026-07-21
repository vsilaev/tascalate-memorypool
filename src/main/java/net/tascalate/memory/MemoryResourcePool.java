/**
 * Copyright 2023-2026 Valery Silaev (http://vsilaev.com)
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

import java.util.concurrent.TimeUnit;

public interface MemoryResourcePool<T> extends AutoCloseable {

    T acquire(long size) throws InterruptedException;

    T acquire(long size, long maxTimeToWaitMillis) throws InterruptedException;

    T acquire(long size, long maxTimeToWait, TimeUnit timeUnit) throws InterruptedException;

    void release(T resource);

    /**
     * The number of threads blocked waiting on memory
     */
    int queued();

    /**
     * the total free memory both unallocated and in the free list
     */
    long availableCapacity();

    /**
     * Get the free memory capacity (not in use or not pooled)
     */
    long unusedCapacity();

    /**
     * The max possible capacity of resources stored in the pool
     */
    long poolableCapacity();

    /**
     * The total capacity of the pool
     */
    long totalCapacity();

    long reclaim(long bytesToRelease);

    /**
     * Closes the pool. Memory resources may not be longer allocated after this call, but may be deallocated. 
     * All threads awaiting for free memory will be notified to abort.
     */
    void close();
    
    public static class Delegate<T> implements MemoryResourcePool<T> {
        private final MemoryResourcePool<T> delegate;
        
        protected Delegate(MemoryResourcePool<T> delegate) {
            this.delegate = delegate;
        }

        public T acquire(long size) throws InterruptedException {
            return delegate.acquire(size);
        }

        public T acquire(long size, long maxTimeToWaitMillis) throws InterruptedException {
            return delegate.acquire(size, maxTimeToWaitMillis);
        }

        public T acquire(long size, long maxTimeToWait, TimeUnit timeUnit) throws InterruptedException {
            return delegate.acquire(size, maxTimeToWait, timeUnit);
        }

        public void release(T resource) {
            delegate.release(resource);
        }

        public int queued() {
            return delegate.queued();
        }

        public long availableCapacity() {
            return delegate.availableCapacity();
        }

        public long unusedCapacity() {
            return delegate.unusedCapacity();
        }

        public long poolableCapacity() {
            return delegate.poolableCapacity();
        }

        public long totalCapacity() {
            return delegate.totalCapacity();
        }

        public long reclaim(long bytesToRelease) {
            return delegate.reclaim(bytesToRelease);
        }

        public void close() {
            delegate.close();
        }
    }

}