package com.graduation.analytics.runtime.submit;

import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.runtime.credential.CredentialService;
import lombok.extern.slf4j.Slf4j;

/**
 * 提交器工厂（V2.0 §15.3 R6-10 / §26）。
 *
 * 职责单一：按**不可变** {@link RuntimeProfileSnapshot} 构造 {@link JobSubmitter}。
 *   - LOCAL / SINGLE_NODE → {@link LocalProcessSparkSubmitter}（本机 spark-submit 进程）
 *   - REMOTE_CLUSTER      → {@link SshSparkSubmitter}（JSch 会话，凭据经凭据服务解析）
 *
 * 本工厂**只负责构造**，不处理阶段 DAG、不落库、不判定成功失败（那是
 * SparkStageExecutor 与 PipelineService 的职责，§15.3 R6-11）。
 *
 * 缺配置时快速失败（fail-fast），不允许"猜一个默认值"继续提交——早期脚本写死绝对路径
 * 正是整改书 §8.4 要求删除的问题；此处 LOCAL 的 spark-submit 路径若为空也直接报错，
 * 由档案（runtime_profile.spark_submit_path）显式提供。
 */
@Slf4j
public class JobSubmitterFactory {

    private final CredentialService credentialService;
    private final String logRoot;

    public JobSubmitterFactory(CredentialService credentialService, String logRoot) {
        this.credentialService = credentialService;
        this.logRoot = logRoot;
    }

    /**
     * 构造提交器；每次调用返回新实例（提交器无跨 run 状态，但隔离实例可避免日志根串用）。
     *
     * @throws IllegalArgumentException 环境类型不支持或必需字段缺失（fail-fast）
     */
    public JobSubmitter create(RuntimeProfileSnapshot profile) {
        if (profile == null) {
            throw new IllegalArgumentException("RuntimeProfileSnapshot 不能为空");
        }
        if (profile.isRemoteCluster()) {
            return createSsh(profile);
        }
        if (profile.isLocal() || com.graduation.analytics.runtime.entity.RuntimeProfile.TYPE_SINGLE_NODE
                .equals(profile.type())) {
            return createLocal(profile);
        }
        throw new IllegalArgumentException("不支持的环境类型: " + profile.type()
                + "（支持 LOCAL / SINGLE_NODE / REMOTE_CLUSTER）");
    }

    private JobSubmitter createLocal(RuntimeProfileSnapshot profile) {
        String submitPath = profile.sparkSubmitPath();
        if (submitPath == null || submitPath.isBlank()) {
            throw new IllegalArgumentException(
                    "LOCAL/SINGLE_NODE 环境缺少 spark_submit_path，无法构造本地提交器（§8.4 禁止硬编码默认路径）");
        }
        String root = (logRoot == null || logRoot.isBlank()) ? "landing/logs" : logRoot;
        log.debug("JobSubmitterFactory: LOCAL submitter sparkSubmitPath={} logRoot={}", submitPath, root);
        return new LocalProcessSparkSubmitter(submitPath, root);
    }

    private JobSubmitter createSsh(RuntimeProfileSnapshot profile) {
        if (profile.sshHost() == null || profile.sshHost().isBlank()) {
            throw new IllegalArgumentException("REMOTE_CLUSTER 环境缺少 ssh_host");
        }
        if (profile.sshUser() == null || profile.sshUser().isBlank()) {
            throw new IllegalArgumentException("REMOTE_CLUSTER 环境缺少 ssh_user");
        }
        String credential = credentialService == null ? "" : credentialService.resolve(profile.credentialRef());
        int port = profile.sshPort() == null ? 22 : profile.sshPort();
        log.debug("JobSubmitterFactory: REMOTE submitter {}@{}:{}", profile.sshUser(), profile.sshHost(), port);
        return new SshSparkSubmitter(profile.sshHost(), port, profile.sshUser(), credential);
    }
}
