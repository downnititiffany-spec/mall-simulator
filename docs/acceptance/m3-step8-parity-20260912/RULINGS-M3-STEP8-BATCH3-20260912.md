# M3 §6.4 步骤 6+8 第三批裁决（D-141）

- 日期：2026-09-12（第三批，接 D-137 / D-138 / D-140）
- 裁决人：总控（父 agent）
- 适用范围：`spark-jobs` 代理键与 DWD 入表缺陷、`pipeline_run` 44–47、P2-03 状态、质量门口径冲突、集群侧口径更正
- 口径：本文件只登记**已实测**事实；未实测项一律标「未测／待证」，人与父侧的复述不作为独立证据

## §1 run 47 = 本地 golden-55 链路首次全绿（实测）

| 项 | 值 | 来源 |
|---|---|---|
| 输入 | batch 43（accepted 51 / quarantine 4）＝ 黄金 55 行夹具（18,430 B / sha256 `2351BCC35E04CCD278638F07247BC37E9C4D402CC4736CD2A4D4B70F4232B11C`） | 父侧与泳道双方实测 |
| 阶段 | **8/8 SUCCESS**：`WAIT_LANDING=51`、`BUILD_DWD=160`、`BUILD_ADS=22`、`PUBLISH_METRIC=44` 等 | `raw/post/run47-final.json`（父侧未逐项复测，标「据证据文件」） |
| 快照 | `S20260901_47` **ACTIVE** / version 12；`_44/_45/_46` 均 **NOT_EXISTS** | `analytics_metric.metric_snapshot` 实测 |
| 指标 | 10 个可读（GMV=2042、净销售额=1493、支付订单数=5 等） | 同上（父侧未逐项复测） |
| 运行期 jar | 296,340 B / sha256 `F9882E6A…9432A0B`（class major 52） | 泳道落包记录 |
| 业务日切片口径 | accepted 51 = **49×2026-09-01 ＋ 2×2026-09-02**；`LOAD_ODS records=51` 是**入参回显**，切片实为 **49** | 泳道实测，订正父侧「≈51／53+2」预判 |

隔离 4 行身份（泳道实测）：`golden-evt-038` 非法枚举 `purchase`、一行非 JSON、`golden-evt-054` schema_version=2.0、`golden-evt-055` 缺 `event_id`。

## §2 真因：动态分区列位置错误（并撤回两个错误解释）

- **真因（成立）**：`TradeDwdJob.orderDetailInsertSql` 的 INSERT **不写列名**，靠与 DDL 的位置对齐；`INSERT OVERWRITE TABLE … PARTITION (dt)` 未给分区值（动态分区）时，Spark 仍要求分区列**由 SELECT 提供且落在最末位**。`ccc1dff`(P2-03) 新增 `user_key/product_key/category_key` 三个 `BIGINT` 代理键时，把它们追加在 **`t.dt` 之后** ⇒ 整体右移一位 ⇒ **STRING** 的 `t.dt` 落入第 20 个普通列 `user_key`(BIGINT) ⇒ `CANNOT_SAFELY_CAST`。
- **正确顺序**：19 个数据字段 → `user_key` → `product_key` → `category_key` → `t.dt`（分区列最末位）。
- **假说一（`SurrogateKey.nullSafe` 裸 `NULL` ⇒ STRING）＝ 已证伪**：最小修复（`THEN CAST(NULL AS BIGINT)`，E1 ✓ / E2 111/111 JDK8 ✓ / 新 jar 296,334 B）落包后 run 46 仍以**逐字相同**错误 FAILED ⇒ 该假设**必要但不充分**，**不得**作为根因；`CAST(NULL AS BIGINT)` 保留，理由仅限「类型确定性」。
- **撤回一（父侧授权改正）**：「错位在 P2-03 之前就已存在／run 43 静默写错值」**不成立**。旧表是「19 个普通列 ＋ 动态分区列 `dt`」、旧 SELECT 是「19 个普通值 ＋ `t.dt`」，`t.dt` 正好**第 20 项**、**位置正确**。「第 19 项」一律改为「前面已有 19 个普通字段，`t.dt` 是第 20 项」。
- **撤回二**：不得用 run 43 与 run 47 的**行数／分区数差异**论证旧版数据损坏（两者输入批次不同）。
- 涉及改正的三个位置：`spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala:131` 起（父侧已改）、`raw/post/p2-03-m2-rootcause-evidence.txt:61`、`IMPL-REPORT.md:140`（泳道改正 ＋ 回读）。

## §3 P2-03 状态：`REGRESSED` → **`REVIEW`**（**不得**标 `DONE`／`DONE_LIMITED`）

修复有效（run 47 全绿）但整体验收未成立，缺口三条：

1. **`P2-03-k`**：空代理键的 `data_quality_result` 记录**尚未实现**（ADR 选项①，平台侧）。
2. **`P2-03-l`**：`surrogate-key.v1.json` **V01 向量事实错误**（把 `event_id` 当 `entity=user` 的 `rawInput`）**尚未修正**。
3. **集群 E4 同输入对照未完成**（见 §5）。

## §4 新开条目 **F-88**：质量门严重度口径「文档—代码」不一致（**待裁决，不在 P2-03 内顺手改**）

- 人裁定引指导书 **§7.3**：`BLOCKING` 与 `ERROR` **均阻断发布**；`WARN`/`INFO` 记录不阻断。
- 实现实测：`analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/DataQualityGate.java:19` 起**只有 `BLOCKING` 阻断**，`ERROR` 失败仍可发布；且**自动化测试固定了该行为**。
- 现场证据：run 47 的 `EVENT_ID_UNIQUE = ERROR`、**1/49 失败**，但**未阻断**发布（`PUBLISH_METRIC` 成功、快照 ACTIVE）。
- 裁决方向（**待出**，本文件不预设）：① 按 §7.3 改实现 ＋ 改测试（`ERROR` 阻断）；或 ② 把观察性规则（如重复事件）**降级**为 `WARN` 并把 §7.3 写清。**两者择一后**，实现、测试、文档同步；在此之前**不得**声称「质量门全部通过」。

## §5 更正父侧上一轮的过宽判断：集群侧不是「未执行」

- **已成立**：集群 **1,000 行 / 10 个作业**已真实成功（`docs/acceptance/e4-cluster-1000-20260912/README.md`；产物 1,000 行 / 353,053 B / sha256 `A586080D…`）。
- **尚缺**（本轮与下步要补）：① 指定黄金数据的**本地 ↔ 集群同输入对照**（§5.5 的 10 指标 ＋ 8 张 ADS 表）；② **导出物真实进入 MySQL 并读取正确**的闭环；③ 集群侧 A8 T2 四列非 NULL 计数。
- **不得声称**：「同一 jar ⇒ 集群必然同样失败」是**推断**；且假说一被证伪后该推断更不足为据 —— 集群侧失败与否**以实测为准**。

## §6 台账统计口径

`132/90/42`（DONE/DONE_LIMITED/其它）是**历史冻结基线**；在未逐条复核 269 项任务前，**不得**当作当前准确统计引用。本轮起新增／改判条目从 §2 的 L0 起逐行登记。

## §7 本批未做／未测（显式）

- 集群 10 作业（本轮 jar）**未提交**；§5.5 对照表 **未执行、无值**；HDFS 导出回读 **未做**；导出 → MySQL 闭环 **未做**；集群 A8 T2 **未做**。
- 本地：run 48 **不跑**（人裁定）；`parent_category_key` 全 NULL（本地两轮独立复现 **4/4/4/0**）**未修**，登记为待裁定（fixture 无该字段；`DimSql.scala:67` 对 id 列有 `-1` 兜底而代理键列无）。
- 商城 8090 / 生成器 8092 **未启动**（不影响 P2-03 验证口径）。
- 四文件修复 ＋ 本裁决的提交状态：见提交记录（父侧逐路径显式 `git add`，**禁止** `git add -A`）。
