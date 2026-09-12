package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.warehouse.RunSourceIdentity;
import com.graduation.analytics.warehouse.WarehouseNamespace;
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
 *
 * <p>P2-07（D-076）：库名前缀不再是"从档案列取、空则缺省"——本类改为接收调用方
 * 按**源**解析好的 {@link RunSourceIdentity}（源编码 + 命名空间）。因此原先断言
 * "前缀来自 profile/回落 dw"的用例已删除（那是旧所有权），改名后只断言"传进来的源身份原样下发"。
 * A12：同一份源身份还下发 {@code --sourceSystem}。</p>
 */
class JobCommandBuilderTest {

    /** 本次运行的源身份（真实链路由 WarehouseNamespaceProvider.runSource 一次读取后传入） */
    private static final RunSourceIdentity SRC = new RunSourceIdentity("mock-mall", WarehouseNamespace.of("dw"));
    private static final RunSourceIdentity SRC_B = new RunSourceIdentity("mall-b", WarehouseNamespace.of("dw_b"));

    private final RuntimeProfileSnapshot local = profile(RuntimeProfile.TYPE_LOCAL, "local[2]",
            "D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd",
            "spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");

    private final RuntimeProfileSnapshot remote = profile(RuntimeProfile.TYPE_REMOTE_CLUSTER, "yarn",
            "/opt/spark/bin/spark-submit",
            "hdfs://node01:9000/app/spark-jobs/spark-jobs-0.1.0-SNAPSHOT.jar");

    private static RuntimeProfileSnapshot profile(String type, String master, String submitPath, String jarUri) {
        RuntimeProfile p = new RuntimeProfile();
        p.setId(1L);
        p.setSourceId(1L);
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
        List<String> cmd = JobCommandBuilder.build(local, SRC, "odl", "20260901", 1L, 1,
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
        List<String> cmd = JobCommandBuilder.build(remote, SRC, "odl", "20260901", 3L, 2, extra, Map.of());
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
        List<String> cmd = JobCommandBuilder.build(local, SRC, "usw", "20260901", 1L, 1, Map.of(),
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
        List<String> cmd = JobCommandBuilder.build(p, SRC, "odl", "20260901", 1L, 1, Map.of(), Map.of());
        assertThat(cmd.get(0)).isEqualTo("D:\\Program Files\\spark\\bin\\spark-submit.cmd");
        assertThat(cmd).doesNotContain("Program");
    }

    @Test
    void localUsesConfiguredMasterAndClassAfterSubmitPath() {
        List<String> cmd = JobCommandBuilder.build(local, SRC, "fna", "20260901", 1L, 3, Map.of(), Map.of());
        assertThat(cmd).startsWith("D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd")
                .contains("--master", "local[2]");
        // 命令应保持 提交器可执行顺序：submit path → master → conf → class → jar → 参数
        assertThat(cmd.indexOf("--master")).isLessThan(cmd.indexOf("--class"));
        assertThat(cmd.indexOf("--class")).isLessThan(cmd.size());
    }

    @Test
    void remoteAndLocalDifferButBothLegal() {
        List<String> localCmd = JobCommandBuilder.build(local, SRC, "bdw", "20260901", 1L, 1, Map.of(), Map.of());
        List<String> remoteCmd = JobCommandBuilder.build(remote, SRC, "bdw", "20260901", 1L, 1, Map.of(), Map.of());
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
        List<String> cmd = JobCommandBuilder.build(remote, SRC, "fna", "20260901", 1L, 1, extra, Map.of());
        assertThat(cmd).contains("--periodStart=20260901", "--periodEnd=20260907", "--topN=50");
    }

    // ── P2-07：前缀是**入参**，本类不再读档案列、也不再自己决定缺省 ──────────────

    @Test
    void hiveDatabasePrefixComesFromResolvedNamespace() {
        // 同一个档案快照 + 两个不同的源级命名空间 ⇒ 命令只在源身份相关参数上不同。
        // 这是"库名跟着源走、档案列无权参与"的最小可判据形式。
        List<String> seed = JobCommandBuilder.build(local, SRC, "odl", "20260901", 1L, 1, Map.of(), Map.of());
        List<String> second = JobCommandBuilder.build(local, SRC_B, "odl", "20260901", 1L, 1, Map.of(), Map.of());

        assertThat(seed).contains("--hiveDatabasePrefix=dw");
        assertThat(second).contains("--hiveDatabasePrefix=dw_b");
        assertThat(second).doesNotContain("--hiveDatabasePrefix=dw");
        assertThat(second.stream()
                .filter(a -> !a.startsWith("--hiveDatabasePrefix=") && !a.startsWith("--sourceSystem="))
                .toList())
                .isEqualTo(seed.stream()
                        .filter(a -> !a.startsWith("--hiveDatabasePrefix=") && !a.startsWith("--sourceSystem="))
                        .toList());
    }

    // ── A12（P2-01 对齐）：源编码与库名并列下发，缺一即拒 ─────────────────────────

    @Test
    void sourceSystemComesFromResolvedSourceCode() {
        // ① 逐参数比对（不是子串包含）：整个参数必须**恰好**是 --sourceSystem=mock-mall。
        List<String> cmd = JobCommandBuilder.build(local, SRC, "odl", "20260901", 1L, 1, Map.of(), Map.of());

        assertThat(cmd).contains("--hiveDatabasePrefix=dw", "--sourceSystem=mock-mall");
        assertThat(cmd.stream().filter(a -> a.startsWith("--sourceSystem=")).toList())
                .as("--sourceSystem 必须恰好出现一次，且值为源编码")
                .containsExactly("--sourceSystem=mock-mall");
        assertThat(cmd.stream().filter(a -> a.startsWith("--hiveDatabasePrefix=")).toList())
                .containsExactly("--hiveDatabasePrefix=dw");
    }

    @Test
    void secondSourceSendsItsOwnSourceSystem() {
        // 换成另一个源身份：两个参数**同时**跟着换 —— 证明它们来自同一个解析结果，
        // 不会出现"A 源的库名 + B 源的 source_system"。
        List<String> cmd = JobCommandBuilder.build(local, SRC_B, "odl", "20260901", 1L, 1, Map.of(), Map.of());

        assertThat(cmd.stream().filter(a -> a.startsWith("--sourceSystem=")).toList())
                .containsExactly("--sourceSystem=mall-b");
        assertThat(cmd.stream().filter(a -> a.startsWith("--hiveDatabasePrefix=")).toList())
                .containsExactly("--hiveDatabasePrefix=dw_b");
    }

    @Test
    void sourceSystemParamNameMatchesSparkJobsContract() {
        // 参数名是跨进程字面量：spark-jobs 侧 OdsLoadSql.ArgSourceSystem 必须同名，
        // 写错会在第一个 ODS 作业处失败（而不是静默丢字段）。此处把名字钉住。
        assertThat(JobCommandBuilder.ARG_SOURCE_SYSTEM).isEqualTo("sourceSystem");
        List<String> cmd = JobCommandBuilder.build(local, SRC, "odl", "20260901", 1L, 1, Map.of(), Map.of());
        assertThat(cmd).anyMatch(a -> a.equals("--sourceSystem=mock-mall"));
    }

    @Test
    void sourceIdentityIsRequiredAndNeverGuessed() {
        // ② 源身份缺失 → 拒绝构造命令（即"提交被拒"：本方法是提交前唯一的命令来源）。
        // 空 sourceCode 在 RunSourceIdentity 构造时就拒（解析侧的真实入口），此处补一层
        // "漏传整个身份"的防线：没有任何缺省源编码可以兜底。
        assertThatThrownBy(() -> new RunSourceIdentity(null, WarehouseNamespace.of("dw")))
                .as("源编码为空必须拒绝，不得兜底成某个字面量")
                .isInstanceOf(com.graduation.analytics.common.PlatformBizException.class)
                .hasMessageContaining("source_code")
                .hasMessageContaining("--sourceSystem");
        assertThatThrownBy(() -> new RunSourceIdentity("   ", WarehouseNamespace.of("dw")))
                .isInstanceOf(com.graduation.analytics.common.PlatformBizException.class)
                .hasMessageContaining("source_code");
    }

    @Test
    void nullSourceIdentityIsRejectedInsteadOfGuessed() {
        // 本类不再提供"缺省 dw / 缺省源"这条兜底：调用方必须给出解析结果。
        // （非法前缀/空源编码的拒绝点在解析侧：WarehouseNamespaceProvider.runSource /
        //  SparkStageExecutorFactory.create，见 warehouse-pipeline 的工厂用例。）
        assertThatThrownBy(() -> JobCommandBuilder.build(local, null, "odl", "20260901", 1L, 1,
                Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RunSourceIdentity");
    }
}