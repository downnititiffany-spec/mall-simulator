package com.graduation.generator.web;

import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.meta.GeneratorMetaStore.TargetRow;
import com.graduation.generator.service.GenerationRunService.RunNotFoundException;
import com.graduation.generator.service.TargetProbeService;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetCheckView;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetRequest;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * §4.4 L158 的目标商城配置端点。
 *
 * <p><b>PUT 的路径照抄契约</b>：契约把更新写成集合路径 {@code PUT /api/v1/targets}，并注明
 * "是否应改为 {@code PUT /api/v1/targets/{id}} 未冻结"。因此这里按契约原样实现集合路径，
 * 用请求体里的 {@code id} 定位被更新的行；**不擅自新增** {@code /targets/{id}} 的 PUT，
 * 以免在契约冻结前造出第二套语义。</p>
 *
 * <p>凭据只存引用：{@code credential_ref} 原样入库（环境变量名/密钥别名），本层不做任何解析或回显明文。</p>
 */
@RestController
@RequestMapping(path = "/api/v1/targets", produces = MediaType.APPLICATION_JSON_VALUE)
public class TargetController {

    private final GeneratorMetaStore store;
    private final TargetProbeService probeService;

    public TargetController(GeneratorMetaStore store, TargetProbeService probeService) {
        this.store = store;
        this.probeService = probeService;
    }

    @GetMapping
    public List<TargetView> list() {
        return store.listTargets().stream().map(TargetController::toView).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public TargetView create(@RequestBody TargetRequest request) {
        TargetRow row = toRow(request, false);
        long id = store.insertTarget(row);
        return toView(store.findTarget(id).orElseThrow(() -> new RunNotFoundException("目标创建后不可读：id=" + id)));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public TargetView update(@RequestBody TargetRequest request) {
        if (request.id() == null) {
            throw new IllegalArgumentException("PUT /api/v1/targets 必须在请求体带 id（契约路径为集合路径，未冻结为 /{id}）");
        }
        TargetRow existing = store.findTarget(request.id())
                .orElseThrow(() -> new RunNotFoundException("目标不存在：id=" + request.id()));
        TargetRow merged = toRow(request, true);
        // 可选字段未提供时沿用现值：集合路径 PUT 的整对象语义会把漏填字段清空，风险不该由调用方承担
        merged = new TargetRow(existing.id(), merged.name(), merged.adapterType(),
                pick(merged.baseUrl(), existing.baseUrl()),
                pick(merged.credentialRef(), existing.credentialRef()),
                pick(merged.configJson(), existing.configJson()),
                existing.configVersion(),
                pick(merged.status(), existing.status()),
                request.testEnvironment() == null ? existing.testEnvironment() : request.testEnvironment(),
                pick(merged.capabilities(), existing.capabilities()));
        if (!store.updateTarget(merged)) {
            throw new RunNotFoundException("目标不存在：id=" + request.id());
        }
        return toView(store.findTarget(request.id()).orElseThrow(
                () -> new RunNotFoundException("目标更新后不可读：id=" + request.id())));
    }

    /** §4.1 {@code test(TargetConfig)} 对应的连通性检查 */
    @PostMapping("/{id}/test")
    public TargetCheckView test(@PathVariable("id") long id) {
        return probeService.probe(id);
    }

    private static TargetRow toRow(TargetRequest request, boolean forUpdate) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name 必填（契约 GeneratorTarget required）");
        }
        if (request.adapterType() == null || request.adapterType().isBlank()) {
            throw new IllegalArgumentException("adapter_type 必填（契约 GeneratorTarget required）");
        }
        if (looksLikePlaintextSecret(request.credentialRef())) {
            throw new IllegalArgumentException("credential_ref 只允许引用（环境变量名/密钥别名），不接受疑似明文凭据");
        }
        return new TargetRow(null, request.name(), request.adapterType(), request.baseUrl(),
                request.credentialRef(), request.configJson(),
                forUpdate ? 0 : 1,
                request.status() == null && !forUpdate ? "ACTIVE" : request.status(),
                request.testEnvironment() == null ? Boolean.TRUE : request.testEnvironment(),
                request.capabilities());
    }

    private static <T> T pick(T provided, T fallback) {
        return provided == null ? fallback : provided;
    }

    /**
     * 明文凭据哨兵：明显是密码/密钥的样子就拒绝。
     *
     * <p>这是启发式检查，不是安全边界——真正的边界是"这一列在契约里就叫引用"。写在这里是为了让
     * "不小心把口令填进 credential_ref"在入库前就失败，而不是等审计时才发现。</p>
     */
    private static boolean looksLikePlaintextSecret(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return false;
        }
        String value = credentialRef.trim();
        return value.startsWith("Bearer ") || value.startsWith("sk-") || value.length() > 80
                || value.matches(".*[:=].*");   // "user=root password=..." 这类整串配置
    }

    private static TargetView toView(TargetRow row) {
        return new TargetView(row.id(), row.name(), row.adapterType(), row.baseUrl(), row.credentialRef(),
                row.configJson(), row.configVersion(), row.status(), row.testEnvironment(), row.capabilities());
    }
}
