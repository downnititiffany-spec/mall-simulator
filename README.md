# 基于 Spark 大数据平台和智能分析模型的电商用户行为分析系统

面向普通运营员工的商城数据分析 Web 系统：数据采集 → Hive 分层数仓 → Spark SQL → ADS 指标快照 → Spring Boot 查询服务 → Vue 看板、AI 分析与决策跟踪。

## 当前权威入口（V3.0）

| 权威文件 | 职责 |
|---|---|
| [项目完整实施指导书 V3.0](docs/guidance/项目完整实施指导书%20V3.0.md) | 项目目标、范围、八个开发阶段、完成标准、总控裁决 |
| [项目设计文档 V3.0](docs/design/项目设计文档%20V3.0.md) | 当前实现、模块/数据/接口/算法、部署、安全与待开发方案 |
| [PROJECT_STATUS.md](docs/PROJECT_STATUS.md) | 当前代码、进度、问题、测试、证据与下一步 |

**历史整理阶段已结束；项目正式进入毕业设计功能开发阶段。** 发布代码基线为`e3c1070c02964512cdd3444c76e3e39ec833bcc8`。V3.0发布后只读，正式变更由总控发布V3.1。代码Agent可维护PROJECT_STATUS，不得自行改变目标/架构、提升backlog或宣布完整验收。

全部V2.x指导书、设计稿、历史看板、remediation-status与历史契约/验收资料均为**历史只读**，保持原路径。旧资料的“当前”只指历史时点，不是现行开发指令。更多导航见[docs/README](docs/README.md)。

## 系统边界与目录

| 程序 | 代码 | 端口 | 职责 |
|---|---|---:|---|
| 分析平台 | analytics-server + web | 8091 | 采集、计算编排、质量、指标、AI与决策 |
| 参考商城 | mall-simulator + mall-frontend | 8090 | 商品、订单、模拟支付退款、Outbox |
| 独立生成器 | synthetic-data-generator | 8092 | 目标适配、场景计划、限速HTTP操作或文件夹具 |

商城/生成器不是平台必需的在线依赖；停止后仍可查询已发布指标。分析平台不直连商城业务库，生成器不直接写商城业务表。前端开发端口分别5173/5174，生产构建分别交由自己的后端承载。

平台六模块：platform-common、connection-ingestion、warehouse-pipeline、metric-analysis、ai-decision、platform-app。spark-jobs为独立Scala工程；仓库无根聚合POM。

```text
analytics-server/           Java/Spring Boot平台六模块
web/                        分析Vue前端
mall-simulator/             独立商城后端
mall-frontend/              商城Vue前端
synthetic-data-generator/   独立生成器
spark-jobs/                 Spark SQL/Scala作业
warehouse/                  分层DDL与迁移制品
ingestion/                  Flume配置等采集材料
scripts/                    构建/启动/测试入口
tests/                      黄金数据及预期结果
docs/guidance/              正式指导书
docs/design/                正式设计及原位历史设计
docs/PROJECT_STATUS.md     唯一动态状态
docs/acceptance/            历史证据（本轮只读）
```

## 数据链与实现边界

```text
商城/外部来源 → 日志或导出 → LocalFile / Flume → Landing
→ ODS → DIM + DWD → DWS → ADS → MySQL指标快照
→ Spring Boot → Vue / AI证据解释 / 决策跟踪
```

平台已有8阶段编排：WAIT_LANDING、INIT_SCHEMA、LOAD_ODS、BUILD_DWD、BUILD_DWS、BUILD_ADS、QUALITY_CHECK、PUBLISH_METRIC。JobRegistry注册11码，其中ljp是独立验证作业；历史完整业务链为10个Spark作业，不把阶段数当作业数。

MySQL是默认指标服务库，不替代Hive数仓。元数据analytics_meta与指标analytics_metric分库；商城mall_simulator和生成器generator_meta独立。Doris后续优先，ClickHouse延后；Hive显式降级为设计目标，不随机按数据大小切引擎。

AI只消费稳定ADS/指标快照及其证据，不直接扫描原始DWD。真实模型、模板回退和Mock须区分；AI辅助建仓是后续独立方向，不是当前AI分析层的原始数据访问许可。

已有本地/隔离实链证据，不代表当前全部功能、Flume/HDFS、真实模型和集群都已验收。**F-88仍为限定验收，完整验收未宣布。**

## 运行环境与安全

后端Java17/Spring Boot3.2.5；Spark3.5.1/Scala2.12；Vue3/Vite5/ECharts5。Spark算法测试显式JDK8，与后端JDK17区分。

Windows可运行应用并使用WSL隔离依赖。WSL单节点可按需模拟HDFS/HMS/Spark，远程集群通过配置接入，不能绑定node01–03。实际部署与连接前置见[设计文档](docs/design/项目设计文档%20V3.0.md)。

- 开发写入型测试目标是批准的3307隔离实例和runId专属库/受限账号。
- 宿主3306为正式/历史实例，保持写入、迁移、ACTIVE切换冻结；不能为运行演示盲用默认配置。
- 本轮未启动应用或复查进程，不声明三个服务当前在线。
- 凭据通过受保护环境注入；README不提供实际账号密码或直接连接正式库的初始化命令。
- 旧部署/验收脚本属于历史参考，执行前按当前隔离规则审查，不能直接复制旧清理动作。

## 统一测试入口与fresh基线

入口：`scripts/run-tests.ps1`。以下来自2026-09-15[DEV-003c fresh证据](docs/acceptance/dev003c-unified-test-entry-20260915/REPORT.md)，本次文档发布未重新跑测试。

| 档位 | CLI参数 | 当前登记结果 |
|---|---|---|
| default-tests | -Suite default | analytics632 + mall13 + generator106 = **751** |
| isolated-tests | -Suite isolated | mall30 + generator19 + metric-analysis IT6 = **55** |
| spark-tests | -Suite spark | **111**，JDK8、ScalaTest |
| all-tests | -Suite all | 顺序执行三档；任一失败或0 tests整体失败；fresh exit0 |

```powershell
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark
# 隔离实例、对应runId库/受限账号、环境凭据已按批准流程准备后：
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite isolated -RunId dev3_20260915_case01 -Confirm
```

Maven/JDK路径不同需传入口支持的覆盖参数。隔离/all需要Confirm和环境凭据，入口不会因此自动授权建库或清理。正式计数变动须总控批准，不能用AllowCountDrift掩盖缺失用例。

Spark仅以本轮新写TestSuite.txt的实跑数、failed/aborted和成功标记判断；Surefire的Tests run:0/BUILD SUCCESS不能代表ScalaTest成功。**local[1] + in-memory catalog通过 ≠ 生产Hive/Spark集群验收通过。**

日常优先受影响单测；跨模块再default，数据库用isolated，Spark改动用spark，阶段性再all/小链。55条验证语义，1000条轻量联调，不每次大规模压测。前端仍可用web内npm test/build；尚未并入统一入口，不计入751。

## 接下来如何开发

1. 开发基线与核心链路确认。
2. 数据采集与ODS/DWD/DWS/ADS数仓主链。
3. Spark SQL指标计算与ADS体系。
4. Spring Boot查询/分析服务。
5. Vue可视化页面。
6. AI智能分析。
7. 业务联调与真实数据链路。
8. 部署、验收、论文证据与答辩材料。

DEV-003整理阶段收口完成；DEV-003d、DEV-004、历史IT收编、F-88完整剩余、F-93、3307旧对象清理、GitHub Actions和前端统一入口转development backlog，仅总控可在真阻塞当前阶段时提升。具体任务和权限看指导书；当下事实看PROJECT_STATUS。

## 文档维护

本轮只新增两份V3.0正式文档，更新本README、docs/README和PROJECT_STATUS。更新前三文件原件备份在`docs/backups/v3-release-20260915/`。历史指导书、设计、看板和证据均不移动、不删除、不覆盖。V2.x正式结束，不再发布“毕业设计指导书V2.9”。正式文档下一版V3.1由总控决策。
