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
    
    default BucketSizer withMinCapacity(long minCapacity) {
        if (minCapacity <= 0) {
            throw new IllegalArgumentException("Negative minCapacity: " + minCapacity);
        }
        BucketSizer delegate = this;
        long idxShift = delegate.sizeToIndex(minCapacity);
        return new BucketSizer() {
            @Override
            public long sizeToIndex(long size) {
                return delegate.sizeToIndex(Math.max(size, minCapacity)) - idxShift;
            }
            
            @Override
            public long indexToCapacity(long index) {
                return delegate.indexToCapacity(index + idxShift);
            }
        };
    }
    
    default BucketSizer withAlignment(long alignment) {
        BucketSizer delegate = this;
        return new BucketSizer() {
            @Override
            public long sizeToIndex(long size) {
                return delegate.sizeToIndex(size);
            }
            
            @Override
            public long indexToCapacity(long index) {
                long result = delegate.indexToCapacity(index);
                long reminder = result % alignment;
                return reminder == 0 ? result : ((long)(result / alignment) + 1) * alignment;
            }
        };
    }
    
    public static BucketSizer exponential(double factor) {
        if (factor <= 1.0) {
            throw new IllegalArgumentException("Factor must be greater than 1.0: " + factor);
        }
        return new BucketSizer() {
            @Override
            public long sizeToIndex(long size) {
                if (size < 0) {
                    throw new IllegalArgumentException("Negative size: " + size);
                }
                long correctedSize = Math.max(size, 1);
                long bucket = (long) (Math.log(correctedSize) / Math.log(factor));
                if (Math.pow(factor, bucket)  < correctedSize)
                    ++bucket;
                return bucket;
            }
            
            @Override
            public long indexToCapacity(long index) {
                if (index < 0) {
                    throw new IllegalArgumentException("Negative index: " + index);
                }
                return (long)(Math.pow(factor, index));
            }
        };
    }
    
    public static BucketSizer linear(long multiplier) {
        if (multiplier <= 0) {
            throw new IllegalArgumentException("Multiplier must be a positive integer: " + multiplier);
        }
        return new BucketSizer() {
            @Override
            public long sizeToIndex(long size) {
                if (size < 0) {
                    throw new IllegalArgumentException("Negative size: " + size);
                }
                long bucket = size / multiplier;
                if (size % multiplier > 0)
                    ++bucket;
                return bucket;
            }
            
            @Override
            public long indexToCapacity(long index) {
                if (index < 0) {
                    throw new IllegalArgumentException("Negative index: " + index);
                }
                return index * multiplier;
            }
        };
    }
}
