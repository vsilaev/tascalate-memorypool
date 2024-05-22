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
    abstract public T create(long capacity);
    abstract public void destroy(T resource);
    abstract public long capacityOf(T resource);
    
    default public void setup(T resource, long size, boolean afterCreate) {
        
    }
    
    default public void cleanup(T resource, boolean beforeDestroy) {
        
    }
    
    public static class Delegate<T> implements MemoryResourceHandler<T> {
        protected final MemoryResourceHandler<T> delegate;
        
        public Delegate(MemoryResourceHandler<T> delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public T create(long capacity) {
            return delegate.create(capacity);
        }
        
        @Override
        public void destroy(T resource) {
            delegate.destroy(resource);
        }
        
        @Override
        public long capacityOf(T resource) {
            return delegate.capacityOf(resource);
        }
        
        @Override
        public void setup(T resource, long size, boolean afterCreate) {
            delegate.setup(resource, size, afterCreate);
        }
        
        @Override
        public void cleanup(T resource, boolean beforeDestroy) {
            delegate.cleanup(resource, beforeDestroy);
        }

    }
}
