package com.graduation.analytics.runtime.storage;

import com.graduation.analytics.landing.LandingInputScanner;
import com.graduation.analytics.landing.LandingLayout;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HdfsLandingStorageScannerTest {

    @Test
    @DisplayName("存储无关扫描器递归枚举 HDFS FLUME_RAW 分区路径并过滤临时文件")
    void scansHdfsFilesThroughStorageContract() throws Exception {
        String namespace = "hdfs://namenode:8020/landing";
        String raw = namespace + "/raw";
        String day = raw + "/dt=20260924";
        String hour = day + "/hour=08";
        Map<String, FileStatus> statuses = Map.of(
                raw + "/dt=20260924", status(raw + "/dt=20260924", true, 0),
                day + "/hour=08", status(day + "/hour=08", true, 0),
                hour + "/events.2", status(hour + "/events.2", false, 14),
                hour + "/events.tmp", status(hour + "/events.tmp", false, 7));
        FileSystem fs = mock(FileSystem.class);
        when(fs.listStatus(any(Path.class))).thenAnswer(invocation -> {
            String path = ((Path) invocation.getArgument(0)).toString();
            if (path.equals(raw)) {
                return new FileStatus[]{statuses.get(raw + "/dt=20260924")};
            }
            if (path.equals(day)) {
                return new FileStatus[]{statuses.get(day + "/hour=08")};
            }
            if (path.equals(hour)) {
                return new FileStatus[]{statuses.get(hour + "/events.tmp"), statuses.get(hour + "/events.2")};
            }
            return new FileStatus[0];
        });
        when(fs.getFileStatus(any(Path.class))).thenAnswer(invocation ->
                statuses.get(((Path) invocation.getArgument(0)).toString()));
        HdfsLandingStorage storage = new HdfsLandingStorage(namespace, new Configuration(), fs);

        assertThat(LandingInputScanner.scan(storage, "raw", LandingLayout.FLUME_RAW))
                .extracting(LandingInputScanner.StoredFile::inputKey)
                .containsExactly("dt=20260924/hour=08/events.2");
    }

    private static FileStatus status(String path, boolean directory, long size) {
        FileStatus status = mock(FileStatus.class);
        when(status.getPath()).thenReturn(new Path(URI.create(path)));
        when(status.isDirectory()).thenReturn(directory);
        when(status.getLen()).thenReturn(size);
        when(status.getModificationTime()).thenReturn(1_758_600_000_000L);
        return status;
    }
}
