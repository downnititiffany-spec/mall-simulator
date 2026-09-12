package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.pipeline.entity.SparkJobRun;
import com.graduation.analytics.pipeline.mapper.SparkJobRunMapper;
import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.runtime.submit.FakeJobSubmitter;
import com.graduation.analytics.runtime.submit.JobSubmitterFactory;
import com.graduation.analytics.warehouse.RunSourceIdentity;
import com.graduation.analytics.warehouse.WarehouseNamespace;
import com.graduation.analytics.warehouse.WarehouseNamespaceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-07 + A12 的**提交前拒绝**与**端到端装配**测试（L1，不启动 Spark）。
 *
 * <p>为什么必须有这个类：库名前缀与源编码的"解析一次、整轮复用"发生在
 * {@link SparkStageExecutorFactory#create}，它是生产链上唯一把
 * {@link WarehouseNamespaceProvider} 与 {@link JobCommandBuilder} 接起来的地方。
 * 只测 builder（纯函数，入参已合法）无法证明"库里的坏值/缺失值到不了 spark-submit"。</p>
 *
 * <p>本类断言两件事：① 解析失败时 {@code create()} 抛错、**零提交**（submitter 一次都没被调用）；
 * ② 解析成功时，源身份确实一路走到命令行（{@code --hiveDatabasePrefix} 与 {@code --sourceSystem}）。
 * 真实链（8091 触发、Spark 侧读到参数）不在此类取证。</p>
 */
class SparkStageExecutorFactoryTest {

    private static final String SUBMIT_PATH = "D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd";

    private final SparkJobRunMapper runMapper = mock(SparkJobRunMapper.class);

    private static RuntimeProfileSnapshot profile(Long sourceId) {
        RuntimeProfile p = new RuntimeProfile();
        p.setId(7L);
        p.setSourceId(sourceId);
        p.setVersion(3);
        p.setType(RuntimeProfile.TYPE_LOCAL);
        p.setSparkMaster("local[2]");
        p.setSparkSubmitPath(SUBMIT_PATH);
        p.setSparkJobJarUri("spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");
        return RuntimeProfileSnapshot.from(p);
    }

    /** 只实现被本类用到的两个抽象方法：runSource / currentRunSource */
    private static WarehouseNamespaceProvider providerReturning(RunSourceIdentity identity) {
        return new WarehouseNamespaceProvider() {
            @Override
            public RunSourceIdentity runSource(Long sourceId) {
                return identity;
            }

            @Override
            public RunSourceIdentity currentRunSource() {
                return identity;
            }
        };
    }

    private static WarehouseNamespaceProvider providerFailing(PlatformBizException failure) {
        return new WarehouseNamespaceProvider() {
            @Override
            public RunSourceIdentity runSource(Long sourceId) {
                throw failure;
            }

            @Override
            public RunSourceIdentity currentRunSource() {
                throw failure;
            }
        };
    }

    private SparkStageExecutorFactory factory(WarehouseNamespaceProvider provider, FakeJobSubmitter submitter) {
        JobSubmitterFactory submitterFactory = mock(JobSubmitterFactory.class);
        when(submitterFactory.create(any(RuntimeProfileSnapshot.class))).thenReturn(submitter);
        return new SparkStageExecutorFactory(submitterFactory, runMapper, provider,
                2_000L, 5L, "tests/p2-07-factory/warehouse", "tests/p2-07-factory/metastore");
    }

    @Test
    @DisplayName("① 前缀非法：create() 抛 WAREHOUSE_PREFIX_*，且零提交")
    void illegalPrefixFailsBeforeAnySubmit() {
        FakeJobSubmitter submitter = new FakeJobSubmitter();
        PlatformBizException failure = new PlatformBizException("WAREHOUSE_PREFIX_LAYER_SUFFIX",
                "warehouse_prefix 非法（WAREHOUSE_PREFIX_LAYER_SUFFIX）：dw_ods");
        SparkStageExecutorFactory factory = factory(providerFailing(failure), submitter);

        assertThatThrownBy(() -> factory.create(profile(5L)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo("WAREHOUSE_PREFIX_LAYER_SUFFIX"))
                .hasMessageContaining("WAREHOUSE_PREFIX_LAYER_SUFFIX");
        assertThat(submitter.submitCount()).as("解析失败时不得有任何提交").isZero();
    }

    @Test
    @DisplayName("① 运行环境未绑定源：create() 抛 SOURCE_NOT_BOUND（409 语义），且零提交")
    void unboundSourceFailsBeforeAnySubmit() {
        FakeJobSubmitter submitter = new FakeJobSubmitter();
        SparkStageExecutorFactory factory = factory(providerFailing(new PlatformBizException(
                PlatformBizException.SOURCE_NOT_BOUND, "运行环境未绑定源…")), submitter);

        assertThatThrownBy(() -> factory.create(profile(null)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.SOURCE_NOT_BOUND));
        assertThat(submitter.submitCount()).isZero();
    }

    @Test
    @DisplayName("① 源身份缺失（source_code 为空）：create() 抛 PARAM_INVALID，且零提交")
    void missingSourceCodeFailsBeforeAnySubmit() {
        FakeJobSubmitter submitter = new FakeJobSubmitter();
        SparkStageExecutorFactory factory = factory(providerFailing(new PlatformBizException(
                PlatformBizException.PARAM_INVALID,
                "source_registry.source_code 为空，无法确定本次运行的源身份（--sourceSystem）")), submitter);

        assertThatThrownBy(() -> factory.create(profile(5L)))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.PARAM_INVALID))
                .hasMessageContaining("--sourceSystem");
        assertThat(submitter.submitCount()).isZero();
    }

    @Test
    @DisplayName("② 解析成功：源身份一路走到命令行（--hiveDatabasePrefix 与 --sourceSystem 同时出现）")
    void resolvedIdentityReachesCommandLine() {
        FakeJobSubmitter submitter = new FakeJobSubmitter();
        submitter.program("SUBMITTED",
                "{\"jobCode\":\"odl\",\"inputRecords\":1,\"outputRecords\":1,\"rejectedRecords\":0,"
                        + "\"attemptNo\":1,\"status\":\"SUCCESS\",\"message\":\"ok\",\"elapsedMs\":10}");
        when(runMapper.insert(any(SparkJobRun.class))).thenAnswer(inv -> {
            inv.getArgument(0, SparkJobRun.class).setId(1L);
            return 1;
        });
        when(runMapper.updateById(any(SparkJobRun.class))).thenReturn(1);

        SparkStageExecutorFactory factory = factory(providerReturning(
                new RunSourceIdentity("mock-mall", WarehouseNamespace.of("dw_b"))), submitter);
        SparkStageExecutor executor = factory.create(profile(5L));

        List<SparkStageExecutor.JobExecution> jobs = executor
                .executeStage(profile(5L), 1L, "LOAD_ODS", "20260901", 1, Map.of()).jobs();

        assertThat(jobs).hasSize(1);
        assertThat(submitter.submitCount()).isEqualTo(1);
        assertThat(submitter.lastCommand().split(" "))
                .as("源身份的两个参数必须同时下发（同一次解析的结果）")
                .contains("--hiveDatabasePrefix=dw_b", "--sourceSystem=mock-mall")
                .doesNotContain("--hiveDatabasePrefix=dw");
    }
}
