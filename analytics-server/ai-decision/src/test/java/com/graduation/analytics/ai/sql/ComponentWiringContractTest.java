package com.graduation.analytics.ai.sql;

import com.graduation.analytics.ai.RuleBasedSqlFallback;
import com.graduation.analytics.ai.SemanticCatalog;
import com.graduation.analytics.ai.TextToSqlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配契约回归（2026-09-11 真机启动事故）：{@code @Component} 若有**多个 public 构造器**，
 * Spring 不会自行择一，而是退化成"找无参构造"→ 启动即 {@code NoSuchMethodException: <init>()}。
 * 单测（MockMvc 手工 new）覆盖不到装配路径，所以这里用反射把规则钉死：
 *
 * <p>凡是 {@code ai} 包内的 {@code @Component}，只要有 >1 个 public 构造器，
 * 就必须**恰好有一个**标了 {@code @Autowired}；只有一个构造器时 Spring 能自行注入，无需注解。</p>
 */
class ComponentWiringContractTest {

    private static final List<Class<?>> AI_COMPONENTS = List.of(
            AiScopeResolver.class,
            QueryCostGuard.class,
            SqlExecutor.class,
            SqlSafetyValidator.class,
            TextToSqlService.class,
            RuleBasedSqlFallback.class,
            SemanticCatalog.class);

    @Test
    @DisplayName("多构造器的 @Component 必须恰好有一个 @Autowired 构造器（否则真机装配失败）")
    void multiConstructorComponentsDeclareAutowiredConstructor() {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : AI_COMPONENTS) {
            if (!AnnotatedElementUtils.hasAnnotation(type, Component.class)) {
                continue; // 非 Spring 组件（手工 new 的协作类）不在装配约束范围内
            }
            List<Constructor<?>> publicCtors = Arrays.stream(type.getDeclaredConstructors())
                    .filter(c -> Modifier.isPublic(c.getModifiers()))
                    .toList();
            if (publicCtors.size() <= 1) {
                continue;
            }
            long autowired = publicCtors.stream().filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            if (autowired != 1) {
                violations.add(type.getSimpleName() + " public 构造器=" + publicCtors.size()
                        + " 其中 @Autowired=" + autowired);
            }
        }
        assertThat(violations)
                .as("多构造器组件的 @Autowired 归属必须唯一：%s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("至少覆盖到已知的两个多构造器组件（防止列表被改空导致假绿）")
    void coversKnownMultiConstructorComponents() {
        assertThat(Arrays.stream(AiScopeResolver.class.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers())).count()).isGreaterThanOrEqualTo(2);
        assertThat(Arrays.stream(QueryCostGuard.class.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers())).count()).isGreaterThanOrEqualTo(2);
    }
}
