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
        return findMethod(type, name);
    }

    public static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                var method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    @SuppressWarnings("unchecked")
    public static <T> T invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        return (T) findMethod(target.getClass(), name, parameterTypes).invoke(target, args);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Object target, String name) throws ReflectiveOperationException {
        return (T) findField(target.getClass(), name).get(target);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getStaticFieldValue(Class<?> type, String name) throws ReflectiveOperationException {
        return (T) findField(type, name).get(null);
    }

    public static void setFieldValue(Object target, String name, Object value) throws ReflectiveOperationException {
        findField(target.getClass(), name).set(target, value);
    }
}
