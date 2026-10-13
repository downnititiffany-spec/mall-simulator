package com.graduation.generator.adapter;

/**
 * 目标商城操作的响亮失败（§4.1 七项调用的错误出口）。
 *
 * <p><b>为什么是运行时异常而不是返回值</b>：MALL_API 模式下"这次调用到底成没成"直接决定统计口径
 * （{@code success_count}）与是否要落运行状态，返回一个"失败对象"极易被调用方忽略成成功。
 * 抛异常则迫使每一条调用路径显式表态。</p>
 *
 * <p><b>信息里绝不带凭据值</b>（D-033）：异常只允许出现 {@code credential_ref} 的<b>名字</b>
 * （环境变量名/别名），不得出现其取值。</p>
 */
public class MallOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 出错的操作（§4.1 的方法名，便于运行报告与统计归因） */
    private final String operation;
    /** 由商城适配器归一化的稳定业务失败分类；不暴露商城特有错误码作为平台契约。 */
    private final MallFailureCode failureCode;

    public MallOperationException(String operation, String message) {
        this(operation, MallFailureCode.MALL_OPERATION_FAILED, message, null);
    }

    public MallOperationException(String operation, String message, Throwable cause) {
        this(operation, MallFailureCode.MALL_OPERATION_FAILED, message, cause);
    }

    public MallOperationException(String operation, MallFailureCode failureCode, String message) {
        this(operation, failureCode, message, null);
    }

    public MallOperationException(String operation, MallFailureCode failureCode, String message, Throwable cause) {
        super("[" + operation + "] " + message, cause);
        this.operation = operation;
        this.failureCode = failureCode == null ? MallFailureCode.MALL_OPERATION_FAILED : failureCode;
    }

    public String operation() {
        return operation;
    }

    public MallFailureCode failureCode() {
        return failureCode;
    }
}
