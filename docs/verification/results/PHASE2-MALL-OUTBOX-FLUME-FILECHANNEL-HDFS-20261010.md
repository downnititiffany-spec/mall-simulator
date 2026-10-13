# 阶段2/7 Mall Outbox 经默认 Flume File Channel 投递（2026-10-10）

## 范围

- task-owned RunId：`cloud_261010_040051_b2ab54`。本项沿用已运行的默认 Flume agent，没有启动/停止该 agent，也没有修改渠道配置。Flume 当前配置为 Spooling Directory → File Channel（checkpoint 与 data 分离）→ HDFS DataStream。
- 通过隔离商城正式登录与用户注册 API 已生成的单条合成 `user_registered` Outbox 事件，执行 Outbox 正式发布后，活动文件为 `/home/asus/graduationproject-runs/cloud-e2e/cloud_261010_040051_b2ab54/mall-landing/events/2026101012.jsonl`：1 行、449 bytes，SHA-256 `0a09fca1885171361c260eb046a58fb3bcfb40a345b205573aa92feb5f2fe459`。
- 以唯一名称原子投递到当前 RunId 默认 Flume spool。`.COMPLETED` 文件已生成，且其 449 bytes 与源 SHA-256 完全一致。
- HDFS 文件：`hdfs://127.0.0.1:19000/landing/cloud_261010_040051_b2ab54/raw/dt=20261010/hour=12/events-.1791606356407.jsonl`，449 bytes，SHA-256 与 Outbox 源一致。事件摘要：类型 `user_registered`、来源 `mock-mall`；事件 ID `f631ce44-38e9-4c87-be36-f0dc640a2de7`。

## 证据边界

- 证明本 RunId 这一条正式商城 Outbox JSONL 经当前默认在线 File Channel agent 投递到 HDFS，且单文件字节级一致。
- 未调用平台采集 API；平台按既有配置读取本地 Mall Landing，不能据此称为平台从 HDFS 回读或 Hive ODS 端到端链路。
- 未中断默认在线 agent；其崩溃恢复由独立隔离 channel 子例另行证明。不能据此声称一般 exactly-once。
- 未连接 MySQL 3306，未直写数据库，未更改/切换 RuntimeProfile；未记录凭据。
- HDFS 命令输出有一条缺少 log4j.properties 的提示，不影响 `dfs -stat` 和文件流哈希结果；试图使用 Windows here-string 统计管道行数时遇到 CRLF 参数传递问题，该检查未计为通过。单行数以原 Outbox JSONL 校验和 HDFS 单文件字节级同哈希为范围内证据。

阶段2/7和阶段4/项目总体验收仍开放。运行现场与数据留在 task-owned RunId。
