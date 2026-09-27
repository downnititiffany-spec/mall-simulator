package com.graduation.analytics.runtime.storage;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in real-HDFS smoke test. Use a disposable namespace and never point it at a configured warehouse.
 * Example (PowerShell): set LANDING_HDFS_IT_URI and HADOOP_HOME, then run
 * mvn -pl connection-ingestion -Dtest=HdfsLandingStorageIT test.
 */
class HdfsLandingStorageIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "LANDING_HDFS_IT_URI", matches = "hdfs://.+")
    @DisplayName("真实 HDFS 验证 Landing CRUD、定位读取、文件身份与 manifest 幂等")
    void verifiesStorageContractAgainstDisposableHdfsNamespace() throws Exception {
        URI baseUri = URI.create(System.getenv("LANDING_HDFS_IT_URI").trim());
        String runId = "it-" + UUID.randomUUID();
        URI runUri = URI.create(baseUri.toString().replaceAll("/+$", "") + "/" + runId);
        HdfsLandingStorage storage = new HdfsLandingStorage(runUri.toString());
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", baseUri.getScheme() + "://" + baseUri.getAuthority());

        try {
            storage.createDirectories("events/dt=20260924");
            String eventPath = "events/dt=20260924/events.1";
            byte[] content = "one\r\ntwo\nlast".getBytes(StandardCharsets.UTF_8);
            try (var output = storage.createOrAppend(eventPath)) {
                output.write(content);
            }

            assertThat(storage.healthCheck().ok()).isTrue();
            assertThat(storage.stat(eventPath).size()).isEqualTo(content.length);
            assertThat(storage.listFiles("events", true))
                    .extracting(LandingStorage.FileEntry::relativePath)
                    .containsExactly(eventPath);
            assertThat(storage.fileIdentity(eventPath)).isNotBlank();
            assertThat(storage.checkpointKey(eventPath)).startsWith(runUri.toString() + "|");

            try (LandingStorage.SeekableInput input = storage.openSeekable(eventPath)) {
                byte[] suffix = new byte[4];
                input.seek(9);
                assertThat(input.read(suffix, 0, suffix.length)).isEqualTo(4);
                assertThat(new String(suffix, StandardCharsets.UTF_8)).isEqualTo("last");
                assertThat(input.length()).isEqualTo(content.length);
            }

            String manifest = "{\"runId\":\"" + runId + "\",\"status\":\"READY\"}";
            String manifestUri = storage.writeManifest(runId, manifest);
            assertThat(manifestUri).contains("/manifests/" + runId + ".json");
            assertThat(storage.writeManifest(runId, manifest)).isEqualTo(manifestUri);
            assertThatThrownBy(() -> storage.writeManifest(runId, "{}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("内容冲突");
        } finally {
            try (FileSystem fs = FileSystem.get(baseUri, conf)) {
                fs.delete(new Path(runUri), true);
            }
        }
    }
}
