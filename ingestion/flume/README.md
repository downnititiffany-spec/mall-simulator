# 采集链路说明（阶段 1 Taildir / 阶段 2 Spooling Directory）

本目录有**两份互不替代**的 Flume 配置模板，对应设计文档里的两种采集语义。
**不得把两者混写成同一种配置**（设计 V3.0 §8.2 L268）。

| 配置 | 采什么 | 完成/断点语义 | 平台侧消费方 |
|---|---|---|---|
| `flume-taildir.conf`（阶段 1，V2 期遗留） | 商城**运行中**的滚动日志 `{landing}/events/{yyyyMMddHH}.jsonl` | Taildir position 文件断点续读；文件还在长，靠"读到当前末尾"推进 | 本机由 `LocalFileIngestor` + `file_checkpoint` 等价实现（布局 `ROLLING_LOG`） |
| `flume-spooldir.conf`（阶段 2，S2-04B） | 已写完的**完成文件**（搬进 spool 即宣告完成） | Spooling Directory Source；文件进 spool 后不再变 | `LandingInputScanner`（布局 `FLUME_RAW`，扫描 `{landing}/raw/`） |

> **状态：未实测。** 本机（LOCAL）没有 Flume/Hadoop，两份配置都**没有真正跑过**。
> 已实测的只有平台侧等价实现与其门禁：
> `LandingLayout` / `LandingInputScanner` / `IngestionService` 的布局分支（JUnit，见
> `analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/landing/`），
> 以及 `FlumeSpoolConfigTest` 对 `flume-spooldir.conf` 的**静态**约束检查
> （禁 `%{eventType}`、checkpoint 与 data 分目录、不改 `.tmp` 后缀、spool ≠ Landing raw）。
> 部署到 SINGLE_NODE / REMOTE_CLUSTER 并跑通 §5.2.9 采集验收前，不得声称任一配置可用。

## 阶段 2 数据路径（只有这一条）

```text
商城事务+Outbox → 滚动日志 → 完成文件 → 交接(Windows→WSL，见 conf 规则(6))
  → /data/flume/spool/mall (SpoolingDirSource)
  → FileChannel(checkpoint/data 分目录)
  → <landing>/raw/dt=%Y%m%d/hour=%H/events-*   ← HDFS/file Sink，**只按 ingest 时间分区**
  → LandingInputScanner(FLUME_RAW, 递归)
  → ingestion_batch / ingestion_batch_file(相对键) / manifest
  → Spark(ODS 入口)
```

要点（对应设计 §8.2 L259-L266 六条规则）：

1. **完成才进 spool**：同文件系统内 `mv` 改名是"完成"的唯一信号；文件名永不复用；
   Windows → WSL 跨系统先落地为 `*.jsonl.tmp` 再在 spool 内改名。
2. **FileChannel**：`checkpointDir` 与 `dataDirs` 必须分开且都在持久盘；停机不丢事件。
3. **sink 只按 ingest 时间分区**：`dt=%Y%m%d/hour=%H`，**禁止** `%{eventType}`
   ——按类型分目录等于让采集侧替业务做解释，与 ODS 分区口径分叉。
4. **写中的文件不算完成文件**：sink 侧写中文件带默认 `.tmp` 后缀，spool 侧 `includePattern`
   只放行 `^[^._].*\.jsonl$`；平台侧 `LandingInputScanner` 再挡一次（`.tmp` 后缀、隐藏或 `_`
   路径段、零字节、非常规文件）。三处口径必须一致。
5. **at-least-once，不是 exactly-once**：agent 重试/重启会重复写入，去重由 DWD 按 `event_id`
   负责；平台记录 file offset 与批次计数，不承诺端到端恰好一次。
6. **交接是显式的一步**：Windows 商城与 WSL spool 是两套路径字符串，不互相 resolve；
   排序按文件名里的时间戳+序号，不靠修改时间。

## 本机（LOCAL）实现

`mall-simulator` 内置 `LocalFileIngestor`（`com.graduation.mall.ingestion`），
以 **Flume Taildir 语义的本机等价实现**验证完整采集链路，无需安装 Hadoop/Flume：

| Taildir 语义 | 本机实现 | 证据 |
|---|---|---|
| 文件级断点（position 文件） | `file_checkpoint` 表（next_offset 持久化） | 暂停→恢复→继续不丢不重 |
| at-least-once 投递 | 读取期间文件增长时以文件末尾为终点，重复行由 DWD 按 `event_id` 去重 | 事件契约 §4 |
| 未知版本/坏行隔离 | 坏行 → `landing/quarantine/{batchNo}.jsonl` + `quarantine_record` | §5.2.5 |
| 批次与偏移可追踪 | `ingestion_batch` / `ingestion_batch_file`（start/end_offset） | §5.2.8 |

## 目录约定（代码与 Flume 共同遵守）

```text
{landing}/events/{yyyyMMddHH}.jsonl         商城滚动日志（Taildir 源 / 本机采集器输入；布局 ROLLING_LOG）
{landing}/raw/dt=YYYYMMDD/hour=HH/events-*  Flume Spooling 落地区（布局 FLUME_RAW，递归）
{landing}/accepted/{batchId}/…             采集并校验通过的干净行（ODS 入口）
{landing}/quarantine/{batchId}/…           隔离行（未知版本/契约违规，待转换器适配）
{landing}/manifests/{batchId}.json         批次清单（READY，§9.3 WAIT_LANDING 认它）
{landing}/dirty/{yyyyMMddHH}.jsonl         生成器注入的脏数据样本（独立于正常链路）
```

布局由 `runtime_profile.landing_layout` 选择（V22 新增列）：空值＝`ROLLING_LOG`（默认，行为与
V2 逐字节一致），`FLUME_RAW`＝递归读 `raw/` 下的完成文件；**未登记值直接拒绝**（`PARAM_INVALID`），
不静默回落——拼错一个字母本该报错，而不是"采集成功、0 条"。

## 切换到真实 Flume（SINGLE_NODE / REMOTE_CLUSTER）

1. 选一份配置部署（阶段 2 用 `flume-spooldir.conf`），position/channel/spool 目录都用持久盘；
2. 把 `runtime_profile.landing_layout` 设为与该配置一致的值（`flume-spooldir.conf` ⇒ `FLUME_RAW`），
   并把 `landing_uri` 指向 sink 的落地区根；
3. 采集验收（§5.2.9）：暂停 Flume → 商城继续生成 → 恢复 Flume → 事件最终进入 Landing
   且没有重复计数；确认 Landing 接收前不清理源日志（Taildir 路径下尤其重要）。

## 常用命令（本机）

```bash
# 启动商城 + 生成器后，手动触发一轮采集：
curl -X POST http://127.0.0.1:8090/api/v1/ingestion/runs
curl http://127.0.0.1:8090/api/v1/ingestion/status
curl "http://127.0.0.1:8090/api/v1/ingestion/batches?limit=10"
```
