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

public interface BucketSizer {
    abstract public long sizeToIndex(long size);
    abstract public long indexToCapacity(long index);
    
    public static BucketSizer byFactor(long factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("Non-positive factor: " + factor);
        }
        return new BucketSizer() {
            @Override
            public long sizeToIndex(long size) {
                if (size < 0) {
                    throw new IllegalArgumentException("Negative size: " + size);
                }
                long bucket = size / factor;
                if (size % factor > 0)
                    ++bucket;
                return bucket;
            }
            
            @Override
            public long indexToCapacity(long index) {
                if (index < 0) {
                    throw new IllegalArgumentException("Negative index: " + index);
                }
                return index * factor;
            }
        };
    }
}
