package com.graduation.analytics.runtime.credential;

/**
 * 凭据服务（§8.1：数据库只保存引用，不保存明文密码）。凭据存放在环境变量或
 * 密钥文件（不入库不入日志）。LOCAL 模式 credential_ref 为空时按默认值解析。
 */
public interface CredentialService {

    /** 解析 credential_ref 引用的 SSH 密钥/口令；ref 为空时返回默认 credential */
    String resolve(String credentialRef);

    /** 解析 metric_store_config_ref 引用的指标库口令；为空返回默认 metric_read 口令 */
    String resolveMetricPassword(String metricStoreConfigRef);
}