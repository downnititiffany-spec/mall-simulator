# 阶段2/4 Mall Outbox、Flume File Channel 与平台本地采集合约复核（2026-10-10）

## 当前 RunId 与授权范围

- task-owned RunId：`cloud_261010_040051_b2ab54`；Mall `8090`、平台 `8091` 均为 loopback。只通过商城正式登录、用户注册和 Outbox publish API 创建合成数据；平台采集通过正式 `POST /api/v1/ingestion/runs`。没有直接写业务数据库、没有连接 MySQL 3306、没有 profile 修改/切换，token/密码未记录。
- 默认在线 Flume agent 使用当前 RunId 配置的 Spooling Directory → File Channel → HDFS Sink。没有停止、重启或中断默认 agent。

## 输入枚举拒绝与修正

- 首条合成 Mall 注册输入使用 `ageGroup=adult`、`cityLevel=tier2`、`memberLevel=basic`。正式采集返回 batch ID 1、`QUARANTINED`、recordCount=1、quarantineCount=1、noNewData=false。既有 `mock-mall.v1.json` 的 `ageGroup` 语义没有 `adult`，`memberLevel` 没有 `basic`；`MappingExecutor.mapEnum` 对契约/映射均未定义的枚举产生 `BAD_ENUM`。这是测试夹具值与登记映射不匹配，不据此判定生产代码缺陷。批次查询 API 未返回 quarantine_record.reason，本报告不声称读到了数据库隔离明细。
- 保留原事件与隔离记录，没有重新投递或重放。用独立幂等键，通过商城正式注册 API 新建另一条合成用户，取契约允许的 `25-34 / tier2 / silver`。Mall Outbox publish 返回 publishedCount=1。
- 原 Outbox 第一行哈希仍为 `0a09fca1885171361c260eb046a58fb3bcfb40a345b205573aa92feb5f2fe459`；第二行事件 `36265ff5-f7c3-43f4-9053-45cf2e967021` SHA-256 为 `9d458e4e8e0198395c46537546896d2127b794dca6abb4854b06ad7c136af43b`。将第二行作为独立唯一名 spool 文件原子投递，`.COMPLETED` 生成；对应 HDFS 文件 `/landing/cloud_261010_040051_b2ab54/raw/dt=20261010/hour=12/events-.1791606726460.jsonl` 为 450 bytes，SHA-256 与第二行一致。

## 平台采集与 checkpoint

- 第二次 `POST /api/v1/ingestion/runs`：batchNo `ing-20261010123228-0aa900ed`，SUCCESS，recordCount=1、quarantineCount=0、noNewData=false。
- 随后第三次调用：batchNo `ing-20261010123237-51c99e8f`，SUCCESS，recordCount=0、quarantineCount=0、noNewData=true。说明本 RunId 上的本地 Landing 文件断点只消费新增字节，随后的空运行没有再次采集。
- 三次采集均有真实管理员会话，结束时注销；只记录汇总字段。首批的 QUARANTINED 及有效事件的 SUCCESS/空增量结果都保留。

## 证据边界

- 本轮串起正式 Mall API → Outbox JSONL → 默认 Flume File Channel → HDFS 字节级传输，并分别实测平台本地 Mall Landing 输入的枚举隔离、合法新增事件采集和 noNewData checkpoint。
- 平台 `IngestionService` 读取 RuntimeProfile 配置的本地 Mall Landing `events/`，不是 HDFS 上的 raw；因此不声称平台从 HDFS 回读，也不覆盖 Hive ODS/DWD/DWS/ADS、第二来源或完整端到端。
- 默认 agent 未中断；独立 File Channel 恢复子例见 `PHASE2-DEFAULT-FLUME-FILE-CHANNEL-CRASH-RECOVERY-20261010.md`，不能把隔离子例扩大成默认 agent 崩溃验收或一般 exactly-once。
- 阶段2其余数仓/多源/失败恢复门槛、阶段4整体与最终验收仍开放。
