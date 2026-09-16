# S3-21 设计差异登记：商品热度榜排序键（`sort`）

- 编号：S3-21（对应事实记录 F-54）
- 日期（项目内）：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 起点提交：`c4c09dc`（F-53 / S3-20 阶段4 净销售额消费侧）
- 判类请求：**A 类（实现/加性）**——不删表/列、不改既有字段类型与业务语义、不改已发布 Flyway 迁移、
  不动正式 3306 数据、不切 ACTIVE、不改 `contract-specs/**` 契约语义、不改 V3.0 总体架构/范围、
  不删已发布功能、不引入 V3.0 未规划的大型基础组件、不造成长期架构分叉。

## 0. 结论（先说边界）

本项给 `/api/v1/analysis/products` 的**热度榜**加一个**排序键参数** `sort`（形式 `字段` 或 `字段,asc|desc`，
白名单 `rank`/`heat`/`pv`/`fav`/`cart`/`buy`，缺省 `rank,asc`），补齐设计 **L693**「分页 `page`/`size`/`sort`」
里 v1.3 明文留白的第三项。

**一句话**：本项只让**同一个 ADS 结果集换一种顺序输出**，**没有**新指标、**没有**改口径、**没有**改分页语义、
**没有**改前端、**没有**连真库。

因此本项**不得**被表述为：前端已支持排序 ❌ / 真库排序结果已核 ❌ / L158 分页/限流/超时已全部满足 ❌
（限流仍未实现）/ 设计 L506 不变式已实现 ❌（与 S3-20 同，仍未做）。

## 1. 判类依据（逐字原文，本项开工前实测）

| 来源 | 行 | 逐字原文 |
|---|---|---|
| 设计 V3.0 | **L693** | 「根路径/api/v1；响应code/message/data/traceId；**分页page/size/sort**；写操作幂等请求头按实际能力明确。长任务返回taskId与可查询状态，不能保持HTTP直到Spark结束。…」 |
| 设计 V3.0 | **L675** | 「\| 商品分析 \| 热度/销量/转化/退款、**稳定排行、分页** \| 成本库存缺失就不算利润/覆盖 \|」 |
| 指导书 V3.0 | §7 阶段4 **L158** | 「3. 异步任务返回标识，提供阶段/失败/重试反馈；**分页**、限流、超时统一。」 |
| 本仓契约 R7-4 | **v1.3 明文留白** | 「**本版只落 L158 的「分页」一项**：`sort` 参数（设计 L693 同一行）与「限流」「超时统一」**本版未实现**」 |

## 2. 冻结事实（本项开始前实测，全部可复现）

| 编号 | 事实 | 证据（命令/文件位置） |
|---|---|---|
| F1 | 起点提交**全仓无 `sort` 请求参数** | `git grep -n -E "RequestParam[^)]*sort" c4c09dc -- analytics-server` ⇒ **0 命中**（同提交内 `sort` 仅出现在服务内部 `List.sort(...)`：`AnalysisService.java:301,435,466`、`RfmService.java:167,173,203`） |
| F2 | `contract-specs/**` **无** `page`/`size`/`sort` 任何字面 | `git grep -n -i -E "page\|size\|sort" -- contract-specs` ⇒ **0 命中**（门⑥不触；本项改的是 R7-4 实现契约，按其自身程序**加性**升版） |
| F3 | 前端**不用** `sort`（也不传 `page`/`size`） | `git grep -n -E "page\|size\|sort" -- web/src` ⇒ 仅 `font-size`/`chart.resize()` 等无关命中；`web/src/api.js:87` `products: (params = {}) => client.get('/analysis/products', { params })` 由调用方传参 |
| F4 | 白名单 6 列**全部**是 ADS 表既有列，**不新增列** | `MetricAdsCatalog.java:40-43`：`ads_hot_product_m` 白名单 = `product_id, product_name, heat_score, pv, fav, cart, buy, rank_no, rule_version`，排序主键 `rank_no` |
| F5 | 温度列**可能取不到值**（缺列/非数值 ⇒ null） | `AdsRows.asDecimal` 语义（v1.4 契约 §3.2 第 3 条同源：「缺列或值畸形 ⇒ `null`，**不臆造 0**」） |
| F6 | v1.3 已有稳定排行末级键 `product_id` 升序 | 契约 R7-4 §3.3 v1.3 条「同 `rank_no` 以 `product_id` 升序打破平局（设计 L675「稳定排行」）」 |
| F7 | 质量规则目录当前规模（供后续规则项对齐） | 门禁内守卫实测输出：`[GUARD-EVIDENCE] catalogDefinitions=37 comparisons=74 blockingCodesOnFailure=33`（`RuleSeverityPathConsistencyTest`） |

## 3. 11 门逐门核对（全部“不触”）

| 门 | 判定 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 不触 | 无任何 DDL/迁移改动（`git status` 无 `db/` 变更） |
| ② 改已有字段类型或既有业务语义 | 不触 | 不改任何响应字段与取值路径；`sort` 仅**新增**请求参数，缺省值与 v1.4 逐行同序 |
| ③ 改已发布 Flyway migration | 不触 | 未新增、未修改任何迁移文件 |
| ④ 写/迁移正式 3306 数据 | 不触 | 无数据库写入（未连库、未跑 IT） |
| ⑤ 切 ACTIVE | 不触 | 无快照状态变更 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 不触 | F2 实测 0 命中；改动的是 R7-4 实现契约，按其 L3-4 程序**加性**升版 v1.5 |
| ⑦ 改 V3.0 总体架构 | 不触 | 数据流不变：`MetricAdsReader`（既有）→ `AnalysisService` → 响应；仅在服务内加排序 |
| ⑧ 改正式项目范围 | 不触 | 实现设计**已冻结**的 L693/L675 要求，范围不变 |
| ⑨ 删除已发布功能 | 不触 | 纯加性（旧调用方不传 `sort` ⇒ 原顺序） |
| ⑩ 引入 V3.0 未规划的大型基础组件 | 不触 | 零新依赖、零新组件（仅用 JDK `Comparator`） |
| ⑪ 两种方案造成重大长期架构分叉 | 不触 | 唯一实现路径（内存内比较器，与既有分页同一处理层）；无备选架构 |

## 4. 实施清单（1 个代码提交 + 1 个 docs 提交）

| 文件 | 改动 |
|---|---|
| `analytics-server/metric-analysis/.../analysis/AnalysisService.java` | `products(...)` 增加 `String sort` 参数（**在 `page`/`size` 之后、`topN` 之前**）；新增 `HotSort` 记录 + `SORT_FIELDS`/`SORT_DESC_FIELDS`/`DEFAULT_HOT_SORT` 常量 + `resolveSort` 校验 + `rankedHotRows` 排序 + `hotKeyComparator`；`filters.put("sort", effectiveSort.echo())` |
| `.../metric-analysis/src/test/.../analysis/AnalysisServiceTest.java` | +7 用例（21→28），13 处旧调用点迁 7 参；新增夹具 `hotRowFull` |
| `analytics-server/platform-app/.../controller/AnalysisController.java` | 新增 `@RequestParam(required = false) String sort` 并透传；javadoc 记 v1.5/S3-21 |
| `analytics-server/platform-app/src/test/.../controller/AnalysisControllerTest.java` | 2 处调用点迁 7 参；装配测试改用 `"buy,desc"` 断言透传 |
| `analytics-server/ai-decision/.../evidence/EvidenceBuilder.java` | 调用点补 `null`（证据包不引入新排序口径）+ 注释 |
| `analytics-server/ai-decision/src/test/.../evidence/EvidenceBuilderTest.java` | 桩参数对齐 |
| `analytics-server/metric-analysis/src/test/.../analysis/AnalysisGoldenMySqlIT.java` | 调用点迁 7 参（该 IT **本轮未运行**，属已登记未测项） |
| `docs/contracts/analysis-viewmodel-r7-4.md` | **v1.5** 加性升版：版本横幅 + §3.3 端点签名与排序规则 + 旧调用方影响 |
| `scripts/run-tests.ps1` | 基线 `analytics-server` 936→**943**（三棵树 1055→**1062**）+ S3-21 注释块 |
| `docs/acceptance/s3-21-products-sort-20260916/*` | 本登记文件 + 质量 12 项覆盖复测 |
| `docs/PROJECT_STATUS.md`、`docs/status-history/开发过程事实与决策记录.md` | F-54 记录与状态同步 |

## 5. 实测证据（RED / GREEN / 门禁）

### 5.1 RED（先写测试，编译期即红）

```
$env:JAVA_HOME='D:\Develop\JAVA17'; D:\apache-maven-3.9.14\bin\mvn.cmd -o "-Dmaven.repo.local=D:\maven_repository" `
  -f analytics-server\pom.xml test "-Dtest=AnalysisServiceTest" "-DfailIfNoTests=false" `
  "-Dsurefire.failIfNoSpecifiedTests=false" *> "$env:TEMP\s321_red.log"
```
- 日志：`$env:TEMP\s321_red.log`（本轮实测存在）
- 结果：`[ERROR] COMPILATION ERROR` + `[INFO] BUILD FAILURE`（exit 1）
- 逐条：`无法将 com.graduation.analytics.analysis.AnalysisService 中的方法 products 应用到给定类型`
  —— `AnalysisGoldenMySqlIT.java:[184,56]` + `AnalysisServiceTest.java` 的 **24 处**调用点
  （行号实测：`80,242,265,282,305,321,337,351,363,366,369,372,377,389,405,421,423,425,435,448,450,461,466,479`；
  旧 6 参调用点全部不匹配 ⇒ 新 7 参签名是**唯一**入口）

### 5.2 GREEN（实现后同一命令全绿）

```
同一 mvn 命令 → $env:TEMP\s321_green.log
```
实测：
- `AnalysisServiceTest` **Tests run: 28**, Failures: 0, Errors: 0
- `EvidenceBuilderTest` **Tests run: 7**, Failures: 0, Errors: 0
- `AnalysisControllerTest` **Tests run: 2**, Failures: 0, Errors: 0
- `[INFO] BUILD SUCCESS`（exit 0）

### 5.3 门禁通过记录（默认档全量，RunId `s321_20260916_def2`）

```
pwsh -NoProfile -File scripts\run-tests.ps1 -Suite default -RunId s321_20260916_def2 -LogDir .verify\s321_def2 -Confirm
```
实测摘要（逐字摘自门禁输出）：

```
  analytics-server           exit=1  Tests run: 943 (F=1 E=0 S=1)  模块汇总行 6 明细 93+350+163+90+93+154
                             tests=943 MATCH
  mall-simulator             exit=0  Tests run: 13 (F=0 E=0 S=0)  模块汇总行 1 明细 13
                             tests=13 MATCH
  synthetic-data-generator   exit=0  Tests run: 106 (F=0 E=0 S=0)  模块汇总行 1 明细 106
                             tests=106 MATCH
  default 三棵树                合计 = 1062（基线 1062）
  default   FAIL （1062 个用例）
            失败项：analytics-server
[FAIL exit=7] 所选档未全部通过。
```

- **计数 MATCH**：943 = 936 + 7（metric-analysis 83→90）。
- **唯一红**＝**已登记环境性红** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`
  （`expected: 43 but was: 0`；工作树 `landing/manifests` 被 `.gitignore:30` 排除 ⇒ 该守卫在隔离工作树**必然**红）。
  ⇒ 判定为「**计数 MATCH + 唯一红＝已登记环境性红**」，**不是** exit=0；该红**不得**被修掉或屏蔽。
- 先用量到真值的 RunId = `s321_20260916_def`（该次基线仍为 936 ⇒ analytics 计数不符，属预期）。

## 6. 未测与边界（不得越界表述）

| 项 | 状态 |
|---|---|
| 真 HTTP 查询参数解析（`?sort=heat,desc` 由 Spring 绑定） | **未测**：无 `@SpringBootTest`；控制器测试直接调方法传参 |
| 真库 ADS 数据上的排序结果/稳定性 | **未测**：`AnalysisGoldenMySqlIT` 从未运行（D 类，永久排除 unified 门禁） |
| 前端使用排序（页面传 `sort`、表头点击） | **未实现**：`web/src` 未改（F3） |
| L158 的**限流**、写操作幂等请求头（L693 同句） | **未实现**：设计零锚点/待总控批注 |
| 设计 §12.3 **L506**「ADS GMV ≥ 净销售 ≥ 0」不变式 | **未实现**（沿用 S3-20 登记；本项未触碰） |
| 其它列表端点分页（`/metrics/snapshots`、`/pipeline-runs` 仅 `limit`、`/decisions`、`/ai/audit/*`、`/admin/users`） | **未实现**，登记在 backlog |
| `drop`/`sort` 之外的排序需求（销量/转化/退款维度排序） | 本项只按 ADS 已有列排序；`conversion` 列表**不**受 `sort` 影响（契约明文） |

## 7. 检索证据（本项结论的可复现依据）

```powershell
cd D:\Develop_code\GraduationProject-wt\v3-dev
git grep -n -E "RequestParam[^)]*sort" c4c09dc -- analytics-server      # ⇒ 0 命中
git grep -n -i -E "page|size|sort" -- contract-specs                     # ⇒ 0 命中
git grep -n -E "page|size|sort" -- web/src                               # ⇒ 仅无关（font-size/resize）
git grep -n -A3 "ads_hot_product" -- analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsCatalog.java
```

## 8. 遗留与移交（R 类）

| 编号 | 内容 | 状态 |
|---|---|---|
| R1 | 设计 §12.3 12 项规则：**5/6/8/9/11 未实现**、**10 部分（对账形态）** | 本轮复测见 `QUALITY-RULES-12-COVERAGE-20260916.md`；**8/9 为下一项候选（S3-22）** |
| R2 | `from`/`to` 仍**只回显不过滤**（`/analysis/sales`） | 承接 S3-20 F8，未动 |
| R3 | 阶段5 页面未展示净额、未使用 `sort` | 未动（前端属整理阶段范围） |
| R4 | L157 归档读取授权、L158 限流、L159 发布账号/只读账号分离 | 未动（待批注/未核） |
| R5 | `metric_snapshot.period`（R-4）、支付复购率（R-2）、观察期缺省（R-3）、`repeat_rate` 文案漂移（R-1） | 承接既有登记 |
