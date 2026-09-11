# P1-03 SourceRegistry 模型、服务、API、审计 —— 验收证据包

- 任务：M2 主线「分析平台与具体商城解耦」的第一块落地 —— 源身份/词汇收敛到 `analytics_meta.source_registry`，
  每次运行的参数仍由 `runtime_profile` 承担。
- 分支：`remediation/r1-boundary`（本任务未提交、未合并；工作区留痕见文末「Git 留痕」）。
- 日期：2026-09-11。
- 结论：**DoD 全部达成**，E1/E2/E3 三级证据齐备；未取证项、边界与需裁决点已在第 7/8/9 节逐条列出。

本目录只放**证据**，不放实现代码。所有数字都能在本目录 `raw/` 里找到原始出处；本文件不作任何无 raw 支撑的断言。

---

## 1. 交付物清单（文件 → 证据）

| # | 交付物 | 绝对路径 | 字节 / sha256 |
|---|--------|----------|---------------|
| 1 | 实体 | `analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/entity/SourceRegistry.java` | 见 `raw/e3-05-deliverable-inventory.txt` |
| 2 | Mapper（源表） | `.../source/mapper/SourceRegistryMapper.java` | 同上 |
| 3 | Mapper（当前源绑定） | `.../source/mapper/ActiveSourceBindingMapper.java` | 同上 |
| 4 | Service 接口 | `.../source/SourceRegistryService.java` | 同上 |
| 5 | Service 实现 | `.../source/SourceRegistryServiceImpl.java` | 同上 |
| 6 | 路径策略 | `.../source/SourcePathPolicy.java` | 同上 |
| 7 | 画像校验器 | `.../source/SourceProfileValidator.java` | 同上 |
| 8 | 审计动作常量 | `.../source/SourceAuditActions.java` | 同上 |
| 9–13 | DTO ×5 | `.../source/dto/{SourceRegistryView,SourceRegistryCreateReq,SourceRegistryUpdateReq,SourceCheckResult,SourceChangeOutcome}.java` | 同上 |
| 14 | Controller | `analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/SourceRegistryController.java` | 同上 |
| 15 | 错误码 | `analytics-server/platform-common/.../PlatformBizException.java`（新增 4 个码） | 同上 |
| 16 | 状态码映射 | `analytics-server/platform-common/.../GlobalExceptionHandler.java` | 同上 |
| 17 | Mapper 扫描 | `analytics-server/platform-app/.../AnalyticsApplication.java` | 同上 |
| 18 | 配置项 | `analytics-server/platform-app/src/main/resources/application.yml`（`platform.source.profile-root`） | 同上 |
| 19–24 | 测试 ×6 | `SourceRegistryServiceTest`(32) / `SourceRegistryControllerAuditTest`(17) / `SourceProfileValidatorTest`(8) / `SourceRegistryConcurrencyTest`(7) / `GlobalExceptionHandlerSourceStatusTest`(5) / `SourceProfileFixtureTest`(4) | 同上 |
| 25 | 测试夹具 | `analytics-server/source-profiles/p1-03-probe-1.v1.json` / `p1-03-probe-2.v1.json` | 同上 |
| 26 | 验收脚本 | 本目录 `e3-acceptance.ps1` | 同上 |
| 27 | 断言器 | 本目录 `e3-verify.ps1` | 清单生成后新增，不在 e3-05 内（用途见 §2 末） |
| 28 | 本文件 | 本目录 `README.md` | 清单生成后新增，不在 e3-05 内 |

> `raw/e3-05-deliverable-inventory.txt` 记录了 **28 个文件**在 E3 定稿时的**字节数与 sha256**，可用于逐字节复核：
> 即本表第 1–26 行覆盖的全部文件（27 个，含 `e3-acceptance.ps1`），外加测试辅助类
> `SourceRegistryTestSupport.java`（0 个 `@Test`，故未单列一行）。
> 注：`e3-acceptance.ps1` / 新增的 `e3-verify.ps1` / 本文件在清单生成之后仍有编辑（补断言、修脚注），
> 三者属于"证据脚本与说明自身"，不是产品交付物。

## 2. DoD 逐条 → 实测证据

| DoD | 实测结论 | 原始证据 |
|-----|----------|----------|
| SourceRegistry entity/mapper/repository/service/controller 齐备 | 7 个生产类 + 5 个 DTO 编译通过；Service 即 repository 层（MyBatis-Plus Mapper 之上），未再引入第二层 | `raw/green-01-test-compile.log`（7/7 模块 BUILD SUCCESS）、`raw/e3-05-deliverable-inventory.txt` |
| 禁止 controller 直接访问 mapper | Controller 构造器只接收 `SourceRegistryService` + `OperationAuditService`，无任何 Mapper 字段/导入 | 源码 + `SourceRegistryControllerAuditTest`（同包内以假 Service 驱动，触不到 Mapper） |
| API list/get/create/update/test/pause/activate 七个端点 | 七个端点全部在真实 HTTP 上跑通 | `raw/e3-03-scenarios-run5-final.txt`：S1 200、S2 200、S4/S4b/S12a 200、S7 200、S4c/S8/S10a 200、S12c 200、S10b/S12b 200 |
| `source_code` 创建后不可改 | 改 `source_code` → 409 `SOURCE_CODE_IMMUTABLE` | 同上 S6 行 |
| 返回 DTO 不含 credential 值 | 响应与审计摘要里均无口令/密钥字样 | 用例 `SourceRegistryServiceTest.viewExposesNoCredentialAndNoAbsolutePath`；E3 摘要扫描 `password/secret/token/credential` 命中 0 |
| 返回 DTO 不含绝对本机路径 | 绝对路径入参 → 400，且**回显被脱敏**：响应体不含 `Develop_code`、不含 `p1-03-probe-1.v1.json` | 用例 `rejectedAbsolutePathIsNotEchoedBack`、`createRejectsIllegalProfilePaths`、`updateRejectsIllegalProfilePath`；E3 S7c 行 |
| 激活前校验 profile 文件存在 | 种子源（声明文件不存在）→ `/test` `ok=false`、activate → 409 `SOURCE_PROFILE_INVALID` | 同上 S8、S9 行 |
| 激活前校验 Schema 合法 | 9 个顶层必需键逐项校验，缺键即 `profile_required_top_level_keys=false` | `SourceProfileValidatorTest`(8)；E3 S10a 行 `ok=true` 全项通过 |
| 激活前校验 sourceCode 一致 | 不一致 → `/test` `ok=false`（逐项明细给出"登记=x 文件=y"）、activate → 409 | 同上 S4c、S4d 行 |
| 激活前校验 version 一致 | 版本项独立断言并通过 | 同上 S4c 行 `profile_profile_version_matches` 明细 |
| 失败码 `SOURCE_NOT_FOUND` | 404 | 同上 S3 行 |
| 失败码 `SOURCE_CODE_IMMUTABLE` | 409 | 同上 S6 行 |
| 失败码 `SOURCE_PROFILE_INVALID` | 409（激活路径 S4d/S9） | 同上 |
| 失败码 `SOURCE_IN_USE` | 409（暂停当前源） | 同上 S11 行 |
| **并发激活只有一个当前源** | 8 路并发激活同一目标：8/8 返回 200，`SUCCESS` 审计行增量 **恰好 1**（只有一次真实切换），结束后 `ACTIVE runtime_profile` 行数 **=1**；混合 A/B 16 路并发：16/16 200、9 次真实切换、结束后 ACTIVE 行数 **=1** | 同上 S13a/S13b/S13c 行 |
| **每次变更有审计** | 审计 total 92 → 116（+24 行）：`SOURCE_ACTIVATE` 12 SUCCESS/2 FAILED、`SOURCE_CREATE` 3/1、`SOURCE_PAUSE` 1/1、`SOURCE_UPDATE` 1/3；失败尝试同样留痕 | 同上「审计总账」段 |

### 断言器：把"读日志"升级成"跑断言"

`e3-acceptance.ps1` 负责**跑**并把原始 HTTP 状态码、响应体、DB 计数落盘，它自身只对
「实例起来了 / 登录拿到 token」等 4 处做硬校验，场景结局是**记录**而非**断言**。
为此本目录另附 `e3-verify.ps1`：它独立读同一份原始日志，把上表每一条期望写成断言，
任一不符即 FAIL 并以非 0 退出。

- `raw/e3-06-verify-run5-final.txt`：**断言 62 条，失败 0，退出码 0**（canonical 轮）
- `raw/e3-06-verify-smoke-javaw-8094.txt`：**断言 62 条，失败 0，退出码 0**（另一轮、另一端口）

两轮都过，说明断言写的是**系统不变式**，不是"把某一轮的输出抄成期望"。断言刻意分两层：

- **不变式**（任何轮次、任何端口都必须成立）：`ACTIVE runtime_profile` 恰好 1 行、
  同目标 8 路并发只产生 1 次真实变更、每次真实变更都留 SUCCESS 行、`SOURCE_CREATE/PAUSE/UPDATE`
  的精确计数（3/1、1/1、1/3，与交错无关）、真库计数不变、收尾端口不再监听。
- **本轮快照**（只记录不断言）：审计总行数（run5=116 / smoke=115）、
  `SOURCE_ACTIVATE SUCCESS` 条数（12 / 11）、收尾时当前源 id（4 / 2）。
  并发交错下这些数字本就不是定值，把它们写成断言等于用"某一轮的观测值"冒充"系统不变量"。

## 3. 复现命令（原样可跑）

E3 只跑在 **8090/8091/8092 之外的 8093**，连**副本库**，不碰在跑的实例与真实库。

```powershell
# E1 编译（7/7 模块）
mvn -o test-compile -f analytics-server/pom.xml

# E2 全量回归（472 用例；forkCount=0 是本机内存约束下的既定跑法）
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djdk.attach.allowAttachSelf=true'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'
$env:SPARK_DRIVER_MEMORY='512m'
mvn -o test -f analytics-server/pom.xml -pl platform-app -am '-DforkCount=0'

# E3 端到端（重建副本库 → 起隔离实例 → 跑场景 → 收尾 → 真库哈希比对）
pwsh -NoProfile -File docs/acceptance/p1-03-source-registry-api-20260911/e3-acceptance.ps1 -Port 8093 -Concurrency 8 -RunLabel run5-final
pwsh -NoProfile -File docs/acceptance/p1-03-source-registry-api-20260911/e3-verify.ps1    -RunLabel run5-final -Port 8093
```

E3 实例的启动方式（脚本内已固定，这里说明为什么不是 `mvn spring-boot:run`）：
`analytics-server` **无法在 8091 实例存活时重新打包**（该实例持有 `platform-app-0.1.0-SNAPSHOT.jar`，
见 `raw/e3-01-package-blocked-by-running-8091.log`）。因此脚本用已构建好的
`target/classes` + 既有 fat jar 里的依赖 jar（59 个）组成 classpath，直接以 `javaw.exe` 启动。
staged 类文件与构建产物做过逐字节核对：**21/21 完全相同**（生产包全部 `.class` + Controller +
启动类 + 两个 common 类 + `application.yml`），见 `raw/e3-08-staged-classpath-provenance.txt` —— 
即 E3 跑的就是编译输出的那份代码，不是某个陈旧副本。

## 4. 实测数字（每个数字的 raw 出处）

| 项 | 数字 | 出处 |
|----|------|------|
| E1 编译 | 7/7 模块 SUCCESS，BUILD SUCCESS | `raw/green-01-test-compile.log` |
| E2 用例 | **472 tests，0 failures，0 errors，0 skipped**，BUILD SUCCESS，构建级 `[ERROR]` 行数 **0** | `raw/green-07-full-regression-final4.log` |
| E2 分模块 | 41 / 114 / 102 / 38 / 91 / 86（platform-common / connection-ingestion / warehouse-pipeline / metric-analysis / ai-decision / platform-app） | 同上 |
| E2 中 P1-03 自有用例 | 32+17+8+7+5+4 = 73 条全绿 | 同上 + `raw/e3-05-...` |
| E2 中 P1-02 冻结用例 | `SourceRegistryMigrationScriptTest`(6) 绿且未被改动（不在 `git status` 改动清单内） | 同上 |
| E2 未执行的用例 | `SourceRegistryMigrationMySqlIT`(5) 属 `-Dp1.it=true` 门控（surefire 默认只收 `*Test`），**本次 E2 未跑** | 同上 |
| E3 断言 | 62 条全过（canonical 轮与 smoke 轮各一次） | `raw/e3-06-verify-*.txt` |
| E3 实例 | `javaw.exe`、端口 8093、启动 4.745s；副本库启动即从 v16 迁到 **v17**（0.143s）；metric 库当前 v3 | `raw/e3-02-instance-run5-final-stdout.log` |
| E3 鉴权 | 无 token → 401 `UNAUTHORIZED`；operator token（无 RUNTIME_MANAGE）→ 403 `FORBIDDEN_PERMISSION` | `raw/e3-03-scenarios-run5-final.txt` |
| E3 并发 | 8 路同目标：8/8 200、SUCCESS 增量 1；16 路混合：16/16 200、9 次真实切换；两次收尾 `active_runtime_rows=1` | 同上 |
| E3 审计 | 92 → 116（+24）；摘要 24 条中 before==after 的 **0** 条；含凭据字样 **0** 条 | 同上 + `raw/e3-06-verify-run5-final.txt` |
| 真库未变 | meta sha256 `5728BA68…F7D9432`(3262447B) / metric sha256 `A7F16963…42561714`(32496B)，before==after | `raw/e3-04-real-db-unchanged-proof.txt` |
| 真库计数 | `pipeline_run=38`、`metric_snapshot=8`、`runtime_profile=1 source_id=1`、`source_registry=1`、`operation_audit_log=92` | `raw/e3-03-scenarios-run5-final.txt` 末段 |
| 副本库隔离 | `p103_*` 账号授权范围仅 `analytics_meta_p103` / `analytics_metric_p103` | `raw/e3-07-copy-db-grants-and-baseline.txt` |

## 5. 运行历史（哪几轮作废、为什么）

| 轮次 | 结果 | 处置 |
|------|------|------|
| run1 | 作废 | ① 绝对路径入参把原值**回显**进错误信息（信息泄露）；② 夹具内 `sourceCode` 与登记值不一致导致误判。修：新增 `SourcePathPolicy` 统一脱敏；夹具编码对齐。该轮自身日志已被后续轮次覆盖，结论以本表为准 |
| run2 | 作废 | 画像各项共用一句 detail，"哪一项不一致"不可定位。修：`/test` 改为逐项 detail + `actualSourceCode/actualProfileVersion` |
| run3 | 作废 | **真机发现审计摘要缺陷**：只改 `displayName`/`timezone` 的 PUT，审计 before/after 摘要完全相同（行 98）—— 有变更记录却看不出改了什么。修：`digestOf` 覆盖全部可变字段；新增 `digestCoversEveryMutableField` 钉住 |
| run4 | 作废（环境） | 实例在启动期以 `exit=-1073741510`(0xC000013A STATUS_CONTROL_C_EXIT) 退出，无 hs_err、stderr 为空 ⇒ 外部控制台关闭/按镜像名杀进程，非 JVM 故障。修：改用 `javaw.exe` 启动并加一次 6 秒内自动重启 |
| 8094 smoke | 有效（交叉验证） | 同脚本换端口跑通，62/62 断言通过，用于确认 run5 不是"碰巧一次好" |
| **run5** | **canonical** | console exit=0，62/62 断言通过，真库逐字节未变 |

## 6. 反熵声明

- **Deletion Class: none** —— 本任务没有删除任何代码/表/配置，只做新增与四处既有文件的小改
  （`AnalyticsApplication.java` 加 Mapper 扫描、`application.yml` 加 `platform.source.profile-root`、
  `PlatformBizException.java` 加 4 个码、`GlobalExceptionHandler.java` 加状态映射）。
- **唯一属主（新）**：源身份与生命周期由 `com.graduation.analytics.source.SourceRegistryServiceImpl` 独占；
  源词汇的持久化唯一属主是 `analytics_meta.source_registry` 表；
  「当前源」的唯一属主是 `runtime_profile` 里那一行 ACTIVE 绑定（不是 `source_registry.status`）。
  没有引入第二套并行真相、没有 fallback、没有双写。
- **路径规则的唯一属主**：`SourcePathPolicy`（仓库相对、POSIX 分隔符、无 `..`、非绝对、≤255 字符）；
  校验器与 Controller 都复用它，不各自实现。
- **新增错误码**：`SOURCE_NOT_FOUND`(404)、`SOURCE_CODE_IMMUTABLE`(409)、`SOURCE_PROFILE_INVALID`(409)、`SOURCE_IN_USE`(409)。
- **新增审计动作**：`SOURCE_CREATE` / `SOURCE_UPDATE` / `SOURCE_ACTIVATE` / `SOURCE_PAUSE`（SUCCESS 与 FAILED 都落行）。
- **未触碰的冻结面**：`V16` 迁移与校验和（`-2113384091`）、P1-02 的 `SourceRegistryMigrationScriptTest` /
  `SourceRegistryMigrationMySqlIT`、`RuntimeProfileServiceImpl`、`spark-jobs/**`、`mall-simulator/**`、
  `mall-frontend/**`、`synthetic-data-generator/**`、`analytics-server/ai-decision/**` 生产代码。

## 7. 未取证清单（明确没做到 / 没测的）

1. **跨实例并发**未测：「只有一个当前源」是在**单实例 + 真实 MySQL 行锁**下证明的（E3 S13）；
   两个 JVM 同时激活依赖同一条 `FOR UPDATE` 路径，但**没有实测**。
2. **P1-05 相关路径**未测：V17 之后不带 `source_id` 的文件接入会因
   `Field 'source_id' doesn't have a default value` 失败（D-037 时序约束），E3 场景不触发接入路径。
3. **画像文件体积**无上限：`SourceProfileValidator` 用 `Files.readString` 全量读入，超大文件会走到
   INTERNAL 而不是干净的 4xx。属已知边界，**故意未改**（避免扩大本次契约面）。
4. **审计摘要的字段边界**：摘要是人读格式，若字段值本身含 `;` 或 `=`，边界会含混
   （`OperationAuditService.digest` 是唯一属主，本次不动它）；512 字符截断会显式追加 `…(截断)`。
5. **E2 并发用例的层次**：`SourceRegistryConcurrencyTest`(7) 用 `ReentrantLock` 假件 + 测试自声明的
   事务边界来验证 Service 逻辑，属 **JVM 级**证据；**DB 级真相只来自 E3**。
6. **E3 脚本的自动重启分支**未被执行过：run5 首启即成，重试分支属未走到的代码路径。
7. **无覆盖率数据**：没有跑 jacoco，"73 条用例"只是用例数，不代表覆盖率。
8. **`*MySqlIT` 集成用例未跑**：`SourceRegistryMigrationMySqlIT`(5) 等 IT 类被 surefire 默认规则排除，
   需 `-Dp1.it=true` 才跑；本次 E2/E3 都未执行它们（E3 验的是运行期行为，不是迁移脚本的 IT）。
9. **无前端/浏览器验证**：源管理目前没有 UI，本任务不涉及。
10. **`POST /api/v1/sources` 返回 200 而非 201**（无 `Location`）：故意保持与既有 Controller 一致，未测 201 语义。
11. **`SOURCE_IN_USE` 只守「暂停当前源」**：没有 delete 端点，故不存在"删除在用源"的守卫，未测。

## 8. 边界与风险

- **Controller 层审计有窗口**：审计行在 Service 事务提交后由 Controller 写入，提交与写审计之间极小窗口内
  进程被杀会丢一条审计行。这与既有 `DecisionController` 的既有做法一致；**故意不给 Controller 加 `@Transactional`**
  （那会把 HTTP 层拉进事务语义，且审计失败会回滚业务）。E3 未构造该窗口。
- **失败的尝试也写审计行**：`FAILED` 行数因此不为 0（本轮 7 条），这是设计而非噪声。
- **`status=ACTIVE` 与 `current=true` 不等价**：激活新源不会把旧源降级，`current` 由唯一绑定行推导；
  DoD 的"只有一个当前源"= `runtime_profile` 恰好一行 ACTIVE。判断依据见第 9 节。
- **命名空间措辞**：本任务**不写** `hive_database_prefix`（源级命名空间接管是 P2，见
  `contract-specs/specs/warehouse-namespace.v1.json` 第 30 行 `sourceOfTruth`）。
  证据只能说「当前源已从 A 切到 B（`runtime_profile.source_id` 变更）」，**库名前缀仍是 profile 级**。
- **V16 种子源当前不可激活**：其 `profile_path` 指向 `analytics-server/source-profiles/mock-mall.v1.json`，
  该文件属 P3-01 交付物、本任务**禁止创建**，因此种子的 `/test` 为 `ok=false`、activate 为 409。
  这是数据状态而非代码缺陷，已由 `SourceProfileFixtureTest` 钉住（D-035 §11 预期行为）。

## 9. 需父会话裁决点（我做了取舍，请确认或改判）

1. **复用 `PermissionCode.RUNTIME_MANAGE`** 而未新增权限码 —— 该码是 admin 专属（E3 中 operator 得到 403）。
2. **"没有 ACTIVE runtime_profile" 时用 `PARAM_INVALID`(400)**，未新增专用码。
3. **DISABLED 源不可 activate/pause**（fail-closed），而非"允许复活"。
4. **`ingest_mode` 只接受 `FILE`**，`JDBC`/`HTTP` 一律拒绝（本阶段无实现，拒绝优于假装支持）。
5. **create 返回 200**（非 201/无 `Location`）。
6. **不暴露 `changed` 字段**：幂等激活（S10c）在响应上与真实切换（S10b）看起来一致，差异只在审计行数。
   若调用方需要区分，需在 DTO 上加字段并同步契约。
7. **`/test` 不再检查落地目录可读性**（只校验路径策略/存在/JSON/编码/版本/必需键/状态迁移）。

## 10. Git 留痕

本任务**未做任何 git 提交**（git 操作被禁止）。`git status --porcelain` 中与 P1-03 相关的条目原样如下：

```
 M analytics-server/platform-app/src/main/java/com/graduation/analytics/AnalyticsApplication.java
 M analytics-server/platform-app/src/main/resources/application.yml
 M analytics-server/platform-common/src/main/java/com/graduation/analytics/common/GlobalExceptionHandler.java
 M analytics-server/platform-common/src/main/java/com/graduation/analytics/common/PlatformBizException.java
?? analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/
?? analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/source/
?? analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/SourceRegistryController.java
?? analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/SourceRegistryControllerAuditTest.java
?? analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceProfileFixtureTest.java
?? analytics-server/platform-common/src/test/java/com/graduation/analytics/common/GlobalExceptionHandlerSourceStatusTest.java
?? analytics-server/source-profiles/
?? docs/acceptance/p1-03-source-registry-api-20260911/
```

> 同一份 `git status` 里另有 **32 条** `synthetic-data-generator/**` 条目与 **1 条**
> `docs/acceptance/m1-4-s4b-mall-api-20260911/` 条目，属于**并行代理**，与本任务无关，本任务未触碰、也不认领。

## 11. raw/ 索引

| 文件 | 内容 |
|------|------|
| `pre-snapshot-*` / `post-e2-snapshot-*` / `pre-flyway-meta.txt` | E2 前后的真库计数与 flyway 状态 |
| `red-01-test-compile.log` | 先红：编译失败（`n/a` 非法标识符） |
| `green-01-test-compile.log` | E1：7/7 模块编译通过 |
| `green-02..green-07*.log` | E2 逐轮回归，`green-07-full-regression-final4.log` 为定稿（472 用例） |
| `e3-00*.log` | 定位/修复编译与用例问题的过程日志 |
| `e3-01-package-blocked-by-running-8091.log` | 8091 占用 jar 导致无法打包的原始报错 |
| `e3-02-instance-*-{stdout,stderr}.log` | 各轮隔离实例日志（run5 定稿：启动 4.745s、V17 应用 0.143s） |
| `e3-03-scenarios-run5-final.txt` | **canonical 场景日志（242 行）** |
| `e3-03-scenarios-*.txt` | 其余轮次场景日志（含 run1/2/3/4 的作废轮与 8094 smoke） |
| `e3-04-real-db-unchanged-proof.txt` | 真库逐字节未变证明（三轮复核） |
| `e3-05-deliverable-inventory.txt` | 28 个交付物的字节数 + sha256 |
| `e3-06-verify-run5-final.txt` / `e3-06-verify-smoke-javaw-8094.txt` | 断言器报告（各 62 条，0 失败） |
| `e3-07-copy-db-grants-and-baseline.txt` | 副本库授权与状态（隔离性的结构性证据） |
| `e3-08-staged-classpath-provenance.txt` | E3 实例 classpath 的来源证明（staged 类文件 vs 构建产物，21/21 逐字节相同） |
