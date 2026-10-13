# 阶段2/3 `dim_date` 输入校验先于维度写入（2026-10-12）

## 发现与修复

`DimensionBuildJob` 原先在写入 `dim_user`、`dim_product` 后才检查四类 ODS 的日期值。无效非空 `dt` 会使阶段失败，但前两张维表的业务日分区已被覆盖。

现把 `dim_date` 日期边界读取和格式校验移至任何维表快照写入之前。校验失败时，用户/商品快照及已有日期维表均保持不变。

## 验证

- 统一入口：`pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark`
- RunId：`dev003c_20261012_112947_3fa0b0`
- JDK：1.8.0_202
- ScalaTest：333/333 succeeded，43 suites completed，0 failed/aborted/ignored/pending；基线 MATCH；Maven BUILD SUCCESS。
- 新增负例并入原有 DateDimensionRangeSpec 用例，保持登记的测试总量不变；同时保留 D-061 连续范围、ISO-8601 和重跑幂等断言。

## 证据边界

仅证明 Spark local[1] 与 in-memory catalog 测试。未验证生产 Hive/HDFS、共享 HMS、管理员/API、数据库或整链验收；未连接 3306。阶段2/3及项目总验收仍开放。
