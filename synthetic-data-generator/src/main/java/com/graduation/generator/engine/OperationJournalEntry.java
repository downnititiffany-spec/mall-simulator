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
 * <p><b>D12：三类行必须分得开</b>。流水里混着三种东西——①真发出去的请求；②生成器自己的本地对齐记账
 * （商品目录对齐用的是预检已取回的目录；退款完成复用申请那次的退款单）；③被能力门挡下的缺口。
 * 三者以前长得一样（本地记账行同样带 {@code GET /api/v1/mall/products}），于是"这次运行到底对商城发了
 * 多少次请求"被系统性高估。现在由 {@link #realHttp()} 与 {@link #localAccounting()} 两个显式字段分开，
 * 且二者互斥、与 {@code SKIPPED} 三分：一行只能属于其中一类。</p>
 *
 * @param seq           序号（运行内从 1 递增，与封面/排障顺序一致）
 * @param operation     §4.1 的操作名（{@code listProducts}/{@code createSyntheticUser}/…）
 * @param supported     能力门判定：该操作在目标能力声明里是否 {@code SUPPORTED}。
 *                      <b>它不表示"发过请求"</b>——发没发请求只看 {@link #realHttp()}
 * @param httpMethod    真发了请求时用的 HTTP 方法（没发请求时为 {@code null}）
 * @param route         真发了请求的路径（没发请求时为 {@code null}）
 * @param canonicalId   生成计划里的规范 ID（用户 {@code U000001}/商品 {@code P00001}/订单 {@code O00000001}）
 * @param externalId    商城返回的外部 ID（雪花 ID 为十进制字符串；失败时为 {@code null}）
 * @param status        {@code OK} / {@code SKIPPED} / {@code FAILED}
 * @param detail        结果说明（失败时为异常摘要；跳过时为缺口原因）
 * @param localAccounting 本地对齐记账：这一行只记"生成器把规范 ID 对到了商城已有的东西"，
 *                      <b>没有向商城发任何请求</b>（商品对齐 / 复用已创建用户 / 复用已完成退款）
 */
public record OperationJournalEntry(long seq,
                                    String operation,
                                    boolean supported,
                                    String httpMethod,
                                    String route,
                                    String canonicalId,
                                    String externalId,
                                    String status,
                                    String detail,
                                    boolean localAccounting) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    public OperationJournalEntry {
        if (localAccounting && (httpMethod != null || route != null)) {
            throw new IllegalArgumentException("本地记账行不得带请求方法/路径——没发请求就没有请求形状："
                    + operation + " " + httpMethod + " " + route);
        }
        if (localAccounting && !STATUS_OK.equals(status)) {
            throw new IllegalArgumentException("本地记账行只能是 " + STATUS_OK
                    + "（它记的是生成器自己完成的对照，不是商城结果）：" + status);
        }
    }

    /**
     * 这一行是否对应真发出去的 HTTP 请求（发过就算，失败也算）。
     *
     * <p>刻意<b>派生</b>而不是再存一个布尔：只要 {@code http_method}/{@code route} 在，
     * 就必然发过请求；不派生就会出现"字段说发过、方法路径却是空的"这种自相矛盾的行。</p>
     */
    public boolean realHttp() {
        return httpMethod != null && route != null;
    }

    /** 流水行 → JSON 对象（键名稳定，便于外部工具对账；不含任何凭据） */
    public Map<String, Object> toJson() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", seq);
        row.put("operation", operation);
        row.put("supported", supported);
        row.put("real_http", realHttp());
        row.put("local_accounting", localAccounting);
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
