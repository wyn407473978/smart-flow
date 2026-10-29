package com.example.smartflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止配置 record 绑定失败：Spring Boot 只在"只有一个非 private 构造器"或"有且只有一个 @ConstructorBinding 构造器"时
 * 才会用构造器绑定配置；否则 record 没有 setter，整个配置会是 null（启动时报 NPE）。
 * 给配置 record 加兼容用的辅助构造器时，必须在规范构造器上加 @ConstructorBinding。
 */
class FlowPropertiesBindingTest {

    @Test
    void everyConfigRecordHasAnUnambiguousBindingConstructor() {
        List<String> problems = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(FlowProperties.class));
        while (!todo.isEmpty()) {
            Class<?> type = todo.pop();
            if (!seen.add(type)) {
                continue;
            }
            Constructor<?>[] ctors = Arrays.stream(type.getDeclaredConstructors())
                    .filter(c -> !c.isSynthetic())
                    .toArray(Constructor[]::new);
            long annotated = Arrays.stream(ctors).filter(c -> c.isAnnotationPresent(ConstructorBinding.class)).count();
            long nonPrivate = Arrays.stream(ctors).filter(c -> !Modifier.isPrivate(c.getModifiers())).count();
            if (annotated > 1 || (annotated == 0 && nonPrivate != 1)) {
                problems.add(type.getName() + "：" + ctors.length + " 个构造器，@ConstructorBinding " + annotated + " 个");
            }
            if (annotated == 1) {
                Constructor<?> bind = Arrays.stream(ctors)
                        .filter(c -> c.isAnnotationPresent(ConstructorBinding.class)).findFirst().orElseThrow();
                if (type.isRecord() && bind.getParameterCount() != type.getRecordComponents().length) {
                    problems.add(type.getName() + "：@ConstructorBinding 应该加在规范构造器上");
                }
            }
            if (type.isRecord()) {
                for (RecordComponent rc : type.getRecordComponents()) {
                    if (rc.getType().isRecord() && rc.getType().getName().startsWith("com.example")) {
                        todo.push(rc.getType());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "配置 record 无法绑定：" + problems);
    }
}
