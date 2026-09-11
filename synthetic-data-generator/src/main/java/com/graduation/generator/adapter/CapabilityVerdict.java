package com.graduation.generator.adapter;

/**
 * 单项能力的探测结论：三态，而不是布尔。
 *
 * <p>两态（支持/不支持）会把"没测出来"混进"测出来不支持"，而这两件事对使用者的含义完全相反：
 * {@link #ABSENT} 说明这台商城确实没有这个接口（例如参考商城没有公开行为埋点接口），
 * {@link #UNDETERMINED} 只是说明本次探测不足以判定（服务不可达、路由存在但缺凭据、路径未声明）。</p>
 */
public enum CapabilityVerdict {

    /** 实测支持：所有代表路由都返回了 2xx */
    SUPPORTED,

    /** 实测不存在：至少一条代表路由返回 404 */
    ABSENT,

    /** 本次测不出来：不可达 / 需凭据 / 路径未声明 / 其它未预期状态码 */
    UNDETERMINED
}
