[![Maven Central](https://img.shields.io/maven-central/v/net.tascalate/net.tascalate.memorypool.svg)](https://search.maven.org/artifact/net.tascalate/net.tascalate.memorypool/0.9.2/jar) [![GitHub release](https://img.shields.io/github/release/vsilaev/tascalate-memorypool.svg)](https://github.com/vsilaev/tascalate-memorypool/releases/tag/0.9.2) [![license](https://img.shields.io/github/license/vsilaev/tascalate-memorypool.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)
# tascalate-memorypool

The `MemoryResourcePool` is a highly configurable, space-limited resource manager designed for efficient memory lifecycle management across various domains, including native RAM, NIO direct buffers, or even GPU memory. It provides strict, predictable control over memory consumption by allowing you to define a `totalCapacity` (the absolute maximum amount of memory the pool is permitted to allocate from the system) and a `poolableCapacity` (the maximum amount of that memory retained internally for reuse). This dual-limit design ensures that your application never exceeds its designated memory footprint, effectively preventing out-of-memory errors while maximizing resource utilization.

To optimize performance and minimize allocation overhead, the pool intelligently recycles memory buffers using a configurable `BucketSizer`. Instead of returning memory to the operating system immediately upon release, buffers are categorized into size-aligned buckets (e.g., using exponential sizing with specific minimum capacities and memory alignments) for rapid future reuse. Combined with a flexible Builder API and pluggable `MemoryResourceHandler` implementations, the `MemoryResourcePool` can be finely tuned to match the exact performance requirements of your application, whether you are targeting legacy Java 8 environments or modern Java 22+ with the Foreign Function & Memory (FFM) API.

> **🚀 Project Loom & Virtual Thread Compatibility (Pin-Free)**  
> This implementation is explicitly designed to be **pin-free** when used with Java Virtual Threads (Project Loom). Traditional resource pools often rely on `synchronized` blocks or methods, which can cause carrier thread pinning and severely degrade the scalability of virtual threads. 
> 
> In contrast, `MemoryResourcePool` relies exclusively on `java.util.concurrent.locks.ReentrantLock` and `java.util.concurrent.ConcurrentLinkedDeque` for its internal concurrency control. Because these specific primitives do not pin virtual threads to their carrier threads, you can safely acquire and release memory resources from thousands of concurrent virtual threads without sacrificing the massive scalability benefits of Project Loom.

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

With Java versions 1.8 to JDK 21 inclusive you can use a memory pool of the `java.nio.ByteBuffer`:
```java
package memory.pool.test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import net.tascalate.memory.BucketSizer;
import net.tascalate.memory.DefaultMemoryResourcePool;
import net.tascalate.memory.MemoryResourceHandler;
import net.tascalate.memory.MemoryResourcePool;
import net.tascalate.memory.nio.DirectByteBufferHandler;

public class NativeMemoryDemo {

    public static void main(String[] argv) throws Throwable {
        int MAX_ITERATIONS = 500;
        long MAX_MEMORY = 1024 * 1024 * 800;
        // Or smth. more realistic like
        // Runtime.getRuntime().maxMemory() / 2;

        //Handler for native system memory using direct ByteBuffer
        MemoryResourceHandler<ByteBuffer> handler = DirectByteBufferHandler.instance();

        try (MemoryResourcePool<ByteBuffer> pool = DefaultMemoryResourcePool.<ByteBuffer>
            builder()
            .handler(handler)
            .totalCapacity(MAX_MEMORY)
            .poolableCapacity(MAX_MEMORY / 2) // we can pool up to totalCapacity
            .bucketSizer(BucketSizer.exponential(2)
                                    .withMinCapacity(512)
                                    .withAlignment(64))
            .build()) {

            AtomicInteger idx = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(MAX_ITERATIONS);

            ExecutorService executor = Executors.newFixedThreadPool(20);
            try {
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    executor.submit(() -> {
                        ByteBuffer b;
                        try {

                            // ACQUIRE MEMORY RESOURCE
                            b = pool.acquire((long)(Math.random() * 1024 * 1024));

                            try {
                                System.out.println("[" + idx.getAndIncrement() + "] " +
                                                   Thread.currentThread().getName() + " " + b);

                                // USE MEMORY RESOURCE
                                b.put(0, (byte)42);

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
            } finally {
                executor.shutdown();
            }
        }
    }
}
```
To run the latest example you should enable reflection on the internal JDK classes.
For non-modular applications with Java 9+:
```
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED 
--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED 
```

For modular applications with Java 9+
```
--add-opens=java.base/sun.nio.ch=net.tascalate.memorypool
--add-opens=java.base/jdk.internal.ref=net.tascalate.memorypool
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
