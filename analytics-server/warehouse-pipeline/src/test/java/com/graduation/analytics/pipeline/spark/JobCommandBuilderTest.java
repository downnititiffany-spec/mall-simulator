package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R6 快速测试（L0，不启动 Spark）：JobCommandBuilder 把 RuntimeProfile 快照 + 作业参数
 * 转成 spark-submit 命令数组。覆盖：JAR 路径来自 profile、参数齐全、--conf 在
 * --class 之前、路径带空格仍为单个参数、LOCAL/REMOTE 生成不同但合法的命令。
 */
class JobCommandBuilderTest {

    private final RuntimeProfileSnapshot local = profile(RuntimeProfile.TYPE_LOCAL, "local[2]",
            "D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd",
            "spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");

    private final RuntimeProfileSnapshot remote = profile(RuntimeProfile.TYPE_REMOTE_CLUSTER, "yarn",
            "/opt/spark/bin/spark-submit",
            "hdfs://node01:9000/app/spark-jobs/spark-jobs-0.1.0-SNAPSHOT.jar");

    private static RuntimeProfileSnapshot profile(String type, String master, String submitPath, String jarUri) {
        RuntimeProfile p = new RuntimeProfile();
        p.setId(1L);
        p.setVersion(1);
        p.setType(type);
        p.setSparkMaster(master);
        p.setSparkSubmitPath(submitPath);
        p.setSparkJobJarUri(jarUri);
        p.setDeployMode("cluster");
        return RuntimeProfileSnapshot.from(p);
    }

    @Test
    void jarPathComesFromRuntimeProfile() {
        List<String> cmd = JobCommandBuilder.build(local, "odl", "20260901", 1L, 1,
                Map.of(), Map.of("landingDir", "file:///D:/landing/accepted", "batchId", "17"));
        // 本地相对 jar 路径必须绝对化为 file:///（历史教训：No FileSystem for scheme "D"）
        assertThat(cmd).anyMatch(a -> a.endsWith("spark-jobs-0.1.0-SNAPSHOT.jar"));
        assertThat(cmd).anyMatch(a -> a.startsWith("file:///"));
    }

    @Test
    void requiredArgsAreComplete() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("landingDir", "file:///D:/landing/accepted");
        extra.put("batchId", "17");
        extra.put("outputSnapshotId", "S20260901_5");
        List<String> cmd = JobCommandBuilder.build(remote, "odl", "20260901", 3L, 2, extra, Map.of());
        assertThat(cmd).contains(
                "--runtimeProfileId=3",
                "--jobCode=odl",
                "--businessDate=20260901",
                "--attemptNo=2",
                "--outputSnapshotId=S20260901_5",
                "--landingDir=file:///D:/landing/accepted",
                "--batchId=17");
    }

    @Test
    void confsComeBeforeClass() {
        List<String> cmd = JobCommandBuilder.build(local, "usw", "20260901", 1L, 1, Map.of(),
                Map.of("spark.sql.warehouse.dir", "file:///D:/wh", "spark.shuffle.partitions", "8"));
        int classIdx = cmd.indexOf("--class");
        int confIdx = cmd.indexOf("--conf");
        assertThat(classIdx).isGreaterThan(-1);
        assertThat(confIdx).isGreaterThan(-1);
        assertThat(confIdx).isLessThan(classIdx);
        assertThat(cmd).contains("--class", "com.graduation.analytics.job.JobRunner");
        assertThat(cmd).contains("--conf", "spark.sql.warehouse.dir=file:///D:/wh");
        assertThat(cmd).contains("--conf", "spark.shuffle.partitions=8");
    }

    @Test
    void pathWithSpacesStaysSingleArgument() {
        RuntimeProfileSnapshot p = profile(RuntimeProfile.TYPE_LOCAL, "local[2]",
                "D:\\Program Files\\spark\\bin\\spark-submit.cmd", "some dir/jar/spark-jobs.jar");
        List<String> cmd = JobCommandBuilder.build(p, "odl", "20260901", 1L, 1, Map.of(), Map.of());
        assertThat(cmd.get(0)).isEqualTo("D:\\Program Files\\spark\\bin\\spark-submit.cmd");
        assertThat(cmd).doesNotContain("Program");
    }

    @Test
    void localUsesConfiguredMasterAndClassAfterSubmitPath() {
        List<String> cmd = JobCommandBuilder.build(local, "fna", "20260901", 1L, 3, Map.of(), Map.of());
        assertThat(cmd).startsWith("D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd")
                .contains("--master", "local[2]");
        // 命令应保持 提交器可执行顺序：submit path → master → conf → class → jar → 参数
        assertThat(cmd.indexOf("--master")).isLessThan(cmd.indexOf("--class"));
        assertThat(cmd.indexOf("--class")).isLessThan(cmd.size());
    }

    @Test
    void remoteAndLocalDifferButBothLegal() {
        List<String> localCmd = JobCommandBuilder.build(local, "bdw", "20260901", 1L, 1, Map.of(), Map.of());
        List<String> remoteCmd = JobCommandBuilder.build(remote, "bdw", "20260901", 1L, 1, Map.of(), Map.of());
        assertThat(localCmd).isNotEqualTo(remoteCmd);
        // REMOTE：jar 已是 hdfs:// URI，不应被本地绝对化；deploy-mode 保留
        assertThat(remoteCmd).anyMatch(a -> a.startsWith("hdfs://node01"));
        assertThat(remoteCmd).contains("--deploy-mode", "cluster");
        // LOCAL：master 来自 profile
        assertThat(localCmd).contains("--master", "local[2]");
    }

    @Test
    void extraArgsArePassedThrough() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("periodStart", "20260901");
        extra.put("periodEnd", "20260907");
        extra.put("topN", "50");
        List<String> cmd = JobCommandBuilder.build(remote, "fna", "20260901", 1L, 1, extra, Map.of());
        assertThat(cmd).contains("--periodStart=20260901", "--periodEnd=20260907", "--topN=50");
    }

    // ── P1-04：库名前缀由 runtime_profile 派生，且非法值在提交前失败 ──────────────

    @Test
    void hiveDatabasePrefixComesFromProfileOrDefaultsToDw() {
        // NULL（源 A 存量档）→ 缺省 dw：与改造前库名逐字一致，零迁移
        List<String> legacy = JobCommandBuilder.build(local, "odl", "20260901", 1L, 1, Map.of(), Map.of());
        assertThat(legacy).contains("--hiveDatabasePrefix=dw");

        // 非缺省前缀（第二个源）→ 原样传给 JobRunner，由 Scala 侧同一规格派生 dw_b_ods…
        RuntimeProfileSnapshot second = withPrefix(local, "dw_b");
        List<String> other = JobCommandBuilder.build(second, "odl", "20260901", 1L, 1, Map.of(), Map.of());
        assertThat(other).contains("--hiveDatabasePrefix=dw_b");
        assertThat(other).doesNotContain("--hiveDatabasePrefix=dw");
    }

    @Test
    void invalidPrefixFailsBeforeSubmit() {
        // 校验发生在构造命令数组时（即 spark-submit 进程启动之前），且不 trim/不兜底
        for (String bad : List.of("dw_ods", "DW", " dw", "dw__b", "default", "dw_")) {
            RuntimeProfileSnapshot p = withPrefix(local, bad);
            assertThatThrownBy(() -> JobCommandBuilder.build(p, "odl", "20260901", 1L, 1, Map.of(), Map.of()))
                    .as("非法前缀 %s 必须在提交前失败", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WAREHOUSE_PREFIX_");
        }
    }

    private static RuntimeProfileSnapshot withPrefix(RuntimeProfileSnapshot base, String prefix) {
        return new RuntimeProfileSnapshot(base.id(), base.version(), base.type(), base.landingUri(),
                base.hiveJdbcUrl(), prefix, base.sparkMaster(), base.deployMode(), base.yarnQueue(),
                base.sshHost(), base.sshPort(), base.sshUser(), base.sparkSubmitPath(),
                base.sparkJobJarUri(), base.metricStoreType(), base.credentialRef(), base.timezone());
    }
}