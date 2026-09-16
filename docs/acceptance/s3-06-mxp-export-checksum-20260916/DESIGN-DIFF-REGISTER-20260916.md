# S3-06 设计差异登记（2026-09-16）

> 提交：`feat(publish): S3-06 mxp 导出制品 checksum 贯通（Spark 产出摘要 → 清单 checksum 键 → 发布侧 BLOCKING 重算比对）`，
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工／哪些必须停工」。

---

## 0. 一句话结论

设计 §12.5 **L528** 的发布顺序逐字写「Spark ADS暂存 → 数据质量通过 → Hive不可变发布制品 →
**mxp 导出 + manifest/checksum** → MySQL BUILDING/staging → 表形/行数/定义版本/**内容验证** → …」，
**L529** 又把「内容验证」列为切 ACTIVE 前的必过步骤；指导书 §7 阶段3 **L151** 要求
「导出 ADS 制品核 **schema/行数/checksum**」。
**此前状态**：`mxp` 清单每表只有 `hiveTable/mysqlTable/rowCount/columns/hivePath/exportFile`
—— 只有**路径与行数，没有任何内容摘要**（缺口早已登记：`docs/audit/v2-completeness-audit.md:213`
「无 checksum（仅路径 + 行数）」、`:358`「发布层 行数一致 + 抽样 checksum｜部分｜**无抽样 checksum**」）。
于是「行数相同、内容被截断或错位搬运」的导出制品在发布侧**没有任何判据**：
`MP_EXPORT_FILES` 只看文件在不在、`MP_ADS_ROWS_MATCH` 只看行数，两者都会放行。
本轮把摘要**贯通三处**：Spark 产出（`MetricExportJob.crc32`）→ 清单键（`checksum`）→
发布侧唯一所有者逐表重算比对（新规则码 `MP_EXPORT_CHECKSUM`，**BLOCKING**）。
**判定：A 类（实现遗漏 + 纯加性）**，不触发 11 条破坏性决策门中的任何一条
⇒ 按「默认自主连续开发」直接实施、测试、提交。

**本轮关闭既有登记项 R-4**（S3-05 登记：「§12.5 L528 `mxp` checksum：未做（既有登记项）」）。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §12.5 **L528** | 「Spark ADS暂存 → 数据质量通过 → Hive不可变发布制品 → **mxp导出 + manifest/checksum** → MySQL BUILDING/staging → 表形/行数/定义版本/内容验证 → 单数据库事务：旧ACTIVE归档，新快照ACTIVE → API/页面/AI固定读取新snapshotId」 | 清单新增 `checksum`；与其**同一步骤**内的「内容验证」在本轮由发布侧新 check 承担 |
| 设计 §12.5 **L529** | 「表形 / 行数 / **金额 / 定义版本 / 内容验证**」 | 「内容验证」＝逐表重算摘要比对（`MP_EXPORT_CHECKSUM`），与既有「表形/行数」两类判据并列而非替代 |
| 设计 §12.5 **L534** | 「MySQL ACTIVE 是在线发布权威；Hive 已准备好但导入失败时维持旧 MySQL 指针」 | 本 check 落在 **manifest 阶段（还没写任何行）** ⇒ 失败即不进入导入，天然满足"不先切后导" |
| 指导书 §7 阶段3 ①（L148） | 「对每个指标固定粒度、分子分母、时间窗口、金额/退款口径、**空值规则和版本**」 | 摘要算法与形式在本文件 §2 **冻结成口径**（版本化约束：改算法必须发新版本，见 §2 与 §4） |
| 指导书 §7 阶段3 ④（L151） | 「导出 ADS 制品核 **schema/行数/checksum**，MySQL 暂存验证后切 ACTIVE」 | schema＝`MP_MANIFEST_TABLES`、行数＝`MP_ADS_ROWS_MATCH`、**checksum＝本轮新增** |
| 设计 §16.4（审计 :358 引） | 「发布层 行数一致 + **抽样 checksum**」 | 实现取**全量**（8 张表逐表整文件），严于「抽样」；`rows()` 为空表也照算（空文件摘要 `"0"`） |
| 指导书 §12 **L271** | 「Code Agent 只提交设计差异请求，不自行修改两正式文档」 | 本轮**未改** guidance/design 任何字节 |
| 设计 §9.3 **L339** | 「`analytics_metric` 中 8 张 `_m` 是已证实体」 | 表数仍 **8**；清单结构只**加键**，不增删表 |
| 设计 §11.5 **L455** | 「商品排行按热度/销量/金额并有稳定次序键」 | 与本轮**无关**（未动排序）；摘要按**字节**计算，与行序无关，故行序稳定与否都不影响摘要语义 |

**失败样例（改前真实状态）**：`MetricExportJob` 写完 `ads_operation_overview_m.jsonl` 后只回读行数
（`MXP_EXPORT_ROWS`）。若该文件在落盘后被截断到「仍是合法 JSONL 行、但少了几列」或
**另一张表的内容被误写进本文件名**（行数恰好相同），则：清单 `rowCount` = 文件行数 ✅、
`MP_EXPORT_FILES` 文件存在 ✅、`MP_ADS_ROWS_DB_MATCH` 库内行数 = 清单行数 ✅ ——
**三关全过，页面上的核心指标却是错的**。这就是 L528「manifest/**checksum**」与 L529「**内容验证**」
要防的那一类失败。

---

## 2. 口径声明（本轮冻结）

| 要素 | 本轮冻结取值 | 依据 / 证据 |
|---|---|---|
| 摘要算法 | **CRC32**（Java `java.util.zip.CRC32`，多项式 `0xEDB88320`） | 与 landing 侧 `ingestion-manifest.v1` **同一写法**（`IngestionService` 的 `Long.toHexString(crc.getValue())`）⇒ 同一制品在两条链路上不会出现两种"校验和"概念 |
| 覆盖范围 | 该导出文件**全部原始字节**（流式读满整个文件，缓冲 64 KiB） | 「内容验证」要求覆盖内容而非前缀；只读首个缓冲区会让大文件的尾部损坏漏检 |
| 输出形式 | `java.lang.Long.toHexString(...)`：小写十六进制、**无前导零**；空文件 = `"0"` | 与 ingestion 清单逐字同形式；`MetricExportChecksumSpec` / `MetricExportManifestChecksumTest` 均以正则 `^[0-9a-f]{1,8}$` 钉住 |
| 已知向量（两侧同断言） | `""`→`0`；`hello`→`3610a686`；`123456789`→`cbf43926`（CRC32 经典校验值）；`{"stub":1}\n`→`195ac066`；`{"stub":2}\n`→`1898aa51` | 两侧各有一份**独立于实现**的期望值；`cbf43926` 是 CRC-32/ISO-HDLC 的公开 check value，可离线复核 |
| 清单字段名 | `checksum`（表项级，与 `rowCount`/`exportFile` 同级） | L528 写的就是 `manifest/checksum` |
| 清单字段必填性 | **必填**：`MetricExportManifest.read` 缺该字段即 `IOException("发布清单缺少字段 checksum")` | §16.4「缺证据=假成功」；把缺摘要当"没要求"会让本规则静默失效 |
| 缺失文件的语义 | 文件缺失 ⇒ `MP_EXPORT_FILES` **与** `MP_EXPORT_CHECKSUM` **各自点名**（前者"不在"，后者"无法核对内容"） | 两个口径回答不同问题，不互相掩盖（测试 `missingExportFileReportedByBothRules` 钉住） |
| 判定属主 | **发布侧唯一所有者** `MetricPublishValidator.manifestChecks`（规则码 `MP_EXPORT_CHECKSUM`） | §12.5 L528 的 checksum 是"导出侧交付物"，"是否可信"必须在**写库前**由发布侧判定；Spark 侧只产出摘要、**不自行判定**自己可信 |
| Spark 侧是否也判 | **否**（不新增 Spark 侧 passed/failed check） | 自己给自己发合格证不构成独立验证；`mxp` 侧仍只产出 `MXP_EXPORT_ROWS`/`MXP_EXPORT_COMPLETE` 两条既有证据 |
| 严重度 | **BLOCKING**（`RuleSeverity` 登记 + `quality_rule_definition` 种子） | 摘要不匹配意味着"即将写入库的内容不是清单声称的内容" ⇒ 与 `MP_EXPORT_FILES` 同级，不得降级为观察项 |
| 阈值 | **无阈值**（`severity_mode=FIXED`、`threshold_json=NULL`） | 摘要比对是**布尔相等**判定，不存在"容差"；设阈值即等于允许内容漂移 |
| 是否落库 | **不落** `data_quality_result`（与其余 `MP_*` 码一致：只进发布报告证据） | 既有约定：`MP_*` 是发布事务内的对账证据，发布报告即其载体 |
| 版本化 | 算法/字段名/形式变更 = **口径变更**：必须发新规则码版本（`QualityRuleDefinition.version`）并在本登记中显式裁决 | 指导书 L148「空值规则和**版本**」的同类要求；本轮首版 `version=1` |
| 兼容性 | 历史 `_export.json`（无 `checksum` 键）**将被拒绝读取** | **刻意的 fail-closed**：清单是**每次发布当轮生成**的中间制品，不是长期存档；见 §6-N-3 的部署注意 |

---

## 3. 本轮实施面（含属主与守卫）

| 所有者 | 文件 | 变更 |
|---|---|---|
| Spark 生产者（摘要算法真源） | `spark-jobs/src/main/scala/.../job/MetricExportJob.scala` | 新增 `def crc32(spark, file): String`（Hadoop `fs.open` ＋ 64 KiB 缓冲流式读满；文件不存在即抛 `FileNotFoundException`）；逐表清单行追加 `"checksum":"$checksum"`；KDoc 写明依据（§12.5 L528/L529、L151）、与 landing 同口径、为什么是 CRC32 而不是 SHA-256、以及**不自行判定**的边界 |
| Spark 行为守卫 | `spark-jobs/src/test/scala/.../MetricExportChecksumSpec.scala`（**新增 3 条**） | ① CRC32 已知向量（含空文件 `"0"`）；② ~200 KB 实文件摘要 == 测试侧独立 `java.util.zip.CRC32` 且形式合规；③ 内容敏感性（同长度不同内容 ⇒ 摘要不同） |
| Spark 链路守卫 | `.../DwsAdsChainExecSpec.scala`（**+1 条**） | 真跑 `sci→odl→dim/bdw/tdw→dws→ads→mxp` 主链后：清单 `checksum` 的键集合 == 8 张表，且逐表值 == 独立重算该 JSONL 真实字节的 CRC32（`parseManifest` 由 4 元组扩为含 `checksum`） |
| Java 清单契约 | `analytics-server/metric-analysis/.../publish/MetricExportManifest.java` | `TableExport` 追加 `checksum`；`read()` 走既有 `required(...)` ⇒ 缺失即 `IOException`；新增 `static String crc32(Path)`（**Java 侧摘要算法唯一实现**，`Files.newInputStream` ＋ 64 KiB 缓冲） |
| Java 判定属主 | `.../publish/MetricPublishValidator.java` | `manifestChecks` 末尾新增 `MP_EXPORT_CHECKSUM`（BLOCKING）：逐表重算并与清单比对，`detail` 逐条给出「表名 清单=… 实算=…」；文件不可读时计为不匹配（不假装通过） |
| 规则目录（唯一权威） | `analytics-server/platform-common/.../metric/QualityRuleCatalog.java` | 新增常量 `RULE_MP_EXPORT_CHECKSUM` ＋ `fixed(..., STAGE_METRIC_PUBLISH, BLOCKING, "导出制品内容摘要 = 清单 checksum（逐表重算，防行数相同但内容被截断/错位搬运）")`；目录 35 → **36** 条定义 |
| 严重度登记 | `.../metric/RuleSeverity.java` | `REGISTERED` ＋ `of()`（BLOCKING）＋ `rationale()` 三处同步；rationale 写明「行数与文件存在性都可能同时正常，只有内容验证能发现」 |
| 契约种子（落库） | `platform-app/src/main/resources/db/meta/V23__quality_rule_publish_export_checksum.sql`（**新增加性迁移**） | 单条 `INSERT IGNORE`，追加 `MP_EXPORT_CHECKSUM` 一行；头部写明依据、为什么"新增迁移而非改 V19"（V19 已发布、门③）、checksum 推导过程、以及「**本迁移在真库上的执行状态：未执行**」 |
| 守卫（Java 清单解析） | `metric-analysis/src/test/java/.../MetricExportManifestChecksumTest.java`（**新增 5 条**） | `crc32` 已知向量 / 20000 行实文件 == 独立 `CRC32` ＋ 形式 / 内容敏感性 / 缺 `checksum` 清单被拒（异常信息含 `checksum`）/ 带 `checksum` 清单原样解析 |
| 守卫（发布侧判定行为） | `.../MetricPublishValidatorTest.java`（**+2 条**，夹具补真摘要） | ① 导出内容被改写而**行数不变** ⇒ `failedRules` **恰好**为 `MP_EXPORT_CHECKSUM`（不牵连 `MP_EXPORT_FILES`）、证据含表名与两侧摘要、`blocked==true`；② 制品缺失 ⇒ `MP_EXPORT_FILES` 与 `MP_EXPORT_CHECKSUM` 各自点名；另在 happy path 断言该 check 真的存在且 `passed/severity` 正确 |
| 守卫（严重度路径一致性） | `.../guard/RuleSeverityPathConsistencyTest.java` | **无需改断言**（遍历目录每一条定义，自动覆盖新码）；仅更新 KDoc 里的「实测 35 条」→「当前 36 条」 |
| 守卫（SQL↔Java 零漂移） | `platform-app/src/test/java/.../QualityRuleVersionMigrationScriptTest.java` | `seedRows()` 改为**多迁移并集**（新增 `V23`，`SEED_SCRIPTS`），35→36；新增 1 条「V23 只追加种子：单条 `INSERT IGNORE`、不建表/不改列/不删行、不碰其它表、写明未执行」 |
| 守卫（登记码算术） | `platform-common/.../RuleSeverityTest.java` | `PUBLISH_VALIDATOR_CODES` 15→**16**、总计 33→**34**、目录 `34+2=36`（并注明新的 `+2` 是既有的两条金额校验码） |
| 真库 IT 夹具 | `metric-analysis/.../MetricPublisherMySqlIT.java` | 夹具清单写入 `checksum`（用 `MetricExportManifest.crc32(file)`，保持夹具与契约对齐以免无辜变红）；**本轮该 IT 未运行** |
| 基线 | `scripts/run-tests.ps1` | spark `208→212`、analytics-server `887→895`（含逐条来历说明） |

**兼容性承诺（可复核）**：清单原 6 个字段的名字/含义**一格未动**，新键为**追加**；
`metric V1`–`V8`、`meta V1`–`V22` 迁移文件**未改一个字节**（`V23` 为**新增**文件，
`V19` 种子逐字节未动）；`MetricAdsCatalog.ALL` / `MetricAdsSpec.TABLES` 仍 **8** 张表；
既有 `MP_EXPORT_FILES` 的判据与 rationale **未动**（新规则码另开一行，不挤进旧码）。

---

## 4. 反熵守卫：为什么新增一个规则码，而不是复用 `MP_EXPORT_FILES`

**被否方案（a）**：把摘要比对塞进现有 `MP_EXPORT_FILES`（「导出文件齐备」）。
**否掉的理由**：① 该码在目录里的 rationale 是「导出文件齐备」，把"内容不符"也记成它，
会让同一条规则码的**语义随实现漂移**（同一个 passed=0 既可能是"文件不在"也可能是"内容被改"），
而 §12.3 L512 要求每条规则记录的是**可判读的单一口径**；② 该码名已被 4 处判据/文档引用，
语义扩张属"改既有业务语义"（门②邻域）；③ 本项目既有约定就是**一个 check 一个码**
（`MP_MANIFEST_TABLES`/`MP_HIVE_PATH_PINNED`/`MP_EXPORT_FILES` 三者互不合并）。
**采用的方案（b）**：新增 `MP_EXPORT_CHECKSUM`，三处登记（目录 / `RuleSeverity` / V23 种子）一次做齐。
**为什么必须"登记再产出"**：§7.3.1 line 524 规定未登记规则码一律「停止发布并报未登记规则」——
若只加 Java 目录不落库，新码第一次产出就会被读侧判为未登记而整链翻红；故 V23 与代码同轮落地。

**三方一致守卫（本轮建立）**：`MetricExportJob.crc32`（Spark 产出）→ 清单 `checksum`（交付物）→
`MetricPublishValidator`（重算比对）。三者由**两条独立测试**各证一半：
① Spark 侧 `DwsAdsChainExecSpec` 真跑链路后**独立重算** JSONL 字节摘要与清单比对（证明产出正确）；
② Java 侧 `MetricPublishValidatorTest` 故意**改内容不改行数**，证明判定真的会红（证明判定有效）。
**没有**引入第二份实现：Java 侧唯一的算法实现是 `MetricExportManifest.crc32`（Spark 侧是 Scala，
两语言无法共享代码，故用**同一批已知向量**在两侧各自钉住 —— 向量相同即口径相同）。

---

## 5. 为什么判定为 A 类（不触门），逐门核对

| 门 | 是否触发 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | 无任何 DDL 删除；Hive/MySQL 列集合**未动** |
| ② 改已有字段类型或**既有业务语义** | **否** | 清单只**追加** `checksum` 键，原 6 字段语义未动；既有规则码的档位/阈值/rationale 未动（新码另开一行） |
| ③ 改已发布 Flyway migration | 否 | `meta V1`–`V22`、`metric V1`–`V8` **字节未动**；`V23` 为**新增**文件（且只 `INSERT IGNORE`） |
| ④ 写/迁移正式 3306 数据 | 否 | 本轮 **0 次连库**；`V23` 未在真库执行（头部已写明） |
| ⑤ 切 ACTIVE | 否 | 未涉及；未改发布事务 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 已全目录检索 `_export.json`/`checksum`：**0 命中** ⇒ 该中间制品不属 `contract-specs/**` 覆盖范围 |
| ⑦ 改 V3.0 总体架构 | 否 | 表/层/链路/表数不变；未新增组件、未新增第 9 张 ADS 表 |
| ⑧ 改正式项目范围 | 否 | 属 §12.5 L528/L529 ＋ 指导书 L151 既有要求，未新增范围 |
| ⑨ 删除已发布功能 | 否 | 只增不减（清单加键、加 check、加规则码） |
| ⑩ 引入未规划大型基础组件 | 否 | 用 JDK 自带 `java.util.zip.CRC32`，**未加任何依赖** |
| ⑪ 两种方案造成重大长期架构分叉 | 否 | 「在发布前对导出制品做摘要核对」是本设计既定的**唯一**方向（L528 逐字写着）；被否的只是"塞进旧码"这一实现选择，不构成架构分叉 |

---

## 6. 未实测边界与待批注项（不得越界表述）

1. **未跑真实 `spark-submit`／未连 Hive metastore**：全部证据是 Scala `local[1]` ＋
   `catalogImplementation=in-memory` ⇒「本地测试通过」**不得**表述为「在产通过」。
2. **两个真库 IT 本轮未运行**（`MetricPublisherMySqlIT` 夹具已补 `checksum`、`MetricAdsMySqlIT` 未改）：
   两者**均无本轮实测证据**；`isolated` 档（WSL 3307）无监听、本轮未跑。
3. **`V23` 未在真库执行**：`QualityRuleVersionMigrationScriptTest` 只证「迁移文本 ↔ Java 目录零漂移」，
   **不证明**该行已在任何库生效；上线前需按部署流程执行（并注意 `quality_rule_definition` 的唯一键
   `(source_scope, rule_code, version)` 使重复执行为幂等 `INSERT IGNORE`）。
4. **CRC32 的能力边界（必须写清）**：它防的是**传输/落盘过程中的意外损坏**（截断、错位、串写），
   **不是**抗蓄意篡改的密码学承诺（能构造碰撞）。选它是因为要与 landing 侧 `ingestion-manifest.v1`
   同口径；若总控要求抗篡改，应整体换 SHA-256 并**同时**改两条链路（属口径变更，见 §2 版本化）。
5. **摘要只覆盖 `mxp` 导出制品，不覆盖 Hive 制品本身**：L528 的「Hive 不可变发布制品」当前以
   `snapshot_id` 钉住（`MXP_SNAPSHOT_PINNED`）＋ 分区证据（`PartitionEvidence`）为不可变性判据，
   **没有**对 Hive 数据文件本身做摘要 ⇒ 登记为 **S3-06-R-1**（见下）。
6. **无门禁证明"清单摘要 == 真实内容"在运行期恒成立**：本轮的证明结构是「生产者测试（Spark 真跑）」
   ＋「消费者测试（故意改内容必红）」两侧各证一半，**不构成**单点强约束；
   跨语言同口径靠**同一批已知向量**维持（两侧各有一份），向量若被单侧改动会立刻在那一侧变红。
7. **新 check 暂无下游消费方**：发布报告会带上它，但 `AnalysisService`/前端/AI 证据**均未消费**
   `MP_EXPORT_CHECKSUM` ⇒ 归**阶段4/5**（与其余 `MP_*` 码同一状态）。

**待总控批注（均不阻塞本轮）**：

- **S3-06-R-1｜Hive 发布制品本身的摘要（§12.5 L528「Hive 不可变发布制品」的后半段）未做**：
  现状以「分区路径钉住 snapshot_id ＋ 分区行数证据」间接保证"读到的就是本次快照"，
  未对 Hive 数据文件（`_SUCCESS` + parquet 文件集）做内容摘要。是否需要（以及是否复用本轮 CRC32 口径，
  还是等 `ads_category_sale`/`ads_region_sale` 落地时一并做）请批注。**本轮不做。**
- **S3-05 登记项 R-4（§12.5 L528 `mxp` checksum）→ 本轮关闭**：已贯通三处并有两侧测试。
  注：S3-05 的备注「若将来启用 checksum，本表的期望值需按新列集合重算」**已自然满足** ——
  摘要按**字节**计算、随清单当轮产出，不做跨版本历史值比对 ⇒ 无历史期望值需要重算。
- **S3-06-R-2｜"抽样 vs 全量"口径**：设计 §16.4 的原话是「抽样 checksum」，本轮实现为**全量逐表**。
  全量更严且成本可控（8 张小表、64 KiB 缓冲流式读），故不认为需要裁定；若总控要求与原文逐字一致
  （改为抽样）请指示。**本轮按全量实现并登记。**
- **S3-06-R-3｜历史 `_export.json` 不再可读**：S3-06 之前落盘的清单（无 `checksum` 键）在发布侧会
  直接 `IOException` 拒绝。设计上清单是**每轮生成**的中间制品，故属预期 fail-closed；
  但若存在"用旧导出目录补发布"的运维习惯，需在部署说明中提示**必须重新跑 `mxp`**。**本轮只登记，不改部署脚本。**

> **编号作用域说明**：本登记的待批注项一律用 **`S3-06-R-n`** 前缀。`docs/PROJECT_STATUS.md`
> 的待批注表里另有 S3-04 起沿用的裸 `R-2/R-3/R-4`（漏斗下钻降级、`cart_add_cnt`、`cart_rate`
> 未进概览 API），与 S3-05 登记里的 `R-1…R-6` **不同源** ⇒ 引用时必须带前缀，避免混号。

---

## 7. 本轮证据（本地，不入库）

`.verify/v3-stage3/s3-06-mxp-export-checksum/`：

- `red/spark-red.log`（Spark RED：编译失败 `value crc32 is not a member of object
  com.graduation.analytics.job.MetricExportJob`，6 处调用点 —— 证明新 API 在改前确实不存在）
- `red/java-red.log`（Java RED：`COMPILATION ERROR`，`找不到符号`×7，位置均为
  `com.graduation.analytics.metric.publish.MetricExportManifest`（`crc32` / `checksum`））
- `green/java-targeted.log`（点名 6 个套件：`RuleSeverityTest` 14、`MetricExportManifestChecksumTest` 5、
  `MetricPublishValidatorSeverityTest` 10、`MetricPublishValidatorTest` 11、`RuleSeverityPathConsistencyTest` 4、
  `QualityRuleVersionMigrationScriptTest` 7，**F/E 全 0，BUILD SUCCESS**）
- `green/spark-targeted.log`（`MetricExportChecksumSpec` ＋ `DwsAdsChainExecSpec` 合并跑：
  `Tests: succeeded 34, failed 0`，`All tests passed`）
- `gate/spark-console.log`（**`[PASS exit=0]`**：`Total number of tests run = 212`、
  `Tests: succeeded 212, failed 0`、`套件 25`、`新写=True`、`JDK8=True`、`基线比对：tests=212 MATCH`）
- `gate/default-console.log`（analytics `895 MATCH`、mall `13 MATCH`、generator `106 MATCH`、
  三棵树 `1014`（基线 1014）、`[FAIL exit=7]` —— 唯一红为已登记环境性
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，本工作树 `landing/manifests` 不存在，
  43 份历史清单仅在主工作树，本轮**未修、未复制 manifest、未用开关掩盖**）
- `gate/spark-logs/`、`gate/default-logs/`（各档原始日志）
- `commit-msg.txt`（本提交的完整提交信息留痕）
- 交叉引用：F-39（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。
