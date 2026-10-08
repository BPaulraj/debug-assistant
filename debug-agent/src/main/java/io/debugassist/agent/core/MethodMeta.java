package io.debugassist.agent.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.concurrent.ConcurrentHashMap;

/** Static description of a recorded method, resolved once per method and cached. */
final class MethodMeta {

    private static final ConcurrentHashMap<String, MethodMeta> CACHE = new ConcurrentHashMap<String, MethodMeta>();

    final String className;
    final String methodName;
    final String descriptor;
    final String[] paramNames;
    final String[] paramTypes;
    final String[] paramRawTypes;
    final String returnType;
    final String returnRawType;
    final boolean isStatic;
    final String visibility;
    final boolean proxy;
    final boolean isVoid;

    private MethodMeta(Class<?> type, String signature) {
        int paren = signature.indexOf('(');
        methodName = signature.substring(0, paren);
        descriptor = signature.substring(paren);
        boolean isProxy = Proxy.isProxyClass(type);
        proxy = isProxy;
        className = isProxy ? proxiedInterface(type) : type.getName();

        Method m = find(type, methodName, descriptor);
        if (m == null) {
            paramNames = new String[0];
            paramTypes = new String[0];
            paramRawTypes = new String[0];
            returnType = returnRawType = "java.lang.Object";
            isStatic = false;
            visibility = "public";
            isVoid = descriptor.endsWith(")V");
            return;
        }
        Class<?>[] raw = m.getParameterTypes();
        Type[] generic = m.getGenericParameterTypes();
        Parameter[] params = m.getParameters();
        paramNames = new String[raw.length];
        paramTypes = new String[raw.length];
        paramRawTypes = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            paramNames[i] = params.length == raw.length && params[i].isNamePresent() ? params[i].getName() : "arg" + i;
            paramRawTypes[i] = raw[i].getName();
            paramTypes[i] = generic.length == raw.length ? generic[i].getTypeName() : raw[i].getTypeName();
        }
        returnRawType = m.getReturnType().getName();
        returnType = m.getGenericReturnType().getTypeName();
        isVoid = m.getReturnType() == void.class;
        int mod = m.getModifiers();
        isStatic = Modifier.isStatic(mod);
        visibility = Modifier.isPublic(mod) ? "public" : Modifier.isPrivate(mod) ? "private"
                : Modifier.isProtected(mod) ? "protected" : "package";
    }

    static MethodMeta of(Class<?> type, String signature) {
        String key = type.getName() + '#' + signature;
        MethodMeta meta = CACHE.get(key);
        if (meta == null) {
            meta = new MethodMeta(type, signature);
            CACHE.put(key, meta);
        }
        return meta;
    }

    private static Method find(Class<?> type, String name, String descriptor) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && descriptor(m).equals(descriptor)) {
                return m;
            }
        }
        return null;
    }

    private static String proxiedInterface(Class<?> proxy) {
        Class<?>[] interfaces = proxy.getInterfaces();
        for (Class<?> i : interfaces) {
            for (String p : Recorder.config().packages) {
                if (i.getName().startsWith(p)) {
                    return i.getName();
                }
            }
        }
        return interfaces.length > 0 ? interfaces[0].getName() : proxy.getName();
    }

    static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(descriptor(p));
        }
        return sb.append(')').append(descriptor(m.getReturnType())).toString();
    }

    private static String descriptor(Class<?> c) {
        if (c.isArray()) {
            return c.getName().replace('.', '/');
        }
        if (c.isPrimitive()) {
            if (c == void.class) return "V";
            if (c == boolean.class) return "Z";
            if (c == byte.class) return "B";
            if (c == char.class) return "C";
            if (c == short.class) return "S";
            if (c == int.class) return "I";
            if (c == long.class) return "J";
            if (c == float.class) return "F";
            return "D";
        }
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
