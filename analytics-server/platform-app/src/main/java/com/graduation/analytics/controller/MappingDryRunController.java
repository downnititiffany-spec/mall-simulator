package com.graduation.analytics.controller;

import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.auth.RequiresPermission;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.mapping.dryrun.MappingDryRunReport;
import com.graduation.analytics.mapping.dryrun.MappingDryRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 映射 dry-run（S2-01B，设计 §7.3 规则 10 / §7.4）：**只预览、不激活**。
 *
 * <pre>
 *   POST /api/v1/sources/{sourceId}/mappings/dry-run          候选画像 + 受控样本 → 报告
 *   GET  /api/v1/sources/{sourceId}/mappings/dry-runs/{reportId}  取回本进程内的一张报告
 * </pre>
 *
 * <p><b>为什么入参传画像"原文"而不是路径/对象</b>：报告里的 {@code profileChecksum} 是**权威画像哈希**
 * （S2-01A 冻结：Loader 对原始画像字节做 sha256）。若让调用方传本机路径，服务端就得读服务端文件系统——
 * 正是必须避免的"任意本机路径"；若传 JSON 对象，Jackson 反序列化再序列化会改变空白与键序，
 * 哈希就不再是"那一版画像"的哈希。传原文（JSON 文本字符串）让"哈希 = 你提交的字节"成立。</p>
 *
 * <p><b>权限</b>：整类复用既有的 {@link PermissionCode#RUNTIME_MANAGE}（第 9 个权限码）。
 * 不新增第 10 个权限码——那会牵动冻结的 {@code PermissionCode.ALL}/{@code MATRIX_ORDER} 与权限矩阵测试；
 * 「谁可以试算映射」与「谁可以改源登记/运行环境」在 V2 里是同一类运维角色。</p>
 *
 * <p><b>审计</b>：dry-run **不写审计行、不写库**。它是零副作用的只读试算；写审计行就等于把"预览"
 * 变成了一次被记录的状态变更，与设计 §7.3 规则 10「不写正式数据/checkpoint/激活指针」相冲突。
 * 需要"谁预览过什么"的留痕时，应作为独立需求单独评审（含审计表的容量与保留策略）。</p>
 */
@RestController
@RequestMapping("/api/v1/sources/{sourceId}/mappings")
@RequiresPermission(PermissionCode.RUNTIME_MANAGE)
public class MappingDryRunController {

    private final MappingDryRunService dryRunService;

    public MappingDryRunController(MappingDryRunService dryRunService) {
        this.dryRunService = dryRunService;
    }

    /**
     * dry-run 入参。
     *
     * @param profileText 候选画像**原文**（JSON 文本；校验其字节的 sha256，见类注释）
     * @param sampleRef   受控样本引用：仓库相对路径，只能落在 {@code platform.mapping.sample-root} 下；
     *                    不接受绝对路径、{@code ..}、协议前缀（{@code file:}/{@code http:}…）、
     *                    百分号转义与控制字符，也不接受符号链接
     * @param limit       本轮最多处理多少行（1..100；设计 §7.3 规则 10 的「最多 100 条受控样本」）
     */
    public record MappingDryRunReq(
            @NotBlank String profileText,
            @NotBlank String sampleRef,
            @NotNull @Min(1) @Max(100) Integer limit) {
    }

    @PostMapping("/dry-run")
    public ApiResponse<MappingDryRunReport> dryRun(@PathVariable String sourceId,
                                                   @Valid @RequestBody MappingDryRunReq req) {
        MappingDryRunReport report = dryRunService.run(sourceId, req.profileText(), req.sampleRef(), req.limit());
        return ApiResponse.ok(report, TraceContext.create().traceId());
    }

    @GetMapping("/dry-runs/{reportId}")
    public ApiResponse<MappingDryRunReport> report(@PathVariable String sourceId,
                                                   @PathVariable String reportId) {
        return ApiResponse.ok(dryRunService.report(sourceId, reportId), TraceContext.create().traceId());
    }
}
