package com.graduation.analytics.common;

/**
 * 平台业务异常（M1-6 由整改前的商城命名改名而来，旧名与根因见《开发过程事实与决策记录》D-023）：
 * code 为稳定错误码（REST 返回），message 面向调用方。
 *
 * <p>命名规则：分析平台自有异常一律 {@code Platform*}——平台不隶属任何具体商城（V2.1 §3.1/§3.4-4），
 * 由 {@code PlatformMallBoundarySourcePolicyTest} 的"商城命名遗留"规则常驻钉住（守卫自身也因此
 * 要求平台主子源码里不出现旧类名，故此处只留指针、不复述那个字面量）。</p>
 */
public class PlatformBizException extends RuntimeException {

    private final String code;

    public PlatformBizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    // 常用错误码（§23.2 错误分类在平台层做完整映射）
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String PARAM_INVALID = "PARAM_INVALID";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String INTERNAL = "INTERNAL";

    // M1-6 顺带清理（2026-09-11，AE-04）：这里原先还有 8 个**商城域**错误码
    // （PRODUCT_NOT_FOUND / PRODUCT_OFF_SALE / INSUFFICIENT_STOCK / ORDER_NOT_FOUND /
    //  ORDER_OWNER_MISMATCH / ORDER_STATE_ILLEGAL / REFUND_EXCEEDS_PAID / REFUND_NOT_FOUND），
    // 是整改前"平台与商城同进程"的残留：全仓引用计数为 0（平台从不抛它们），商城程序另有自己的
    // 同名常量。平台不复制、不映射具体商城的业务词汇——这类"原始 code → 规范语义"的映射属
    // 每源配置（见《分析平台商城无关化改造设计》P3 语义注册表），故直接删除而不是留在平台侧。
}