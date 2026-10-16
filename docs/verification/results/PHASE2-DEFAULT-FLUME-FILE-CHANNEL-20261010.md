# 阶段2/7 统一单节点默认 Flume File Channel（2026-10-10）

## 改动

- `scripts/cloud-single-node.sh` 默认 Flume agent 之前使用 `memory` channel，与 V3.0 设计 §8.2 的 File Channel 小链要求不一致，最近一次真实分页采集因此明确不能提供 Flume 持久性/恢复证据。
- 现改为 RunId 专属 File Channel，checkpoint=`$RUN_DIR/flume-channel/checkpoint`、data=`$RUN_DIR/flume-channel/data`，独立目录；显式 `useDualCheckpoints=false`、`checkpointInterval=30000`，容量与事务容量沿用既有配置。该变更仅影响后续启动，不改写已运行中的 agent。

## 复核

- 当前工作树脚本经 WSL `bash -n` 通过；File Channel 类型、两个分离目录和 no-memory-channel 静态守卫通过；`git diff --check` 通过。
- 对原隔离 RunId `cloud_261010_005249_1c73ec` 的停机使用统一脚本身份守卫，退出码 0；其数据库和 HDFS 数据目录保留。新启动前 preflight 通过，没有创建目录、凭据、数据库、HDFS 路径或进程。
- 当前 RunId `cloud_261010_040051_b2ab54` 由修改后的启动脚本启动；status 显示 12/12 目标端口 LISTEN。其生成的 `flume.conf` 是 `type=file`，Flume Java 进程存活，RunId 下 checkpoint 与 data 目录独立且已初始化。
- 向此默认 agent 的 task-owned spool 原子投递 162 字节/1 行 synthetic transport probe；Flume 将源文件改名为 `.COMPLETED`，HDFS Sink 在 `/landing/cloud_261010_040051_b2ab54/raw/dt=20261010/hour=12/events-.1791605190810.jsonl` 写入 1 行/162 字节。完成文件与 HDFS 输出 SHA-256 同为 `44d47282b65ebcd244ee1ebc76e78553164b16cab368856a261a8369edf90ddf`。
- 只读 `GET /api/v1/mall/outbox/status` 返回 HTTP 401；未进行商城写请求、未生成商城业务数据。该单行数据是直接投入隔离 transport spool 的 synthetic fixture。

## 证据边界

本轮证明默认配置在当前 RunId 实际完成单行 spool→File Channel→HDFS 投递，源与输出字节完全一致；但没有在此 RunId 执行 kill/restart 恢复，也没有经 Mall API 生成业务事件或经平台摄取。历史 `BATCH-U-STAGE7-FLUME-SPOOL-CHAIN-RESULT.md` 的 File Channel 故障恢复结果保持独立历史证据。下一步应在该默认 agent 上做受控积压期间中断/恢复并核对源、HDFS 及重投语义。未连接 MySQL 3306。