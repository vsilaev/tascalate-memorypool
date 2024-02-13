package net.tascalate.memory.nio;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

class DirectBufferCleaner {
    private static Method cleanerMethod = null;
    private static Method cleanMethod = null;
    private static Method viewedBufferMethod = null;
    private static Method freeMethod = null;

    static {
        // Oracle JRE / OpenJDK
        cleanerMethod = loadMethod("sun.nio.ch.DirectBuffer", "cleaner");
        cleanMethod = loadMethod("sun.misc.Cleaner", "clean");
        viewedBufferMethod = loadMethod("sun.nio.ch.DirectBuffer", "viewedBuffer");
        if (viewedBufferMethod == null) {
            // They changed the name in Java 7
            viewedBufferMethod = loadMethod("sun.nio.ch.DirectBuffer", "attachment");
        }

        // Apache Harmony (allocated directly, to not trigger allocator used
        // logic in BufferUtils)
        ByteBuffer bb = ByteBuffer.allocateDirect(1);
        Class<?> clazz = bb.getClass();
        try {
            freeMethod = clazz.getMethod("free");
        } catch (NoSuchMethodException | SecurityException ex) {
        }
    }

    private static Method loadMethod(String className, String methodName) {
        try {
            Method method = Class.forName(className).getMethod(methodName);
            //method.setAccessible(true);// according to the Java documentation, by default, a reflected object is not accessible
            return method;
        } catch (NoSuchMethodException // the method was not found
                | SecurityException // setAccessible not allowed by security policy
                | ClassNotFoundException ex) { // the direct buffer implementation was not found
            return null;
        } catch (Throwable t) {
            if (t.getClass().getName().equals("java.lang.reflect.InaccessibleObjectException")) {
                return null;// the class is in an unexported module
            } else {
                throw t;
            }
        }
    }

    /**
     * This function explicitly calls the Cleaner method of a direct buffer.
     *
     * @param toBeDestroyed
     *            The direct buffer that will be "cleaned". Utilizes reflection.
     */
    static void destroyDirectBuffer(Buffer toBeDestroyed) {
        try {
            if (freeMethod != null) {
                freeMethod.invoke(toBeDestroyed);
            } else {
                //TODO load the methods only once, store them into a cache (only for Java >= 9)
                Method localCleanerMethod;
                if (cleanerMethod == null) {
                    localCleanerMethod = loadMethod(toBeDestroyed.getClass().getName(), "cleaner");
                } else {
                    localCleanerMethod = cleanerMethod;
                }
                if (localCleanerMethod == null) {
                    Logger.getLogger(DirectBufferCleaner.class.getName()).log(Level.SEVERE,
                            "Buffer cannot be destroyed: {0}", toBeDestroyed);
                } else {
                    Object cleaner = localCleanerMethod.invoke(toBeDestroyed);
                    if (cleaner != null) {
                        Method localCleanMethod;
                        if (cleanMethod == null) {
                            if (cleaner instanceof Runnable) {
                                // jdk.internal.ref.Cleaner implements Runnable in Java 9
                                localCleanMethod = loadMethod(Runnable.class.getName(), "run");
                            } else {
                                // sun.misc.Cleaner does not implement Runnable in Java < 9
                                localCleanMethod = loadMethod(cleaner.getClass().getName(), "clean");
                            }
                        } else {
                            localCleanMethod = cleanMethod;
                        }
                        if (localCleanMethod == null) {
                            Logger.getLogger(DirectBufferCleaner.class.getName()).log(Level.SEVERE,
                                    "Buffer cannot be destroyed: {0}", toBeDestroyed);
                        } else {
                            localCleanMethod.invoke(cleaner);
                        }
                    } else {
                        Method localViewedBufferMethod;
                        if (viewedBufferMethod == null) {
                            localViewedBufferMethod = loadMethod(toBeDestroyed.getClass().getName(), "viewedBuffer");
                        } else {
                            localViewedBufferMethod = viewedBufferMethod;
                        }
                        if (localViewedBufferMethod == null) {
                            Logger.getLogger(DirectBufferCleaner.class.getName()).log(Level.SEVERE,
                                    "Buffer cannot be destroyed: {0}", toBeDestroyed);
                        } else {
                            // Try the alternate approach of getting the viewed
                            // buffer
                            // first
                            Object viewedBuffer = localViewedBufferMethod.invoke(toBeDestroyed);
                            if (viewedBuffer != null) {
                                if (viewedBuffer instanceof Buffer) {
                                    destroyDirectBuffer((Buffer) viewedBuffer);
                                }
                                // Else nothing to do, we may be on android which has an
                                // internal system for freeing direct buffers
                            } else {
                                Logger.getLogger(DirectBufferCleaner.class.getName()).log(Level.SEVERE,
                                        "Buffer cannot be destroyed: {0}", toBeDestroyed);
                            }
                        }
                    }
                }
            }
        } catch (IllegalAccessException
                | IllegalArgumentException
                | InvocationTargetException
                | SecurityException ex) {
            Logger.getLogger(DirectBufferCleaner.class.getName()).log(Level.SEVERE, "{0}", ex);
        }
    }

}
