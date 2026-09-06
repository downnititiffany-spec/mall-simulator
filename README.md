# 基于 Spark 大数据平台和智能分析模型的电商用户行为分析系统

本仓库为毕业设计《基于 Spark 大数据平台和智能分析模型的电商用户行为分析系统设计与实现》的工程代码仓库。
权威设计文档为 `基于Spark大数据平台和智能分析模型的电商用户行为分析系统设计与实现——项目设计文稿 V2.2.md`（本仓库根目录），
开发按其中 **§28 分阶段开发路线** 与 **§19 可执行级工程设计** 执行。

## 阶段进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 契约与基准：事件 Schema、指标字典、黄金数据 | ✅ 完成 |
| 2 | 最小商城：商品、购物车、下单、支付、退款与 Outbox | ✅ 完成 |
| 3 | 自动生成：场景、分布、随机种子和脏数据 | ✅ 完成 |
| 4 | 环境与采集：LOCAL 环境 + Flume，再验证远程集群 | ✅ 完成（LOCAL：断点采集+批次状态机+契约隔离；Flume 模板已交付） |
| 5 | 数仓与 Spark：ODS、DWD、DWS、ADS、算法和质量规则 | ✅ 四层 29 表 DDL + 6 个 Scala 作业；**本地 Derby-Hive 全链实跑通**（sci→odl→bdw→usw→fna，13 万事件）；集群多机验证待环境 |
| 6 | 指标服务：快照发布和 MySQL MetricStore | ✅ 快照状态机(BUILDING→VERIFYING→ACTIVE)、流水线7阶段+幂等键、MetricStore 接口（黄金对账 60 测试全绿） |
| 7 | Web 程序：权限、运行中心和普通员工分析页面 | ✅ 分析 API + Vue3/ECharts 看板 6 页面（端到端联通）；登录权限随后续阶段 |
| 8 | AI 与决策：证据解释、受控查询、报告和决策闭环 | ✅ 语义层+受控SQL校验+证据解释 + 决策闭环（AI草稿→审核→基线锁定→效果评价，83 测试全绿） |
| 9 | 测试与实验：功能、性能、恢复、对照和安全实验 | ✅ 100题评测(拦截100%)、黄金回归、性能压测(缓存优化185-254x、P95≤30ms)、Spark 三档规模；集群性能与真模型对照待环境 |
| 10 | 论文与答辩 | ✅ 初稿素材齐备：九章初稿 + 实验汇总表 + 11 张截图 + 16 页答辩网页 PPT；正文精修与答辩演练留待作者 |

## 仓库结构（§19.1）

```text
docs/                 契约、API、部署、验收、兼容性矩阵与论文初稿素材
tests/golden-dataset/ 黄金数据及标准答案
tests/ai-questions/   Text-to-SQL 评审测试集（100 题）
mall-simulator/       简化单片应用：商城/生成器/采集/流水线/指标/AI/决策（阶段 1-8）
spark-jobs/           Scala Spark 作业（6 个，含本地链验证）
web/                  Vue 3 + ECharts 看板（7 页面）
warehouse/            四层数仓 DDL（29 表）与血缘说明
ingestion/flume/      Flume 部署模板（集群模式）
scripts/              一键演示 / Spark 链 / 截图脚本
experiments/          全部实测结果档案（可复现）
ppt/                  答辩网页 PPT（16 页）
```

## 本地开发

- JDK 17、Maven 3.9+、MySQL 8.0（服务已运行）。
- 数据库凭据通过环境变量提供，**禁止写入 Git**：
  - 副本制：`Copy-Item .env.example .env.local` 后填写，`.env.local` 已被 .gitignore 忽略；
  - 或在 shell 中设置 `MALL_DB_PASSWORD` 环境变量。
- 商城应用（独立进程、独立库 `mall_simulator`）：
  ```bash
  cd mall-simulator
  mvn spring-boot:run
  # 或先跑测试：
  mvn test
  ```