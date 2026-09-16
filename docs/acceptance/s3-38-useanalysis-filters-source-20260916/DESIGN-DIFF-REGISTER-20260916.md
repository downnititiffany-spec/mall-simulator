# S3-38 设计差异登记：`useAnalysis.js` 导出上下文的 `filters` **唯一来源**口径收口（＋一条台账校正）

- 日期：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`；主检出全程未改）
- 行来源：`docs/PROJECT_STATUS.md` backlog 行 **L400**「`web/src/composables/useAnalysis.js:33` 注释与实现口径不一致（S3-35 实测新登记）」的**选项①**（把注释改成与实现一致）
- 类别：**A 类（实现/加性 → 本项为"注释与实现对齐 ＋ 守卫"）** —— 见 §6（11 门逐门否）

## §0 本轮主张与证据边界

**本轮实测**：`web` 套件 `npm test` **118/118 `exit=0`**（基线 114 ⇒ **+4 条守卫**）
＋ **3 个源码变异探针（P1/P2/P3）红得其所** ＋ 复原后 hash 逐字相同、终态复跑 118/118。
另：`git diff <S3-37 HEAD> -- analytics-server spark-jobs scripts mall-simulator synthetic-data-generator` **为空**
⇒ 后端/门禁相关树**零字节变化**，S3-37 旁证轮（`s337-side-1`：`tests=956 MATCH` ＋ 唯一已登记环境性红）**逐字适用**（见 §5）。

**本轮未测**：
① **运行时行为未验** —— `useAnalysis.js` 依赖 `vue`，而 `web/node_modules` 不存在 ⇒ 该 composable **不可被 import 进单测**；
本轮证据**全部是源码文本守卫**（与 `tests/pipelinePage.test.js`、`tests/netSaleDisplay.test.js` 同型）。
⇒ **不得**称「已验证运行时不回退」，只能说「**当前源码形状**不允许该回退存在，且改回去会被守卫抓住」。
② 渲染／浏览器未验（同上环境缺口）。
③ 选项②（**真的实现回退**）**未做**：它要先裁决「哪些请求参数算 `filters`」＝**口径问题**，见 §8 R-1。

## §1 开工前实测（真跑）

| # | 实测 | 证据 |
| --- | --- | --- |
| F1 | 漂移**只存在于一处代码注释**：全仓（排除 `node_modules`/`target`/`.verify`/`.git`）搜「回退到」＋「本次请求参数」⇒ `web/src/composables/useAnalysis.js:33` 唯一命中（另一命中是 backlog 行 L400 自己在**引用**这句话） | `Get-ChildItem -Recurse -File -Include *.js,*.vue,*.md \| Select-String '本次请求参数\|回退到'` |
| F2 | **契约口径与实现一致、与旧注释不一致**：`docs/contracts/analysis-viewmodel-r7-4.md:182` 逐字「`filters` \| object \| **原样回显生效筛选**（含快照、日期区间、topN）」；**无**任何「缺失时用请求参数补」的要求 | 同上；另 `:337`「导出（CSV）必须携带当前 `filters`…」 |
| F3 | 实现事实：`exportContext`（L44）**只**读 `ctx.filters`（且空对象 ⇒ `null`）；`load(requestParams)`（L55-78）**不保存** `requestParams`，`requestParams` 全文只出现 2 次（形参 L55、透传 L63） | 代码 L44 / L55 / L63 |
| F4 | S3-35 已把该事实写进登记（`docs/acceptance/s3-35-pipeline-state-owner-20260916/DESIGN-DIFF-REGISTER-20260916.md:102`「**不存** `requestParams`」）⇒ 本轮是把**代码注释**也拉到同一口径上，不是新发现 | 该登记 §… 第 3 条 |
| F5 | 旧注释的**副作用表述**也是错的（漏了一半信息）：`@click="load"` 传进来的 `MouseEvent` **不会**污染 filters/导出件 —— 这是"不回退"的**好处**，值得写进注释 | S3-35 登记 L102 已实测该点 |

## §2 可复现命令

```powershell
Set-Location 'D:\Develop_code\GraduationProject-wt\v3-dev\web'
npm test                                              # 118/118 exit=0
& "$env:TEMP\s338_probe.ps1"                          # P1/P2/P3 变异探针 + 复原终态复跑
git -C 'D:\Develop_code\GraduationProject-wt\v3-dev' diff --stat f2178da -- analytics-server spark-jobs scripts mall-simulator synthetic-data-generator   # 应为空
```

## §3 本轮冻结口径

1. **注释必须与实现一致**：`filters` **只**取信封回显（＝ 契约 L182 的"原样回显生效筛选"），**不**回退到请求参数。
2. **缺失即缺失**：信封无 `filters`（或空对象）⇒ `null` ⇒ 导出件不写该行（既有行为，`csv.js` 侧不变）。
3. **该事实要能被守卫**：用**源码文本守卫**钉住三件事 —— ①注释不得出现**非否定**的「回退到本次请求参数」；
   ②注释正面写明「只取信封回显」「不回退到本次请求参数」；③`requestParams` **只允许出现在入参位置**。
4. **不动运行时行为**：本轮**零**语义改动（唯一生产改动是注释），因此**不**新增/修改任何运行时分支。
5. **选项②不越界**：真的实现回退属**口径裁决**项（哪些参数算 filters、与契约 L337 的关系），未裁决前**不得**实现。
6. **守卫的诚实边界要写进代码注释与登记**：文本守卫 ≠ 污点分析，`arguments[0]` 类写法可绕过（§7）。

## §4 实现面（3 文件：1 生产注释 ＋ 1 新测试 ＋ 文档；生产 `+4/−1`、测试 `+62/−0`（新文件））

| 文件 | 改动 | 行 |
| --- | --- | --- |
| `web/src/composables/useAnalysis.js` | L33-36 注释重写：`filters` **只**取信封回显（引契约 `:182`）＋ `load` 不保存请求参数 ⇒ 缺 `filters` 按缺失处理、**不**回退；副作用不污染；L37 保留 S3-26 那行 | L33-36 |
| `web/tests/useAnalysisFilters.test.js` | **新增** 4 条源码守卫（下节） | 全文件 62 行 |
| `docs/acceptance/s3-38-useanalysis-filters-source-20260916/DESIGN-DIFF-REGISTER-20260916.md` | 本登记 | 全文件 |

**4 条守卫**（`useAnalysis.js` 依赖 `vue` ⇒ 只能读源码文本）：
1. 注释不得出现**非否定**的「回退到本次请求参数」——用后顾断言 `/(?<!不)回退到本次请求参数/`（在**归一化文本**上跑，原因见 §5 的守卫自纠）。
2. 注释**正面**写明 `filters 只取信封回显` 与 `不回退到本次请求参数`（归一化后断言 ⇒ 不绑排版、只绑措辞）。
3. `exportContext` 的 `filters:` 取值行只读 `ctx.filters`，且**不**引用 `requestParams`。
4. 代码内 `requestParams` 恰 **2** 处（形参 ＋ 透传），且**每一处都必须在入参位置**（前一个非空白字符是 `(` 或 `,`）。

## §5 证据矩阵（真跑，逐条可复现）

| 轮次 | 动作 | 结果 |
| --- | --- | --- |
| **RED-1** | 先写 4 条守卫、未改注释 | `exit=1`、**3 红**（守卫 1/2 因旧注释文本；**守卫 4 误红**：断言写成"文件内恰 2 处"但把 L53 的 **JSDoc** 也算进去 ⇒ 3 处） |
| **RED-2** | 守卫 4 只看**代码行**（剔除 `//`、`*`、`/*` 开头） | `exit=1`、**恰好 2 红**（＝两条注释口径守卫），`tests 118 / pass 116 / fail 2` |
| **GREEN-1（失败，如实登记）** | 改注释 | `exit=1`、**1 红**（守卫 1）—— 我的后顾断言 `/(?<!不)回退/` 在原文件上**看不到「不」**：源码写的是 `**不**回退`，`回退` 前面那个字符是 `*`。**这是守卫自身的缺陷，不是实现问题** |
| **GREEN-2** | 守卫 1 改在**归一化文本**（去 `*`/反引号、压缩空白）上断言 | **118/118 `exit=0`** |
| **守卫加固（防绕过）** | 追加"`requestParams` 只允许在入参位置"不变量 | 首版写成 `/=\s*[^\n]*\brequestParams\b/` ⇒ **被探针 R（复原终态复跑）误报**：`const raw = await fetcher(requestParams, …)` 行内**有一个无关的 `=`** ⇒ 实测红 ⇒ 改为**逐处检查前一个非空白字符是否为 `(`/`,`** |
| **GREEN-3** | 守卫收紧后 | **118/118 `exit=0`** |
| **探针 P1**（注释漂回去） | 4 行新注释换回旧句 | `pass=116`、**2 红**＝守卫 1 ＋ 守卫 2（措辞守卫有牙） |
| **探针 P2**（**真实现回退**且**改名变量**） | 加 `let lastParams = {}` ＋ `lastParams = requestParams` ＋ `filters: … : lastParams` | `pass=117`、**1 红**＝守卫 4（**"只允许入参位置"这一不变量把改名绕过面堵住了**；若只有"恰 2 处"计数断言则此探针会 `0 红`） |
| **探针 P3**（`filters` 行直接用请求参数） | `filters: … : requestParams` | `pass=116`、**2 红**＝守卫 3 ＋ 守卫 4 |
| **复原** | 从 `$env:TEMP\s338_bak` 复原 | `useAnalysis.js` `SHA256-16 = 5CF42045C63C0D0F` **与探测前逐字相同**；终态复跑 **118/118 `exit=0`** |
| **旁证（零字节变化实测）** | `git diff f2178da -- analytics-server spark-jobs scripts mall-simulator synthetic-data-generator` | **空**（本轮改动只在 `web/**` 与 `docs/**`）⇒ S3-37 旁证轮 `s337-side-1` 的 `tests=956 MATCH`／三棵树 `1075（基线 1075）`／唯一已登记环境性红 **逐字适用**；门禁**基线不动** |

## §6 类别判定与 11 门逐门核对

**类别：A 类（实现/加性）** —— 生产改动**只是注释**（`+6/−1`，零运行时语义变化）＋ 测试新增 4 条守卫。

| # | 门 | 是否触发 | 依据 |
| --- | --- | --- | --- |
| ① | DROP TABLE/COLUMN | **否** | 未动 SQL/DDL |
| ② | 改已有字段类型或既有业务语义 | **否** | `filters` 语义由契约 L182 定义为"原样回显"，实现本就如此；本轮把**注释**对齐契约与实现 |
| ③ | 改已发布 Flyway migration | **否** | 未触碰 `V*.sql` |
| ④ | 写/迁移正式 3306 数据 | **否** | 0 次连库 |
| ⑤ | 切 ACTIVE | **否** | 未触碰 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **否** | 未触碰；`docs/contracts/**` 本轮**无需改**（F2：契约本就写"原样回显"，是旧注释与契约/实现都不一致） |
| ⑦ | 改 V3.0 总体架构 | **否** | 无 |
| ⑧ | 改正式项目范围 | **否** | 落点＝backlog L400 的选项① |
| ⑨ | 删除已发布功能 | **否** | 纯注释重写 ＋ 测试新增，零删除 |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **否** | 未新增依赖（`node:test`/`node:assert`/`node:fs` 皆为既有用法） |
| ⑪ | 两种方案造成重大长期架构分叉 | **否** | 本轮**只做选项①**；选项②（真回退）保留为待裁决项，见 §8 R-1 |

## §7 未测与边界（不得越界表述）

1. **运行时未验**：`useAnalysis.js` 依赖 `vue`、`web/node_modules` 不存在 ⇒ 文本守卫 ≠ 运行时证明。
   ⇒ **不得**称「运行时已验证不回退」或「filters 行为已回归测试」。
2. **文本守卫 ≠ 污点分析**：探针 P2 证明"改名变量保存请求参数"会被拦住，但
   **`arguments[0]`／`Object.assign(t, arguments[0])`** 这类**完全不写出 `requestParams`** 的写法仍可绕过。
   该残余绕过面**如实登记**，不声称"根治"。
3. **注释里的契约引用是行号**（`analysis-viewmodel-r7-4.md:182`）：契约是已发布文件、行号在该版本内稳定；
   若将来契约改版换行号，本守卫**不会**发现（守卫只钉措辞，不校验契约行号指向）。
4. **选项②未做**（真的实现回退）⇒ `filters` 缺省时导出件仍不写该行；这是**既有行为**、不是本轮引入的回归。
5. **一条台账校正（本轮顺带，见 §10）**：backlog L416「阶段5 页面未展示净销售额」在 S3-27 后**已部分满足**但行内无更新标记；
   本轮据实补注（**不删行、不改判类**）。其"概览页是否也要展示"属**口径**问题，未裁决前不擅自加。

## §8 遗留

- **R-1（选项②，未做）**：真的实现「信封缺 `filters` 时从请求参数回退」—— 需先裁决「哪些请求参数算 `filters`」
  （页面入参 vs 后端回显语义）＋ 与契约 L337「导出必须携带当前 `filters`」的关系 ⇒ **口径裁决项**，Coder 不擅改。
- **R-2（守卫残余绕过面）**：`arguments[0]` 类写法（§7-2）。
- **R-3（契约行号漂移）**：注释引用的契约行号无守卫（§7-3）。
- **R-4**：`useAnalysis.js` 仍不可被单测 import（依赖 `vue` ＋ 无 `node_modules`）⇒ 该 composable 的**行为**层证据仍是空白，
  属**环境缺口**（与 S3-27/S3-34/S3-35/S3-37 登记的同一缺口）。
- **R-5**：S3-35 登记的其他遗留（`Store 能力描述暴露面`、`S3-18 R6` 待批注等）继续留在 backlog。

## §9 反熵声明

- **同一事实只有一个所有者**：`filters` 的来源＝`exportContext` 内一处表达式（`ctx.filters`）；本轮**没有**引入第二个来源、
  也**没有**引入任何"备用 filters"。守卫钉的正是"只有一个来源"这一不变量（探针 P3 证明加第二个来源即红）。
- **注释不再是一份"独立的、会漂的声明"**：措辞被守卫钉住（P1 证明漂回去即红），且注释里**指明**事实的权威位置（契约 `:182` ＋ 本文件 `load`）。
- **没有新增缺失值词汇/降级路径**：`filters` 缺失仍是 `null`（既有语义），未新增"未知/回退值"等第二套表达。
- **零删除、零降级**：生产 `+6/−1`（−1 是被替换的旧错误注释）；测试纯新增；既有 114 条断言**无一改动**、全部仍绿。
- **诚实记录自己的守卫缺陷**：本轮守卫自身出过两次错（RED-1 的 JSDoc 误计、GREEN-1 的强调符后顾失效、加固版的 `=` 误报被探针 R 抓到）
  —— 三处全部如实写入 §5，不掩盖。

## §10 顺带台账校正（backlog L416，**不删行、不改判类**）

- **行现状**：`| 阶段5 页面**未展示**净销售额：`netSaleAmount`…在 `chartOptions.js`／`Sales.vue`／`Overview.vue` **零消费** | **阶段5 开发项** …|`
- **实测事实（2026-09-16，`git grep`/`Select-String` 全 `web/**`）**：`netSaleAmount` 已被消费 ——
  `web/src/utils/chartOptions.js:36`（趋势图第二条 bar「净销售额」）、`web/src/views/Sales.vue:49`（明细列）、
  `:101`（汇总卡）、`:112`（CSV 导出列）、`:145`（导出行）；测试 `web/tests/netSaleDisplay.test.js`、`web/tests/chartOptions.test.js`；
  **`Overview.vue` 仍 0 命中**。
- **结论**：该行**已由 S3-27 实施**（登记见 `docs/PROJECT_STATUS.md` 阶段5 的 S3-27 记录与
  `docs/acceptance/s3-27-net-sale-display-20260916/`），仅 `Overview.vue` 未展示；「概览页是否也要展示净销售额」属**口径**问题。
  本轮**只补注**、**不删行**、**不改判类**，也不擅自往概览页加内容。
