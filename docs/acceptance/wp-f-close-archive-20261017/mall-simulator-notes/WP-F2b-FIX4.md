# WP-F2b 修复轮 4（总控裁定，2026-10-16 22:4x）

## 事实（总控亲跑 RunId fix3acc20261016）
收编腿的 schema 步被原机制挡下：`AnalyticsIsolationFlywayIT.migratesMetaAndMetricSchemasIndependentlyAndIdempotently` 断言隔离 meta 全量 migrate 后当前版本 `isEqualTo("34")`，实际为 "38"。V34 是该断言写下时的迁移头（V34__quality_rule_category_region_sales）；此后仓库已续加 V35（runtime_profile_single_active）、V36（ai_call_request_identity）、V37（ai_feedback_and_history_evidence）、V38（business_report_instances），生产平台在本 VM 的 meta 亦为 V38。断言属版本钉过期；V34 内容的实证在同方法下方的 quality_rule_definition 查询中独立存在，不受影响。metric 钉 "13" 仍为当前头，不动。

## 交付（仅改一个文件）
`analytics-server/platform-app/src/test/java/com/graduation/analytics/isolation/AnalyticsIsolationFlywayIT.java`，仅 `migratesMetaAndMetricSchemasIndependentlyAndIdempotently` 方法内 meta 版本断言一处：
- `.as("隔离 meta schema 必须真实应用分类/城市等级规则迁移 V34")` 改为 `.as("隔离 meta schema 必须全量迁移至当前迁移头 V38（V35 运行时档案单活 / V36 AI 调用请求标识 / V37 AI 反馈与历史证据 / V38 业务报告实例）")`
- `.isEqualTo("34")` 改为 `.isEqualTo("38")`
- 其他任何断言、方法、文件一律不动（含 metric 的 "13" 钉与下方规则查询）。

## 约束
- 你只有文件读写权限，不运行任何命令；验收由总控以全新 RunId 亲跑收编腿。
- 完成标记：回复末尾一行 WPF2BFIX4-DONE
