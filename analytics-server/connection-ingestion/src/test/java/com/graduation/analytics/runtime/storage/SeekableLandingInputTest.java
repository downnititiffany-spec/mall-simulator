package com.graduation.analytics.runtime.storage;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeekableLandingInputTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    @DisplayName("本地 Landing 可按字节偏移 seek 并读取 UTF-8 文件原始字节")
    void localStorageReadsFromByteOffset() throws IOException {
        byte[] expected = "订单\nnext\n".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("events.jsonl"), expected);
        LocalLandingStorage storage = new LocalLandingStorage(tempDir.toString());

        try (LandingStorage.SeekableInput input = storage.openSeekable("events.jsonl")) {
            assertThat(input.length()).isEqualTo(expected.length);
            input.seek("订单\n".getBytes(StandardCharsets.UTF_8).length);
            byte[] next = new byte[5];
            int read = input.read(next, 0, next.length);
            assertThat(new String(next, 0, read, StandardCharsets.UTF_8)).isEqualTo("next\n");
        }
    }

    @Test
    @DisplayName("HDFS Landing 适配器将 seek/read/length 映射到 Hadoop 流")
    void hdfsStorageDelegatesSeekAndRead() throws IOException {
        FileSystem fs = mock(FileSystem.class);
        FSDataInputStream input = mock(FSDataInputStream.class);
        FileStatus status = mock(FileStatus.class);
        when(fs.open(any(Path.class))).thenReturn(input);
        when(fs.getFileStatus(any(Path.class))).thenReturn(status);
        when(status.getLen()).thenReturn(6L);
        when(input.read(any(byte[].class), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(2);
        HdfsLandingStorage storage = new HdfsLandingStorage(
                "hdfs://namenode:8020/landing", new Configuration(), fs);

        try (LandingStorage.SeekableInput seekable = storage.openSeekable("raw/events.jsonl")) {
            assertThat(seekable.length()).isEqualTo(6L);
            seekable.seek(4L);
            assertThat(seekable.read(new byte[4], 0, 4)).isEqualTo(2);
        }

        verify(input).seek(4L);
        verify(input).close();
    }

    @Test
    @DisplayName("负字节偏移在本地与 HDFS 后端都拒绝")
    void negativeOffsetIsRejected() throws IOException {
        Files.writeString(tempDir.resolve("events.jsonl"), "x");
        LocalLandingStorage local = new LocalLandingStorage(tempDir.toString());
        try (LandingStorage.SeekableInput input = local.openSeekable("events.jsonl")) {
            assertThatThrownBy(() -> input.seek(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        FileSystem fs = mock(FileSystem.class);
        when(fs.open(any(Path.class))).thenReturn(mock(FSDataInputStream.class));
        HdfsLandingStorage hdfs = new HdfsLandingStorage(
                "hdfs://namenode:8020/landing", new Configuration(), fs);
        try (LandingStorage.SeekableInput input = hdfs.openSeekable("raw/events.jsonl")) {
            assertThatThrownBy(() -> input.seek(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
