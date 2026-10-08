package io.debugassist.cli;

import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the compiled class under test (without loading it) to find its injectable fields, so that only real
 * collaborators are mocked; data objects and helpers created inside the method run for real.
 */
final class Collaborators {

    static final class Field {
        final String name;
        final String type;

        Field(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private static final String[] CLASS_DIRS = {"target/classes", "build/classes/java/main", "build/classes/kotlin/main", "bin/main", "out/production/classes"};

    private final TypePool pool;
    private final List<Field> fields = new ArrayList<Field>();
    final File classesDir;

    private Collaborators(TypePool pool, File classesDir) {
        this.pool = pool;
        this.classesDir = classesDir;
    }

    /** Returns null when the project's compiled classes cannot be found (then every collaborator is mocked). */
    static Collaborators load(File project, String targetClass) throws IOException {
        for (String dir : CLASS_DIRS) {
            File classes = new File(project, dir);
            if (!new File(classes, targetClass.replace('.', '/') + ".class").isFile()) {
                continue;
            }
            ClassFileLocator locator = new ClassFileLocator.Compound(
                    new ClassFileLocator.ForFolder(classes), ClassFileLocator.ForClassLoader.ofSystemLoader());
            Collaborators c = new Collaborators(TypePool.Default.of(locator), classes);
            c.readFields(targetClass);
            return c;
        }
        return null;
    }

    private void readFields(String targetClass) {
        TypeDefinition type = pool.describe(targetClass).resolve();
        while (type != null && !type.represents(Object.class)) {
            try {
                for (FieldDescription f : type.getDeclaredFields()) {
                    if (!f.isStatic() && !f.getType().isPrimitive()) {
                        fields.add(new Field(f.getName(), f.getType().asErasure().getName()));
                    }
                }
                type = type.getSuperClass();
            } catch (RuntimeException unresolvable) {
                break; // superclass from a library that is not on this classpath
            }
        }
    }

    /** The target's field a mock of {@code collaboratorClass} would be injected into, or null. */
    Field fieldFor(String collaboratorClass) {
        for (Field f : fields) {
            if (f.type.equals(collaboratorClass)) {
                return f;
            }
        }
        for (Field f : fields) {
            if (isAssignable(collaboratorClass, f.type)) {
                return f;
            }
        }
        return null;
    }

    private boolean isAssignable(String from, String to) {
        TypePool.Resolution r = pool.describe(from);
        if (!r.isResolved()) {
            return false;
        }
        try {
            for (TypeDefinition t = r.resolve(); t != null; t = t.getSuperClass()) {
                if (t.asErasure().getName().equals(to) || implementsInterface(t.asErasure(), to)) {
                    return true;
                }
            }
        } catch (RuntimeException unresolvable) {
            return false;
        }
        return false;
    }

    private static boolean implementsInterface(TypeDescription t, String to) {
        for (TypeDescription.Generic i : t.getInterfaces()) {
            TypeDescription e = i.asErasure();
            if (e.getName().equals(to) || implementsInterface(e, to)) {
                return true;
            }
        }
        return false;
    }
}
