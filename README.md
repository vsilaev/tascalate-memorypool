[![Maven Central](https://img.shields.io/maven-central/v/net.tascalate/net.tascalate.memorypool.svg)](https://search.maven.org/artifact/net.tascalate/net.tascalate.memorypool/0.9.2/jar) [![GitHub release](https://img.shields.io/github/release/vsilaev/tascalate-memorypool.svg)](https://github.com/vsilaev/tascalate-memorypool/releases/tag/0.9.2) [![license](https://img.shields.io/github/license/vsilaev/tascalate-memorypool.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)
# tascalate-memorypool
Tascalate MemoryPool

Example usage (Java 22+ required, using native memory as `MemorySegment`):
```java
package memory.pool.test;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import net.tascalate.memory.BucketSizer;
import net.tascalate.memory.DefaultMemoryResourcePool;
import net.tascalate.memory.ffm.NativeMemory;
import net.tascalate.memory.ffm.NativeMemoryHanlder;

public class NativeMemoryDemo {

    public static void main(String[] argv) throws Throwable {
        int MAX_ITERATIONS = 500;
        long MAX_MEMORY = 1024 * 1024 * 800;
        // Or smth. more realistic like
        // Runtime.getRuntime().maxMemory() / 2;

        //Handler for native system memory using FFM
        var handler = new NativeMemoryHanlder(64 /* alignment */);

        try (var pool = DefaultMemoryResourcePool.<NativeMemory>
            builder()
            .handler(handler)
            .totalCapacity(MAX_MEMORY)
            .poolableCapacity(MAX_MEMORY / 2) // we can pool up to totalCapacity
            .bucketSizer(BucketSizer.exponential(2)
                                    .withMinCapacity(512)
                                    .withAlignment(64))
            .build();

            var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            var idx = new AtomicInteger();
            var latch = new CountDownLatch(MAX_ITERATIONS);

            for (int i = 0; i < MAX_ITERATIONS; i++) {
                executor.submit(() -> {
                    NativeMemory b;
                    try {

                        // ACQUIRE MEMORY RESOURCE
                        b = pool.acquire((long)(Math.random() * 1024 * 1024));

                        try {
                            System.out.println("[" + idx.getAndIncrement() + "] " +
                                               Thread.currentThread().getName() + " " + b);

                            // USE MEMORY RESOURCE
                            b.segment().set(ValueLayout.JAVA_BYTE, 0, (byte)42);

                            Thread.sleep((long)(Math.random() * 10));
                        } finally {
                            // RELEASE MEMORY RESOURCE
                            pool.release(b);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });

            }
            latch.await();
        }
    }
}
```
The `BucketSizer` defines what will be an actual capacity of the poolable memory region, for example:
```java
        var bx = BucketSizer.exponential(2)
                 .withMinCapacity(512)
                 .withAlignment(64);
        for (int i = 0; i < 10; i++) {
            System.out.println(bx.indexToCapacity(i));
        }
```
This prints the following:
```
Bucket #0 = 512
Bucket #1 = 1024
Bucket #2 = 2048
Bucket #3 = 4096
Bucket #4 = 8192
Bucket #5 = 16384
Bucket #6 = 32768
Bucket #7 = 65536
Bucket #8 = 131072
Bucket #9 = 262144
```

So if you requested a `MemorySegment` of the size 15 000 bytes, then  16 384 bytes will be actually allocated, and when you `release` this `MemorySegment` it will be stored in the bucket #5 for the later reuse.
