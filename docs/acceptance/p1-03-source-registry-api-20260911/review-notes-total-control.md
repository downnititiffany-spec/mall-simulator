# P1-03 验收：总控独立复核笔记

- 作者：总控（父会话），**在泳道报告送达后**独立复核所得，不采信泳道自述。
- 时间：2026-09-11 21:00–21:0x（文件最后写入 2026-09-11 21:05:20）。
- 性质：本文件是**验收过程的复核记录**（发现 + 处置建议），与泳道自产的 README.md（DoD 逐条映射）互补。
- 采纳情况：三项发现 R1/R2/R3 的最终处置见决策记录 **D-038**。
- 说明：本文件中的"真库只读查询"结果均为总控亲自执行 mysql 只读语句所得，语句随文附上以便复现。

---
## 正文（原始笔记，随复核进程逐条写下）

## 一、已独立核验通过的部分（不采信代理自证）

1. **真库零变化（21:00 只读查询）**：`pipeline_run`=38、`metric_snapshot`=8、`runtime_profile`=1（ACTIVE `source_id`=1）、
   `source_registry`=1、`operation_audit_log`=92、`file_checkpoint`=102、`ingestion_batch`=39；最新迁移 V16、V17 未应用、
   两表无 `source_id` 列 —— 与 P1-01 冻结基线逐项相符。
2. **副本库状态与代理报告吻合**：`analytics_meta_p103` 最新 V17、`file_checkpoint.source_id` 存在、`source_registry`=4、
   ACTIVE `source_id`=4、审计 116 行（代理报告 92→116）—— 数字独立复算一致。
3. **E3 报告本身**：`raw/e3-06-verify-run5-final.txt` 62 断言 / 0 失败；断言含反向证据
   （S13b 要求 SUCCESS 增量 ≥2 以证明不是"假并发"、S10c 幂等要求审计增量为 0、真库四项不变量）。
4. **端口与进程收尾**：8093/8094 均不再监听；无遗留 `javaw` 进程；8090/8091/8092 三个常驻实例仍在。
5. **代码复核（`SourceRegistryServiceImpl` 406 行，逐行读过）**：
   - `activate` 的**校验先于写入**顺序正确（存在性 → 取 ACTIVE 行锁 → 锁内 `lockById` 重读 → 生命周期可变更 →
     画像校验 → 幂等判断 → 才写），失败不留半成品**不依赖回滚**（E2 内存替身无事务管理器，行为一致）；
   - 锁序固定（先 ACTIVE `runtime_profile` 行、后源行），且**锁内用锁定读重读被决策行**——这是并发下不产生
     假 `changed=true` 的关键，注释里点明是 E2 用例抓出来的；
   - `pause` 对"当前源"以 `SOURCE_IN_USE` fail-closed 且文案声明"源状态未改动"；
   - `DISABLED` 的判定**唯一所有者**是 `lifecycleMutable`，`activate`/`pause` 的拒绝与 `/test` 的
     `status_transition_allowed` 同源（注释明确写了不同源会重新引入"校验通过却激活失败"）；
   - `test()` 里 `applicable=false` 的项 `passed` 恒为 false（"没评估 ≠ 通过"），且每项 detail 只讲自己的事实
     （修掉了"passed=true 却说 sourceCode 不一致"的自相矛盾）；
   - 长度校验与 V16 列宽对齐（128/64/32/3），`source_code` 模式校验、`currency` ISO 4217、`ingest_mode` 仅 FILE
     且预留能力"不假装支持"；`create` 拒绝 ACTIVE（必须走 `/activate`）。

## 二、必须定性的三个问题（我的发现，代理未提出）

### P1-03-R1（中）`update` 改画像路径不需要重新校验，"ACTIVE 源必有已校验画像"不是不变量
`PUT /sources/{id}` 可改 `profile_path`/`profile_version`，**不做画像校验**。若该源正是当前激活源，
可把它改成不存在的路径；此后 ingestion 才会失败。E3 的 S7 只改了 displayName/timezone，未覆盖这一面。
- 反驳意见（我自己的）：`/activate` 之外的路径**已经**能产生"当前源画像无效"这一状态——
  E3 的 S8/S9 就是种子源（绑定为当前源、画像文件缺失）⇒ 该状态由构造即存在，且 P1-05 会在摄取侧
  fail-closed。故 `update` **没有引入新的状态种类**，严重度不由"能造出坏状态"决定。
- 处置建议：**本期不加固**，但必须**显式登记**为已知边界（谁拥有：P1-05 摄取侧 fail-closed + P2 画像真正被
  消费时收紧），禁止写成"已覆盖"。

### P1-03-R2（低）`create` 的 `source_code` 唯一性是"先查再插"，并发同名会以 500 收场
`countBySourceCode` 与 `insert` 之间有窗口；真正所有者是 V16 的唯一索引 ⇒ 竞态下后者抛
`DuplicateKeyException`（500）而非 `PARAM_INVALID`（400）。DB 是所有者这点正确，可接受；
建议在 `create` 里捕获 `DuplicateKeyException` 归一成 `PARAM_INVALID`（或登记为 P2 nit）。

### P1-03-R3（低）`update` 的 `before` 快照在锁外读取
`update` 用 `require(id)`（普通 SELECT）取 `before`，与并发 `activate` 交错时审计里的 `before` 可能偏旧。
`update` 不改变生命周期不变量，故不影响正确性；审计"改了什么"的保真度有理论缺口。登记即可。

## 三、待代理收口后才能定的两件事

1. 探针画像 `analytics-server/source-profiles/p1-03-probe-1.v1.json` / `-2.v1.json` 的**定位**：
   它们是 E3 的验收夹具（内容=设计 §4.2 的键，仅 `sourceCode` 不同），**不是**真实源画像；
   必须在证据 README 里写明定位与 P2 处置（真实源画像到位后删除或转为测试夹具）。
2. 证据 README 必须写出：真库未动（本节一的数字）、副本库 `analytics_meta_p103`/`analytics_metric_p103`
   是本次 E3 的临时库（**保留**，未清理）、8093 用后释放。

## 四、V17 预检（2026-09-11 21:0x，真库只读 + p103 副本实测）——供 P1-05 轮给真库应用 V17 复用

### 4.1 Flyway 应用后的结构结果（p103 副本，V17 于 20:56:03 由 Flyway 应用）
| 项 | 实测 | 期望 |
|---|---|---|
| `file_checkpoint` 行数 / `source_id` NULL 数 / 取值 | 102 / 0 / 全为 1 | 回填到位 ✓ |
| `ingestion_batch` 行数 / `source_id` NULL 数 / 取值 | 39 / 0 / 全为 1 | 回填到位 ✓ |
| `file_checkpoint` 索引 | `uk_ckpt_source(runtime_profile_id,source_id,file_path,file_identity)` UNIQUE、`idx_file_checkpoint_source`、PRIMARY | `uk_ckpt` **已消失** ✓ |
| `ingestion_batch` 索引 | `idx_ingestion_batch_source`、`uk_batch_no` UNIQUE、PRIMARY | ✓ |
| 外键 | 2 个（两表各一，指向 `source_registry(id)`） | ✓ |
| 可空 `source_id` 列 | 2（`runtime_profile.source_id` 与 `ingestion_batch.source_id`；`file_checkpoint.source_id` 为 NOT NULL） | ✓ 符合 D-037 |

### 4.2 真库数据侧预检（决定 `MODIFY … NOT NULL` 会不会失败）
```sql
-- 结果（2026-09-11 21:0x，全部为 0 / 1，无风险项）
SELECT COUNT(*) FROM analytics_meta.file_checkpoint c LEFT JOIN analytics_meta.runtime_profile p
  ON p.id=c.runtime_profile_id WHERE p.id IS NULL;                       -- 0（无孤儿断点）
SELECT COUNT(*) FROM analytics_meta.file_checkpoint c JOIN analytics_meta.runtime_profile p
  ON p.id=c.runtime_profile_id WHERE p.source_id IS NULL;                -- 0（回填后不会留 NULL）
SELECT COUNT(*) FROM analytics_meta.ingestion_batch b LEFT JOIN analytics_meta.runtime_profile p
  ON p.id=b.runtime_profile_id WHERE p.id IS NULL;                       -- 0
SELECT COUNT(DISTINCT runtime_profile_id) FROM analytics_meta.file_checkpoint; -- 1
SELECT COUNT(*) FROM analytics_meta.runtime_profile WHERE source_id IS NULL;   -- 0
SELECT COUNT(*) FROM analytics_meta.source_registry WHERE id=1;                -- 1（FK 目标存在）
SELECT COUNT(*) FROM (SELECT 1 FROM analytics_meta.file_checkpoint
  GROUP BY runtime_profile_id,file_path,file_identity HAVING COUNT(*)>1) t;    -- 0（新键无潜在重复组）
```
### 4.3 结论与限制
- 新唯一键比旧键**更宽松**（多一维 source_id）⇒ 应用时**不存在唯一键冲突**这一失败面。
- 回填是 **join 推导**（`V17` L38-41、L60-63），非硬编码 1；本库取值 1 是**数据**决定的。
- **限制**：以上是"预检"，不等于"已应用"。真库应用必须在 P1-05 轮**重新实测**并留原始日志；
  且应用前须先取得用户对"在真库上执行 V17"的**明确范围确认**（新增 NOT NULL 列 + 删旧唯一键 = 结构性不可逆）。
