# M3 前置演练：指标导出物 → 导入 MySQL 闭环（本地实测）

- 报告路径：`docs/acceptance/m3-step8-parity-20260912/raw/post/export-import-rehearsal/README.md`
- 执行时间：2026-09-12 22:25 ~ 22:33（本机 Windows，仓库根 `D:\Develop_code\GraduationProject`）
- 性质：**只读代码 + 隔离库写入** 的机制/参数/幂等性实测。**未修改任何生产代码**；未跑 Spark；未调 `POST /api/v1/pipeline-runs`；未触碰 `landing/`；未新增 git 提交。
- 结论口径：以下每条结论都对应一个 raw 证据文件与一个可复现命令；推断项显式标注「推断」；未做的一律进 §10「未测与不可声称」。

---

## 0. BLOCKED 声明（先读）

> **BLOCKED — 集群导出物（cluster export artifact）：本环境不存在可测对象，本次未测、也未连接任何集群。**
>
> 证据：
> 1. `runtime_profile` 表**只有一行**：`id=1, profile_code=local-dev, type=LOCAL, landing_uri=file://./landing, hdfs_uri=NULL, hive_jdbc_url=NULL, spark_master=local[2], deploy_mode=NULL, spark_submit_path=D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd, spark_job_jar_uri=.../spark-jobs-0.1.0-SNAPSHOT.jar, metric_store_type=MYSQL, version=3`（`raw/57-run47-profile.txt` 原文）——**没有任何 REMOTE_CLUSTER/HDFS 档案**。
> 2. 导出根 `metric-staging` 下 13 个快照目录全部是**本机文件系统路径**产物（`raw/60-artifact-inventory.txt` A 段），最近一次 run 47 清单里的 `exportFile` 也是 `D:/Develop_code/...`（同文件 D 段）。
> 3. 导入侧读文件用 `java.nio`（`MetricExportManifest.java:32/35`、`AdsExportReader.java:56/59`、`MetricPublishValidator.java:72`），Spark 侧写文件用 Hadoop `FileSystem`（`MetricExportJob.scala:142`）——若导出物落在 HDFS，导入侧能否读取**属未验证的机制缺口**。
>
> 因此本报告**全部结论只覆盖 LOCAL 档案下的「本地导出物 → MySQL」段**；凡涉及「集群导出」的结论一律不可由本报告得出（详见 §10）。按任务口径，本次采用「先用本地导出物排练、不等集群」：使用的是**生产 run 47 真实产出的本地导出物** `metric-staging\S20260901_47`，全程只读、sha256 未变（§2.2 与 `raw/90` 末段）。

---

## 1. 结论速览（一句话）

**闭环在本机以 LOCAL 档案真实存在、真实跑通过，且我可以按生产同一套参数、同一份导入器字节码、同一份导出物在隔离库里稳定复现：导入 22 行 ADS + 10 条指标值、15/15 校验通过、幂等（重导不翻倍）、三类失败路径均不残留半成品、快照置 FAILED、旧 ACTIVE 不动。**
**但这条闭环的「集群导出」腿在本环境不存在可测对象：`runtime_profile` 表里只有 `local-dev(LOCAL)` 一个档案（`hdfs_uri=NULL`、`spark_master=local[2]`），本机不存在集群导出物，我也没有连接任何集群。凡涉及「集群导出物 → 导入」的说法，本次一律不可声称。**

| 待验证问题 | 实测结果 | 证据 |
|---|---|---|
| 导出物在哪、长什么样 | `metric-staging\S20260901_47\`：`_export.json` 3648 B + 8 个 JSONL，共 9 文件；清单 `totalRows=22`、`source=spark-ads` | `raw/60-artifact-inventory.txt` |
| 导出目录由谁决定 | 运行中的 8091 进程命令行显式传 `-Dplatform.metric.publish.export-dir=D:\Develop_code\GraduationProject\metric-staging`；代码默认值 `metric-staging`（`PipelineService.java:124-125`），发布阶段拼 `<root>\<snapshotId>`（`:542`） | `raw/54-running-process-vs-source.txt` |
| 导入器是谁 | `MetricPublisher.publish`（`metric-analysis/.../publish/MetricPublisher.java:81`）；生产唯一调用点 `PipelineService.java:579`（`PUBLISH_METRIC` 阶段 `:537`） | 本文件 §3 + `raw/52` |
| 有无 CLI/脚本入口 | **无 main、无脚本、无 REST 直调入口**；触发方式是流水线阶段 | §3.6 |
| 写哪些表 | 8 张 ADS 宽表 + `metric_value` + `metric_snapshot`，共 10 张 | `raw/02-schema-dump.sql` |
| 是否幂等 | **是**（delete-then-insert + 主键/唯一键兜底）。同物重导：22 行 / 10 值不变，两次全量 SELECT 只差 `published_at` | `raw/31`、`raw/32` |
| 失败是否回滚 | 无 DB 回滚；靠**显式补偿删除**。三类失败残留 ADS 行 = 0、`metric_value` = 0、ACTIVE 指针不动 | `raw/46` |
| 导入期间读数是否稳定 | **不稳定**：同快照重导期间，profile 的 ACTIVE 指针实测空缺 ≈170 ms，ADS 行可出现 0 与 1/2/7/15 等中间态；`metric_value` 全程稳定（700/700 样本 = 10 行） | `raw/74` |
| 我是否动了生产库 | 未动：`analytics_metric` 快照 27 仍 ACTIVE/12、`published_at` 未变、10 条指标值、ADS 行数不变 | `raw/50` |
| 生产是否真的发生过导入失败 | **发生过**：run 38 的 `PUBLISH_METRIC` 阶段 FAILED（`RUN_METRIC_PUBLISH_FAILED`），快照 `S20260901_38` 行留存为 FAILED、8 张 ADS 表与 `metric_value` 残留全为 0，而它的导出目录**照样存在** | `raw/59` |

---

## 2. 导出物（问题 1）

### 2.1 位置与形态

根目录 `D:\Develop_code\GraduationProject\metric-staging`（mtime `2026-09-12 21:29:22`），下辖 13 个快照目录。`S20260901_47` 为**最新一次真实产出**（mtime `2026-09-12 21:29:25`，与 run 47 的 `PUBLISH_METRIC` 结束时间 `21:29:27.333` 相差 2 s）。

```
S20260901_47  visibleFiles=9  hiddenFiles(crc)=9  subdirs=0  lastWrite=2026-09-12 21:29:25
```

- 9 个可见文件 = `_export.json` + 8 个 `<mysqlTable>.jsonl`；另有 9 个 Hadoop `.crc` 伴随文件（Hadoop `LocalFileSystem` 写入的校验旁文件，导入侧不读；大小 12/16/40 B）。
- 形态为**单文件 JSONL**（不是 `part-00000` 目录）：这是 `MetricExportJob.writeJsonl` 的契约（`spark-jobs/.../job/MetricExportJob.scala:139-169`：先写 `<file>.parts` 目录再按 `part-*` 文件名序拼成单文件）。
- 历史形态对照：**13** 个快照目录里仅 `S20260901_21` 还是 8 个 `*.jsonl\` **子目录**（旧 `text()` 直写布局），`_22` 起全部为单文件 —— 与 `MetricExportJob.scala:143-149` 注释记载的 run 21/22 事故与修复一致。**导入器只认单文件**（`Files.isRegularFile`，见 §3.2），故 21 那份旧形态产物**不可导入**（本次未试）。
- **「有导出目录」≠「导入成功」**：13 个导出目录对应的生产快照只有 11 个成功入库（`metric_value` 各 10 行）；`S20260901_21` 是旧形态，`S20260901_38` 是**导入失败**（见 §8 末段）。目录存在性不能作为导入成功的判据。

### 2.2 文件清单（字节数 / sha256 / 首行原文）

| 文件 | 字节 | sha256 | 首行（截断） |
|---|---|---|---|
| `_export.json` | 3648 | `CD071B18E39DC58DFC7506D407397EC6DB75EAF8BC0D85B77482B2EEB553D207` | `{` |
| `ads_operation_overview_m.jsonl` | 160 | `C499ACD6D5A0E23D50AAAA3E7E5681F8041EFB65FDD09D6886D89A3DB22FE5C6` | `{"pv":7,"uv":3,"dau":3,"order_count":5,"sale_amount":2042.00,"net_sale_amount":1493.00,"avg_order_value":408.40,"refund_rate":0.6000,"full_refund_rate":0.2000}` |
| `ads_sale_trend_m.jsonl` | 81 | `7808A741AE4A94EA3D93EC6EFFBF4D90BCC3A2DAE2ADE03A46B49EFA5CA48779` | `{"order_count":5,"buyer_count":3,"sale_amount":2042.00,"avg_order_value":408.40}` |
| `ads_behavior_funnel_m.jsonl` | 332 | `A98B1C68CE8B0E301C7A51FF451C6FCAE12A6DAA9BF91806D66ADE0E4232985C` | `{"stage":"intent","user_count":2,"conversion_rate":0.6667,"overall_buy_rate":1.0000}` |
| `ads_active_trend_m.jsonl` | 30 | `1E0EA9BF127CEC4B276E4411094B794DB4F5B2CBECBDBB13F7936A3070061429` | `{"dau":3,"behavior_count":14}` |
| `ads_hot_product_m.jsonl` | 441 | `2BCFA7688D0F40707280007CD8E48031C7908EFD8D05E966A4FD40C5F5A93C30` | `{"product_id":3,"product_name":"保温杯","heat_score":11.0904,"pv":1,"fav":1,"cart":1,"buy":3,"rank_no":1}` |
| `ads_product_conversion_m.jsonl` | 276 | `9C136D34F2E50BA423D2ACF424EA250F13D452E26D00C7A059C025C804F6673D` | `{"product_id":2,"pv_users":3,"buy_users":1,"conversion_rate":0.3333}` |
| `ads_user_profile_m.jsonl` | 720 | `8B268F063396B8D2AC13461CFE6E7405258270511049802764AA32140B747F54` | `{"user_id":3,"r":3,"f":3,"m":1,"value_group":"一般挽留","active_level":"低","favorite_category":11,...}` |
| `ads_data_quality_m.jsonl` | 476 | `7B5A77A6CEC44E8FDF68F6FF70D091F99C911A9CBA10C3EFF36FC98FD8A09565` | `{"rule_code":"REQUIRED_FIELD_NULL_RATE","check_count":14,"error_count":0,"error_rate":0.000000,"passed":1,"threshold":"0.001"}` |

### 2.3 清单结构（决定导入器行为的关键字段）

`_export.json` 关键字段（原文见 `raw/60` D 段）：

- `snapshotId=S20260901_47`、`businessDate=20260901`、`dt=20260901`、`source="spark-ads"`、`generatedAt=2026-09-12T21:29:25.254295400`、`totalRows=22`。
- `tables[]` 每项：`hiveTable`、`mysqlTable`、`rowCount`、`columns[]`、`hivePath`、`exportFile`。
- `hivePath` 例：`file:/D:/Develop_code/GraduationProject/spark-warehouse/dw_ads.db/ads_operation_overview__staging/snapshot_id=S20260901_47/dt=20260901`（**Hive 暂存分区路径**，导入器只用它做「快照钉住」字符串校验，不读该路径）。
- `exportFile` 例：`D:/Develop_code/GraduationProject/metric-staging/S20260901_47/ads_operation_overview_m.jsonl`（正斜杠，由 `MetricExportJob.posix` 统一，`MetricExportJob.scala:129`）。

### 2.4 导出目录是怎么定的（含集群腿的口径缺口）

- 平台侧：`metricExportRoot` ← `${platform.metric.publish.export-dir:metric-staging}`（`PipelineService.java:124-125`），发布阶段 `exportDir = Paths.get(metricExportRoot).toAbsolutePath().resolve(snapshotIdRef)`（`:542`），既传给 Spark（`--exportDir=`，`:545`）又传给导入器（`:581`）。
- 启动脚本显式固定：`scripts/start-all.ps1:62` 与 `scripts/accept-three-programs.ps1:29` 都传 `-Dplatform.metric.publish.export-dir=<root>\metric-staging`；`application.yml` 里**没有**该配置项 → 生效值就是脚本传入的绝对路径。
- 运行中进程实测：`"D:\Develop\JAVA17\bin\java.exe" -Dfile.encoding=UTF-8 -Dplatform.metric.publish.export-dir=D:\Develop_code\GraduationProject\metric-staging -jar ...\platform-app-0.1.0-SNAPSHOT.jar`（`raw/54`）。
- Spark 侧写文件走 **Hadoop FileSystem**（`target.getFileSystem(hadoopConfiguration)`，`MetricExportJob.scala:142`），而**导入侧读文件走 `java.nio`**（`MetricExportManifest.java:32/35`、`AdsExportReader.java:56/59`、`MetricPublishValidator.java:72`）——**只能读进程可见的本地/挂载路径**。
- 因此「集群档案（REMOTE_CLUSTER）下导出物落在 HDFS，导入侧还能不能读到」是一个**真实存在的机制缺口**，本次**未测**：`runtime_profile` 只有 `local-dev/LOCAL`（`hdfs_uri=NULL`、`spark_master=local[2]`、`spark_submit_path` 指向本机 `.cmd`、jar 为本地 file 路径，`raw/57`），本机既无集群导出物、也无集群档案可跑。

---

## 3. 导入器入口与机制（问题 2，只读代码）

### 3.1 唯一编排者

`com.graduation.analytics.metric.publish.MetricPublisher`（`@Service`，`MetricPublisher.java:41`），实现端口 `MetricPublisherPort`（`platform-common/.../MetricPublisherPort.java:17`）。

`publish(PublishRequest)`（`:81`）执行顺序：

| 步骤 | 位置 | 行为 |
|---|---|---|
| 0 参数校验 | `:89-93` | `snapshotId/exportDir/businessDate` 缺一 → `MP_REQUEST_INVALID`，不写任何行 |
| 0 读清单 | `:96-106` | 读 `<exportDir>/_export.json`；读不到 → `MP_MANIFEST_READ` |
| 0 清单门 | `:102-106` | 4 条 BLOCKING 规则；任一不过 → `MP_MANIFEST_INVALID`（不写任何行） |
| 1 登记快照 | `:110-120` | 取 `previousActiveSnapshotId`（进 evidence）；`createBuilding` 置 BUILDING |
| 2 幂等清理 + 写 8 张 ADS | `:122-148` | 先 `deleteSnapshot(snapshotId)`，再逐表读 JSONL + 批量 INSERT |
| 3 指标值 + 写库前对账 | `:150-...` | 概览列→指标值映射（`OVERVIEW_TO_METRIC` `:45-57`），漏斗 `overall_buy_rate`→`buy_rate`（`:238-245`） |
| 4 落库 | `MySqlMetricStore.publish`（`:149`） | 单事务：写 `metric_value` → 旧 ACTIVE 归档 → 本快照 ACTIVE |
| 5 只读回读校验 | 校验器 | `MP_ADS_ROWS_DB_MATCH` / `MP_METRIC_VALUE_DB_MATCH` |

`OVERVIEW_TO_METRIC` 映射（`:45-57`，决定 `metric_value.metric_code`）：`pv→pv`、`uv→uv`、`dau→dau`、`order_count→paid_order_cnt`、`sale_amount→gmv`、`net_sale_amount→net_sale`、`avg_order_value→avg_order_value`、`refund_rate→refund_rate`、`full_refund_rate→full_refund_rate`。

### 3.2 读什么（格式与路径约束）

- 清单：`MetricExportManifest.read(Path, ObjectMapper)`（`MetricExportManifest.java:31`），`Files.isRegularFile` 前置（`:32`），`Files.readString`（`:35`）。
- 数据：`AdsExportReader.readTable(Path exportFile, String mysqlTable, List<String> columns)`（`AdsExportReader.java:48`）：
  - 文件不存在 → `导出文件不存在: `（`:57`）；
  - 清单列与白名单不一致 → `导出清单列与指标库白名单不一致`（`:51`）；
  - 行内出现非白名单列 → `导出文件第 N 行含非白名单列`（`:92`）；
  - 按 JSON 类型转换（整型 → `Long`、小数 → `BigDecimal`），空值保留（导出侧 `ignoreNullFields=false`）。
- 清单门规则（`MetricPublishValidator.manifestChecks`，`:36`）：`MP_MANIFEST_SNAPSHOT`（清单快照/业务日 = 本次请求）、`MP_MANIFEST_TABLES`（8 张表齐备且列与白名单一致）、`MP_HIVE_PATH_PINNED`（`hivePath` 必须含 `snapshot_id=<本次快照>`，防读到上一版）、`MP_EXPORT_FILES`（`Files.isRegularFile`，`:72`）。

### 3.3 写哪些表

10 张（`raw/02-schema-dump.sql`）：

- 8 张 ADS 宽表：`ads_operation_overview_m`、`ads_sale_trend_m`、`ads_behavior_funnel_m`、`ads_active_trend_m`、`ads_hot_product_m`、`ads_product_conversion_m`、`ads_user_profile_m`、`ads_data_quality_m`（白名单见 `MetricAdsCatalog`，非白名单表名直接抛 `非法表名（不在 ADS 白名单）`）。
- `metric_value`（核心指标值，`MySqlMetricStore`）。
- `metric_snapshot`（快照登记与 ACTIVE 指针，`MetricPublishRepository`）。

写入账号分工：写走 `metric_pub`（`metricPublishJdbcTemplate`），校验回读走 `metric_read`（SELECT-only，`metricReadJdbcTemplate`）——`PlatformDataSources.java`；指标字典读 `analytics_meta.metric_definition`（`metaJdbcTemplate`）。

### 3.4 幂等机制（delete-then-insert + 键兜底）

- `MetricAdsWriter.deleteSnapshot(snapshotId)`（`:44-45`）：`DELETE FROM <table> WHERE snapshot_id=?`，遍历 8 张白名单表；`@Transactional(metricPublishTransactionManager)`。
- `MetricAdsWriter.insertRows(...)`（`:69-70`）：**批量 INSERT，不是 upsert**；注释明确「同一快照同一主键重复写入会报唯一键冲突」。
- 键（`raw/02-schema-dump.sql`）：8 张 ADS 表主键分别为 `(snapshot_id,dt)`、`(snapshot_id,dt,stage)`、`(snapshot_id,dt,rank_no)`、`(snapshot_id,dt,product_id)`、`(snapshot_id,dt,user_id)`、`(snapshot_id,dt,rule_code)`；`metric_snapshot` 有 `uk_snapshot_id(snapshot_id)` 与 `uk_active_profile(runtime_profile_id,active_flag)`（保证同档案至多一行 ACTIVE）；`metric_value` 有 `uk_snapshot_metric_period_dim(snapshot_id,metric_code,period,dimension_key)`。
- 快照登记幂等：`MetricPublishRepository.createBuilding`（`:50`）先 `UPDATE`（回 BUILDING、`active_flag=NULL`），再 `INSERT ... WHERE NOT EXISTS`（`uk_snapshot_id` 兜底）→ 同快照重试不产生第二行（本次实测：重导后 `metric_snapshot.id` 仍为 29、`version` 仍为 1，见 §5）。
- `metric_value` 覆盖式写入（按快照整体覆盖）+ 旧 ACTIVE 归档（`active_flag=NULL`）+ 新快照 `UPDATE ... WHERE snapshot_id=? AND status='VERIFYING'` 置 ACTIVE，全部在 `MySqlMetricStore.publish`（`:149`）**一个事务**内。

### 3.5 失败处理与「半成品」边界

| 失败点 | 机制 | 是否留下半成品 |
|---|---|---|
| 清单不可读/不合规 | 直接 `fail(...)`，未进入写入 | 不写任何行（实测 A：0 行、无快照行） |
| ADS 写入中途异常 | `markFailed`（`:286`）+ `compensate`（`:295`，`deleteSnapshot` 补偿删除，删除条数入 evidence `compensatedAdsRows`） | 实测 C：先写了 6 张表 15 行，补偿后残留 0 行 |
| 写库前对账不通过 | 同上（`MP_VERIFY_FAILED`） | 实测 B：写入 21 行后补偿清理，残留 0 行 |
| 快照状态 | `markStatus(..., FAILED, reason)`（`failure_reason` 落库） | 快照行保留为 FAILED、`active_flag=NULL`（**不是回滚掉**，是显式标记） |
| 旧 ACTIVE | 指针未切换前不归档旧快照 | 实测 A/B/C 后 ACTIVE 仍为 `S20260901_47` |

**注意（本次实测的非原子性）**：ADS 的「先删后插」不是一个大事务——`MetricPublisher.publish` 本身**没有** `@Transactional`（`:41` 仅 `@Service`），事务边界是 `MetricAdsWriter` 每个方法各一个、`MySqlMetricStore.publish` 一个。因此**同快照重导期间存在可被读者观测到的中间态**（§8 有实测轨迹）。

### 3.6 CLI / 脚本 / 接口入口：无

全仓检索（`MetricPublisherPort|metricPublisher.publish|new PublishRequest`）：

- 生产唯一调用点：`warehouse-pipeline/.../PipelineService.java:579`，位于 `PUBLISH_METRIC` 阶段（`:537`，参数装配 `:542-548`）。
- 触发入口是平台的 `POST /api/v1/pipeline-runs`（跑整条 8 阶段链），**没有**任何 `main`、脚本或 REST 端点可以单独触发导入。
- 测试入口：`metric-analysis/src/test/java/.../MetricPublisherMySqlIT.java`（`@EnabledIfSystemProperty(named="metric.it", matches="true")`，`:43`）。它使用合成命名空间（`it-r7-3-*` 快照、`runtime_profile_id=999002`）并在结束时清理自己写入的行，但其数据源指向**生产 `analytics_metric`**（`:60-61` 用 `metric_pub` / `metric_read` 账号直连）。**本次未运行它**——理由：任何直连生产库的 IT 都不属于「隔离演练」，与任务约束冲突。
- 因此「接口/入口存在」在本报告里只作为**代码事实**陈述；我用的是**真实实现**在隔离库上的执行结果（§4-§8），不是接口签名推断。

---

## 4. 演练环境与隔离（可复现）

### 4.1 隔离命名空间

- 新库：`CREATE DATABASE analytics_verify_m3_parity` （`utf8mb4` / `utf8mb4_0900_ai_ci`）——`raw/01-create-isolated-db.txt`。
- 结构来源：`mysqldump --no-data --skip-add-drop-table analytics_metric` 只读导出 10 张表 DDL（`raw/02-schema-dump.sql`，10947 B，10 个 `CREATE TABLE`），导入隔离库（`raw/03-schema-verify.txt`：10 张表、0 行）。
- 授权（**追加式**，生产库上的既有授权未改）：`GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,REFERENCES,INDEX,ALTER ON analytics_verify_m3_parity.* TO 'metric_pub'@'localhost'`；`GRANT SELECT ON analytics_verify_m3_parity.* TO 'metric_read'@'localhost'`；`FLUSH PRIVILEGES`（`raw/06-grants-isolated.txt`）。
- 生产库只读复核（演练前后）：`analytics_metric` 快照 27 仍 `ACTIVE/active_flag=1/version=12`、`published_at=2026-09-12 21:29:27.321` 未变；`metric_value` 10 行；ADS 行数 11/59/27/44（其中 S20260901_47 占 1/4/3/4）（`raw/50`）。

### 4.1b 安全断言：本次没有触发任何链路运行（实测）

`analytics_meta` 只读复核（`raw/81-no-new-runs-check.txt`）：

```
pipeline_run：MAX(id)=47，共 46 行，最新 started_at=2026-09-12 21:26:36.126、finished_at=2026-09-12 21:29:27.334
started_at >= 2026-09-12 22:00 的 run 行数 = 0
started_at >= 2026-09-12 22:00 的 stage 行数 = 0
```

⇒ 本次演练（22:25~22:45）**没有创建任何 pipeline_run / pipeline_stage_run**，即未触发 Spark、未走 `PUBLISH_METRIC` 编排；8091 进程全程为同一 PID 61104（未重启、未停止），`landing/` 未触碰。

### 4.2 驱动（非生产代码，仅在报告目录内）

- `build/RehearsalRunner.java`（199 行）：用 Spring 注解上下文按 `PlatformDataSources` 同名 Bean 装配 `metricPublishDataSource` / `metricReadDataSource` / `metaDataSource` / 两个 `JdbcTemplate` / `metricPublishTransactionManager`，并 `@EnableTransactionManagement` 使 `@Transactional` 代理真实生效；`@ComponentScan("com.graduation.analytics.metric")` 装配**真实实现类**（非 mock）。
- 指标字典从 `analytics_meta.metric_definition` 实读（与 `PipelineService.metricDefinitions()` 同源），实测 16 个码：`avg_order_value, buy_rate, cart_rate, dau, full_refund_rate, gmv, net_sale, paid_order_cnt, product_heat, pv, refund_rate, repeat_rate, stock_days, stock_shortage_rate, user_value_level, uv`。
- `build/run-rehearsal.ps1`：拼出并打印**完整可复现命令行**，运行后打印 `### EXIT CODE = N ; WALL CLOCK = X s ###`。
- 类路径：`metric-analysis/target/classes` + `platform-common/target/classes`（当前源码编译产物）优先，其余 59 个 jar 取自 `platform-app` 胖包 `BOOT-INF/lib`（`build/classpath.txt`）。

### 4.3 与生产接线的差异（必须随结论一起引用）

1. 数据库指向隔离库（生产为 `analytics_metric`）。
2. 不经过 Spark / `PUBLISH_METRIC` 阶段编排（不经 `PipelineService`，直接调 `MetricPublisher`）。
3. 隔离库为空 ⇒ `metric_snapshot.version` 从 1 起（生产快照 27 的 `version=12`）；对应 `MP_OLD_ACTIVE_ARCHIVED` 首次为「上一版 ACTIVE=null」。
4. 请求参数与生产 run 47 逐项对齐（见 4.4）。

### 4.4 请求参数（实读，非估计）

来源 `analytics_meta.pipeline_run` id=47（`raw/04b-run47-row.txt`、`raw/57-run47-profile.txt`）：

| 参数 | 值 | 来源 |
|---|---|---|
| `snapshotId` | `S20260901_47` | `pipeline_run.target_snapshot_id` |
| `businessDate` | `20260901` | 清单 `businessDate` = run 业务日 |
| `businessTime` | `2026-09-01T00:00`（库中 `2026-09-01 00:00:00.000`） | `pipeline_run.business_time` |
| `runtimeProfileId` | `1` (`local-dev`, type **LOCAL**, `spark_master=local[2]`, `hdfs_uri=NULL`) | `pipeline_run.runtime_profile_id` / `runtime_profile` |
| `runtimeProfileVersion` | `3` | `pipeline_run.runtime_profile_version` |
| `pipelineRunId` | `47` | `pipeline_run.id` |
| `exportDir` | `D:\Develop_code\GraduationProject\metric-staging\S20260901_47` | 进程启动参数 + `PipelineService.java:542` |

**可复现命令（RUN1 原文，UTF-8 版）**：

```
"D:\Develop\JAVA17\bin\java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
  -Drehearsal.publish.url=jdbc:mysql://127.0.0.1:3306/analytics_verify_m3_parity?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
  -Drehearsal.publish.user=metric_pub -Drehearsal.publish.password=***
  -Drehearsal.read.url=<同上隔离库> -Drehearsal.read.user=metric_read -Drehearsal.read.password=***
  -Drehearsal.meta.url=jdbc:mysql://127.0.0.1:3306/analytics_meta?... -Drehearsal.meta.user=meta_app -Drehearsal.meta.password=***
  -cp <classes;libs> RehearsalRunner RUN1 S20260901_47 20260901 2026-09-01T00:00 1 3 47 "D:\Develop_code\GraduationProject\metric-staging\S20260901_47"
```

（完整未省略版本见 `raw/12-run1-success-utf8.log` 首段；`run-rehearsal.ps1` 会原样打印。）

---

## 5. 成功路径 RUN1 + 幂等 RUN2（问题 3、4）

### 5.1 RUN1：全新导入

- 退出码 **0**，`WALL CLOCK = 1.395 s`；`PublishReport.evidence.elapsedMs = 203`（发布器自身计时），驱动脚本的 `run elapsedMs = 1180` 是「导入 + 用只读账号独立回读 8 张表」的总耗时，两者不可混用。
- `PublishReport`：`ok=true`、`errorCode=null`、`message=发布成功`、`adsRows=22`、`metricValues=10`；日志原文 `metric publish: 快照 S20260901_47 发布成功（ADS 22 行，指标值 10 条，旧 ACTIVE=null）`（`raw/12:29`）。
- evidence：`previousActiveSnapshotId=null`（隔离库为空，故无旧 ACTIVE 可归档）、`manifestTotalRows=22`、`manifestSource=spark-ads`、`activeSnapshotId=S20260901_47`、`adsRowsByTable={overview=1, sale_trend=1, funnel=4, active_trend=1, hot_product=4, conversion=4, user_profile=3, data_quality=4}`。
- 校验 **15/15 全部 PASS**（14 条 BLOCKING + 1 条 INFO）。关键几条原文：
  - `MP_ADS_ROWS_MATCH ... ads_hot_product_m=manifest:4/written:4 ...`（8 表逐表一致，总 22）；
  - `MP_OVERVIEW_CORE_NOT_NULL ... overview={pv=7, uv=3, dau=3, order_count=5, sale_amount=2042.00, net_sale_amount=1493.00, avg_order_value=408.40, refund_rate=0.6000, full_refund_rate=0.2000}`；
  - `MP_VALUE_MATCH_ADS ... gmv/net_sale/paid_order_cnt 一致`；
  - `MP_METRIC_VALUE_COUNT ... 实际=10 码=[pv, uv, dau, paid_order_cnt, gmv, net_sale, avg_order_value, refund_rate, full_refund_rate, buy_rate]`；
  - `MP_ACTIVE_SNAPSHOT ... active=S20260901_47 期望=S20260901_47`；
  - `MP_ADS_ROWS_DB_MATCH`（只读账号实读 8 表行数 = 清单行数）、`MP_METRIC_VALUE_DB_MATCH`（行数=10/10，值不一致=0）。
- 证据：`raw/12-run1-success-utf8.log`（sha256 `F22131A1FD9E3B377A1058119235206567C34B49B5030F2AFAD3B276E8B2985C`）。

**独立复核（不经导入器，用 mysql 客户端直读隔离库）**——`raw/20-verify-sql.sql` → `raw/21-run1-independent-select.txt`：

```
metric_snapshot: 29  S20260901_47  1  3  2026-09-01 00:00:00.000  47  ACTIVE  1  v2  spark-ads  1  2026-09-12 22:25:47.752  2026-09-12 22:25:47.656
ADS 行数：overview 1 / sale_trend 1 / funnel 4 / active_trend 1 / hot_product 4 / conversion 4 / user_profile 3 / data_quality 4 = TOTAL 22
metric_value（原文值）：pv 7.0000 次 | uv 3.0000 人 | dau 3.0000 人 | paid_order_cnt 5.0000 单 | gmv 2042.0000 元 | net_sale 1493.0000 元
                        | avg_order_value 408.4000 元 | refund_rate 0.6000 | full_refund_rate 0.2000 | buy_rate 1.0000
definition_version：refund_rate = v2，其余 9 个码 = v1（原文如此，逐行见 raw/21 §3）；unit 为空的有 refund_rate/full_refund_rate/buy_rate
概览原文：pv=7 uv=3 dau=3 order_count=5 gmv=2042.00 net_sale_amount=1493.00 avg_order_value=408.40 refund_rate=0.6000 full_refund_rate=0.2000
漏斗：view(3,NULL) intent(2,0.6667) order(3,1.5000) pay(3,1.0000)，overall_buy_rate 均为 1.0000（order 阶段 conversion_rate=1.5000 为 ADS 侧原样落库值，本次不评价其口径正误）
重复粒度探针：(snapshot_id,metric_code,period) 重复行 = 0
```

### 5.2 RUN2：同一份导出物再导一次（幂等）

- 命令与 RUN1 完全相同，仅 label 不同；退出码 **0**，`WALL CLOCK = 1.126 s`，`ok=true`，`adsRows=22`、`metricValues=10`（**未翻倍**）；`elapsedMs=191`（发布器）/ `run elapsedMs=955`（驱动）。
- 走了重试分支，日志原文：`metric publish: 快照 S20260901_47 重试，先清理已写 ADS 行 22 条`（`raw/30:21`）——即第 2 次导入实际删掉了第 1 次写入的 22 行再重写。
- `previousActiveSnapshotId=S20260901_47`、`activeSnapshotId=S20260901_47`（同快照重导不改变 ACTIVE 身份）。
- 唯一「FAIL」项：`MP_OLD_ACTIVE_ARCHIVED severity=INFO ... 上一个 ACTIVE=S20260901_47（本次=S20260901_47）`——**severity=INFO，不参与阻断**（`MetricPublishValidator.blocked()` 只看 BLOCKING），故 `ok=true`。
- **前后对比**：两次独立 SELECT 输出逐行 diff（`raw/32-run1-vs-run2-diff.txt`）**只差 1 行**，即 `metric_snapshot.published_at` 由 `2026-09-12 22:25:47.752` 变为 `2026-09-12 22:26:11.226`；快照 `id=29`、`version=1`、`active_flag=1` 均未变；8 表行数与 10 条指标值逐值相同。

**结论（幂等）**：同一导出物重复导入 = 覆盖式重写，不产生重复行、不产生第二个快照、不影响 ACTIVE 身份；机制是 `deleteSnapshot` + 主键/唯一键（§3.4），且 `metric_snapshot` 侧 `uk_snapshot_id` + `INSERT ... WHERE NOT EXISTS`。

### 5.3 演练结束时留存的隔离库状态（供交接核对，`raw/80-final-state.txt`）

```
检查时刻 2026-09-12 22:43:34.402788
metric_snapshot：29 / S20260901_47   ACTIVE  version=1 active_flag=1 v2 published_at=2026-09-12 22:32:37.973
                 30 / S20260901_47RH FAILED  version=2 active_flag=NULL published_at=NULL  failure_reason=MP_VERIFY_FAILED: MP_ADS_ROWS_MATCH
                 31 / S20260901_47RC FAILED  version=3 active_flag=NULL published_at=NULL  failure_reason=MP_ADS_WRITE_FAILED: 导出文件第 1 行含非白名单列（…）
metric_value：仅 S20260901_47 共 10 行；ADS 8 表合计 22 行；profile 1 的 ACTIVE = S20260901_47
生产 analytics_metric：快照 27 / S20260901_47 ACTIVE / version=12 / active_flag=1 / published_at=2026-09-12 21:29:27.321（与演练前一致），该快照 10 条 metric_value
```

说明：`S20260901_47` 的 `published_at` 由 RUN1 的 `22:25:47.752` 推进到 `22:32:37.973`，是 RUN2/RUN3/RUN4 三次同快照重导各自完成 ACTIVE 切换所致（每次切换都会刷新该字段）；行身份（`id=29`）、`version=1`、`active_flag=1`、行数（22/10）始终未变。

---

## 6. 失败路径实测（问题 5）

三个失败夹具均为**本次在报告目录内自造**（`build/make-failure-fixtures.ps1`），`metric-staging` 原件只读复制、事后重新哈希确认**原件 9 个文件 sha256 与演练前完全一致**（`raw/40-failure-fixtures.log` + `raw/90` 末段）。

| 用例 | 夹具与篡改内容 | 退出码 | `errorCode` | 原文错误 | 残留 ADS 行 | 快照状态 |
|---|---|---|---|---|---|---|
| A 导出目录不存在 | `exportDir=build\no-such-export-dir`（不存在），snapshotId `S20260901_47FX` | **3**（wall 3.917 s） | `MP_MANIFEST_READ` | `发布清单不存在: D:\...\build\no-such-export-dir\_export.json` | **0**（8 表全 0） | **无快照行**（连 BUILDING 都没建） |
| B 行数对不上 | `build\fx-b-missing-row`：复制原件后从 `ads_hot_product_m.jsonl` **删 1 行**（4→3），清单 `rowCount` 仍为 4，`snapshotId` 与 `hivePath` 改写为 `S20260901_47RH`、`exportFile` 指向夹具目录 | **3** | `MP_VERIFY_FAILED` | `写库前对账未通过: MP_ADS_ROWS_MATCH`；明细 `ads_hot_product_m=manifest:4/written:3` | **0**（`compensatedAdsRows=21`） | `FAILED`、`active_flag=NULL`、`version=2`、`failure_reason=MP_VERIFY_FAILED: MP_ADS_ROWS_MATCH` |
| C 非白名单列 | `build\fx-c-unknown-column`：复制原件后在第 7 张表 `ads_user_profile_m.jsonl` 首行注入 `"hacked_col":1`，清单改写为 `S20260901_47RC` | **3** | `MP_ADS_WRITE` | `导出文件第 1 行含非白名单列（...\ads_user_profile_m.jsonl）: [hacked_col]` | **0**（先写 6 张表 15 行，`compensatedAdsRows=15`） | `FAILED`、`active_flag=NULL`、`version=3`、`failure_reason=MP_ADS_WRITE_FAILED: ...` |

证据：`raw/42-fail-a-wrong-dir.log`、`raw/43-fail-b-row-mismatch.log`、`raw/44-fail-c-bad-column.log`、`raw/41-fixture-manifests.txt`；残留复核 `raw/45-residual-check.sql` → `raw/46-residual-check.txt`：

```
A/B/C 残留 ADS 行 = 0 / 0 / 0        残留 metric_value 行 = 0（三者在 metric_value 中无任何行）
ACTIVE 指针（全部失败后）= S20260901_47（ACTIVE, active_flag=1, version=1, published_at=2026-09-12 22:26:11.226）
ACTIVE 数据完整性：metric_values=10, gmv=2042.0000, paid_order_cnt=5.0000, uv=3, hot_product_rows=4
隔离库快照清单：S20260901_47 ACTIVE / S20260901_47RH FAILED / S20260901_47RC FAILED；metric_value 合计 10 行
```

补充说明（机制层面）：

- 用例 A 属于「清单门之前」失败 ⇒ 连 `createBuilding` 都没执行，所以没有快照行；这正是 `MetricPublisher.java:96-106` 的顺序（先读清单、先过门，再登记）。
- 用例 B 的 21 行、用例 C 的 15 行都**真实写进过 InnoDB**（`compensatedAdsRows` 就是删除命中数），随后被补偿 `DELETE` 清掉 ⇒ 「失败不留半成品」在本用例集成立；但这是**补偿**而不是**回滚**（见 §3.5），补偿本身失败只记 `compensateError`（`MetricPublisher.java:301`），**该分支本次未测**。
- 生产可用性结论（仅限本用例集）：**可发布失败 = 线上读数不受影响**（旧 ACTIVE 指针与数据均不变），这一点与 `MetricPublisherMySqlIT` 注释声称的性质一致，且本次是隔离库实测而非注释引用。

---

## 7. 导入期间读数是否稳定（并发可见性，实测）

方法：一条 mysql 会话连续 700 次采样（`SELECT NOW(6), ...` + `DO SLEEP(0.005)`，实测采样间隔约 14-15 ms），另起进程做同快照重导（RUN3/RUN4）。脚本与原始轨迹：`raw/70`、`raw/71`（每次快照自述 `active_flag` + 8 表行数合计 + `metric_value` 行数）、`raw/73`、`raw/74`（profile 级 ACTIVE 指针 + 8 表行数合计 + `metric_value`）。

**RUN4 轨迹（profile 级，`WHERE runtime_profile_id=1 AND active_flag=1`）关键片段：**

```
22:32:37.808144  S20260901_47  22  10      ← 重导前：正常
22:32:37.823654  NULL          22  10      ← ACTIVE 指针消失（快照被置 BUILDING）
22:32:37.884989  NULL           0  10      ← ADS 行被删空（先删后插的删除已提交）
22:32:37.899879  NULL           1  10      ← 逐表回填：1
22:32:37.916607  NULL           2  10
22:32:37.931598  NULL           7  10
22:32:37.946673  NULL          15  10
22:32:37.962705  NULL          22  10      ← 8 表回填完成
22:32:37.994327  S20260901_47  22  10      ← ACTIVE 指针恢复
```

量化（`raw/74` 共 700 样本；`raw/71` 共 1400 样本）：

| 观测量 | 实测 |
|---|---|
| ACTIVE 指针空缺时长 | `22:32:37.823654` → `22:32:37.978147`，连续 11 个样本 ≈ **170 ms**（其间 profile 1 无 ACTIVE 快照） |
| ADS 行数 = 0 的样本 | 1 个（≈15 ms） |
| ADS 行数中间态 | 1 / 2 / 7 / 15 各 1 个样本（**部分表已写、部分表未写的状态可被外部读者直接观测**） |
| `metric_value` 行数 | 700/700 样本恒为 10（`raw/71` 亦为 1400/1400），**全程未抖动** |
| 其余 689 样本 | `S20260901_47 / 22 / 10`（稳态） |

机制解释（代码证据）：`createBuilding` 先把该快照置 `BUILDING` 且 `active_flag=NULL`（`MetricPublishRepository.java:50`）→ `deleteSnapshot` 一次事务删光 8 表该快照行（`MetricAdsWriter.java:44-45`）→ 8 次独立事务逐表 INSERT（`:69-70`）→ 最后 `MySqlMetricStore.publish` 单事务写 `metric_value` 并切换 ACTIVE（`:149`）。因此「ADS 行可见性」与「ACTIVE 指针」都不具备读原子性。

**适用范围（重要，避免过度外推）**：

- 上述 170 ms 空缺是**同一快照 id 重导**（快照已 ACTIVE 又被重新发布）时测得的。生产 run 47 是**新快照**导入，`DELETE` 只按新 `snapshot_id` 命中 0 行、旧 ACTIVE 行不受影响；新快照导入时 ACTIVE 指针的空缺窗口**本次未测**（机制上旧 ACTIVE 的 `active_flag` 直到 `MySqlMetricStore.publish` 事务内才被归档，属**推断**，不是实测）。
- 对 M3 的直接含义：**按 `snapshot_id` 读取 `metric_value` 的对照实验在重导期间是稳定的**（实测 700/700 不变）；但**按 `active_flag=1` 解析「当前快照」再读 ADS 宽表**的读者，在重导窗口内会读到「无 ACTIVE」或部分表缺行的中间态。M3 若要做「本地↔集群同输入对账」，读侧务必钉住 `snapshot_id`，不要依赖 ACTIVE 指针在导入瞬间的取值。

---

## 8. 生产侧一致性核对（不是我的链路运行，是生产既有记录）

以下均为**只读查询 `analytics_meta` / `analytics_metric`** 得到的既有事实（1~5 为核对项），用于说明「我复现的对象 = 生产实际在跑的对象」：

1. **生产 run 47 的发布阶段证据**（`raw/52`）：`pipeline_stage_run` id=303、`stage_code=PUBLISH_METRIC`、`status=SUCCESS`、`records=44`、`started_at=2026-09-12 21:28:49.023`、`finished_at=21:29:27.333`；evidence 抽取：
   `metricExportDir=D:\Develop_code\GraduationProject\metric-staging\S20260901_47`、`metricPublish.ok=true`、`message=发布成功`、`snapshotId=S20260901_47`、`adsRows=22`、`metricValues=10`；内部 evidence：`manifestTotalRows=22`、`manifestSource=spark-ads`、`previousActiveSnapshotId=S20260901_43`、`activeSnapshotId=S20260901_47`、`elapsedMs=60`、`adsRowsByTable.ads_operation_overview_m=1`。
2. **校验规则口径一致**（`raw/53`）：生产 run 47 落了 15 条 `metricPublishChecks`，规则码与 severity 与我本次实测**逐条相同**（14 BLOCKING + 1 INFO，全 true）。
3. **导入器字节码一致**（`raw/56`）：运行中的胖包内 `BOOT-INF/lib/metric-analysis-0.1.0-SNAPSHOT.jar` 里 8 个类的 sha256，与当前 `metric-analysis/target/classes` **完全相同**：`MetricPublisher`、`AdsExportReader`、`MetricPublishValidator`、`MetricExportManifest`、`MetricAdsWriter`、`MySqlMetricStore`、`MetricPublishRepository`、`MetricAdsCatalog`。⇒ 我演练的是**生产同一份导入器字节码**。
4. **运行构建 vs 当前源码存在差异（必须知道）**（`raw/54`、`raw/55`）：运行进程加载的胖包 mtime `2026-09-12 17:21:07`（内层 `PipelineService.class` `17:20:54`），其中 `PipelineService` **不含** `prePublishGate` 字符串，`PIPELINE_QUALITY_FAILED` 存在；当前源码（mtime `22:06:35`）与 `target/classes`（`22:23:42`）**含** `prePublishGate`。与之一致：run 47 的 evidence 键里没有 `prePublishGate`（`raw/53` 的 `JSON_KEYS` 输出）。⇒ **F-88「发布前质量门断言」不在运行的构建里**，只在源码/新构建里；本次演练也没有覆盖该分支（见 §10）。
5. **链路真跑过**（`raw/50`）：`analytics_metric.metric_value` 里存在 11 个快照的行（`S20260901_22/23/24/25/29/30/39/41/42/43/47`，每快照 10 行），说明生产导入器已多次真实执行；`raw/57`：这些 run 用的都是 `local-dev(LOCAL)` 档案。

#### 生产自身的导入失败实例（run 38 / S20260901_38）——「导出成功 ≠ 导入成功」的实证

这是**生产既有记录**（只读查询，非本次演练写入），证据 `raw/59-prod-run38-failed-publish.txt`：

- run 38 的 8 个阶段：`WAIT_LANDING`/`INIT_SCHEMA`/`LOAD_ODS`/`BUILD_DWD`/`BUILD_DWS`/`BUILD_ADS`/`QUALITY_CHECK` 全部 SUCCESS，**第 8 阶段 `PUBLISH_METRIC` FAILED**（stage_id 248、`records=0`、`error_code=RUN_METRIC_PUBLISH_FAILED`、`2026-09-11 15:00:20.370 → 15:01:02.668`）；run 38 整单状态 `FAILED`。
- 快照行：`metric_snapshot` id=22、`snapshot_id=S20260901_38`、`status=FAILED`、`version=7`、`active_flag=NULL`、**`published_at=NULL`**，`failure_reason`（338 字符，全文见 `raw/59`）：
  `MP_ADS_WRITE_FAILED: PreparedStatementCallback; SQL [INSERT INTO \`ads_user_profile_m\` (...)]; Column 'favorite_category' cannot be null`
- **残留全为 0**：`S20260901_38` 的 `metric_value` 行数 = 0；8 张 ADS 表逐表 0/0/0/0/0/0/0/0（`raw/59` E 段）。
- **但它的导出目录照样存在**：`metric-staging\S20260901_38`（9 个可见文件 + 9 个 `.crc`，lastWrite `2026-09-11 15:01:02`，与阶段结束同秒；`raw/60` A 段）。
- 失败点与我本次的**失败用例 C 同类**：都是 `ads_user_profile_m` 这一张表、都是 `errorCode=MP_ADS_WRITE`（我为「非白名单列」，生产为「`favorite_category` 为 NULL」），都走 `markFailed` + `compensate`。

⇒ 结论（本次可声称、且有生产证据）：**「集群/本地导出成功」与「MySQL 导入成功」确实是两件事，且失败后的补偿清理在生产里也真的留下了 0 行残留**。这正是 D-142 §4 要求把两者分开登记的现实依据；同时说明 **M3 的对账口径不能以「导出目录存在」为输入**，必须以流水线阶段状态（`pipeline_stage_run.status/error_code`）与快照行状态（`metric_snapshot.status/published_at`）为准。

---

## 9. 复现与证据索引

### 9.1 复现步骤（按序）

1. `powershell -File build\inventory-artifact.ps1`（导出物清单，只读）
2. 建隔离库 + 拷结构 + 授权：见 `raw/01`、`raw/03`、`raw/06`（SQL 原文）
3. `powershell -File build\run-rehearsal.ps1 -Label RUN1 -SnapshotId S20260901_47 -ExportDir D:\Develop_code\GraduationProject\metric-staging\S20260901_47`
4. 复核：`mysql ... analytics_verify_m3_parity < raw\20-verify-sql.sql`
5. 幂等：重复第 3 步（label 改 RUN2）→ 再跑第 4 步 → 比对两次输出（本次 diff 见 `raw/32`）
6. 失败夹具：`powershell -File build\make-failure-fixtures.ps1` → 用 `fx-b-missing-row` / `fx-c-unknown-column` / 不存在的目录分别跑第 3 步
7. 残留复核：`mysql ... < raw\45-residual-check.sql`
8. 并发可见性（可选）：后台跑 `raw\70`/`raw\73` 的采样脚本，同时执行第 3 步

### 9.2 关键证据文件

| 文件 | 内容 | sha256 |
|---|---|---|
| `raw/60-artifact-inventory.txt` | 导出物清单（大小/哈希/首行/清单原文） | `3CD4277FE368DBA11C26CE27F69AC1528268CFEF44A4B50A0A4F887F3CF1C561` |
| `raw/12-run1-success-utf8.log` | RUN1 全量日志（含可复现命令行） | `F22131A1FD9E3B377A1058119235206567C34B49B5030F2AFAD3B276E8B2985C` |
| `raw/21-run1-independent-select.txt` | RUN1 独立 SELECT 复核 | `71388C8AEBE604ABD36E2F3F61C2DD653FEF8F1837C1DB76B9A0EFC8A42479F1` |
| `raw/30-run2-idempotency.log` / `raw/32-run1-vs-run2-diff.txt` | 幂等 RUN2 日志 / 前后 diff | `D9A93A11...` / `97CAD1EB...` |
| `raw/42`、`raw/43`、`raw/44` | 失败 A/B/C 日志 | 见 `raw/90-sha256-manifest.txt` |
| `raw/46-residual-check.txt` | 残留/半成品与 ACTIVE 复核 | `6B9E00EDD1016E70EC7D5E0693ED6C3D4BE6184203C73B052487D5C17506D5CA` |
| `raw/50-prod-untouched-check.txt` | 生产库未被触碰复核 | `D98D430330C4AF8B1263E602E55C7A8C8453B394F70DC809A746D9E76D11B2F8` |
| `raw/52`、`raw/53` | 生产 run 47 发布证据 / 校验规则列表 | 见 `raw/90` |
| `raw/58-export-vs-imported-snapshots.txt`、`raw/59-prod-run38-failed-publish.txt` | 生产「导出目录数 vs 实际入库快照数」对照 / run 38 导入失败实例（FAILED 快照 + 0 残留 + 导出目录仍在） | 见 `raw/90` |
| `raw/55`、`raw/56`、`raw/54`、`raw/57` | 运行构建指纹、导入器类哈希一致性、进程命令行、档案 LOCAL | 见 `raw/90` |
| `raw/71-nonatomic-poller.txt`、`raw/74-active-window-poller.txt` | 并发采样原始轨迹（1400 / 700 样本） | `13BD90CC...` / `D8E1920D...` |
| `raw/80-final-state.txt` | 交接时的隔离库状态 + 生产库未被触碰的最终复核 | 见 `raw/90` |
| `raw/90-sha256-manifest.txt` | 全部 raw/build 证据 + 导出物原件的 sha256 清单 | — |
| `build/RehearsalRunner.java`、`build/run-rehearsal.ps1`、`build/make-failure-fixtures.ps1`、`build/inventory-artifact.ps1` | 演练驱动与夹具脚本（**非生产代码**） | 见 `raw/90` |

### 9.3 清理命令（**未执行**，保留现场作证据）

```sql
-- 隔离库只属于本次演练（含 3 条快照：S20260901_47 / S20260901_47RH / S20260901_47RC）
REVOKE SELECT, INSERT, UPDATE, DELETE, CREATE, REFERENCES, INDEX, ALTER ON analytics_verify_m3_parity.* FROM 'metric_pub'@'localhost';
REVOKE SELECT ON analytics_verify_m3_parity.* FROM 'metric_read'@'localhost';
DROP DATABASE analytics_verify_m3_parity;
FLUSH PRIVILEGES;
```

（`analytics_metric` 上的既有授权未改动，无需回滚；`metric-staging\S20260901_47` 原件未改动；`build\fx-b-missing-row`、`build\fx-c-unknown-column`、`build\no-such-export-dir`、`build\jarchk` 为一次性夹具，可直接删除，但保留它们才能复核哈希。）

---

## 10. 未测与不可声称

**未测（本次没有执行、没有数据）**：

1. **集群导出物**：`runtime_profile` 只有 `local-dev(LOCAL)`（`hdfs_uri=NULL`、`spark_master=local[2]`，`raw/57`），本机不存在集群/HDFS 导出物；我未连接任何集群、未提交任何 Spark 作业、未跑 yarn/ssh 档案。⇒ 「集群档案下 mxp 导出成功」**没有任何本次证据**。
2. **HDFS 回读**：清单 `hivePath` 只被当作字符串做「快照钉住」校验（`MP_HIVE_PATH_PINNED`），我没有访问过该路径，也没有验证暂存分区内容与 JSONL 内容一致（行数一致性是导入器按清单 `rowCount` 与写入行数比对，**不是**回读 Hive）。
3. **真实生产表导入**：全部写入发生在 `analytics_verify_m3_parity`；生产 `analytics_metric` 只做了只读核对（`raw/50`）。
4. **经平台编排的导入**：未调 `POST /api/v1/pipeline-runs`，故 `PUBLISH_METRIC` 阶段的前置（Hive 正式分区发布、F-88 质量门断言、阶段重试与证据落库）**未测**；`metric-staging` 里的导出物是生产 run 47 的既有产物，不是我生成的。
5. **并发导入 / 并发发布**：只做了「单写者 + 只读采样」。两个导入器同时跑同一快照（`uk_active_profile`、ADS 主键冲突、`deleteSnapshot` 交叉删除）**未测**。
6. **补偿失败分支**：`compensate()` 自身抛异常（`compensateError`，`MetricPublisher.java:301`）**未测**，因此「补偿失败时是否残留半成品」没有数据。
7. **规模**：单快照 22 行、10 条指标值、约 1.2 s/次。大分区（数万行以上）、批量插入分批、长事务/锁等待、超时**未测**。
8. **`metric_value` 语义**：只核对了数值与 `unit/period/definition_version` 原文；`dimension_key` 的维度语义、`period` 与看板口径的对应、`buy_rate` 与 ADS `overall_buy_rate` 的业务等价性**未验证**。
9. **F-88 发布前质量门**：运行的构建里没有该代码（§8.4），当前源码里有；其运行行为（阻断/放行、evidence 落库）**未测**。
10. **旧形态产物**：`S20260901_21` 的目录形态（8 个子目录）是否可导入**未测**（代码上 `Files.isRegularFile` 会拒绝）。
11. **失败注入种类**：只覆盖「目录不存在」「行数少一行」「非白名单列」三类；清单缺表、清单列不一致、`hivePath` 未钉住、字符集/编码损坏、磁盘写满、DB 连接中断**未测**。（生产 run 38 那次失败是「列值为 NULL 触发 DB 约束」这一类，本次**未在新库中复现**，只做了历史记录只读核对，见 §8 末段。）
12. **run 38 失败现场的当次细节**：我没有也不会去重跑或改写它；`failure_reason`、阶段状态、残留计数是**只读抄录**，其失败当时的内存/日志已不可追（`landing/logs` 未读，属禁触目录）。

**不可声称（本次数据不支持）**：

- 不可声称「**导出 → MySQL 闭环已完整验证**」：本次只验证了 **LOCAL 档案 + 既有本地导出物 + 隔离库** 这一段；集群导出腿、平台编排腿、生产表腿都没有本次证据。
- 不可声称「集群导出成功」或「HDFS → MySQL 导入成功」：无对象、无测量。
- 不可声称「导入过程对看板零影响」：实测同快照重导期间 ACTIVE 指针空缺 ≈170 ms、ADS 宽表出现 0/部分行中间态（§7）；新快照导入的空缺窗口未测。
- 不可声称「失败零残留」是普适规律：生产 run 38 那一次确实残留为 0（§8 末段，只读抄录的既有事实），但覆盖面只有「`MP_ADS_WRITE` 一次 + 本次隔离库三类注入」；补偿自身失败、并发交叉、进程被杀等场景未测，不能外推。
- 不可声称「幂等 = 原子」：幂等指重复导入的最终状态一致（实测），不排除过程中的中间可见态（实测存在）。
- 不可声称「生产当前运行的就是仓库当前源码」：运行的胖包早于当前源码/构建（§8.4），且缺 F-88 代码。
- 不可声称「导入器有独立 CLI/脚本入口」：无（§3.6）；触发只能经流水线阶段，或像本报告这样在隔离库上直接驱动。
- 本报告使用的 `build/RehearsalRunner.java` 等是**报告目录内的一次性驱动**，不是生产代码、也不应被当成产品的一部分；生产代码本次**一行未改**。

