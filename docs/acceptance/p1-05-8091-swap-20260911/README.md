# P1-05 轮 · D-040 原子换血：真库应用 V17 + 8091 换新 jar（同一批）

**状态：已执行完成（2026-09-12 09:10:04 – 09:15:05，共 4 次运行，第 4 次完整绿通、退出码 0）。**
执行者=总控；授权=D-040「授权：P1-05 轮直接应用 V17」+「同批生效：应用 V17 与 8091 换新 jar 一次做完」。

> **本次执行的真实过程不是一次成功，而是"1 次落地 + 3 次被脚本自身的错误断言拦下"。** 三次 fail-stop 全部源于**本脚本的判据缺陷**（不是代码缺陷、不是环境问题、也不是迁移问题），逐条记于第 3 节（F-12）。真库最终状态与断言逐条实测结果见第 2、4、6 节。

## 1. 为什么必须同批原子

V17 让 `file_checkpoint.source_id` 变成 `NOT NULL` 且**无默认值**。旧 jar 的写入路径不是按源写入的，因此：

| 顺序 | 结果 |
|---|---|
| 先迁库、稍后换 jar | 中间窗口内**采集写入失败**：`Field 'source_id' doesn't have a default value`（读路径不受影响） |
| 先换 jar、稍后迁库 | 新 jar 读不到 `source_id` 列而失败 |
| **同批（本方案）** | 停机窗口内一次性完成，无上述中间态 |

因此本脚本把"迁库"交给**新实例启动时的 `MetaFlywayInitializer`**——即"启新 jar"本身就是"应用 V17"，两者天然同批。**run2 实测证实了这条机制**：`[4] 新实例 PID = 37500` → 6 秒后 `[4] /api/v1/health -> HTTP 200`，同一时刻 `[5] 迁移末条 = 16/17/1`。V17 是在应用启动过程中由应用自己应用的，脚本从未单独执行任何 DDL。

## 2. 最终状态（run4 于 09:15:05 实测 + 总控独立复核）

| 项 | 实测值 |
|---|---|
| 迁移末条 | `installed_rank=16 version=17 success=1`，`checksum=-555998778`（与设计值一致） |
| `file_checkpoint` | 新增 `source_id bigint NOT NULL`；`uk_ckpt` **已消失**；`uk_ckpt_source(runtime_profile_id,source_id,file_path,file_identity)` 就位；`idx_file_checkpoint_source(source_id)` |
| 新外键 | `fk_file_checkpoint_source → source_registry(id)`、`fk_ingestion_batch_source → source_registry(id)` |
| 回填 | `source_id IS NULL` 行数 = **0**（102 行断点全部归属到源） |
| 行数（换血前后**逐字一致**） | `pipeline_run=38 / ingestion_batch=39 / file_checkpoint=102 / source_registry=1 / runtime_profile=1 / metric_snapshot=8` |
| 8091 新身份 | PID **47132**，jar `sha256=6742197BD26CED91…`，mtime `2026-09-12 09:14:58`，`-Dplatform.metric.publish.export-dir` 位于 `-jar` **之前**，`WorkingDirectory=仓库根` |
| 端点活体（带会话） | `/api/v1/health` → 200；`GET /api/v1/sources` → **200**；对照 `/api/v1/__no_such_endpoint__` → **403**（两码不同 ⇒ 本次探测**有区分力**） |
| `GET /api/v1/sources` 内容 | `{"id":1,"sourceCode":"mock-mall","displayName":"参考商城（源 A）","ingestMode":"FILE","status":"ACTIVE","profileVersion":"1.0","current":true,…}` |

> 8091 在 run4 之后**保持运行**（新 jar，不随脚本退出而停止）；8090/8092 全程未被本脚本触碰。

## 3. 四次运行的真实过程与三次脚本缺陷（F-12）

| 运行 | 时刻 | 结果 | 根因（**全部在本脚本的断言里**） | 证据文件 |
|---|---|---|---|---|
| run1 | 09:10:04 | fail-stop 于 [3] | 断言写的是 `jar tf` 命中 `SourceRegistryController\|SourceRegistryServiceImpl` 且 `>=2`。**`SourceRegistryServiceImpl` 这个类从来不存在**（服务类名是 `SourceRegistryService`，且它在内嵌依赖 jar 内、不在 `BOOT-INF/classes`）⇒ 计数恒为 1 ⇒ 必然触发。此运行**未启动任何实例**，真库未被改动 | `swap-8091-run1-failstop.log` |
| run2 | 09:11:59 | **应用了 V17**，随后 fail-stop 于 [5] | `mysql -B` **先输出表头**，断言 `($nulls -join ' ').Trim() -notmatch '^0$'` 拿到的实为 `"COUNT(*)\t0"` ⇒ 把**"回填后 0 行 NULL"这一正确结果**判成失败。修法：新增 `SqlVal()` 只取数据行 | `swap-8091-run2-APPLIED-V17-RECONSTRUCTED.log`（重构件，见第 7 节） |
| run3 | 09:13:34 | fail-stop 于 [G2] | 持有者断言 `无其他持有 platform-app jar 的 java 进程` 排在 **[2] 停 8091 之前**，而 run2 起的 PID 37500 **自己就持有**该 jar ⇒ 必然误判。修法：持有者检查移到"停库之后、打包之前" | `swap-8091-run3-failstop.log` |
| run4 | 09:14:48 | **完整绿通，退出码 0** | 另修一处只有重跑才暴露的硬前提：[G1] 原写死"末条必须是 V16"，V17 一旦应用就永远无法再通过 ⇒ 改为**模式感知**（`swap`/`recheck`，两种模式判据都严格），并在汇总 JSON 记录 `runMode=recheck` | `swap-8091-run-20260912-091448.log`、`swap-summary.json` |

**连带修掉的三处证据完整性缺陷**（同属 F-12）：

1. **日志自覆盖**：脚本原把日志固定命名 `swap-8091.log` 并在启动时 `Remove-Item`，run3 因此删掉了 run2（真正应用 V17 的那次）的日志 ⇒ 改为日志名带运行时刻、永不覆盖。
2. **`pre-v17-schema.sql` 会被覆盖**：该文件是"迁移前"的唯一 DDL 证据，run4 若照原逻辑重导就会把它变成"迁移后"⇒ 改为首份永不覆盖 + 每次另存 `schema-snapshot-<时刻>.sql`。
3. **`Tee-Object` 抓不到日志**：`Write-Host` 不经过成功流管道，我的 `.verify` 控制台日志只有 52 B ⇒ 权威日志是脚本自己 `Add-Content` 写的；两个 52 B 空产物按成因改名保留（`*-TEE-FAILED-52B.log`），不删除。

**换血后新增的两条更强断言**（替代 run1 的错误计数法，可用于任何后续换血）：

- 逐字条目名断言：V17 迁移脚本、`SourceRegistryController.class`、`BOOT-INF/lib/connection-ingestion-0.1.0-SNAPSHOT.jar` 必须在 jar 内；
- **字节同一性断言**：fat jar 内嵌的 connection-ingestion jar 与刚构建的模块产物 sha256 **逐字节相同**（实测两侧均 `0C0AFF2251B64AD7…`）⇒ 证明**随包发布的 P1-05 类就是 E2 测过的那棵树**。

## 4. 迁移前后 DDL 逐行差异（快照对，非人工描述）

`pre-v17-schema.sql`（09:12:01 导出，**真库仍为 V16**）vs `schema-snapshot-20260912-091451.sql`（09:14:51 导出，**已为 V17**）。按表逐块比对，22 张表**只有 2 张**有结构差异：

```
表 file_checkpoint（5 行差异）:
    + `source_id` bigint NOT NULL COMMENT '源登记（source_registry.id，D-037）：断点归属的源，切换源不共享断点',
    + UNIQUE KEY `uk_ckpt_source` (`runtime_profile_id`,`source_id`,`file_path`,`file_identity`),
    + KEY `idx_file_checkpoint_source` (`source_id`),
    + CONSTRAINT `fk_file_checkpoint_source` FOREIGN KEY (`source_id`) REFERENCES `source_registry` (`id`)
    - UNIQUE KEY `uk_ckpt` (`runtime_profile_id`,`file_path`,`file_identity`)
表 ingestion_batch（5 行差异）:
    + `source_id` bigint DEFAULT NULL COMMENT '源登记（source_registry.id，D-037）：批次归属的源；历史行可空',
    + UNIQUE KEY `uk_batch_no` (`batch_no`),          ← 与迁移前同名同定义，仅位置移动（非变更）
    + KEY `idx_ingestion_batch_source` (`source_id`),
    + CONSTRAINT `fk_ingestion_batch_source` FOREIGN KEY (`source_id`) REFERENCES `source_registry` (`id`)
    - UNIQUE KEY `uk_batch_no` (`batch_no`)           ← 同上，位置移动
```

## 5. 唯一一处"数据可见变化"必须如实记录

两次快照之间（09:12:01 → 09:14:51）**唯一**的数据可见差异是：

```
- ) ENGINE=InnoDB AUTO_INCREMENT=160 … COMMENT='登录会话（简易 token，24h 过期）';   ← user_session
+ ) ENGINE=InnoDB AUTO_INCREMENT=161 … COMMENT='登录会话（简易 token，24h 过期）';
```

成因：**总控自己在 09:12:12 的登录探针**（`POST /api/v1/auth/login`，admin）写入了一条会话行。它**不属于**任何被断言的行数集合（第 2 节的六项行数全部未变），但不得省略——否则"两次快照仅差 V17"这句话就是假的。

## 6. 有意偏差：D-040 第 6 步的"采集写路径打通"**不在本脚本做**

D-040 把"采集写路径打通（新代码写断点带 `source_id`）"列为换血成功条件之一，但**在真库上跑一次采集会写入第 40 条 `ingestion_batch` 并推进 `file_checkpoint`，即移动 P1-01 冻结基线**——该动作属于 **P1-06 的 T2 授权范围**。因此：

- 本脚本只做**结构 + 读端点**验收（V17 生效、唯一键/外键/非空、行数不变、`/api/v1/sources*` 活体可用）；
- 写路径取证改由两处承担：**P1-05 泳道在副本库上的 E3**（断点行确带 `source_id`、切源不串）+ **P1-06 T2 真链**；
- 该偏差在执行输出与 `swap-summary.json` 的 `deviations` 字段中显式标明，**不得**在别处写成"换血已证明采集写路径可用"。

## 7. 证据文件清单（含来源标注）

| 文件 | 来源 | 说明 |
|---|---|---|
| `swap-8091.ps1` | 脚本本体 | 含 run1–run4 的全部修正；日志名带时刻、快照不覆盖、断言逐字精确 |
| `swap-8091-run1-failstop.log` | run1 原始日志 | 09:10:04–09:10:22 |
| `swap-8091-run2-APPLIED-V17-RECONSTRUCTED.log` | **重构件** | 原文件被 run3 启动时删除（缺陷已修）；内容为 run2 的 `Say` 行逐行重构，文件头有来源声明 |
| `swap-8091-run3-failstop.log` | run3 原始日志 | 09:13:34–09:13:35 |
| `swap-8091.log` | run3 日志副本 | 旧固定文件名留下的最后一份；run4 起改用带时刻名 |
| `swap-8091-run-20260912-091448.log` | run4 原始日志（**绿通**） | 09:14:48–09:15:05，完整 [G0]→[7] |
| `pre-v17-schema.sql` | run1/run2 导出 | 30096 B，**真库 V16 时**的 DDL，是"迁移前"的唯一证据 |
| `schema-snapshot-20260912-091451.sql` | run4 导出 | 30680 B，迁移后 DDL；与上一行构成快照对 |
| `swap-summary.json` | run4 | `runMode=recheck`、新旧实例身份、行数前后、`deviations`、`baselineNote` |
| `built-jar-history.txt` | run2 起自动追加 | 本轮构建过的每枚 jar 的 sha256 与时刻（run1 那条为事后补登） |
| `package.log` | run4 的 Maven 输出 | — |
| `8091-stdout.log` / `8091-stderr.log` | run4 的 `Start-Process` 重定向 | stdout = **49 行 / 6507 B**：Spring Boot banner、`Tomcat started on port 8091`、`Started AnalyticsApplication in 4.86 seconds`、`PipelineRecoveryService: R6-14 启动对账完成: pending重排=[] running判定中断=[] 孤立作业置UNKNOWN=0 errors=[]`，**每行都带 PID 47132** ⇒ 新实例自证；stderr = 51 B，仅 JVM 的 `Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8`，**无应用报错**。两文件随 8091 运行持续增长，本目录是提交时刻的快照 |
| `swap-run1-failstop-console-TEE-FAILED-52B.log` / `swap-run2-applied-v17-console-TEE-FAILED-52B.log` | 失败的捕获产物 | 仅 52 B（`Write-Host` 绕过管道）；按成因命名保留，**不当作运行日志引用** |

> `*.log` 在本仓库被 gitignore 覆盖，提交时需 `git add -f`。

## 8. 复跑方式

```powershell
pwsh -File docs/acceptance/p1-05-8091-swap-20260911/swap-8091.ps1
```

脚本按真库当前迁移状态自动选择模式并打印：末条为 V16 ⇒ `swap`（V17 将由新实例启动时应用）；末条为 V17 且 checksum 一致 ⇒ `recheck`（迁移不再执行，[5]/[6] 断言照跑）；**其他任何状态一律 fail-stop**。两种模式下都会：停 8091 → 断言无人持有 jar → 重新打包 → 启新实例 → 跑完整断言 → 写汇总 JSON。**任一断言不满足即 FAIL-STOP，且不自动回滚**；失败消息按"失败发生在启动之前/之后"分别声明真库是否可能已被迁移。
