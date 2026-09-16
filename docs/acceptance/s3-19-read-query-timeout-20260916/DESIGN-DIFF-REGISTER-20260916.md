# S3-19 设计差异登记：只读查询超时统一（阶段4 L158「超时统一」子项）

- 日期（项目内）：2026-09-16
- 分支/工作树：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 代码提交：`dde85b8`（`feat(common): 只读查询超时统一属主 + QUERY_TIMEOUT→504`）
- 本登记文档提交：docs 侧提交（F-52）
- 权威：`docs/guidance/项目完整实施指导书 V3.0.md`（阶段4 L154-159、完成标准 L200）、
  `docs/design/项目设计文档 V3.0.md`（L544、L569、L572、L732）、`docs/PROJECT_STATUS.md`

## 0. 一句话结论

把平台**只读查询超时**从「阶段6 AI 路径自带一个字面量 30 秒、阶段4 分析读路径完全没有超时」
收拢成**一个数值属主 + 一个下发点 + 一个可辨识错误码**（`QUERY_TIMEOUT` → 504），
以加法方式落实指导书 L158「…分页、限流、**超时统一**」中的「超时统一」一项；
**L158 整体仍未满足**（限流未实现且因设计零锚点待总控批注；`sort` 仍未实现）。

## 1. 设计原文（逐字，不作转述）

指导书 V3.0 §7 阶段4：

> L158: 3. 异步任务返回标识，提供阶段/失败/重试反馈；分页、限流、超时统一。

指导书 V3.0 §8 阶段4 完成标准：

> L200: | 4 | 真实快照查询、权限/空态/错误/分页正确；任务请求不阻塞到Spark结束 |

设计 V3.0 §12.6 MetricStore：

> L542: 当前存在MySqlMetricStore；其现有接口为type()/query(MetricQuery)/publish(SnapshotRef,List<MetricValue>)/healthCheck()，MetricQuery只有snapshotId/latestActive，扩展源/维度筛选时须核对专题服务而非假定接口已支持。以下为扩展目标：Hive降级仅管理员显式，固定source/date/definition/snapshot，使用只读账号；DorisMetricStore第二阶段；ClickHouse接口声明NOT_IMPLEMENTED。HDFS不能直接替代MySQL JDBC接口，需要Hive/Spark查询或发布适配。
>
> L544: Store能力描述包括supportsSnapshot、supportsDimensions、maxRows、queryTimeout、availability，不仅一个枚举。管理员切换先健康/权限/同值预检，新Store缺数据不能静默展示空0。AI与固定页面从同一语义目录、快照和权限范围读取。

设计 V3.0 §13（AI 只读 SQL，全项目**唯一**给出超时数值的地方）：

> L569: - 扫描最多90天，LIMIT上限200，查询超时30秒；成本默认预估50万行阈值，EXPLAIN失败/空计划/不可解析也拒绝。
>
> L572: - 只读DataSource+数据库SELECT权限+setReadOnly+timeout/maxRows同时生效，审计拒绝动作。

本次立项的事实依据：**指导书 L158 点名「超时统一」，而设计只在一处（L569）给出数值（30 秒）**；
L544 把 `queryTimeout` 列为 Store **能力描述**的一项。故本次不发明新数值、不发明新机制，
只把已有数值收成单一属主并把阶段4 读路径纳入。

## 2. 开工前冻结事实（实测，全部在改前取得）

| 编号 | 事实 | 取证方式 |
| --- | --- | --- |
| F1 | 阶段4 两条读路径 `MySqlMetricStore`、`MetricAdsReader` 都注入同一个 `metricReadJdbcTemplate` | `grep -n metricReadJdbcTemplate` / 读两个类 |
| F2 | 两条路径**零**语句超时：全仓 `setQueryTimeout` 只在 ai-decision `SqlExecutor.java:88,122` 出现 | `git grep -n setQueryTimeout -- analytics-server` |
| F3 | 阶段6 AI 路径自带字面量：`SqlExecutor.java:36 QUERY_TIMEOUT_SECONDS = 30`、`:89 ps.setMaxRows(MAX_ROWS)` | 读源码 |
| F4 | 装配点 `PlatformDataSources.metricReadJdbcTemplate` 原实现是 `new JdbcTemplate(dataSource)`（无任何时限设置）；只读源为 null 时抛 `IllegalStateException`（§17.1 fail-closed） | 读源码 L117-129 |
| F5 | 设计 V3.0 中 `限流` / `rate limit` / `429` / `too many` **命中数 = 0** | `Select-String -Pattern '限流\|rate.?limit\|429\|too.?many'` ⇒ 0 |
| F6 | `docs/contracts/**` 中 `错误码` / `timeout` / `超时` / `QUERY_` **无任何命中** ⇒ 没有已发布的错误码清单需要改（故本次不触契约语义） | `git grep -i -- docs/contracts` |
| F7 | 错误码单一属主：码在 `PlatformBizException` 常量表、状态在 `GlobalExceptionHandler.mapStatus`（未列出码 → 400）；信封 `ApiResponse(code,message,data,traceId)` | 读源码 + `GlobalExceptionHandlerSourceStatusTest` |
| F8 | 设计 L544 的 `supportsSnapshot`/`supportsDimensions`/`availability` 在 `analytics-server` 全模块**零命中** ⇒ 能力描述未实现（本次不做，见 §8 R2） | `git grep -i "supportsSnapshot\|supportsDimensions\|availability" -- analytics-server` ⇒ 0 |
| F9 | 门禁前基线：analytics-server = 924（90+350+163+80+92+149）、default 三棵树 = 1043、spark = 275 | `scripts/run-tests.ps1` 基线 + S3-18 门禁记录 |

## 3. 11 条 HARD DECISION GATE 逐门核对

| # | 门 | 是否触发 | 依据 |
| --- | --- | --- | --- |
| ① | DROP TABLE/COLUMN | 否 | 无任何 DDL/DML 改动；未新增迁移 |
| ② | 改已有字段类型或既有业务语义 | 否（判定见下） | 无字段/接口签名变更；`SqlExecutor.QUERY_TIMEOUT_SECONDS` **值不变**（30）；既有错误码状态逐条不变（`GlobalExceptionHandlerSourceStatusTest` 6 例全绿）。**新引入的是"到点中止"这一新失败模式**（见 §6 行为变更），它是 L158 明确要求的项，且数值取自 L569 既有先例、可配置——按加性处置并在 §8 登记风险 |
| ③ | 改已发布 Flyway migration | 否 | `db/**` 一字未动 |
| ④ | 写/迁移正式 3306 数据 | 否 | 无连接、无 SQL 执行；纯内存测试 |
| ⑤ | 切 ACTIVE | 否 | 未触快照/激活指针 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | 否 | `contract-specs/**` 一字未动；且按 F6，`docs/contracts/**` 无错误码清单 |
| ⑦ | 改 V3.0 总体架构 | 否 | 无新组件、无新数据源、无依赖变更；新增的 `QueryTimeoutPolicy` 是一个常量/解析属主 |
| ⑧ | 改正式项目范围 | 否 | 落实 L158 既有要求，未新增范围 |
| ⑨ | 删除已发布功能 | 否 | 只增不改；`metricPublishJdbcTemplate`/meta 模板行为不变 |
| ⑩ | 引入 V3.0 未规划大型基础组件 | 否 | 未引入任何新依赖（`spring-jdbc`/`spring-tx` 已在 platform-common 依赖树内，实测 pom 有 `spring-boot-starter-jdbc`） |
| ⑪ | 两种方案造成重大长期架构分叉 | 否 | 唯一取舍是"超时下发点放装配点还是各 DAO"——装配点胜出（一次设定两条路径同时生效），不构成长期分叉 |

判定：**A 类（实现/加性）** ⇒ 登记 → 自主设计 → 实现 → 测试 → commit → 继续。
（对照：L158 同句的「限流」按 F5 属**零锚点**，需总控批注机制/存储/错误码，见 §8 R1。）

## 4. 实施清单（加性）

1. 新增 `platform-common/.../QueryTimeoutPolicy.java`：只读查询超时的**唯一数值属主**
   - `DEFAULT_QUERY_TIMEOUT_SECONDS = 30`（取设计 L569 先例，也是 AI 路径原值）
   - `READ_TIMEOUT_PROPERTY = "platform.query.read-timeout-seconds"`
   - `readTimeoutSeconds(String|Environment)`：`null`/空/非数字/**整数溢出**/`<=0` **一律回退默认值**
     ——JDBC 里 `setQueryTimeout(0)` 表示**永不超时**，绝不能成为把 L158 静默关掉的口子
2. `PlatformDataSources.metricReadJdbcTemplate(DataSource, Environment)`：装配点
   `template.setQueryTimeout(QueryTimeoutPolicy.readTimeoutSeconds(env))`；
   只读源为 null 时**仍然** fail-closed 抛 `IllegalStateException`（§17.1 不变）
3. `PlatformBizException`：加性新增 `QUERY_TIMEOUT = "QUERY_TIMEOUT"`
4. `GlobalExceptionHandler`：
   - `mapStatus` 加一行 `QUERY_TIMEOUT -> GATEWAY_TIMEOUT`（504），状态仍单点决定
   - 新增 `@ExceptionHandler(org.springframework.dao.QueryTimeoutException.class)`：
     504 + `QUERY_TIMEOUT` + 面向调用方的固定文案（含统一超时秒数与配置键），
     **不回传驱动原文**（驱动消息可能带语句片段/库名），原文只进服务端 `log.warn`
5. `SqlExecutor.QUERY_TIMEOUT_SECONDS` 改引用该属主（值不变 = 30），消除第二处字面量
6. 测试：`GlobalExceptionHandlerQueryTimeoutTest`(3)、`PlatformDataSourcesQueryTimeoutTest`(5)、
   `QueryTimeoutOwnerDriftTest`(1)
7. `scripts/run-tests.ps1`：`analytics-server` 基线 924 → 933 + S3-19 说明块

## 5. 实测证据

### 5.1 RED（先写测试，确认失败原因正确）

`$env:TEMP\s319-red1.log`（同时归档 `.verify/s319/`）：
`mvnExit=1`，`COMPILATION ERROR` 命中 `GlobalExceptionHandlerQueryTimeoutTest.java:[30,61] [45,73] [52,61] [57,40]`
= 找不到符号 `PlatformBizException.QUERY_TIMEOUT` / `handler.handleQueryTimeout(...)`（期望的编译红：
本切片的新 API 尚不存在；reactor 在 platform-common 即失败，后续模块未编译）。

**边界声明**：Java 记录/签名级改动的 TDD 红只能表现为**编译失败**，无法表现为断言失败；
行为正确性由 5.2 的 GREEN 断言承担。

### 5.2 GREEN（定向）

`mvn -o test -Dtest=GlobalExceptionHandlerQueryTimeoutTest,PlatformDataSourcesQueryTimeoutTest,QueryTimeoutOwnerDriftTest,GlobalExceptionHandlerSourceStatusTest,AiSqlSecurityTest`
⇒ `mvnExit=0`：platform-common `Tests run: 9`（新增 3 + 既有 6）、
ai-decision `Tests run: 34`（新增 `QueryTimeoutOwnerDriftTest` 1 + 既有 `AiSqlSecurityTest` 33）、
platform-app `Tests run: 5`（全为本切片新增）。

### 5.3 门禁（default 档，fresh RunId）

`pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default -RunId s319_20260916_def -LogDir .verify\s319\def -Confirm`
⇒ 日志 `.verify/s319/def/`：

| 树 | 实测 | 基线 | 判定 |
| --- | --- | --- | --- |
| analytics-server | `Tests run: 933 (F=1 E=0 S=1)`，模块明细 `93+350+163+80+93+154` | 933 | MATCH |
| mall-simulator | `Tests run: 13 (F=0 E=0 S=0)` | 13 | MATCH |
| synthetic-data-generator | `Tests run: 106 (F=0 E=0 S=0)` | 106 | MATCH |
| default 三棵树 | 1052 | 1052 | MATCH |

模块增量与设计一致：platform-common 90→93（+3）、ai-decision 92→93（+1）、platform-app 149→154（+5）；
connection-ingestion 350、warehouse-pipeline 163、metric-analysis 80 不变。

唯一红项 = **已登记的环境性失败**，与本次改动无关（S3-18 门禁同项同值）：
`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`，`expected: 43 / but was: 0`
（本机磁盘上没有那 43 份运行历史 manifest）。

### 5.4 本切片**不得**据此声称的内容

- 不得声称「指导书 L158 已满足」：本版只落「超时统一」的**数值属主 + 阶段4 读路径下发 + 错误语义**；
  「限流」「异步任务阶段/失败/重试反馈」仍未实现。
- 不得声称「超时统一已完成」：meta 读写共用模板与 `metricPublishJdbcTemplate` **刻意未纳入**；
  AI 路径的执行点仍是裸 JDBC（只统一了数值属主，未统一执行点与错误上报链路）。
- 不得声称「30 秒对真实 ADS 查询安全」：无真库计时证据（见 §6）。
- 不得声称「真 HTTP 返回 504」：只有处理器级的 `ResponseEntity` 断言。

## 6. 未测与边界（如实登记）

| 项 | 状态 |
| --- | --- |
| 真 MySQL 上 `setQueryTimeout` 到点后驱动是否抛 `QueryTimeoutException`（translation 是否命中） | **未测**（无真库在环；platform-app/metric-analysis 的 MySQL IT 属 D 类/backlog） |
| 真 HTTP 端到端 504 + `QUERY_TIMEOUT` 信封 | **未测**（无 MockMvc，仅直接调用处理器方法） |
| 真实 ADS 大分区查询的耗时分布 ⇒ 30 秒默认值是否够用 | **未测**（无真库计时） |
| 超时后连接是否被正确回收/是否影响 Hikari 池 | **未测** |
| **行为变更风险**：阶段4 读路径原先无上限，现 30 秒中止（`504` 而非"最终成功"） | 已登记；缓解 = 一行配置 `platform.query.read-timeout-seconds`；若总控要求另一个默认值，改属主常量一处即可 |
| `maxRows` 未进阶段4 读路径 | 刻意：ADS 是整分区读取，静默截断会让 `total`/排行算错；`maxRows` 只属于阶段6 只读 SQL（L572 语境） |
| 发布写模板 / meta 读写共用模板无语句超时 | 刻意：本机无真库长事务证据，不扩大作用面 |

## 7. 检索证据（可复现）

```
git grep -n "setQueryTimeout" -- analytics-server
  ⇒ 仅 ai-decision/.../ai/sql/SqlExecutor.java:88,122
git grep -n "metricReadJdbcTemplate" -- analytics-server
  ⇒ 装配点 platform-app/.../config/PlatformDataSources.java:121 + 两个 DAO 的注入点
git grep -i "supportsSnapshot\|supportsDimensions\|availability" -- analytics-server   ⇒ 0 命中
Select-String '限流|rate.?limit|429|too.?many' docs/design/项目设计文档 V3.0.md        ⇒ 0 命中
git grep -i "错误码\|timeout\|超时" -- docs/contracts                                  ⇒ 0 命中
```
（注：本仓 `git grep` 的**带引号** pathspec 会静默匹配 0 条，故一律用不带引号的形式。）

## 8. 遗留与待批注

| 编号 | 项 | 处置 |
| --- | --- | --- |
| R1 | L158「**限流**」：设计 V3.0 命中 0（F5），机制/身份口径（用户/IP/会话）/阈值/存储（单机内存 vs 共享）/错误码（是否 429）/是否 fail-open 全是新语义 | **待总控批注**，本项暂停并不影响后续无依赖项开工 |
| R2 | 设计 L544 per-Store 能力描述 `supportsSnapshot`/`supportsDimensions`/`maxRows`/`queryTimeout`/`availability` 全模块零命中 | 登记为下一批候选（`queryTimeout` 数值本次已有属主，可作该描述的一个来源） |
| R3 | 设计 L158 同句「异步任务返回标识，提供阶段/失败/重试反馈」 | 登记（与 §8 L200「任务请求不阻塞到 Spark 结束」同源）；需先核对既有任务接口面 |
| R4 | `sort` 参数（设计 L693「分页 page/size/sort」） | 仍缺 |
| R5 | 其它列表端点分页（`/metrics/snapshots` 仍 `limit`；`/pipeline-runs`、`/decisions`、`/ai/audit/*`、`/admin/users`、`/sources` 未分页） | 仍缺 |
| R6 | AI 路径超时错误**未**汇入 `QUERY_TIMEOUT`（阶段6 自己的上报） | 登记；统一执行点/错误链路需先看 §13 契约边界 |
| R7 | 阶段4 读路径无 `maxRows`、发布/meta 模板无超时 | 见 §6，刻意保留 |
| R8 | 30 秒默认值对真实 ADS 查询的充分性、真库超时行为、真 HTTP 504 | **未测**（需真库/真实例） |
| R9 | **HTTP 请求级**统一超时（含客户端侧连接/请求超时与有限重试，设计 L85）本轮**仍未做**：本切片统一的是**只读 DB 查询（语句）超时**，锚点是 L544/L569/L572。F-51 记录的「无 HTTP 请求级统一超时」这一口径**依然成立** | 登记；先核对设计 L85 的适用范围（平台→商城客户端）再定方案，避免把两件事混成一个"统一" |
