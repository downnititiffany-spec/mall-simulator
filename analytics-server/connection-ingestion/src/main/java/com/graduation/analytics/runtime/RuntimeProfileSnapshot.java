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
 */
public record RuntimeProfileSnapshot(
        Long id,
        Integer version,
        String type,
        String landingUri,
        String hiveJdbcUrl,
        String hiveDatabasePrefix,
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
                p.getId(), p.getVersion(), p.getType(), p.getLandingUri(),
                p.getHiveJdbcUrl(), p.getHiveDatabasePrefix(),
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
