package dev.timefiles.miahub.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ReflectionSupport {
    private ReflectionSupport() {
    }

    public static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                var field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public static Method findNoArgMethod(Class<?> type, String name) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                var method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Object target, String name) throws ReflectiveOperationException {
        return (T) findField(target.getClass(), name).get(target);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getStaticFieldValue(Class<?> type, String name) throws ReflectiveOperationException {
        return (T) findField(type, name).get(null);
    }
}
