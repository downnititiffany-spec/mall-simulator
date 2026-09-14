package com.graduation.generator.service;

import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.meta.GeneratorMetaStore.TargetRow;
import com.graduation.generator.adapter.CapabilityVerdict;
import com.graduation.generator.adapter.MallCapability;
import com.graduation.generator.adapter.MallTargetAdapter;
import com.graduation.generator.adapter.MallTargetAdapterRegistry;
import com.graduation.generator.adapter.TargetCheckResult;
import com.graduation.generator.adapter.TargetConfig;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetCheckView;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §4.4 L158 的 {@code POST /targets/{id}/test}：目标商城检查。
 *
 * <p><b>本服务不再自己探测</b>（S4a）。它只做三件事：按 id 取目标行、按 {@code adapter_type} 从
 * {@link MallTargetAdapterRegistry} 找适配器、把适配器的实测结论翻译成响应体。探测逻辑的所有者
 * 是适配器——文件目标在 {@code FileModeTargetAdapter}，参考商城在 {@code ReferenceMallHttpAdapter}；
 * 换一台商城＝新增一个适配器并注册，不改本类。</p>
 *
 * <p><b>删掉的兜底</b>：旧实现对"非文件类型"一律做一次 TCP 端口连接，并把结果当作检查结论。
 * 那条路径对"这台商城能提供什么"什么也没证明（它自己的说明文字就写着"本次未验证"），
 * 却会让 {@code adapter_type} 写错的目标看起来是"可达"的。现在未注册的 {@code adapter_type}
 * 直接 400 并列出已注册类型（{@code ApiExceptionHandler.INVALID_ARGUMENT}）。</p>
 */
@Service
public class TargetProbeService {

    private final GeneratorMetaStore store;
    private final MallTargetAdapterRegistry adapters;

    public TargetProbeService(GeneratorMetaStore store, MallTargetAdapterRegistry adapters) {
        this.store = store;
        this.adapters = adapters;
    }

    public TargetCheckView probe(long targetId) {
        TargetRow target = store.findTarget(targetId)
                .orElseThrow(() -> new GenerationRunService.RunNotFoundException("目标不存在：id=" + targetId));
        MallTargetAdapter adapter = adapters.require(target.adapterType());
        TargetCheckResult check = adapter.test(new TargetConfig(target.id(), target.adapterType(),
                target.baseUrl(), target.credentialRef(), target.configJson()));
        return new TargetCheckView(check.targetId(), check.reachable(), check.detail(), jsonCapabilities(check));
    }

    /** 能力判定转成响应体的键值（键＝{@code MallCapability.key()}，值＝三态名） */
    private static Map<String, String> jsonCapabilities(TargetCheckResult check) {
        if (check.capabilities().isEmpty()) {
            return Map.of();
        }
        Map<String, String> json = new LinkedHashMap<>();
        for (MallCapability capability : MallCapability.values()) {
            CapabilityVerdict verdict = check.capabilities().get(capability);
            if (verdict != null) {
                json.put(capability.key(), verdict.name());
            }
        }
        return json;
    }
}
