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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import net.tascalate.memory.core.CleanerMethodsCache;
import net.tascalate.memory.nio.DirectByteBufferHandler;

public class App {

    public static void main(String[] argv) throws Exception {
        testMin();
        BucketSizer bs = BucketSizer.exponential(2).withMinCapacity(512).withAlignment(256);
        for (long s = 17; s < 11330; s+=200 ) {
            long idv = bs.sizeToIndex(s);
            long capacity = bs.indexToCapacity(idv);
            System.out.println("Size = " + s + ", idx = " + idv + ", capacity = " + capacity);
        }

        long maxMemory = 1024 * 1024 * 40;//Runtime.getRuntime().maxMemory();
        MemoryResourcePool<ByteBuffer> pool = new MemoryResourcePool<>(
            DirectByteBufferHandler.instance(), 
            maxMemory / 4, // 1024 * 1024 * 1024, 
            maxMemory / 8, //  800 * 1024 * 1024, 
            BucketSizer.exponential(2).withMinCapacity(512).withAlignment(64)
        );

        for (int k = 0;  k < 3; k++) {
            ByteBuffer bb = DirectByteBufferHandler.instance().create(1024);
            DirectByteBufferHandler.instance().destroy(bb);
            Buffer bx = bb.slice().asCharBuffer();
            CleanerMethodsCache.cleanerOf(bx.getClass(), true).accept(bx);
            
        }
        
        ExecutorService svc = Executors.newFixedThreadPool(100);
        AtomicInteger idx = new AtomicInteger();
        int MAX_ITERATIONS = 10000;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            svc.submit(() -> {
                ByteBuffer b;
                try {
                    int id = idx.getAndIncrement();
                    b = pool.acquire((long)(Math.random() * 1024 * 1024));
                    try {
                        System.out.println("[" + id + "] " + Thread.currentThread().getName() + " " + b);
                        b.put((byte) 11);
                        Thread.sleep((long)(Math.random() * 100));
                    } finally {
                        pool.release(b);
                        
                        if (id == MAX_ITERATIONS - 1) {
                            svc.submit(() -> pool.close());
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } 
            });
        }
        svc.shutdown();
        System.out.println();
    }
    
    public static void testMin() throws Exception {
        BucketSizer bs = BucketSizer.linear(4);
        MemoryResourcePool<ByteBuffer> pool = new MemoryResourcePool<>(
            DirectByteBufferHandler.instance(), 6, 6, bs
        );
        System.out.println(pool.availableCapacity());
        ByteBuffer b1 = pool.acquire(4);
        ByteBuffer b2 = pool.acquire(2);
        System.out.println(pool.availableCapacity());
        pool.release(b1);
        pool.release(b2);
        System.out.println(pool.availableCapacity());
        pool.close();
    }
}
