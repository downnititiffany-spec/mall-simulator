# BATCH-G31-11 结果文档 — M3 发布语义收紧（消费台账 + FIFO 选择器 + 显式重算）

> **批次**：G31-11 ｜ **执行日**：2026-09-25 → 2026-09-26 ｜ **裁决依据**：总控 D-048 §(5) M3 语义裁定（PROJECT_STATUS「M3 语义裁定（归 G31-11 执行）」条目）
> **计划**：`docs/verification/batches/BATCH-G31-11-M3-PUBLISH-SEMANTICS-PLAN.md`（§0 D-049a–h 语义定义、§1 判据 1–8、§4 P0–P4 执行序列）
> **证据根**：
> - P1+P2 隔离栈：`target/v25-it/g3111iso_20260926_073200/`（driver-state.json 为权威终态，`resumedAt 2026-09-26T08:09:53`、`resumeMode "ResumeLeg7 (legs1-6 inherited; BF-row ledger semantics per D-049j)"`、`finishedAt 2026-09-26T08:13:57`、`outcome: PASS`）
> - P3 正式栈：`target/v25-it/g3110_20260925_191753/restart-result-g3111.json` + `evidence-g3111/`（noop-db-evidence.json + 16 legs + 20 sql-* 文件），attempt 4 `outcome=PASS`、`finalizedAt 2026-09-26T09:36:08`
> - P0 全 reactor 日志与 run-tests 记录（基线 1150→1165→1169）
> **jar SHA256**：platform-app `69990aadb898645d09335f24f5ca50ec166b7c25fa369917cfcb54effdc4ddb7`、spark-jobs `71c0fc88b1c093df3e2e828d0c00b827a5bd51bca1d0ca0ab4a378e37bc00e57`（隔离栈 driver-state 头部与正式栈一致）
> **测试基线**：G31-09 收口态 1150 → G31-11 首轮全 reactor **1165**（+15 = M3 五场景等，warehouse-pipeline 185→195）→ **D-049x** 追加 4 用例 **1169**（195→199）→ 终态 **F=0 E=0 S=2 @ 1169**；`scripts/run-tests.ps1` 基线 `'analytics-server' = 1169` + D-049x 变更记录（927–929 行）
> **结论**：**判据 1–8 全 PASS**（判据 5 的发布型腿按计划映射至隔离栈承担；发布型腿不在正式栈重跑——见 §2.5 映射表）。请求总控签收；签收权归总控，本批不自宣完整验收。

---

## §1 验收→证据映射

| # | 计划判据（§1） | 证据 | 结果 |
|---|---|---|---|
| 1 | 场景1 无新输入不发布（no-op SUCCESS、ACTIVE 不变、无新快照、`input_batch_id` NULL 如实） | 隔离栈 run6-noop（runId 6）+ 正式栈 run 27（§2.1） | ✅ |
| 2 | 场景2 有新输入能发布 | 隔离栈 run1（批1→S20260901_1）、run2（批2→S20260901_2）、run5（批4→S20260901_5）（§2.2） | ✅ |
| 3 | 场景3 失败重试仍有效（FAILED 不写消费行、retry 钉原批、修复后重跑 SUCCESS、消费行落原批） | 隔离栈 run4 三次尝试链 + run3-recalc 显式重算（§2.3） | ✅ |
| 4 | 场景4 连续两个待处理批次不遗漏（FIFO 逐批消费） | 隔离栈 run1/run2 连续消费批 1、2 + run5 消费批 4 后台账恰 4 行（批 1–4）零跳批（§2.4） | ✅ |
| 5 | 受影响终验 = 正式栈 no-op + G31-07 非发布型腿；发布型腿→隔离栈映射表 | P3 attempt 4（§3）+ §2.5 映射表 | ✅ |
| 6 | 措辞红线：全部结论限于 WSL 单节点环境；终验登录与 AI 探针产生审计记录、非「完全只读」 | 全文措辞 + §8 | ✅ |
| 7 | 全 reactor 测试 F=0 E=0 S≤2 | 1169 通过、F=0 E=0 S=2（P0） | ✅ |
| 8 | 红线：3306 永久冻结零接触；`V25_IT_*` 零落盘；3307 root 仅经 credref/WSL env 通道；push 授权已用尽仅本地提交；`*.bak-*`（含轮换前明文凭据）永不提交 | P3 F2b 零 `:3306` 日志扫描 + §7/§8 | ✅ |

---

## §2 四场景逐条对答（隔离栈 g3111iso_20260926_073200）

隔离栈事实基础：per-run 独立库对 `g3111iso_20260926_073200_analytics_meta` / `_analytics_metric`（it-prepare 隔离预备 62/62），平台 wrapperPid 65560、sourceId 1、landingUri `file://…/g3111iso_20260926_073200/landing`，双 jar SHA 见头部。以下引号内容均为 driver-state.json / 平台 API 原文。

### 2.1 场景1 无新输入不发布 ✅

- 隔离栈 **run6-noop**（幂等键 `g3111iso-run6-081351`，runId 6）：`RUN|6|SUCCESS|NULL|NULL|-|1|-`——SUCCESS 且 `input_batch_id`/`target_snapshot_id` 双 NULL（**no-op 如实落库，不伪造批次归属**）。
- waitLanding 证据原文：`{"noNewInput":true,"reason":"ALREADY_CONSUMED","batchId":4,"consumedByRunId":5,"readyButConsumedCount":4}`——READY 清单非空但全部已被消费（批 1–4），FIFO 无未消费批可选 → no-op SUCCESS（**非 RUN_EMPTY_LANDING fail-closed**；「READY 清单完全不存在」仍走 `RUN_EMPTY_LANDING`，两种"无输入"语义分裂已在计划 §0(c) 登记并在单测钉住）。
- `SNAPCNT|5`（快照 5→5 零新增）；ACTIVE 保持 `SNAP|S20260901_5|ACTIVE`；消费台账保持恰 4 行零新增。
- **指纹不变证明**：run6 前后两次 overview 指纹 `fingerprintBefore` 与 `fingerprintAfter` 除 `traceId` 外逐字段全等（S20260901_5：pv 7、uv 3、dau 3、fav_cnt 2、cart_add_cnt 3、paid_order_cnt 6、gmv 3139.0、net_sale 2590.0、avg_order_value 523.17、refund_rate 0.5、full_refund_rate 0.1667、repeat_rate 0.6667、buy_rate 1.0、cart_rate 0.6667）。
- 正式栈复证 **run 27**（幂等键 `g3111-formal-noop-2`）：SUCCESS/ALREADY_CONSUMED，快照 11→11、台账 2→2 零新增，ACTIVE 唯一 `S20260901_23` 不迁移（§3）。

### 2.2 场景2 有新输入能发布 ✅

- 批1 落地（`ing-20260926073044-9a1b9dd4`，50 records/0 隔离/0 错误/1 文件/17100 bytes，manifest READY）→ **run1**（`g3111iso-run1-073045`）SUCCESS：`RUN|1|SUCCESS|1|S20260901_1|-|1|-`，ACTIVE=S20260901_1，台账 `CONS|1|1|1|S20260901_1|1|0|-|PIPELINE`；指纹 = G31-04 S1 同源基线（pv 7、gmv 2042.0、avg_order_value 408.4 等 14 项）。
- 批2 落地（`ing-20260926073431-7bb5d6bd`，50 records）→ **run2**（`g3111iso-run2-073431`）SUCCESS：`RUN|2|SUCCESS|2|S20260901_2|-|1|-`，ACTIVE=S20260901_2，`CONS|2|2|2|S20260901_2|1|0|-|PIPELINE`。
- 批4 落地 → **run5**（`g3111iso-run5-081007`）SUCCESS：`RUN|5|SUCCESS|4|S20260901_5|-|1|-`。
- 台账仅在 PUBLISH_METRIC ok 后写入（D-049a）；FAILED run（run4 两次尝试）**零台账写入**（§2.3）。

### 2.3 场景3 失败重试仍有效 ✅

**质量门必败批**：批3 落地（`ing-20260926074207-b015fa2c`，2 records，AMOUNT_RECONCILE 毒丸：`order_paid` amount 1197.00 vs 明细合计 1097.00）。

- **run4 尝试1**（`g3111iso-run4-074207`）FAILED：`DQ|AMOUNT_RECONCILE|1|1|0|LANDING`（1 检/1 错/0 过）+ `RUN|4|FAILED|3|S20260901_4|PIPELINE_QUALITY_FAILED|1|-`；**ACTIVE 不变** `SNAP|S20260901_3|ACTIVE`；**台账不变**（仍 2 行，无批3 行——FAILED 不写消费行，D-049d）。
- **run4 尝试2 = retry 钉批证明（D-049b/D-049i）**：retry-from-stage 后仍 FAILED `RUN|4|FAILED|3|S20260901_4|PIPELINE_QUALITY_FAILED|2|G31-11-D-049b-retry-must-repin-BF`——waitLanding 证据 `batchId=3`（**钉住原败批，未被重扫带走**），`recalcReason "G31-11-D-049b-retry-must-repin-BF"` 落 `pipeline_run.recalc_reason`（D-049i）；台账仍 2 行、ACTIVE 仍 S20260901_3。
- **修复**：同 `event_id g3111-evt-bf-3001-pay` amount 1197.00→1097.00 重投递（`manifestUntouched:true`；odl new-wins 语义以新行替换坏行；manifest checksum 下游无复验点，计划 §0 已知边界）。
- **run4 尝试3（resumed:true）** SUCCESS：`DQ|AMOUNT_RECONCILE|1|0|1`（0 错误）+ `RUN|4|SUCCESS|3|S20260901_4|-|3|G31-11-repair-BF-then-retry`，ACTIVE=S20260901_4，台账 `CONS|3|4|4|S20260901_4|1|1|G31-11-repair-BF-then-retry|PIPELINE`——**消费行落到原批 3**，`first_consumed_by_run_id=4`。
- **显式重算入口（D-049e）**：`POST /api/v1/admin/pipeline-runs/recalculate` body `{runtimeProfileId, batchId:1, operator:"g3111", reason:"G31-11 判据3：显式重算复核 B1（D-049e）"}` → runId 3 SUCCESS：`RUN|3|SUCCESS|1|S20260901_3|-|1|G31-11 判据3：显式重算复核 B1（D-049e）`，ACTIVE=S20260901_3，台账 `CONS|1|3|1|S20260901_3|2|1|G31-11 判据3：…|PIPELINE`（**publish_count 1→2、recalc_count 0→1、first_consumed_by_run_id 保持 1**；D-049j 口径：recalc_count 计「带理由的显式再发布」= /recalculate 与 retry-from-stage 均计入——run4 的 1 也来自 retry 理由）。
  - 负例①：batchId 999 → **400 PARAM_INVALID**「批次 999 在源 1 下没有任何流水线 run（无既成发布可复核），拒绝重算」。
  - 负例②：reason 空白 → **400 PARAM_INVALID**「显式重算必须携带理由（reason 必填，D-049e）」。

### 2.4 场景4 连续两个待处理批次不遗漏 ✅

- 批1、批2 先后落地后 **run2 的 FIFO 选择器取最旧未消费批（批2）**，而非重选已消费批1 或跳批：`CONS|2|2|2|S20260901_2|…`。
- 批3（经 §2.3 修复后由 run4 消费）、批4（`ing-20260926081007-b4f720d0`，50 records）落地 → run5 消费批4 后**台账恰 4 行、批号 1/2/3/4 连续、零跳批零遗漏**：
  `CONS|1|3|1|S20260901_3|2|1|…` ／ `CONS|2|2|2|S20260901_2|1|0|-|PIPELINE` ／ `CONS|3|4|4|S20260901_4|1|1|…` ／ `CONS|4|5|5|S20260901_5|1|0|-|PIPELINE`。
- 随后 run6-noop 的 `readyButConsumedCount:4` 反证所有 READY 清单均已消费、无遗漏残留。

### 2.5 发布型腿→隔离栈映射表（判据5）

G31-10 P4 的发布型腿（M1/M2/M3/F1）按计划不在正式栈重跑，其等价验证由本批隔离栈承担：

| G31-10 发布型腿 | G31-11 隔离栈等价证据 | 语义差异说明 |
|---|---|---|
| M1 基线发布（S_A） | run1（批1→S20260901_1，指纹==G31-04 S1 基线） | 相同 |
| M2 增量发布（S_B） | run2（批2→S20260901_2）+ run5（批4→S20260901_5） | 相同；新增台账行 |
| M3 同批重放再发布（S_C，值不变新快照——**旧语义**） | run6-noop（ALREADY_CONSUMED、零新快照——**新语义**）+ 正式栈 run 27 | **本批收紧点**：重放/已消费批不再发布；G31-10 结果 §5-1 的缺口由此闭合 |
| F1 质量门阻断 | run4 尝试1/2 FAILED + 尝试3 SUCCESS（§2.3） | 相同，且补齐消费台账维度 |

正式栈仅保留：no-op 腿（run 27）+ G31-07 非发布型腿重跑（§3）。

---

## §3 P3 正式栈受影响终验（g3111-formal-restart attempt 4，PASS）

对正式库 `analytics_meta`（3307，V32 时代锚点 ACTIVE=S20260901_23）执行；证据 `target/v25-it/g3110_20260925_191753/restart-result-g3111.json`、`evidence-g3111/`。

1. **V33 迁移**：flyway 32 行、V33 顶部 `success=1`、checksum `-75448211`。**迁移缺陷与修复见 §5 偏差-3**（error 1567 + flyway repair + 手工去重回填；**G31-12 更正：success=1 系受控手工 SQL 直接置位，并非 `flyway repair` CLI 所为，见 §9 补记①**）。
2. **回填（D-049f）**：`INSERT…SELECT` 从 SUCCESS 且 `input_batch_id`/`source_id` 双非空 run 回填，**恰 2 行 == EXPECTBF**：run 22→批21→S20260901_21、run 23→批24→S20260901_23，均 `created_via='BACKFILL_V33'`——历史已消费批若无台账将重发布并移动终验锚点，回填即防线。预检「SUCCESS 且 input_batch_id 非空但 source_id 为 NULL」期望 0、实测 0。
3. **正式栈 no-op 腿**：run 27（幂等键 `g3111-formal-noop-2`）SUCCESS/ALREADY_CONSUMED；快照 11→11、台账 2→2 零新增；**ACTIVE 唯一 S20260901_23 不迁移**（终验锚点保持）。幂等键注记：attempt 3 已消费 `g3111-formal-noop-1`，attempt 4 换用 noop-2（操作记录非缺陷）。
4. **G31-07 非发布型腿重跑**：Phase E 指纹 **14/14 == P6 oracle** `{pv=8.0; uv=3.0; dau=3.0; fav_cnt=3.0; cart_add_cnt=3.0; paid_order_cnt=5.0; gmv=2042.0; net_sale=1493.0; avg_order_value=408.4; refund_rate=0.6; full_refund_rate=0.2; repeat_rate=0.3333; buy_rate=1.0; cart_rate=0.6667}`（tol 0.0005）；**F2b 正式平台日志零 `:3306`**；AI 探针 stub-local/EXECUTED；平台 LEFT RUNNING 常驻（identity pid 44284、marker `g3111-formal-restart`）。
5. **陈旧 READY manifest 隔离终态**：{23,25,26,27}（SHA 留证：23=`e5f3aec037b2…`、25=`8ca9ed30d725…`、26=`7589661b1ac7…`、27=`539bd6eefd9b…`）；24.json 恢复 READY（SHA `6dda162f595753209a74891949b5b16713a9776d387e015be7ace332d432029d`，属回填已消费批 24，参与 readyButConsumed 计数）。处置依据：批 23/25/26/27 无消费行（其消费 run 属 pre-S3-36 时代未记录 source_id/input_batch_id，见 §5 偏差-5 BAD 组成），若保持 READY 会被 D-049b FIFO 重选重发布并移动锚点——**隔离是该 no-op 判据成立的前提**。过程中两轮误隔离事故见 §5 偏差-6。

---

## §4 D-049x 登记：发布前质量门陈旧行缺陷（G31-11 内发现并修复）

- **缺陷**：`DataQualityGate` 发布前判定原读 run 的**全量历史** `data_quality_result` 行——重试链保留旧行作证据，导致修复后重试被陈旧 `passed=0` 行**永久拦截**。实测触发于隔离栈 run4 重试链：同 (规则码=AMOUNT_RECONCILE，层=LANDING) 三条并存 id 79（fail）/83（fail）/87（pass）。
- **修复**：只判每 (规则码， 层) **最新一行**（max(id)）为裁决行；历史行保留但不再阻断。落点 `DataQualityGate.java:53,174`；变更记录 `scripts/run-tests.ps1:927-929`、`scripts/g3111-formal-restart.ps1:235,474`。
- **测试**：`DataQualityGateTest` +4（基线 1165→1169）：`repairedRetrySupersedesStaleFailureRows`（79/83 fail + 87 pass → 放行，即 run4 实测形态）、`latestFailureStillBlocksAfterEarlierPass`（最新行 fail 仍拦截）、`crossLayerLatestVerdictsAreJudgedIndependently`（LANDING/ADS_STAGING 各自最新行独立裁决）、`unregisteredCodeStillStopsPublishEvenWhenLatestRowPassed`（未登记码 fail-closed）。

---

## §5 偏差登记（诚实披露）

1. **D-049x 陈旧行缺陷**（§4）：G31-11 执行中由隔离栈 run4 重试链暴露，同批修复+测试；属「执行暴露的存量缺陷修复」非计划内改动，若不修则场景3 重试链在修复后仍永久 FAILED，判据3 无法闭合。
2. **测试基线两段增长**：1150→1165（M3 五场景等 +15）→1169（D-049x +4）；均为本批新增用例，非 flake；`run-tests.ps1` 基线同步 1169 属 MIXED 文件（§7），其 G31-08 时代遗留注释翻新无法与本次基线提升分离，故**不入提交组**、随工作树保留并在此登记。
3. **V33 迁移缺陷（error 1567）**：`ON DUPLICATE KEY UPDATE id = pipeline_batch_consumption.id`（V33 第 61 行）——MySQL 不允许对自增主键自身做该更新，flyway 首跑报 1567。**修复**：`flyway repair` 置 success=1（checksum `-75448211` 原样保留）+ 手工去重回填 INSERT…SELECT 达成 EXPECTBF=2（**G31-12 更正：实际修复 = 手工 SQL `UPDATE flyway_schema_history SET success=1` 直接置位 + 显式 VALUES 逐行回填，非 `flyway repair` CLI、`INSERT…SELECT` 语句现场从未成功执行（其同语句 UK 冲突 + 自指更新正是 1567 根因）；原句按当时报告原貌保留，见 §9 补记①**）；**jar 未重建**（纯 SQL 数据层修复，代码零改动）；**V33 源文件未来修正 = 去重构造 + checksum 重锚**，登记为后续批次义务（不改已发布迁移内容，仅修写法，需随下个迁移窗口重锚）。
4. **驱动脚本 PowerShell 四陷阱**（均已修复留痕，属驱动层非产品层）：① 单元素数组经函数返回被展开 → `return ,@(…)`；② 管道上下文 `,@()` 整组作单 `$_` 传递 → `(Sql-Lines …) | ForEach-Object` 括号化；③ PowerShell 变量名大小写不敏感致 `$h` 覆写 `$H` → 改名 `$healthResp`；④ `-like '*.jar'` 尾锚永不匹配 → `'*platform-app*'`。
5. **形状巡检 BAD=8**：pre-S3-36 时代 `source_id` 未记录的历史 SUCCESS run（4,12,15,16,17,19,20）∪ no-op run（26/27，按 D-049a 设计 `input_batch_id` 留 NULL）并集计数 8；`restart-result` 内 `deviations.badSeven` 为编写时 7 项陈旧枚举，实际 8（差 1 = 首个 no-op run）。**每执行一次 no-op 该计数按设计 +1**；no-op run 无批次可关联、对再消费零风险，属形状说明非缺陷（对应 mini-gate 硬门项 Fail 15 登记）。其中 runs 4,12,15,16,17,19,20 的批次因消费 run 无 source_id 双证不参与回填（防无源归属数据入台账），其 manifest 残留以隔离处置（§3-5）。
6. **陈旧 manifest 两轮误隔离事故**：驱动脚本 PowerShell 管道 `,@()` 展平陷阱致判断集错位，24.json 一度被误隔离，两轮内发现并恢复（SHA `6dda162f…` 留证），quarantine README 事故补记留痕。
7. **幂等键 noop-1→noop-2**：attempt 3 已消费 noop-1，attempt 4 换键重跑（幂等键语义正常工作的操作记录）。
8. **外观项**：`consBatches` 输出打印 "22,24,24"（集合语义无害，显示层重复）；不修复不影响任何判据。

---

## §6 运维查询 SQL（台账/run/快照巡检，原文可复跑）

```sql
-- 消费台账（D-049a）
SELECT CONCAT('CONS|', batch_id, '|', consumed_by_run_id, '|', first_consumed_by_run_id, '|',
              IFNULL(target_snapshot_id,'-'), '|', publish_count, '|', recalc_count, '|',
              IFNULL(SUBSTRING(last_recalc_reason,1,40),'-'), '|', created_via)
FROM pipeline_batch_consumption ORDER BY batch_id;

-- pipeline_run 形状（input_batch_id/target_snapshot_id/error_code/attempt_no/recalc_reason）
SELECT CONCAT('RUN|', id, '|', status, '|', IFNULL(input_batch_id,'NULL'), '|',
              IFNULL(target_snapshot_id,'NULL'), '|', IFNULL(error_code,'-'), '|',
              attempt_no, '|', IFNULL(SUBSTRING(recalc_reason,1,60),'-'))
FROM pipeline_run ORDER BY id;

-- ACTIVE 唯一性
SELECT CONCAT('SNAP|', snapshot_id, '|', status) FROM analytics_snapshot WHERE status='ACTIVE';

-- 快照计数（no-op 零新增判据）
SELECT CONCAT('SNAPCNT|', COUNT(*)) FROM analytics_snapshot;

-- 质量门结果（D-049x 后以每 (rule_code, layer) 最新行裁决）
SELECT CONCAT('DQ|', rule_code, '|', check_count, '|', error_count, '|', passed, '|', layer)
FROM data_quality_result WHERE run_id = <runId>;
```

（正式栈/隔离栈各自库对执行；`recalculate` 入口：`POST /api/v1/admin/pipeline-runs/recalculate`，body `{runtimeProfileId, batchId, operator, reason}`，reason 必填。）

---

## §7 触碰文件归属清单（P4.3 提交分组依据）

**提交组①（G31-11 代码+测试+迁移，纯度逐文件核验）**：

| 文件 | 性质 | 内容 |
|---|---|---|
| `platform-app/src/main/resources/db/meta/V33__pipeline_batch_consumption.sql` | 新增 | D-049a 台账表（V33） |
| `warehouse-pipeline/…/pipeline/entity/PipelineBatchConsumption.java` | 新增 | 台账实体 |
| `warehouse-pipeline/…/pipeline/mapper/PipelineBatchConsumptionMapper.java` | 新增 | 台账 mapper |
| `platform-app/src/test/java/com/graduation/analytics/migration/PipelineBatchConsumptionMigrationScriptTest.java` | 新增 | 迁移静态门禁 |
| `warehouse-pipeline/…/pipeline/entity/PipelineRun.java` | 修改（+6） | 仅 `recalc_reason` 字段（D-049i） |
| `platform-app/…/controller/PipelineAdminController.java` | 修改（+25） | 仅 `/recalculate` 端点（D-049e） |
| `warehouse-pipeline/…/pipeline/LandingManifestSelector.java` | 修改（111 行） | FIFO+钉批选择器（D-049b） |
| `warehouse-pipeline/…/pipeline/PipelineService.java` | 修改（13 hunks / +317） | 全部 D-049 标记 |
| `warehouse-pipeline/…/test/…/LandingManifestSelectorTest.java` | 修改 | 全部 D-049b |
| `warehouse-pipeline/…/test/…/PipelineServiceTest.java` | 修改 | 全部 G31-11 M3 五场景 |
| `warehouse-pipeline/…/pipeline/DataQualityGate.java` | 修改 | 仅 D-049x hunks |
| `warehouse-pipeline/…/test/…/DataQualityGateTest.java` | 修改（+76） | 全部 D-049x 四用例+夹具（本批逐行核验纯） |

**排除（MIXED，不入提交组、随工作树保留）**：`scripts/run-tests.ps1`（G31-08 时代注释翻新 + `$BaselineSpark` 322→329 遗留与本次 1169 基线提升无法分离）。

**不触碰（G31-10/D-048 在途文件，保持未提交原状）**：ExplanationService、IngestionService、LocalFileIngestor、LandingInputScanner、LandingLayout、HdfsLandingStorage、LandingStorage、LocalLandingStorage、LocalProcessSparkSubmitter、MetricPublishValidator、MetricPublisher 及其测试。

**永不提交**：`*.bak-*` 备份（含轮换前明文凭据的文档备份与 `scripts/run-tests.ps1.bak-20260926-g3111`）、根目录游离 `PROJECT_STATUS.md`、`.zcode/`、`.zcodeignore`、`target/`。

**平台 UI 零改动**（D-049h）：本批未触碰任何前端文件。

**提交组②（G31-11 文档）**：本结果文档 + `docs/decisions/DECISION_LOG.md`（D-049 追加）+ `docs/verification/CURRENT_BATCH.md` + `docs/PROJECT_STATUS.md`（编辑前备份 `*.bak-20260926-g3111` 三份在案）。

---

## §8 边界与措辞红线（判据6/8）

- 全部"通过"结论**仅限当前 WSL 单节点环境**；不证明远程集群/共享 Hive Metastore/YARN/真实 LLM（G31-06 仍 BLOCKED，未伪造）。
- 终验登录与 AI 探针按设计产生审计记录，**非「完全只读」**。
- **3306 永久冻结零接触**（P3 F2b 日志零 `:3306` 复证）；**V32/V33 永不在 3306 运行**。
- 3307 root 口令仅经 `credref-mysql3307-root.properties` / WSLENV(`MYSQL_PWD`) 通道，零落盘/零 argv/零 git/零文档。
- `V25_IT_*` 口令零落盘（隔离栈单进程 in-process → credref → `IT_GUARD_*`）。
- **push 授权已用尽 → 仅本地提交**，按 §7 分组提交、绝不 bulk-tree。
- 归档义务按 D-048 执行：`v3-archive/g3111/`（源码快照 + 脱敏证据 + git bundle + SHA256 清单），DB 备份受控保存不公开上传。
- 已知窗口（D-049g，计划 §0 预登记）：发布 ok 后、台账写入前的崩溃窗口 → 批次未被消费 → 重跑将重发布同值快照（D-045 去重兜底、ACTIVE 前移一槽）——登记为**已知窗口非缺陷**。
- 语义分裂登记（计划 §0(c)）：「READY 清单存在但全部已消费」= no-op SUCCESS；「READY 清单完全不存在」= `RUN_EMPTY_LANDING` fail-closed。二者判据不同、不可混同。

---

## §9 总控复核补记（2026-09-26，G31-12 批次）

总控 G31-11 复核结论：**主要功能已实现，复核发现问题待修，最终签收暂缓**（已完成能力与 333/333 SHA256 归档校验接收；G31-10 已解除的两项拦截保持解除）。本节为本文档两处不准确表述的**更正补记**——正文按原貌保留、不就地改写（历史报告完整性），歧义以本节为准。

① **「flyway repair 已修复迁移」表述不成立**（对应正文 §3-1、§5-3）。实际补偿动作（证据原件 `target/v25-it/g3110_20260925_191753/evidence-g3111/sql-repair2-backfill.sql`，按原貌保留）为**受控手工 SQL**，两步：
- `UPDATE flyway_schema_history SET success = 1 WHERE installed_rank = 32 AND version = '33' AND success = 0;`——success=1 系**手工 UPDATE 直接置位**，`flyway repair` CLI 根本未使用；checksum `-75448211` 原样保留。
- 回填为**显式 VALUES 逐行** INSERT（批 22：consumed_by=21/first=21/S20260901_21/publish=1；批 24：consumed_by=23/first=22/S20260901_23/publish_count=2），非正文 §5-3 所述「INSERT…SELECT」——V33 原文的 INSERT…SELECT 同语句 UK 冲突 + `ON DUPLICATE KEY UPDATE id=…` 自指赋值正是 1567 根因，该语句现场从未成功执行。
- 措辞后果：「当前库恢复可用」成立；「从已有 V32 数据正常升级」在当时**未闭合**——已由 G31-12 闭合（见 ③）。

② **场景3「修复后重试成功」的手段限定**（对应正文 §2 场景3）。g3111 驱动第 551 行系**直接改写 accepted 文件**（manifest 不变）——仅证明「人工订正数据后计算可恢复」，**不构成正常数据修复流程的验收证据**。原实验按原貌保留、结论限于此；正常流程证据由 G31-12 腿④补验：输入文件与 manifest 校验和全程不变、仅解除临时环境故障（job jar 移走）后**原 run 原地重试成功**（`target/v25-it/g3112iso_20260926_192745/` evidence/leg4-*）。

③ **G31-12 已闭合项**（结果文档 `docs/verification/results/BATCH-G31-12-MASTER-REVIEW-FIXES-RESULT.md`，决策 **D-050**）：
- **V33 可重复升级**：重写为去重构造（`INSERT IGNORE` + `GROUP BY` 聚合），原 ODKU 语句保留为注释；权威 checksum **`535846146`**（原 `-75448211` 记录于文件头）；小型历史夹具升级 IT 3/3（含「同批多次成功发布」批 24 形态：r1/r2 聚合 publish_count=2）；正式库 live 重锚 success=1 @ `535846146`（credref 通道）。
- **重算目标批次校验旁路修复**（`PipelineService`：先冻结原请求批次，选中与请求相等才写回 run 行；不等时保持原值、由 WAIT_LANDING 按冻结值 fail-closed `RUN_RECALC_BATCH_UNAVAILABLE`）+ 负例：目标批清单缺失、选择器回落他批 → 拒绝执行、他批不被消费、ACTIVE 不变、run 行 `input_batch_id` 保持原请求值（腿⑤）。
- **FIFO「两批同时待处理」真实链路证据**（腿③）：先完成 A、B 两批摄取并确认台账为空、均未消费，再连续两次流水线 → A→B 顺序、零遗漏（旧「取最新」在该序列下必然失败，方可证明 FIFO）。
- **正常修复流程证据**（腿④）：见 ②。
