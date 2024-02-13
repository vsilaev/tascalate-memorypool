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

public interface MemoryResourceHandler<T> {
    abstract public T create(long size, long capacity);
    abstract public void destroy(T resource);
    abstract public long capacityOf(T resource);
    
    default public void reset(T resource, boolean acquired, long size) {
        
    }
    
    public static class Delegate<T> implements MemoryResourceHandler<T> {
        protected final MemoryResourceHandler<T> delegate;
        
        public Delegate(MemoryResourceHandler<T> delegate) {
            this.delegate = delegate;
        }
        
        public T create(long size, long capacity) {
            return delegate.create(size, capacity);
        }
        
        public void destroy(T resource) {
            delegate.destroy(resource);
        }
        
        public long capacityOf(T resource) {
            return delegate.capacityOf(resource);
        }
        
        public void reset(T resource, boolean acquired, long size) {
            delegate.reset(resource, acquired, size);
        }
    }
}
