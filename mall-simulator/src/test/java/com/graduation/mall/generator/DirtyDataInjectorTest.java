package com.graduation.mall.generator;

import com.graduation.mall.generator.DirtyDataInjector.DirtySample;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脏数据注入测试（§20.5）：7 类脏数据齐全、期望处理映射正确、独立落 dirty 目录、不触碰业务表。
 */
class DirtyDataInjectorTest extends MallTestSupport {

    @Autowired
    private DirtyDataInjector dirtyInjector;

    @Test
    @DisplayName("注入 7 类脏数据并给出期望处理结果")
    void injectsAllDirtyTypes() throws IOException {
        DistributionKit kit = new DistributionKit(7);
        List<DirtySample> samples = dirtyInjector.inject(1.0, 7,
                List.of("normal-evt-1", "normal-evt-2"), kit);

        // 7 条 × 7 类全覆盖（类型按 0..6 循环）
        assertEquals(7, samples.size());
        Set<String> types = samples.stream().map(DirtySample::type).collect(Collectors.toSet());
        assertEquals(Set.of("missing_field", "illegal_enum", "future_time", "negative_qty",
                "amount_mismatch", "duplicate_event_id", "unknown_schema"), types);

        // 期望处理映射
        for (DirtySample s : samples) {
            assertEquals(DirtyDataInjector.expectedHandling(s.type()), s.expectedHandling());
        }
    }

    @Test
    @DisplayName("脏数据写入 landing/dirty 目录，样例可被解析")
    void writesToDirtyDir() throws IOException {
        DistributionKit kit = new DistributionKit(8);
        List<DirtySample> samples = dirtyInjector.inject(0.5, 10, List.of("n1"), kit);
        assertEquals(5, samples.size());

        Path dirtyDir = LANDING.get().resolve("dirty");
        assertTrue(Files.isDirectory(dirtyDir), "dirty 目录应存在: " + dirtyDir);
        try (Stream<Path> files = Files.list(dirtyDir)) {
            long jsonlCount = files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).count();
            assertEquals(1, jsonlCount);
        }
    }

    @Test
    @DisplayName("dirtyDataRate=0 时不注入")
    void zeroRateInjectsNothing() {
        List<DirtySample> samples = dirtyInjector.inject(0.0, 100, List.of("n1"), new DistributionKit(9));
        assertEquals(0, samples.size());
        assertTrue(!Files.exists(LANDING.get().resolve("dirty")));
    }
}