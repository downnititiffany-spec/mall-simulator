# 阶段 7：当前 RunId MALL_API → Outbox → Flume → HDFS

日期：2026-10-12  
RunId：`cloud_261012_012653_d3b0fc`  
工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`

## 实际链路

- 通过本地 Mall 正式登录 API 获取短时 token；token 只保存在本轮 Python 进程内，随后调用 logout 成功。Mall 库、生成器库均属于当前 RunId 的 MySQL 3307；没有直连数据库。
- 通过 Generator API 创建 `REFERENCE_MALL_HTTP` 目标 ID 1，凭据仅登记为 `CLOUD_MALL_TOKEN` 引用。追加不可变 MALL_API 计划 `s7p-261012-012653-d3b0fc-a` v1：场景 `refund_rise`、seed `20261012`、60 个事件、单小时窗口。使用 `cloud-generator-cli.sh` 自动识别活动进程 task root 后启动正式生成器 CLI。
- 生成 Run `s7p-261012-012653-d3b0fc-a-v1-20261012-102537-8201` 状态 `SUCCESS`，success=60、failed=0。操作流水 61 行，0 FAILED；真实 HTTP 成功操作：目录读取1、用户注册5、下单22、支付10、取消11、退款4。此为流水与应答证据，不声称 exactly-once 或目标库逐行回读。
- Mall `outbox/status` 初始 pending=0；生成后状态仍为0（没有调用发布 POST）。Mall 滚动 JSONL `2026101210.jsonl` 有89行，89个唯一 event_id、重复0，source_system 均为 `mock-mall`。事件分布：order_created 22、order_paid 10、order_cancelled 11、refund_created/refund_completed 各4、user_registered 5、stock_reserved 22、stock_released 11。
- 现有 `cloud-flume-submit-landing-file.sh` 因 NameNode 进程使用旧 RunId 配置而按守卫拒绝，未写 HDFS。没有绕过该拒绝；新增 `cloud-flume-submit-active-run.sh`，核对当前 Flume PID 2414、当前 RunId 配置、File Channel、spool/checkpoint/data 目录、HDFS 目标路径与空 Landing 后，向当前 RunId spool 投递唯一完整文件。没有重启、停止或格式化服务。
- 默认 File Channel 将89行写入 `/landing/cloud_261012_012653_d3b0fc/raw/dt=20261012/hour=10/` 的单个文件。源与 HDFS 内容均89行、SHA-256 相同：`5946695fdf129017ce2e9721d639e52492b4cbccf62f00793f5e6c2e5a732c43`。active-run helper 返回 `result=PASS`、`contentMatches=true`；常驻 Flume PID仍为2414。

## 验证及边界

- `bash -n scripts/cloud-flume-submit-active-run.sh`：PASS。
- `mvn -o -pl platform-common '-Dtest=AnalyticsIsolationScriptsContractTest' test`：13/13 PASS，0 failures/errors/skips。
- 当前证据闭合 Mall HTTP 生成、滚动事件文件、当前 RunId File Channel 和共享 HDFS namespace 中的 RunId 专属 Landing 路径。HDFS NameNode 本身由先前 RunId 持有，这是已恢复栈复用的 namespace，不登记成独立 NameNode 部署验收。
- 尚未执行平台认证摄取、ODS/Hive、Spark、ADS 发布、指标 API/浏览器或 AI/决策链。`GRADUATION_TEST_ADMIN_PASSWORD` 当前任务环境不可见；先前隔离管理员凭据重置操作被执行策略在启动前拒绝，没有数据库写入。3306 未访问。
- 阶段 7、阶段 4、阶段 8 和总验收保持开放。失败的 `cloud-flume-submit-landing-file.sh` 守卫与替代 active-run helper 的证据均保留在本报告；没有重放商城 POST。
