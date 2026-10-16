# S3-34 设计差异登记 —— `/pipeline` 页上下文条与「结果所属数据源」列（E5-c 可 A 类实现部分）

- 日期：2026-09-16
- 分支：`feature/v3-development`（工作树 `D:\Develop_code\GraduationProject-wt\v3-dev`；**不并入 main**）
- 起点提交：`3e94e74`（`docs(status): S3-33 收口 告警文案展示链与信封码文案（实测改写行前提）`，HEAD ＝ `origin/feature/v3-development` ＝ `3e94e74c15825b907554c4aa3004b93f4d6233e2`）
- 权威：`docs/guidance/项目完整实施指导书 V3.0.md` §7 阶段5 **L164**；`docs/design/项目设计文档 V3.0.md` §15 **L687**；`docs/contracts/analysis-viewmodel-r7-4.md`（展示层契约，L3-4「若实现中确需改动，先改本文件再改代码」）
- 类别：**A 类（实现／加性）**，11 门逐门否（§6）

## §0 证据边界（先说清楚哪些是实测、哪些不是）

本轮全部结论均来自**本轮真跑**的命令输出，日志只落在 `$env:TEMP`（仓库内零证据文件）。**未测**的边界集中在 §7，任何越界表述都不得出现在报告/论文里。

- 可复现命令（`pwsh`，工作目录 `web/`）：`npm test`（＝ `node --test "tests/**/*.test.js"`，纯 `node:test`，**不依赖 `web/node_modules`**）。
- **不可复现/未跑**：`npm install`／`vite build`（`web/node_modules` 不存在，装依赖需联网，未授权）、真浏览器/DOM 走查、任何 Java/Scala/SQL 改动（本轮零改动）、统一门禁四档（本轮 `analytics-server/**`＋`spark-jobs/**`＋`scripts/**` 零改动，web 套件**不在**统一门禁覆盖内 —— 既有事实）。

## §1 本轮行前提与实测改写

backlog 行（`docs/PROJECT_STATUS.md` **L369**，E5-c）：

> `/overview`、`/pipeline`、`/ops` 三页对 `spark-ads`/`数据源`/`sourceCode`/`source_id` **命中 0**（`docs/acceptance/e5-preaccept-20260912/README.md:37`）

行措辞经本轮实测**部分陈旧、剩余部分可拆**：

| 行内子项 | 本轮实测 | 处置 |
| --- | --- | --- |
| `/ops` 看不到结果所属数据源 | `pipeline_run.source_data_version`（V2 建列）**有写入**（`PipelineService.java:169`），但 `/ops` 流水线实例表 9 列**无**该列（历史测量 `e5-preaccept-20260912/README.md:126`） | **A 类 ⇒ 本轮做**（加性列） |
| `/pipeline` 看不到数据源/上下文 | `Pipeline.vue`（97 行）**改前零** `AnalysisContext`／`buildFallbackContext`／`pipelineRunRows` 命中；其余 8 个分析页**都挂**上下文条 | **A 类 ⇒ 本轮做** |
| `/overview` 命中 0 | `Overview.vue:18` **已挂** `AnalysisContext`，S3-26 起上下文条已展示「来源（发布方）」 | **已被 S3-26 覆盖**，仅剩「业务源身份」缺口（下行） |
| 业务源身份（`source_id`／`sourceCode`／per-source namespace） | 指导书/契约**无**该字段的展示契约；需要新契约字段 ⇒ 属**新语义** | **B 类 ⇒ 不实现、只登记**（S3-26 已裁决 `metric_snapshot.source` **不是**源身份，见 L359） |

⇒ 本轮**只做**「A 类可落地的那一刀」，**不关闭 E5-c 行**。

## §2 开工前实测（F1–F9，全部本轮真跑）

- **F1（页面缺口）**：`web/src/views/Pipeline.vue` 97 行，`AnalysisContext`／`buildFallbackContext`／`pipelineRunRows` **命中 0**；实例表列为 `ID/流水线/业务时间/状态/尝试/操作`（6 列）。
- **F2（同一实体的单一映射所有者已存在）**：`web/src/utils/tables.js` 的 `pipelineRunRows`（L78-90）**已**映射 `id/pipelineCode/businessTime/targetSnapshotId/attemptNo/status/currentStage/errorCode/createdAt`，其 KDoc 已写「溯源链路：业务时间 / 目标快照 / 当前阶段」—— 但 `/pipeline` 页**没调用它**，而是自渲染裸行字段 ⇒ 同一实体**两条渲染路径**。
- **F3（后端确凿有这两列且有写入）**：`analytics-server/platform-app/src/main/resources/db/meta/V2__platform_pipeline_quality.sql:9` `source_data_version VARCHAR(64) NULL`（`CREATE TABLE pipeline_run`）；`V7__platform_runtime_profile.sql:65` `ADD COLUMN target_snapshot_id VARCHAR(64) NULL`；写入点 `PipelineService.java:169`（`run.setSourceDataVersion(sourceDataVersion)`）与 `:420`（`run.setTargetSnapshotId(snapshotId)`）；列表接口 `PipelineController` 返回 `ApiResponse<List<PipelineRun>>`（实体整行序列化，`limit` 默认 20）。
- **F4（新发现：批次级溯源断链）**：`pipeline_run.input_batch_id`（V7 `:64`）在全仓（`*.java/*.scala/*.sql/*.xml/*.py/*.ps1/*.js`，排除 `target/`、`node_modules/`、`.verify/`）**仅 7 处命中**：V7 DDL 2 行、`PipelineRun.java:34` 字段声明、两份历史 schema 快照（`p1-05-8091-swap-20260911/pre-v17-schema.sql:366`、`schema-snapshot-20260912-091451.sql:372`）、两份验收脚本的 `SELECT`（`t2rerun-golden55-post-ct-20260912/tools/control-verdict.ps1:333`、`v25-e3-isolated-chain-20260914/scripts/21-dump-isolated-evidence.ps1:32`）。生产代码 **0 处** `setInputBatchId` ⇒ **该列恒为 NULL**，「本 run 吃的是哪一批」在库内**不可查**。
- **F5（F4 的补救可行性）**：批次号在 `WAIT_LANDING` **已可得** —— `PipelineService.java:428` `manifestForRun(landingRoot, run.getId(), runSourceId)`；`LandingManifestSelector.java:24-26/54-58` 负责「重试钉住原批次、否则取本源 READY 非空 batchId 最大者」；`PipelineService.java:323` 注释明说「正则读 `batchId`」。⇒ 写入 `inputBatchId` 技术上可行（**A 类候选**），但**本轮不实现**，只登记为 backlog（§8）。
- **F6（不扩键的理由）**：`web/src/utils/context.js` 的 `collectSnapshotIds` **只认** `snapshotId|snapshot_id|suggestionSnapshotId`，**不认** `targetSnapshotId`。若本轮顺手扩键，会**静默改变既有 8 个调用方**的快照号口径 ⇒ 改为**页面显式传** `snapshotIds`（加性、影响面可控），`collectSnapshotIds` **一字未改**。
- **F7（守卫必须限定作用域）**：`Pipeline.vue:105` 的**触发请求体**里本来就有 `businessTime: businessDate.value + 'T00:00:00'`（创建入参）。因此「不得把行级 `businessTime` 当响应级口径」这条守卫**必须限定在上下文调用块内**，否则误伤（探针 C 系列实测，见 §5）。
- **F8（不引新样式）**：`.mono` 已在 `web/src/theme.css:126` 定义（`td b, td .mono { font-family: var(--font-mono); … }`）⇒ 新列直接复用，零新增 CSS。
- **F9（取数形状）**：`web/src/api.js` 客户端拦截器在 `body.code === 'OK'` 时 **`return body.data`** ⇒ `api.pipelineRuns(10)` 得到 `List<PipelineRun>` 数组。`Ops.vue` 对同一实体另有 `pipelineRuns.items || pipelineRuns` 的防御分支 ⇒ 本页加**形状守卫**（非数组退化为空表，不抛错），与既有处理一致。

## §3 本轮冻结的口径声明

1. **上下文条描述的是「整页响应口径」，不是某一条实例**。`/pipeline-runs` 是**裸数组**接口（无统一信封）⇒ 页面**显式登记** `warnings: ['ENVELOPE_MISSING']`，由既有 `warningTextAll` 渲染，**不**在页面重算。
2. **快照号只取实例的 `targetSnapshotId`**，一次性传给 `buildFallbackContext` 的 `snapshotIds`。多条实例的快照号**不合并** —— 沿用该函数既有规则「该响应含多个快照号（…），未合并为单一快照」如实标注。
3. **响应级业务时间／数据更新时间／口径版本／质量状态，裸数组接口不提供** ⇒ 交给既有缺失清单（前缀「接口未提供（已如实标注，不代替后端编造）：」）如实标注。页面**不得**从某一行挑一个值冒充整页口径（守卫 §5 覆盖）。
4. **来源（发布方）**沿用 S3-26 口径：后端未给 ⇒ 展示层「未知」，且**不**进入缺失清单。本页**不**新增「来源」文案，也不把「来源（发布方）」写成「数据源」。
5. **结果所属数据源** 的可展示事实只有 `pipeline_run.source_data_version`（V2 列、逐次运行写入，例如 `manual-<ms>`），列名用「**源数据版本**」；**不得**表述为「业务源身份」「数据源实例」。
6. **`input_batch_id` 不展示**：该列恒 NULL（F4），展示它等于展示空列并暗示链路可用 ⇒ 只登记 backlog。
7. **触发动作的失败 ≠ 数据加载失败**：`state/error` **只**由实例列表取数（`loadRuns`）驱动；`runOnce` 的失败仍走页面原有的 `runResult` 提示，不冒充上下文条错误。

## §4 实现面（4 文件，全部 `web/**`；+126/−6）

| 文件 | 改动 | 性质 |
| --- | --- | --- |
| `web/src/utils/tables.js` | `pipelineRunRows` **加性**一行 `sourceDataVersion: text(r.sourceDataVersion)`；`COLUMNS.opsPipelineRuns` **加性**一列 `{ key: 'sourceDataVersion', label: '源数据版本' }`（置于「业务时间」与「目标快照」之间） | 加性 |
| `web/src/views/Pipeline.vue` | ① 挂 `<AnalysisContext :context="context" :state="state" :error="error" />`（**第 9 个**调用方）＋ 一行提示「上下文条＝整页口径，实例级溯源见下表」；② 实例表改用 `runRows`（`pipelineRunRows` 单一所有者）并**新增**「源数据版本」「目标快照」两列；③ `state`/`error` 两个 `ref`（`loadRuns` 内 `loading→ready/error`）；④ `runList` 形状守卫、`runRows`、`context` 三个 `computed` | 加性 ＋ **退休页内自渲染路径** |
| `web/tests/tables.test.js` | +2 用例（`pipelineRunRows` 搬运/缺失占位；`COLUMNS.opsPipelineRuns` 含该列） | 新测试 |
| `web/tests/pipelinePage.test.js` | **新文件**（49 行，3 用例）：`Pipeline.vue` 源码文本守卫 | 新测试 |

**未改**：`api.js`、`envelope.js`、`context.js`、`quality.js`、`chartState.js`、`chartOptions.js`、`exportCsv.js`、`number.js`、`components/**`、其余 8 个视图、`web/package.json`（**零新依赖**）、任何 Java/Scala/SQL/迁移/脚本、`contract-specs/**`、`docs/contracts/**`。

## §5 证据矩阵（真跑）

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| RED（先写用例，`web/tests/tables.test.js` ＋ `pipelinePage.test.js`） | `npm test` | `ℹ tests 107 / pass 102 / fail 5`、`exit=1`（**5 红全在新增用例，既有 102 条无一变红**） |
| GREEN | `npm test` | `ℹ tests 107 / pass 107 / fail 0`、`exit=0` |
| 探针 A：删 `tables.js` 的 `sourceDataVersion: text(r.sourceDataVersion)` 一行 | `npm test` | **唯一红** ＝ `pipelineRunRows 映射「源数据版本」…` ⇒ 搬运有牙 |
| 探针 B：拆掉 `Pipeline.vue` 的 `<AnalysisContext … />` 挂载行 | `npm test` | **唯一红** ＝ `Pipeline.vue 挂载 AnalysisContext…` ⇒ 挂载有牙 |
| **探针 C（反证）**：删 `warnings: ['ENVELOPE_MISSING'],` 代码行 | `npm test` | **`exit=0`、107/107 ⇒ 守卫无牙（本轮发现并修正）**：原断言 `assert.match(text, /ENVELOPE_MISSING/)` 被**注释**里的同名 token 满足 |
| 守卫收紧（改为在 `buildFallbackContext` **调用块内**断言 `warnings:\s*\['ENVELOPE_MISSING'\]`） | `npm test` | `107/106/1`、`exit=1`（收紧后先红）→ 实现面不变、复跑 `107/107 exit=0` |
| **探针 C2**：同「删 `warnings: ['ENVELOPE_MISSING']` 代码行」 | `npm test` | **唯一红** ＝ 挂载/如实标注用例 ⇒ 收紧后有牙 |
| 复原校验 | `Get-FileHash` vs `$env:TEMP` 备份 | 探针 A/B/C2 后**逐一相同**（`Pipeline.vue` 复原一致 `True`；`tables.js` 复原一致 `True`） |
| 改动面 | `git diff --numstat` ＋ `git status --porcelain` | 4 文件（3 改 1 新），`web/**` 之外 **0** 文件 |

终态文件指纹（SHA256 前 16 位）：`tables.js DE2A657513FC6D7B`、`Pipeline.vue F02D1805506B157D`、`tables.test.js BF6823243AE59CF6`、`pipelinePage.test.js 67C9C74EB5635061`。

## §6 类别判定与 11 门逐门核对

| 门 | 是否触碰 | 依据 |
| --- | --- | --- |
| ①DROP TABLE/COLUMN | 否 | 零 DDL 改动 |
| ②改已有字段类型/既有业务语义 | 否 | 只**加列显示**；`source_data_version` 语义沿用写入侧，未重定义 |
| ③改已发布 Flyway migration | 否 | `V2`/`V7` 只被**读**（F3），零改动 |
| ④写/迁移正式 3306 数据 | 否 | 零连库 |
| ⑤切 ACTIVE | 否 | 未触碰运行时剖面/发布开关 |
| ⑥改 `contract-specs/**` 既有契约语义 | 否 | 未触碰；`docs/contracts/**` 亦未改（新增的是**展示列**，不需要新契约字段） |
| ⑦改 V3.0 总体架构 | 否 | 前端展示层 |
| ⑧改正式项目范围 | 否 | 落在指导书 §7 阶段5 L164「显示…来源、快照和限制」既有范围 |
| ⑨删除已发布功能 | 否 | **零删除**；页内自渲染裸行路径被**同一页**改用既有映射所有者（行为等价且更一致） |
| ⑩引入 V3.0 未规划大型基础组件 | 否 | 零新依赖、零新组件 |
| ⑪两种方案造成重大长期架构分叉 | 否 | 沿用既有唯一 owner，无二选一分支 |

## §7 未测与边界（不得越界表述）

1. **`vite build` 未跑**（`web/node_modules` 不存在，`'vite' is not recognized`）⇒ `Pipeline.vue` 的**模板改动未由 SFC 编译器解析**，本轮证据只有 `node --test` 单测。
2. **真浏览器/DOM/像素级展示未验收** ⇒ 只能表述为「**源码接线经静态守卫约束**」，**不得**表述为「`/pipeline` 页已验证显示数据源」；`web/tests/pipelinePage.test.js` 首行已把「源码文本守卫不能替代渲染验证」写死在文件里。
3. **HTTP 真实响应形状未测** ⇒ `sourceDataVersion` 在真实 `manual-<ms>` 之外的取值分布（是否可能为 NULL）**未测**；页面按占位符 `—` 处理 NULL（与既有 `text()` 口径一致），但这不等于「线上不会出现 NULL」。
4. **`/ops` 新增列的真实表格渲染未验收**（同上：无浏览器）⇒ 历史测量列清单 `e5-preaccept-20260912/README.md:126` 是**当时的** 9 列事实，本轮按**追加记账**新增第 10 列，**不追改**该历史文件。
5. **`input_batch_id` 仍恒 NULL**（F4）⇒ 不得声称「本 run 与来源批次可互相追溯」。
6. **业务源身份（`source_id`/`sourceCode`）仍未展示** ⇒ 不得声称「E5-c 已关闭」。
7. **统一门禁本轮未跑**（零 Java/Scala/脚本改动；web 套件不在门禁覆盖内）⇒ 107 这个数字**不得**表述为「统一门禁通过」。
8. 指导书 §7 阶段4 余项（限流、HTTP 请求级统一超时、异步任务反馈、写操作幂等头、其余列表端点分页排序）与阶段5 其他项**本轮均未做**。

## §8 遗留与待裁决

- **R-1（本轮新登记，A 类候选）**：`pipeline_run.input_batch_id` **零写入**（F4）——「本 run 消费了哪一批」在库内不可查；补救点在 `WAIT_LANDING`（`PipelineService.java:428` 已取到 manifest/batchId，F5）。是否在阶段链内补写该列、以及是否需要迁移历史行，**未做**，已写入 `docs/PROJECT_STATUS.md` backlog。
- **R-2（B 类，维持登记）**：**业务源身份**（`per-source namespace`、`sourceCode`、`source_id`）进入页面 ⇒ 需要新契约字段与展示口径定义（S3-26 已裁决 `metric_snapshot.source` **不是**源身份）⇒ 属门⑥/⑧邻域，**不自行实现**。
- **R-3（沿用 S3-33 R-1）**：`ENVELOPE_WARNING_CODES` 是前端镜像清单，后端新增码不会自动发现。

## §9 反熵声明（本轮）

- **零删除、零退休、零新增 owner**：`buildFallbackContext` 仍是**唯一**非信封上下文属主（`/pipeline` 是第 9 个调用方，**未**复制其规则）；`pipelineRunRows` 仍是该实体的**唯一**映射属主，本轮的净效果是**收敛**（页面自渲染裸行字段的第二条路径被改用既有属主）。
- **未新增 fallback 分支**：`WARNING_TEXT`／`WARNING_TEXT_EXTRA`／`collectSnapshotIds`／`sourceText` 一字未改；页面新增的 `Array.isArray` 是**形状守卫**（不产生第二语义来源）。
- **未新增持久化/真源边界**：零 DDL、零连库、零写入；新增显示列来自**既有**列（F3），不符合「先问该缺口是否属于新属主」以外的任何新属主条件。
