# DESIGN-DIFF-REGISTER —— S3-17 分析信封补 `source`（阶段4 L156 最后一项未满足字段）

- 日期：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`，**不 merge main**）
- 代码提交：`e224508`（feat(analysis): 阶段4 分析信封补 source…，7 文件）；文档提交：本文件所在 `docs(status): F-50 …` 提交（自指哈希不写入本文件）
- 任务来源：指导书 V3.0 §7 阶段4 **L156**（`source` 为 4 个元数据字段中**唯一未实现**者）
- 判定：**A 类（IMPLEMENTATION / ADDITIVE）** —— 登记 → 自主设计 → 实现 → 测试 → commit → 继续

## 0. 一句话结论

指导书 §7 阶段4 L156 要求分析服务「返回明确 **source**、snapshot、definitionVersion、时间和质量信息」；
本轮开工前实测：信封 8 个字段里 snapshot/definitionVersion/时间/质量**齐备**，**`source` 缺失**
（`AnalysisViewModel` 记录无 `source` 分量，`metric-analysis` 全模块 `getSource()` **零命中**）。
本轮**加性**补 `source` = `metric_snapshot.source`（发布方，§17.6 只接受 `spark-ads`），
契约 v1.2 先行登记，实现与测试同步；**未**做业务源身份（那是另一条链，见 §6/§8）。

## 1. 设计原文（逐字，S3-17 标尺）

| 出处 | 原文 |
|---|---|
| 指导书 §7 阶段4 **L156** | 「MetricStore/专题服务返回明确 source、snapshot、definitionVersion、时间和质量信息。」 |
| 指导书 §7 阶段4 **L157** | 「查询固定快照，不跨请求拼接不同 ACTIVE；归档读取授权、未知快照不静默回最新。」（**归档读取授权未满足**，见 §8） |
| 指导书 §7 阶段4 **L158** | 「异步任务返回标识，提供阶段/失败/重试反馈；分页、限流、超时统一。」（**未评估完**，见 §8） |
| 设计 §11.1 **L414** | 「MetricDefinition：metricCode、名称、单位、公式、数据粒度、时间字段、过滤条件、分子/分母、zeroPolicy、direction、definitionVersion、lineage。**MetricValue：source作用域**、snapshotId、businessDate/窗口、dimensionKey、metricCode、decimalValue、definitionVersion、qualityStatus、warnings。」 |
| 设计 §11.1 **L542**（能力边界） | 「当前存在MySqlMetricStore；其现有接口为type()/query(MetricQuery)/publish(SnapshotRef,List<MetricValue>)/healthCheck()，MetricQuery只有snapshotId/latestActive，**扩展源/维度筛选时须核对专题服务而非假定接口已支持**。」 |
| 设计 §17.6（经 V1 DDL 注释引用） | `metric_snapshot.source`：`VARCHAR(32) NOT NULL DEFAULT 'spark-ads' COMMENT '§17.6 成功快照只接受 spark-ads'`（`db/metric/V1__metric_store.sql:20`） |
| 契约 `docs/contracts/analysis-viewmodel-r7-4.md` **L4** | 「**本文件是 R7-4 后端与前端唯一契约**，两侧实现必须逐字段对齐；若实现中确需改动，**先改本文件再改代码**。」 |
| P2-04 裁决 `RULINGS-P2-04-20260912.md:40` | 「①`metric_snapshot` 实测**无**该列，其 `source` 列实测值为发布方 `spark-ads`，**不是**源身份 ⇒ **不得把 `source` 当源身份使用**；②源身份已由 **per-source warehouse namespace**（冻结决策 ④）承载」 |
| E5 预验收登记 `e5-preaccept-20260912/README.md:37` | 「**E5-c** ｜ D-c ｜ 页面不显示结果所属**数据源**（DB/API 有 `source=spark-ads`，三页 0 命中）⇒ 第 3 项缺口」 |

## 2. 开工前冻结事实（实测，非推测）

1. **信封 8 字段**：`AnalysisViewModel` 记录 = `snapshotId/businessTime/dataUpdatedAt/definitionVersion/qualityStatus/filters/data/warnings`，
   构造点仅 `of(...)`（1 处生产 + 5 处测试）与 `empty(...)`。
2. **`source` 在读取侧零消费**：`git grep -n "getSource()" HEAD -- analytics-server/metric-analysis/` **无命中**（exit=1）
   ⇒ `MySqlMetricStore.SNAPSHOT_MAPPER:75` 把 `source` 读进实体，但**没有任何读取方**；分析信封因此无 source。
3. **DB/API 侧本来就有**：`/api/v1/metrics/snapshots` 直接返回 `List<MetricSnapshot>` 实体（`MetricController:52-55`）⇒ 含 `source`；
   与 E5-c 记录的「DB/API 有、页面无」一致。**缺口只在分析信封（专题服务）这一层**。
4. **`source` 的唯一写入方**：`MetricPublishRepository:55` 的 `UPDATE … source = 'spark-ads' …`（发布侧字面量）
   ⇒ 读侧**原样回显**即可，不得自行推导/覆盖。
5. **契约程序**：契约 v1（8 字段）→ v1.1（S3-16 加性补 RFM 原值/窗口，**先改文档再改代码**已走通）
   ⇒ 本轮沿用同一程序出 v1.2，**既有字段语义一律不变**。
6. **前端兼容性（加性成立的前提）**：`web/src/utils/envelope.js:22-30` 的 `readEnvelope` **逐键取值**，
   未知键被忽略 ⇒ 新增 `source` 不会破坏既有页面；同时也意味**页面不会显示它**（阶段5 工作，见 §8）。
7. **测试面**：`AnalysisServiceTest` 有 1 条标题断言「信封 **8** 字段齐备」的用例（本轮同步改为 9），
   `AnalysisViewModelTest` 断言 `empty()` 的 null 语义 —— 两处都是**判定点**而非文案。

## 3. 逐门核对（11 条 HARD DECISION GATE）

| 门 | 判定 | 依据 |
|---|---|---|
| ① DROP | 不触 | 零 DDL、零迁移 |
| ② 改字段类型/既有业务语义 | 不触 | 加性新增分量；既有 8 字段语义一格不改（§1 契约 L4 程序） |
| ③ 改已发布 migration | 不触 | `db/metric/V1-V10`、`db/meta/V1-V25` 字节未动 |
| ④ 写/迁移正式 3306 | 不触 | **零连库**（L0 证据） |
| ⑤ 切 ACTIVE | 不触 | 不涉发布/状态机 |
| ⑥ 改 `contract-specs/**` 契约语义 | 不触 | `contract-specs/**` 零改动；分析 ViewModel 契约**不在**该目录（其 `README.md:136` 明文「本目录不复制」） |
| ⑦ 改 V3.0 总体架构 | 不触 | 只增一个信封字段 |
| ⑧ 改正式项目范围 | 不触 | 落实 L156 既有要求，不扩范围 |
| ⑨ 删除已发布功能 | 不触 | 纯加性 |
| ⑩ 引入未规划大型组件 | 不触 | 零新依赖 |
| ⑪ 造成重大长期架构分叉 | 不触 | 唯一方案；且**明确不**把 `source` 当源身份（P2-04） |

## 4. 实施清单（本轮）

1. `docs/contracts/analysis-viewmodel-r7-4.md`：**先行**加性补 **v1.2**（§2 JSON 示例 + 字段表新增 `source` 行 + 文首口径边界引用 P2-04）。
2. `AnalysisViewModel`：记录新增 `String source`（紧随 `snapshotId`）、`of(...)` 增参、`empty()` 传 null、javadoc 补 `@param source`。
3. `AnalysisService.view(...)`：`blankToEmpty(meta.getSource())` 原样回显（空串保持空串，不臆造 `spark-ads`）。
4. 测试：`AnalysisViewModelTest`（3 处）+ `AnalysisServiceTest`（fixture 补 `spark-ads`、总览用例补断言、空信封补 null 断言、**新增**空串回显用例）、
   `EvidenceBuilderTest`（3 处调用点机械改参）。
5. `scripts/run-tests.ps1`：基线上调（本任务新增 1 条 Java 用例）。

## 5. 实测证据

### 5.1 RED（先红，编译红 —— Java 新增记录分量的 TDD 红只可能表现为编译失败，先例 S3-15 RED #0 / S3-16 RED-2）

`mvn -f analytics-server/pom.xml test -Dtest=AnalysisViewModelTest,AnalysisServiceTest`：

```
[ERROR] .../AnalysisViewModelTest.java:[25,25] 找不到符号
[ERROR] .../AnalysisViewModelTest.java:[40,34] 无法将记录 AnalysisViewModel<T> 中的方法 of 应用到给定类型
[ERROR] .../AnalysisViewModelTest.java:[42,25] 找不到符号
[ERROR] .../AnalysisViewModelTest.java:[56,34] 无法将记录 AnalysisViewModel<T> 中的方法 of 应用到给定类型
[ERROR] .../AnalysisServiceTest.java:[82,29] 找不到符号
[ERROR] .../AnalysisServiceTest.java:[102,25] 找不到符号
[ERROR] .../AnalysisServiceTest.java:[154,25] 找不到符号
[INFO] BUILD FAILURE    （mvnExit=1）
```

- 行号即判定点：`:25`/`:42` = `model.source()` 不存在；`:40`/`:56` = `of(...)` 参数个数不符 ⇒ **新 API 改前确实不存在**。
- 证据：`$env:TEMP\s317-red1.log`（副本 `.verify/s317/red1-mvn.log`）。
- **局限（如实登记）**：本轮 RED 是**编译红**，不是行为红 —— 新增记录分量无法在"旧代码存在"的前提下产生行为失败；
  行为正确性由 GREEN 的断言承担（`source()` = `"spark-ads"`、空串 ⇒ `""`、无快照 ⇒ null）。

### 5.2 GREEN（靶向，含跨模块编译面）

`mvn -f analytics-server/pom.xml test -Dtest=AnalysisViewModelTest,AnalysisServiceTest,EvidenceBuilderTest`：

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- AnalysisServiceTest
[INFO] Tests run:  3, Failures: 0, Errors: 0, Skipped: 0 -- AnalysisViewModelTest
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- EvidenceBuilderTest（ai-decision 模块）
[INFO] Reactor: platform-common / connection-ingestion / warehouse-pipeline / metric-analysis / ai-decision / platform-app 全 SUCCESS
[INFO] BUILD SUCCESS   （mvnExit=0）
```

- 新增 1 条（`AnalysisServiceTest.blankSnapshotSourceIsEchoedAsEmptyString`）⇒ `AnalysisServiceTest 10 → 11`。
- 关键断言：`模型.source()=="spark-ads"`（总览）、空串 ⇒ `isEmpty()` 且 `warnings` 为空、无 ACTIVE ⇒ `source()` 为 **null**。
- 证据：`$env:TEMP\s317-green1.log`。

### 5.3 门禁（fresh 真跑，门禁版本 = 待提交版本）

**第 1 轮 `s317_20260916_def`（改代码后、基线上调前）**：`[FAIL exit=7]` ——
`analytics-server 915 (F=1 E=0 S=1)` 明细 `90+350+163+**73**+92+147` ⇒ **DRIFT(基线 914，+1)**；
`mall-simulator 13` MATCH、`synthetic-data-generator 106` MATCH；三棵树 `1034`（基线 1033）。
增量 **+1 恰为**本轮新增用例（`metric-analysis` 模块 72→73），**无其它漂移** ⇒ 按既有程序上调基线。

**第 2 轮 `s317_20260916_def2`（基线上调后，待提交版本）**：**计数全 MATCH、`[FAIL exit=7]`** ——
`analytics-server 915 (F=1 E=0 S=1)`（`90+350+163+73+92+147`）／`mall-simulator 13`／
`synthetic-data-generator 106`、三棵树 `1034`（基线 1034）、**无 DRIFT**；
**唯一红 ＝ 已登记环境性** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
（断言点 `:61`，期望 43 条历史 manifest／实测 0，本工作树无 `landing/manifests`），
**未修、未复制 manifest、未用开关掩盖**（与 S3-16 同一登记项，逐字同因）。

**未跑 spark 档**，理由：本轮**零 Spark 改动**（`spark-jobs/**` 字节未动；`git status` 实证）。

证据：`.verify/s317/def/{default-*.log}`、`.verify/s317/def2/{default-*.log}`（gitignored）；摘要副本
`.verify/s317/def1-summary.log`、`.verify/s317/def2-summary.log`。

### 5.4 不得越界表述

- 本轮**不**声称：业务源身份已暴露（`source` 是**发布方**，P2-04 明确不得当源身份用）；
- 本轮**不**声称：页面已显示数据源（E5-c 的页面缺口属**阶段5**；`readEnvelope` 逐键取值 ⇒ 新字段被忽略）；
- 本轮**不**声称：`source` 的**真库**取值分布（未连库；`V1` 默认值与 `MetricPublishRepository` 字面量只证静态一致）。

## 6. 未测与边界

| # | 未测/边界 | 原因与证据域 |
|---|---|---|
| 1 | 真库 `metric_snapshot.source` 实际取值 | 零连库；隔离档（3307 无监听）未跑，`AnalysisGoldenMySqlIT`（D 类，永久排除）未运行 |
| 2 | 前端展示 | `readEnvelope` 未取 `source` ⇒ 页面不显示（阶段5） |
| 3 | 「源作用域筛选」（设计 L414/L542 的 `MetricQuery` 扩展源/维度筛选） | **未实现**：`MetricQuery` 仍只有 `snapshotId/latestActive`；属设计目标接口，需单独开发项 |
| 4 | 归档快照读取授权（指导书 L157） | **未实现**：显式 `snapshotId` 请求可读任意状态快照（含 ARCHIVED），无授权区分 —— 需鉴权语义裁决，见 §8 |
| 5 | L158 分页/限流/超时/异步任务反馈 | **未评估完**，见 §8 |

## 7. 检索证据（「未改即证据」）

- `git grep -n "getSource()" HEAD -- analytics-server/metric-analysis/` → **无命中**（改前读取侧零消费，§2.2）。
- `git grep -n -i "viewmodel" -- contract-specs/` → 仅 `README.md:136`「归 `docs/contracts/analysis-viewmodel-r7-4.md`…**本目录不复制**」⇒ 门⑥不触。
- 受保护面零改动：`git status --porcelain -- contract-specs db warehouse spark-jobs docs/guidance docs/design` **无输出**。
- 前端兼容性依据：`web/src/utils/envelope.js:22-30`（逐键取值，未知键忽略）。

## 8. 遗留 / 后续（登记，不擅自实施）

1. **`source` 的页面展示**（E5-c D-c）：阶段5 页面工作；后端字段本轮已就位。
2. **业务源身份（`source_system`/`source_instance_id`）不进入分析信封**：P2-04 裁决明确 `metric_snapshot.source` **不是**源身份；
   若要在看板回答"这些数值来自哪个业务源"，需新契约字段 + 上游承载裁决 ⇒ **登记，需总控批注**。
3. **指导书 L157「归档读取授权」未满足**（实测）：`AnalysisService.pin()` 只校验快照存在，不校验状态/角色；
   显式请求 ARCHIVED 快照会照读。引入"归档读取需授权"属**新鉴权语义**（门②邻域）⇒ **须总控批注**后另行开工。
4. **指导书 L158**（异步任务反馈 / 分页 / 限流 / 超时统一）**尚未逐条评估** ⇒ 阶段4 下一候选开发项。
5. **设计 L414/L542 的「源作用域筛选」**（`MetricQuery` 扩展源/维度）未实现 ⇒ 阶段4 候选开发项（需先定"源作用域"落在哪一列）。
