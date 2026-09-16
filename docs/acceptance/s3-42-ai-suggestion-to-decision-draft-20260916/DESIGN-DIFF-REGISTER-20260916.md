# S3-42 设计差异登记 —— AI 建议 → 决策草稿的前端打通（证据锚点 + 字段口径唯一属主）（阶段6）

- 日期：2026-09-16
- 轮次：S3-42（V3.0 持续执行模式，goal `goal-dd741915-9d85-4d36-a1ca-c92f6a534702`）
- 分支/worktree：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 起点 HEAD：`5d1ebe8`（S3-41 收口，HEAD == origin）
- 来源：设计文档 **L714** 的接口清单已把「decisions **创建**/列表及 `/{id}/submit`…」列为既有面，
  权限冻结表 `ControllerPermissionCoverageTest.java:64` 也已冻结 `POST /api/v1/decisions → DECISION_CREATE`，
  但**改前 `web/**` 无任何创建入口**（F1/F2）⇒ 「AI 建议 → 决策草稿」在 UI 侧断链；
  同轮发现 `buildAiEvidenceContext` **丢掉了后端已经返回的顶层 `evidenceId`**（F4/F5）。
- 类别：**A 类（实现/加性）** —— 只**消费**既有响应字段与既有服务端端点，
  **未改任何既有字段类型/业务语义**、**未改 `contract-specs/**`**、**零 Java/Scala/SQL/迁移/连库**、**零 DDL**
- 交付面：`web/**` 7 文件（1 新增生产文件、3 改、3 测试文件其中 2 新增）＋ `docs/**` 4 文件（契约 §5 补遗、本登记、PROJECT_STATUS、历史 F-75）

---

## §1 开工前实测（改前取证，可复现）

命令均在 worktree 根执行（`Set-Location D:\Develop_code\GraduationProject-wt\v3-dev`）。

| # | 实测 | 命令/位置 | 结果（实测） |
| --- | --- | --- | --- |
| F1 | 改前 `web/**` 有没有证据包 ID | `git grep -n evidenceId 5d1ebe8 -- web/src web/tests` | **ZERO HITS** ⇒ 后端已返回的 `evidenceId` **一个字节都没到前端** |
| F2 | 改前前端能调的决策端点 | `git show 5d1ebe8:web/src/api.js`（L111-113） | **只有 3 个**：`decisions`（`GET /decisions`）、`decisionEvaluations`（`GET /decisions/{id}/evaluations`）、`decisionAction`（`POST /decisions/{id}/{action}`）⇒ **没有** `POST /decisions` ⇒ 设计 L714 的「创建」在 UI 侧**不可达** |
| F3 | 权限冻结表怎么说的 | `.../platform-app/src/test/java/.../ControllerPermissionCoverageTest.java` | 表头注释 **L44** 自称「前端 api.js 实际调用的端点」、**L64** 冻结 `POST /api/v1/decisions → DECISION_CREATE`、共 **48** 条；但**测试体从不读 `web/src/api.js`**（L123-140 只把冻结表与**扫描到的控制器端点**对比，L138 的失败消息才提到「前端 api.js 会 404」）⇒ 「前端调用」是**手写镜像**；本轮只把该 1 条在**前端**变真，**其余 47 条是否真被前端调用未对账** |
| F4 | 后端到底给不给证据包 ID | `AiController.java:84-85,202,230-231` | **给**：`record AiQueryResp(QueryResult query, ExplanationResult explanation, String evidenceSummary, String evidenceId)`；`evidenceIdOf(pkg) = pkg == null ? null : pkg.evidenceId()`；证据包构建失败时 **L202 `log.warn` 后置空**、问数**照常返回** |
| F5 | 哪条分支填 `explanation.evidence.evidenceId` | `ExplanationService.java:55-57,114-115,144-145,180` | `record Evidence(..., String evidenceId, String templateVersion)`；**问数分支**（L114-115）与**规则分支**（L144-145）传 `null, null` ⇒ **该字段在问数路径恒为 null**；只有解释分支 L180 才填 `pkg.evidenceId()` ⇒ 前端锚点**只能取顶层 `evidenceId`** |
| F6 | AI 建议长什么样 | `ExplanationService.java:295-310,142` | `actionSuggestions()` ⇒ 每条 `{title(≤60 截断 + '…'), action(整行), targetMetricCode=null}`；**规则分支 `suggestions = List.of()`** ⇒ **建议可能为空**，页面**不得**造建议 |
| F7 | 服务端草稿契约 | `DecisionController.java:51-57`；`DecisionService.java:88,409-426,436-439` | `POST /` + `@RequiresPermission(DECISION_CREATE)` + `createDraft(req, actor, "ai")` ⇒ **`source` 与初始状态由服务端固定**（`ai`/`DRAFT`，契约 §3.3）；`CreateDraftReq(title, action, targetMetricCode, targetDirection, suggestionSnapshotId, risk, owner, evidencePackageId)`；`missingForSubmit` 要求 动作/负责人/`target_metric_code`/`target_direction(UP|DOWN)`/`eval_window_days` ＋ **锚点非空**；`evidenceAnchor` = `evidence_package:<id>` 优先，否则 `suggestion_snapshot:<snap>`，两者皆无 ⇒ `null` ⇒ **提交必被拒** |
| F8 | 契约既有条文 | `docs/contracts/r8-evidence-security-decision.md` L47/L227/L257 | §1 规则 5「证据包**不落库为文本**，只落 `evidenceId` + `templateVersion`（决策任务 `evidence_package_id` 引用）」；§3.3「`source=ai` 只能落 `DRAFT`」；§4 工作面与文件归属 ⇒ 本轮只在**可编辑**契约加 **§5 补遗**，**未**碰 `contract-specs/**` |
| F9 | backlog 是否已登记该断链 | `Select-String 'ControllerPermissionCoverageTest\|决策草稿\|/decisions\|DECISION_CREATE' docs/PROJECT_STATUS.md` | 只命中 S3-18/S3-21/S3-26 的**无关段落** ⇒ **无既有行**覆盖本断链 ⇒ 本轮**新登记**（见 §8） |
| F10 | 禁改面是否被碰（改前） | `git status --porcelain -- contract-specs scripts mall-simulator synthetic-data-generator spark-jobs analytics-server db README.md docs/guidance docs/design` | **为空** |
| F11 | 证据目录是否被忽略 | `git check-ignore -v '.verify/...'` | `.gitignore:65:.verify/` ⇒ 证据不进版本库 |

**实测结论**：这不是「新功能」，而是**已发布能力在前端的最后一米缺失**——
服务端 `POST /api/v1/decisions` 与顶层 `evidenceId` 都已存在并有契约（F4/F7/F8），
但员工在 `/ai` 页面上**没有任何按钮**能把一条 AI 建议变成决策草稿（F1/F2），
而阶段6 完成标准要求「获得有事实引用的 AI 解释，**提交建议并跟踪效果**」（指导书 L30）。

## §2 类别判定（为什么是 A 类，而不是 HARD DECISION）

11 条 HARD DECISION 门逐条对照：

| 门 | 判定 | 依据 |
| --- | --- | --- |
| ① DROP TABLE/COLUMN | 不触发 | 零 DDL、零迁移（F10） |
| ② 改已有字段类型或既有业务语义 | 不触发 | **只新增**前端请求体装配；`CreateDraftReq`/`DecisionService` **一字未改**；`targetDirection` 是**既有**服务端字段的**取值**，不是新语义 |
| ③ 改已发布 Flyway migration | 不触发 | `db/**` 零字节变化 |
| ④ 写/迁移正式 3306 数据 | 不触发 | **零连库**（测试全是 `node --test` 纯逻辑；创建动作发生在浏览器，本轮**未发生**） |
| ⑤ 切 ACTIVE | 不触发 | 未碰发布链 |
| ⑥ 改 `contract-specs/**` 既有契约语义 | 不触发 | 只改**可编辑**的 `docs/contracts/r8-evidence-security-decision.md`（**加性** §5 补遗，v1 正文一字未改） |
| ⑦ 改 V3.0 总体架构 | 不触发 | 前端加一个属主模块 + 一个入口，架构不变 |
| ⑧ 改正式项目范围 | 不触发 | 设计 **L714** 已把 decisions 创建列为既有接口面 ⇒ **收口既有范围**，非扩范围 |
| ⑨ 删除已发布功能 | 不触发 | 纯加性（+535/−1，唯一删除行是 `promptVersion: definitions` 补逗号） |
| ⑩ 引入未规划大型基础组件 | 不触发 | 零新依赖（`decisionDraft.js` 不依赖 vue，仅复用同目录既有判据） |
| ⑪ 两种方案造成重大长期架构分叉 | 不触发 | 字段口径**唯一属主**化，减少分叉而非制造分叉 |

**结论**：**A 类（实现/加性）**，按纪律「登记 → 自主设计 → 实现 → 测试 → commit → 继续」执行。

## §3 本轮冻结的口径（写进契约 §5 补遗 v1.1，逐字见契约）

1. **入口**：只有后端真给了 `suggestions` 才显示入口；**建议为空就不造建议**（F6 规则分支恒空）。
2. **字段只搬运不推断**：页面不得自造字段；`targetMetricCode` 后端给 `null` ⇒ 页面留空并标注「接口未提供」，**不猜指标编码**。
3. **`targetDirection` 必须员工显式选**（`未选择/提升/降低`），页面**不代选**、**不推断**；只有 `UP`/`DOWN` 原值算有效（带空格/其它值一律不视为已选）。
4. **锚点**：首选顶层 `evidenceId`（问数分支 `explanation.evidence.evidenceId` **恒 null**，F5），次选**真实**快照号（占位 `unknown`/空白不算，F5/F6 既有占位语义）；二者皆无 ⇒ **不构造请求**、不造锚点；且**两个锚点不得同时下发**（服务端二选一）。
5. **服务端固定项前端不传**：`source`/`status`/`evalWindowDays` 不由前端下发（F7）；**提交审批的齐全性判定归服务端**，前端**不复刻**该校验。
6. **成功后只显示服务端返回的 `decisionNo`** 并指向**决策中心**（`/decisions`）提交审批；AI 侧**不执行商业动作**、前端**不改状态**。

## §4 实现面（本轮改了什么）

- **新增** `web/src/utils/decisionDraft.js`（**唯一属主**，纯逻辑、不依赖 vue、LF、130 行）：
  `DRAFT_DIRECTIONS`／`DIRECTION_CHOICES`／`ANCHOR_KIND`（`evidence_package`/`suggestion_snapshot`/`none`）／
  `DRAFT_BLOCK`（`SUGGESTION_INCOMPLETE`/`NO_EVIDENCE_ANCHOR`）／`DRAFT_FIELDS`（7 键白名单）／
  `SUBMIT_REQUIREMENT_TEXT`／`draftText`／`isRealEvidenceId`（**委托** `context.js` 的占位判据，先 trim 再判）／
  `normalizeDirection`（严格只认 `UP`/`DOWN`）／`draftSuggestions`／`draftAnchor`／`anchorText`／`buildDraftBody`。
- `web/src/api.js` **+2 行**（121 → 123）：`decisionCreate: (body) => client.post('/decisions', body)`（CRLF、裸 LF=0、**末尾无换行**保持）。
- `web/src/utils/context.js` **+6 行**（229 → 235）：`buildAiEvidenceContext` 归一化并搬运**顶层** `evidenceId`（占位/空白 ⇒ `null`），证据块新增 `evidenceId` 字段。
- `web/src/views/AiAssistant.vue`（263 → **391** 行，CRLF、裸 LF=0）：证据区新增「证据包 ID」meta 项；建议列表新增「转决策草稿」入口（`:disabled="!canCreateDraft"`，无锚点时给出原因）；内联草稿表单（标题/动作预填、目标指标留空标注「接口未提供」、方向下拉**默认未选择**、负责人可留空）＋「将提交」预览 ＋ 成功/失败横幅（成功只显示 `decisionNo` 并 `<router-link to="/decisions">`）；`ask()` 时清掉上一次草稿状态。
- **测试** 3 文件 20 条：**新增** `web/tests/decisionDraft.test.js`（**10** 条，LF：字段集合 ⊆ `DRAFT_FIELDS`、模板分支不下发 `targetMetricCode`、未选方向不下发、锚点二选一、占位回退、无锚点拒绝构造、缺 title/action 拒绝、纯函数不改入参）＋ **新增** `web/tests/aiDecisionDraft.test.js`（**7** 条，LF：`api.decisionCreate` 打集合路径、页面不自拼 `evidencePackageId`/`suggestionSnapshotId`、不复刻服务端校验、无锚点按钮禁用、成功指向决策中心、属主唯一性用 `filesWith` 断言）＋ `web/tests/context.test.js` **追加 3 条**（CRLF：搬运/占位/缺失）。
- **零变化**：生产 Java/Scala **0 行**、SQL/迁移 **0 行**、`scripts/**` **0 行**、`contract-specs/**` **0 行**、连库 **0 次**（`git diff --stat 5d1ebe8 HEAD -- analytics-server spark-jobs scripts mall-simulator synthetic-data-generator db contract-specs` ⇒ **EMPTY**）。

## §5 证据（真跑，非推断）

- **RED（先测后码）**：两个新测试文件先落地、`web/src/utils/decisionDraft.js` 未写 ⇒
  `node --test tests/decisionDraft.test.js tests/aiDecisionDraft.test.js` ⇒
  `ERR_MODULE_NOT_FOUND: …/web/src/utils/decisionDraft.js`、**`tests 2 / pass 0 / fail 2`、`exit=1`**。
- **GREEN**：`cd web; npm test` ⇒ **`tests 168 / pass 168 / fail 0`、`exit=0`**（基线 **148** ⇒ **+20**；
  逐文件实测 `context 25`（22→25，+3）、`decisionDraft 10`、`aiDecisionDraft 7` = `148+20`）。
- **首轮 GREEN 尝试的 3 处红（如实登记，不隐去）**：
  ① 视图注释里出现服务端内部标识 `missingForSubmit` 字面量 ⇒ 守卫②**当场红**（证明守卫确实在读源码文本）；
  ② `isRealEvidenceId(' unknown ')` 被误判为真（**先委托后 trim 的次序错**）⇒ `decisionDraft.test.js:146` 红 ⇒ 修为**先 `draftText` 去空白再委托**；
  ③ 测试自身把 `DRAFT_DIRECTIONS` 列为视图必引入项，而视图实际用 `DIRECTION_CHOICES`（**测试缺陷，非实现缺陷**）⇒ 改测试。
- **变异探针 6 条（打在真实被测物上，探针后按字节还原）**：

| 探针 | 注入的反例 | 结果（实测） |
| --- | --- | --- |
| P1 | `buildDraftBody` 未选方向时**默认下发** `UP` | `fail=2`（「不下发 targetDirection」＋「缺 title/action 拒绝」） |
| P2 | 删掉「无锚点 ⇒ 拒绝构造」分支 | `fail=1`（唯一红＝无锚点用例） |
| P3 | 视图里手写 `{ evidencePackageId: …, suggestionSnapshotId: … }` | `fail=1`（属主唯一性守卫） |
| P4 | `api.js` 去掉 `decisionCreate` | `fail=1`（`api.decisionCreate` 守卫） |
| P5 | `context.js` 不再搬运 `evidenceId` | `fail=3`（3 条 evidenceId 用例全红） |
| P6 | `isRealEvidenceId` 直接 `return t !== null`（占位串当真） | `fail=2`（回退用例 ＋ 占位判据用例） |

  六条探针**全部转红**，且**还原后 `pass=168 / fail=0 / exit=0`**（按字节 `Copy-Item` 还原，非文本重写）。
- **统一门禁**：本轮改动**全部落在 `web/**` ＋ `docs/**`**，非 web 面**零字节变化**（§4 末行实测 `EMPTY`）⇒
  沿用本会话改动前的门禁漂移轮 **`RunId S3-42-default-01`** ⇒ `analytics 960 MATCH`（F=1 E=0 S=1）／
  `mall 13 MATCH`／`generator 106 MATCH`／**三棵树 1079 MATCH**／`[FAIL exit=7]`（唯一红＝已登记环境性红
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61` `expected: 43 but was: 0`），与 `s339-final-1` **逐字相同**；
  `spark 308`／`isolated 55` 两档**未重跑**。
- **文件体检**：`api.js` 122 换行/裸 LF=0/末尾无换行；`context.js` 234/0；`AiAssistant.vue` 390/0；
  `context.test.js` 278/0；`decisionDraft.js` 130 行全 LF；`decisionDraft.test.js` 152 行全 LF；
  `aiDecisionDraft.test.js` 87 行全 LF；全部**无 BOM**。

## §6 契约变更（先改契约再改代码，符合契约文首纪律）

- `docs/contracts/r8-evidence-security-decision.md`（**可编辑**，非 `contract-specs/**`）**加性**新增
  **§5 补遗（v1.1，2026-09-16，S3-42）**：AI 建议 → 决策草稿的**前端口径 6 条**（＝§3）。
- **先契约后代码**：补遗在本轮任何 `web/**` 改动**之前**完成并体检（改后 `23516 bytes / CRLF=305 / 裸 LF=0 / 无 BOM / 306 行`）。
- **v1 正文（§1–§4）一字未改**（补遗自 L270 起，见契约内「本节是**加性补遗**：v1 正文（§1–§4）一字不改」）。

## §7 未测与边界（不得越界表述）

1. **web 套件不在统一门禁内**（`cd web; npm test` ＝ `node --test "tests/**/*.test.js"`）⇒ 门禁计数**未变**（960/1079/308/55）。
2. **运行时渲染未验**：`web/node_modules` **不存在** ⇒ 无 `vite build`／SFC 编译／浏览器 ⇒
   `AiAssistant.vue` 的模板、`computed`（`evidenceAnchor`/`canCreateDraft`/`draftPayload`/`draftPreview`）与 `createDraft()` **从未被执行过**；
   本轮证据＝`npm test`（node 纯逻辑 ＋ **源码文本守卫**）。
3. 守卫是**文本存在性检查、不是渲染断言、也不是污点分析** ⇒ 逻辑搬去别的文件、或用动态键拼装即可绕过；`filesWith` 属主唯一性只覆盖 `web/src` 扫描面。
4. **零连库、零 HTTP** ⇒ **真实 `evidenceId`（`EV-<yyyyMMdd>-<6位>`）从未产生**、决策行**从未落库**、
   `POST /api/v1/decisions` **端到端未测**（禁 `@SpringBootTest`；控制器绑定/序列化/权限拦截在真实请求下的行为**未实测**）。
5. **提交审批齐全性仍归服务端** ⇒ 员工仍可能在**提交时**被服务端以 `PARAM_INVALID` 拒（这是**有意的单一属主**，不是缺陷）；
   前端只**提前**拦下「无可用锚点」这一种**必然失败**的构造。
6. **`targetDirection` 不可能由证据推出** ⇒ 只能员工选；本轮**不代选**、**不给默认值**（契约 §5 第 3 条）。
7. **未做**：草稿创建后**不刷新**决策中心列表、不做「一键转审批」、不做**批量**转草稿；
   解释分支的 `explanation.evidence.evidenceId` 页面**未单独显示**（顶层 ID 缺失时也无从显示）；
   决策**审批/执行/评价**三面仍是既有 `Decisions.vue` 的能力。
8. **不得**把本轮读成「AI → 决策闭环已完成」：本轮只补**创建入口**（草稿 → 提交 → 审批 → 执行 → 评价中，只有第一步在 `/ai` 页面上）；
   亦**不得**表述为「权限冻结表已与前端对账」（F3，其余 47 条仍未对账）。

## §8 顺带台账（**不删行、不改判类**）

- **新增 2 行**（紧接 backlog 末行下方），既有行**一字未改**：
  ① `ControllerPermissionCoverageTest` 的 `FROZEN_FRONTEND_EXPECTATIONS`（**48** 条，表头 L44 自称「前端 api.js 实际调用的端点」）
  **从不读 `web/src/api.js`** ⇒ 该「对账」是**手写镜像**；S3-42 只把 `POST /api/v1/decisions` 这 **1** 条在**前端**变真，
  **其余 47 条是否真被前端调用未对账**（判类：A 类候选＝纯测试新增跨树对账守卫）；
  ② AI 建议 → 决策草稿链路的**残余面**（创建后不刷新列表、无批量转草稿、解释分支 `evidenceId` 未显示）（判类：development backlog／非阻塞）。

## §9 复现命令

```powershell
Set-Location D:\Develop_code\GraduationProject-wt\v3-dev

# 改前取证
git grep -n evidenceId 5d1ebe8 -- web/src web/tests          # ⇒ 无输出（ZERO HITS）
git show 5d1ebe8:web/src/api.js | Select-String decision      # ⇒ 只有 L111-113 三个方法
git diff --stat 5d1ebe8 HEAD -- analytics-server spark-jobs scripts mall-simulator synthetic-data-generator db contract-specs  # ⇒ 空

# RED → GREEN
Set-Location web
node --test tests/decisionDraft.test.js tests/aiDecisionDraft.test.js   # 实现未写时 ⇒ fail 2、exit=1
npm test                                                                # ⇒ tests 168 / pass 168 / fail 0 / exit=0

# 逐文件用例数
foreach ($t in 'context','decisionDraft','aiDecisionDraft') { node --test "tests/$t.test.js" 2>&1 | Select-String '^ℹ (pass|fail)' }

# 文件体检（绝对路径；Set-Location 不改变 .NET CWD）
$b = [IO.File]::ReadAllBytes('D:\Develop_code\GraduationProject-wt\v3-dev\web\src\views\AiAssistant.vue')
```
