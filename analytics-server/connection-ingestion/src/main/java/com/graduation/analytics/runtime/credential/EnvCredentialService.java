package com.graduation.analytics.runtime.credential;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 环境变量凭据服务：凭据只从环境变量/系统属性读取，不落库、不落日志。
 *   - SSH：CRED_SSH_PASSWORD 或 ${user.home}/.ssh 密钥
 *   - 指标库：PLATFORM_METRIC_READ_PASSWORD（与 application.yml 默认一致）
 * LOCAL 演示环境允许默认值（毕业设计本地），生产必须显式配置并禁用默认。
 */
@Service
public class EnvCredentialService implements CredentialService {

    private final String defaultSshCredential;
    private final String defaultMetricPassword;

    public EnvCredentialService(
            @Value("${platform.credential.ssh-default:}") String defaultSshCredential,
            @Value("${platform.metric.read.password:metric_read_pw_2026}") String defaultMetricPassword) {
        this.defaultSshCredential = defaultSshCredential;
        this.defaultMetricPassword = defaultMetricPassword;
    }

    @Override
    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return defaultSshCredential;
        }
        // ref 形如 env:CRED_SSH_PASSWORD（仅支持 env: 前缀，禁止明文内联）
        if (credentialRef.startsWith("env:")) {
            String v = System.getenv(credentialRef.substring(4));
            if (v == null || v.isBlank()) {
                throw new IllegalStateException("凭据环境变量未配置: " + credentialRef.substring(4));
            }
            return v;
        }
        throw new IllegalStateException("不支持的凭据引用格式（仅 env:VAR）: " + credentialRef);
    }

    @Override
    public String resolveMetricPassword(String metricStoreConfigRef) {
        if (metricStoreConfigRef == null || metricStoreConfigRef.isBlank()) {
            return defaultMetricPassword;
        }
        if (metricStoreConfigRef.startsWith("env:")) {
            String v = System.getenv(metricStoreConfigRef.substring(4));
            if (v == null || v.isBlank()) {
                throw new IllegalStateException("指标库凭据环境变量未配置: " + metricStoreConfigRef.substring(4));
            }
            return v;
        }
        throw new IllegalStateException("不支持的指标库凭据引用格式: " + metricStoreConfigRef);
    }
}