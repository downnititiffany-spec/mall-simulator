package com.graduation.generator.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4a：{@code adapter_type} → 适配器实现必须由注册表决定，不得在服务里写死 {@code if (type.equals(...))}。
 *
 * <p>这条正是 M1-9 要的证据形态：加一个商城＝加一个适配器实现并注册，而不是改探测服务的分支。
 * 注册表用<b>真实</b>适配器（文件适配器 + 参考商城 HTTP 适配器）验证，不用手写替身。</p>
 */
class MallTargetAdapterRegistryTest {

    private static final MallTargetAdapterRegistry REGISTRY = new MallTargetAdapterRegistry(List.of(
            new FileModeTargetAdapter(),
            new ReferenceMallHttpAdapter(name -> null, java.time.Duration.ofSeconds(1))));

    @Test
    void resolvesRegisteredAdapterTypesIgnoringCase() {
        assertTrue(REGISTRY.find("CANONICAL_EVENT_FILE").isPresent());
        assertTrue(REGISTRY.find("canonical_event_file").isPresent());
        assertTrue(REGISTRY.find("  REFERENCE_MALL_HTTP  ").isPresent(),
                "首尾空白与大小写不应导致找不到适配器");
        assertEquals("REFERENCE_MALL_HTTP", REGISTRY.find("reference_mall_http").orElseThrow().adapterType());
    }

    @Test
    void unknownAdapterTypeIsNotSilentlyFallenBack() {
        Optional<MallTargetAdapter> found = REGISTRY.find("SOME_OTHER_MALL");

        assertTrue(found.isEmpty(), "未注册的类型必须找不到，不能退化成通用探测");
        assertTrue(REGISTRY.knownTypes().contains("CANONICAL_EVENT_FILE"));
        assertTrue(REGISTRY.knownTypes().contains("REFERENCE_MALL_HTTP"));
        assertFalse(REGISTRY.knownTypes().isEmpty());
    }

    @Test
    void unknownAdapterTypeFailsLoudlyWithKnownTypesInMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> REGISTRY.require("SOME_OTHER_MALL"));

        assertTrue(error.getMessage().contains("SOME_OTHER_MALL"), error.getMessage());
        assertTrue(error.getMessage().contains("REFERENCE_MALL_HTTP"),
                "报错必须列出已注册类型，否则调用方无从修正：" + error.getMessage());
    }

    @Test
    void duplicateAdapterTypeFailsLoudly() {
        MallTargetAdapter fake = new MallTargetAdapter() {
            @Override
            public String adapterType() {
                return "REFERENCE_MALL_HTTP";
            }

            @Override
            public TargetCheckResult test(TargetConfig config) {
                return new TargetCheckResult(config.id(), false, "不会被调用", Map.of());
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MallTargetAdapterRegistry(List.of(fake,
                        new ReferenceMallHttpAdapter(name -> null, java.time.Duration.ofSeconds(1)))));

        assertTrue(error.getMessage().contains("REFERENCE_MALL_HTTP"), error.getMessage());
    }
}
