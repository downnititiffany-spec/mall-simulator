# 兼容性矩阵（§29.3 · 实际安装版本与最小连通测试）

> 依据：V2.0 指导书 §29 与项目设计文稿 V2.2 §29。**论文必须写真实环境**：本表 2026-09-11 按
> `docs/acceptance/r9-20260911-fabc6cb-run25-S20260901_25/` 证据包重写，候选版本仅作集群参考。
> 任何组件升级后需在本表追加一行并补最小连通测试记录。

## 1. 实际安装版本（本机实测）

| 组件 | 版本 | 安装位置 / 来源 | 连通性 | 最小测试记录 |
|---|---|---|---|---|
| JDK | 17.0.12 LTS | `D:\Develop\JAVA17` | ✅ | `java -version`；Spring Boot 编译/运行 |
| Maven | 3.9.14 | `D:\apache-maven-3.9.14` | ✅ | 全量 `mvn test` |
| Node | 24.16.0 | — | ✅ | `npm run build`（web） |
| MySQL | 8.0.41（服务 MySQL80） | C:\Program Files\MySQL | ✅ | `analytics_meta` Flyway **V1–V15**（14 版，无 V6）+ `analytics_metric` V1–V3；平台全量测试见 `24-fulltest-analytics-server.log` |
| MySQL Connector/J | 8.x（Boot 托管） | maven 仓库 | ✅ | 数据源连通、`metric_read` 只读账号实测（仅 `GRANT SELECT ON analytics_metric.*`） |
| Scala | 2.12.19（编译）/ 2.12.17（本机 CLI） | maven 仓库 | ✅ | spark-jobs 编译打包 + 46/46 单测 |
| Spark | 3.5.1（bin-hadoop3，单机 lib） | `D:\Develop\spark-3.5.1-bin-hadoop3` | ✅ | local：55 行黄金夹具全链 run 25 真实 SUCCESS（8 阶段、11 个 Spark 作业） |
| Spark 内嵌 Hive | Derby（仓库根 `derby-metastore`） | Spark 发行自带 | ✅ | `sci/odl/bdw/tdw/dim/usw/fna/dqc/pub/mxp/ljp` 真实提交；四层行数见证据包 `15-hive-layers.txt` |
| Hadoop client | 3.3.4（provided 依赖） | maven 仓库 | ⏳ | 仅编译期依赖；集群环境待验证 |
| Flume | 1.11.0（部署模板） | `ingestion/flume/flume-taildir.conf` | ⏳ | 本地等价实现已测，集群实录待环境 |
| Spring Boot | 3.2.5 | maven 仓库 | ✅ | 平台全量测试见 `24-fulltest-analytics-server.log`（本表不再写死测试条数，以该日志为准） |
| MyBatis-Plus | 3.5.7 | maven 仓库 | ✅ | 集成测试 |
| JSqlParser | 4.9 | maven 仓库 | ✅ | 四层校验单测 |
| ECharts | 5.5.0 | npm | ✅ | 看板渲染验证截图 |

## 2. 最小连通测试记录（L0）

```bash
# 1. 平台（分析平台，端口 8091；商城 mall-simulator 为独立进程 8090）
pwsh scripts/start-all.ps1 -PlatformOnly                        # Flyway V1–V15 + 8091
curl http://127.0.0.1:8091/api/v1/metrics/health                # {code:OK}

# 2. 前端（web 静态资源由平台打包；开发模式 dev 代理指向 8091）
cd web && npm run dev                                           # :5173，/api 代理 8091

# 3. Spark（单机 local，无 Hadoop 服务）—— 真实链由平台提交，不由脚本直连
pwsh scripts/start-all.ps1                                      # 平台 + 商城
# 黄金夹具五步小链（与 run 25 同路径，见 .verify/r9-evidence-collect.ps1 与 r7-3-run24.ps1）
```

测试口径以证据包内日志为准：平台全量 `24-fulltest-analytics-server.log`、spark-jobs 46/46、
mall-simulator 54/54、web `node --test` 74/74（**不是 vitest**）。
`scripts/run-spark-chain.ps1` 为**遗留脚本**：跳过 `tdw/dim`、默认仓为 `D:\Develop\tmp\spark-warehouse`，
与平台真实链不同源，不得作为链路证据。

## 3. 集群部署候选版本（待环境验证后回填）

| 组件 | 候选 | 回填项 |
|---|---|---|
| Hadoop | 3.3.x | NameNode/DataNode/YARN 连通、HDFS 写读 |
| Hive | 3.1.x | Metastore 服务、DDL 执行四层表 |
| Spark | 3.5.1 | YARN client 模式提交、日志收集 |
| Flume | 1.11.0 | Taildir→HDFS 断点恢复实录（§5.2.9） |
| 后续增强 | DataX/Doris/Livy/DolphinScheduler | 依 §29 官方文档二次验证 |

接入新组件前统一执行：版本核对 → 最小连通（L0）→ 黄金数据回归 → 性能记录。

## 4. 官方参考资料（§29.2）

- Apache Flume 1.11.0 发布与用户文档：https://flume.apache.org/releases/1.11.0.html
- Alibaba DataX 开源仓库与插件模型：https://github.com/alibaba/DataX
- Apache Livy 文档与 REST API：https://livy.apache.org/docs/latest/
- Apache Doris 官方概览：https://doris.apache.org/docs/
- Apache SeaTunnel 部署文档：https://seatunnel.apache.org/docs/getting-started/locally/deployment/

> 注：DataX/Doris/Livy/SeaTunnel 均属第二阶段；Livy 仍为 Apache 孵化项目，
> 接入前需独立验证与所选 Spark/Hadoop 版本的兼容性（§29.1）。