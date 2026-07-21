/**
 * Copyright 2026 Valery Silaev (http://vsilaev.com)
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
package net.tascalate.memory.ffm;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import net.tascalate.memory.MemoryResourcePool;

public class MemorySegmentPool implements MemoryResourcePool<MemorySegment> {
    private final MemoryResourcePool<NativeMemory> delegate;
    private final Map<MemorySegment, NativeMemory> remapping = new ConcurrentHashMap<>();
    
    private MemorySegmentPool(MemoryResourcePool<NativeMemory> delegate) {
        this.delegate = delegate;
    }

    @Override
    public MemorySegment acquire(long size) throws InterruptedException {
        return enlist(delegate.acquire(size));
    }

    @Override
    public MemorySegment acquire(long size, long maxTimeToWaitMillis) throws InterruptedException {
        return enlist(delegate.acquire(size, maxTimeToWaitMillis));
    }

    @Override
    public MemorySegment acquire(long size, long maxTimeToWait, TimeUnit timeUnit) throws InterruptedException {
        return enlist(delegate.acquire(size, maxTimeToWait, timeUnit));
    }

    @Override
    public void release(MemorySegment segment) {
        var resource = remapping.remove(segment);
        if (null == segment) {
            throw new IllegalArgumentException("Resource does not belong to the pool");
        }
        delegate.release(resource);
    }

    @Override
    public int queued() {
        return delegate.queued();
    }

    @Override
    public long availableCapacity() {
        return delegate.availableCapacity();
    }

    @Override
    public long unusedCapacity() {
        return delegate.unusedCapacity();
    }

    @Override
    public long poolableCapacity() {
        return delegate.poolableCapacity();
    }

    @Override
    public long totalCapacity() {
        return delegate.totalCapacity();
    }

    @Override
    public long reclaim(long bytesToRelease) {
        return delegate.reclaim(bytesToRelease);
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            remapping.clear();
        }
        
    }
    
    private MemorySegment enlist(NativeMemory m) {
        var segment = m.segment();
        var previous = remapping.put(segment, m);
        if (null != previous) {
            throw new IllegalStateException();
        }
        return segment;
        
    }
    
    public static MemoryResourcePool<MemorySegment> of(MemoryResourcePool<NativeMemory> wrapped) {
        return new MemorySegmentPool(wrapped);
    }
}
