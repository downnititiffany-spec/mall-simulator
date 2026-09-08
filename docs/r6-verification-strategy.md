# R6 验证策略（四级测试，不再每次重跑全链）

> 依据用户 2026-09 评审意见固化：缩小数据量但不缩减业务场景；降低全链运行频率但不取消真实链路验收。
> 核心原则：**L0/L1 快速测试覆盖复杂逻辑（状态机/幂等/重试/命令/解析），真实 Spark 只在里程碑跑一条"小而全"黄金小链（L2），大规模性能全部推迟到 R9（L3）。**

## 核心原则

> 缩小数据量，但不能缩减业务场景；降低全链运行频率，但不能取消真实链路验收。

## 一、四级测试

| 级别 | 什么时候运行 | 数据量 | 是否启动 Spark | 目标时间 |
|---|---:|---:|---:|---:|
| L0 编译与单元测试 | 每次修改后 | 无或内存对象 | 否 | 10～30 秒 |
| L1 模块集成测试 | 完成一个小功能后 | 20～100 条 | 通常否 | 1～3 分钟 |
| L2 真实小链路 | R6 子阶段完成时 | 50～500 条 | 是 | 3～10 分钟 |
| L3 完整/性能验收 | R6 完成、R9、发布前 | 10 万～100 万以上 | 是 | 30 分钟以上 |

日常开发只跑 L0、L1；只有完成一个跨组件节点时跑一次 L2；L3 全部推迟到 R9。

## 二、R6 快速测试基建（不启动 Spark）

1. `PipelineServiceTest`（编排状态机）：
   - 首次提交立即返回 taskId，不阻塞到 Spark 完成；
   - 相同幂等键返回原任务；
   - WAIT_LANDING 成功后进入 LOAD_ODS；
   - 某阶段失败后流水线变为 FAILED；
   - 重试时跳过已经成功的阶段；
   - 质量检查失败后不得进入发布阶段；
   - 重试次数 attemptNo 正确增加；
   - 并发提交同一个幂等键只能产生一个任务。
2. `FakeJobSubmitter`（测试替身）：不启动 Spark，可配置 externalJobId/RUNNING/SUCCESS/FAILED/指定日志/退出码。
3. `JobCommandBuilderTest`：JAR 路径来自 RuntimeProfile；businessDate/runtimeProfileId/inputVersion/outputSnapshotId/attemptNo 齐全；`--conf` 放在 `--class` 之前；路径带空格仍为一个参数；LOCAL 与 REMOTE 生成不同但合法的命令。
4. `JobResultParserTest`：能读取最终 JobResult；前面普通 JSON 日志不误判；缺少结果行判失败；退出码非 0 判失败；日志过长正确截断；异常日志仍可入库。

## 三、真实 Spark 只保留一条"小而全"黄金小链（R6 专用微型数据集）

约 50～100 条事件，必须覆盖：用户创建/更新、商品创建/更新、浏览/收藏/加购、
单商品订单、多商品订单、支付、取消、部分退款、全额退款、迟到退款、重复 event_id、
错误 JSON、未知 schema_version、空必填字段、质量检查成功场景、质量检查失败场景。

## 四、R6 必须真实运行 Spark 的边界（每项小数据验证一次即可）

- LocalProcessSparkSubmitter 确实启动了 spark-submit；
- 后端参数能被 JobRunner 正确解析；
- externalJobId/退出码/日志路径落库；
- Spark 确实写出目标分区；临时分区成功后切换正式分区；失败不污染正式数据；
- 重试只重跑失败阶段；质量失败阻断发布；后端阶段状态与 Spark 实际结果一致。

SSH 远程集群平时只验证"命令生成和状态处理"，真实 node01～03 提交放到 R6 最终验收/R9。

## 五、影响矩阵（按改动范围决定跑什么）

| 修改内容 | 必须运行 | 不必运行 |
|---|---|---|
| Controller、DTO、参数校验 | Controller/接口测试 | Spark、Hive 全链 |
| 异步线程池、状态转换 | PipelineServiceTest | 四层数仓重放 |
| 幂等、重试、阶段跳过 | 流水线单元测试＋元数据库测试 | 大数据量测试 |
| Spark 命令构造 | 命令构建测试＋一次单作业冒烟 | 完整 ADS 链 |
| JobArgs/JobResult 契约 | 契约测试＋一次真实提交 | 大规模性能测试 |
| 某个 Spark SQL | 对应单作业＋微型分区 | 重跑所有无关作业 |
| ODS/DWD 表结构 | 对应层测试＋L2 小全链 | 页面和 AI 全测 |
| ADS 指标口径 | DWS/ADS 小数据对账 | 重新采集商城历史数据 |
| Hive→MySQL 发布 | 固定 ADS 样本＋MySQL 发布测试 | Landing→DWD 重放 |
| 看板页面 | Mock API＋前端构建 | Spark 全链 |
| AI 提示词 | 固定 EvidencePackage＋Mock AI | 数仓重放 |
| 事件字段或跨层契约 | L2 真实小全链 | 百万级性能测试 |

仅以下内容改变才需完整小链：事件 Schema、Hive DDL、跨层 SQL、JobArgs/JobResult、
流水线阶段顺序、临时分区发布机制、Hive ADS 到 MySQL 的发布边界。

## 六、run-spark-chain.ps1 开发模式（后续增强）

支持参数：`-BusinessDate 20260901 -Dataset micro -FromStage bdw -ToStage fna -JobCode tds -ReuseWarehouse -NoInit`。
- 日常不执行 `mvn clean`（避免删除已编译 JAR 触发 Scala 重建）；
- 只有 spark-jobs 或共享契约变化才重打 Spark JAR；
- 固定一个业务日 + 小分区；Spark 测试用 `local[2]`。

## 七、可复用测试检查点

```text
tests/fixtures/
├── landing-micro/ accepted-micro/ ods-micro/ dwd-micro/ ads-micro/
├── evidence-package/
└── expected/
```

每个里程碑结束时仍跑一次完整小链，防止检查点单独正确、连接失败。

## 八、执行节奏

- 每次修改：编译受影响模块 → 对应测试类 → R6 快速测试集合；
- 完成 R6 子阶段：快速测试全过 → 只跑一个真实 Spark 作业 → 核对 externalJobId/日志/阶段记录；
- R6 全部完成：50～500 条黄金数据 → 真实完整小链一次 → 失败注入和恢复一次 → 保存验收结果；
- R9 才跑完整端到端（商城→采集→Landing→ODS→DWD→DWS→ADS→MySQL→页面→AI→决策）+ 10万/100万性能对照。

## 最重要的调整

1. 先给 analytics-server 补上 R6 快速测试能力（当前 src/test 全空，这是只能反复手工跑真实环境的根本原因）；
2. 真实数据缩为 50～500 条但保留全部业务和失败场景；
3. 只有跨层契约变化才跑完整小链，大规模测试全部进入 R9。