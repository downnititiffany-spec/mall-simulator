# synthetic-data-generator（模拟数据生成器）

V2.1 §3.1 的**第三个独立程序**。唯一职责：固定 seed 的场景生成、负载控制、脏数据策略、运行报告。

## 边界（V2.1 §3.1 / §3.4-3）

| 允许 | 禁止 |
|---|---|
| 通过商城**公开 HTTP 接口**造数据（`MALL_API` 模式，§3.3 A） | 直接操作商城数据库、依赖商城内部 Service/Mapper |
| 输出符合契约的 JSONL + manifest 到**自己的**测试目录（`CANONICAL_EVENT_FILE` 模式，§3.3 B） | 写分析平台的 ODS/DWD/ADS/指标库，或写平台 landing |
| 读写自己的元数据库（`generator_meta`） | 依赖分析平台或商城的任何 Java 包、表、配置键 |
| 自报负载与失败（§4.3：失败必须记入运行报告） | 触发平台流水线；在 `MALL_API` 模式绕过商城业务规则写脏数据 |

契约来源是中立目录 `contract-specs/`（只读消费，不共享 Java 实体）。

## 独立构建 / 启动 / 停止（§3.4-1）

```powershell
# 构建（离线可用；产出可执行 fat jar）
mvn -o -DskipTests package -f synthetic-data-generator/pom.xml

# 启动（前台；端口 8092，日志 ./logs/generator.log）
java -jar synthetic-data-generator/target/synthetic-data-generator-0.1.0-SNAPSHOT.jar

# 后台启动（工作目录必须是本模块，日志/产物都相对它解析）
Start-Process -FilePath 'java' `
  -ArgumentList '-jar','target/synthetic-data-generator-0.1.0-SNAPSHOT.jar' `
  -WorkingDirectory 'synthetic-data-generator' -WindowStyle Hidden

# 停止（只停本程序，不影响 8090 商城与 8091 平台）
Get-NetTCPConnection -LocalPort 8092 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

## 配置

`src/main/resources/application.yml`，命名空间 `generator.*`，环境变量可覆盖：

| 键 / 变量 | 默认值 | 说明 |
|---|---|---|
| `server.port` | `8092` | 独立端口（V2.1 §3.1 原文；`8082` 属误记，见事实记录 D-011） |
| `GENERATOR_DB_USER` / `GENERATOR_DB_PASSWORD` | `root` / `123456` | 元数据库账号（裁决项 B-05：鉴权模型未定前沿用本机开发账号） |
| `spring.datasource.url` | `.../generator_meta?...createDatabaseIfNotExist=true` | **库名是配置项**：B-05 裁决为 `synthetic_generator` 或复用其它库时只改这一行 |
| `GENERATOR_OUTPUT_ROOT` | `./generator-output` | 文件模式产物目录（独立测试目录，绝不指向平台 landing） |
| `GENERATOR_LOG_FILE` | `./logs/generator.log` | 独立日志文件 |

数据库迁移在本模块自己的 location `classpath:db/generator`，五张表见 `V1__generator_meta.sql`（V2.1 §4.2）。

## 元数据库（V2.1 §4.2）

| 表 | 用途 |
|---|---|
| `generator_target` | 目标商城配置；凭据只存引用（`credential_ref`），每次运行冻结 `config_version` |
| `generation_plan` | 生成计划；`(plan_id, version)` 唯一，运行只引用不可变版本 |
| `generation_run` | 运行实例；状态机 `PENDING -> RUNNING -> SUCCESS/FAILED/CANCELLED`，支持查询与幂等取消 |
| `generation_artifact` | 产物（JSONL / 清单 / 脏样本）的 checksum、字节数、记录数、时间范围 |
| `generation_event_stat` | 按事件类型的条数与金额分布 |

## 守卫测试

```powershell
mvn -o test -f synthetic-data-generator/pom.xml
```

`GeneratorBoundarySourcePolicyTest`（4 用例）是**负向证据**：Java 源码、配置、迁移脚本、`pom.xml` 中一旦出现商城/分析平台的包名、库表、配置键或构件依赖即失败。

## 当前进度（诚实登记）

- ✅ **S1**：独立工程骨架、配置、端口 8092、自有库与五表迁移、边界守卫、真实启停实测（2026-09-11）。
- ⬜ **S2**：纯逻辑搬迁（分布/行为链/时间权重/场景注册表）+ `JsonlEventSink` + 产物 manifest。
- ⬜ **S3**：运行模型与 §4.4 API 骨架 + 状态机。
- ⬜ **S4**：`ReferenceMallHttpAdapter`（阻塞于 B-04：商城缺行为事件与库存重置公开端点）。
- ⬜ **S5**：页面或 CLI。⬜ **S6**：第二适配器/不同字段夹具（M1-9）。

进度与证据的权威登记处是 `docs/项目实施进度与任务看板.md`（§3.1 任务包）与 `docs/开发过程事实与决策记录.md`。
