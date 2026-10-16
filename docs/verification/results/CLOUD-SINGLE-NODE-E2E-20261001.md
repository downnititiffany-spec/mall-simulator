# 云端 Linux 单节点联调结果（2026-10-01）

状态：**当前任务范围 PASS_WITH_LIMITATION**。本结果仅记录云端单节点证据，不构成总控产品最终验收。

## 环境与边界

- Git checkpoint：`161ae29 chore: checkpoint cloud single-node integration`，分支 `codex/cloud-v3-development`；随后按用户授权继续的脚本与结果文档列为本地待存档改动。
- RunId：`cloud_261001_071427_ac5e43`，根目录 `/workspace/single-node/cloud-e2e/cloud_261001_071427_ac5e43`。数据、日志和生成事件均留在仓库外的任务目录。
- 复用 MySQL 8.0.41、Hadoop 3.3.4、Hive 3.1.3、Spark 3.5.1、JDK 17、Maven 与既有 Node/Vite 依赖；MySQL 仅绑定 `127.0.0.1:3307`。全程未连接 3306。
- HDFS 复用初始化过的 namespace `/workspace/single-node/runs/cloud_20261001_hdfs01`，未格式化。HDFS 服务端口为 19000/19010；Hive Thrift HMS 为 19083。
- Derby 分开验收：平台 `SINGLE_NODE` Spark pipeline 的嵌入式 Derby 是本 RunId 下的 `derby-embedded`；共享 Thrift HMS 后端 Derby 是既有 `/workspace/single-node/runs/cloud_20261001_hdfs01/derby/metastore_db`。Hive CLI 与独立 Spark 3.5.1 `local[1]` 均通过 `thrift://127.0.0.1:19083` 读取 `cloud_smoke`、`default`。平台 pipeline 结果没有被用作共享 HMS 证据。
- 健康监听通过：3307、19000、19010、19083、8090、8091、8092、5173、5174。分析平台、商城、生成器健康请求通过；浏览器对分析页和商城页分别登录成功并实际请求自己的后端 API，响应为 200，无浏览器页面异常。

## 数据链与发布记录

所有 batch 属于 `sourceId=1`（`mock-mall`）；pipeline 逐批串行执行。

| 输入 | 摄取结果 | 流水线结果 | 发布 |
|---|---|---|---|
| 文件生成器 v1，20 条，seed 20261001，SHA-256 `ba4f333a91d6f670497e88bdb74b11a64caa5c9e9aaf9d68736039a9e9e94d7d` | batchId=1，20/0 隔离 | runId=1 SUCCESS | `S20261001_1`，初始 ACTIVE |
| 文件生成器 v2，20 条，seed 20261002，SHA-256 `f556072985f7cf2cd14f2ec3ccb5ed0bfc4efe6a0775cef5d1ae3bf197f7c4c3` | batchId=2，20/0 隔离 | runId=2 SUCCESS | `S20261001_2`，ACTIVE；与独立累计 oracle 一致：PV=8、UV=3、fav=1、cart=1、DAU=3、有效支付订单=0 |
| 重放，无新输入 | 摄取 run SUCCESS，0 条；batchId=3 | runId=3 no-op SUCCESS，未绑定输入批次或 snapshot | ACTIVE 仍为 `S20261001_2`，没有新快照 |
| 文件生成器 v3，10 条，seed 20261003，SHA-256 `d162e878bd3bf1c9af672cdbc91434e49c9cde75930d05aa13248fcfe512971c` | batchId=4，10/0 隔离 | 首次 runId=4 在 INIT_SCHEMA 受控失败，`RUN_JOB_FAILED`；旧 ACTIVE 仍为 `S20261001_2` 且原指标未变。恢复有效 JAR 配置后，runId=5 对该批受控重试 SUCCESS | `S20261001_5` 发布；失败快照 `S20261001_4` 未发布 |
| 生成器 `MALL_API`→商城 HTTP→Outbox，94 条 Outbox 事件 | Mall landing 原文、Flume spool 输入、HDFS 三方 SHA-256 均为 `c74e16972d27146aef8bd4402684c169e1851e0aca86be74647df15111865a44`；batchId=5，94/0 隔离，43,336 B | runId=6 SUCCESS，8 阶段通过 | `S20261001_6` ACTIVE，`S20261001_5` ARCHIVED |

`MALL_API` generator runId=`cloud_261001_071427_ac5e43_mall_http-v1-20261001-154044-5818`，targetId=1，seed=20261004，计划事件预算 60，真实操作成功 60、失败 0。操作流水 61 行：只读 `listProducts` 5 次、用户创建 5 次、订单创建 26 次、取消 12 次、支付 13 次；所有流水结果为 OK。商城 Outbox 有 94 个唯一 event_id：user_registered 5、order_created 26、stock_reserved 26、order_cancelled 12、stock_released 12、order_paid 13，无重复。

独立 oracle 在平台发布前按 `order_created`、`order_paid`、`order_cancelled` 的订单 ID 关联重算：26 个订单、13 个最终有效支付、12 个取消、支付与取消无重合；paid amount 合计 1617.40。快照 `S20261001_6` API 返回 paid_order_cnt=13、GMV=1617.40、net_sale=1617.40；退款为 0。累计行为 oracle 为 PV=8、UV=3、DAU=3、fav=2、cart=2，API 对应值逐项一致。其余 14 项 overview 指标均由同一快照返回，核心质量规则 4/4 通过。

浏览器在最终快照发布后重新登录分析平台，前端代理实际调用 `/api/v1/dashboards/overview` 与快照/source API 均为 200，页面显示 PV 8、UV 3，pageErrors=0。此前商城前端登录后真实调用 `/api/v1/mall/products` 为 200，pageErrors=0。

## 缺陷处置与独立测试档位

- 首个旧 RunId `cloud_261001_070712_10c8a3` 的 runId=1 因 harness 提前创建 Spark Derby 的 `create=true` 目标目录而在 INIT_SCHEMA 失败。脚本改为不创建该最终目录，让 Derby 自行初始化；保留失败数据和日志。新 RunId 完成 runId=1..6 的后续验证。此为 harness 修复，未改产品逻辑。
- mall 健康探针曾错误地对 POST-only 登录路径发 GET；现改为 SPA 根页检查，并以浏览器真实登录独立验证。
- MySQL 启动改用独立 session，修复 shell 退出后进程失去生命周期的问题。`scripts/cloud-generator-cli.sh` 的 `--with-mall-token` 只从调用进程内存环境取短期 `CLOUD_MALL_TOKEN`，在 Java 子进程内使用，并在 CLI 日志写入前脱敏；target 元数据只保存环境变量引用名。
- Smoke：健康端口、Hive/Spark 对共享 Thrift HMS 的只读检查、两个前端与应用 HTTP 健康均 PASS。
- 单元测试：本轮没有改业务实现或单元测试；不把历史单测结果冒充本轮结果。云端脚本通过 `bash -n`，仓库差异通过 `git diff --check`。
- 隔离数据库测试：本轮未重新运行独立 MySQL IT 套件。早前已发布的 `PROJECT_STATUS.md` 中 13/13 IT 属于其各自的历史隔离 RunId。本次真实 MySQL 3307 的 Flyway、pipeline 发布、快照/API读回属于单节点 E2E，不替代该 IT 档。
- 单节点 E2E：本结果所列 RunId 上真实完成。重型 Spark 作业顺序执行。

## 限制、恢复与回滚

- 这是单机 Linux `local[1]` 场景。未验证 YARN、多节点/远程集群、生产级可用性、并发规模或性能；真实 LLM/外部 provider 未接入。异构第二源和 stub-local AI/决策的既有状态以 `PROJECT_STATUS.md` 对应验收记录为准，本结果不重复宣称。
- 服务当前保留运行；无需重复安装或启动。检查：`cd /workspace/mall-simulator && ./scripts/cloud-single-node.sh status`。停止本 RunId 的任务进程但保留 MySQL/HDFS 文件：`./scripts/cloud-single-node.sh stop`。数据和日志保留在该 RunId 目录。
- 需要新鲜隔离运行时，确认旧服务已停止且任务端口释放，再执行 `./scripts/cloud-single-node.sh start`。脚本生成新 RunId、3307 数据目录和随机进程凭据，复用既有已初始化 HDFS namespace；不会格式化 HDFS，也不会复用或覆盖旧 RunId。新 MALL_API 运行须重新通过商城登录取得短期令牌，仅以调用进程的 `CLOUD_MALL_TOKEN` 环境值传给 `scripts/cloud-generator-cli.sh --with-mall-token`；令牌不要写入文件、命令历史、日志或仓库。
- 回滚脚本改动可恢复 `scripts/cloud-single-node.sh`/`scripts/cloud-generator-cli.sh` 的 Git 版本并停止当前任务进程；任务数据保持原位供审查。当前 11 个原有文件改动完整保留。
- 本轮最重要的执行选择、依据和影响已登记于 `docs/PROJECT_COMPLETION_CHECKLIST.md`。本结果不更改冻结 V3.0 正文、不接触 3306、不包含凭据、数据库备份或原始事件归档。
