package com.graduation.analytics.runtime.storage;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LandingStorageContractTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    @DisplayName("local 与 HDFS 对不存在路径都以空目录/null 文件统计表达，不误吞真实 IO 故障")
    void missingPathsHaveConsistentResultsAndIoFailuresRemainVisible() throws IOException {
        LocalLandingStorage local = new LocalLandingStorage(tempDir.toString());
        assertThat(local.list("missing")).isEmpty();
        assertThat(local.stat("missing/file.jsonl")).isNull();

        Files.createDirectory(tempDir.resolve("present"));
        assertThat(local.list("present")).isEmpty();

        FileSystem fs = mock(FileSystem.class);
        when(fs.listStatus(any(Path.class))).thenThrow(new FileNotFoundException("missing"));
        when(fs.getFileStatus(any(Path.class))).thenThrow(new FileNotFoundException("missing"));
        HdfsLandingStorage hdfs = new HdfsLandingStorage(
                "hdfs://namenode:8020/landing", new Configuration(), fs);
        assertThat(hdfs.list("missing")).isEmpty();
        assertThat(hdfs.stat("missing/file.jsonl")).isNull();

        when(fs.listStatus(any(Path.class))).thenThrow(new IOException("namenode unavailable"));
        assertThatThrownBy(() -> hdfs.list("raw"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HDFS list 失败");

        when(fs.getFileStatus(any(Path.class))).thenThrow(new IOException("namenode unavailable"));
        assertThatThrownBy(() -> hdfs.stat("raw/events.jsonl"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HDFS stat 失败");
    }

    @Test
    @DisplayName("manifest 先写临时文件再发布；相同内容幂等，冲突不覆盖")
    void manifestIsPublishedAtomicallyAndConflictsAreRejected() throws IOException {
        LocalLandingStorage local = new LocalLandingStorage(tempDir.toString());

        String firstUri = local.writeManifest("batch-42", "{\"status\":\"READY\"}");
        String secondUri = local.writeManifest("batch-42", "{\"status\":\"READY\"}");

        assertThat(secondUri).isEqualTo(firstUri);
        assertThat(Files.readString(tempDir.resolve("manifests/batch-42.json"), StandardCharsets.UTF_8))
                .isEqualTo("{\"status\":\"READY\"}");
        assertThat(local.list("manifests")).containsExactly("batch-42.json");
        assertThatThrownBy(() -> local.writeManifest("batch-42", "{\"status\":\"FAILED\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("内容冲突");
        assertThat(Files.readString(tempDir.resolve("manifests/batch-42.json"), StandardCharsets.UTF_8))
                .isEqualTo("{\"status\":\"READY\"}");

        try (OutputStream output = local.createOrAppend("accepted/7/events.jsonl")) {
            output.write("one\n".getBytes(StandardCharsets.UTF_8));
        }
        try (OutputStream output = local.createOrAppend("accepted/7/events.jsonl")) {
            output.write("two\n".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(Files.readString(tempDir.resolve("accepted/7/events.jsonl"), StandardCharsets.UTF_8))
                .isEqualTo("one\ntwo\n");
    }
}
