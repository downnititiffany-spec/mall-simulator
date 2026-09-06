# Slice03: LOCAL 采集链路（断点采集 + 批次状态机 + 契约校验隔离） — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 mall-simulator 内实现阶段 4 的 LOCAL 采集链路（§5.2.5/§5.2.7/§5.2.8）：`LocalFileIngestor` 以 Flume Taildir 语义（断点偏移、暂停恢复、不重复）读取 `landing/events/*.jsonl`，逐行契约校验，干净行进入 `landing/landed/{batchId}/`，坏行隔离到 `landing/quarantine/` 并记录 `quarantine_record`；批次状态机 `GENERATED→COLLECTING→LANDED→VALIDATING→SUCCESS/QUARANTINED` 落 `ingestion_batch`；同时交付真实 Flume 部署模板（集群替换用）。

**Architecture:** 采集 = 可替换基础设施适配器（§19.3 精神）。`EventIngestor` 接口 + `LocalFileIngestor`（本机验证等价语义：文件级 checkpoint 表 `file_checkpoint` 记录 next_offset，恢复时从断点继续，保证 at-least-once + 不重复计数）。`EventContractValidator` 为纯函数逐行校验（信封必需字段/事件类型白名单/版本白名单/金额格式/行为枚举），坏行不入 landed。批次与文件明细分别落 `ingestion_batch`/`ingestion_batch_file`。CLI/API：`POST /api/v1/ingestion/runs`（手动一轮）、`GET /api/v1/ingestion/status`、`GET /api/v1/ingestion/batches`。

**Tech Stack:** Java 17、Spring Boot 3.2.5、MyBatis-Plus、Flyway V2、JUnit 5（MallTestSupport 集成底座）。

**外部约束（V2.2 文稿）:** §5.2.5 分阶段采集（首版 Flume Taildir→Landing；本机用等价实现验证语义）；§5.2.7 批次状态机；§5.2.8 增量/迟到/重放（保存 start_offset/end_offset/批次号/记录数；DWD 按 event_id 去重故允许 at-least-once）；§5.2.9 验收（暂停恢复不丢不重；未知版本与错误数据不进入正式层）。

---

### Task 1: Flyway V2 采集元数据表

**Files:**
- Modify: `src/main/resources/db/migration/V2__ingestion.sql`
- 表：`ingestion_batch`（batch_no 唯一、status、source、record_count、error_count、quarantine_count、landing_dir、start/end_time）、`ingestion_batch_file`（batch_id+file_path 唯一、start_offset、end_offset、record_count、status）、`file_checkpoint`（file_path 主键、next_offset）、`quarantine_record`（event_id、schema_version、reason、raw_path、batch_id）

### Task 2: 实体与 Mapper（4 表 × entity+mapper）

### Task 3: EventContractValidator（纯逻辑）

**Files:** `ingestion/EventContractValidator.java` — `check(String jsonLine, int lineNo)` → 返回 `Violation(reason)` 或 null；校验项：JSON 可解析、信封 7 必需字段、event_type 白名单（12 类）、schema_version 白名单（1.0）、金额字段格式 `^\d+(\.\d{1,2})?$`、behavior_type 枚举。
- Test: `ingestion/EventContractValidatorTest`（好行通过；7 类坏行各返回对应 violation）

### Task 4: LocalFileIngestor + IngestionService

**Files:**
- `ingestion/EventIngestor.java` 接口：`List<IngestedFile> ingest(Path eventsDir, TraceContext trace)`、`Path landedRoot()`、`Path quarantineRoot()`
- `ingestion/LocalFileIngestor.java`：按文件名排序扫描 `events/*.jsonl`；每个文件从 `file_checkpoint.next_offset` 读取 → 逐行 Validator → 干净行写 `landed/{batchId}/events-{file}.jsonl`，坏行写 `quarantine/{batchId}.jsonl` + quarantine_record；EOF 后 upsert checkpoint；返回文件级结果（start/end offset、行数、坏行数）
- `ingestion/IngestionService.java`：一轮 = 创建 batch(COLLECTING) → ingest → 统计 → VALIDATING → SUCCESS（无坏行）/ QUARANTINED（有坏行）→ 返回摘要
- `ingestion/IngestionDtos.java` + `controller/IngestionController.java`

### Task 5: Flume 部署模板

**Files:** `ingestion/flume/flume-taildir.conf`（agent：taildir source + 本地/spool 或 hdfs sink 模板，含 position/checkpoint 配置）、`ingestion/flume/README.md`（Taildir 与 LocalFileIngestor 语义对照、集群替换步骤）

### Task 6: 测试与验收

- `LocalFileIngestorTest`（集成）：生成 3 个事件文件 → 采集一轮 SUCCESS、landed 行数=源行数；追加新事件到第 2 个文件 → 第二轮仅采到新增行（无重复计数）→ 累计 landed = 源总数
- `IngestionServiceTest`：含 1 条坏行（非法枚举）→ 批次 QUARANTINED、quarantine_count=1、干净行全部 landed、`quarantine_record` 可查、坏行 sample 可见
- `GoldenIngestionTest`：拷入 `tests/golden-dataset/events/golden-20260901.jsonl` → 采集 → landed=30、SUCCESS；连续两轮不重复
- [ ] `mvn test` 全绿（34 + 新增）
- [ ] 冒烟：启动应用 POST /ingestion/runs，GET /ingestion/status、/batches，核对 landing/landed 与 checkpoint
- [ ] 更新 README（阶段 4 ✅），提交

**验收（本轮完成定义）：** 暂停-恢复-继续不丢不重（§5.2.9 核心）；坏行不进入 landed（未知版本/非法枚举隔离）；批次状态机与文件级 offset 落库可查；黄金数据 30 行对账通过；Flume 部署模板交付。