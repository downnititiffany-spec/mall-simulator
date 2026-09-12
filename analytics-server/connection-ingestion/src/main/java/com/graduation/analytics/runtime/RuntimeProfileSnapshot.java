package com.graduation.analytics.runtime;

import com.graduation.analytics.runtime.entity.RuntimeProfile;

/**
 * 运行环境不可变快照（V2.0 §15.3 R6-10/§26）。
 *
 * 提交器工厂只接受本快照，不直接读可变的 {@link RuntimeProfile} 实体：一次流水线运行
 * 在开始时冻结一份环境配置，运行期间管理员即使修改/重激活档案，也不会让同一次 run 的
 * 前半段与后半段使用不同的 spark-submit / master / 凭据引用（证据可复现）。
 *
 * 只保留提交与命令构造所需的字段；凭据仍是引用（credentialRef），不含明文。
 *
 * <p><b>P2-07 变更（D-070/D-073）</b>：不再携带 {@code hiveDatabasePrefix}
 * （该列的生产读取点清零；物理列保留，写入侧由 {@code RuntimeProfileServiceImpl} 拒绝非空值），
 * 改为携带 {@code sourceId} —— 数仓库名空间按**这一次运行所用的源**解析。
 * 冻结源身份而不冻结前缀本身，是因为前缀是可变的登记值（改源的前缀是有意为之的运维动作），
 * 而"这次运行属于哪个源"在运行开始时就不该再变：中途改源的前缀，已提交作业的库名与
 * 后续作业的库名若不同，血缘会指向两个库。</p>
 */
public record RuntimeProfileSnapshot(
        Long id,
        Long sourceId,
        Integer version,
        String type,
        String landingUri,
        String hiveJdbcUrl,
        String sparkMaster,
        String deployMode,
        String yarnQueue,
        String sshHost,
        Integer sshPort,
        String sshUser,
        String sparkSubmitPath,
        String sparkJobJarUri,
        String metricStoreType,
        String credentialRef,
        String timezone) {

    public static RuntimeProfileSnapshot from(RuntimeProfile p) {
        if (p == null) {
            throw new IllegalArgumentException("RuntimeProfile 不能为空");
        }
        return new RuntimeProfileSnapshot(
                p.getId(), p.getSourceId(), p.getVersion(), p.getType(), p.getLandingUri(),
                p.getHiveJdbcUrl(),
                p.getSparkMaster(), p.getDeployMode(), p.getYarnQueue(),
                p.getSshHost(), p.getSshPort(), p.getSshUser(),
                p.getSparkSubmitPath(), p.getSparkJobJarUri(),
                p.getMetricStoreType(), p.getCredentialRef(), p.getTimezone());
    }

    public boolean isLocal() {
        return RuntimeProfile.TYPE_LOCAL.equals(type);
    }

    public boolean isRemoteCluster() {
        return RuntimeProfile.TYPE_REMOTE_CLUSTER.equals(type);
    }
}
