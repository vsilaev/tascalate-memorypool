package net.tascalate.memory.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

final class NamedLocks<K> {
    private final ConcurrentMap<K, Lock> locksByKey = new ConcurrentHashMap<>();

    public Lock acquire(K key) throws InterruptedException {
        Lock ourLock = Lock.acquire(() -> locksByKey.remove(key));
        while (true) {
            Lock theirLock = locksByKey.putIfAbsent(key, ourLock);
            if (theirLock == null) {
                // No other locks, we are the owner
                return ourLock;
            }
            if (theirLock.tryAcquire(false)) {
                // Reentrant call
                return theirLock;
            }
            // Wait for other lock release and re-try
            theirLock.await();
        }
    }

    final static class Lock implements AutoCloseable {
        private final CountDownLatch mutex;
        private final long threadId;
        private final Runnable cleanup;
        private int lockedCount = 1;

        private Lock(long threadId, Runnable cleanup) {
            this.threadId = threadId;
            this.cleanup = cleanup;
            this.mutex = new CountDownLatch(1);
        }
        
        static Lock acquire(Runnable cleanup) {
            return new Lock(currentThreadId(), cleanup);
        }
        
        boolean sameThread(long currentThreadId, boolean throwError) {
            if (currentThreadId != threadId) {
                if (throwError) {
                    return invalidThreadContext("The lock modified from the thread " + currentThreadId + " but was accuried in the thread " + threadId);
                } else {
                    return false;
                }
            } else {
                return true;
            }
            
        }
        
        void await() throws InterruptedException {
            mutex.await();
        }
        
        boolean tryAcquire(boolean throwError) {
            long currentThreadId = currentThreadId();
            if (threadId != currentThreadId) {
                if (throwError) {
                    return invalidThreadContext("Trying to re-acquire lock from the thread " + currentThreadId + " but it was accuried in the thread " + threadId);
                } else {
                    return false;
                }
            }
            lockedCount++;
            return true;
        }
        
        boolean tryRelease(boolean throwError) {
            long currentThreadId = currentThreadId();
            if (threadId != currentThreadId) {
                if (throwError) {
                    return invalidThreadContext("Trying to release lock from the thread " + currentThreadId + " but it was accuried in the thread " + threadId);
                } else {
                    return false;
                }
            }
            if (lockedCount < 1) {
                return false;
            } else if (--lockedCount == 0) {
                cleanup.run();
                mutex.countDown();
                return true;
            } else {
                return true;
            }
        }
        
        public boolean release() {
            return tryRelease(false);
        }
        
        @Override
        public void close() {
            tryRelease(true);
        }
        
        private static long currentThreadId() {
            return Thread.currentThread().getId();
        }

        private static boolean invalidThreadContext(String message) {
            throw new IllegalStateException(message);
        }
    }    
}
