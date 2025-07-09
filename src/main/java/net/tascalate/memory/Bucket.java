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

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.LongConsumer;

class Bucket<T> {
    private final Deque<T> queue = new ConcurrentLinkedDeque<>();
    private final MemoryResourceHandler<T> handler;
    private final long entryCapacity;
    
    private final LongConsumer totalPoolSizeUpdater;

    Bucket(MemoryResourceHandler<T> handler, long entryCapacity, LongConsumer totalPoolSizeUpdater) {
        this.handler = handler;
        this.entryCapacity = entryCapacity;
        this.totalPoolSizeUpdater = totalPoolSizeUpdater;
    }

    T acquire(long size, boolean mayCreate) {
        if (size > entryCapacity) {
            throw new IllegalArgumentException("Size must not exceed entry capacity: " + size + " > " + entryCapacity);
        }
        T resource = queuePoll();
        
        if (resource == null) {
            if (mayCreate) {
                resource = handler.create(entryCapacity);
                handler.setup(resource, size, true);
                return resource;
            } else {
                return null;
            }
        } else {
            totalPoolSizeUpdater.accept(-entryCapacity);
            handler.setup(resource, size, false);
            return resource;
        }
    }

    boolean release(T resource, boolean mayPool) {
        if (!mayPool) {
            handler.cleanup(resource, true);
            handler.destroy(resource);
            return false;
        }
        
        handler.cleanup(resource, false);
        boolean queued = queueOffer(resource);
        if (queued) {
            totalPoolSizeUpdater.accept(+entryCapacity);
        }
        return queued;
    }
    
    long clear(long minSpaceToRelease) {
        long releasedSpace = 0;
        int removed = 0;
        T resource;
        try {
            while ((resource = queuePoll()) != null) {
                removed++;
                releasedSpace += handler.capacityOf(resource);
                handler.destroy(resource);
    
                if (releasedSpace >= minSpaceToRelease) {
                    break;
                }
            }
        } finally {
            totalPoolSizeUpdater.accept(-removed * entryCapacity);
        }
        return releasedSpace;
    }

    void clear() {
        T resource;
        int removed = 0;
        try {
            while ((resource = queuePoll()) != null) {
                removed++;
                handler.destroy(resource);
            }
        } finally {
            totalPoolSizeUpdater.accept(-removed * entryCapacity);
        }
    }

    boolean hasPooled() {
        return !queue.isEmpty();
    }
    
    long entryCapacity() {
        return entryCapacity;
    }

    private boolean queueOffer(T resource) {
        return queue.offerFirst(resource);
    }

    private T queuePoll() {
        return queue.poll();
    }

    @Override
    public String toString() {
        return String.format("BucketsChain@%x{%d/%d}", hashCode(), queue.size(), entryCapacity);
    }
}
