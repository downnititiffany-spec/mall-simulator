package com.graduation.generator.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * §4.4 七个端点的请求/响应体。
 *
 * <p><b>字段名逐项照抄冻结契约</b> {@code contract-specs/openapi/generator-api.v1.yaml}（components.schemas），
 * 不自行改名、不加字段：契约里这些 schema 全部标注了 {@code additionalProperties: false}，
 * 多写一个键就是违约。命名风格本身在契约里是混的（多数 snake_case，启动响应却是 {@code runId}），
 * 这里按契约原样保留，不做"统一美化"。</p>
 *
 * <p>可空字段用 {@link JsonInclude.Include#NON_NULL} 省略而不是写 null：契约只要求必填键存在，
 * 省略比塞 null 更不容易被误读成"有值且为空"。</p>
 */
public final class GeneratorApiDtos {

    private GeneratorApiDtos() {
    }

    /**
     * {@code GenerationRunStartRequest}：只承载被引用的不可变计划版本（{@code required: [plan_id, version]}）。
     *
     * <p>契约刻意不允许在这里塞场景/种子/时间窗——那些必须来自计划版本，否则"运行只引用不可变版本"
     * 就成了空话。计划版本由生成器 CLI 创建（见 {@code GeneratorCli}），本 API 不发明计划创建端点。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StartRunRequest(
            @JsonProperty("plan_id") String planId,
            @JsonProperty("version") Integer version) {
    }

    /** {@code GenerationRunStarted}：{@code required: [runId]}，且 {@code additionalProperties: false} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunStarted(@JsonProperty("runId") String runId) {
    }

    /**
     * {@code GenerationRun}：字段名照 §4.2 原样（{@code started}/{@code finished}/{@code error}，
     * 契约明确说明不擅自改写成 {@code started_at} 等）。
     *
     * <p>契约里没有制品与事件统计的位置，故本对象不带它们：制品走
     * {@code GET /generation-runs/{id}/artifacts}，事件统计契约明确"没有端点、只登记结构"。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunView(
            @JsonProperty("run_id") String runId,
            @JsonProperty("plan_version") Integer planVersion,
            @JsonProperty("target_version") Integer targetVersion,
            @JsonProperty("status") String status,
            @JsonProperty("started") String started,
            @JsonProperty("finished") String finished,
            @JsonProperty("success_count") Long successCount,
            @JsonProperty("failed_count") Long failedCount,
            @JsonProperty("checksum") String checksum,
            @JsonProperty("error") String error,
            @JsonProperty("cancel_requested") Boolean cancelRequested) {
    }

    /**
     * {@code GenerationArtifact}：{@code required: [run_id, uri, checksum, schema_version]}。
     *
     * <p>{@code synthetic} 取值枚举就是 {@code [true]}——文件模式的制品必须标 synthetic，
     * 这是 §3.3 B 的硬要求，所以此处不是"可选装饰"。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArtifactView(
            @JsonProperty("run_id") String runId,
            @JsonProperty("uri") String uri,
            @JsonProperty("checksum") String checksum,
            @JsonProperty("bytes") Long bytes,
            @JsonProperty("record_count") Long recordCount,
            @JsonProperty("min_event_time") String minEventTime,
            @JsonProperty("max_event_time") String maxEventTime,
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("synthetic") Boolean synthetic) {
    }

    /**
     * {@code Scenario}：契约对这个对象**没有**声明任何 properties（空 schema + x-unspecified），
     * 并明确"生成器实现不得据此发明对外字段后回写成契约"。这里的字段是本实现的输出约定，
     * 已在《开发过程事实与决策记录》D-015 登记为待冻结项，不声称为契约字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScenarioView(
            @JsonProperty("code") String code,
            @JsonProperty("label") String label,
            @JsonProperty("expected_directions") Map<String, String> expectedDirections) {
    }

    /**
     * {@code GeneratorTarget} 的写入体（POST/PUT 共用；契约的 requestBody 就是该 schema）。
     *
     * <p>{@code credential_ref} 只允许引用（环境变量名/密钥别名），不接受明文口令——契约与 §4.2 都写了
     * "凭据只存引用"。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetRequest(
            @JsonProperty("id") Long id,
            @JsonProperty("name") String name,
            @JsonProperty("adapter_type") String adapterType,
            @JsonProperty("base_url") String baseUrl,
            @JsonProperty("credential_ref") String credentialRef,
            @JsonProperty("config_json") String configJson,
            @JsonProperty("status") String status,
            @JsonProperty("test_environment") Boolean testEnvironment,
            @JsonProperty("capabilities") String capabilities) {
    }

    /** {@code GeneratorTarget} 的读出体：字段与 §4.2 的 {@code generator_target} 十列一一对应 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetView(
            @JsonProperty("id") Long id,
            @JsonProperty("name") String name,
            @JsonProperty("adapter_type") String adapterType,
            @JsonProperty("base_url") String baseUrl,
            @JsonProperty("credential_ref") String credentialRef,
            @JsonProperty("config_json") String configJson,
            @JsonProperty("config_version") Integer configVersion,
            @JsonProperty("status") String status,
            @JsonProperty("test_environment") Boolean testEnvironment,
            @JsonProperty("capabilities") String capabilities) {
    }

    /**
     * {@code TargetCheckResult}：契约**没有**给出任何字段（"不发明'检查项/是否通过/耗时'等字段"），
     * 但 §4.4 要求该端点必须返回检查结果，所以这里返回最小可核实的三项，并在 D-015 登记为待冻结。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetCheckView(
            @JsonProperty("target_id") Long targetId,
            @JsonProperty("reachable") boolean reachable,
            @JsonProperty("detail") String detail) {
    }
}
