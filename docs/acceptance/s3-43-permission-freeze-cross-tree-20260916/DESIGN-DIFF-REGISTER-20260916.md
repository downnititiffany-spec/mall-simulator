# S3-43 设计差异登记 —— 权限冻结表 ↔ 前端调用面「跨树对账」守卫（阶段6 反熵，纯测试新增）

- 日期：2026-09-16
- 轮次：S3-43（V3.0 持续执行模式，goal `goal-dd741915-9d85-4d36-a1ca-c92f6a534702`）
- 分支/worktree：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 起点 HEAD：`c13615d`（S3-42 收口，HEAD == origin）
- 来源：S3-42 台账 backlog 行「**权限冻结表与前端实际调用面仍未对账**」（`docs/PROJECT_STATUS.md` 第 11 节 backlog，S3-42 那行）。
  S3-42 只把冻结表里的 `POST /api/v1/decisions` **1** 条在前端变真，并如实写下「其余 47 条未验证」；
  本轮把这张**手写镜像**变成**可失败的对账**：两侧源码文本求差、差集逐条登记。
- 类别：**A 类（实现/加性）** —— 新增 **1** 个 web 守卫测试文件 ＋ 把 Java 冻结表表头那句**与事实不符**的注释
  改成真话并指向守卫（**注释级**，不改测试体、不改断言语义）；**零**产品 Java/Scala/SQL/迁移、
  **零** DDL、**零**连库、**零** `contract-specs/**`、**零**契约变更（§6）
- 交付面：`web/tests/permissionReconcile.test.js`（新增，236 行 / 7 条用例）＋
  `analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/ControllerPermissionCoverageTest.java`（+8/−1 行，**仅注释**）
  ＋ `docs/**`（本登记、`PROJECT_STATUS`、历史 F-76）

---

## §1 开工前实测（改前取证，可复现）

命令均在 worktree 根执行（`Set-Location D:\Develop_code\GraduationProject-wt\v3-dev`）。

| 编号 | 实测（改前） | 取证 |
|---|---|---|
| F1 | Java 冻结表 **48** 条、豁免表 **5** 条；表头 **L44** 改前原文＝`/** 前端 api.js 实际调用的端点 → 期望权限码（路径占位符统一写成 {id}） */` | `git show c13615d:analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/ControllerPermissionCoverageTest.java` |
| F2 | 该测试**只**把冻结表与**扫描到的控制器端点**对比（`frozenFrontendExpectations()`，L123-140），**从不读** `web/src/api.js`；L138 的失败消息才提到「前端 api.js 会 404」 | 同上 |
| F3 | 前端**只有** `web/src/api.js` 发请求：`web/src` 全树除 api.js 外 `client.(get\|post\|put\|delete)(` **零命中**，`from 'axios'` 只出现在 api.js ⇒ 静态调用点 **29** 个 | 本守卫「前提」用例 ＋ `git grep -n 'client\.' -- web/src` |
| F4 | 其中 **2** 条末段是动态参数：`client.post(\`/decisions/${id}/${action}\`)`（api.js L113）、`client.post(\`/admin/users/${id}/${action}\`)`（L119）；取值字面量在页面里：`Decisions.vue` **7** 个（L63 submit／L65 reject／L66 start／L67 complete／L185 approve／L201 cancel／L214 evaluate）、`Ops.vue` **2** 个（L452 toggle／L469 reset-password） | `git grep -n "act(d, '\|decisionAction(\|adminUserAction(" -- web/src` |
| F5 | 展开后前端调用面 **36** 条；**前端调用面 ⊆ 冻结表 ∪ 豁免表，差集 0 条**（没有任何「前端调了却没权限期望」的端点） | 本守卫判据 A（改前即已通过，见 §5） |
| F6 | **冻结表 − 前端调用面 ＝ 15 条**（改前这些行只被 Java 侧当作「端点存在＋权限码正确」的期望，**不是**前端调用证据）：`POST /ai/analyses`、`POST /ai/explanations`、`GET /ingestion/batches`、`GET /admin/pipeline-runs/recovery-report`、`POST /admin/pipeline-runs/{id}/resume`、`/{id}/mark-failed`、`/{id}/retry-from-stage`、`GET /runtime-profiles`、`GET /runtime-profiles/active`、`GET /runtime-profiles/{id}`、`POST /runtime-profiles`、`PUT /runtime-profiles/{id}`、`POST /runtime-profiles/{id}/test`、`/{id}/activate`、`/{id}/disable` | 本守卫判据 B 的 RED 输出（§5 逐字） |
| F7 | 36 ＝ **33**（命中冻结表）＋ **3**（命中豁免表：`POST /auth/login`、`POST /auth/logout`、`GET /auth/me`）；豁免表另 2 条（`GET /metrics/health`、`GET /health`）前端无调用 | 本守卫判据 A/D 与 §5 计数 |
| F8 | 冻结表 ∩ 豁免表 ＝ **0**；两表各自无重复键 | 本守卫判据 D |
| F9 | 5 条 backlog 行/契约**都没有**「冻结表＝前端调用面」的表述 ⇒ 本轮**无契约变更**面 | `git grep -n 'FROZEN_FRONTEND_EXPECTATIONS\|权限冻结表\|冻结调用' -- docs/contracts contract-specs` ⇒ **零命中** |
| F10 | 改前 web 套件基线 **168 pass / 0 fail / exit=0**（S3-42 收口轮实测） | `.verify/` 与 S3-42 登记 §5 |

---

## §2 类别判定（为什么是 A 类，而不是 HARD DECISION）

| # | 硬门禁 | 本轮是否触碰 | 依据 |
|---|---|---|---|
| ① | DROP TABLE/COLUMN | **否** | 全程无 DDL |
| ② | 改已有字段类型/既有业务语义 | **否** | 产品代码**零改动**；Java 仅**注释**；新增文件只读源码 |
| ③ | 改已发布 Flyway migration | **否** | 未触碰 `V*.sql` |
| ④ | 写/迁移正式 3306 数据 | **否** | 零连库（守卫只读文件） |
| ⑤ | 切 ACTIVE | **否** | 未触碰运行时配置面（`activate` 行只登记「前端未接线」，不调用） |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **否** | F9：零命中，本轮无契约变更 |
| ⑦ | 改 V3.0 总体架构 | **否** | 只在既有 web 测试套件内加一条跨树对账守卫 |
| ⑧ | 改正式项目范围 | **否** | **不接线**任何新页面/端点（15 条未接线行保持原状，见 §3⑥） |
| ⑨ | 删除已发布功能 | **否** | 未删任何测试/断言/端点；Java 只改注释 |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **否** | 零新依赖（node 内置 `node:test`/`fs`/`path`） |
| ⑪ | 两种方案造成重大长期架构分叉 | **否** | 归属面清晰：Java 侧管「冻结表 ↔ 控制器端点」，web 侧管「前端调用面 ↔ 冻结表」 |

判类：**A 类（实现/加性）** ⇒ 登记 → 自主设计 → 实现 → 测试 → commit → 继续。

---

## §3 本轮冻结的口径（守卫判据，逐条落在断言上）

① **谁扫描谁**：web 侧扫描**两端源码文本** —— `analytics-server/.../ControllerPermissionCoverageTest.java`（`EXEMPT` ＋ `FROZEN_FRONTEND_EXPECTATIONS`）
与 `web/src/api.js`；Java 侧**测试体不动**（它继续负责「冻结表 ↔ 扫描到的控制器端点」＋权限码矩阵校验）。
不选「Java 侧读 `web/src/api.js`」的理由：那会把 Java 用例计数从 **960** 改掉（改验收基线），而**本轮要查的断言是关于前端的**。
② **失败怎么报**：一律**集合相等/包含**（`deepEqual`/`filter` 差集），差集原文进失败消息；不允许「打印一下就过」。
③ **动态段口径**：末段是 `{action}` 的调用必须登记进 `DYNAMIC_CALL_RULES`，且**展开集合 ↔ 冻结表同前缀行集合相等**、
每个取值须在对应页面源码里以带引号字面量出现；普通路径参数 `{id}`（如 `GET /pipeline-runs/{id}`）**直接按字面量对账**，不需要规则
（本轮第一版把「末段带占位符」一律要求规则 ⇒ 误报 `GET /api/v1/pipeline-runs/{id}`，如实登记并修正，见 §5）。
④ **例外必须带原因**：`NOT_WIRED_IN_FRONTEND` 每条原因 ≥10 字符，且与「冻结表 − 前端调用面」**集合相等** ⇒ 静默新增/删除都会红。
⑤ **表头必须说真话**：Java 冻结表表头改为「端点 → 期望权限码冻结表」，显式写明**不等于**「前端 api.js 实际调用面」，
并指向本守卫；守卫有一条断言专门钉这句话（不含「前端 api.js 实际调用的端点」四字短语 ＋ 必须含 `web/tests/permissionReconcile.test.js`）。
⑥ **不得用猜测补白**：15 条未接线行**不接线**（接线属项目范围）、**不删除**（删＝改 Java 侧期望面），只在守卫里逐条登记原因。

---

## §4 实现面（本轮改了什么）

| 文件 | 变更 | 说明 |
|---|---|---|
| `web/tests/permissionReconcile.test.js` | **新增** 236 行 / **7** 条用例 | 前提（只有 api.js 发请求）＋ 判据 A（前端 ⊆ 冻结 ∪ 豁免）＋ C0/C1（动态调用登记与集合相等）＋ B（冻结表逐条对账，集合相等）＋ D（无重叠/无重复）＋ 表头真话 |
| `.../controller/ControllerPermissionCoverageTest.java` | **+8 / −1 行，仅注释** | 表头改真话 ＋ 指向守卫；**测试体、断言、冻结表数据一字未改** |

守卫导出的纯函数（便于将来复用）：`normalizeCallPath(raw)`、`callSites(source)`。

---

## §5 证据（真跑，非推断）

**RED（先测后码，判据 B 先空登记）**：`cd web; node --test tests/permissionReconcile.test.js`
⇒ `ℹ tests 7 / pass 6 / fail 1`，唯一红项＝判据 B，差集**逐字**给出 **15** 行（F6 清单）。
⇒ **这条红就是「47 条未对账」的收敛结果：15 条前端根本没调用、33 条真被调用**。

**首轮自曝的 2 处问题（如实登记，非环境问题）**：
① 判据 C0 第一版把「末段任何占位符」都要求登记动态规则 ⇒ 误报 `GET /api/v1/pipeline-runs/{id}`（它是**普通路径参数**，冻结表里就是这么写的）
⇒ 修为「先按字面量直接对账，命中冻结/豁免即通过」；
② `reconcile()` 里引用了模块级 `known` 而 `known` 在其后初始化 ⇒ `ReferenceError: Cannot access 'known' before initialization`
（`node --test` 报 `tests 1 / fail 1`，整文件收集期失败）⇒ 修为在函数内构造 `known`。

**GREEN**：`cd web; npm test` ⇒ **`exit=0`、`tests 175 / pass 175 / fail 0`**（基线 **168 → 175**，+7）。

**变异探针 5 条（打在**真实被测物**上，探针后按**字节**还原）**：

| 探针 | 打在 | 预期 | 实测 |
|---|---|---|---|
| P1 | Java 冻结表新增 `GET /api/v1/never-wired-probe` | 判据 B 红 | `fail=1`，红项＝判据 B |
| P2 | `api.js` 新增 `client.get('/never-declared-probe')` | 判据 A 红 | `fail=1`，红项＝判据 A |
| P3 | `Decisions.vue` 把 `act(d, 'reject')` 改成 `act(d, d.rejectAction)` | 判据 C1 红 | `fail=1`，红项＝判据 C1 |
| P4 | Java 豁免表加入 `GET /api/v1/dashboards/overview`（与冻结表重叠） | 判据 D 红 | `fail=1`，红项＝判据 D |
| P5 | Java 表头删掉指向守卫的那句 | 表头真话用例红 | `fail=1`，红项＝表头用例 |

⇒ **5/5 全部按预期红**；探针后 `git status --porcelain` 只剩本轮预期的两处改动，基线复跑 **7/7 pass**。

**统一门禁（本轮 Java 树有改动 ⇒ 必须重跑，不沿用）**：`.\scripts\run-tests.ps1 -Suite default -RunId s343-gate-1 -LogDir .verify\s343-gate-1 -Confirm`
⇒ `analytics-server 960 MATCH`（明细 **93+350+169+97+94+157**）／`mall-simulator 13 MATCH`／`synthetic-data-generator 106 MATCH`／**三棵树 1079 MATCH**；
其中 `ControllerPermissionCoverageTest` **Tests run: 5, Failures: 0, Errors: 0**（注释改动未影响测试体）；
`[FAIL exit=7]` 的唯一红＝**已登记环境性红** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`（`expected: 43 but was: 0`）。
`spark`／`isolated` 两档本轮未重跑（未触碰其面）。

---

## §6 契约变更（先改契约再改代码）

**无**。本轮不改任何契约文件：F9 实测 `docs/contracts/**` 与 `contract-specs/**` 对
`FROZEN_FRONTEND_EXPECTATIONS`／「权限冻结表」／「冻结调用」**零命中**；R8 契约 §3.2 只规定
`permissionCode` 常量＋角色矩阵＋`@RequiresPermission` 的**服务端**实现口径，从未把冻结表声明为前端调用面
⇒ 冲突在**注释**里，不在契约里，故**先改注释再改代码**（同一纪律）。

---

## §7 未测与边界（不得越界表述）

① 守卫读的是**源码文本**，**不做求值**、**不做污点分析**：路径若由变量拼出（非模板字面量里的 `${...}`）就扫不到
—— 这一面由「前提」用例钉住（`web/src` 除 api.js 外零 `client.*(` 、axios 只在 api.js 引入），**不是**证明「不可能有运行时拼路径」；
② `DYNAMIC_CALL_RULES` 的动作取值是**存在性检查**（该字面量出现在页面源码里），**不是**「运行时一定会传它」；
③ 本守卫**不证明权限码正确**（那是 Java 侧 `frozenFrontendExpectations()` 与 `permissionCodesComeFromMatrix()` 的职责），
只证明「前端调用面 ↔ 冻结表」的集合关系与登记一致；
④ 本文件在 **web 套件**（`cd web && npm test`），**不在**统一门禁 `run-tests.ps1` 内 ⇒ **门禁计数不变**（960/1079/308/55，`spark`/`isolated` 未重跑）；
⑤ 15 条未接线行是**实测的设计面事实**（前端确实没有这些页面/入口），**不是**权限缺陷；是否接线属项目**范围**问题（⑧），本轮**不接线**；
⑥ `EXEMPT` 的语义是「**无需权限码**」而非「前端调用面」，故判据 B 不覆盖它；豁免表 5 条中 2 条（`/metrics/health`、`/health`）前端无调用，属正常；
⑦ **零连库零 HTTP**：本轮没有任何网络/数据库/浏览器证据，**不得**称「页面已按新表头行为」「权限已端到端验证」；
⑧ **不得**把本轮读成「15 条从未接线的前端点已补齐」或「权限冻结表 = 前端调用面」——正确表述是
「**48 条冻结行已与 `web/src/api.js` 逐条对账：33 条前端真调用、15 条前端未接线并逐条登记原因**」。

---

## §8 顺带台账（**不删行、不改判类**）

- `docs/PROJECT_STATUS.md` 第 11 节 backlog：S3-42 那行「权限冻结表与前端实际调用面仍未对账」**在原行末单元格内**追加
  「（S3-43 已对账：48 ＝ 33 真调用 ＋ 15 未接线并逐条登记；本行判类不变，2026-09-16）」；
  **新增 1 行**登记本轮发现的新面：「**15 条冻结行对应的运维/管理/AI 分析端点前端未接线**」
  （`/runtime-profiles` ×8、`/admin/pipeline-runs` ×4、`/ingestion/batches` ×1、`/ai/analyses`＋`/ai/explanations` ×2；
  判类＝**development backlog 非阻塞**：是否接线属项目范围，**不得**由测试轮擅自扩张）。
- 未修/未动：Java 侧冻结表**数据一字未改**（48 条保留原样）、`EXEMPT` 5 条未动、`web/src/**` 零改动、
  `contract-specs/**` 未触碰。

---

## §9 复现命令

```powershell
Set-Location 'D:\Develop_code\GraduationProject-wt\v3-dev'
# 改前取证（起点 c13615d）
git show c13615d:analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/ControllerPermissionCoverageTest.java | Select-String 'FROZEN_FRONTEND_EXPECTATIONS\.put' | Measure-Object   # 48
git grep -n 'client\.\(get\|post\|put\|delete\)' -- web/src
# 守卫（RED → GREEN）
cd web
node --test tests/permissionReconcile.test.js
npm test                       # 175 pass / 0 fail / exit=0
# 统一门禁（Java 树有改动 ⇒ 重跑）
cd ..
.\scripts\run-tests.ps1 -Suite default -RunId <runId> -LogDir .verify\<runId> -Confirm
# 文件体检（绝对路径；Set-Location 不改变 .NET CWD）
$p='D:\Develop_code\GraduationProject-wt\v3-dev\web\tests\permissionReconcile.test.js'
$b=[IO.File]::ReadAllBytes($p); "字节=$($b.Length) 行数=$((Get-Content $p).Count) 裸LF=$(($b|Where-Object{$_ -eq 10}).Count - ([regex]::Matches([IO.File]::ReadAllText($p),"`r`n")).Count)"
```
