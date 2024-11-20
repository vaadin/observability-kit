package com.vaadin.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;

import net.bytebuddy.asm.Advice;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AbstractTypeInstrumentationTest {
    protected static void checkAdviceClass(Class<?> cls) {
        checkAdviceClassMethods(cls.getDeclaredMethods());
    }

    private static void checkAdviceClassMethods(Method[] methods) {
        for (var method : methods) {
            var annotations = method.getDeclaredAnnotations();
            assertNotEquals(0, annotations.length);
            assertTrue(Arrays.stream(annotations).map(Annotation::annotationType).anyMatch(
                type -> type.equals(Advice.OnMethodExit.class) || type.equals(Advice.OnMethodEnter.class)));
        }
    }
}
