package com.graduation.analytics.ai.evidence;

import com.graduation.analytics.ai.ExplanationService;
import com.graduation.analytics.ai.ExplanationService.ExplanationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 证据包服务（§19.2/§19.3、§24.7）：对外只暴露三件事——建包、取当前包、按包出解释。
 *
 * <p>分工：{@link EvidenceBuilder} 负责取数装载，{@link ExplanationService} 负责叙述，
 * 本类不做任何取数或口径判断（避免出现第三处口径）。</p>
 */
@Service
@RequiredArgsConstructor
public class EvidenceService {

    private final EvidenceBuilder builder;
    private final ExplanationService explanationService;

    /** 建包（只读已发布快照/ADS，不调用模型） */
    public EvidencePackage build(EvidenceRequest request) {
        return builder.build(request);
    }

    /** 当前经营状况的证据包（ACTIVE 快照） */
    public EvidencePackage latest(String requestedBy) {
        return builder.build(EvidenceRequest.latest(requestedBy));
    }

    /** 模板优先的解释（模型只改写措辞；模型不可用也有完整结论） */
    public ExplanationResult explain(EvidencePackage pkg, String question) {
        return explanationService.explain(pkg, question);
    }

    /** 固定模板的六段原文（前端可直接展示，不必自己拼段落） */
    public Map<String, List<String>> sections(EvidencePackage pkg) {
        return explanationService.sections(pkg);
    }
}
