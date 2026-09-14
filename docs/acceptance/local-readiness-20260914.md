# 2026-09-14 本地轻量测试与 WSL 单节点准备度

## 结论与范围

项目不必在日常开发时连接 node01–03。现有 LOCAL 路径和真实 Spark local[1] 测试支持本机开发；WSL 单节点是可行部署方向，但尚未验证当前完整应用在 WSL 中启动、采集和发布。

本次针对 HEAD 8983616 加当前未提交工作树进行检查，没有修改生产源码、既有指导书或正式数据库；没有启动集群、安装 WSL、运行数据库写入 IT。新建此报告，不覆盖历史文稿。

## 新执行结果

| 范围 | 数量 | 失败 | 备注 |
|---|---:|---:|---|
| platform-common | 49 | 1 | 库名字面量门禁命中注释 |
| connection-ingestion | 156 | 0 | 本次选定快速测试 |
| warehouse-pipeline | 123 | 0 | 排除真实提交器 SmokeTest |
| metric-analysis | 47 | 0 | 未运行数据库 IT |
| ai-decision | 91 | 0 | 不代表真实 LLM 验收 |
| platform-app | 100 | 1 | manifest 文件集合断言失败 |
| spark-jobs | 111 | 0 | JDK8；15 个套件；含真实 local[1] 小数据计算 |
| 分析前端 | 74 | 0 | Node 测试；生产构建成功 |
| 商城前端 | 6 | 0 | Node 测试；生产构建成功 |

后端合计 566 项，564 通过、2 失败、0 错误、0 跳过。首次遇错停止；第二次为收集其他模块结果使用 maven.test.failure.ignore=true。第二次命令的 BUILD SUCCESS 不表示测试全绿。显式排除的测试不计入上述数量。

Spark Maven 总耗时 40.371 秒，ScalaTest 23.595 秒。此次使用隔离临时目录和内存 catalog，不是完整 Hive Metastore + MySQL 发布验收，也不是性能对比实验。

原始测试报告：analytics-server 各模块 target/surefire-reports；spark-jobs/target/surefire-reports。它们会被以后测试覆盖，本报告记录的是本轮汇总。

## 确认的问题

1. WarehouseNameLiteralGateTest:109 命中 TradeDwdJob.scala:145、SurrogateKey.scala:160 的注释文本。未据此认定运行时存在硬编码。建议将历史错误原文留在证据文档，源码注释引用证据；或完善扫描器的词法识别，同时保留对真实 SQL 字面量的负向测试，不整体关闭门禁。
2. IngestionManifestSourceSchemaTest:168 对当前文件集合的前置断言失败，差异为 40.json、41.json、42.json、43.json。固定历史清单测试应使用冻结夹具及明确清单；实时目录检查应单独报告。不得删除实际 manifest 使测试变绿。
3. SINGLE_NODE 提交器走本地进程，但 RuntimeProfileSnapshot.isLocal() 仅识别 LOCAL；JobCommandBuilder 在 master 缺省时会给 SINGLE_NODE 选 yarn。必须统一执行模式判定，并补 LOCAL/SINGLE_NODE/REMOTE_CLUSTER 配置矩阵测试。在修复前，本地档案明确使用 LOCAL 和 local[1]，不能依赖缺省值。
4. MetricAdsMySqlIT、MetricPublisherMySqlIT 仍包含直连 analytics_metric 和 cleanup 路径。本轮未运行。需完成测试库白名单、专用最小权限账号、写前拒绝正式库、按 runId 限定清理。
5. RuleSeverity 对已登记规则按 ruleCode 全局覆盖原严重度，接口未携带规则版本。EVENT_ID_UNIQUE/PUB_DQ_EVENT_ID_UNIQUE 固定为 WARN；此函数未检查重复率阈值。单测通过不证明满足“限定历史版本兼容、超阈值不得静默降级”的裁决。下一步须追踪调用侧并补阈值与版本边界测试，不能直接将 F-88 标 DONE。

## 当前服务和环境

本轮 HTTP 探测 8090、8091、8092、5173 不可达，不把连接失败等同于代码缺陷。未开展登录、真实订单入口或员工页面 E5 验收。

只读 WSL 枚举确认 Ubuntu、docker-desktop 都是 WSL2 且处于 Stopped。尚未进入 Ubuntu 核对 Java/Spark/MySQL 安装状态。

## 建议的低资源部署

优先使用单个 Ubuntu WSL2：分析后端与 Spark 位于同一 Linux 环境，避免 Windows 本地提交器直接执行 Linux 路径。前端可留 Windows。商城、生成器仅在演示输入链时启动。

第一档：本地文件 Landing + Spark local[1] + 本地 Parquet 数仓 + Hive 支持的嵌入式 Metastore + 隔离 MySQL。只允许一个数仓作业运行，避免嵌入式 Derby 并发锁；不启动 YARN/HDFS 服务。保留 ODS/DWD/DWS/ADS 及交易分层，不把 MySQL 改成唯一数仓。

第二档：需要验收 Flume→HDFS 时再启动单 NameNode/DataNode，副本数 1，仍使用 Spark local[1]。这是单节点 HDFS 接口验证，不证明多机容错或水平扩展。

建议起始预算为 WSL 2 CPU、4–6 GB 内存、Spark 单作业 driver 1 GB、shuffle partitions 2–4；仅为待实测起点，不是最低配置保证。应先确认宿主机总内存及空闲量，再决定 .wslconfig 上限。本轮未更改配置。

## 下一轮最小验收

1. 先修两项快速测试失败及 SINGLE_NODE 默认 master 问题。
2. 安全隔离 MySQL、warehouse、metastore、Landing、checkpoint、staging/export 和快照作用域。
3. 在 WSL 内核对运行时版本，将 D: 路径配置化为 Linux 绝对路径；数据优先存放 WSL Linux 文件系统。
4. 固定 55 条黄金样本、种子、配置和期望值；串行跑一次完整采集→分层→质量→MySQL 发布→页面查询。
5. 构造质量失败，验证旧 ACTIVE 仍可读；另验证重复导入幂等。只对变动范围跑追加用例，不默认重跑大数据。
6. 集群验收保留为可选里程碑，不以单节点成功替代已有集群要求；变更指导书阶段出口前先明确裁决并另存新版本。

参考：Spark local[K] https://spark.apache.org/docs/3.5.8/submitting-applications.html ；WSL 资源限制 https://learn.microsoft.com/en-us/windows/wsl/wsl-config 。
