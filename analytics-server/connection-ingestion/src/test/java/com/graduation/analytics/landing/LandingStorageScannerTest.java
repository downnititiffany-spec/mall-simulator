package com.graduation.analytics.landing;

import com.graduation.analytics.runtime.storage.LocalLandingStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class LandingStorageScannerTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    @DisplayName("Storage 扫描器对 FLUME_RAW 递归布局排除临时/隐藏条目并保留相对键")
    void scansCompletedFlumeFilesRecursively() throws Exception {
        java.nio.file.Path root = tempDir.resolve("landing");
        Files.createDirectories(root.resolve("raw/dt=20260924/hour=08/.flumespool"));
        Files.createDirectories(root.resolve("raw/dt=20260924/hour=08/_temporary"));
        Files.writeString(root.resolve("raw/dt=20260924/hour=08/events.2"), "payload\n");
        Files.writeString(root.resolve("raw/dt=20260924/hour=08/events.1.tmp"), "partial\n");
        Files.writeString(root.resolve("raw/dt=20260924/hour=08/.flumespool/checkpoint"), "state");
        Files.writeString(root.resolve("raw/dt=20260924/hour=08/_temporary/hidden"), "state");
        Files.createFile(root.resolve("raw/dt=20260924/hour=08/empty"));
        LocalLandingStorage storage = new LocalLandingStorage(root.toString());

        var candidates = LandingInputScanner.inspect(storage, "raw", LandingLayout.FLUME_RAW);
        var completed = LandingInputScanner.scan(storage, "raw", LandingLayout.FLUME_RAW);

        assertThat(candidates).extracting(LandingInputScanner.StoredCandidate::inputKey)
                .containsExactly("dt=20260924/hour=08/empty", "dt=20260924/hour=08/events.2");
        assertThat(completed).extracting(LandingInputScanner.StoredFile::inputKey)
                .containsExactly("dt=20260924/hour=08/events.2");
    }

    @Test
    @DisplayName("Storage 扫描器的 ROLLING_LOG 保持一层且只接纳 jsonl")
    void scansOnlyTopLevelJsonlForRollingLayout() throws Exception {
        java.nio.file.Path root = tempDir.resolve("landing");
        Files.createDirectories(root.resolve("events/nested"));
        Files.writeString(root.resolve("events/one.jsonl"), "one\n");
        Files.writeString(root.resolve("events/two.json"), "two\n");
        Files.writeString(root.resolve("events/nested/three.jsonl"), "three\n");
        LocalLandingStorage storage = new LocalLandingStorage(root.toString());

        assertThat(LandingInputScanner.scan(storage, "events", LandingLayout.ROLLING_LOG))
                .extracting(LandingInputScanner.StoredFile::inputKey)
                .containsExactly("one.jsonl");
    }

}
