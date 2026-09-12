# M1-9 ② 第二家商城适配器 · 独立只读复核报告（VERIFY-PARENT）

## 0. 抬头
- 泳道：M1-9 ②（第二家商城适配器）· R2 修复轮交付面 = `synthetic-data-generator` 8 个未提交文件（FIX2-REPORT 自述）
- 复核方式：只读。无任何 git 写、未改任何已有文件、未删任何文件/目录、未启停任何进程、未对任何库做写操作（未使用任何 DB 语句）、未跑 mvn 以外的构建工具；唯一新写文件 = 本文件
- 时间：2026-09-12 14:40:52（起） / 2026-09-12 14:44:54（终）
- HEAD：`0df3d1ce0909a6b6a79dd12aa306ef73c3a5a486`（14:40:52 实测）→ `cdd36dbf523587cfa876e107a5fb3814964e9d42`（14:42:07 实测）
  - 并发写者：会话中途 HEAD 被推进 2 条提交（`cdd36db` P2-07、`9679f5e` P2-01）；`git diff --name-only 0df3d1c..HEAD -- synthetic-data-generator` = 空 ⇒ 本模块未被其触碰；8 文件 sha256 两次取样全等
- `git status --porcelain -- synthetic-data-generator` 原文（14:40:52 与 14:42:07 两次一致，8 行）：
```
 M synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ProductPage.java
 M synthetic-data-generator/src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java
 M synthetic-data-generator/src/main/java/com/graduation/generator/adapter/TargetCheckResult.java
 M synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiGenerationEngine.java
 M synthetic-data-generator/src/main/java/com/graduation/generator/web/dto/GeneratorApiDtos.java
 M synthetic-data-generator/src/test/java/com/graduation/generator/adapter/SecondMallAdapterOperationsTest.java
 M synthetic-data-generator/src/test/java/com/graduation/generator/engine/SecondMallDualTargetTest.java
 M synthetic-data-generator/src/test/java/com/graduation/generator/fixture/SecondMallFakeServer.java
```

## 1. E1 编译（原始命令 + 退出码 + 关键输出原文）
```powershell
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o -f synthetic-data-generator\pom.xml test-compile
```
- `$LASTEXITCODE` = **0**；关键输出原文：
```
[INFO] --- compiler:3.11.0:compile (default-compile) @ synthetic-data-generator ---
[INFO] Nothing to compile - all classes are up to date
[INFO] --- compiler:3.11.0:testCompile (default-testCompile) @ synthetic-data-generator ---
[INFO] Nothing to compile - all classes are up to date
[INFO] BUILD SUCCESS
[INFO] Total time:  0.657 s
```
- 读数性质（陷阱③）：E1 是一次**增量空转**，日志中**没有** `Compiling N source files` 这一形状 ⇒ 它**没有重新编译任何源码**，"可编译"只由 Maven 的 mtime 判定背书。

## 2. E2 测试（原始命令 + 退出码 + 关键输出原文）
```powershell
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o test -f synthetic-data-generator\pom.xml
```
- `$LASTEXITCODE` = **0**；类级 `-- in` 行 **19** 条；汇总行与分项原文：
```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.290 s -- in com.graduation.generator.adapter.SecondMallAdapterOperationsTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.786 s -- in com.graduation.generator.engine.SecondMallDualTargetTest
[INFO] Tests run: 120, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  27.688 s
```
- 逐失败用例名：**无**。历史基线 120 条 ⇒ **差值 0**（无新增/删除用例，`Skipped: 0`）。
- 并发 Maven：跑测前后 `Get-Process java` 仅 2 个（pid 19268 = 端口 8092、pid 41228 = 端口 8090，均非 Maven）⇒ **未发现并发 Maven**，本次读数有效。
- E2 同样**未重编译**（日志无 `Changes detected` / `Compiling`）。字节码对应性佐证（**推断**）：8 个源文件 mtime 13:28:48 早于对应 `.class` 的 13:28:50~51；`.class` 内含只属于当前源码的符号（`ProductPage.class` 含 `hasGap`、`SecondMallHttpAdapter.class` 含 `CONFIG_FORMAT_KEY`、测试类含 `T2d`）。

## 3. 待验清单与逐条判定（第 1 步抄录 + 第 5 步三态合并成表，避免重复）
判定口径：**已验** = 我用独立读数复现；**未验** = 我无法复现（不等于假）；**与实测不符** = 我的读数与声称冲突。

| # | 声称（来源：行） | 判定 | 我的原始读数 |
|---|---|---|---|
| C01 | 变更面 = 8 文件且全在 `synthetic-data-generator/**`；无新增/删除/移动（FIX2-REPORT:112） | 已验 | `git status --porcelain` 8 行全为 ` M`；全仓 9 行中无 `D ` / `R ` 前缀项；`git diff --numstat` 8 行 = +31/-9、+134/-44、+11/-4、+75/-49、+9/-3、+288/-21、+73/-8、+87/-33，与任务给定逐字一致 |
| C02 | 8 文件 sha256「后」值（FIX2-REPORT:116-123） | 已验 | `Get-FileHash -Algorithm SHA256` 8/8 与表内「后」列全等（`B379F809…1323`、`BCE3F0E3…D8D9`、`A62712AD…81CD` 等）；且「前」列 8/8 等于归档 `evidence/R2-baseline-sha256-before.txt`（93 条，mtime 13:05:12） |
| C03 | 8 文件行数 57→79 / 1061→1151 / 32→39 / 543→569 / 149→155 / 572→839 / 568→633 / 569→623（FIX2-REPORT:116-123） | 已验（后值） | 现文件实测 = 79 / 1151 / 39 / 569 / 155 / 839 / 633 / 623，与「后」列 8/8 全等；「前」列未独立复现（原因见 §5 陷阱①） |
| C04 | `ExternalOrder.java` 本轮零改动，sha256 `6E4D5546…DE80`（FIX2-REPORT:125） | 已验 | 该文件不在 8 行 status 内；`R2-baseline-sha256-before.txt` 内 `6E4D5546E383B9690E8EBCA65262945188D411CC6954BD2B8181FE460E02DE80` 逐字相符 |
| C05 | E2 = `Tests run: 120, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS + exit 0（FIX2-REPORT:65） | 已验 | 我自己跑 E2 得同一汇总行 + `BUILD SUCCESS` + `$LASTEXITCODE=0`（§2） |
| C06 | 本泳道两类分项 12 / 7；改动前 9 / 6 ⇒ 净增 4 条（FIX2-REPORT:70-74） | 已验（12/7） | 实测 = 12 / 7；归档 `R2-e2-…130559.log` = `Tests run: 116`、`R2-e2-…132012.log` = `Tests run: 120` ⇒ 净增 4 与归档自洽 |
| C07 | 10 次变异测试「先破坏→观察→还原+sha256 核对」；MUT-1 共 5 红且 `IllegalArgumentException: price …5.85` 穿透到引擎层；MUT-3 第 1 次全绿、补强后同一次变异变红；MUT-8/MUT-9 全绿（FIX2-REPORT:76-99） | 已验（存档核对；未重跑变异） | 10 份 `R2-mut-*.log` 汇总行原文：MUT-1 `19, Failures: 2, Errors: 3`（=5 红）；MUT-M5a `19/1`；M5b `132218 = 19/0` 全绿 与 `132241 = 19/1` 红；M2 `120/1`；L2 `12, Errors: 8`；H2 `34/3`；L1 `12/1`；L5 `12/1`；guardrestore `19/0`；M7-restore `120/0`。MUT-1 日志按 GB18030 解码含 3 处 `IllegalArgumentException: price …5.85` |
| C08 | 两次全量红：13:27:54 = Maven 增量旧 class 假失败；13:28:48 = `ReferenceMallHttpAdapterTest` 抖动（与本泳道无关）（FIX2-REPORT:101-106） | 已验 | `…132754.log` = `Tests run: 120, Failures: 1`，红的是 `SecondMallAdapterOperationsTest.differentEnvelopeParsesAndFailsLoudly`，报文（GB18030）确为退化的合并话术「目标未配置 credential_ref」= 旧 class 残留；`…132848.log` 含 `Changes detected - recompiling the module!` + `Compiling 70 source files`，红的是 `ReferenceMallHttpAdapterTest.probeReportsSupportedCapabilitiesFromRealResponses`；`R2-flake-refadapter-…133000.log` = `Tests run: 8, Failures: 0` |
| C09 | H2：`ProductPage` 三缺口通道并列上报 + 两个真实出口 + 引擎缺口行 + `hasGap()`（FIX2-REPORT:32） | 已验 | `ProductPage.java:39/48/76-77`（第 5 分量、归一、`hasGap()` = 三通道任一非空）；关键字分支 `SecondMallHttpAdapter.java:312-313` 原样带过三通道；`readCatalog` `:356-357` `total = products.size() + unreadablePrices.size()`；引擎 `MallApiGenerationEngine.java:260` 三通道传入 |
| C10 | M1：正则收紧为 `\d+`、异常统一 `MallOperationException`、量纲常量化（FIX2-REPORT:33） | 已验 | `SecondMallHttpAdapter.java:687` `text.matches("\\d+")`（无 `-?`）；`:107` `CENTS_PER_YUAN = 100`；`:117` `CENTS_PER_YUAN_LOG10 = requirePowerOfTen(CENTS_PER_YUAN)`；`:123-131` 非 10 的幂即 `IllegalStateException` |
| C11 | M2：空白⇒null 单一所有者 = `ExternalOrder` 构造器，`readOrder` 只透出（FIX2-REPORT:34/125） | 已验（代码形状） | `ExternalOrder.java` 不在改动面（C04）；MUT-4 删构造器两行 ⇒ `missingStateNeverInventsAMallWord` 变红（`R2-mut-M2-…132303.log` = `120, Failures: 1`），与「唯一所有者」自洽 |
| C12 | M5：夹具 `OrderResponseShape` 7 值；T2d 真的跑 4 条出口（FIX2-REPORT:35） | 已验 | `SecondMallFakeServer.java:102-124` 实测 7 值 = `AS_IS / OMIT_STATE / BLANK_STATE / OMIT_LINES / EMPTY_LINES / OMIT_LINE_PRICE / RESULT_NOT_OBJECT`；逐值调用见 `SecondMallAdapterOperationsTest.java:313-315 / 330 / 346 / 361 / 758` |
| C13 | M7：3 个夹具 API 退休、零调用点（FIX2-REPORT:166-169） | 已验 | 全 `src` 检索 `putItem\|putItemWithoutPrice\|putItemWithoutState` = **0 命中**；阳性对照同一检索命中 `overrideItemState` 3 处、`removeItemPrice` 3 处；MUT-9 贴回后全量仍 120 绿（`…132544.log`） |
| C14 | L1–L5/L6b 随手修（时间戳复用、行为断言、内联常量、凭据两态、删恒真断言）（FIX2-REPORT:36） | 已验（抽样） | L5：`SecondMallHttpAdapter.java:826-832` 把 `null` 引用与空白引用分成两态；L2：`CENTS_PER_YUAN_LOG10` 变异 ⇒ `Errors: 8`（`…132349.log`），证明断言已与常量行为绑定；L6b：`assertTrue(routeResolutions` 形态 0 命中，仅存 `assertEquals(1, …routeResolutions)`（`SecondMallDualTargetTest.java:165/194`） |
| C15 | `evidence/` 共 22 个原始日志（FIX2-REPORT:18） | 已验 | `Get-ChildItem evidence -File` = **22** 个，mtime 13:05:12~13:30:44，与自述收工窗口一致 |
| C16 | 未停/未起 8090(pid 41228)、8091(pid 47132)、8092(pid 19268)；不声称运行中的 8092 含本轮改动（FIX2-REPORT:22） | **与实测不符（仅 pid 引用部分）** | 现观测监听端口只有 8090(pid 41228) 与 8092(pid 19268)，**无 8091**（`Get-NetTCPConnection -State Listen`；`Get-Process java` 仅 2 个，无 pid 47132）；且本仓既有提交 `b900aeb` 正文记录「8091 自 09:31:13 静默消失且未重启」⇒「8091(pid 47132) 未被本泳道启停」这一 pid 引用与本项目自身记录冲突。进程历史本身**未验**（不可复现） |
| C17 | §5 未取证清单：`declared` 标记未做、显式 JSON null / 小数分未取证、无真实第二家、并发未取证、运行态未验证（FIX2-REPORT:134-139） | 已验（披露与代码方向一致，口径需限定） | `GeneratorApiDtos.java:141-142` 自述「响应体加 `declared` 属契约变更，排到 CT 之后」；`TargetCapabilities.java:17/27-33` 内部 record 已含 `(verdicts, declared)`、`none()` ⇒ `declared=true`，与 `contract-specs/openapi/generator-api.v1.yaml:486/496` 一致 ⇒「`declared` 未做」只指**响应体暴露**，非类型层缺失；Part B 商城确为 `com.sun.net.httpserver.HttpServer` 夹具 |
| C18 | 「跑 R1 冻结判据 `verify-fix-v3.ps1` 可能把本轮 8 文件报成清单外」（FIX2-REPORT 第 6 节第 1 条） | 已验（并给出精确定量） | 按 `verify-fix-v3.ps1:56-74` 的 18 条 `$allow` 复算：`git status --porcelain -- …/src` 的 8 条中**确定 2 条在清单外** = `adapter/TargetCheckResult.java`、`web/dto/GeneratorApiDtos.java`（其余 6 条命中）⇒ 不是「可能」而是**必然 W1 FAIL**；该 v3 于 12:2x 冻结，早于本轮 8 文件的落点 |

## 4. 我**没有**证明的东西（不得由本报告外推）
1. **真实第二家商城的连通性/契约一致性**：Part B「商城」是 `127.0.0.1:0` 上的 `HttpServer` 夹具；分页、幂等、限流、错误码全谱面未取证。
2. **双目标写入的真实副作用**：未接任何真实商城、未读任何数据库、未检查任何产物（本泳道零 DB 语句）；「双目标」只到夹具层。
3. **平台侧消费**：8090/8092 未重启、未热载，未验证运行中的 8092 是否含本轮字节码；ODS/Hive/看板/报告侧的消费未验证。
4. **源码可编译性本身**：E1/E2 **均未重编译**，结论只是 mtime 判定 + `.class` 内含现源码符号的推断，**不是**从源码重新编译的证明（受「禁删 `target/`、禁改文件」约束，无法用 clean 强制重编译）。
5. **变异测试**：只核对了归档日志的汇总行与报文，**未重跑**任何一次变异（重跑需改文件，被禁）。
6. **README 的历史读数与脚本计数**：`Tests run: 111`、`Total time: 22.818 s`、`116`、`verify-v2.ps1` 的 PASS=16/FAIL=1、`verify-fix-v3.ps1` 的 18/0 等一律**未验**（复现需 checkout 旧提交或运行脚本，两者均被本轮禁令排除）。
7. **M1-9 是否可判 `DONE`**：本报告只覆盖 E1/E2 + 静态读数，不含 E3（真实链）/E4（集群 1000 行）。

## 5. 异常与陷阱（本轮实测遇到）
1. **陷阱③「日志里没有的形状被当成通过」**：E1 与 E2 **都是增量空转**，两份日志里都没有编译形状。若把 `BUILD SUCCESS` 当作「源码编译通过」，就是拿一个没有发生的动作当证据 ⇒ 已按 §1/§4-4 降级表述。
2. **HEAD 在复核期间被推进**：`0df3d1c` → `cdd36db`（2 条 P2-07/P2-01 提交）。已确认其未触碰本模块，且 8 文件 sha256 前后全等 ⇒ 本报告结论仍对应同一字节。
3. **陷阱①「近似统计当精确读数」**：README §13.2 的断言计数（`SecondMallAdapterOperationsTest` 79→135、`SecondMallDualTargetTest` 46→66、`assertEquals("NEW")`=6、`assertEquals("SETTLED")`=3、`GeneratorBoundarySourcePolicyTest` 20→20）用同一正则复算：在 `ed8c531` blob 上 **135 / 66 / 6 / 3** 与 20/20 **逐字命中**；但在**当前工作区**为 **191 / 76 / 9 / 3**（R2 净增 4 条用例所致）⇒ 该组数字只对 `ed8c531` 成立，**不可当当前读数使用**。
4. **陷阱②「零命中当通过」**：C13 的「0 调用点」若不做阳性对照，无法区分「真零命中」与「正则写错」⇒ 已用同一次检索命中 `overrideItemState` / `removeItemPrice` 各 3 处作对照。
5. **归档日志编码**：`evidence/*.log` 的中文是 GBK 字节，按 UTF-8 读会得到替换字符，直接 grep 中文会**假阴性**（我首次检索「不得为负数」= 0 命中，改按 GB18030 解码后命中 3 处）。这是取证方法陷阱，不是内容缺失。
6. **`verify-fix-v3.ps1` 的清单缺口（C18）**：R1 冻结判据按「评审点名的文件」枚举，未含本轮 2 个措辞文件 ⇒ 原样复用会把一次合规变更判成 W1 FAIL。建议父方以**追加声明式补丁**处理，而不是静默放宽阈值。
7. **未运行任何 `verify*.ps1`**：它们面向旧交付面，且 `verify-fix.ps1` 有破坏性历史（别名 `rd` = `Remove-Item`，见 v3 头部 修-4）⇒ 本泳道禁令下不执行；这些脚本的读数一律计**未验**。

REVIEW_RESULT=READY_FOR_PARENT