# S3-26 设计差异登记：阶段5 第一项「页面显示**来源**」——统一信封 `source`（发布方）消费侧落地

- 任务编号：**S3-26**（V3.0 持续执行模式，滚动选出的第一个尚未满足验收条件的开发项）
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `feature/v3-development`
- 起点提交：`b39803b`（`docs(status): F-58 S3-25 记录…`）
- 代码提交：**`82141ad`**（11 个文件，全部在 `web/**`；无 Java/Scala/SQL/迁移改动）
- 日期（项目内）：**2026-09-16**
- 类别判定：**A 类（实现/加性）** —— 消费**已由契约定义**（r7-4 v1.2 L130，S3-17 落地）的信封字段，
  只加字段、加展示项、加导出行；未改任何既有字段语义，未触 DDL/迁移/Spark/3306，未改 `contract-specs/**`。
  逐门表见 §5。

## 0. 证明边界（先说不能证明什么）

1. **`vite build` 未跑通，且不是本轮引入的问题**：`web/node_modules` **不存在**，`npm run build` 实测
   `'vite' is not recognized as an internal or external command`（exit 1）；安装依赖需联网，本轮**未获授权**
   ⇒ **SFC 模板编译未由编译器验证**。模板层证据只有**源码文本结构守卫**
   （`web/tests/analysisContext.test.js`），它**不能**证明运行时渲染结果。
2. **没有浏览器走查**：本轮**未**启动前端 dev server、**未**启动 8091 后端、**未**做 DOM/截图核对
   ⇒ 「页面上真的多出一栏『来源（发布方）』」只有源码与单测证据，**没有**像素/DOM 证据。
3. **真实 HTTP 响应含 `source` 未实测**：本轮零连库、零起服务 ⇒ 「后端 `/api/v1/analysis/**` 的信封 JSON
   里 `source` 键真实存在」在本轮**未被实测**（沿用 S3-17 的既有证明，不重复也不加强）。
4. **E5-c 页面缺口只推进了一半**：`E5-c` 的原始措辞是「`/overview`、`/pipeline`、`/ops` 三页对
   `spark-ads`/`数据源`/`sourceCode`/`source_id` 命中 0」。本轮补的是**发布方**（生产者）；
   **业务源身份**（`source_id`/`sourceCode`/per-source namespace）**仍未展示**，
   且 `/pipeline` 页**不挂** `AnalysisContext`（`Pipeline.vue` 不引用该组件，实测）⇒
   **不得**称「E5-c 关闭」，也**不得**称「页面已显示数据源」。
5. **未改**：`contract-specs/**`、`docs/contracts/**`、设计/指导书 V3.0、`README.md`、
   任何已发布 Flyway 迁移、任何 `analytics-server/**`、`spark-jobs/**`、`scripts/run-tests.ps1` 基线。
6. **web 测试不在统一门禁内**：`scripts/run-tests.ps1` 的档级基线**不含** `web/**`
   （脚本摘要逐字写明「web 前端（整理阶段范围外）」）⇒ 本轮的 default 档回归**不**证明前端用例，
   前端证据只由 `npm test` 独立提供。

## 1. 逐字锚点（权威三件套 + 契约）

| 来源 | 位置 | 逐字要点 |
|---|---|---|
| 指导书 V3.0 | 阶段5 **L164** | 「页面统一加载/空数据/成功/失败状态，显示**更新时间、来源、快照和限制**」 ← 本项即「来源」 |
| 设计 V3.0 | §15 **L687** | 「错误对业务用户显示简明原因和可做动作，**技术用户附 source**/run/stage/rule/trace」 |
| 设计 V3.0 | §15 **L672** | 经营概览须显示「更新时间/快照」 |
| 设计 V3.0 | §15 **L679** | 运行/质量中心「**普通员工看更新时间**」 |
| 设计 V3.0 | §15 **L685** | 每页四态（加载/空数据/成功/失败） |
| 契约 r7-4 | **v1.2 L14-22** | `source` = `metric_snapshot.source`（**发布方/生产者**），§17.6 只接受 `spark-ads`；无可用快照时 `null`；空串按 `""` 与 `definitionVersion` 同口径，**不臆造值** |
| 契约 r7-4 | **v1.2 L116 / L130** | `source` 为 string\|null；**不是业务源身份**（源身份由 per-source namespace 与 ODS/DWD `source_system`/`source_instance_id` 承载，不得把 `source` 当源身份） |
| 已登记缺口 | `docs/PROJECT_STATUS.md` **L329**（E5-c 行） | 「`/overview`、`/pipeline`、`/ops` 三页对 `spark-ads`/`数据源`/`sourceCode`/`source_id` **命中 0** ⇒ 页面不显示结果所属数据源」；同格逐字写明「页面展示属**阶段5**」 |
| S3-17 交接 | `docs/PROJECT_STATUS.md` **L165** | 「**页面不显示**：`web/src/utils/envelope.js:22-30` 逐键取值、未知键忽略 ⇒ 新字段加性安全，同时页面不显示它（E5-c 页面缺口属**阶段5**）」 ← 本项即该交接的兑现 |

## 2. 开工前实测事实（F1–F9，改前取证）

- **F1（缺口真实，信封 `source` 在 web 侧零消费）**：`git grep -n "source" 82141ad^ -- web/src`
  **共 6 处命中，无一读信封 `source`**：
  `utils/context.js:37-40`（`pickText(source, keys)` 的**形参名**）、
  `utils/tables.js:96` + `:191`（决策表列 `source`＝**决策来源**，另一事物）、
  `views/Decisions.vue:46`（同上，`d.source`）、
  `views/Pipeline.vue:77`（`sourceDataVersion: 'manual-' + Date.now()`，是**请求参数**，与信封无关）。
- **F2（后端已产出，属既有证明）**：`AnalysisViewModel.java:21-24` 逐字记载 `source` 的语义与
  「**不是业务源身份**（P2-04 裁决）」；`:36` 构造参数、`:84-87` `of(snapshotId, source, …)` 工厂；
  `:92` 无可用快照时 `source` 与 snapshotId/时间/口径版本**一律 null**。S3-17 已把该字段放进分析信封
  （本轮**不重复**其证明，也不因本轮而增强）。
- **F3（丢弃点）**：改前 `readEnvelope`（`web/src/utils/envelope.js:22-36`）逐键解 8 个字段
  （snapshotId/businessTime/dataUpdatedAt/definitionVersion/qualityStatus/filters/warnings/data），
  **无 `source`** ⇒ 即使后端返回，页面也拿不到。
- **F4（影响面）**：`git grep -l "AnalysisContext" -- web/src` **命中 8 个视图**
  （AiAssistant/Behavior/Decisions/Ops/Overview/Products/Rfm/Sales）⇒ 上下文条一处改动、八页同见；
  改前组件 meta 区只有**五枚**（快照/业务时间/数据更新/口径版本/质量），**无来源**。
- **F5（导出件无法自证）**：改前 `useAnalysis.js:33-45` `exportContext` 白名单 7 键，**无 `source`**；
  改前 `csv.js:41-55` 元信息 8 行（快照ID/业务时间/数据更新时间/口径版本/质量状态/生效筛选/生成时间/告警），
  **无来源行** ⇒ 导出文件离开页面后无法自证「谁发布的」。
- **F6（非信封路径）**：`buildFallbackContext`（Ops 两处、Decisions 一处调用）与
  `buildAiEvidenceContext`（AI 页）走的都**不是**统一信封 ⇒ 这两类页面**本来就没有** `source`，
  必须显示「未知」而不是借用当前快照的发布方。
- **F7（开工前基线）**：改前 `npm test` = **tests 74 / pass 74 / fail 0**（node v24.16.0、npm 11.13.0）；
  `Test-Path web/node_modules` = **False** ⇒ `npm run build` 不可运行（F0-边界①）。
- **F8（RED 实测）**：先写用例再实现，改前 `npm test` = **tests 72 / pass 65 / fail 7**
  （`envelope.test.js` 因导入尚未存在的 `sourceText` 触发**模块级错误**计 1 条 file 级 fail，
  另有 6 条断言 fail：上下文条「来源」、模板「非业务源身份」、`exportContext.source`、
  CSV 文本含来源、无快照时元信息行位、导出元信息行位）⇒ 用例**确实红**，不是空转。
- **F9（改后 GREEN 实测）**：`npm test` = **tests 84 / pass 84 / fail 0**（+10：envelope +3、
  csv +1、context +2、源守卫 +4），duration 140.6ms。

## 3. 口径声明（本轮冻结的判据）

1. **`source` ＝ 发布方（生产者），不是业务源身份**。页面上的一栏标签写作「**来源（发布方）**」，
   并**必须**附提示「**非业务源身份**」；`web/src` 中**不得**出现 `source_id`/`sourceCode`
   之类把它当源身份用的写法（已由结构守卫断言）。
2. **取不到一律「未知」，绝不臆造**：`sourceText()` 对 `null`/`undefined`/空串/纯空白/非字符串
   统一返回「未知」；页面与测试文本里**不得**出现 `spark-ads` 字面量（已由结构守卫断言）——
   把缺失写成 `spark-ads` 会让归档/历史快照看起来像正常发布。
3. **空串口径与后端一致**：契约 v1.2 规定 `source` 空串按 `""` 处理（与 `definitionVersion` 同口径），
   `readEnvelope` 用既有的 `asText`（空白 → null），**不新增**任何「空串当 spark-ads」的兜底。
4. **非信封接口不重复报缺**：Ops/Decisions/AI 三页本就有 `ENVELOPE_MISSING` 告警，
   因此 `source` **不**并入 `buildFallbackContext` 的 `missing` 清单（避免把「接口非信封」
   重复报成「字段缺失」）；这**不**等于隐瞒——页面显示「未知」，告警解释了原因。
5. **AI 证据包不借源**：`buildAiEvidenceContext` 显式 `source: null`——AI 结果的来源是证据包自身，
   不得用「当前快照的发布方」冒充。
6. **导出元信息同源可比对**：CSV 的「# 来源（发布方）」取自**同一个** `exportContext.source`
   （不是另算一遍），缺失写「（缺失）」，与快照ID/口径版本同一自证口径。

## 4. 实现面（11 个文件，全部 `web/**`）

| 文件 | 改动 | 说明 |
|---|---|---|
| `web/src/utils/envelope.js` | +16/-2 | `readEnvelope` 增 `source: asText(src.source)` + JSDoc 字段行（写明「非业务源身份」）；新增 `sourceText(source)` 输出「未知」兜底 |
| `web/src/components/AnalysisContext.vue` | +7/-1 | meta 区在「数据更新」与「口径版本」之间插入「来源（发布方）」一栏（`sourceText(ctx.source)`）+ 提示「非业务源身份」；导入 `sourceText` |
| `web/src/composables/useAnalysis.js` | +2/-0 | `exportContext` 白名单补 `source: ctx.source \|\| null`（导出件自证发布方） |
| `web/src/utils/csv.js` | +6/-2 | `buildContextRows` 在「# 数据更新时间」后插入 `['# 来源（发布方）', context.source \|\| MISSING]`（行位 index 3）；JSDoc 同步 |
| `web/src/utils/context.js` | +12/-1 | `buildFallbackContext` 增 `source` 入参与「信封 → 显式入参 → null」取值，返回值补 `source`；`buildAiEvidenceContext` 显式 `source: null`；两处注释写明为何**不**入 `missing` |
| `web/src/views/Ops.vue` | +2/-0 | 两处 `ctx → 页面上下文` 投影补 `source: ctx.source`（防再次静默丢字段） |
| `web/src/views/Decisions.vue` | +1/-0 | 同上 |
| `web/tests/envelope.test.js` | +33/-1 | 完整信封透传 / 缺字段为 null / 它方发布方原样透传 / `undefined`·`''`·空白·数值·数组一律 null / `sourceText` 文案 |
| `web/tests/csv.test.js` | +19/-1 | CSV 文本含 `# 来源（发布方）,spark-ads`；无快照时该行「（缺失）」且行位右移断言；新增行位/首行不变断言 |
| `web/tests/context.test.js` | +25/-0 | 信封 source 透传 / 无来源为 null 且**不**进缺失清单 / AI 上下文 `source === null` |
| `web/tests/analysisContext.test.js` | **新增** | 源码结构守卫 4 条（见 §6.1） |

**未改动**（`git status` 实测零命中）：`analytics-server/**`（含全部 Java 与已发布迁移）、
`spark-jobs/**`、`contract-specs/**`、`docs/contracts/**`、`scripts/**`、设计/指导书 V3.0。

## 5. 11 门 HARD DECISION 逐门判定（全部「否」）

| 门 | 判定 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | 无任何 DDL；本轮不碰 SQL |
| ② 改已有字段类型/业务语义 | 否 | 行业务取值的既有字段**语义未改**；`readEnvelope` 只是**新增**一个键，既有 8 键取值逻辑逐字未动（`git diff` 实测） |
| ③ 改已发布 Flyway migration | 否 | 未触任何迁移文件（`git status` 零命中） |
| ④ 写/迁移正式 3306 数据 | 否 | 零连库、零写库 |
| ⑤ 切 ACTIVE | 否 | 不涉及发布/切换 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 未改该目录，也未改 `docs/contracts/**`；**只读**契约 v1.2 既有字段定义 |
| ⑦ 改 V3.0 总体架构 | 否 | 无新组件/新依赖；只在既有 utils/组件/composable 上加字段 |
| ⑧ 改正式项目范围 | 否 | 实现的是指导书阶段5 **L164 已写明**的「显示来源」+ 设计 §15 L687；未扩范围 |
| ⑨ 删除已发布功能 | 否 | 无删除；既有 5 枚 meta 与既有 CSV 行**全部保留**（只插入新项） |
| ⑩ 引入 V3.0 未规划大型基础组件 | 否 | 零新依赖（`package.json` 未改） |
| ⑪ 两种方案重大长期架构分叉 | 否 | 「页面显示 source」由契约 v1.2 + 指导书 L164 给定方向；「未知 vs 臆造」由契约「不臆造值」逐字给定，非自由分叉 |

## 6. 实测证据（未实测不写结论）

### 6.1 定向套件（RED → GREEN）

- **RED（改前，先写用例）**：`npm test` ⇒ `tests 72 / pass 65 / fail 7`；
  6 条断言红（上下文条「来源」正则、模板「非业务源身份」正则、`source: ctx.source || null` 正则、
  `# 来源（发布方）,spark-ads` 正则、无快照行位 `# 来源（发布方）`、导出元信息行位）
  ＋ `envelope.test.js` 模块级错误 1 条（导入不存在的 `sourceText`）⇒ **用例有判别力**。
- **GREEN（改后）**：`npm test` ⇒ **`tests 84 / pass 84 / fail 0`**（+10），
  node v24.16.0、npm 11.13.0，`duration_ms 140.566`。
- **新增守卫的判别力设计**（`web/tests/analysisContext.test.js`，**源码结构守卫**，无 SFC 编译器）：
  ① 模板含「来源」且含 `sourceText(ctx.source)`，并断言模板**不得**含 `spark-ads`；
  ② 模板含「非业务源身份」，且**不得**含 `source_id`/`sourceCode`；
  ③ composable 含 `source: ctx.source || null`；
  ④ 8 个视图**仍**引用 `AnalysisContext`（防「改了组件但没人用」的空转）。
  **边界**：本守卫只读源码文本，**不**证明渲染结果，也不替代编译器（见 §0.1）。
- **`npm run build`（未通过，环境性）**：`web/node_modules` 不存在 ⇒
  `'vite' is not recognized as an internal or external command`（exit 1）。**未**通过装依赖绕过
  （需联网、未获授权）⇒ SFC 模板编译**未验证**。

### 6.2 档级回归（default 档，证明「未波及既有三棵树」）

- **RunId `s326_20260916_def1`**（`-Suite default`，未加 `-AllowCountDrift`），日志 `.verify/s326_def1`：
  `analytics-server exit=1 Tests run: 949 (F=1 E=0 S=1)`、明细 `93+350+163+93+93+157`、
  `tests=949 MATCH`；mall `13 MATCH`；generator `106 MATCH`；**三棵树 1068 ＝ 基线 1068**、**无 DRIFT**。
- **唯一红 ＝ 已登记环境性红**：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
  （`.verify/s326_def1/default-analytics-server.log:1161-1162` 失败行、`:1199` 断言详情，
  platform-app 汇总 `:1203 Tests run: 157, Failures: 1`），**未修、未用开关掩盖** ⇒
  default 档结论＝「**计数 MATCH ＋ 唯一红＝该已登记环境性红**」，
  脚本 `[FAIL exit=7]` 为**预期**，**不得**写成 `[PASS exit=0]`。
- **该回归的解释力边界**：本轮**未改**任何 Java/Scala（`git status` 对 `analytics-server/**`、
  `spark-jobs/**` 零命中）⇒ `949/13/106` MATCH 只证明「**没有把既有三棵树改坏**」，
  **不**证明前端行为；web 用例不在该门禁内（§0.6）。

## 7. 未测边界与未做（不得越界表述）

1. **`vite build` 未通过**（依赖未安装）⇒ **模板编译未验证**；无浏览器/DOM 走查（未起前端与 8091）。
2. **真实 HTTP 响应含 `source` 未实测**（零起服务、零连库）——沿用 S3-17 既有证明。
3. **业务源身份仍未展示**（`source_id`/`sourceCode`/per-source namespace）⇒ E5-c **只推进一半**，
   `/pipeline` 页**未覆盖**（不挂 `AnalysisContext`）⇒ **不得**称「E5-c 关闭」/「页面已显示数据源」。
4. **`repeat_rate` 消费侧仍只部分**：`Overview.vue` 兜底卡片仍丢 `period`/`definitionVersion`，
   CSV 指标表头仍无周期/口径版本 ⇒ `docs/PROJECT_STATUS.md` L355 行只能**加性批注**，**不得**改判/删行。
5. **阶段5 其它已登记项未做**：净销售额展示（backlog L345）、`quality.ruleVersions` 展示（L346）、
   支付复购率变体（L352）、`/analysis/sales` 的 `from`/`to` 真过滤（L342，**门②邻域**，未触）。
6. **未做**：不改 `package.json`/不装依赖；不改 `scripts/run-tests.ps1` 基线（本轮无计数变化）；
   不重跑 spark 档（未动 Scala，且 spark 档与前端无关）。

## 8. 结论

- 指导书阶段5 **L164** 要求的「**来源**」在**8 个分析页**的上下文条与**导出件元信息**上落地：
  值取自信封 `source`（发布方），取不到统一显示「未知」/「（缺失）」，并逐字标注「**非业务源身份**」。
- 判定 **A 类（实现/加性）**，11 门**逐门否**；定向 RED→GREEN（`fail 7` → `84/84 全绿`）；
  default 档回归 `949/13/106 MATCH、1068＝基线`，唯一红＝已登记环境性红 ⇒ **未把既有三棵树改坏**。
- **本条不得越界**：`vite build` 未验证、无 DOM 走查、真实响应未实测、**E5-c 未关闭**
  （业务源身份与 `/pipeline` 页仍未覆盖）。
