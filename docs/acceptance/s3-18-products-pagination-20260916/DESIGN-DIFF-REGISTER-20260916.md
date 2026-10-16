# DESIGN-DIFF-REGISTER —— S3-18 商品分析热度榜真分页（阶段4 L158「分页」子项）

- 日期：2026-09-16（项目内日期）
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 上游=F-51（前序 F-50 = S3-17 分析信封补 `source`，HEAD `219cb37`）
- 类别判定：**A 类（IMPLEMENTATION / ADDITIVE）** ⇒ 登记 → 自主设计 → 实现 → 测试 → commit → 继续
- 本轮证据目录（gitignored）：`.verify/s318/def/`；靶向日志 `%TEMP%\s318-red1.log`、`%TEMP%\s318-green1.log`

## 0. 一句话结论

指导书 §7 阶段4 **L158** 的第 3 条含三个并列子项（**分页** / 限流 / 超时统一），本轮只落
**「分页」的第一刀：`/api/v1/analysis/products` 热度榜真分页 + 稳定排行**（A 类加性），并把它写进契约 v1.3。
**L158 整体仍未满足**（`sort`、限流、超时统一未做，逐条登记于 §8）——不得因本文件发布而声称 L158 通过。
11 条 HARD DECISION GATE **全部不触**（§3 逐门）。

## 1. 设计原文（逐字，S3-18 标尺）

**指导书 V3.0 §7 阶段4 L158（`docs/guidance/项目完整实施指导书 V3.0.md`）**：

> 3. 异步任务返回标识，提供阶段/失败/重试反馈；分页、限流、超时统一。

**指导书 V3.0 §8 各阶段完成标准 L200（第 4 阶段行）**：

> | 4 | 真实快照查询、权限/空态/错误/分页正确；任务请求不阻塞到 Spark 结束 |

**设计 V3.0 L693（`docs/design/项目设计文档 V3.0.md`，API 通用约定）**：

> 根路径/api/v1；响应 code/message/data/traceId；分页 page/size/sort；写操作幂等请求头按实际能力明确。长任务返回 taskId 与可查询状态，不能保持 HTTP 直到 Spark 结束。时间 API 用 ISO8601 带时区，内部 ADS 字符串 dt 按 yyyyMMdd 转换；金额精确字符串或严格 decimal，不用 JS 浮点做汇总。

**设计 V3.0 L675（专题页要求表「商品分析」行）**：

> | 商品分析 | 热度/销量/转化/退款、稳定排行、分页 | 成本库存缺失就不算利润/覆盖 |

**指导书 §12 L271 约束**（Code Agent 只提交设计差异请求，不自行修改两份正式文档）：本轮**未改** guidance/design 正式文档，
改动只落在 `docs/contracts/analysis-viewmodel-r7-4.md`（分析接口 ViewModel 契约，非正式设计文档）与代码/测试/基线。

## 2. 开工前冻结事实（实测，非推测）

| # | 事实 | 取证命令 / 位置 |
|---|---|---|
| F1 | `contract-specs/**` 中 `page`/`size`/`sort` **零命中** ⇒ 分页语义此前不存在于契约面（故新增分页**不是** gate ⑥「改已有契约语义」） | `git grep -n -i -E "page\|size\|sort" -- contract-specs/` ⇒ 0 行 |
| F2 | 后端 main 源码此前**无任何分页实现**（无 `PageResult`/`Pageable`/`@RequestParam … page`） | `git grep -n -E "class Page\|PageResult\|pageNum\|@RequestParam.*page" -- analytics-server/*/src/main/java` ⇒ 0 命中 |
| F3 | 改前端点签名：`AnalysisController.products(@RequestParam(defaultValue="10") int topN, from, to, snapshotId)`；服务：`products(String snapshotId, int topN, LocalDate from, LocalDate to)` | `AnalysisController.java:65-73`（改前）、`AnalysisService.java:183`（改前） |
| F4 | 热度榜切片发生在 **Java 侧**（`ranked.stream().sorted(rank_no).limit(topN)`），不是 SQL `LIMIT` ⇒ 分页**不需要**改 SQL、表、迁移或 reader 接口 | `AnalysisService.hotProducts()`（改前 L428-439） |
| F5 | 既有上限常量：`DEFAULT_TOP_N = 10`、`MAX_TOP_N = 100`（旧行为对 `topN>100` 静默截断到 100，回显仍为请求原值） | `AnalysisService.java:63-64` |
| F6 | 前端只发旧参数：`web/src/views/Products.vue:148` `analysis.load({ topN: topN.value })`（输入框 `min=1 max=200`），并显示 `本次返回 topN={{returnedTopN}}` | `web/src/views/Products.vue:7,11,148` |
| F7 | AI 证据包调用点：`EvidenceBuilder.java:236` `products(snapshotId, DIMENSION_TOP_N=10, from, to)` | `EvidenceBuilder.java:47,236` |
| F8 | `ProductsData` 构造点 2 处（服务 1 + `EvidenceBuilderTest` 1）；`products(...)` 调用点 5 处（控制器、EvidenceBuilder、3 个测试） | `git grep -n "\.products("` |
| F9 | 错误码通路：`PlatformBizException(code,msg)` → 单一所有者 `GlobalExceptionHandler.mapStatus`，**未列出的码一律 400**；`PARAM_INVALID` 已在 ai-decision 使用 | `GlobalExceptionHandler.java:47-55`；`DecisionService.java:125` 等 |
| F10 | 限流：全仓**无实现**（`ratelimit/429` 10 处命中逐条看均为 checksum/数值误报） | `git grep -n -i "ratelimit\|429" -- analytics-server` |
| F11 | 服务端超时：`application.yml` 仅有 Spark 作业超时 `job-timeout-ms`，**无 HTTP 请求级统一超时** | `platform-app/src/main/resources/application.yml:43` |
| F12 | 断言口径：默认档基线 `analytics-server=915`（明细 `90+350+163+73+92+147`）、`三棵树=1034` | `scripts/run-tests.ps1:111`、S3-17 末次门禁 |

## 3. 逐门核对（11 条 HARD DECISION GATE）

| 门 | 判定 | 依据（实测/事实） |
|---|---|---|
| ① DROP TABLE/COLUMN | **不触** | 本轮 0 个迁移文件改动（`git status` 无 `db/` 路径） |
| ② 改已有字段类型或既有业务语义 | **不触** | 请求侧 `topN` 兼容保留；`data.topN` 由「前 N 名」收紧为「本次窗口上限」，但**旧前端只可能构造 page=1 请求，page=1 时逐字段等值**（见 §5.2 的 `productsFirstPageKeepsV12ShapeWhenOnlyLegacyTopNIsProvided` 与更新后的 `productsEchoTopNButClampEffectiveValue`）；`page>1` 是**新增能力**，此前该字段在分页请求下无既有语义。**已在 §8 主动登记请总控批注** |
| ③ 改已发布 Flyway migration | **不触** | `db/metric/V1-V10`、`db/meta/V1-V25` 字节未动 |
| ④ 写/迁移正式 3306 数据 | **不触** | 本轮测试全为 Mockito 单测（零 DB 连接）；门禁为单测档 |
| ⑤ 切 ACTIVE | **不触** | 无快照状态写入；`MetricAdsReader.activeSnapshotId()` 未改 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **不触** | `contract-specs/**` 零改动（F1：本就没有分页语义）；分页契约写在 `docs/contracts/analysis-viewmodel-r7-4.md` |
| ⑦ 改 V3.0 总体架构 | **不触** | 仅控制器加 2 个可选参数 + 1 个 data 记录加 4 个字段 |
| ⑧ 改正式项目范围 | **不触** | L158 本就在 V3.0 阶段4 范围内；本轮只做其中「分页」子项 |
| ⑨ 删除已发布功能 | **不触** | 未删端点/字段/测试；`topN` 与 `conversion` 全量语义保留 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **不触** | **零新依赖**（未引入 Spring Data / Bucket4j / Resilience4j）；只复用既有 `PlatformBizException` |
| ⑪ 两种方案造成重大长期架构分叉 | **不触** | 分页只在分析服务层做一处（未同时做网关/拦截器两套）；无可分叉的备选架构 |

## 4. 实施清单（本轮）

1. **契约（先文档后代码，契约 L4「若实现中确需改动，先改本文件再改代码」）**
   `docs/contracts/analysis-viewmodel-r7-4.md`：新增 **v1.3 加性补充**块（含「本版只落分页一项，不得声称 L158 满足」的
   边界声明）；§3.3 标题补 `page`/`size`、JSON 示例补 4 字段、新增 6 条语义（参数/窗口/稳定排行/非法参数/`conversion` 不分页/`filters` 回显）。
2. **服务** `analysis-server/metric-analysis/.../AnalysisService.java`
   - `ProductsData` **加性**补 `page`/`size`/`total`/`hasMore`（记录 3 → 7 分量）；
   - `products(snapshotId, Integer page, Integer size, Integer topN, from, to)`；
   - 新常量 `MAX_PAGE_SIZE = MAX_TOP_N`（刻意共用，避免两处上限漂移）；
   - `resolvePage`/`resolveSize`：显式非法值 fail-fast `PARAM_INVALID`；`size` 未给出时沿用 v1.2 旧 `topN` 口径
     （`topN<=0` ⇒ 缺省 10，**不**因本版转错误）；`size` 显式超上限 ⇒ 生效值截到 100（与旧 `topN` 同口径）；
   - `hotProducts(sid, topN)` → `rankedHotRows(sid)`（全量 + 稳定排序）+ `toHotProduct` 映射；
     排序 = `rank_no` 升序 **then `product_id` 升序**（设计 L675「稳定排行」）。
3. **控制器** `platform-app/.../AnalysisController.java`：`page`/`size` 两个 `required=false Integer`，`topN` 由
   `int + defaultValue="10"` 改为 `Integer`（缺省语义在服务层解析，**HTTP 观测不变**：缺省仍等价于 10）。
4. **AI 侧调用点** `ai-decision/.../EvidenceBuilder.java:236`：改传 `(snapshotId, null, null, DIMENSION_TOP_N, from, to)`
   ⇒ page=1、窗口=10，**行为不变**。
5. **测试**：`AnalysisServiceTest` +7；新增 `AnalysisControllerTest`（2 条，接线）；`EvidenceBuilderTest` 2 处机械改；
   `AnalysisGoldenMySqlIT` 1 处机械改（D 类永久排除，仅为编译）。
6. **基线** `scripts/run-tests.ps1`：`analytics-server 915 → 924`（明细 `73→80`、`147→149`），三棵树 `1034 → 1043`；
   新增 S3-18 注释块（含「只落分页一项」的文字边界）。

## 5. 实测证据

### 5.1 RED（先红）

- 日志：`%TEMP%\s318-red1.log`，`mvnExit=1`
- 形态：**编译红**（`COMPILATION ERROR`）：`AnalysisServiceTest` 多处
  `无法将 AnalysisService 中的方法 products 应用到给定类型`（旧 4 参 → 新 6 参）与
  `找不到符号`（`page()`/`size()`/`total()`/`hasMore()`，行 202-205、218-221、236-239、256-259 等）。
- **诚实边界**：Java 记录分量/方法签名变更的 TDD 红**只可能表现为编译失败**（先例 S3-15 RED #0、S3-16 RED-2、S3-17 RED-1）。
  因此行为正确性（窗口边界、`total` 真值、平局决胜、非法参数 fail-fast）由 5.2 的 GREEN 断言承担，不由 RED 承担。

### 5.2 GREEN（靶向，含跨模块编译面）

- 日志：`%TEMP%\s318-green1.log`，`mvnExit=0`，反应堆 `7/7` + `BUILD SUCCESS`
- `AnalysisServiceTest`：`Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`（改前 11，+7）
- `AnalysisControllerTest`（新类）：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`
- `EvidenceBuilderTest`：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`
- 覆盖点：① 只传旧 `topN` ⇒ page=1 且 `topN`/`hot`/`total`/`hasMore` 与 v1.2 期望一致（兼容性）；
  ② `page=2&size=5` ⇒ 第 6~10 名窗口 + `total=15` + `hasMore=true` + `filters` 回显（`topN` 仍为请求原值/缺省 10）；
  ③ 旧 `topN=3` 退化为窗口大小；④ `page` 越界 ⇒ 空 `hot` 且 `total`/`hasMore` 如实（空页不是错误、无 warning）；
  ⑤ 空排行 ⇒ `total=0`；⑥ 同 `rank_no` 平局 ⇒ 按 `product_id` 升序（稳定）；⑦ 显式 `page<1`/`size<1` ⇒
  `PARAM_INVALID`（4 个用例）且旧 `topN=0` 仍取缺省 10（不转错误）；⑧ 控制器按位置下传 3 个可分页参数、全缺省传 `null`。

### 5.3 门禁（fresh 真跑，门禁版本 = 待提交版本）

- RunId `s318_20260916_def`，日志 `.verify/s318/def/default-analytics-server.log` 等 3 份
- `analytics-server  exit=1  Tests run: 924 (F=1 E=0 S=1)  模块汇总行 6 明细 90+350+163+80+92+149` ⇒ **tests=924 MATCH**
  （`metric-analysis` 73→80、`platform-app` 147→149，与 `AnalysisServiceTest +7` / `AnalysisControllerTest +2` 逐一对上）
- `mall-simulator 13 MATCH`、`synthetic-data-generator 106 MATCH`、`default 三棵树 合计 = 1043（基线 1043）` MATCH
- **唯一红**：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`（`expected: 43`，实际 0 个 manifest）
  —— 已登记的**环境性**红（S3-16/S3-17 同一处）。本轮**未修、未复制 manifest、未用开关掩盖**。
- 口径说明：本档结论是「**计数 MATCH + 唯一红＝已登记环境性红**」，`exit=7`／`[FAIL]`，**不是** exit=0（基线数字本身含这 1 个红）。

### 5.4 不得越界表述

- 不得表述为「L158 已满足」：只落「分页」一项，`sort`/限流/超时统一未做（§8 逐条）。
- 不得表述为「分页端到端已验证」：无 `@SpringBootTest`/MockMvc（项目禁止 `@SpringBootTest`），
  只有服务层语义测试 + 控制器 Mockito 接线测试；**真实 HTTP 请求未走**。
- 不得表述为「真库商品总数/分页窗口已核」：零 DB 访问，`total` 的真实取值未测。
- 不得表述为「页面已支持分页」：前端本轮未改（`Products.vue` 仍只发 `topN`，输入框上限 200 由服务端截到 100）。

## 6. 未测与边界

1. **真实 HTTP 端到端**：`GET /api/v1/analysis/products?page=2&size=5` 未经真实容器请求验证（无 MockMvc/@SpringBootTest）。
2. **真库/真快照**：真实 ADS 行数、真实 `rank_no` 分布、真实平局是否存在、真库 `total` 取值——全部**未测**。
3. **静默上限的不对称（已决策，仍请批注）**：显式 `page<1`/`size<1` fail-fast 400；显式 `size=500` **截到 100 不报错**
   （沿用旧 `topN` 的既有截断口径），`filters.size` 回显**生效值 100**（与旧 `topN` 回显请求原值的做法不同——因为新参数无历史包袱，
   回显生效值才与「实际返回行数」一致）。
4. **`conversion` 不分页**：保持全量（既有理由：`ads_product_conversion_m` 截断会漏商品）。
5. **其它列表端点仍无分页**：`/metrics/snapshots` 仍用 `limit`；`/pipeline-runs`、`/decisions`、`/ai/audit/*`、`/admin/users` 无 `page/size`。
6. **前端未接**：`Products.vue` 未加分页控件（阶段5 范围）。
7. `AnalysisGoldenMySqlIT` 的签名机械改**未执行**（D 类永久排除，仅保证编译）。

## 7. 检索证据（「未改即证据」）

- `git status --porcelain` ⇒ 9 条：8 `M`（3 main + 3 test + 契约 + 基线脚本）+ 1 `??`（新测试类）；
  **无** `contract-specs/`、**无** `db/`、**无** guidance/design 正式文档、**无** `web/`、**无** mall-simulator/generator。
- `git grep -n "Integer page" -- analytics-server` ⇒ 3 命中（`AnalysisService` 声明 2 + `AnalysisController` 参数 1）
  ⇒ **分页只落在 products 一条链**，未顺手改其它端点。
- `git grep -c "int topN, LocalDate from"` ⇒ 0 ⇒ 旧签名无残留（不存在两套入口）。
- `MetricAdsReader` 未改（分页在 Java 侧切片，读取器不承担分页职责 ⇒ 不产生第二个分页所有者）。
- 已发布 Flyway（`db/metric/V1-V10`、`db/meta/V1-V25`）零改动。
- `contract-specs/**` 本轮 `git status` 无路径命中（F1 的 0 命中在改后仍为 0）。

## 8. 遗留 / 后续（登记，不擅自实施）

| # | 事项 | 归属 | 本轮状态 |
|---|---|---|---|
| R1 | L158「**限流**」——全仓无实现（F10） | 阶段4 | **未实现**；需先定策略（内存令牌桶? 阈值? 429 码所有权?)⇒ 涉及新错误码与并发语义，**下一候选前需先测量并判 A/B 类** |
| R2 | L158「**超时统一**」——服务端仅 Spark 作业超时，无 HTTP 请求级统一超时（F11） | 阶段4 | **未实现**；需明确「统一」的作用域（查询超时? 外部调用?） |
| R3 | L158/设计 L693 的 **`sort` 参数** | 阶段4 | **未实现**（商品榜固定 `rank_no` ⇒ 排序由生产者定义，页面不该改语义） |
| R4 | 其它列表端点分页（snapshots/decisions/pipeline-runs/ai-audit/users） | 阶段4 | 未接；本轮只做设计明确点名「分页」的商品分析 |
| R5 | 前端商品页分页控件 + `size` 上限提示 | 阶段5 | 未做（页面项） |
| R6 | **`data.topN` 语义收紧**（前 N 名 → 窗口上限）+ `size` 静默上限不对称 | 契约 | 已在契约 v1.3 写明；**请总控批注**是否接受该收紧口径 |
| R7 | 真实 HTTP 分页端到端 / 真库 `total` | 验收 | 未测（§6.1、§6.2） |
| R8 | 商品榜真库是否存在 `rank_no` 平局（本轮加 `product_id` 决胜键的动因） | 数据核查 | 未测；平局决胜键已实现且单测覆盖 |
