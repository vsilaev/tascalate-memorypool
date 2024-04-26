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
package net.tascalate.memory.nio;

import java.nio.ByteBuffer;

import net.tascalate.memory.MemoryResourceHandler;

public abstract class ByteBufferHandler implements MemoryResourceHandler<ByteBuffer> {

    @Override
    public long capacityOf(ByteBuffer resource) {
        return resource.capacity();
    }
    
    @Override 
    public void setup(ByteBuffer resource, long size, boolean afterCreate) {
        if (resource != null) {
            resource.position(0);
            if (size > 0) {
                resource.limit(verifySizeParam(size));
            }
        }
    }
    
    protected static int verifySizeParam(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("Invalid size/capacity " + size);
        }
        if (size >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid size/capacity " + size + ", should not exceed " + Integer.MAX_VALUE);
        }
        return (int)size;
    }

}
