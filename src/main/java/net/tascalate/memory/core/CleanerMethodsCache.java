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
package net.tascalate.memory.core;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public final class CleanerMethodsCache {
    
    @FunctionalInterface
    static interface ExceptionalConsumer<T> {
        void accept(T a) throws Throwable;
        default Consumer<T> unchecked() {
            return (a) -> { 
                try {
                    this.accept(a);
                } catch (Error | RuntimeException ex) {
                    throw ex;
                } catch (Throwable ex) {
                    throw new RuntimeException(ex);
                }
            };            
        }
    }
    
    @FunctionalInterface
    static interface ExceptionalFunction<T, R> {
        R apply(T a) throws Throwable;
        default Function<T, R> unchecked() {
            return (a) -> { 
                try {
                    return this.apply(a);
                } catch (Error | RuntimeException ex) {
                    throw ex;
                } catch (Throwable ex) {
                    throw new RuntimeException(ex);
                }
            };            
        }
    }
    
    public static Consumer<Buffer> cleanerOf(Class<?> bufferClass) {
        return cleanerOf(bufferClass, false);
    }
    
    public static Consumer<Buffer> cleanerOf(Class<?> bufferClass, boolean releaseProjectedBuffer) {
        return
        (releaseProjectedBuffer ? 
            GET_CLEANER_METHOD_BY_CLASS_WITH_ATTACHMENTS :
            GET_CLEANER_METHOD_BY_CLASS_DIRECT_ONLY).apply(bufferClass);
    }
    
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    
    private static final Function<Class<?>, Function<Buffer, Buffer>> GET_ATTACHMENT_METHOD_BY_CLASS = 
        new FunctionMemoization<>(bufferClass -> {
            
            Stream<Function<Class<?>, ExceptionalFunction<Buffer, Buffer>>> options = Stream.of(
                c -> attachmentMethodOf(c, "attachment"),
                c -> attachmentMethodOf(c, "viewedBuffer")
            );
            return options.map(option -> Optional.ofNullable( option.apply(bufferClass) ))
                          .filter(Optional::isPresent)
                          .map(Optional::get)
                          .map(ExceptionalFunction::unchecked)
                          .findFirst()
                          .orElse(buffer -> {
                              throw new IllegalArgumentException("Attachment may not be invoked on " + buffer);   
                          });
        });
    
    private static final Function<Class<?>, Consumer<Buffer>> GET_CLEANER_METHOD_BY_CLASS_DIRECT_ONLY = allCleanerMethods(false);
    private static final Function<Class<?>, Consumer<Buffer>> GET_CLEANER_METHOD_BY_CLASS_WITH_ATTACHMENTS = allCleanerMethods(true);
    
    private static final Function<Class<?>, Consumer<Object>> GET_CLEAN_METHOD_BY_CLASS = 
        new FunctionMemoization<>(cleanerClass -> {
            ExceptionalConsumer<Object> c = cleanMethodOf(cleanerClass);
            return null != c ? c.unchecked() : cleaner ->  {
                throw new IllegalArgumentException("Clean may not be invoked for cleaner " + cleaner);
            };
        });
        
    private static ExceptionalFunction<Buffer, Buffer> attachmentMethodOf(Class<?> clazz, String name) {
        try {
            Method m = firstMethod( clazz.getMethod(name) );
            if (null == m) {
                return null;
            }
            // Option 1
            /*
            MethodHandle mh = unreflect(m).asType(MethodType.methodType(Object.class, Buffer.class));
            */
            // Option 2
            Function<Buffer, Object> getAttachment = createLambdaFunction(unreflect(m));
            return (buffer) -> {
                if (!buffer.isDirect()) {
                    throw new IllegalArgumentException("The supplied buffer is not a direct memory buffer: " + buffer);
                }
                return (Buffer)getAttachment.apply(buffer); /* = mh.invokeExact(buffer); */
            };
        } catch (ReflectiveOperationException | SecurityException ex) {
            throw new RuntimeException(ex);
        }
    } 
    
    private static FunctionMemoization<Class<?>, Consumer<Buffer>> allCleanerMethods(boolean releaseProjectedBuffer) {
        return new FunctionMemoization<>(bufferClass -> {
            
            Stream<Function<Class<?>, ExceptionalConsumer<Buffer>>> options = Stream.of(
                CleanerMethodsCache::freeMethodOf,
                releaseProjectedBuffer ? CleanerMethodsCache::cleanerMethodOf_ScanAttachments : CleanerMethodsCache::cleanerMethodOf_DirectOnly
            );
            return options.map(option -> Optional.ofNullable( option.apply(bufferClass) ))
                          .filter(Optional::isPresent)
                          .map(Optional::get)
                          .map(ExceptionalConsumer::unchecked)
                          .findFirst()
                          .orElse(buffer -> {
                              throw new IllegalArgumentException("Cleaner may not be invoked on " + buffer);   
                          });
        });        
    }
    
    private static ExceptionalConsumer<Buffer> cleanerMethodOf_DirectOnly(Class<?> clazz) {
        return cleanerMethodOf(clazz, buffer -> {
            String desc = buffer.getClass().getName() + "@" + System.identityHashCode(buffer);
            throw new IllegalArgumentException("The buffer " + desc + " is either created from the memory segment, or via JNI native call, or is a view buffer; it has no associated cleaner");
        });
    }
    
    private static ExceptionalConsumer<Buffer> cleanerMethodOf_ScanAttachments(Class<?> clazz) {
        return cleanerMethodOf(clazz, buffer -> {
            Buffer attachment = GET_ATTACHMENT_METHOD_BY_CLASS.apply(clazz).apply(buffer);
            if (null == attachment) {
                String desc = buffer.getClass().getName() + "@" + System.identityHashCode(buffer);
                throw new IllegalArgumentException("The buffer " + desc + " is created from the memory segment or via JNI native call; it has no associated cleaner");
            } else {
                cleanerOf(attachment.getClass(), true).accept(attachment);
            }
        });
    }
    
    private static ExceptionalConsumer<Buffer> cleanerMethodOf(Class<?> clazz, Consumer<Buffer> nullCleanerHandler) {
        try {
            Method m = firstMethod( clazz.getMethod("cleaner") );
            if (null == m) {
                return null;
            }
            // Option 1
            /*
            MethodHandle mh = unreflect(m).asType(MethodType.methodType(Object.class, Buffer.class));
            */
            // Option 2
            Function<Buffer, Object> getCleaner = createLambdaFunction(unreflect(m));
            return (buffer) -> {
                if (!buffer.isDirect()) {
                    throw new IllegalArgumentException("The supplied buffer is not a direct memory buffer: " + buffer);
                }
                Object cleaner = getCleaner.apply(buffer); /* = mh.invokeExact(buffer); */
                if (cleaner instanceof Runnable) {
                    ((Runnable)cleaner).run();
                } else if (null == cleaner) {
                    nullCleanerHandler.accept(buffer);
                } else {
                    GET_CLEAN_METHOD_BY_CLASS.apply(cleaner.getClass()).accept(cleaner);
                }
            };
        } catch (ReflectiveOperationException | SecurityException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    private static ExceptionalConsumer<Object> cleanMethodOf(Class<?> clazz) {
        try {
            Method m = firstMethod( clazz.getMethod("clean") );
            if (null == m) {
                return null;
            }
            // Option 1
            /*
            MethodHandle mh = unreflect(m).asType(MethodType.methodType(void.class, Object.class));
            */
            // Option 2
            Consumer<Object> runClean = createLambdaConsumer(unreflect(m));
            return cleaner -> runClean.accept(cleaner); /* mh.invokeExact(cleaner); */
        } catch (ReflectiveOperationException | SecurityException ex) {
            return null;
        } 
    }
    
    private static ExceptionalConsumer<Buffer> freeMethodOf(Class<?> clazz) {
        try {
            Method m = firstMethod( clazz.getMethod("free") );
            if (null == m) {
                return null;
            }
            // Option 1
            /*
            MethodHandle mh = unreflect(m).asType(MethodType.methodType(void.class, Buffer.class));
            */
            Consumer<Object> runFree = createLambdaConsumer(unreflect(m));
            return buffer -> runFree.accept(buffer); /* mh.invokeExact(buffer); */
        } catch (ReflectiveOperationException | SecurityException ex) {
            return null;
        }
    }
    
    private static <T, R> Function<T, R> createLambdaFunction(MethodHandle impl) {
        CallSite callSite = createCallSite("apply", MethodType.methodType(Function.class), MethodType.methodType(Object.class, Object.class), impl);
        MethodHandle mh = callSite.getTarget();
        try {
            return (Function<T, R>)mh.invokeExact();
        } catch (Throwable ex) {
            throw new RuntimeException(ex); 
        }
    }
    
    private static <T> Consumer<T> createLambdaConsumer(MethodHandle impl) {
        CallSite callSite = createCallSite("accept", MethodType.methodType(Consumer.class), MethodType.methodType(void.class, Object.class), impl);
        MethodHandle mh = callSite.getTarget();
        try {
            return (Consumer<T>)mh.invokeExact();
        } catch (Throwable ex) {
            throw new RuntimeException(ex); 
        }
    }

    private static CallSite createCallSite(String intfMethodName, MethodType intfMehtodClass, MethodType intfMethodSignature, MethodHandle impl) {
        try {
            return LambdaMetafactory.metafactory(
                LOOKUP, intfMethodName, intfMehtodClass, intfMethodSignature, impl, impl.type()
            );
        } catch (LambdaConversionException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    private static Method firstMethod(Method m) {
        return firstMethod(m.getDeclaringClass(), m, new HashSet<>());
    }
    
    private static Method firstMethod(Class<?> clazz, Method m, Set<Class<?>> visited) {
        if (visited.contains(clazz)) {
            return null;
        }
        visited.add(clazz);
        if ((clazz.getModifiers() & Modifier.PUBLIC) != 0) {
            try {
                Method parent = clazz.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if ((parent.getModifiers() & Modifier.PUBLIC) != 0) {
                    return parent;
                } else {
                    // Visibility can't be reduced, so no need to check superclasses
                    return null;
                }
            } catch (NoSuchMethodException ex) {
                // Ok, will check parents
            }
        }
        return
        Stream.concat( Stream.of(clazz.getSuperclass()), Stream.of(clazz.getInterfaces()) )
              .filter(c -> c != null && !visited.contains(c))
              .map(superClazz -> firstMethod(superClazz, m, visited))
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(null)
        ;
    }
    
    private static MethodHandle unreflect(Method m) throws IllegalAccessException {
        return LOOKUP.unreflect(m);
    }
}
