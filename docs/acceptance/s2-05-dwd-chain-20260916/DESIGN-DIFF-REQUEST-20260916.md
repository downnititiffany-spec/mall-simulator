# 设计差异请求（S2-05 轮，2026-09-16）

> 依据：指导书 §12 L266-275（规则 4，L271）「Code Agent 只提交设计差异请求，不自行修改两正式文档」。
> 本文**不是裁决**，是提请总控裁定的差异清单；两份 V3.0 正式文档本轮**一字未改**。
> 日期口径：本机原始日志时间戳为 `2026-09-15T19:5x–20:3x`（沿用 F-29 起的备注，以原始日志为准）。

## 请求 1（设计 §10.1 L365）：`bdw` 的 DAG 前置与已落地 JOIN 事实冲突

**设计原文（§10.1，JobRegistry 依赖表附近，L365）**：`bdw` 前置 = `odl`（**不含 `dim`**）。

**实测事实**：`DwdSql.behaviorClean` 的 SELECT 列表含 `u.city_level`、`p.category_id`、
`p.category_key`，且 SQL 尾部有
`LEFT JOIN dim_user u ON u.user_id = … AND u.dt = '<业务日>'` 与
`LEFT JOIN dim_product p ON p.product_id = … AND p.dt = '<业务日>'`（P2-03 落地）。
故「`dim` 未先建/未先跑」时这两张快照为空，JOIN 全部未命中。

**实测后果（两个独立取证面）**：
1. **编排面**：`SparkStageExecutor.STAGE_JOBS` 的 `BUILD_DWD` 原为 `bdw → tdw, dim`，
   实测 `stage BUILD_DWD fail-fast: job bdw 失败…剩余作业 [tdw]`（`raw/r2-GREEN-warehouse-pipeline.log`）；
   `JobRegistry.topologicalOrder()` 实测 `List(ljp, odl, sci, **bdw, dim**, tdw, …)` ⇒ `bdw`(3) 先于 `dim`(4)。
2. **数据面**（独立 namespace 的 A/B 真跑）：A＝`bdw→dim` 时行为明细 `city_level` NULL×14、
   `category_id` −1×14、`category_key` NULL×14；B＝`dim→bdw` 全部命中。**生产顺序下的 A 场景
   会让 14/14 条行为明细行全部丢失维度补全，且行数、代理键、拒绝数、订单指标一律不变**
   —— 即**静默降级**，不是报错。冷启动（表不存在）会硬失败 `TABLE_OR_VIEW_NOT_FOUND`，
   但在产路径由 `INIT_SCHEMA` 先建空表，故静默降级才是真实行为。

**本轮已做的实现侧处理（未改正式文档）**：按设计 §10.1 **L375**「扩展 DAG 时必须用 Kahn 拓扑
排序或 DFS 检测真实环，不能沿用假定」的授权口径，把 `bdw` 前置改为 `List("odl","dim")`，
并把 `hasCycle = false` 换成真实现的 `topologicalOrder`（Kahn）。RED→GREEN 取证见
`.verify/v3-stage2/s2-05/s205-dwd-dedup-key/raw/r5–r8`。

**请求**：① 请裁定设计 §10.1 L365 的表述是否更正为 `bdw` 前置＝`{odl, dim}`（本轮实现已按此
事实执行）；② 若保持 L365 原文不改，请裁定"设计表以 DAG 前置声明为准、JOIN 实为跨作业隐式依赖"
是否是可接受口径 —— 若是，则需要一条「隐式依赖必须显式登记」的规则，否则同一形态会复发。

## 请求 2（裁决 D-122 L34）：契约别名映射的落点

**裁决原文**：只加一行「契约逻辑名 `source_instance_id` ⇔ 物理列 `source_system`」的
**别名映射说明**（不改语义、不改列名）。

**本轮已做的实现侧处理**：该行已写进 `spark-jobs/.../sql/DwdSql.scala` 的对象 KDoc
（并同时写明「只扩键、不加列、不动 DDL、不得用 `raw_source_system` 充当键」）。

**请求**：若总控希望该别名映射同时出现在**契约文本**（`contract-specs/**`）里，请裁定后由
总控侧修改 —— 改已发布契约语义属门 ⑥，Code Agent **不擅改**（本轮 `contract-specs/**` 零改动）。

## 附带事实登记（非请求，仅供裁定参考）

- `DwdSql.duplicateReject` 的 population **不含** `behaviorClean` 所用的
  `schema_version='1.0'` / 枚举白名单 / 非空过滤 ⇒ 拒绝记录会把 DWD 从未考虑过的行也算成重复
  （本轮**未改**，属"拒绝口径 vs 明细口径漂移"，已入 backlog）。
- 跨源去重正确性的本轮上限是 **`DONE_LIMITED`**（D-126）：真库是单源，跨源性质只由
  **构造夹具 + 本地链**证明；**不得**据此声称单源真库的跨源去重正确。
- 本轮的 DDL 边界：`dwd_user_behavior_detail` **不加** `source_system` 列（裁决 L81
  「本裁决不授权任何 DDL」＋D-122「不新增列」），该边界已被两向断言钉住。
