# S3-28 设计差异登记：阶段5 页面展示规则定义版本 `quality.ruleVersions`（读侧消费）

- 日期：2026-09-16（项目内日期口径）
- 分支：`feature/v3-development`（worktree `D:\Develop_code\GraduationProject-wt\v3-dev`；**未 merge main**）
- 轮次定位：V3.0 持续执行模式 **round 7/40**（goal `goal-dd741915-9d85-4d36-a1ca-c92f6a534702`）
- 前序提交：`0293bf4`（S3-27 代码）、`ddc0e6b`（S3-27 文档）
- 类别判定：**A 类（实现／加性，读侧消费）** —— 11 门逐门**否**（见 §5）

## 0. 证明边界（先声明，避免越界解读）

1. 本轮**未运行** `vite build`（`web/node_modules` 不存在，`'vite' is not recognized…`；安装依赖需联网且未授权）
   ⇒ **SFC 模板编译未由编译器验证**，模板层证据**只有源码文本守卫**与纯函数单测。
2. 本轮**未起任何前端/后端服务**，**无浏览器/DOM 走查**，**未发起真实 HTTP 请求**
   ⇒ **不得**声称「页面上已能看到规则版本」。
3. 本轮**零连库**（3306 只读未连、3307 无监听）⇒ 真实库 `rule_version` 的实际取值与 NULL 占比**未测**。
4. 本轮**未改**任何 Java/Scala/SQL/迁移/脚本/依赖；后端 `ruleVersions` 是 **S3-24 已发布**的既有字段。
5. 页面渲染的是**后端返回值**（`data.quality.ruleVersions`）；本轮**不**新增字段、**不**重算、**不**补版本。
6. 证据仅落在 gitignored `.verify/**` 与 `$env:TEMP`；历史记录**只追加**，不改写已发布证据。
7. 门禁 `[FAIL exit=7]`（唯一红＝已登记环境性红）为**预期**，**不得**写成 `[PASS exit=0]`。

## 1. 逐字锚点（判定依据，全部引自仓内文件）

| 来源 | 位置 | 逐字/要点 |
| --- | --- | --- |
| 契约 | `docs/contracts/analysis-viewmodel-r7-4.md` **v1.6 L79-96** | `/dashboards/overview`（§3.1）与 `/analysis/sales`（§3.2）的 `quality` **加性**新增 **`ruleVersions`**（规则码 → 规则定义版本），值 = `ads_data_quality_m.rule_version` **原样透传** |
| 契约 | 同上 **L86-89** | **键集语义**：`ruleVersions` 键集是 `ruleCount` 的**子集**；不出现的规则码 = 该行 `rule_version` 为 NULL 或不可解析，**不补 0、不冒充 v1**；键序按规则码升序 |
| 契约 | 同上 **L94**（本项交接句） | 「**仍未实现**…：阶段5 页面**尚未**展示规则版本（`web/**` 本版未改）」 |
| 契约 | 同上 **L95** | 「设计 §9.3 **L335**「规则版本与**实时结果**待接齐」的**另一半**（发布链实时回写）」——本轮**不**触碰该半 |
| 契约 | 同上 §3.1 **L202** / §3.2 **L221** | 两处响应示例均含 `"ruleVersions": { "AMOUNT_RECONCILE": 1, "ENUM_WHITELIST": 1 }`；**L231** ＝ 两处同源同语义（同一 `quality()` 所有者） |
| 设计 | `docs/design/项目设计文档 V3.0.md` **§9.3 L335** | `ads_data_quality` 行：「历史已发布，**规则版本与实时结果待接齐**」 |
| 设计 | 同上 **§12.3 L512** | 「每条规则记录作用域、阈值、**版本**、阶段、实际值、passed、原始/生效严重度」 |
| 指导书 | `docs/guidance/项目完整实施指导书 V3.0.md` **阶段5 L156** | 「MetricStore/专题服务返回明确 source、snapshot、**definitionVersion**、时间和**质量信息**」 |
| 指导书 | 同上 **阶段5 L163 / L164** | L163 各页接真实 API；L164 页面统一状态并显示「更新时间、来源、快照和**限制**」⇒ 版本列表**必须**带限制说明 |
| backlog | `docs/PROJECT_STATUS.md` **L368**（本项本体） | 「阶段5 页面**未展示**规则定义版本：`quality.ruleVersions`（**S3-24 起已由后端返回**，契约 v1.6 就绪）在 `web/src/views/Sales.vue`（`qualityText` 只读 `ruleCount`/`passedCount`/`failedRules`）与 `views/Overview.vue` **零消费**」⇒ 判定「**阶段5 开发项**（非阻塞）」、边界「不得据此声称『页面已展示规则版本』」 |
| 后端 | `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisService.java` **L96-109 / L596-616** | `QualitySummary` 含 `Map<String,Integer> ruleVersions`（紧凑构造器归一为 `TreeMap` 不可变副本）；`quality()` 仅对非空 `rule_version` 落键 |
| 后端测试 | `.../AnalysisServiceTest.java` **L149-180** | 断言「按码升序进 `ruleVersions`」「NULL 不进、不补 0/1」「**overview 与 sales 两端点同值**」 |

**结论（改前）**：缺口是「后端已发布并已被单测覆盖、契约已登记、**页面零消费**」的**确证缺口**，不是推测；
且 backlog 该行**逐字点名** `Sales.vue` 与 `Overview.vue` 两处 ⇒ 本轮在两处消费**属本项范围内**，不越阶段。

## 2. 开工前实测（改前，HEAD = `ddc0e6b`）

| 编号 | 实测命令 | 结果 |
| --- | --- | --- |
| F1 | `git grep -n "ruleVersions" -- web` | **0 命中**（`exit=1`）⇒ 前端全仓零消费确证 |
| F2 | `git grep -n "quality" -- web/src` | 命中点：`Sales.vue:117-123` 内联 `qualityText`（只读 `ruleCount`/`passedCount`/`failedRules`）；`Overview.vue:91` defaults `quality: {}` 但**模板无任何质量展示**；`Ops.vue` 走 `/metrics/quality` 列表（另一 owner，与本项无关） |
| F3 | `git grep -n "ruleVersions" -- analytics-server` | `AnalysisService.java` L96-109／L596-616（字段与读取）；`AnalysisServiceTest.java` L149-180（**两端点同值**断言）⇒ 后端就绪 |
| F4 | 契约 §3.1/§3.2 | 两处响应示例均含 `ruleVersions`；v1.6 键集语义与「不补 0/不冒充 v1」在册 |
| F5 | `docs/PROJECT_STATUS.md` L368 | 本项仍在册（状态「阶段5 开发项（非阻塞）」），**唯一**「未展示规则版本」登记行 |
| F6 | `git grep -n "quality" -- web/tests` | 既有测试只覆盖 `tables.qualityRows`（`/metrics/quality` 列表）与 `envelope.qualityText`（信封状态）⇒ **`data.quality.ruleVersions` 无任何测试** |
| F7 | `npm test`（改前基线） | **92/92 全绿**、`exit=0`（S3-27 后基线） |

## 3. 口径声明（本轮冻结）

1. **只展示不重算**：页面**原样**展示后端 `ruleVersions` 的键值对；不为缺失规则码补 `0`、**不**冒充 `v1`、
   **不**用「最新版本」等默认值填充（与写入侧 `V8__ads_data_quality_rule_version.sql`「**不写 0 冒充 v1**」同源判据）。
2. **空集显式化**：无任何版本记录 ⇒ 页面显示「**无版本记录**」（**不**留空、**不**显示 `v1`）。
3. **键在值不可解析**（理论上后端不产出，前端防御）⇒ 该码显示「**未记录版本**」，**不**臆造版本号。
4. **键序**：按规则码**升序**渲染（与后端 `TreeMap` 同序）⇒ 同一快照多次渲染顺序稳定。
5. **单一属主**：通过情况文案（`qualitySummaryText`）与版本列表文案（`ruleVersionText`）、限制说明
   （`RULE_VERSION_NOTE`）**只**在 `web/src/utils/quality.js` 定义；`Sales.vue`/`Overview.vue` **只引用不自拼**
   （守卫测试对 `failedRules.join` / `Object.keys(…ruleVersions)` 做反证）。
6. **必须带限制说明**：两视图都渲染 `RULE_VERSION_NOTE`（指导书 L164「显示…限制」），文案写明「键集是子集、
   不补 0、不冒充 v1」。
7. **不越界**：展示**只**回答「本次快照按哪一版规则判定」；**不**构成质量达标、**不**构成「版本已全部登记」、
   **不**构成「规则版本与实时结果已接齐」（设计 L335 的另一半＝发布链实时回写**未做**）。

## 4. 实现面

| 文件 | 改动 | 性质 |
| --- | --- | --- |
| `docs/contracts/analysis-viewmodel-r7-4.md` | **先改**：追加 **v1.8** 段（读侧消费 + 展示层口径 5 条 + 「仍未实现」） | 契约先行（本文件 L3-4「**先改本文件再改代码**」） |
| `web/src/utils/quality.js` | **新文件**：`RULE_VERSION_NOTE`、`qualitySummaryText()`、`ruleVersionText()` | 纯函数，零依赖（仅 `./number.js` 既有 `formatInteger`） |
| `web/src/views/Sales.vue` | 内联 `qualityText` 计算**收敛为** `qualitySummaryText`；新增 `ruleVersionsText`；提示行补「规则版本：… 限制说明：…」；import 统一属主 | 消费既有字段；**不**改列/不改导出 |
| `web/src/views/Overview.vue` | 新增「数据质量（快照 run）」块（质量规则 + 规则版本 + 限制说明）＋两个 computed ＋import | 纯加性 UI（该页此前**零**质量展示） |
| `web/tests/quality.test.js` | **新文件**：6 条（4 条纯函数含空集/不可解析/升序、1 条常量文案、1 条两视图消费与「不自拼」反证） | TDD 先写（RED 见 §6.1） |

**未改**：`web/package.json`（**零新依赖**）、`api.js`、`envelope.js`、`context.js`、`chartState.js`、`chartOptions.js`、
`tables.js`、`exportCsv.js`、`number.js`、`components/**`、其余 7 个视图、任何 Java/Scala/SQL/迁移/脚本/CI。

## 5. 11 条 HARD DECISION GATE 逐门核对

| # | 门 | 判定 | 依据 |
| --- | --- | --- | --- |
| ① | DROP TABLE/COLUMN | **否** | 零 DDL；无删除 |
| ② | 改已有字段类型或既有业务语义 | **否** | 未改任何响应字段；`ruleVersions` 语义**沿用 v1.6**，仅在页面展示 |
| ③ | 改已发布 Flyway migration | **否** | 未触碰 `db/**` |
| ④ | 写/迁移正式 3306 数据 | **否** | 零连库、零 SQL 执行 |
| ⑤ | 切 ACTIVE | **否** | 未触碰任何 profile／开关 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **否** | `git grep` 确认 `contract-specs/**` **0 命中**；改的是 `docs/contracts/**`（可编辑，且为**加性**版本说明） |
| ⑦ | 改 V3.0 总体架构 | **否** | 仅页面文案与纯函数工具 |
| ⑧ | 改正式项目范围 | **否** | 实现的是 backlog L368 已登记项，**不**新增范围 |
| ⑨ | 删除已发布功能 | **否** | 旧提示行保留（仅把内联文案改为引用共享属主，**输出文案等价 + 增补版本信息**） |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **否** | 零新依赖（`package.json` 未改） |
| ⑪ | 两种方案造成重大长期架构分叉 | **否** | 单一属主收敛（消除潜在双份文案），不产生分叉 |

## 6. 实测

### 6.1 RED / GREEN（TDD，真跑；`web/`，node v24.16.0 / npm 11.13.0）

- **RED**（`$env:TEMP\s328_red.log`，先写 `web/tests/quality.test.js` 后跑）：`tests 93 / pass 92 / fail 1`、**`exit=1`**；
  红因＝**文件级模块错误** `Cannot find module '.../src/utils/quality.js'`（被测属主尚未创建）⇒ 6 条新用例未计入
  （93 = 92 ＋ 1 个失败文件）⇒ 用例对「实现缺失」有判别力。
- **GREEN 第 1 次尝试**（`$env:TEMP\s328_green1.log`）：`tests 93 / pass 92 / fail 1`、`exit=1`、`duration_ms 153.5672`；
  红因＝**真实缺陷** `ERR_MODULE_NOT_FOUND: Cannot find module '.../src/utils/number'` —— 新文件按打包器习惯写了
  无扩展名相对导入，而 `node --test` 的 ESM 解析**要求扩展名**（同目录既有 `tables.js` 亦写 `'./number.js'`）。
  **如实登记为失败轮次**，不并入 GREEN。
- **GREEN（终态）**（`$env:TEMP\s328_green2.log`）：修 `quality.js` 导入为 `'./number.js'` 后
  **`tests 98 / pass 98 / fail 0`**、**`exit=0`**、`duration_ms 155.7021`（92 → 98，**+6** ＝ 新文件全部用例）。
- **未另做变异探针**（RED 的两轮已分别证明「实现缺失」与「导入缺陷」可被捕获；与 S3-26/S3-27 同口径）。

### 6.2 default 档回归（统一门禁脚本）

- 命令：`& .\scripts\run-tests.ps1 -Suite default -RunId s328_20260916_def1 -LogDir .verify/s328_def1 -Confirm`
  （**未加** `-AllowCountDrift`；PowerShell 7 下执行）。
- 结果：`analytics-server exit=1 Tests run: 949 (F=1 E=0 S=1)`、明细 `93+350+163+93+93+157`、`tests=949 MATCH`；
  mall `13 MATCH`（`exit=0`）；generator `106 MATCH`（`exit=0`）；**三棵树 1068 ＝ 基线 1068**、**无 DRIFT**；
  脚本末行 `[FAIL exit=7]`。
- **唯一红 ＝ 已登记环境性红** `com.graduation.analytics.ingestion.IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
  （`[清单快照 39＋P1-05 前批 4（40-43.json）＝P1-05 期望真实采集数= 43] expected: 43 but was: 0`，
  `IngestionManifestRuntimePatrolTest.java:61`；platform-app `Tests run: 157, Failures: 1, Errors: 0, Skipped: 0`）
  ⇒ 本档结论 ＝「**计数 MATCH ＋ 唯一红＝该已登记环境性红**」。
- **spark 档本轮未跑**（零 Scala/Java 改动），spark ＝ **303 沿用 S3-25 `s325_20260916_spark2`（`[PASS exit=0]`）**；
  `isolated` 档未跑（S3-27 同口径）。
- 失败日志留存在 `.verify/s328_def1/**`（gitignored），本登记不复制其内容以免与权威日志分叉。

## 7. 未测与边界（不得越界表述）

1. **模板编译未证**：`vite build` 未通过（`web/node_modules` 缺失）⇒ `Overview.vue` 新块与 `Sales.vue` 提示行
   **未由 SFC 编译器解析**，证据只有源码文本守卫与纯函数测试。
2. **渲染未证**：无浏览器/DOM 走查、未起前端与 8091 ⇒ **不得**称「页面上已正确显示规则版本」。
3. **真实 HTTP 未测**：本轮未发请求 ⇒ `ruleVersions` 的真实 JSON 形状沿用 S3-24 证据。
4. **真实数据未测**：真实库 `rule_version` 取值分布、NULL 占比、历史快照「未记录版本」比例**均未测**。
5. **不在页面上做质量判定**：`passed` 与 `ruleVersionText` 只作展示；页面**不**据版本做任何门禁/放行判断。
6. **设计 L335 另一半未做**：发布链「**实时结果**回写/接齐」**未实现** ⇒ **不得**称「规则版本与实时结果已接齐」。
7. **契约 v1.6 的其它「仍未实现」不变**：§12.3 规则 5/6/11、L158 限流、L157 归档读取授权、L506 不变式。
8. **阶段5 其余项不变**：`repeat_rate` 周期/口径版本展示、支付复购率变体、E5-c 剩余一半（业务源身份 + `/pipeline`）、
   `/analysis/sales` 真窗口过滤。
9. **导出件未带版本**：本轮**未**把规则版本写入 CSV 导出元信息（Sales/Overview 导出的是指标行/卡片）⇒
   导出件仍**不**自证「按哪一版规则判定」，该点**不得**宣称已解决。

## 8. 结论与下一候选

- 本项（backlog **L368**）自本轮起**页面侧已消费**：`Sales.vue` 与 `Overview.vue` 均展示「规则通过情况 + 规则版本
  （按码升序）+ 限制说明」，文案单一属主 `web/src/utils/quality.js`；契约按程序**先改**（v1.8）**再**改代码。
- 判定：**A 类完成**（代码 + 测试 + 门禁计数 MATCH；唯一红为已登记环境性红）。
  边界（§0/§7）随登记一并生效，**不得**据此声称「页面渲染已验证」「版本已全部登记」「实时结果已接齐」。
- **下一候选（滚动 backlog **表序**；下列行号为本登记时点实读，非推测）**：`docs/PROJECT_STATUS.md`
  **L369**（`AiSqlDriftTest.ddlTables()` 残留：迁移文件名按字典序排序，V10+ 会错序）→ **L370**（DDL 加列类变更存在
  「第二所有者」盲区）→ **L371**（DIM 三张表有 DDL 无生产链）→ **L372**（`repeat_rate`/`repeat_period_*` 真库存在性
  **未测**，受连库限制）→ **L373**（已发布迁移 `V2` 的 `repeat_rate` 公式文案与设计 §11.x 口径差异，**可能触门②
  需裁决**）→ **L374**（「支付复购率」变体未落地）→ **L377**（`repeat_rate` 系列列**尚无消费方**，阶段4/5）→
  **L384**（`cart_rate` 未进概览 API，阶段4/5 / R-4）。
  下一轮**须按表序重扫**取**第一个**「既未被他项阻塞、也未被判为需总控裁决」的项，不在本登记内预先承诺。
- **轮次标注更正（追加，不改上文，2026-09-16）**：本登记头部所写「round 7/40」为**当轮自报序号**；收口时读
  goal 元数据实测为 `revision 1 / phase active / roundsStarted 5 / maxGoalRounds 40 / activation armed`
  ⇒ 自报序号比 `roundsStarted` **偏大**（该计数只在**自动续跑轮**开始时递增，本会话内一轮里可完成多个 S3-nn）。
  **权威标识以 `S3-nn` 任务编号为准**，括号内 round 号仅供顺序参考；此处如实登记差异，不改写 S3-27 登记（F-60）
  的同类自报数字。
