# S3-09 设计差异登记（2026-09-16）

> 提交：`fix(publish): S3-09 发布侧表形严格化 —— 导出缺声明列即拒绝（不再静默补 null）`，
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工／哪些必须停工」。

---

## 0. 一句话结论

设计 §12.5 **L528/L529** 的发布链逐字写着「**表形**/行数/定义版本/内容验证」，
指导书 §7 阶段三第 ④ 条（**L151**）逐字写着「核 **schema**/行数/checksum」。
S3-06 已补上「内容验证」（checksum），但「**表形**」这一格**一直没有所有者**：

- **发布清单级**（`MP_MANIFEST_TABLES`）确实核了「清单声明列 ↔ `MetricAdsCatalog` 白名单」——
  但清单是 **Spark 侧自己写的**（`MetricExportJob` 把 `spec.columns` 写进 `_export.json`），
  它证明的是「Spark 声明的列集 = Java 白名单」，**不证明导出文件里的行真的是那个列集**。
- **行级**（`AdsExportReader`）此前是**单向**校验：非白名单列 → 拒绝；**声明列缺失 → `putIfAbsent(null)` 静默补齐**
  （旧注释逐字：「缺失列补 null：写入侧按列集合建 SQL，缺列会退化为 DDL 默认值，这里显式补齐保证列集一致」）。

于是出现一条**三关全过**的假成功路径：导出侧少写一列（版本落后的 Spark 制品、被人工截断的 JSONL、
其他工具生成的"像"制品）⇒ 读取器补成 `null` ⇒ 该列以「值为空」写进 ADS/`metric_value` ⇒
`MP_MANIFEST_TABLES`（清单✅）、`MP_EXPORT_CHECKSUM`（字节与清单一致✅）、
`MP_ROW_SHAPE_CONSISTENT`（各行补完后完全一致✅）、`MP_ADS_ROWS_MATCH`/`MP_ADS_ROWS_DB_MATCH`（行数✅）
**全部通过**，发布切 ACTIVE —— 页面上就是一个**"今天这列指标没数据"**的假结论。

本轮把校验做成**双向严格**，并把两件被混为一谈的事分开：
**「键缺失」（导出的不是声明的表形）⇒ 拒绝**；**「键在、值为 null」（当日没有该指标值）⇒ 原样保留**。
**判定：A 类（修正实现遗漏 + 补齐设计明确要求的校验）**，不触发 11 条破坏性决策门中的任何一条
⇒ 按「默认自主连续开发」直接实施、测试、提交。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §12.5 **L528** | 「…→ `mxp`导出 + manifest/checksum → MySQL BUILDING/staging → **表形**/行数/定义版本/内容验证 → 单数据库事务…」 | 「表形」这一格落到 `AdsExportReader`（读取即校验），补上 S3-06 未覆盖的部分 |
| 设计 §12.5 **L529** | 「**表形**/行数/金额/定义版本/内容验证」 | 同上：**逐行**核声明列集，不为残缺制品做修复 |
| 指导书 §7 阶段3 ④（**L151**） | 「导出 ADS 制品核 **schema**/行数/checksum，MySQL 暂存验证后切 ACTIVE」 | schema = 每行键集必须逐列等于目录声明列集；校验失败 ⇒ 该表不写、整个发布 FAILED |
| 指导书 §7 阶段3（**L148**） | 「对每个指标固定粒度、分子分母、时间窗口、金额/退款口径、**空值规则**和版本」 | 「空值规则」被本轮**钉死为显式**：**键在值空 = 真实空值（保留）**，**键缺失 = 表形不合（拒绝）**——两者不得再互相冒充 |
| 指导书 §8 **L199** | 「稳定指标公式、分层对账与发布制品；**失败保旧**；不靠前端/AI临时算指标」 | 失败路径不变：`MP_ADS_WRITE_FAILED` → `compensate` 清掉本次已写的半张表 → **ACTIVE 指针不动**（旧快照仍是唯一可读版本） |
| 设计 §9.3 **L320** | 「ADS…每行带snapshot／定义版本／业务日期，发布可追溯」 | 行必须**真的**带齐声明列才谈得上"可追溯"：缺列的行连"哪个口径字段没算"都无法区分 |
| Spark 侧既有保证（`MetricExportJob.scala:26,52,159`） | 「JSONL 必须保留 null 字段：**发布侧批量写入要求同一批列集一致**（`ignoreNullFields=false`）」＋ `spark.conf.set("spark.sql.jsonGenerator.ignoreNullFields", "false")` ＋ `df.toJSON` | 生产者**本来就承诺**每行写全列（null 也写键）。旧读取器用"补 null"替生产者兜底，等于把**承诺降级成默契**；本轮改为**校验该承诺** |
| 审计 `docs/audit/v2-completeness-audit.md:213,:358` | 「无 checksum（仅路径 + 行数）」／「发布层 行数一致 + 抽样 checksum｜部分｜**无抽样 checksum**」 | S3-06 已补齐内容验证；本轮补齐同一行里并列的「表形」格 |

**失败样例（改前真实行为，来自本轮的 RED 取证）**：`ads_active_trend_m` 的导出文件只写
`{"dau":3}`（缺 `behavior_count`），`readTable` **不报错**，返回
`{dau=3, behavior_count=null}` —— 即"当日行为次数为空"。RED 日志逐字：
`Expecting code to raise a throwable.`（`.verify/v3-stage3/s3-09-export-row-shape/red/java-reader-red.log`）。

---

## 2. 语义声明（本轮冻结）

| 项 | 冻结取值 | 依据 |
|---|---|---|
| 「表形」定义 | 对**每一行**：键集 **==** `MetricAdsCatalog.require(mysqlTable).columns()`（集合相等，不看顺序） | §12.5 L528/L529「表形」 |
| 缺声明列 | **拒绝整行**（`IOException`，逐行点名 `缺列` + 缺失列集合 + 该表声明列集） | 同上；"看起来成功"是比失败更坏的结局 |
| 非白名单列 | **拒绝整行**（既有行为，未变） | 既有实现（S3-06 前即存在） |
| 键在、值为 `null` | **保留**（`dau:null` 仍是"当日没有该指标值"） | 指导书 L148「空值规则」 |
| `{}`（空行/全缺列） | **拒绝**（旧行为会补成全 null 行并写入） | 同「缺声明列」 |
| 校验位置 | `AdsExportReader.toRow`（**读取即校验**，JSONL 解析的唯一所有者） | §12.3 L512 单一口径；避免第二个解析者 |
| 失败面 | **不新增规则码**：仍走 `MetricPublisher` 既有 catch → `markFailed("MP_ADS_WRITE_FAILED: …")` + `compensate` + `fail(..., "MP_ADS_WRITE", …)` | 见 §3「为什么不新开规则码」 |
| 作用范围 | 8 张 ADS 表**逐表逐行**（概览/销售趋势/漏斗/活跃/商品热度/转化/画像/质量） | 同 S3-06「全量逐表」而非抽样（S3-06-R-2 已登记该口径差异） |

---

## 3. 为什么是 A 类（逐门核对）

| 门 | 是否触发 | 理由 |
|---|---|---|
| ① DROP TABLE/COLUMN | **否** | 无任何 DDL 变更（本轮**零**迁移文件） |
| ② 改已有字段类型/既有业务语义 | **否** | 不改列、不改码、不改指标口径；改的是**残缺制品的处置方式**（修复 → 拒绝）。原"补 null"从来不是被设计批准的语义，只是实现兜底；且它会让"缺列"冒充"空值"，恰恰**破坏** L148 的空值规则 |
| ③ 改已发布 Flyway migration | **否** | 一个迁移文件都未新增/未修改 |
| ④ 写/迁移正式 3306 数据 | **否** | 本轮 **0 次连库**（3306/3307 均未触碰） |
| ⑤ 切 ACTIVE | **否** | 未运行发布作业 |
| ⑥ 改 `contract-specs/**` | **否** | 未改；已检索（见 §7） |
| ⑦ 改 V3.0 总体架构 | **否** | 不加组件、不改分层 |
| ⑧ 改正式项目范围 | **否** | 只做设计已写明的校验格 |
| ⑨ 删除已发布功能 | **否** | 未删任何规则码/校验/列；`MP_ROW_SHAPE_CONSISTENT` **保留**（**冗余守卫不删**：它仍守着"同一批行键集一致"，且删除属门⑨邻域） |
| ⑩ 引入未规划大型组件 | **否** | 只用一个既有 JDK 集合做差集 |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 见下 |

**为什么不新开规则码（候选方案对比）**：

- **方案 A（本轮采用）**：读取器拒绝 + 走既有 `MP_ADS_WRITE_FAILED`/`MP_ADS_WRITE` 失败面。
  优点：① JSONL 解析**只有一个所有者**（§12.3 L512），不引入第二个解析者；
  ② 失败发生在**写入该表之前**（读失败 → 该表零行落地），语义上比"先写再判"更干净；
  ③ 与既有 catch 的 `compensate`/`markFailed` 路径**完全复用**，不新增失败分支（反熵：不增回退面）。
- **方案 B（未采用）**：新增 `MP_EXPORT_ROW_SHAPE` 规则码 + 在 `MetricPublishValidator` 里判。
  代价：校验要么**重复解析一遍 JSONL**（第二个解析所有者、两处必然漂移），要么把读取器的违规
  **回传**给发布器（改 `readTable` 返回类型 / 加回调），把"读"和"判"拆到两个类；
  且**清单级的表形已由 `MP_MANIFEST_TABLES` 承担**（声明列 ↔ 白名单），新码只会成为同一断言的
  第二个所有者。⇒ 记为 **S3-09-R-1**（若总控认为发布报告里需要一个**独立可检索的码**，
  请批注；届时应同时定"谁继续做 JSONL 的唯一解析者"）。

**同样**，本轮**未**顺手扩 `MP_ROW_SHAPE_CONSISTENT` 的语义（那会让同一码的含义随实现漂移，
属门②邻域；S3-06 已确立"一个 check 一个码、旧码语义不动"的既有约定）。

**Anti-Entropy Declaration（本轮）**

- 删除类别：`code-retirement`（内部兜底分支 —— `AdsExportReader` 的补列循环）
- 旧路径：`for (String column : spec.columns()) row.putIfAbsent(column, null);`
- 新唯一所有者：`MetricExportJob`（生产者承诺每行写全列，`ignoreNullFields=false`）
  ＋ `AdsExportReader.toRow`（消费者**校验**该承诺，不再代跑）
- 预期保留行为：显式 null 值原样传递；非白名单列仍拒绝；发布失败面/补偿路径不变
- 预期退役行为：「缺声明列」不再被静默补成 null
- External Boundary Touched：**no**（无外部契约、无已发布 API 语义变更）
- Source-of-Truth Data Risk：**none**（零连库、零迁移）
- Retirement Decision：`delete-first`（内部兜底，无外部依赖证据需求）
- Non-edits（**刻意不删/不改**）：`MP_ROW_SHAPE_CONSISTENT`（已发布规则码，删除属门⑨邻域）、
  `MP_MANIFEST_TABLES`（清单级表形，职责不同且仍需）、任何迁移文件、任何 DDL
- 残留风险：**S3-09-R-2**（发布级后果未测）、**S3-09-R-5**（冗余码收敛待裁决）
  ⇒ 本轮完成度表述为「**有界收敛（bounded mitigation）**」，**不是**"发布链表形已全链验证"

---

## 4. 实现面（纯 Java、零迁移、零 Spark 改动）

1. **`analytics-server/metric-analysis/.../publish/AdsExportReader.java`**
   - 删除补列循环 `for (String column : spec.columns()) row.putIfAbsent(column, null);`；
   - 新增差集校验：`missing = spec.columns() − row.keySet()`，非空即 `IOException`
     （消息含**行号**、**文件**、**缺失列集合**、**该表声明列集**，便于运维定位是哪个制品/哪一列）；
   - 类 KDoc 增「表形（schema）双向严格」段，逐字写明「键缺失」与「键在、值为 null」的区别，
     以及旧行为为何会让残缺导出静默发布成功。
2. **`analytics-server/metric-analysis/.../publish/MetricPublishValidatorTest.java`**
   - `+2`：`exportReaderKeepsExplicitNullValue`（显式 null 仍可读 —— 防"顺手把 null 也拒了"的过度收紧）、
     `exportReaderRejectsMissingDeclaredColumn`（缺列拒绝且**点名列名**）；
   - 原用例 `exportReaderReadsRows` 的 `@DisplayName` 从「…/**缺列补齐**/null 保留」改为
     「声明列齐备的行 + null 保留」（其数据本就列齐，断言未变）。原「非白名单列 → 拒绝」用例**未动**。
3. **`scripts/run-tests.ps1`**：基线 `analytics-server 906 → 908`，并在基线注释区加 S3-09 段
   （依据、旧行为、为何 906→908）。

**兼容性**：无列/无迁移/无契约变更；`AdsExportReader` 全仓调用点仅
`MetricPublisher.java:145`（生产唯一）与测试 3 处；`docs/acceptance/**` 内的历史留档脚本未纳入编译。

---

## 5. 反熵守卫与「无回归」证据

- **RED（先红后绿，归因正确）**：新用例在**未改实现**时失败，失败原因正是"没有抛异常"——
  `.verify/v3-stage3/s3-09-export-row-shape/red/java-reader-red.log`
  （`Tests run: 13, Failures: 1` / `Expecting code to raise a throwable.`）。
  ⚠️ 如实说明：旧实现**没有任何用例钉住"补列"行为**（原用例的数据列是齐的），
  所以本轮 RED **仅 1 条红**，不是"旧行为被测试保护、改它必然多处红"。
- **GREEN（点名套件）**：`MetricPublishValidatorTest` `Tests run: 13, Failures: 0, Errors: 0`（13/13）
  —— `.verify/v3-stage3/s3-09-export-row-shape/green/java-reader-green.log`。
- **全量档反证「无人依赖旧补列行为」**：`default` 档 analytics-server 全量 **908** 条里
  **没有任何一条**因本次严格化变红（唯一红是既有环境性 manifest 用例，见下）。
  这是一条**实测**结论：既有测试/夹具**没有**依赖 `putIfAbsent(null)` 的地方。
  同时说明：`MetricPublishValidatorTest`/`MetricPublishValidatorSeverityTest` 的清单夹具写的是
  `{"stub":1}` 占位文件，但它们**不经过读取器**（直接传行 Map），故不受影响、也未改 —— 属**夹具简化**，
  非"绕过校验"。
- **双档门禁（fresh 真跑）**：
  - **spark 档 `[PASS exit=0]`**：`Total number of tests run = 226`、`套件 27`、
    `succeeded 226, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、
    `新写=True`、`JDK8=True`、`tests=226 MATCH`（`gate/spark-console.log`）。
    本轮**未改任何 Spark 文件**，此档为"未波及"的对照证据。
  - **default 档计数全 MATCH、`[FAIL exit=7]`**：analytics-server `exit=1 Tests run: 908 (F=1 E=0 S=1)`
    （模块明细 `90+350+163+67+92+146`）、mall `13`、generator `106`、三棵树 **1027**（基线 1027）、
    `tests=908/13/106 MATCH`；**唯一红**＝已登记环境性
    `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（platform-app F=1，与 S3-07/S3-08 同一枚；
    本轮**未修、未复制 manifest、未用开关掩盖**）（`gate/default-console.log`）。
  - **一次真实抖动如实留痕**：第一轮 default 档（`.verify/…/gate/run1/default-console.log`）
    除该环境性红外，**另有一条 `synthetic-data-generator` 红**：
    `ReferenceMallHttpAdapterTest.credentialIsSentOnPublicRoutesToo`（`F=1`，`/api/v1/admin/products=FAILED`）。
    该套件自建 `HttpServer`（`127.0.0.1:0` 随机端口）做真实 HTTP 探测，与本轮改动**无任何调用关系**
    （`synthetic-data-generator` 不依赖 `analytics-server`）；**同机第二轮即 `F=0 106 MATCH`**。
    归因：**负载/超时敏感抖动**，不是本轮引入。**未做**的是"在隔离负载下重复 N 次统计抖动率"，
    故只能表述为「第二轮复现为绿、与本次改动无调用关系」，**不得**表述为"已证明该用例稳定"。
- **基线对账**：`analytics-server 906 → 908`，增量 **+2** 与本轮新用例数**逐条相等**，无套件消失
  （模块明细 `…+67+…` 中 `metric-analysis 65 → 67`）。

---

## 6. 未做与待批注（不阻塞，编号 `S3-09-R-n`）

- **S3-09-R-1｜失败面是否要独立规则码**：当前"缺列"以 `MP_ADS_WRITE_FAILED`（DB `failure_reason`）
  + 报告码 `MP_ADS_WRITE` 呈现，**没有**一个 `MP_*` 规则码专门表示"表形不符"。
  若总控要求发布报告/质量大盘里可**独立检索**该失败类型，请批注（届时按 §3 方案 B 的代价评估：
  需指定 JSONL 的唯一解析者，避免两处解析漂移）。
- **S3-09-R-2｜发布级后果未测**：本轮只证到**读取器拒绝**（单测）。"拒绝 ⇒ `MetricPublisher` 记
  `MP_ADS_WRITE_FAILED` ⇒ `compensate` 删净本次已写行 ⇒ ACTIVE 不动"这条链，唯一覆盖者是
  `MetricPublisherMySqlIT.publishActivationAndFailureCompensation`，而该 IT **从未运行**（需真 MySQL，
  默认档/隔离档均不跑）⇒ 状态 **未测**。本项**不得**被表述为"已验证失败保旧"。
- **S3-09-R-3｜历史留档未重跑**：`docs/acceptance/m3-step8-parity-20260912/raw/post/export-import-rehearsal/build/RehearsalRunner.java`
  是历史演练脚本（非构建输入）。其产物是在**宽松读取器**下生成的，**未重跑**，
  故**不能**用它来声明"新严格校验对那批制品同样通过"。
- **S3-09-R-4｜仍未关闭的 §16.2 缺口（明确不冒充）**：审计 `v2-completeness-audit.md:344`「ODS schema 受支持」
  与 `:519`「ADS 关系式（GMV≥净销售≥0、UV≤PV）」**本轮均未做**；后者另有一条已登记的口径冲突
  （逐业务日 `net_sale` 因跨日退款**可以合法为负**；「支付用户 ≤ 浏览用户」与设计 §11.3 L441/指导书 L152
  「宽松漏斗**不得**强制单调」相抵），属**需裁定项**，不得当作普通 A 类实现。
- **S3-09-R-5｜`MP_ROW_SHAPE_CONSISTENT` 现已近乎恒真**：生产路径上的行都经读取器校验，
  该码对 8 张表不再有可失败的输入（测试/IT 直接传 Map 时仍有效）。**未删**（门⑨邻域），
  仅登记：若后续要收敛重复所有者，应由总控决定合并/降级。

---

## 7. 证据清单与检索记录

- `.verify/v3-stage3/s3-09-export-row-shape/red/java-reader-red.log`（RED：`Expecting code to raise a throwable`）
- `.verify/v3-stage3/s3-09-export-row-shape/green/java-reader-green.log`（GREEN：`Tests run: 13, Failures: 0`）
- `.verify/v3-stage3/s3-09-export-row-shape/gate/spark-console.log`（spark `[PASS exit=0]`，226/27 套件）
- `.verify/v3-stage3/s3-09-export-row-shape/gate/default-console.log`（default 计数全 MATCH；`[FAIL exit=7]`；
  analytics-server `908 (F=1 E=0 S=1)`；唯一红＝环境性 manifest 用例）
- `.verify/v3-stage3/s3-09-export-row-shape/gate/run1/default-console.log`（第一轮：generator 抖动 1 红，
  第二轮复现为绿，见 §5）
- `.verify/v3-stage3/s3-09-export-row-shape/gate/`（`spark-jobs.log`、`spark-jdk-version.log`、
  `default-analytics-server.log`、`default-mall-simulator.log`、`default-synthetic-data-generator.log`）
- 交叉引用：F-42（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。
- **检索记录（未触碰声明）**：`contract-specs/**` 内**无** `AdsExportReader`/`putIfAbsent`/「缺列」任何引用；
  guidance/design V3.0 两份正式文档**一字未改**；已发布迁移（`db/metric/V1–V10`、`db/meta/V1–V24`）**字节未动**；
  `warehouse/ddl/**`、`spark-jobs/**` 本轮**未改**；8 张 ADS 表清单、任何质量规则码/阈值**未增删未改**。

---

## 8. 判定与停工条件

**判定：A 类（修正实现遗漏 + 补齐设计明确要求的校验）⇒ 登记后自主实施，不停工。**
本轮**无**需另择窗口的动作：零迁移、零连库、零 ACTIVE 切换、零正式数据写入。
唯一可能需要总控裁定的是 **S3-09-R-1（是否单开规则码）** 与 **S3-09-R-4（§16.2 两条仍未做的口径冲突）**，
两者**都不阻塞**本轮交付，按「默认自主连续开发」继续滚动下一开发项。
