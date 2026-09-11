package com.graduation.analytics.controller;

import com.graduation.analytics.ai.evidence.EvidencePackage;

import java.util.List;
import java.util.Map;

/**
 * 证据包测试夹具（真实 record，字段与 R8-1 契约 §1 一致）。
 *
 * <p>只服务控制层的「搬运 / 形状适配」断言：证据包本身由 ai-decision 的构建器负责，
 * 这里手工构造一个字段完整的包，让模板渲染（六段叙述）走真实代码。</p>
 */
final class EvidenceTestFixtures {

    /** 契约示例快照号 */
    static final String SNAPSHOT = "S20260901_24";
    /** 契约示例证据包 id（EV-yyyyMMdd-xxxxxx） */
    static final String EVIDENCE_ID = "EV-20260901-abc123";

    private EvidenceTestFixtures() {
    }

    static EvidencePackage packageOf(String snapshotId) {
        return new EvidencePackage(EVIDENCE_ID, EvidencePackage.TEMPLATE_VERSION, snapshotId,
                "r7-metric-v1", "2026-09-01T10:00:00",
                new EvidencePackage.Period("2026-09-01", "2026-09-01"),
                new EvidencePackage.Period("2026-09-01", "2026-09-01"), null,
                List.of(new EvidencePackage.Fact("gmv", "成交额", "1234.5", "元",
                                "day:2026-09-01", "metric_value.gmv@" + snapshotId),
                        new EvidencePackage.Fact("refund_rate", "退款率", "0.032", "",
                                "day:2026-09-01", "metric_value.refund_rate@" + snapshotId)),
                List.of(), Map.of(), List.of(),
                new EvidencePackage.DataQuality(EvidencePackage.DataQuality.GATE_PASS, 8, 6,
                        List.of("ADS_STAGING_PRESENT"), List.of()),
                new EvidencePackage.Lineage(List.of("ads_trade_overview"), List.of("ads_trade_overview"),
                        7L, snapshotId),
                List.of(EvidencePackage.WARN_NO_COMPARISON_PERIOD));
    }
}
