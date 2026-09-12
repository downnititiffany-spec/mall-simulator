package com.graduation.generator.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 MALL_API 运行里**真实发生过的**一次商城操作（成功或失败）的对账记录。
 *
 * <p><b>为什么必须有这份流水</b>：文件模式的产物是事件文件本身，看一眼就知道生成了什么；
 * MALL_API 模式的数据长在商城里，如果只留一个计数，"这次运行到底对商城做了什么"就无从核实——
 * 既没法证明 §3.3 A 的"只经公开接口"，也没法在商城侧对账。因此每次调用（含失败、含被能力门挡下的跳过）
 * 都逐条入流水，并在运行结束时落成 {@code OPERATION_JOURNAL} 制品。</p>
 *
 * <p><b>绝不含凭据</b>：只记方法、路径、规范 ID、商城返回的外部 ID 与结果；凭据值不进任何字段
 * （与 D-033 的"异常信息不得回显凭据"同一条纪律）。</p>
 *
 * @param seq           序号（运行内从 1 递增，与封面/排障顺序一致）
 * @param operation     §4.1 的操作名（{@code listProducts}/{@code createSyntheticUser}/…）
 * @param supported    能力判定：{@code true} 表示真发了请求，{@code false} 表示被能力门挡下（未发请求）
 * @param httpMethod    真实用的 HTTP 方法（未发请求时为 {@code null}）
 * @param route         真实请求的路径（未发请求时为 {@code null}）
 * @param canonicalId   生成计划里的规范 ID（用户 {@code U000001}/商品 {@code P00001}/订单 {@code O00000001}）
 * @param externalId    商城返回的外部 ID（雪花 ID 为十进制字符串；失败时为 {@code null}）
 * @param status        {@code OK} / {@code SKIPPED} / {@code FAILED}
 * @param detail        结果说明（失败时为异常摘要；跳过时为缺口原因）
 */
public record OperationJournalEntry(long seq,
                                    String operation,
                                    boolean supported,
                                    String httpMethod,
                                    String route,
                                    String canonicalId,
                                    String externalId,
                                    String status,
                                    String detail) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    /** 流水行 → JSON 对象（键名稳定，便于外部工具对账；不含任何凭据） */
    public Map<String, Object> toJson() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", seq);
        row.put("operation", operation);
        row.put("supported", supported);
        row.put("http_method", httpMethod);
        row.put("route", route);
        row.put("canonical_id", canonicalId);
        row.put("external_id", externalId);
        row.put("status", status);
        row.put("detail", detail);
        return row;
    }

    public boolean succeeded() {
        return STATUS_OK.equals(status);
    }
}
