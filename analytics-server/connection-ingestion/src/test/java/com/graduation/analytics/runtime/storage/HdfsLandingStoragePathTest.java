package com.graduation.analytics.runtime.storage;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HdfsLandingStoragePathTest {

    private static final String NAMESPACE = "hdfs://namenode:8020/warehouse/landing";

    @Test
    @DisplayName("健康探针只列出配置的 landing namespace，不访问 HDFS 根目录")
    void healthCheckStaysWithinConfiguredNamespace() throws IOException {
        FileSystem fs = mock(FileSystem.class);
        when(fs.listStatus(any(Path.class))).thenReturn(new FileStatus[0]);
        HdfsLandingStorage storage = storage(fs);

        LandingStorage.HealthResult result = storage.healthCheck();

        assertThat(result.ok()).isTrue();
        verify(fs).listStatus(new Path(URI.create(NAMESPACE)));
    }

    @Test
    @DisplayName("合法相对路径以 landing namespace 为根拼接")
    void resolvesSafeRelativePathUnderNamespace() throws IOException {
        FileSystem fs = mock(FileSystem.class);
        when(fs.exists(any(Path.class))).thenReturn(true);
        HdfsLandingStorage storage = storage(fs);

        assertThat(storage.exists("accepted/batch-42/events.jsonl")).isTrue();

        verify(fs).exists(new Path(new Path(URI.create(NAMESPACE)), "accepted/batch-42/events.jsonl"));
    }

    @Test
    @DisplayName("绝对路径、父目录穿越、反斜杠与 URI 形式在访问 FileSystem 前拒绝")
    void rejectsUnsafeRelativePathsBeforeFileSystemAccess() {
        FileSystem fs = mock(FileSystem.class);
        HdfsLandingStorage storage = storage(fs);

        for (String path : new String[]{"/warehouse/other", "../outside", "accepted/../../outside",
                "accepted\\events.jsonl", "hdfs://other:8020/path"}) {
            assertThatThrownBy(() -> storage.exists(path))
                    .as("拒绝路径 %s", path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(fs);
    }

    @Test
    @DisplayName("manifest batchId 只接受安全单段标识符")
    void rejectsUnsafeManifestBatchIdBeforeFileSystemAccess() {
        FileSystem fs = mock(FileSystem.class);
        HdfsLandingStorage storage = storage(fs);

        for (String batchId : new String[]{"../escape", "a/b", "a\\b", ".", "", "bad id"}) {
            assertThatThrownBy(() -> storage.writeManifest(batchId, "{}"))
                    .as("拒绝 batchId %s", batchId)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(fs);
    }

    @Test
    @DisplayName("landing URI 拒绝路径穿越与非 HDFS scheme")
    void validatesConfiguredNamespace() {
        FileSystem fs = mock(FileSystem.class);

        for (String uri : new String[]{"file:///tmp/landing", "hdfs://namenode:8020/a/../outside",
                "hdfs:///missing-authority", "hdfs://namenode:8020/landing?x=1"}) {
            assertThatThrownBy(() -> new HdfsLandingStorage(uri, new Configuration(), fs))
                    .as("拒绝 namespace %s", uri)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(fs);
    }

    @Test
    @DisplayName("安全 batchId 写入 manifests 子目录并返回其 HDFS URI")
    void writesManifestUnderNamespace() throws IOException {
        FileSystem fs = mock(FileSystem.class);
        FSDataOutputStream output = mock(FSDataOutputStream.class);
        when(fs.create(any(Path.class), eq(true))).thenReturn(output);
        HdfsLandingStorage storage = storage(fs);

        String written = storage.writeManifest("batch-42_v1", "{}");

        Path expected = new Path(new Path(URI.create(NAMESPACE)), "manifests/batch-42_v1.json");
        assertThat(written).isEqualTo(expected.toUri().toString());
        verify(fs).create(expected, true);
    }

    private static HdfsLandingStorage storage(FileSystem fs) {
        return new HdfsLandingStorage(NAMESPACE, new Configuration(), fs);
    }
}
