# 阶段 4：LOCAL 采集链路说明

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
{landing}/events/{yyyyMMddHH}.jsonl     商城滚动日志（Flume Taildir 源/采集器输入）
{landing}/landed/{batchNo}/events-*.jsonl  采集并校验通过的干净行（ODS 入口，本机版）
{landing}/quarantine/{batchNo}.jsonl    隔离行（未知版本/契约违规，待转换器适配）
{landing}/dirty/{yyyyMMddHH}.jsonl      生成器注入的脏数据样本（独立于正常链路）
```

## 切换到真实 Flume（SINGLE_NODE / REMOTE_CLUSTER）

1. 部署 `flume-taildir.conf`，position/channel 目录用持久盘；
2. Taildir 通配 `events/.*\.jsonl`，文件滚动与商城写入节奏一致（每小时一个文件）；
3. HDFS Landing 目录 `/landing/events/{event_type}/dt=…/hour=…` 之上建 ODS 外部表（阶段 5）；
4. 采集验收（§5.2.9）：暂停 Flume → 商城继续生成 → 恢复 Flume → 事件最终进入 Landing
   且没有重复计数；确认 Landing 接收前不清理源日志。

## 常用命令（本机）

```bash
# 启动商城 + 生成器后，手动触发一轮采集：
curl -X POST http://127.0.0.1:8090/api/v1/ingestion/runs
curl http://127.0.0.1:8090/api/v1/ingestion/status
curl "http://127.0.0.1:8090/api/v1/ingestion/batches?limit=10"
```