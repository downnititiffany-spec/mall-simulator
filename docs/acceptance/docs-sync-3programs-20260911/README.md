# 文档与脚本一致性泳道 · 三程序对齐证据（docs-sync-3programs-20260911）

> 泳道职责：把**脚本与演示/论文素材**中残留的"单进程/双程序"口径，对齐到本仓库当前的
> **三程序独立形态**（`:8090` 模拟商城 ／ `:8091` 分析平台 ／ `:8092` 合成数据生成器），
> 并且**只用从源码实测出来的端点归属**改脚本 —— 不发明端点、不静默替换。
>
> 铁律：**未实测不写结论、Mock 不当真实**。本文件里每一处论断都带 `文件:行` 或命令原始输出；
> 凡没跑过的（三程序未启动时无法取得的运行时证据），一律写进 §4「未取证清单」，不写成结论。
>
> 本目录为**新增**证据目录，未修改 `docs/acceptance/**` 下任何既有文件。

---

## 0. 一句话结论

`scripts/run-demo.ps1`、`scripts/final-accept.ps1`、`docs/demo/demo-script.md` 里
**所有非商城端点都被打向了 `:8090`**（沿袭已废弃的单进程形态），其中生成器端点
`POST /api/v1/generator/runs` 在当前仓库中**已彻底不存在**；已按实测端点归属改为
`$Mall`/`$Platform`/`$Generator` 三个 base URL，并把无端点可用的步骤如实标为 **缺口**而非伪造通过。

**顺带查出三处比端点更要紧的数据安全问题**（均为只读实测，详见 §3 / §5.7-§5.9）：

1. **旧脚本的"清库"清错了对象**：四条 DELETE 打的是**平台侧**表
   （`file_checkpoint`/`ingestion_batch`/`ingestion_batch_file`/`quarantine_record`），
   而 `mall_simulator` 库里**确实存在**这些表且非空（旧单进程架构遗留，`flyway_schema_history` 7 条记录为证）
   ⇒ 那不是空操作，是**真删平台侧数据**。两条脚本里的这批 DELETE 已全部移除。
2. **清 landing 也清错了地方**：商城实际把产物写到**仓库根** `landing\events`
   （不是 `mall-simulator\landing\events`，后者为空），而**平台采集落地根是同一个目录**
   ⇒ 无脑 `Remove-Item *.jsonl` 会删掉平台侧真实已采数据（该目录现有 55 个文件、含 2026-09-06 的 12 MB 业务日文件）。
   已改为**只删 2 小时内新文件**并把跳过的旧文件数打印出来。
3. **口令前提被写错**：`mysql -uroot` **不是免密**（实测 `ERROR 1045 … using password: NO`），
   而 `init-three-dbs.sql` 授权的 `mall_app` 对 `mall_simulator` **无权限**（它只看得见空库 `mall_business`）
   ⇒ 真跑前必须设 `MALL_DB_PASSWORD`，且不能用 `init-three-dbs.sql` 那套账号清库。

---

## 1. 改动表（旧值 → 新值 → 依据）

### 1.1 `scripts/run-demo.ps1`（全文重写，69 行 → **186 行 / 15,275 字节**）

| 位置 | 旧值 | 新值 | 依据 |
|---|---|---|---|
| 顶部说明 | "请先在 mall-simulator 目录 `mvn spring-boot:run`"（把三程序当一程序） | 三程序边界声明 + 各程序端口/库 + 端点归属指向本 README | `scripts/start-all.ps1:1-4`（三程序口径）；`synthetic-data-generator/.../application.yml:2` 原话"§3.1 第三个独立程序" |
| 参数 | `$Base = 'http://127.0.0.1:8090'` 单变量 | `$Mall`/`$Platform`/`$Generator` 三个 base（默认 8090/8091/8092） | 各程序 `application.yml` 的 `server.port`（`mall-simulator/.../application.yml:2`=8090、`analytics-server/platform-app/.../application.yml:2`=8091、生成器 `:12`=8092） |
| 探活 | 只用 8090 一个端点 | 三程序各打自己的探活端点：商城 `GET /`、平台 `GET /api/v1/metrics/health`、生成器 `GET /api/v1/scenarios` | `SpaFallbackController.java:16`、`MetricController.java:59`、`ScenarioController.java:26`（均在 §2 清单内，带行号） |
| 登录 | 单次 `POST http://127.0.0.1:8090/api/v1/auth/login` 后拿这个 token 打**所有**端点 | **两次**登录：商城 token 打商城、平台 token 打平台 | 两程序各有一套用户表/会话（`AuthController.java:31,43` × 2，见 §2）；跨程序用 token 会 401 |
| `-Clean` | 无条件 `mysql -uroot -e "DELETE FROM mall_simulator.event_outbox;"` + 删 `mall-simulator\landing\events\*.jsonl` | 保留但改为**双门禁**：必须显式传 `-Clean` **且** 提供 `$env:MALL_DB_PASSWORD`，否则只打印一行"跳过清场" | `mall-simulator\src\main\resources\db\migration\V1__init_mall.sql:116`（`event_outbox` 是**商城自有表**；该迁移脚本 10 张表全是商城业务表，**不含任何采集元数据表**）；landing 目录由 `mall-simulator\src\main\resources\application.yml:33`（`mall.landing.path` 默认 `./landing`）+ `scripts/start-all.ps1:47`（`-WorkingDirectory $root`）⇒ 相对**仓库根**解析，即 `mall-simulator\landing\events`（实测该目录存在）；平台无任何 reset/cleanup 路由（§3 缺口 G-2） |
| `[1]` 造数 | `POST $Base/api/v1/generator/runs` + `{userCount, eventsPerSecond, baseConversionRate, startTime, endTime, randomSeed, dirtyDataRate, scenario}` | 标 `TODO(缺口)`，给出生成器真实现状（`POST /api/v1/generation-runs` 只收 `{plan_id, version}`；计划版本只能 CLI 建），并写明 CLI 两跳命令 | §3 缺口 G-1；`GenerationRunController.java:25,35`；`GeneratorApiDtos.java:31-34`；`GeneratorCli.java:29-31,35-37` |
| `[2]` 采集**前置判据** | 未判 `noNewData`，只看行数 | 若 `noNewData=true` 立即停（`exit 4`）并提示"本次无新数据，商城未产出 landing" | `IngestionService.java:63-70`：`noNewData` 是**独立信号**（"本次没有读到任何新字节（数据源未产出）"），`SUCCESS + 0 条` 与"数据源没产出"是两件事 |
| `[2]` 发布 | 只被动轮询 `GET /api/v1/mall/outbox/status`（8090），未说明发布由谁触发 | 轮询期间若 `pendingCount > 0` 则显式调 `POST /api/v1/mall/outbox/publish` | `OutboxController.java:21,27,40`（商城侧确有 publish 端点）；**补充实测**：商城 Outbox 本就有**定时发布**（`mall-simulator/.../application.yml:34-36`：`batch-size: 5000`、`poll-seconds: 3`）⇒ 追加显式 publish 只是把"等定时轮"变成"立即推"，**不是必需**，不会改变语义 |
| `[3]` 采集 | `POST $Base/api/v1/ingestion/runs`（打到 8090） | `POST $Platform/api/v1/ingestion/runs` | `IngestionController.java:25,37`（平台侧） |
| `[3]` 流水线 | `POST $Base/api/v1/pipeline-runs` 后**直接读响应**判断"阶段 x/7 成功" | `POST $Platform/api/v1/pipeline-runs` 取 `runId` → **轮询** `GET /api/v1/pipeline-runs/{runId}` → 断言 `SUCCESS` 且阶段数 **8** | `PipelineController.java:31,45,55`；`PipelineService.java:165-167`（"立即返回 PENDING taskId，计算链异步执行"）；`PipelineService.java:205-207`（`STAGE_ORDER` 恰 8 项） |
| `[4]` 指标 | `GET $Base/api/v1/metrics/overview`（打到 8090） | `GET $Platform/api/v1/metrics/overview` | `MetricController.java:28,36`（平台侧） |

### 1.2 `scripts/final-accept.ps1`（全文重写，69 行 → 161 行 / 13228 字节）

| 位置 | 旧值 | 新值 | 依据 |
|---|---|---|---|
| 交付形态声明 | 启动 `mall-simulator\target\*.jar`，注释写这是"**单进程交付形态**" | **删除自启动**；改为"三程序独立形态"，并只做**只读**产物存在性检查（三份 jar 各自列出名字/时间） | 三程序边界（§3.1/§3.4）；脚本无权代人起停服务 |
| 参数/端点 | `$Base='http://127.0.0.1:8090'`，11 处调用全打 8090 | `$Mall`/`$Platform`/`$Generator` 三个 base，逐一按 §2 清单归属 | 同 1.1 |
| 前置 | 自己 `Start-Process java -jar` 起商城 | 只做三程序就绪轮询（各打自己探活端点），未就绪即 FAIL | 同上 |
| **清库（整段移除）** | `mysql … DELETE FROM mall_simulator.event_outbox; …file_checkpoint; …ingestion_batch; …ingestion_batch_file; …quarantine_record;` + 删商城 landing `*.jsonl` | **整段删除**，替换为一行 `[INFO] 本脚本不清理任何数据`。`-DbPassword` 参数与 `mysql`/`MYSQL_PWD` 全部消失（实测残留扫描为空） | ①**语义矛盾**：验收脚本先清空来源数据、再断言"采集 `recordCount>0`"，等于自己删掉断言的前提；清场是**演示准备**动作，只该出现在 `run-demo.ps1 -Clean`。②**归属错误且非空操作**：后四张表由**平台**元库脚本创建（`V1__platform_ingestion.sql:8,24,37,45`），但 `mall_simulator` 库里**确实存在同名表且非空**（实测 30 张表中含这些平台侧表；`file_checkpoint`=3 行、`ingestion_batch`=1、`pipeline_run`=10，见 §5.7）⇒ 旧 DELETE **会真的删掉平台侧遗留行**，不是空操作 |
| 登录 | 只用 8090 的 token（却拿去打平台端点） | 平台、商城各登各的（Check 5/6） | 见 1.1 |
| 造数 | `POST $Base/api/v1/generator/runs` | 改为 `[GAP]` 项（缺口计数，不计 PASS 也不计 FAIL），并把 CLI 两跳命令写进注释 | §3 缺口 G-1 |
| 采集断言 | `$ing.data.recordCount -gt 0` | `recordCount>0` **且** `-not $ing.data.noNewData` | `IngestionService.java:63-70`：`noNewData=true` 表示"本次没有读到任何新字节（数据源未产出）"，是独立信号，不能只看行数 |
| 流水线断言 | `$stages.Count -eq 7`（且直接读 POST 响应） | 轮询至终态后断言 `status -eq SUCCESS` **且** `stages.Count -eq 8`（并把 8 行 `stageCode=status` 打印出来） | `PipelineService.java:205-207`；`PipelineService.java:165-167` |
| 商城下单请求体 | 硬编码 `productId:1001`，`userId` 以字符串直接塞进 `userId` 字段 | 商品 id 改为**先查 `GET /api/v1/mall/products` 再取 `data[0].id`**；`userId` 显式 `[long]` 转换后送出 | `MallDtos.java:19-23`（`CreateUserReq`）、`:31-34`（`OrderCreateReq`）、`:36-39`（`OrderItemReq`）、`:41-43`（`OrderPayReq` 的 `userId` 声明为 **Long**）；`MallController.java:47`（`/users` 返回的 `userId` 是**字符串**，原话"ID 超 JS 安全整数，统一字符串返回"）⇒ 不转换则绑定失败；不硬编码商品 id 是因为它依赖种子数据 |
| 退出码 | `FAIL>0 → exit 1` | `FAIL>0 → exit 1`；`FAIL=0 但 GAP>0 → exit 2`（"环境没坏、脚本与架构不同步"单独一档） | 防止缺口被读成"跑失败"，也防止被读成"通过" |
| 收尾 | `Get-Process java \| Where CommandLine -like '*mall-simulator*' \| Stop-Process -Force` | **删除**；仅留一行注释说明"三程序形态下不停止任何进程，停谁由运维按 §3.4-1 决定" | `scripts/start-all.ps1:5`（"停任一个不影响其余"） |

> 兼容性提醒：`run-demo.ps1` 的 `-Base` 参数**已被移除**，改用 `-Mall`/`-Platform`/`-Generator`。
> 仓库内其它文档/命令若引用 `-Base`，需同步（本次未发现其它脚本引用）。

### 1.3 `docs/demo/demo-script.md`

| 行（改后） | 旧值 | 新值 | 依据 |
|---|---|---|---|
| L6-11 | "分析平台与模拟商城是**两个独立进程、两套库、两份前端产物**" | 三程序（三进程/三端口/三套库/三份构建产物），并补"每个端点只属于一个程序：打错端口就是 404" | 同上 |
| L15-17 | "一键起两进程 … `-MallOnly`"（注释把"只造数据"错标成 MallOnly） | 三进程；补 `-GeneratorOnly`；`-MallOnly` 改标为"只演示模拟商城" | `scripts/start-all.ps1:9-12,54-56` |
| L26-35 | 只讲平台 token | 补商城 token（并写明"令牌不通用，用平台 TOKEN 打 `:8090` 会 401"） | 两套会话，见 §2 |
| **L37-42** | `curl -X POST http://127.0.0.1:8090/api/v1/generator/runs` + 旧参数体 | `:8092` 的 `POST /api/v1/generation-runs`（`{plan_id, version}`）+ 先跑生成器 CLI `plan-append` 建计划版本；并加 ⚠️ 提示旧命令已 404、参数语义已变 | §3 缺口 G-1；`GenerationRunController.java:25,35`；`GeneratorApiDtos.java:31-34`；`GeneratorCli.java:35-37`；`FileModeGenerationEngine.java:59` |
| 新增 L44-50（1b 段） | 无 | 补商城 Outbox `status` → `publish` 两步（发布后才轮到平台采集） | `OutboxController.java:21,27,40` |
| L154-155 | （无此行） | 说明"三程序都在跑"的前置 + `analytics-web/` 属平台 | `scripts/start-all.ps1:1-4` |

### 1.4 `docs/thesis-materials/证据映射表.md`

| 行 | 旧值 | 新值 | 依据 |
|---|---|---|---|
| L49 | "两个独立应用（不同进程、端口、库…）"；证据只列平台+商城；备注"可写「双进程双库」" | "**三个**独立应用"；补齐生成器端口/库证据；备注改"**三**进程三库" + 指向本 README | 生成器 `application.yml:12,20`（`:2` 原话"第三个独立程序"） |
| **L50** | 证据列写 `SimulationEngine.java:47`／`GeneratorRunService.java:19`／`GeneratorController.java:28` "**全在商城侧**"；测试数写 `mall-simulator 54/54` | 证据列换成**现存**文件（`GenerationRunController.java:25`、`FileModeGenerationEngine.java:59`、`service/GenerationRunService.java`）；备注显式写"**证据已失效（原文件已移除）**：旧三个文件名全仓 0 命中"；测试数改 **38/38** | 全仓 glob `SimulationEngine.java`/`GeneratorRunService.java`/`GeneratorController.java` **0 命中**；现存等价物在 `synthetic-data-generator/src/main/java/com/graduation/generator/**` |
| L51 | "`:28-35` 六模块"（行号错） | "`:16`（packaging=pom）、`:18`、`:40-47` 恰 6 条 `<module>`"；补**口径说明**：父 POM 不是 `<module>`，故模块数=6，"7 个 reactor 模块"是把父 POM 也算进去，已统一为 6 | `analytics-server/pom.xml:16,18,40-47`（实测 `<module>` 计数=6） |
| L175 | `run-demo.ps1`(4885B)、`final-accept.ps1`（无字节）、"run-demo 实测通过"（旧基线） | 新字节数 + 明确"旧实测属整改前旧端点、不适用当前架构；本轮只过语法解析、**未实跑**" + `-Base` 改名提醒 | 本次实测 `Get-ChildItem` 字节数；`Parser::ParseFile` 0 错误 |
| L176 | "7 个 reactor 模块全 SUCCESS"；`mall-simulator 54/54` | "**6 个 `<module>`** 全 SUCCESS（口径见 L51 行）"；`mall-simulator **38/38**` 并注明 54/54 已失效 | 同上 |
| L177 | "部署文档按真实**双进程/双库**边界重写" | "真实**三进程/三库**边界重写（覆盖旧双进程口径）"，补 `docs/api-overview.md:7`、`scripts/start-all.ps1:1-4` | 同 1.1 |

### 1.5 `docs/thesis-materials/thesis-outline.md`

| 行 | 旧值 | 新值 | 依据 |
|---|---|---|---|
| L13 | 9.5 写 `SimulationEngine` + "11 场景" + "生成器属**模拟商城进程（:8090）**，不属平台" | 改为现存类 `FileModeGenerationEngine` + `GenerationRunService`；场景数补证据（`ScenarioRegistry.java:11-28` 实测 11 个，逐个列出）；边界改为"**第三个独立程序（:8092，独立库 `generator_meta`）**，既不属于平台也不属于商城"；加"旧名 `SimulationEngine` 全仓 0 命中，不得再引用" | 同上 + `ScenarioRegistry.java:11-28`（`Map.ofEntries` 恰 11 条 `Map.entry`） |
| L26 | "7 个 reactor 模块（…6 个 + 父 POM）" | "**6 个 `<module>` 的聚合工程**"，并写明父 POM 是 aggregator、**不计入模块数** | `analytics-server/pom.xml:16,18,40-47` |
| L27 | "**两进程两端口两库**边界" | "**三进程三端口三库**边界" + 生成器行 + 依据行号 | 生成器 `application.yml:12,20` |
| L30 | "表清单：`warehouse/ddl/00-ods.sql`…`04-ads.sql`，Hive 库 `dw_ods`/…/`dw_ads`"（库名无来由，且第 3 个文件名被写成 `02-dim`） | 展开为：①五个 DDL 文件名逐一点名（**`02-dims.sql`**，不是 `02-dim.sql`）；②库名规则 **`库名 = <前缀>_<层>`**，DDL 内实写 `${WAREHOUSE_PREFIX}_ods` 等；③**前缀 NULL/空串时缺省前缀 = `dw`** ⇒ "前缀为空"≠裸 `ods`，而是 `dw_ods`…；④当前激活 profile 前缀为 NULL ⇒ 实际库名就是 `dw_ods`…`dw_ads`（实测 `spark-warehouse/` 五个 `dw_*.db` 目录） | `warehouse/ddl/00-ods.sql:6,8`、`01-dwd.sql:8`、`02-dims.sql:6`、`03-dws.sql:6`、`04-ads.sql:7`；`WarehouseNamespace.scala:17,67-68,76,108,122-127`；`Get-ChildItem spark-warehouse` 输出（§5.4） |
| L30 次行 | "DDL 声明 ODS 4 / DWD 3 / DIM 5 / DWS 7 / ADS 10"（无计数依据） | 保留数字并补"实测逐文件计数 `CREATE EXTERNAL TABLE`" | §5.5 计数输出：4/3/5/7/10 |

---

## 2. 真实端点 → 端口归属清单（唯一依据：各程序自身源码的 `@RequestMapping`）

> 全部行号来自本次实际打开的文件。调用脚本**只能**按本表打对应端口；本表**没有**的端点即"不存在"，
> 不得凭印象补写。

### 2.1 模拟商城 `mall-simulator` → **:8090**（库 `mall_simulator`）

端口依据：`mall-simulator/src/main/resources/application.yml:1-2`（`server.port: 8090`）。
**注意包名是 `com.graduation.mall.controller`**（不是 `...mall.web`；本泳道第一版曾按 `web` 写错，已按 `glob **/*Controller.java` 实测枚举纠正）。

| 方法 | 路径 | 证据 `文件:行` |
|---|---|---|
| POST | `/api/v1/auth/login` | `mall-simulator/src/main/java/com/graduation/mall/controller/AuthController.java:31`（类级 `/api/v1/auth`）、`:43`（login） |
| POST | `/api/v1/auth/logout` | 同上 `:65` |
| GET | `/api/v1/auth/me` | 同上 `:72` |
| GET | `/`（SPA 首页）、`/{path}` | `.../controller/SpaFallbackController.java:16` |
| POST | `/api/v1/mall/users` | `.../controller/MallController.java:37`（类级 `/api/v1/mall`）、`:43` |
| GET | `/api/v1/mall/products`、`/products/{productId}` | 同上 `:50`、`:55` |
| POST/GET | `/api/v1/mall/cart/items` | 同上 `:60`、`:67` |
| POST | `/api/v1/mall/orders`、`/orders/{orderId}/pay`、`/cancel`、`/refunds` | 同上 `:72`、`:80`、`:87`、`:94` |
| POST | `/api/v1/mall/refunds/{refundId}/complete` | 同上 `:102` |
| GET | `/api/v1/mall/orders` | 同上 `:110` |
| GET | `/api/v1/mall/outbox/status` | `.../controller/OutboxController.java:21`（类级 `/api/v1/mall/outbox`）、`:27` |
| POST | `/api/v1/mall/outbox/publish` | 同上 `:40`（**无 `@RequestBody`** ⇒ 空体调用） |
| GET/POST | `/api/v1/admin/products`（`:59`、`:83`）、`/{productId}/price`（`:91`）、`/stock`（`:98`）、`/status`（`:106`） | `.../controller/AdminProductController.java:33`（类级） |
| GET/POST | `/api/v1/admin/users`（`:41`、`:46`）、`/{userId}/toggle`（`:53`）、`/reset-password`（`:60`） | `.../controller/UserAdminController.java:22`（类级） |

**商城侧鉴权边界**（决定脚本必须带对 token）：`.../auth/AuthInterceptor.java:29-30`（白名单仅 `/api/v1/auth/login`）、`:39-41`（`ADMIN_ONLY_PREFIXES = /api/v1/mall/outbox` + `/api/v1/admin`）、`:35-37`（注释明确 `/api/v1/generator` 前缀已退役、ingestion/pipeline/metric/AI 属 analytics-server）。

**商城 8090 上不存在的**（本次实测确认）：`/api/v1/metrics/**`、`/api/v1/ingestion/**`、`/api/v1/pipeline-runs/**`、`/api/v1/generator/**`、`/api/v1/ai/**`、`/api/v1/decisions/**`、`/api/v1/health`。

### 2.2 分析平台 `analytics-server/platform-app` → **:8091**（库 `analytics_meta` + `analytics_metric`）

端口依据：`analytics-server/platform-app/src/main/resources/application.yml:1-2`（`server.port: 8091`）。
**包名同样是 `com.graduation.analytics.controller`。** 演示种子账号（**明码口令**）见
`platform-app/src/main/resources/db/meta/V5__platform_users.sql:2`（原话"admin/admin123、operator/operator123、analyst/analyst123"）、`:25-32`（三条 `INSERT INTO sys_user`：admin=系统管理员、operator=运营专员、analyst=数据分析师）；角色枚举见 `:9`（admin | operator | analyst | data_dev）。

| 方法 | 路径 | 证据 `文件:行` |
|---|---|---|
| GET | `/api/v1/health` | `.../controller/HealthController.java:12` |
| GET | `/api/v1/metrics/overview`、`/snapshots`、`/health`、`/quality` | `.../controller/MetricController.java:28`（类级 `/api/v1/metrics`）、`:36`、`:52`、`:59`、`:65`；`:58` 注释确认 `/health` 是**鉴权白名单**路径（探活用） |
| GET | `/api/v1/ingestion/status`、`/batches` | `.../controller/IngestionController.java:25`（类级）、`:31`、`:44` |
| POST | `/api/v1/ingestion/runs` | 同上 `:37` |
| POST | `/api/v1/pipeline-runs` | `.../controller/PipelineController.java:31`（类级 `/api/v1/pipeline-runs`）、`:45`；请求体 = `CreateRunReq`（`:38-43`：`runtimeProfileId`/`pipelineCode`/`businessTime`/`sourceDataVersion`），可选头 `Idempotency-Key`（`:48`）；权限 `PIPELINE_RUN`（`:46`） |
| GET | `/api/v1/pipeline-runs/{id}`、`/api/v1/pipeline-runs`（列表，`limit` 默认 20） | 同上 `:55`、`:68`（`:70` 的 `@RequestParam`）；权限 `OPS_LOG_VIEW`（`:56`、`:69`） |
| POST | `/api/v1/pipeline-runs/{id}/retry` | 同上 `:61` |
| * | `/api/v1/admin/pipeline-runs/**` | `.../controller/PipelineAdminController.java:23` |
| POST | `/api/v1/ai/queries`、`/analyses`、`/explanations` | `.../controller/AiController.java:53`（类级 `/api/v1/ai`）、`:115`、`:132`、`:95` |
| GET | `/api/v1/ai/audit/history`、`/audit/calls`、`/history/my` | 同上 `:271`、`:280`、`:289` |
| GET | `/api/v1/dashboards/overview` | `.../controller/AnalysisController.java:38`（类级 `/api/v1`）、`:47` |
| GET | `/api/v1/analysis/sales`、`/products`、`/funnel`、`/users`、`/rfm` | 同上 `:56`、`:65`、`:76`、`:84`、`:93` |
| * | `/api/v1/decisions/**`（POST、`/{id}/submit|approve|reject|start|complete|cancel|evaluate`、GET、`/{id}/evaluations`） | `.../controller/DecisionController.java:44`（类级）、`:52`、`:133`、`:139` |
| * | `/api/v1/runtime-profiles/**` | `.../controller/RuntimeProfileController.java:32` |
| * | `/api/v1/sources/**` | `.../controller/SourceRegistryController.java:52` |
| * | `/api/v1/admin/users/**` | `.../controller/UserAdminController.java:33` |
| POST | `/api/v1/auth/login` | `.../controller/AuthController.java:31`、`:43` |

> **脚本选账号的依据**：`POST /api/v1/pipeline-runs` 需要 `PIPELINE_RUN`、`GET /api/v1/pipeline-runs/{id}` 需要 `OPS_LOG_VIEW`。
> 本次未逐条核对"角色→权限码"映射表，故两个脚本统一用 **admin**（管理员，权限最全）跑主线，
> 并单独用 **operator** 做一次"越权访问 `/api/v1/admin/users` 应 403"的隔离复查。
> 若真跑时 `admin` 仍拿不到 `OPS_LOG_VIEW`，请以 `PermissionCode`/角色映射表为准（**未取证**，见 §4）。

**平台 8091 上不存在的**：`/api/v1/mall/**`、`/api/v1/generator/**`、`/api/v1/generation-runs`。

### 2.3 合成数据生成器 `synthetic-data-generator` → **:8092**（独立库 `generator_meta`）

端口/库依据：`synthetic-data-generator/src/main/resources/application.yml:12`（`port: 8092`）、`:20`（`jdbc:mysql://127.0.0.1:3306/generator_meta…`）、`:2`（原话"第三个独立程序"）、`:9`（产物写 `./generator-output`，**绝不写平台 landing**）。

| 方法 | 路径 | 证据 `文件:行` |
|---|---|---|
| POST | `/api/v1/generation-runs`（`consumes=application/json`，体仅 `{plan_id, version}`） | `.../web/GenerationRunController.java:25`（`@RequestMapping(path="/api/v1/generation-runs")`）、`:35` |
| GET | `/api/v1/generation-runs/{id}` | 同上 `:41` |
| POST | `/api/v1/generation-runs/{id}/cancel` | 同上 `:47` |
| GET | `/api/v1/generation-runs/{id}/artifacts` | 同上 `:53` |
| GET | `/api/v1/scenarios` | `.../web/ScenarioController.java:23`、`:26` |
| GET | `/api/v1/targets`、POST `/api/v1/targets`、PUT `/api/v1/targets`、POST `/api/v1/targets/{id}/test` | `.../web/TargetController.java:32`、`:43`、`:48`、`:55`、`:80` |

**生成器没有的**：任何 `/api/v1/generator/**` 路径；任何"创建计划版本"的 HTTP 端点（只能 CLI）。

---

## 3. 缺口清单（脚本/文档要用的能力，当前架构下**没有**对应端点）

> **数据安全补充（本次新发现，两处脚本原有的清库 SQL 都打错了地方）**
>
> **① 四张采集表被当商城表删。** 旧 `final-accept.ps1` 的直连 SQL 里有
> `DELETE FROM mall_simulator.file_checkpoint; …ingestion_batch; …ingestion_batch_file; …quarantine_record;`。
> 这四张表**属于分析平台**，由平台元库脚本创建
> （`analytics-server/platform-app/src/main/resources/db/meta/V1__platform_ingestion.sql:8,24,37,45`）；
> **商城**迁移脚本里对这四张表 grep **0 命中**（`mall-simulator/src/main/resources/db/migration/` 只有一个 `V1__init_mall.sql`，
> 其 10 张表全是商城业务表）。
>
> **② 更要紧的是：`mall_simulator` 库里*确实存在*这四张表，而且里面有数据。**
> 实测（只读 `information_schema` + `COUNT(*)`，见 §5.7）：
> `mall_simulator` 共 **30 张表**，其中包含 `file_checkpoint`(3 行)、`ingestion_batch`(1)、
> `ingestion_batch_file`(3)、`quarantine_record`(0)、`pipeline_run`(10) 等**平台侧对象**；
> 而 `mall_business` 库是**空的**（0 张表）。
> 原因也已实测清楚：`mall_simulator.flyway_schema_history` 里有 7 条**旧单进程时代**的迁移记录
> （`V1__init_mall` + `V2__ingestion` + `V3__metrics_pipeline` + `V4__ai_audit` + `V5__decisions` + `V6__security_reader` + `V7__security_auth`，见 §5.7），
> 而当前商城模块的迁移目录里只剩 `V1__init_mall.sql`——这正是 `mall-simulator/.../application.yml:15-19`
> 那段注释所解释的历史（"平台侧迁移已随平台复制代码移出本模块，但历史库 flyway_schema_history 中仍留有这些已应用记录"）。
> ⇒ 这四张表是**旧架构在同一库里留下的遗留副本**；当前平台读写的权威库是
> **`analytics_meta`**（`platform-app/.../application.yml:7` 默认 `…/analytics_meta`，
> 由 `PlatformDataSources.java:46` 的 `env.getProperty("platform.meta.url")` 唯一绑定，**不存在**回退到 `mall_simulator` 的路径；
> `analytics_meta` 里同名表行数远大于遗留副本：`file_checkpoint` 102 / `ingestion_batch` 39 / `pipeline_run` 38，见 §5.7）。
>
> **结论**：这四条 DELETE **不是"空操作"**——它们会真的删掉 `mall_simulator` 里那批平台侧遗留行。
> 更要紧的是，**它们删的目标从一开始就不该由商城侧脚本碰**：脚本作者以为在"清空演示数据"，
> 实际是在另一个程序的历史表上执行删除。本次已把两条脚本里的这四条 DELETE **全部移除**
> （`run-demo.ps1 -Clean` 只保留确属商城的 `event_outbox`；`final-accept.ps1` 整段清库动作移出），
> 并在脚本注释里写明四张表的真实归属与"平台断点只能人工单独重置"。
>
> **同时纠正本泳道自己的一个错误结论**（保留记录，便于复核）：本节初稿曾断言"这四张表在商城库里不存在、
> 旧 DELETE 属全静默空操作、未损失任何行为"。看到 `information_schema` 实测输出后**该结论作废**：
> 表存在且非空。改为上面的准确表述。

### 3.0 本轮删除动作的"反熵"自查（为什么删这两处是安全的）

> 下表是对**我自己**做的删除动作的交代，避免"删旧路径"变成新的熵。

**Anti-Entropy Declaration**

- **Deletion Class**：`code-retirement`（脚本里的直连 SQL 语句）。**不是** `persistent-state` 删除——
  本轮没有执行任何 DDL/DML，脚本也未被运行（只做了语法解析）。
- **Old Path/Object**：`final-accept.ps1` 整段清库 SQL（对 5 张表的 DELETE + 删商城 landing `*.jsonl`）；
  `run-demo.ps1` 的对应 SQL（本轮同步收紧）。其中 4 条 DELETE 指向的是**平台侧表**（见上）。
- **New Canonical Owner**：*清场* = `run-demo.ps1 -Clean`（唯一的"演示准备"动作，需 `-Clean` **且** `$env:MALL_DB_PASSWORD` 双门禁）；
  *验收* = `final-accept.ps1`（只读断言，不再改任何数据）。
- **Expected Preserved Behavior**：商城侧演示数据仍可被清空（`mall_simulator.event_outbox` + 商城 landing `*.jsonl`），
  清空范围与旧版在**商城侧**的实际效果**完全相同**。
- **Expected Retired Behavior**：对 `file_checkpoint` / `ingestion_batch` / `ingestion_batch_file` / `quarantine_record`
  的 DELETE 不再出现。
  **已实测**：这四张表由平台元库脚本创建（`…/db/meta/V1__platform_ingestion.sql:8,24,37,45`），
  商城迁移脚本里 **0 命中**；但 `mall_simulator` 库里**确实存在**这四张表且部分有数据（§5.7 的 `information_schema`/`COUNT(*)` 输出），
  属旧单进程架构的遗留副本。
  **因此本轮删除的实际效果是**：不再删除 `mall_simulator` 中这批**平台侧遗留行**。
  这些行对当前平台**不是**权威数据（权威库是 `analytics_meta`），但**它们也不是商城数据**——
  原脚本的语义（"清空演示数据"）与实际作用（删平台侧历史表）从来就不一致，
  这正是必须移除的理由；**不**声称"删了也没事"。
  **仍未实测的部分**：这批遗留行的具体内容/来源时间、旧脚本此前是否真的成功执行过这些 DELETE——
  本轮只做了 `COUNT(*)` 与 `information_schema` 只读查询，**没有执行过任何 DDL/DML**，已记入 §4。
- **External Boundary Touched**：**no**。两条脚本都是仓库内开发/演示脚本，无外部消费方依赖这段 SQL。
- **Source-of-Truth Data Risk**：**none for the live platform**（权威库 `analytics_meta` 从未被这两条脚本指向），
  但**必须说明**：被删除的目标（`mall_simulator` 里的平台侧遗留表）**确实有数据**，
  所以不能把这轮称为"删了个空操作"。本轮**未执行**任何删除。

**Retirement Decision**

- **Path**：`delete-first`。
- **Why**：目标是 `code-retirement`，无外部边界、无真实持久状态可被影响；
  保留"无效但看着能删库"的 SQL 只会持续误导（本次它已经误导了旧脚本作者一次）。
- **Non-edits**：**不动** `analytics-server/**`、`synthetic-data-generator/**`、`mall-simulator/**` 任何源码；
  **不动**平台元库迁移脚本（`V1__platform_ingestion.sql` 保持原样）；**不动**任何 `docs/acceptance/**` 既有文件。

**Verification Plan**

- **Main-path check**：`run-demo.ps1 -Clean` 仍能清商城侧（语句与双门禁见 L56/L65/L70-73）；
  `final-accept.ps1` 的三程序探活/构建产物/登录/采集/流水线/指标/决策/商城下单等 15 项断言与 1 项缺口计数仍在（L46-L154）。
- **Lingering-reference check**：两脚本全文 grep `file_checkpoint|ingestion_batch|quarantine_record` →
  仅剩**注释**（run-demo L61/L63、final-accept L64）；可执行的 `DELETE|DROP|TRUNCATE` 只剩
  `run-demo.ps1:71`（`event_outbox`，商城自有表，双门禁）。`final-accept.ps1` 中
  `DbPassword|MYSQL_PWD|mysql -u` **0 残留**。
- **Negative check**：`final-accept.ps1` 参数表已无 `-DbPassword`（L14-19），传它会直接报参数不存在，
  不可能再走直连清库分支。
- **Boundary check**：两脚本 `[System.Management.Automation.Language.Parser]::ParseFile` 均 **0 错误**；
  未触碰任何程序源码与既有验收记录。

**Gap Closure**（本轮出现的缺口与归类）

| 缺口 | 类型 | 处理动作 | 是否重新引入兼容 |
|---|---|---|---|
| 生成器无 HTTP 建计划端点 | `expected-retirement`（生成器有意移出商城、改为 CLI 两跳） | 记为 GAP 计数 + `TODO(缺口)`，不伪造调用 | no |
| 平台无 reset/cleanup 端点 | `missing-owner-logic`（平台侧确实没有这个能力） | 记为缺口 G-2，明确"平台断点只能人工单独重置" | no |
| 旧脚本"参数化生成"请求体在新 API 无对应 | `missing-owner-logic`（新 API 只收 `{plan_id,version}`，参数烘焙进计划版本） | 记为缺口 G-3，写清参数应写进计划 | no |
| 旧 `-Base` 单 base 变量 | `stale-internal-consumer` | 迁移为 `$Mall`/`$Platform`/`$Generator` 三个 base | no |

> **`persistent-state-risk`：未出现**（若出现则必须停下征求确认，本轮没有该类缺口）。

| 编号 | 缺口 | 实测证据 | 处置 |
|---|---|---|---|
| **G-1** | "生成器一键造数"无可用 HTTP 调用：旧 `POST /api/v1/generator/runs` 已不存在；现存的 `POST /api/v1/generation-runs` **只接受 `{plan_id, version}`**，而**计划版本只能由 CLI 创建** | `docs/api-overview.md:138`（"已退役（M1-7，2026-09-11）：`/api/v1/generator/runs`、`/api/v1/generator/scenarios`"）；`docs/acceptance/M1-8-三程序独立启停验收记录.md:67`（实测 8090 该路径 404）；`GenerationRunController.java:25,35`；`GeneratorApiDtos.java:31-34`；`GeneratorCli.java:29-31`（"契约的七个端点里没有创建计划入口"） | 两个脚本该步标 `TODO(缺口)` / `GAP`，**保留步骤但不由脚本伪造调用**；同时在缺口处给出生成器侧 CLI 两跳命令 |
| **G-2** | 平台侧**没有**"清空演示数据 / 重置采集断点"端点 | `platform-app` 全部 `@RequestMapping` 见 §2.2，其中无 `reset`/`cleanup`/`clear` 路径 | `-Clean` 只清商城侧；平台侧清理写成人工单独步骤，**不得**为脚本顺手删平台库/数仓 |
| **G-3** | 旧脚本的参数化生成能力（`userCount`/`eventsPerSecond`/`baseConversionRate`/`startTime`/`endTime`/`randomSeed`/`dirtyDataRate`/`scenario`）在生成器新 API 中**无等价物** | 同上：新 API 体只有 `plan_id`+`version`；参数须烘进不可变计划版本（CLI `--scenario/--seed/--start/--end/--event-count/--rate`） | 论文/演示词里"可参数化造数"的表述必须改为"参数写入不可变计划版本"，不要宣称有参数化 HTTP 接口 |
| **G-4** | 旧 `--rate`（=旧 `eventsPerSecond`）在**文件模式下不生效** | `FileModeGenerationEngine.java:59`（原话"rate_per_second 在文件模式不生效"）；`GenerationRequest.java:21`（"ratePerSecond 负载上限（0 表示不限）"） | 演示词不得用 `--rate` 声称"限速生成"；文件模式下应按 `--event-count` 描述 |

| G-5 | 演示/验收脚本所需 DB 口令与 `init-three-dbs.sql` 的授权**不一致**：脚本按 `root` + `$env:MALL_DB_PASSWORD` 走，而 `init-three-dbs.sql:10-11` 只给 `mall_app` 授了 `mall_business`（本机为空库），`mall_app` 对 `mall_simulator` 无权限 | 实测见 §5.9（`mall_app` 只看得见 `mall_business`）；`mall-simulator/.../application.yml:8,10`（应用连 `mall_simulator`、口令默认空串）；`warehouse/migrations/init-three-dbs.sql:5,10-11` | 脚本统一按"`root` + `$env:MALL_DB_PASSWORD`"取口令（与 `scripts/start-all.ps1:47` 默认值一致）；**不修改** `init-three-dbs.sql`（不在本泳道范围），如实登记为缺口 |

| G-6 | **landing 目录被商城与平台共用**：商城产物落 `<仓库根>\landing\events`，而平台的采集落地根也是同一个目录（`LocalLandingStorage.java:28` + `runtime_profile.landing_uri = file://./landing`，见 §5.8）⇒ 两者的文件命名空间重叠，脚本**无法**只按目录区分"该清谁" | 实测 §5.8：根 `landing\events` 55 个文件中含 `"source_system":"mock-mall"` 的商城产物（`2026091122.jsonl`，2026-09-11 22:02）**与** 2026-09-06 的采集用业务日文件（`2026090509.jsonl`，12 MB）；而 `mall-simulator\landing\events` 是**空的** | `run-demo.ps1 -Clean` 改为**只删 2 小时内新文件**、并打印跳过的旧文件数，**拒绝**无脑清空该目录（否则会删掉平台侧真实 landing 数据）；同时如实登记本缺口：**当前无法可靠区分"商城的 landing"与"平台的落地根"** |

---

## 4. 未取证清单（**没有实测，故不写结论**）

---


| 项 | 为什么没取证 | 当前状态 |
|---|---|---|
| 三程序**运行时**行为（HTTP 状态码、响应体形状、看板数值） | 本次只允许只读探活；实测时 **8090/8091/8092 三个进程都未启动**（§5.1 原始输出：连接被拒绝）。**禁止**由本泳道启动进程 | **未实测** → 脚本里的请求体字段名（如采集/流水线请求体、`data.token` 路径）是**按源码 DTO/控制器签名推得**，未做端到端验证；真跑前请先 `pwsh -File scripts/start-all.ps1` |
| 改写后两个脚本的**端到端实跑** | 同上（实跑会写状态、造数据，属破坏性操作，本泳道禁止执行） | 只过了 **PowerShell 语法解析**（§5.3：0 错误）。**未实跑** |
| `POST /api/v1/mall/outbox/publish` 是否幂等/是否需要额外参数 | 服务未启动，无法实测；源码 `OutboxController.java:40` 无 `@RequestBody` | 未实测（脚本按"无体调用"处理，并在注释标明） |
| `GET /api/v1/pipeline-runs/{id}` 响应中 `stages[]` 的字段名（脚本用 `stageCode`/`status`） | 服务未启动；`PipelineStageRun` 实体字段未逐一核对 | **未逐字取证**（脚本用 `$_.status` 判 SUCCESS、打印 `$_.stageCode`）；真跑若字段名不符，只影响打印与计数，不影响控制流 |
| 平台侧 `file_checkpoint`/`ingestion_batch` 落在哪个库 | ~~本次未逐表核对平台库建表脚本~~ **已补测** | **已取证**：权威库 = `analytics_meta`（`platform-app/.../application.yml:7` + `PlatformDataSources.java:46`；实测 22 张表、行数见 §5.7）。同时发现 `mall_simulator` 里有一批**平台侧遗留表**（旧单进程架构，`flyway_schema_history` 7 条记录为证）。**遗留行为什么还在、内容是否还需保留：未取证** → 脚本口径取保守（一律不删平台侧表） |
| 三程序**同时**运行的端口/资源冲突 | 服务未启动 | 未实测 |
| 商城 `admin/admin123` 是否为本机现存口令 | 未登录（服务未起） | 来自 `scripts/start-all.ps1:68` 的演示账号说明（**平台侧**三个账号，平台侧另有 `V5__platform_users.sql:2` 佐证）；**商城侧口令未取独立证据**（商城自己的 `sys_user` 表未查） → 真跑前请以本机实际口令为准 |
| 清库步骤的**数据库口令与账号** | 本轮只做了一次连通性/库表只读探测，未做任何写入 | **已实测**：见 §5.9 —— `mysql -uroot` **免密被拒**（`ERROR 1045 … using password: NO`），`root/123456` 可连；`mall_app/mall_app_pw_2026` **能连但只看得见 `mall_business`（空库）**，对 `mall_simulator` 无权限。⇒ 清库/探测必须用**有 `mall_simulator` 权限的账号**；`init-three-dbs.sql` 里那套账号与本机实际可用账号**不一致**（见 §5.9 结论） |

> ⚠️ 与任务书写法的一处**实测冲突**（如实报告，不迁就描述）：任务书说"当前激活环境前缀为空 ⇒ 用默认库名"，
> 容易被读成库名就是裸 `ods/dwd/dim/dws/ads`。**实测规则是**：`hive_database_prefix` 为 NULL/空串时
> **缺省前缀 = `dw`**（`WarehouseNamespace.scala:17,67-68`），即库名 `dw_ods`/`dw_dwd`/`dw_dim`/`dw_dws`/`dw_ads`
> （`03-dws.sql:6` 同；实测 `spark-warehouse/` 下确为 `dw_*.db` 五个目录，见 §5.4）。
> 即 `thesis-outline.md` 原有的 `dw_*` 写法**本身是对的**，缺的是"由 `<前缀>_<层>` 派生、缺省 `dw`"这条规则。

---

## 5. 只读命令原始输出（本轮全部取证动作）

### 5.1 三程序探活（唯一被允许的运行时动作，结果：三程序均未启动）

```
PS> Invoke-WebRequest http://127.0.0.1:8090/                       -TimeoutSec 4 -UseBasicParsing
mall      :8090 GET /                        => 由于目标计算机积极拒绝，无法连接。 (127.0.0.1:8090)
platform  :8091 GET /api/v1/metrics/health   => 由于目标计算机积极拒绝，无法连接。 (127.0.0.1:8091)
generator :8092 GET /api/v1/scenarios        => 由于目标计算机积极拒绝，无法连接。 (127.0.0.1:8092)

PS> Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 8090,8091,8092 }
8090/8091/8092 均无 LISTEN

PS> Get-Process java | Select Id,ProcessName
   Id ProcessName
12016 java
54404 java
# 逐一看命令行（只读）：54404 = DataGrip 的 RemoteJdbcServer（Hive JDBC 驱动），
# 12016 = Maven（`-o test -f analytics-server/pom.xml -pl platform-app -am -DforkCount=0`）——
# 是**别的泳道**在跑平台单测，与 8090/8091/8092 无关；本泳道未启动、未停止任何进程。
```

### 5.2 死指针复核：旧证据文件名全仓 0 命中

```
PS> Get-ChildItem -Path <repo> -Recurse -File -Include 'SimulationEngine.java','GeneratorRunService.java','GeneratorController.java'
    | Where-Object { $_.FullName -notmatch '\\\.git\\' }
（无任何输出）
# glob 工具三个模式亦零命中，原始返回逐字如下：
#   glob '**/SimulationEngine.java'    → No files found
#   glob '**/GeneratorRunService.java' → No files found
#   glob '**/GeneratorController.java' → No files found
#   ⇒ 三个文件名在当前工作树中 0 命中（含被忽略文件；glob 工具会列出隐藏/忽略文件）
# 注意名字相近但**路径不同**、属另一个程序的文件，别当成同一证据：
#   synthetic-data-generator/.../service/GenerationRunService.java   ← 新实现（类名同名、包/程序不同）
#   synthetic-data-generator/.../web/GenerationRunController.java     ← 新实现（对应旧 GeneratorController）
存活的等价实现（实测枚举）：
  synthetic-data-generator/src/main/java/com/graduation/generator/engine/FileModeGenerationEngine.java
  synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiGenerationEngine.java
  synthetic-data-generator/src/main/java/com/graduation/generator/engine/GenerationEngine.java
  synthetic-data-generator/src/main/java/com/graduation/generator/service/GenerationRunService.java
  synthetic-data-generator/src/main/java/com/graduation/generator/web/GenerationRunController.java
```

### 5.3 两个脚本的 PowerShell 语法解析（唯一"跑过"的脚本动作）

```
PS> $t=$null;$e=$null
PS> [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path 'scripts\run-demo.ps1'),[ref]$t,[ref]$e)
OK (no parse errors): scripts\run-demo.ps1
PS> [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path 'scripts\final-accept.ps1'),[ref]$t,[ref]$e)
OK (no parse errors): scripts\final-accept.ps1
```

### 5.4 Hive 库名实测（前缀为空 ⇒ `dw_*`）

```
PS> Get-ChildItem D:\Develop_code\GraduationProject\spark-warehouse -Directory | Select Name
dw_ads.db  dw_dim.db  dw_dwd.db  dw_dws.db  dw_ods.db  probe_r613.db
# 依据：WarehouseNamespace.scala:17/67-68（缺省前缀 dw）+ 激活 profile 的 hive_database_prefix=NULL
# （docs/acceptance/p1-baseline-r39-20260911/raw/api-runtime-profile-active.json）
```

### 5.5 warehouse DDL 实测

```
PS> Get-ChildItem warehouse\ddl\ | Select Name
00-ods.sql  01-dwd.sql  02-dims.sql  03-dws.sql  04-ads.sql      # 第 3 个是 02-dims.sql（非 02-dim.sql）

PS> 逐文件计数 'CREATE EXTERNAL TABLE'
00-ods.sql: 4    01-dwd.sql: 3    02-dims.sql: 5    03-dws.sql: 7    04-ads.sql: 10    （合计 29）
# 注意：这些 DDL 用的是 Hive `CREATE EXTERNAL TABLE`（如 00-ods.sql:11），不是 MySQL `CREATE TABLE`。
# 库名以 ${WAREHOUSE_PREFIX} 占位（00-ods.sql:8；注释 :6 写明"缺省源 A 用 dw"）。

PS> analytics-server/pom.xml 的 <module> 计数
6   （platform-common / connection-ingestion / warehouse-pipeline / metric-analysis / ai-decision / platform-app；
     父 POM 为 packaging=pom 的 aggregator，不计入）
```

### 5.6 生成器场景数

```
ScenarioRegistry.java:11-28 的 Map.ofEntries 恰 11 条 Map.entry：
normal / promotion / weekend_growth / sales_decline / refund_rise / price_increase /
hot_product / stock_shortage / new_product_cold_start / old_user_churn / new_user_growth
```

### 5.7 库/表实测：平台权威库 vs `mall_simulator` 里的平台侧遗留表（**只读**）

> 本节是修正本 README 初稿错误结论的依据。全部为只读查询（`information_schema` + `COUNT(*)` + `SELECT` 元数据表），
> **未执行任何 DDL/DML**，也**未**触碰任何业务表内容。

```
PS> mysql -uroot -N -e "SELECT TABLE_SCHEMA, COUNT(*) FROM information_schema.TABLES
      WHERE TABLE_SCHEMA IN ('mall_simulator','mall_business') GROUP BY TABLE_SCHEMA;"
mall_simulator	30
# ⚠️ mall_business 一行都没有 ⇒ 该库 0 张表（init-three-dbs.sql:5 建的库，本机是空的）

PS> mysql -uroot -N -e "SELECT TABLE_NAME FROM information_schema.TABLES
      WHERE TABLE_SCHEMA='mall_simulator' ORDER BY TABLE_NAME;"
ads_behavior_funnel_m   ads_operation_overview_m  ads_sale_trend_m   ai_call_log
ai_query_history        cart_item                 category           data_quality_result
decision_evaluation     decision_task             event_outbox       file_checkpoint
flyway_schema_history   ingestion_batch           ingestion_batch_file
inventory               mall_order                mall_user          metric_definition
metric_snapshot         metric_value              order_item         payment
pipeline_run            pipeline_stage_run        product            quarantine_record
refund                  sys_user                  user_session
# ⇒ 商城自有表（10）与**平台侧表**（file_checkpoint/ingestion_batch/…/metric_*/decision_*/ai_*/pipeline_*）混在同一库里

PS> mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='analytics_meta';"
22
analytics_meta 表清单：
ai_call_log  ai_query_history  data_quality_result  decision_evaluation  decision_task
file_checkpoint  flyway_schema_history  ingestion_batch  ingestion_batch_file
metric_definition  metric_snapshot  metric_value  operation_audit_log  pipeline_run
pipeline_stage_run  quarantine_record  runtime_profile  source_registry  spark_job_run
sys_user  t_ckpt_test  user_session
# ⇒ 平台权威库是 analytics_meta；注意它**没有** mall_* 业务表（边界干净）

PS> 同一批表在两个库的行数（只读 COUNT）
  file_checkpoint        analytics_meta=102          mall_simulator=3
  ingestion_batch        analytics_meta=39           mall_simulator=1
  ingestion_batch_file   analytics_meta=109          mall_simulator=3
  quarantine_record      analytics_meta=104          mall_simulator=0
  pipeline_run           analytics_meta=38           mall_simulator=10
# ⇒ mall_simulator 里的平台侧表**非空**。旧脚本的 4 条 DELETE 打的就是这里：
#    不是"空操作"，而是**会真的删掉平台侧遗留行**。

PS> mysql -uroot -e "SELECT installed_rank, version, description, script, success
      FROM mall_simulator.flyway_schema_history ORDER BY installed_rank;"
1  1  init mall            V1__init_mall.sql            1
2  2  ingestion            V2__ingestion.sql            1
3  3  metrics pipeline     V3__metrics_pipeline.sql     1
4  4  ai audit             V4__ai_audit.sql             1
5  5  decisions            V5__decisions.sql            1
6  6  security reader      V6__security_reader.sql      1
7  7  security auth        V7__security_auth.sql        1
# ⇒ 这是**旧单进程架构**的迁移史：商城库当年还装平台侧迁移。
#    与 mall-simulator/.../application.yml:15-19 的注释完全对上
#    （"平台侧迁移已随平台复制代码移出本模块，但历史库 flyway_schema_history 中仍留有这些已应用记录，
#      忽略 '*:missing' 以免 validate 报 applied migration not resolved locally"）。

PS> mysql -uroot -e "SELECT installed_rank, version, script FROM analytics_meta.flyway_schema_history ORDER BY installed_rank;"
1..16  V1__platform_ingestion / V2__platform_pipeline_quality / V3__platform_ai_audit /
       V4__platform_decisions / V5__platform_users / V7__platform_runtime_profile /
       V8__platform_ingestion_r3 / V9__platform_stage_evidence_widen /
       V10__spark_job_run_output_partitions / V11__data_quality_layers /
       V12__quality_detail_width / V13__metric_definition_r7 / V14__r8_identity_decision /
       V15__stage_evidence_mediumtext / V16__source_registry
# ⇒ 平台权威迁移史在 analytics_meta，与 analytics-server/platform-app/src/main/resources/db/meta/ 对应

PS> 平台元库连接的唯一来源（源码，只读）
  analytics-server/platform-app/src/main/resources/application.yml:7
    url: ${PLATFORM_META_URL:jdbc:mysql://127.0.0.1:3306/analytics_meta?...}
  analytics-server/platform-app/src/main/java/com/graduation/analytics/config/PlatformDataSources.java:46
    .url(env.getProperty("platform.meta.url"))
# ⇒ 没有任何回退到 mall_simulator 的路径 ⇒ mall_simulator 里那些平台侧表**不是**当前平台的权威数据
```

### 5.8 landing 目录归属实测（商城产物 vs 平台落地根，**只读**）

> 本节推翻了本 README 初稿里"清 `mall-simulator\landing\events` 就清掉了商城产物"的写法。

```
PS> # 平台落地根的唯一来源（源码）
  analytics-server/connection-ingestion/.../runtime/storage/LocalLandingStorage.java:28
    public LocalLandingStorage(@Value("${platform.landing.local-root:./landing}") String localRoot)
  analytics-server/platform-app/.../config/PlatformBeans.java:54
    @Value("${platform.landing.local-root:./landing}") String landingRoot

PS> # 平台 ACTIVE 运行环境的 landing_uri（只读 SELECT）
SELECT id, profile_code, landing_uri, hive_database_prefix FROM analytics_meta.runtime_profile;
1   local-dev   file://./landing   NULL
# ⇒ landing 根 = ./landing（相对启动工作目录 = 仓库根，见 start-all.ps1:48 -WorkingDirectory $root）
# ⇒ 同时：hive_database_prefix = **NULL** ⇒ 印证 thesis-outline L30 的"前缀空 → 缺省 dw"（库名 dw_*）

PS> # 商城落地路径（源码）
  mall-simulator/src/main/resources/application.yml:33
    path: ${MALL_LANDING_PATH:./landing}
  scripts/start-all.ps1:48  -WorkingDirectory $root   # 工作目录 = 仓库根
# ⇒ 商城默认也落到 **<仓库根>\landing**，与平台落地根**同一个目录**

PS> # 实证：该目录里同时有商城产物和平台已采数据
  仓库根 landing\events          => 55 个文件（最新 2026091122.jsonl 130 KB, 2026/9/11 22:02；
                                     以及 2026090509.jsonl 12,089,941 B 等大批 2026/9/6 文件）
  首行: {"event_id":"2f2e1eb3-…","event_type":"user_registered","event_time":"2026-09-11T22:02:49",
         "source_system":"mock-mall","schema_version":"1.0", …}
# ⇒ 根 landing 里**确实**有商城的 mock-mall 产物（2026-09-11 22:02 写）

  mall-simulator\landing\events  => **0 个文件**
  （但 mall-simulator\landing\ 下有 dirty/ landed/ quarantine/：dirty\2026090611.jsonl 1 个，
    landed\ 12 个 ing-* 批次目录共 40+ 个 jsonl，quarantine\ 7 个 —— 均为 **2026-09-06** 的旧采集痕迹）

PS> # 干跑清场 guard（不执行删除，只计数）
  会被删的新文件: 0
  会跳过的旧文件: 55   ← 平台已采数据，必须保住
```

**结论与处置**：

1. **商城当前实际把产物写到 `<仓库根>\landing\events`**（不是 `mall-simulator\landing\events`，
   后者为空，只有 2026-09-06 的旧 `landed/`/`quarantine/` 痕迹）。
2. **平台的采集落地根也是 `<仓库根>\landing`**（`file://./landing`）⇒ **两者共用同一目录**，
   脚本**无法**用"目录"来区分"该清商城的还是平台的"。
3. 因此 `run-demo.ps1 -Clean` **不**无脑清空该目录，改为只删 **2 小时内**新产生的文件
   （演示刚造的数据），并把更早的文件计入"跳过"并打印数量——干跑显示当前会跳过 **55** 个（全部），
   即**本机现状下 `-Clean` 不会删掉任何平台侧已采数据**。
4. 这是**缺口 G-6**（不是本轮能修好的架构问题）：landing 目录未按程序隔离，
   所以"清商城 landing"这个动作在当前布局下**无法被脚本安全自动化**——脚本选择保守并明示。

### 5.9 清库步骤的账号/口令实测（**只读连接探测**）

```
PS> mysql -uroot -N -e "SELECT VERSION(); SHOW DATABASES;"
ERROR 1045 (28000): Access denied for user 'root'@'localhost' (using password: NO)
# ⇒ root **不是**免密；旧脚本 `mysql -uroot -e "DELETE …"` 若不设 MYSQL_PWD 会直接认证失败
#   （而旧脚本正是用 `2>$null` 吞掉这条错误 ⇒ 失败也不可见）

PS> $env:MYSQL_PWD='123456'; mysql -uroot -N -e "SHOW DATABASES;"
analytics_meta  analytics_meta_p103  analytics_meta_v17probe  analytics_metric
analytics_metric_p103  blog_system  company  crm  emp  generator_meta
information_schema  itcast  library  mall_business  mall_simulator
mall_simulator_test  mybatis  mybatis_db  mybatis_library  mydb …
# ⇒ root/123456（= scripts/start-all.ps1 的默认口令）可用，且**同时看得见三套库**
#   （mall_simulator / analytics_meta / analytics_metric / generator_meta 都在）

PS> $env:MYSQL_PWD='mall_app_pw_2026'; mysql -umall_app -N -e "SHOW DATABASES;"
information_schema  mall_business  performance_schema
# ⇒ init-three-dbs.sql:10-11 授权的 mall_app 账号，**只**能看见 mall_business（本机为空库），
#   对 mall_simulator 无任何权限 ⇒ **不能用它做清库**
```

**由 §5.9 推出的前提条件（真跑前必须满足）**：

1. 清库步骤要能连上 `mall_simulator`，必须提供**有该库权限**的账号口令；
   本机可用的是 `root` + 口令（`scripts/start-all.ps1:47` 的默认 `123456` 即按此约定）。
   两个脚本都从 `$env:MALL_DB_PASSWORD` 取值，这一点与 `start-all.ps1` 一致。
2. **`init-three-dbs.sql` 与本机现状不一致**（它建的是 `mall_business` 且只给 `mall_app` 授权），
   而商城应用连的是 `mall_simulator`（`mall-simulator/.../application.yml:8`）。
   这是**脚本与库现状的既存分歧**，本轮只如实记录，**不修改** `init-three-dbs.sql`（不在本泳道范围）。
3. 商城应用自身的库口令走 `${MALL_DB_PASSWORD:}`（`mall-simulator/.../application.yml:10`，**默认空串**）。
   既然实测 root 非免密，**启动商城前必须先设 `MALL_DB_PASSWORD`**，否则商城连不上库。

---

## 6. 本次改动的文件清单

| 文件 | 动作 |
|---|---|
| `scripts/run-demo.ps1` | 重写（三 base + 端点归属 + 缺口标注 + 异步轮询 + 8 阶段 + `noNewData` 判据 + `-Clean` 双门禁 + landing 清场加"只删 2 小时内新文件"保护）**186 行 / 15,275 字节** |
| `scripts/final-accept.ps1` | 重写（去掉自启动/自杀进程 + 三 base + **整段清库动作移出** + 缺口计 GAP + 8 阶段 + 退出码分档 + 商城请求体按 `MallDtos` 修正）161 行 / 13,228 字节 |
| `docs/demo/demo-script.md` | 三程序口径 + L37 生成步骤改 `:8092` + 补商城 Outbox 段 |
| `docs/thesis-materials/证据映射表.md` | L49/L50/L51/L175/L176/L177 死指针与口径 |
| `docs/thesis-materials/thesis-outline.md` | L13/L26/L27/L30 生成器归属、模块数、Hive 库名规则 |
| `docs/acceptance/docs-sync-3programs-20260911/README.md` | 本文件（新增） |

**未碰**：`docs/项目实施进度与任务看板.md`、`docs/开发过程事实与决策记录.md`、`analytics-server/**`、`synthetic-data-generator/**`、`mall-simulator/**` 源码、`docs/acceptance/**` 既有文件、`warehouse/migrations/init-three-dbs.sql`（其与现状的分歧见缺口 G-5，只登记不改）。无 `git add` / `git commit`。

---

## 7. 真跑这两个脚本的前置条件（**按实测倒推，缺一不可**）

> 本节只列"要真跑必须先满足什么"，**本泳道没有实跑**（实跑会造数/写状态，属禁止动作）。每条都对应上面的实测。

1. **必须有人先把数据造出来**（否则步骤 [1] 无法完成）。生成器没有 HTTP 建计划入口（缺口 G-1）：
   需在生成器侧用 CLI 两跳预置不可变计划版本，命令见 `run-demo.ps1` 步骤 [1] 注释。
   ⚠️ 注意生成器产物写在 `./generator-output`（`synthetic-data-generator/.../application.yml:9`），
   **不写**平台 landing；平台采集读的是平台侧 `platform.landing.local-root`（默认 `./landing`），
   两者如何对接**未取证**（§4）。
2. **先把三程序起来**：`pwsh -File scripts/start-all.ps1`。实测当前 8090/8091/8092 **均未监听**（§5.1），
   本泳道被禁止启动/停止它们。
3. **必须设 `MALL_DB_PASSWORD`**：实测 `mysql -uroot` **免密被拒**（§5.9），
   而商城应用口令默认空串（`mall-simulator/.../application.yml:10`）⇒ 不设它商城起不来、清库也失败。
4. **不要用 `init-three-dbs.sql` 里那套账号做清库**：`mall_app` 只看得见空的 `mall_business`（§5.9 / 缺口 G-5）。
5. **清场只在 `run-demo.ps1 -Clean` 做，且必须显式加 `-Clean`**；`final-accept.ps1` 已不含任何清理动作。
6. **验收要看退出码**：`0`=全 PASS；`1`=有 FAIL（环境/功能问题）；`2`=无 FAIL 但有 GAP
   （脚本与架构不同步，**不等于通过**）。
7. **预期仍有 1 项 GAP**（造数步骤），这是**已知且如实标注**的，不是脚本故障。
