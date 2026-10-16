# PHASE 6 Query Explanation Numeric Format Normalization — 2026-10-06

## 变更

- 数字证据守卫现在用 `BigDecimal.stripTrailingZeros()` 比较数值语义；证据中的 `2042.0000` 可匹配模型输出 `2042`，负号仍保留并参与匹配，`-2042` 不会被正数 `2042.0000` 放行。
- 数值格式等价项加入 `ExplanationEvidenceTest`。修改前用例 RED：`2042` 与 `2042.0000` 被错误判为两个不同数字。

## 验证

- `ExplanationEvidenceTest` + `ExplanationProviderProvenanceTest`：15/15 PASS。
- 完整 `ai-decision` 上游 reactor：platform-common 4、connection-ingestion 389（2 skipped）、metric-analysis 141、ai-decision 175；合计 709 项，0 failure、0 error、2 skipped，BUILD SUCCESS。JDK 17 / Maven offline。

## 边界

本项仅规范化阿拉伯数字的十进制表示并保留正负号语义；外部真实 LLM/数据库未调用。阶段 6 仍受 D-039 阻塞。
