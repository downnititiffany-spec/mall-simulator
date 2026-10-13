# 阶段 4/5 `from`/`to` 日期过滤契约校正（2026-10-08）

## 发现与处理

当前工作树 `AnalysisService` / `MetricAdsReader` 已把 `from`/`to` 作用于固定快照的逐日 ADS：概览过滤销售/活跃趋势；销售页过滤趋势、分类及地区行。标量指标和质量仍按 pinned snapshot。单端点可用、双端为含端点区间，倒置区间在 ADS 读取前拒绝。接口契约 v1.11 仍将 overview/sales 的日期参数称为只回显；与当前实现和 V3.0 设计 §18.5 不符。

按 `analysis-viewmodel-r7-4.md` 的契约先行、版本化纪律，新增 v1.12 说明并明确历史段落仅代表早期状态。本轮没有改业务代码、字段或端点，也未改冻结 V3.0 指导书/设计文档。`/analysis/products` 的日期语义未在本说明中扩展。

## 验证

JDK 17 离线 Maven 定向回归：`AnalysisServiceTest` 42/42、`MetricAdsDaoTest` 11/11，合计 **53/53 PASS**，0 failure/error/skipped；`platform-common` + `metric-analysis` reactor `BUILD SUCCESS`。测试覆盖单日、双日含端点、单端日期、空结果、缺省整快照、倒置拒绝以及过滤绑定的 SQL 边界。

验证仅覆盖服务/DAO 测试，不含真实 Spring HTTP、MySQL 查询、浏览器或部署验证；未访问 3306。日期过滤是已发布 ADS 的 `dt` 行筛选，不表示重算指标或解决退款归属期。
