package io.debugassist.agent.core;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls framework methods (servlet request, Spring HttpHeaders, ...) by name, so the agent has no compile-time
 * dependency on javax.servlet, jakarta.servlet, Spring or Apache HttpClient and works with all their versions.
 */
final class Reflect {

    private static final Method MISSING;

    static {
        try {
            MISSING = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final ClassValue<ConcurrentHashMap<String, Method>> METHODS =
            new ClassValue<ConcurrentHashMap<String, Method>>() {
                @Override
                protected ConcurrentHashMap<String, Method> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<String, Method>();
                }
            };

    private Reflect() {
    }

    /** Invokes a public method; returns null if it does not exist or fails. */
    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        Method m = lookup(target.getClass(), name, types);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object call(Object target, String name) {
        return call(target, name, new Class<?>[0]);
    }

    static String callString(Object target, String name) {
        Object v = call(target, name);
        return v == null ? null : String.valueOf(v);
    }

    private static Method lookup(Class<?> type, String name, Class<?>[] types) {
        ConcurrentHashMap<String, Method> cache = METHODS.get(type);
        String key = name + types.length + (types.length > 0 ? types[types.length - 1].getName() : "");
        Method m = cache.get(key);
        if (m == null) {
            try {
                m = type.getMethod(name, types);
                try {
                    m.setAccessible(true); // public method declared on a non-public class
                } catch (RuntimeException ignored) {
                    // keep it; invoke may still work
                }
            } catch (NoSuchMethodException e) {
                m = MISSING;
            }
            cache.put(key, m);
        }
        return m == MISSING ? null : m;
    }
}
