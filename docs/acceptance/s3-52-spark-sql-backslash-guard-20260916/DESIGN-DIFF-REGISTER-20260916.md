# S3-52 登记件：Spark SQL 字面量「反斜杠形态」静态守卫

- 轮次：S3-52（阶段6 反熵 · 静态守卫）
- 日期：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 开工前 HEAD：`723e9a2`（＝ `origin/feature/v3-development`）
- 本项性质：**测试面加性守卫**（新增 2 个测试/测试支撑类），**零生产代码改动、零契约改动、零迁移、零连库、零外网**

## §1 开工前实测（改前事实，全部本轮取得）

1. **来源（backlog 行原文）**：`docs/PROJECT_STATUS.md` backlog **L505**（S3-51 行；本轮拼接后 **L520**）＝「Spark SQL 字面量**反斜杠转义被解析器吃掉**这一类缺陷目前只靠**人工审计**兜底（S3-01 已全仓复核：`spark-jobs` 主源码内 `regexp_replace` 仅剩 ODS 的 `regexp_replace(substr(event_time, 1, 10), '-', '')` 两处，**均无反斜杠**，且被 `OdsV2SqlContractSpec` L152 / `SqlTemplateSpec` L32 钉住 ⇒ 当前无残留实例）」；判类＝development backlog（**候选静态守卫**）。该行此前两次挂起的理由是「注释/KDoc 会大量误报，需先定白名单口径」。
2. **缺陷面（S3-01 的真实事故形态）**：SQL 文本里写了正则 `\d`（三引号形态时源码里就是一个反斜杠），经 Spark SQL 解析后 `\d` 变 `d` ⇒ 正则不匹配、**静默**输出错值（既不报错也不为空）。这类缺陷**编译期、单测、门禁都不会响**，故只能靠源码形态守卫。
3. **判据面规模**：`spark-jobs/src/main/scala` **36** 个 `.scala`（与 S3-49/S3-50 实测一致）。
4. **两套仪器分叉（本轮实测，用于定口径）**：
   - 一次性 PowerShell 仪器（正则式）读数：字面量 **1364**／像 SQL **135**／带反斜杠 **10**；
   - 本守卫的 Java 词法读数：字面量 **1406**／像 SQL **132**／带反斜杠 **9**。
   - 分叉定位在 `job/MetricExportJob.scala:91` 的 `s""""$c""""`（引号串 ＋ 插值洞）：洞内 `mkString(",\n")` 的反斜杠被 PowerShell 仪器算成**外层字面量的内容**，而洞是**代码**、不是外层字面量的内容 ⇒ **以 Java 词法为准**。像 SQL 数的差异来自关键字表口径（表在扫描器内显式列出，启发式，非求值）。
5. **主源码现有带反斜杠字面量清单（改前实测，共 9 处）**：`algorithm/Cleaners.scala:14` `"^\\d+(\\.\\d{1,2})?$"`（`.r` 宿主）；`job/JobArgs.scala:19` `"^\\d{8}$"`（`.r`）；`job/LocalSchemaInitJob.scala:310` `"\\s+"`（`split`）；`job/LocalSchemaInitJob.scala:399` `"\\s+"`（`replaceAll`）；`job/MetricExportJob.scala:137` **字符**字面量（`.replace`，路径 `\`→`/`）；`sql/JsonObjectSlicer.scala:155` `"\\\""`；同文件 `:116`／`:180`／`:206` **字符**字面量（`c == '\\'`）。按文件计数＝`Cleaners{str1}`／`JobArgs{str1}`／`LocalSchemaInitJob{str2}`／`MetricExportJob{char1}`／`JsonObjectSlicer{str1,char3}`。
6. **像 SQL ∧ 带反斜杠 ＝ 0**（改前无残留实例，与 S3-01 的人工复核结论一致）⇒ 本守卫属 **characterization guard**（被守性质在写断言之前就成立）。
7. **既有注释剥离实现不能直接复用（实测定性）**：`WarehouseNameLiteralScanner.CommentSyntax.strip(text, CommentSyntax.SLASH)`（S3-49 起的区间剥注释唯一所有者，测试树 `public`）对三引号**只认第一个 `"""` 就闭合**；而本判据面里存在「引号串 ＋ 插值洞」（`s""""$c""""`）与 `s"""…${…}…"""` 形态，其正确闭合规则是「连续 N≥3 个引号 ⇒ 内容取 N−3 并闭合」（N−3 规则）。⇒ 直接把它当前置剥离会**失步**（把后续源码吞进字面量或反之）。**本轮不合并、不修改它**（改它＝改 S3-49/S3-50 既有守卫口径）。
8. **刻意排除面（实测）**：①`spark-jobs/src/test/scala` **39** 个 `.scala`，含 **7** 处 `""""…""""` 引号串形态、宿主多为 Scala 正则 `.r`（测试里的期望值不是第二处所有者 —— 与库名门禁同口径）；实测到一套朴素词法在该树的 `DimDwdChainExecSpec.scala:283` 处失步（`${…}` 洞内嵌套三引号字面量），作为「为何必须做洞递归」的演示点；②`warehouse/**` **6** 个 `.sql`、实测反斜杠 **0** 处（且 .sql 需要另一套 SQL 词法，不在本轮判据面内）。
9. **改前基线**：`scripts/run-tests.ps1` `$BaselineDefault` ＝ analytics-server **996**／mall-simulator **13**／synthetic-data-generator **110**（三棵树 **1119**）；改前脚本 **1125** 行（CRLF 1125／裸 LF 0）。
10. **唯一已登记环境性红**：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`（`platform-app`；`expected: 43 but was: 0`，因 `landing/` 被 gitignore）。本轮**未**新增、**未**消除任何红。

## §2 11 项 HARD DECISION GATE 逐项判定

①DROP TABLE/COLUMN **否**（零 DDL）②改已有字段类型/既有业务语义 **否**（零生产代码改动）③改已发布 Flyway migration **否**（零 SQL/迁移）④写/迁移正式 3306 数据 **否**（零连库）⑤切 ACTIVE **否** ⑥改 `contract-specs/**` 已有契约语义 **否**（零命中，只读）⑦改 V3.0 总体架构 **否** ⑧改正式项目范围 **否**（收口 backlog 既有候选，不是新范围）⑨删除已发布功能 **否** ⑩引入 V3.0 未规划大型基础组件 **否**（只用 JDK 标准库 `java.nio`／`java.util.regex`）⑪两种方案造成重大长期架构分叉 **判「否」，但登记一个 owner 风险**：本轮**新增**了一个「注释/字面量词法器」，与既有 `CommentSyntax.strip` **并存且口径不同**（见 §1.7、§8.3）；因**不改既有守卫口径**、也不新增生产路径，故不构成架构分叉；但「同一测试树里两份剥注释实现」属**反熵候选**，已在 §8 登记为**待裁决**。
⇒ 11 门全「否」⇒ 判 **A 类（实现/加性）**，自主实施、无需暂停。

## §3 口径（本轮冻结）

### 3.1 判据面
只覆盖 `spark-jobs/src/main/scala/**`（生产 Scala 树）。`src/test` 树与 `warehouse/**` **刻意排除**（§1.8）。

### 3.2 「像 SQL」＝显式关键字表（启发式）
命中 `SQL_KEYWORD`（32 个 token：`SELECT`／`INSERT`／`UPDATE`／`DELETE`／`CREATE`／`DROP`／`ALTER`／`FROM`／`WHERE`／`GROUP BY`／`ORDER BY`／`HAVING`／`JOIN`／`LEFT JOIN`／`LATERAL VIEW`／`PARTITION`／`OVERWRITE`／`WITH`／`UNION ALL`／`CASE WHEN`／`RLIKE`／`REGEXP_REPLACE` 等，大小写不敏感、最长优先、`Pattern.quote`）即判「像 SQL」。**不**声称该字面量真的被交给 Spark（不是求值、不是数据流分析）。

### 3.3 何谓「反斜杠字面量」（源码形态口径）
- 三引号字面量（`"""…"""`）或 `raw` 前缀字面量 ⇒ 内容里出现**任意单个 `\`** 即计入（三引号/`raw` 形态下源码里的 `\` 会原样进入值）；
- 单行字符串与**所有字符字面量** ⇒ 只有出现 **`\\`** 才计入（此时值里才真带反斜杠；单个 `\d` 在单行字符串里是**非法** Scala 转义，编译不过 ⇒ 不可能存在于可编译源码里）。
- 这是**源码形态**判据，**不是** Scala 转义求值语义的判据。

### 3.4 判据② 是「按文件计数的双向精确闭集」
登记表以 **(file, {str,char}) 计数**为键，而非 `file:line`：①未登记文件出现反斜杠 ⇒ 红；②已登记文件的计数变化（多/少）⇒ 红。⇒ **同一已声明文件内把一处反斜杠挪到别处不可见**（残余面，§7.4）。

### 3.5 判据① 白名单当前为空（`SQL_TEXT_WHITELIST = Set.of()`）
「SQL 文本 ∧ 反斜杠」＝ 0 是**零容忍**。若将来确有合法用例，必须**显式加白名单并写明理由**，而不是放宽判据。—— 这正是 backlog 行「需先定白名单口径」的落地形式：口径定成「白名单存在但为空」，误报处理路径明确。

### 3.6 被否方案三个（含实测理由）
1. **复用 `CommentSyntax.strip` 做前置剥离**：实测其引号串闭合口径与判据面不符（§1.7）⇒ 会产生失步的**假绿/假红**；且扩改它＝改 S3-49/S3-50 既有守卫的语义。**否**（改为在扫描器内自带一份注释识别，并**不声称与前者等价**）。
2. **「字面量里有 `\` 就红」的全局判据**：会把 5 处合法宿主（Scala 正则 `.r`、`split`、`replaceAll`、字符处理）全部打成假红，且注释里的 `\` 会大量误报 ⇒ 不可用。**否**（改为「形态 ＋ 像 SQL」双限定的窄判据，其余用闭集登记）。
3. **把一次性 PowerShell 仪器当门禁判据**：两套仪器读数已分叉（§1.4）⇒ 说明口径必须由**可复跑的代码**承载。**否**（一次性仪器只用于量数与交叉核对，且分叉已如实登记）。

### 3.7 探针纪律（沿用 S3-49/S3-50）
锚点必须**唯一命中**（≠1 即明写「未执行」）；探针注入前按字节备份、跑完**按字节还原**并复核 sha256；最后 `git status --porcelain -- spark-jobs` 必须为空；**负对照**（预期不红的探针）必须与预期一致的绿。

## §4 实现面（2 个新文件 ＋ 门禁脚本 1 个数字）

1. **新增** `analytics-server/platform-common/src/test/java/com/graduation/analytics/sql/SparkSqlBackslashScan.java`（**493** 行、纯 LF、无 BOM、包内可见）：一遍词法同时处理 ①`//` 与 `/* */` 注释（可关，用于判据④）②单行字符串与字符字面量的转义对（`\\`／`\"`／`\n` …）③三引号的「连续 N≥3 个引号 ⇒ 取 N−3 并闭合」④插值 `${…}` 洞按**代码**递归（洞内字面量**单独登记**、不计入外层内容）。产物类型：`Kind{STRING,CHAR}`／`Prefix{PLAIN,S,F,RAW}`／`Lit(file,line,offset,kind,prefix,triple,raw,valueBackslash,sqlText)`／`Result`（含 `backslashLiterals()`／`sqlBackslashStrings()`／`backslashByFile()`）。扫描面枚举 `scalaFiles(root)`：walk ＋ 只取 `.scala` ＋ 跳过 `target/` ＋ 排序，**缺目录直接抛错（门禁不得空跑）**。
2. **新增** `…/sql/SparkSqlBackslashGateTest.java`（**323** 行、纯 LF、无 BOM）**6 条判据**：`sqlTextCarriesNoSourceFormBackslash`（①，白名单空）／`backslashLiteralsAreAClosedSet`（②，双向精确）／`scanSurfaceIsNonVacuous`（③，四类下限 `MIN_SCALA_FILES=36`／`MIN_LITERALS=1300`／`MIN_SQL_TEXT_STRINGS=100`／`MIN_BACKSLASH_LITERALS=5`，**只防塌缩**，精确口径由② 独占）／`commentStrippingIsLoadBearingOnTheRealTree`（④，真树 `raw.size() > code.size()`）／`lexerFamiliesAreHandledAsMeasured`（夹具逐条钉形态）／`commentOnlySqlIsIgnoredWhileRealSqlIsFlagged`（注释里的 SQL 全绿 ＋ 非注释真 SQL 恰好 1 命中）。
3. `scripts/run-tests.ps1`：`$BaselineDefault['analytics-server']` **996→1002**（三棵树 **1119→1125**）＋ S3-52 说明块（含实测清单、两套仪器分叉、5 条探针、两轮门禁、7 条边界）。改后 **1170** 行（CRLF 1170／裸 LF 0），`+46/−1`；**未改**任何命令语义、**未改** `$BaselineSpark`(308)／`$BaselineIsolated`(30/19/6)。
4. **零改动**：生产 Java/Scala、`pom.xml`、`application.yml`、Flyway、`contract-specs/**`、`docs/contracts/**`、web、DB。

## §5 证据

### 5.1 诚实表述（不得越界）
本守卫**不是**「先红后绿」的功能开发：被守性质（SQL 文本里无反斜杠字面量）在写断言之前**就已成立** ⇒ 首跑即绿**不构成**判据有效的证据。非恒真性**只**由 §5.3 的 5 条真树变异探针证明。所谓「RED-1」是**声明表故意留空**造成的红（用来打印实测清单），属工具性 RED。

### 5.2 内层真跑（定向单类）
- **RED-1**（声明表空）：`Tests run: 6, Failures: 2` —— `backslashLiteralsAreAClosedSet` 按设计红（打印实测全表，用于登记）＋ `scanSurfaceIsNonVacuous` 因**我原先用 PowerShell 仪器推导的下限 10 与 Java 词法的 9 不符**而红（「带反斜杠的字面量塌缩：9 < 10」）。⇒ 该红暴露了 §1.4 的分叉，是本轮第一处「口径必须由代码承载」的直接证据。
- 修正：把四类下限改成**只防塌缩**的宽松值（36／1300／100／5），精确性交给判据②（避免第二处口径竞争）。
- **GREEN**：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`／`BUILD SUCCESS`；权威读数行（maven 控制台，UTF-8）：`[S3-52 扫描面读数] 文件=36 字面量=1406 字符串=1368 字符=38 像SQL=132 带反斜杠=9`。改下限后**重跑**同样 6/6 绿、读数一致。
- 工具事实（沿用 S3-49/50）：surefire XML 的 `<system-out>` **未填充** ⇒ 读数行只能从 maven 控制台取；失败用例名**以 surefire XML 为准**（避开控制台中文乱码）。

### 5.3 变异探针 5 条（真树注入；`.verify/s352-probe.ps1`，日志 `.verify/s352-probe.txt`）
| 探针 | 注入 | 实测失败用例 | 预期 | 判定 |
|---|---|---|---|---|
| P1 | `sql/OdsLoadSql.scala` 三引号 WHERE 文本加 `AND dt RLIKE '\d{4}'` | `backslashLiteralsAreAClosedSet` / `sqlTextCarriesNoSourceFormBackslash` | 同左 | PASS |
| P2 | `algorithm/Cleaners.scala` 加 `private val PROBE_EXTRA_PATTERN = "\\s+".r` | `backslashLiteralsAreAClosedSet` | 同左 | PASS |
| P3 | `sql/AdsSql.scala` 把 P1 同文本放进注释行 | （无，全绿） | 全绿（负对照） | PASS |
| P4 | `sql/AdsSql.scala` 单行字符串注入 `\\d` 进 SQL 文本 | `backslashLiteralsAreAClosedSet` / `sqlTextCarriesNoSourceFormBackslash` | 同左 | PASS |
| P5 | `job/LocalSchemaInitJob.scala` 外层三引号、反斜杠只在 `${…}` 洞内嵌套字面量里 | `backslashLiteralsAreAClosedSet` | 同左 | PASS |

- P1 抓到的是 **S3-01 的事故形态**；P3 证明判据**不是**「见到反斜杠就红」（注释载重）；P5 证明**洞确实被递归扫到**（若不扫洞，计数不变 ⇒ 该探针会变绿）。
- 5/5 锚点命中＝1、5/5「字节还原: OK（sha256 一致）」、探针后 `git status --porcelain -- spark-jobs` **为空**。
- 探针脚本按 surefire XML 取失败用例名（权威）；`读数:` 字段为空（§5.2 的 XML 限制），读数以 §5.2 的两轮为准。

### 5.4 门禁（默认档两轮，均真跑）
- **量数轮 `s352_20260916_pre`**（基线仍 996）：analytics-server `1002 (F=1 E=0 S=1)`、明细 `105+353+172+97+118+157` ⇒ `DRIFT(基线 996)`，**+6 全部落在 platform-common**（99→105，其余模块一字未变）；mall-simulator 13 MATCH；synthetic-data-generator 110 MATCH；`default 三棵树 1125（基线 1119）`；唯一红＝已登记环境性用例；并发锁 `Local\v25tests-dab4013e51a18093`；`[FAIL exit=7]`（该轮因计数漂移记 FAIL，**只作量数依据、不作通过证据**）。
- **收口轮 `s352_20260916_close`**（基线 1002）：`1002 MATCH`／`13 MATCH`／`110 MATCH`、`三棵树 1125（基线 1125）`；**唯一失败项**＝analytics-server 的已登记环境性用例（`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`，`expected: 43 but was: 0`，日志 `default-analytics-server.log:1202–1212`）；并发锁 `Local\v25tests-caf57ddd1670e633`；`[FAIL exit=7]`。
- ⇒ **不得**表述「门禁已通过」：本轮门禁是**计数一致性**证据（1002/13/110 全 MATCH）＋ 唯一红为**改前既有**的环境性红；档位结论仍是 `[FAIL exit=7]`。
- 顺带事实：两轮门禁都打印了 `Local\` 互斥锁名 ⇒ S3-51 的入口互斥在**真实门禁**下已生效（非本轮判据，仅记录）。
- `spark`／`isolated` 档本轮**未重跑**（新用例无 `@Tag("it")`，不在 isolated 选择面内）。

## §6 对外行为变更

- 生产代码 / 契约 / API / DTO / 表结构 / 迁移 / 依赖：**无变更**。
- 门禁计数基线：analytics-server **996→1002**（＋6）、三棵树 **1119→1125**；新增的 2 个测试类进入 default 档计数（`spark`／`isolated` 基线不变：308／30/19/6）。
- 新增可复用测试支撑：`SparkSqlBackslashScan`（包内可见，后续若要扩面可复用其词法；**不**对外承诺 API 稳定性）。

## §7 未测与边界（不得夸大）

1. **判据面**只覆盖 `spark-jobs/src/main/scala`；`src/test/scala`（39 个 `.scala`，含 7 处 `""""…""""` 形态、宿主多为 Scala 正则 `.r`）与 `warehouse/**`（6 个 `.sql`，实测 0 反斜杠）**刻意排除**。
2. 只判**源码形态**：**不**求值 Scala 转义语义、**不**判 Spark SQL 解析后的实际行为、**不证明** Spark 运行时正确性（`spark` 档未重跑）。
3. 「像 SQL」是**关键字启发式**：不证明该字面量真的被交给 Spark；关键字表本身是第二处「什么算 SQL」的口径（表在扫描器内附出处注释）。
4. 判据② 按**文件计数**：在同一已声明文件内**移动**一处反斜杠**不可见**；且新增合法反斜杠字面量必须先改登记表（刻意设计，但任何新形态都会先红一次、需人工裁定）。
5. **不覆盖**另一缺陷类：`SurrogateKeyVectorSupport.scala` 那类「Scala 转义产出 `\"` 进 JSON 正则」的问题（已有其自己的 spec 钉住，不在本守卫面内）。
6. **两份剥注释实现并存**：本扫描器自带注释识别，`WarehouseNameLiteralScanner.CommentSyntax.strip` 仍是 S3-49/50 的唯一所有者；**两者口径不同、不声称等价**（§1.7、§8.3）。
7. **不得**表述「门禁已通过」（§5.4）、**不得**表述「Spark SQL 反斜杠缺陷已绝迹」（守卫只覆盖主源码树的**源码形态**，且未证明运行时行为）。

## §8 顺带台账（登记，不在本轮处理）

1. backlog **L520**（＝原 L505；S3-51 行）在行内追加「S3-52 已实施」说明；**不删行、不改判类**（仍为 development backlog 行，收口的是其中的**候选静态守卫**）。
2. **新增 1 行** backlog「S3-52 后继残余面」（§7 ①②③④⑤⑥）。
3. **待裁决（反熵候选，非阻塞）**：测试树里现有**两份**「注释/字面量」词法实现（`CommentSyntax.strip` 与 `SparkSqlBackslashScan`），口径不同（引号串闭合规则 vs N−3 规则）。是否收敛到唯一所有者＝改既有守卫口径 ⇒ **本轮只登记、不合并**。
4. **行号映射更正（本轮顺带，因 S3-51 在阶段6 插入 7 条导致旧引用漂移）**：S3-51 登记件 §8.1／§8.4 与 F-84 中的 `L504`／`L505` 指的是**插入前的行号**，当前实际为 **L519**／**L520**；已在相应位置补「（拼接后 Lxxx）」标注。
5. **S3-51 登记件 §8.4 的预测已按实测更正**：原文写「『注释/KDoc 误报』可由既有 `CommentSyntax.strip` 化解」——**实测未复用**（§1.7），误报问题改由「形态 ＋ 像 SQL ＋ 空白名单 ＋ 闭集登记」四件套化解。
6. 滚动执行位置块（5 段／9 行）同步为本轮口径；阶段6 区块**新增 7 条** S3-52 记录；`docs/status-history/开发过程事实与决策记录.md` 追加 **F-85**。

## §9 复现命令

```powershell
# 0) 前置：$wt = D:\Develop_code\GraduationProject-wt\v3-dev（分支 feature/v3-development）
# 1) 定向单类（内层循环；注意 -D 必须单引号、类名分隔符是逗号）
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' `
  -f "$wt\analytics-server\pom.xml" -pl platform-common -am test `
  '-Dtest=SparkSqlBackslashGateTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# 期望：Tests run: 6, Failures: 0 ⇒ BUILD SUCCESS；控制台打印
#   [S3-52 扫描面读数] 文件=36 字面量=1406 字符串=1368 字符=38 像SQL=132 带反斜杠=9
#   失败用例名请从 surefire XML 取（控制台中文会乱码；XML 的 <system-out> 未填充）

# 2) 变异探针（真树注入 → 单类真跑 → 按字节还原 → sha256 复核 → git status 必须干净）
& "$wt\.verify\s352-probe.ps1"      # 日志：$wt\.verify\s352-probe.txt
# 期望：P1..P5 全 PASS，且「字节还原: OK (sha256 一致)」

# 3) 门禁默认档（摘要走 Write-Host ⇒ 外层落盘必须用 *>&1）
& "$wt\scripts\run-tests.ps1" -Suite default -RunId 's352_20260916_close' -Confirm *>&1 |
  Tee-Object -FilePath "$wt\.verify\s352-close-default.log" | Out-Null
# 期望：1002 / 13 / 110 全 MATCH；三棵树 1125（基线 1125）；[FAIL exit=7]
#   唯一红＝IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61（已登记环境性红）
```
