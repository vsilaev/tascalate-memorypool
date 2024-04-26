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

public class HeapByteBufferHandler extends ByteBufferHandler {
    
    protected HeapByteBufferHandler() {
        
    }
    
    @Override
    public ByteBuffer create(long size, long capacity) {
        return ByteBuffer.allocate(verifySizeParam(capacity));
    }
    

    @Override
    public void destroy(ByteBuffer resource) {
        
    }
    
    public static HeapByteBufferHandler instance() {
        return INSTANCE;
    }
    
    private static final HeapByteBufferHandler INSTANCE = new HeapByteBufferHandler();

}
