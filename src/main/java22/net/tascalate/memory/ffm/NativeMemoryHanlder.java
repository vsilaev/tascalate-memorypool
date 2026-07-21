/**
 * Copyright 2026 Valery Silaev (http://vsilaev.com)
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
package net.tascalate.memory.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Objects;
import java.util.function.BiConsumer;

import net.tascalate.memory.MemoryResourceHandler;

public class NativeMemoryHanlder implements MemoryResourceHandler<NativeMemory> {

    private static final MethodHandle ALLOC_HANDLE;
    private static final MethodHandle FREE_HANDLE;
    private static final boolean IS_WINDOWS;

    static {
        var linker = Linker.nativeLinker();
        var stdlib = linker.defaultLookup();
        var os = System.getProperty("os.name").toLowerCase();
        IS_WINDOWS = os.contains("win");

        try {
            if (IS_WINDOWS) {
                // Windows layout: void* _aligned_malloc(size_t size, size_t alignment)
                ALLOC_HANDLE = linker.downcallHandle(
                    stdlib.find("_aligned_malloc").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG)
                );
                // Windows layout: void _aligned_free(void* memblock)
                FREE_HANDLE = linker.downcallHandle(
                    stdlib.find("_aligned_free").orElseThrow(),
                    FunctionDescriptor.ofVoid(ADDRESS)
                );
            } else {
                // POSIX layout: int posix_memalign(void **memptr, size_t alignment, size_t size)
                ALLOC_HANDLE = linker.downcallHandle(
                    stdlib.find("posix_memalign").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG)
                );
                // Standard POSIX free
                FREE_HANDLE = linker.downcallHandle(
                    stdlib.find("free").orElseThrow(),
                    FunctionDescriptor.ofVoid(ADDRESS)
                );
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final static BiConsumer<MemorySegment, Boolean> NO_CLEANER = (_, _) -> {};

    static class NativeMemoryImpl implements NativeMemory {
        private volatile MemorySegment externalValue;
        private final MemorySegment internalValue;

        public NativeMemoryImpl(MemorySegment m) {
            Objects.requireNonNull(m, "Memory segment must not be null");
            internalValue = m;
        }

        @Override
        public MemorySegment segment() {
            if (null == externalValue) {
                throw new IllegalStateException();
            }
            return externalValue;
        }

        void setup(long userRequestedSize) {
            if (null == internalValue) {
                throw new IllegalStateException();
            }
            long capacity = internalValue.byteSize();
            if (userRequestedSize > capacity) {
                throw new IllegalArgumentException("Requested size is bigger than capacity: " + userRequestedSize + " vs " + capacity);
            } else if (userRequestedSize == capacity) {
                externalValue = internalValue;
            } else {
                externalValue = internalValue.asSlice(0, userRequestedSize);
            }
        }

        void cleanup(BiConsumer<MemorySegment, Boolean> cleaner, boolean beforeDestroy) {
            if (null == externalValue) {
                throw new IllegalStateException();
            }
            cleaner.accept(externalValue, Boolean.valueOf(beforeDestroy));
            externalValue = null;
        }

        void destroy() {
            release(internalValue);
        }

        long capacity() {
            return internalValue.byteSize();
        }
    }

    private final long alignment;
    private final BiConsumer<MemorySegment, Boolean> cleaner;

    public NativeMemoryHanlder() {
        this(64L);
    }

    public NativeMemoryHanlder(long alignment) {
        this(alignment, NO_CLEANER);
    }

    public NativeMemoryHanlder(BiConsumer<MemorySegment, Boolean> cleaner) {
        this(64L, cleaner);
    }

    public NativeMemoryHanlder(long alignment, BiConsumer<MemorySegment, Boolean> cleaner) {
        this.alignment = alignment;
        this.cleaner = null == cleaner ? NO_CLEANER : cleaner;
    }

    @Override
    public NativeMemory create(long size) {
        return new NativeMemoryImpl(allocate(size, alignment, Arena.global()));
    }

    @Override
    public void setup(NativeMemory m, long size, boolean afterCreate) {
        implementation(m).setup(size);
    }

    @Override
    public void cleanup(NativeMemory m, boolean beforeDestroy) {
        implementation(m).cleanup(cleaner, beforeDestroy);
    }

    @Override
    public void destroy(NativeMemory m) {
        implementation(m).destroy();
    }

    @Override
    public long capacityOf(NativeMemory m) {
        return implementation(m).capacity();
    }

    private static NativeMemoryImpl implementation(NativeMemory m) {
        return (NativeMemoryImpl)m;
    }

    static MemorySegment allocate(long size, long alignment, Arena arena) {
        MemorySegment rawPtr;
        if (IS_WINDOWS) {
            try {
                rawPtr = (MemorySegment) ALLOC_HANDLE.invokeExact(size, alignment);
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            }
            if (rawPtr.equals(MemorySegment.NULL)) {
                throw new OutOfMemoryError();
            }
        } else {
            // POSIX stores the resulting pointer inside a pointer reference passed to it
            try (Arena local = Arena.ofConfined()) {
                MemorySegment ptrOut = local.allocate(ADDRESS);
                int result;
                try {
                    result = (int) ALLOC_HANDLE.invokeExact(ptrOut, alignment, size);
                } catch (Throwable ex) {
                    throw new RuntimeException(ex);
                }
                if (result != 0) {
                    throw new OutOfMemoryError("posix_memalign failed with code: " + result);
                }

                rawPtr = ptrOut.get(ADDRESS, 0);
            }
        }
        return rawPtr.reinterpret(size, arena, null);
    }

    static void release(MemorySegment seg) {
        try {
            FREE_HANDLE.invokeExact(seg);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
