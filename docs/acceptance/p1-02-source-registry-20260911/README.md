# P1-02 源登记迁移（analytics_meta V16）验收记录

- **任务**：M2 / P1-02（看板 §3.4）「源登记：`source_registry` 表 + `runtime_profile.source_id` + 存量回填」
- **DoD（看板原文）**：迁移首次/重复启动均成功；回滚设计不删除业务数据；测试 = 迁移脚本单测 + 真实 MySQL schema 查询
- **结论**：**实测通过**（E1 编译 / E2 模块自动化 / E3 真 MySQL 首跑+重跑+生产启动路径），状态建议 `DONE`
- **边界**：只改 `analytics-server/platform-app`（新增迁移资源 + 新增测试）；未触碰 `mall-simulator/**`、`synthetic-data-generator/**`、`spark-jobs/**`、`contract-specs/**`、`warehouse/**`、`.gitignore`
- **实施与自证说明（诚实标注）**：本轮由**总控本人**实施并自测（无独立复核人），E2/E3 均为本轮现场实测原文；若要求独立复核，请在合并门（P1-06 T2）一并复核。

---

## 1. 交付物与指纹

| 交付物 | 路径 | 关键指纹 |
| --- | --- | --- |
| 迁移脚本 | `analytics-server/platform-app/src/main/resources/db/meta/V16__source_registry.sql` | 58 行；应用后入库 checksum `-2113384091`（冻结，改动须新开 V17） |
| 迁移门禁（静态，进默认 E2） | `analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceRegistryMigrationScriptTest.java` | 6 用例 |
| 迁移真库 IT（默认不跑，需开关） | `analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceRegistryMigrationMySqlIT.java` | 5 用例；`@EnabledIfSystemProperty(named="p1.it", matches="true")` |
| 生产 fat jar（重启后运行中） | `analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar` | 33,089,738 B / 2026-09-11 19:59:25 / sha256 `851FADD7944A183576292CD8468596EEF17BDC0A36E3ABD2E8AB108E6A81F2A6`；jar 内含 `BOOT-INF/classes/db/meta/V16__source_registry.sql` |

## 2. 迁移做了什么（V16，纯加性）

1. `CREATE TABLE source_registry`：`id BIGINT AUTO_INCREMENT PK`、`source_code VARCHAR(64) UNIQUE`、`display_name VARCHAR(128)`、`ingest_mode VARCHAR(16)`、`profile_path VARCHAR(255)`、`timezone VARCHAR(64)`、`currency CHAR(3)`、`status VARCHAR(16)`、`profile_version VARCHAR(32)`、`created_at/updated_at DATETIME(3)`、`KEY idx_source_registry_status`。
2. 种子 1 行（幂等）：`mock-mall` / `参考商城（源 A）` / `FILE` / `analytics-server/source-profiles/mock-mall.v1.json` / `Asia/Shanghai` / `CNY` / `ACTIVE` / `1.0`，`WHERE NOT EXISTS` 保护。
3. `ALTER TABLE runtime_profile`：`ADD COLUMN source_id BIGINT NULL` + `ADD KEY idx_runtime_profile_source` + `ADD CONSTRAINT fk_runtime_profile_source FOREIGN KEY (source_id) REFERENCES source_registry(id)`。
4. 回填：`UPDATE runtime_profile p JOIN source_registry s ON s.source_code='mock-mall' SET p.source_id=s.id, p.updated_at=p.updated_at WHERE p.source_id IS NULL`。

设计裁决全文见《开发过程事实与决策记录》**D-034**（7 条）。三条最关键的：

- **迁移号只由总控分配**：`analytics_meta` 迁移集实测历史 **14 行**（V1–V5、V7–V15，**V6 缺号**）、最高 V15 ⇒ P1-02 取 V16；应用后 checksum 入库，**V16 与 V1–V15 同规矩：要改就新开 V17**。
- **字段取「设计 §4.1 ⊇ 实施书 §3.1」的并集**，不删任何已列字段（多源必须能表达该源时区/币种，P3-01 需要可核对的 `profile_version`）。
- **回填不伪装成人工编辑**：显式 `updated_at = updated_at`（MySQL 的 `ON UPDATE CURRENT_TIMESTAMP(3)` 只在未显式赋值时生效）⇒ 迁移后 `runtime_profile` 的版本/状态/创建时间/更新时间逐字段不变，只多一个 `source_id`。

## 3. 如何复现（命令原文）

```powershell
# E2：模块自动化（脚本门禁 6 例在默认 includes 内；*MySqlIT 不在，默认跳过）
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djdk.attach.allowAttachSelf=true'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'
$env:SPARK_DRIVER_MEMORY='512m'
mvn -o test -f analytics-server/pom.xml -pl platform-app -am '-DforkCount=0'

# E3：真 MySQL（analytics_meta 真库，账号 meta_app/meta_app_pw_2026；必须显式开开关并点名测试类）
mvn -o test -f analytics-server/pom.xml -pl platform-app -am '-DforkCount=0' `
  '-Dp1.it=true' '-Dtest=SourceRegistryMigrationMySqlIT,SourceRegistryMigrationScriptTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'

# E3：生产启动路径（clean package 后重启 8091）
mvn -o clean package -DskipTests -f analytics-server/pom.xml -pl platform-app -am
java -Dfile.encoding=UTF-8 -jar analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar
```

## 4. 实测结果（关键数字，均可回溯到证据文件）

| 检查 | 实测结果 | 证据 |
| --- | --- | --- |
| 迁移首次启动 | `执行脚本数=1，schemaVersion=16` | `verify/02-apply-run-v16-applied.log` |
| 迁移重复启动（幂等） | `执行脚本数=0`（第二次启动无任何 DDL） | `verify/03-green-all-pass.log` |
| Flyway 历史行 | `installed_rank 15 / version 16 / source registry / checksum -2113384091 / success 1 / installed_on 2026-09-11 19:56:28`，且 V16 唯一、rank 为最大 | `post-state/05-flyway-history.txt`、`verify/07-post-restart-checks.txt` |
| 新表 schema | 11 列类型/可空性/`source_code` 唯一/`id` auto_increment 逐项断言通过 | `post-state/01-source-registry-ddl.txt` + IT 断言 |
| 种子行 | 1 行：`mock-mall / FILE / ACTIVE / 1.0` | `post-state/04-source-registry-rows.txt` |
| 存量回填 | `runtime_profile` 列 24 → 25（新增项**只有** `source_id`）；`source_id IS NULL` 的行数 = **0** | `pre-state/05-runtime-profile-columns.txt`、`post-state/07-runtime-profile-columns.txt`、`post-state/09-null-source-id-count.txt` |
| 未改业务数据 | 9 张表行数 pre/post **字节级相同**（两侧 sha256 同为 `C0DB038F5EACF77F`）：`pipeline_run 38`、`pipeline_stage_run 241`、`metric_snapshot 9`、`ingestion_batch 39`、`file_checkpoint 102`、`runtime_profile 1`、`data_quality_result 379`、`quarantine_record 104`、`spark_job_run 205` | `pre-state/04-row-counts.txt`、`post-state/06-row-counts.txt` |
| 未动版本/激活/时间 | profile 行 id=1 `local-dev`：`version=3`、`status=ACTIVE`、`created_at=2026-09-07 17:15:57.615`、`updated_at=2026-09-10 16:48:17.303` 全部不变；`hive_database_prefix` 仍 `NULL`（P1-04 边界） | `pre-state/02-runtime-profile-rows.txt` vs `post-state/03-runtime-profile-rows.txt` |
| E2（六模块） | **392 用例 / 0 失败 / 0 错误 / 0 跳过**，BUILD SUCCESS（50.878 s）；platform-app 模块 58 例（含新增门禁 6 例） | `verify/04-e2-platform-app-full.log` |
| E3（IT + 门禁） | **11 用例 / 0 失败 / 0 错误**，BUILD SUCCESS | `verify/03-green-all-pass.log` |
| 反向检查（TDD 先红） | 迁移落盘前同一批用例**真红**（8 errors + 3 failures：脚本未落盘 / 表不存在 / 列不存在） | `verify/01-red-before-migration.log` |
| 生产启动路径 | 新 jar 启动 → `analytics_meta 迁移完成: 执行 0 个脚本，版本 null` + `Tomcat started on port 8091` + `Started AnalyticsApplication in 5.006 seconds`，进程 PID 16568 监听 8091 | `verify/06-production-startup.log` |
| 重启后功能 | `/api/v1/health`、`/api/v1/runtime-profiles`、`/api/v1/runtime-profiles/active` 均 **HTTP 200**；`hiveDatabasePrefix` 仍 `null` | `verify/07-post-restart-checks.txt` |
| 旧 jar 兼容（迁移后未重启） | 迁移后**未重启**的 8091（旧 jar 16:28:26）读同一张表仍 200 ⇒ 加列未破坏已部署实例的读路径 | 本文件 5.3 节 |

**关于启动日志里的「版本 null」**：`MetaFlywayInitializer` 打印的是 Flyway `MigrateResult.targetSchemaVersion`（`MetaFlywayInitializer.java:30`），本次**没有待执行脚本**时该值为 `null`，**不是**「库内版本为 null」。库内版本以真库查询为准：`flyway_schema_history` 最高行 = V16、`success=1`（见上表与 `verify/07-post-restart-checks.txt`）。

## 5. 证据清单

### 5.1 `pre-state/`（迁移前快照，2026-09-11 19:5x）

| 文件 | 大小 | sha256(前16) | 内容 |
| --- | --- | --- | --- |
| `00-meta-tables.txt` | 360 B | `B8B5D5A1B5F22211` | `analytics_meta` 表清单（**无** `source_registry`） |
| `01-runtime-profile-ddl.txt` | 2,029 B | `35BD1A6C89876C4A` | `runtime_profile` 建表 DDL（24 列，无 `source_id`） |
| `02-runtime-profile-rows.txt` | 118 B | `1AC802C987629041` | profile 行原文（id=1，v3 ACTIVE） |
| `03-flyway-history.txt` | 1,287 B | `B8FD1B33F7258487` | 迁移历史 **14 行**（V1–V5、V7–V15，**V6 缺号**），最高 V15 |
| `04-row-counts.txt` | 187 B | `C0DB038F5EACF77F` | 9 张表行数 |
| `05-runtime-profile-columns.txt` | 1,035 B | `BAB7E2CC4B2438B8` | 列清单 24 项 |
| `06-runtime-profile-constraints.txt` | 45 B | `887010F2C78181AE` | 索引/外键（无 source 相关） |
| `07-source-registry-exists.txt` | 3 B | `13BF7B3039C63BF5` | `0`（表不存在） |
| `08-source-id-column-exists.txt` | 3 B | `13BF7B3039C63BF5` | `0`（列不存在） |

### 5.2 `post-state/`（迁移后快照）

`00-meta-tables.txt`(377 B)、`01-source-registry-ddl.txt`(1,331 B)、`02-runtime-profile-ddl.txt`(2,322 B，含 `source_id`+索引+外键)、`03-runtime-profile-rows.txt`(120 B)、`04-source-registry-rows.txt`(170 B)、`05-flyway-history.txt`(1,369 B，末行 V16)、`06-row-counts.txt`(187 B，与 pre 同哈希)、`07-runtime-profile-columns.txt`(905 B，25 列)、`08-constraints.txt`(137 B)、`09-null-source-id-count.txt`(3 B = `0`)。

### 5.3 `verify/`（过程与结论日志）

| 文件 | 大小 | sha256(前16) | 说明 |
| --- | --- | --- | --- |
| `01-red-before-migration.log` | 36,228 B | `6713DDA57B5A2C14` | **真红**：迁移落盘前 8 errors + 3 failures |
| `02-apply-run-v16-applied.log` | 17,033 B | `66888D7D9A209B7C` | 中间态：迁移已作用于真库（`执行脚本数=1，schemaVersion=16`），但暴露 2 个**测试脚手架**缺陷 |
| `03-green-all-pass.log` | 13,571 B | `3A5F8E793CD6C68E` | 修脚手架后 11/11 全绿 |
| `04-e2-platform-app-full.log` | 49,160 B | `583245ADC66F6D0D` | 六模块 392 用例全绿（含迁移资源库名字面量门禁）；日志中唯一堆栈是 `DecisionControllerAuditTest` 的**预期**异常 `RuntimeException: audit db down` |
| `05-package-clean.log` | — | — | `clean package -DskipTests`（与 `scripts/build-web-and-package.ps1` 同款命令） |
| `06-production-startup.log` | — | — | 新 jar 启动原文（迁移行 + Tomcat + 启动耗时） |
| `07-post-restart-checks.txt` | — | — | 重启后 API 200 + 库内版本/种子/回填复核 |

### 5.4 首轮 GREEN 暴露的两个**测试脚手架**缺陷（迁移本身无误，如实记录）

1. 静态门禁用 `\bupdate\b` 找 `UPDATE` 语句时命中了 `CREATE TABLE` 里的 `ON UPDATE CURRENT_TIMESTAMP(3)` ⇒ 断言改为**锚定语句边界**：`(?ims)(?:^|;)\s*<kw>\b.*?;`。
2. Connector/J 把 `flyway_schema_history.success TINYINT(1)` 映射成 `Boolean`，测试却按 `Number` 取值 ⇒ `ClassCastException java.lang.Boolean cannot be cast to java.lang.Number`，改为断言 `isEqualTo(Boolean.TRUE)`。

两次修正**只改测试**，V16 脚本一字未动（checksum 至今仍为 `-2113384091`）。

## 6. 未取证（诚实清单，不得写成已验收）

1. **画像文件不存在**：`analytics-server/source-profiles/mock-mall.v1.json` 本轮**未创建**（加载器与文件形状属 **P3-01**）⇒ 种子行的 `profile_path` / `profile_version` 是**声明值**，未经加载器校验。对 P1-03 的接口约束：激活前校验画像文件存在**只对新激活生效、不追溯**已 ACTIVE 的种子行。
2. **单源单模式**：只登记 1 个源，`ingest_mode` 只实测 `FILE`；「两个源登记同一 checkpoint 路径互不推进」属 **P1-05**。
3. **回退未执行**：DROP 外键/列/表**只文档化**（见 §7），本轮未执行，也未在真库演练；执行前需按持久状态变更单独确认。
4. **源级库名空间未接线**：`source_registry` 尚未与每源 `<prefix>_<layer>` 命名空间打通（owner 在 P1-04 的 `WarehouseNamespace`，接线属 P1-06/P4）。
5. **不涉数据量档**：本任务与 55 行 / 1,000,000 行无关，未跑集群档。
6. **`contract-specs` 未加源登记 spec**：源登记结构目前只在设计 §4.1 与实施书 §3.1 里，`contract-specs/` 仍只有 canonical-event / manifest / warehouse-namespace ⇒ 是否补 `source-registry` 机器可读 schema，建议在 G0 契约门裁决（已记入 D-034 遗留）。

## 7. 回退路径（文档化，**未执行**；执行前须单独确认）

```sql
-- 仅设计，不要直接执行；执行属持久状态破坏性变更（Data Destruction Guard 需显式确认）
ALTER TABLE runtime_profile DROP FOREIGN KEY fk_runtime_profile_source;
ALTER TABLE runtime_profile DROP KEY idx_runtime_profile_source;
ALTER TABLE runtime_profile DROP COLUMN source_id;
DROP TABLE source_registry;
-- 库内 flyway_schema_history 的 V16 行需人工判定：删除或保留（保留则 Flyway 会认为已应用）
```

本轮实际可回退性证据：迁移是**纯加性**（新表 + 可空列 + 种子行 + 只填空值），迁移前后业务表行数字节级相同、profile 行版本/状态/时间未变 ⇒ 回退只需丢弃新增结构，不涉及任何业务数据重建。

## 8. 结论

P1-02 的 DoD 三项（首次/重复启动、回滚不删业务数据、单测 + 真库 schema 查询）**均已现场实测并有原文证据**；增量风险已被"加性变更 + 回填不动版本/时间 + 旧实例兼容"三重检查覆盖。剩余未取证项**不属本任务 DoD**，已逐条列明并挂到对应任务（P3-01 / P1-05 / P1-06 / G0）。
