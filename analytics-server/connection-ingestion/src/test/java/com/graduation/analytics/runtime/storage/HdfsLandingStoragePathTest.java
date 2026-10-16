package com.graduation.analytics.runtime.storage;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
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
        when(fs.mkdirs(any(Path.class))).thenReturn(true);
        when(fs.create(any(Path.class), eq(false))).thenReturn(output);
        when(fs.rename(any(Path.class), any(Path.class))).thenReturn(true);
        HdfsLandingStorage storage = storage(fs);

        String written = storage.writeManifest("batch-42_v1", "{}");

        Path expected = new Path(new Path(URI.create(NAMESPACE)), "manifests/batch-42_v1.json");
        assertThat(written).isEqualTo(expected.toUri().toString());
        org.mockito.ArgumentCaptor<Path> temporary = org.mockito.ArgumentCaptor.forClass(Path.class);
        verify(fs).create(temporary.capture(), eq(false));
        assertThat(temporary.getValue().getParent()).isEqualTo(expected.getParent());
        assertThat(temporary.getValue().getName()).startsWith(".batch-42_v1-").endsWith(".tmp");
        verify(fs).rename(temporary.getValue(), expected);
    }

    @Test
    @DisplayName("HDFS Storage 的 createOrAppend 在缺失时创建、存在时追加相对文件")
    void createsOrAppendsUnderNamespace() throws IOException {
        FileSystem fs = mock(FileSystem.class);
        Path target = new Path(new Path(URI.create(NAMESPACE)), "accepted/7/events.jsonl");
        FSDataOutputStream created = mock(FSDataOutputStream.class);
        FSDataOutputStream appended = mock(FSDataOutputStream.class);
        when(fs.mkdirs(any(Path.class))).thenReturn(true);
        when(fs.exists(target)).thenReturn(false, true);
        when(fs.create(target, false)).thenReturn(created);
        when(fs.append(target)).thenReturn(appended);
        HdfsLandingStorage storage = storage(fs);

        try (OutputStream output = storage.createOrAppend("accepted/7/events.jsonl")) {
            output.write(1);
        }
        try (OutputStream output = storage.createOrAppend("accepted/7/events.jsonl")) {
            output.write(2);
        }

        verify(fs).create(target, false);
        verify(fs).append(target);
    }

    @Test
    @DisplayName("HDFS 文件身份优先采用文件系统 checksum")
    void fileIdentityUsesHdfsChecksum() throws IOException {
        FileSystem fs = mock(FileSystem.class);
        Path target = new Path(new Path(URI.create(NAMESPACE)), "raw/dt=20260924/events.jsonl");
        FileStatus status = new FileStatus(12L, false, 1, 128L, 42L, target);
        org.apache.hadoop.fs.FileChecksum checksum = mock(org.apache.hadoop.fs.FileChecksum.class);
        when(fs.getFileStatus(target)).thenReturn(status);
        when(fs.getFileChecksum(target)).thenReturn(checksum);
        when(checksum.getAlgorithmName()).thenReturn("MD5-of-0MD5-of-512CRC32");
        when(checksum.getBytes()).thenReturn(new byte[]{(byte) 0xab, (byte) 0xc1, 0x23});

        assertThat(storage(fs).fileIdentity("raw/dt=20260924/events.jsonl"))
                .isEqualTo("hdfs:MD5-of-0MD5-of-512CRC32:abc123");
    }

    @Test
    @DisplayName("不提供 checksum 的兼容文件系统使用路径、长度、修改时间组合身份")
    void fileIdentityFallsBackToMetadataWhenChecksumUnavailable() throws IOException {
        FileSystem fs = mock(FileSystem.class);
        Path target = new Path(new Path(URI.create(NAMESPACE)), "raw/events.jsonl");
        when(fs.getFileStatus(target)).thenReturn(new FileStatus(12L, false, 1, 128L, 42L, target));
        when(fs.getFileChecksum(target)).thenReturn(null);

        assertThat(storage(fs).fileIdentity("raw/events.jsonl"))
                .isEqualTo("hdfs-meta:/warehouse/landing/raw/events.jsonl:12:42");
    }

    private static HdfsLandingStorage storage(FileSystem fs) {
        return new HdfsLandingStorage(NAMESPACE, new Configuration(), fs);
    }
}
