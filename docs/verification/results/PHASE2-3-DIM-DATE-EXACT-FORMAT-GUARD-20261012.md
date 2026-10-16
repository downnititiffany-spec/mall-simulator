# 阶段2/3 `dim_date` 日期输入精确格式与日历有效性守卫（2026-10-12）

## 实现

- 四类 ODS 的 `dt` 必须符合固定宽度 `yyyyMMdd`，且解析为真实日历日期。
- 既有 `dim_date.date_key` 必须符合固定宽度 `yyyy-MM-dd`，且解析为真实日期。
- 边界和格式/日历检查在 `dim_user`、`dim_product`、`dim_date` 任一写入前完成。

## 负例

同一 Spark suite 用例包含短格式 `2021011` 和格式正确但无效的非闰年日期 `20210229`。校验失败后，预置的用户、商品快照和日期维表均保持不变。既有跨年连续区间、ISO-8601 星期、周末和重跑幂等断言仍保留。

## 最终验证

- 统一入口：`pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark`
- RunId：`dev003c_20261012_114026_1a64af`
- JDK：1.8.0_202
- ScalaTest：333/333 succeeded，43 suites completed；0 failed、aborted、canceled、ignored、pending；基线 MATCH；Maven BUILD SUCCESS；TestSuite.txt 为本轮新写。

## 边界

证据限 Spark local[1]/in-memory catalog，不代表生产 Hive/HDFS、共享 HMS、平台 API/DB 或完整链路验收；未连接 MySQL 3306。阶段2/3及项目总验收仍开放。
