package com.graduation.analytics.runtime.submit;

import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.credential.CredentialService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R6-10（V2.0 §15.3）：提交器工厂快速测试（不启动 Spark）。
 *
 * ①LOCAL / SINGLE_NODE → local-process；REMOTE_CLUSTER → ssh；
 * ②缺 spark_submit_path / ssh_host / ssh_user 时 fail-fast（§8.4 禁止硬编码默认路径）；
 * ③未知环境类型明确报错；
 * ④输入是不可变快照：档案在 run 中途被改不影响已冻结的提交器选择。
 */
class JobSubmitterFactoryTest {

    private static final CredentialService CREDS = new CredentialService() {
        @Override
        public String resolve(String credentialRef) {
            return "secret-from-test";
        }

        @Override
        public String resolveMetricPassword(String metricStoreConfigRef) {
            return "metric-pw-test";
        }
    };

    private final JobSubmitterFactory factory = new JobSubmitterFactory(CREDS, "landing/logs");

    private static RuntimeProfile prof(String type) {
        RuntimeProfile p = new RuntimeProfile();
        p.setId(1L);
        p.setVersion(3);
        p.setType(type);
        p.setSparkSubmitPath("D:\\spark\\bin\\spark-submit.cmd");
        p.setSparkJobJarUri("spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar");
        p.setSshHost("spark-master.local");
        p.setSshPort(2222);
        p.setSshUser("hadoop");
        p.setCredentialRef("env:SPARK_SSH_KEY");
        return p;
    }

    @Test
    void localProfileYieldsLocalProcessSubmitter() {
        JobSubmitter s = factory.create(RuntimeProfileSnapshot.from(prof(RuntimeProfile.TYPE_LOCAL)));
        assertThat(s.type()).isEqualTo("local-process");
    }

    @Test
    void singleNodeProfileYieldsLocalProcessSubmitter() {
        JobSubmitter s = factory.create(RuntimeProfileSnapshot.from(prof(RuntimeProfile.TYPE_SINGLE_NODE)));
        assertThat(s.type()).isEqualTo("local-process");
    }

    @Test
    void remoteClusterProfileYieldsSshSubmitter() {
        JobSubmitter s = factory.create(RuntimeProfileSnapshot.from(prof(RuntimeProfile.TYPE_REMOTE_CLUSTER)));
        assertThat(s.type()).isEqualTo("ssh");
    }

    @Test
    void localWithoutSparkSubmitPathFailsFast() {
        RuntimeProfile p = prof(RuntimeProfile.TYPE_LOCAL);
        p.setSparkSubmitPath(null);
        assertThatThrownBy(() -> factory.create(RuntimeProfileSnapshot.from(p)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spark_submit_path");
    }

    @Test
    void remoteWithoutSshHostFailsFast() {
        RuntimeProfile p = prof(RuntimeProfile.TYPE_REMOTE_CLUSTER);
        p.setSshHost("  ");
        assertThatThrownBy(() -> factory.create(RuntimeProfileSnapshot.from(p)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ssh_host");
    }

    @Test
    void remoteWithoutSshUserFailsFast() {
        RuntimeProfile p = prof(RuntimeProfile.TYPE_REMOTE_CLUSTER);
        p.setSshUser(null);
        assertThatThrownBy(() -> factory.create(RuntimeProfileSnapshot.from(p)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ssh_user");
    }

    @Test
    void unknownTypeIsRejected() {
        RuntimeProfile p = prof("KUBERNETES");
        assertThatThrownBy(() -> factory.create(RuntimeProfileSnapshot.from(p)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的环境类型");
    }

    /** ④快照不可变：构造提交器后修改原实体，不影响已冻结快照的选择结果 */
    @Test
    void snapshotFreezesProfileSoLaterEditsDoNotAffectSubmitter() {
        RuntimeProfile p = prof(RuntimeProfile.TYPE_LOCAL);
        RuntimeProfileSnapshot snapshot = RuntimeProfileSnapshot.from(p);

        // 管理员随后把档案改成 REMOTE_CLUSTER（模拟运行中被改）
        p.setType(RuntimeProfile.TYPE_REMOTE_CLUSTER);

        JobSubmitter s = factory.create(snapshot);
        assertThat(s.type()).as("已冻结快照仍按 LOCAL 选择提交器").isEqualTo("local-process");
        assertThat(snapshot.type()).isEqualTo(RuntimeProfile.TYPE_LOCAL);
        assertThat(snapshot.version()).isEqualTo(3);
    }
}
