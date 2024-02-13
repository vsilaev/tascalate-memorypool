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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

abstract class ConditionWaiter {
    abstract boolean awaitNext(Condition condition) throws InterruptedException;
    abstract void checkTimeElapsed() throws InterruptedException;
    
    static ConditionWaiter unlimited() {
        return TIME_UNLIMITED_WAITER;
    }
    
    static ConditionWaiter withMaxWaitTimeMillis(long maxTimeToWait) {
        return withMaxWaitTime(maxTimeToWait, TimeUnit.MILLISECONDS);
    }
    
    static ConditionWaiter withMaxWaitTime(long maxTimeToWait, TimeUnit timeUnit) {
        return new TimeLimitedWaiter(maxTimeToWait, timeUnit);
    }
    
    private static class TimeUnlimitedWaiter extends ConditionWaiter {
        TimeUnlimitedWaiter() {}
        
        @Override
        boolean awaitNext(Condition condition) throws InterruptedException {
            condition.await();
            return true;
        }
        
        @Override
        void checkTimeElapsed() throws InterruptedException {
        }
    }
    
    private static class TimeLimitedWaiter extends ConditionWaiter {
        private final long maxTimeToWait;
        private long remainingTimeToWait;
        private boolean timeElapsed = false;
        
        TimeLimitedWaiter(long maxTimeToWait, TimeUnit timeUnit) {
            this.maxTimeToWait = TimeUnit.NANOSECONDS.convert(maxTimeToWait, timeUnit);
            remainingTimeToWait = this.maxTimeToWait;
        }
        
        @Override
        boolean awaitNext(Condition condition) throws InterruptedException {
            if (remainingTimeToWait <= 0) {
                return timeElapsed = true;
            }
            long iterationStartTime = System.nanoTime();
            try {
                timeElapsed = timeElapsed || !condition.await(remainingTimeToWait, TimeUnit.NANOSECONDS);
            } finally {
                long iterationEndTime = System.nanoTime();
                remainingTimeToWait -= Math.max(0L, iterationEndTime - iterationStartTime);
            }  
            return !timeElapsed;
        }
        
        @Override
        void checkTimeElapsed() throws InterruptedException {
            if (timeElapsed) {
                throw new InterruptedException("Wait time elapsed waiting for free pool space");
            }
        }
    }
    
    private static final ConditionWaiter TIME_UNLIMITED_WAITER = new TimeUnlimitedWaiter();
}
