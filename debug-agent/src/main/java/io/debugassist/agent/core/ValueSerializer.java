package io.debugassist.agent.core;

import io.debugassist.agent.AgentConfig;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns any argument / return value into a JSON snapshot.
 * <p>
 * It is defensive by design: depth, string and collection sizes are capped, cycles are cut, sensitive
 * fields are masked, and framework / JDK objects are summarised with {@code toString()} instead of being walked.
 * Application objects are written field-by-field with an {@code "@type"} key so they can be rebuilt in replay tests.
 */
final class ValueSerializer {

    private static final String[] OPAQUE_PREFIXES = {
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.springframework.", "org.hibernate.",
            "org.apache.", "org.eclipse.", "io.netty.", "reactor.", "com.fasterxml.", "com.google.", "ch.qos.",
            "org.slf4j.", "io.micrometer.", "com.zaxxer.", "org.jboss.", "okhttp3.", "feign.", "kotlin.", "scala.",
            "groovy.", "org.aspectj.", "net.bytebuddy.", "org.mockito.", "io.opentelemetry."
    };

    private final AgentConfig config;

    private final ClassValue<Field[]> fields = new ClassValue<Field[]>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> out = new ArrayList<Field>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (RuntimeException inaccessible) {
                        // e.g. a module that is not open on Java 17+: skip the field.
                    }
                }
            }
            return out.toArray(new Field[0]);
        }
    };

    ValueSerializer(AgentConfig config) {
        this.config = config;
    }

    String toJson(Object value) {
        StringBuilder sb = new StringBuilder(64);
        try {
            write(sb, value, 0, new IdentityHashMap<Object, Boolean>());
        } catch (Throwable t) {
            sb.setLength(0);
            Json.quote(sb, "<not serializable: " + t.getClass().getSimpleName() + ">");
        }
        return sb.toString();
    }

    private void write(StringBuilder sb, Object v, int depth, IdentityHashMap<Object, Boolean> path) {
        if (v == null) {
            sb.append("null");
            return;
        }
        Class<?> type = v.getClass();
        if (v instanceof CharSequence) {
            string(sb, v.toString());
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            number(sb, (Number) v);
        } else if (v instanceof Character) {
            string(sb, v.toString());
        } else if (v instanceof Enum) {
            string(sb, ((Enum<?>) v).name());
        } else if (v instanceof Class) {
            string(sb, ((Class<?>) v).getName());
        } else if (v instanceof Date) {
            sb.append(((Date) v).getTime());
        } else if (v instanceof Calendar) {
            sb.append(((Calendar) v).getTimeInMillis());
        } else if (v instanceof byte[]) {
            string(sb, "<byte[" + ((byte[]) v).length + "]>");
        } else if (v instanceof char[]) {
            string(sb, new String((char[]) v));
        } else if (depth >= config.maxDepth) {
            string(sb, "<max depth>");
        } else if (path.containsKey(v)) {
            string(sb, "<cycle: " + type.getSimpleName() + ">");
        } else {
            path.put(v, Boolean.TRUE);
            try {
                if (v instanceof Optional) {
                    Optional<?> o = (Optional<?>) v;
                    write(sb, o.isPresent() ? o.get() : null, depth, path);
                } else if (type.isArray()) {
                    array(sb, v, depth, path);
                } else if (v instanceof Collection) {
                    iterable(sb, ((Collection<?>) v).iterator(), ((Collection<?>) v).size(), depth, path);
                } else if (v instanceof Map) {
                    map(sb, (Map<?, ?>) v, depth, path);
                } else if (isOpaque(type)) {
                    string(sb, safeToString(v));
                } else {
                    object(sb, v, type, depth, path);
                }
            } finally {
                path.remove(v);
            }
        }
    }

    private void object(StringBuilder sb, Object v, Class<?> type, int depth, IdentityHashMap<Object, Boolean> path) {
        sb.append("{\"@type\":");
        Json.quote(sb, type.getName());
        for (Field f : fields.get(type)) {
            sb.append(',');
            Json.quote(sb, f.getName());
            sb.append(':');
            if (config.isMasked(f.getName())) {
                sb.append("\"****\"");
                continue;
            }
            Object fv;
            try {
                fv = f.get(v);
            } catch (Throwable t) {
                string(sb, "<inaccessible>");
                continue;
            }
            write(sb, fv, depth + 1, path);
        }
        sb.append('}');
    }

    private void map(StringBuilder sb, Map<?, ?> m, int depth, IdentityHashMap<Object, Boolean> path) {
        sb.append('{');
        int n = 0;
        try {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (n > 0) {
                    sb.append(',');
                }
                String key = String.valueOf(e.getKey());
                if (n >= config.maxCollectionSize) {
                    sb.append("\"<truncated>\":\"").append(m.size() - n).append(" more entries\"");
                    break;
                }
                Json.quote(sb, key);
                sb.append(':');
                if (config.isMasked(key)) {
                    sb.append("\"****\"");
                } else {
                    write(sb, e.getValue(), depth + 1, path);
                }
                n++;
            }
        } catch (RuntimeException lazyOrConcurrent) {
            if (n > 0) {
                sb.append(',');
            }
            sb.append("\"<error>\":");
            Json.quote(sb, lazyOrConcurrent.getClass().getSimpleName());
        }
        sb.append('}');
    }

    private void iterable(StringBuilder sb, Iterator<?> it, int size, int depth, IdentityHashMap<Object, Boolean> path) {
        sb.append('[');
        int n = 0;
        try {
            while (it.hasNext()) {
                Object item = it.next();
                if (n > 0) {
                    sb.append(',');
                }
                if (n >= config.maxCollectionSize) {
                    string(sb, "<" + (size - n) + " more items>");
                    break;
                }
                write(sb, item, depth + 1, path);
                n++;
            }
        } catch (RuntimeException lazyOrConcurrent) {
            if (n > 0) {
                sb.append(',');
            }
            string(sb, "<error: " + lazyOrConcurrent.getClass().getSimpleName() + ">");
        }
        sb.append(']');
    }

    private void array(StringBuilder sb, Object arr, int depth, IdentityHashMap<Object, Boolean> path) {
        int len = Array.getLength(arr);
        sb.append('[');
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (i >= config.maxCollectionSize) {
                string(sb, "<" + (len - i) + " more items>");
                break;
            }
            write(sb, Array.get(arr, i), depth + 1, path);
        }
        sb.append(']');
    }

    private void number(StringBuilder sb, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                string(sb, String.valueOf(d));
                return;
            }
        }
        if (n instanceof BigDecimal) {
            sb.append(((BigDecimal) n).toPlainString());
        } else if (n instanceof BigInteger || n instanceof Integer || n instanceof Long || n instanceof Short
                || n instanceof Byte || n instanceof Double || n instanceof Float) {
            sb.append(n);
        } else {
            // AtomicInteger, LongAdder, ...
            sb.append(n.toString());
        }
    }

    private void string(StringBuilder sb, String s) {
        if (s.length() > config.maxStringLength) {
            s = s.substring(0, config.maxStringLength) + "...<" + (s.length() - config.maxStringLength) + " more chars>";
        }
        Json.quote(sb, s);
    }

    private static String safeToString(Object v) {
        try {
            return String.valueOf(v);
        } catch (Throwable t) {
            return "<" + v.getClass().getName() + ": toString failed>";
        }
    }

    private static boolean isOpaque(Class<?> type) {
        String n = type.getName();
        if (n.contains("$$") || n.contains("$Proxy") || n.contains("$HibernateProxy")) {
            return true;
        }
        for (String p : OPAQUE_PREFIXES) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
