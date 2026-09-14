# 决策清单：必须上升用户的 vs 由本泳道按已有设计自行决定的

- 依据（用户裁决 ①）：只有四类才上升用户 —— **(a) 会导致架构分叉**、**(b) 会改变数据口径**、**(c) 会改变论文内容/范围**、**(d) 会产生破坏性操作**。其余普通实现细节**按仓库已有设计自行判定**并给出「建议取值＋依据」，**不再标注为待裁决**。
- 上一版 V25-R01 曾把 Q1–Q10 十条并列交用户；**已按此判据重分类**：Q1–Q10 全部**不命中四类**，已由总控裁决（§2 逐条留档），**不再占用用户**。
- 本文件只列**当前仍未决**的事项。

---

## 1. 必须用户裁决（**2 条**，逐条命中四类之一）

> **【13:0x 更新】原第 3 条 E-03 已由总控跨库普查（12:4x，root 只读）消解，不再上升用户** —— 见 §1.3 留档（原始观测保留，结论改写为「已消解」）。当前仍在上升用户的只有 E-01 与 E-02。

### E-01 论文的漏斗口径是否必须是「日 × 分类 × 渠道」？— 命中 (c) 论文内容/范围
- 现状（实测）：`DwsSql.scala:41-45` 把漏斗维度硬编码为 `-1 AS category_id, 'all' AS channel` ⇒ 落库结果**退化为单行「日」**（`MATRIX.md` §7-3）。
- 分叉点：论文若画「分类/渠道漏斗图」，则必须先修 DWS 维度（属 §7.2 工作量）；论文若接受「日粒度漏斗」，则当前实现与论文一致，只需在论文中如实写明口径。
- **一个问题**：论文/答辩是否要求按**分类与渠道**下钻的漏斗（而非仅按日）？

### E-02 论文是否包含「AI 辅助建仓」章节？— 命中 (c) 论文内容/范围
- 现状（实测）：`WarehouseBlueprint`/`BlueprintValidator`/`SourceProfileDraft`/`ModelingWizard`/`SandboxExecutor` **全部 0 命中**；`MATRIX.md` §13-4～§13-24 的 AIW-001～020 二十条**全部 TODO**；V2.5 L718 明确「AI 建仓是后续独立出口」，但 §1/§13 又把它列为 V2 系列未完成要求。
- 分叉点：若论文必须写这一章，则首版可用产品出口之外还要排 20 条细目的工期；若论文不含该章，则 AIW 归入「后续可选」。
- **一个问题**：毕业设计论文与答辩是否必须包含 AI 辅助建仓的实现内容？

### E-03 ACTIVE 指标快照的权威存储是哪个库？— 命中 (a) 架构分叉 ＋ (b) 数据口径
- 现状（实测，12:38）：应用配置 `application.yml:15` 把 `PLATFORM_METRIC_PUBLISH_URL` 默认指向 `jdbc:mysql://127.0.0.1:3306/**analytics_metric**`；但只读 `information_schema`（账号 `meta_app`）在 `analytics_metric` 中**看不到任何表**，而 `analytics_meta` 存在 `metric_snapshot`（12 列）/`metric_value`/`metric_definition`（22 表）——**ACTIVE 快照的唯一性约束实际都建在 `analytics_meta`**。`raw/09-information-schema-readonly.txt`。
- 分叉点：这决定「页面读哪个库」「§7.4 唯一性约束建在哪张表」「发布失败保旧快照如何原子保证」。同时 `meta_app` 的可见性受权限限制，**不排除** `analytics_metric` 里另有同名表（本任务未取证）。
- **一个问题**：ACTIVE 指标快照的权威库确认为 `analytics_meta`（并把配置默认值改齐），还是 `analytics_metric`（并把约束迁过去）？

### E-03【已消解·不再上升用户】ACTIVE 指标快照的权威库 ＝ `analytics_metric`
- **原始观测（保留，12:38）**：应用配置 `application.yml:15` 把 `PLATFORM_METRIC_PUBLISH_URL` 默认指向 `…/**analytics_metric**`；当时只读 `information_schema`（账号 `meta_app`）在 `analytics_metric` 中看不到任何表，而 `analytics_meta` 有 `metric_snapshot`（12 列）——故曾判为「架构分叉候选」。`raw/09-information-schema-readonly.txt`。
- **总控 12:4x 跨库普查（root 只读）判定**：`analytics_metric` 为**权威库**（与 `application.yml:15` 一致）；`analytics_meta` 的 12 列 `metric_snapshot` 是**历史副本** ⇒ 性质是「**历史重复表 ＋ schema 漂移**」，**不是架构分叉**。
- **本泳道独立复核支持**（`raw/10` R7/R13，root 只读）：metric 族 4 个库**独有** `uk_active_profile(runtime_profile_id, active_flag)`；权威侧 15 列**独有** `definition_version varchar(16) NOT NULL`、`failure_reason`、`active_flag` ⇒ 权威侧确为演进后结构；meta 族仅 12 列、无 `active_flag`，无法承载 ACTIVE 作用域唯一。
- **⇒ 处置**：本项从「必须用户裁决」移出，状态 = **已由总控跨库普查消解**；不再向用户提问。随之更正：此前基于 meta 族 12 列所写「无 `definitionVersion`」按权威侧更正为**存在且 NOT NULL**。

---

## 2. 已由总控裁决（原 Q1–Q10，留档，不再上升用户）

| 原编号 | 总控裁决 | 本泳道据此的动作 |
|---|---|---|
| Q1 证据目录是否预建 | **不预建空目录**；登记行写**预期交付路径**，交付后按实际目录就地更正 | `MATRIX.md` 缺口 #1 改写为「登记行为预期路径」；S01 至今未交付的事实保留 |
| Q2 严重度判据以哪个为准 | 过渡期**门禁唯一判据 = 版本化 `RuleSeverity.resolve(rules,…)`**；全局 `of()` 仅临时兼容，须有清单与限期迁移 | 缺口 #2 的六处调用点（`PipelineService.java:712,748,808`、`MetricPublishValidator.java:201,219,225`）**归 Q01 泳道迁移**；矩阵不再标「待裁决」，改标「已裁决·待迁移」 |
| Q3 编译红能否先交文档 | 允许；**并更正时点**：该编译红已由 Q01 于 12:2x 修复，`-pl platform-common -am -DskipTests compile` = `BUILD SUCCESS`/exit 0 | 缺口 #3 保留 12:08 旧结论并加 12:19 新时点（见 §3） |
| Q4 §11.4 任务文字 | **以看板 `V25-I02` 修订后为准**（真实缺口 = §5.3 连接器接口 + Flume 连接器 + 接入编排）；指导书 §11.4 文字待下版修订，本轮不改编号文档 | 缺口 #7 改写为「已裁决」 |
| Q5 基线指针 | **不写死 HEAD**，改「引用时点实测 HEAD + 时点」表述 | 本目录 `README.md` 已改为时点式表述 |
| Q6 §5.7 新字段归属 | **先并入 `ingestion-manifest.v1` 作可选扩展**，不自动升 major、不改 `contract-specs/VERSION`；是否升 major 留 `V25-C01` 冻结时定 | 缺口 #5 由「矛盾」改「已裁决·待 C01」 |
| Q7 AIW-001～020 是否拆行 | **不拆**（看板是执行队列不是功能清单）⇒ 在 AIW 专项文件内逐条登记 Owner/判据/证据位，`V25-A03` 行加索引指针 | 缺口 #10 由「治理缺口」改「已裁决·按专项文件登记」 |
| Q8 §10 13 字段是否要求看板单表齐备 | **不要求**；§10 是**任务包格式**（发给执行方的完整 14 字段），看板列只是状态视图 | 缺口 #9 关闭为「非缺陷」 |
| Q9 269 条归并归属 | **由 `V25-R02` 独占**，R01 只交「未映射·待归并」桶 | `MATRIX.md` §269 保持不变 |
| Q10 只读 `information_schema` | **授权**，约束：schema 限定 / 行数一律 `COUNT(*)` / 禁用 `TABLE_ROWS` / 每条注明时点 / 绝不写库 / 不用 `AUTO_INCREMENT` 或 mtime 作存在性推理 | 已执行 → `raw/09`（含权限边界更正） |

---

## 3. 由本泳道按已有设计自行决定（不上升用户）

| # | 事项 | 决定 | 依据 |
|---|---|---|---|
| D-01 | §4.2 对象个数 | **按指导书原文订正为 5 个**（`generator_target`/`generation_plan`/`generation_run`/`generation_artifact`/`generation_event_stat`），不写「六表」 | V2.5 L263–L269 表体实测 5 行 |
| D-02 | `analytics_metric` 零表 | **【13:0x 关闭】**原记「受权限限制·未取证」正确；总控 12:4x root 普查 ＋ 本泳道 `raw/10` R8 实测该库**有 11 张表**（8 张 `ads_*_m` + `metric_snapshot` + `metric_value` + `flyway_schema_history`）⇒ 可见性缺口**已关闭** | `raw/10` R1/R2/R8；口径教训：账号可见性 ≠ 库内容 |
| D-03 | §4.2 五表在可见范围内 0 命中 | **【13:0x 更正】**原「可见范围内不存在」不足以支撑任何结论：实测五个对象**存在于 schema `generator_meta` 且均有数据**（422/430/184/551/947 行）（`raw/10` R3/R5/R6）；总控普查因按 `analytics_*` 前缀过滤亦漏掉该库 ⇒ 记 `MATRIX.md` 更正 A。**纪律：按前缀/按账号过滤的普查不得得出「不存在」** | `raw/10` 更正 A；DDL `synthetic-data-generator/src/main/resources/db/generator/V1__generator_meta.sql:12,30,50,74,92` |
| D-04 | 旧 229 表处置 | **不删不改**，在其目录外（本目录）完成订正；其第 3 行的 README 断链原样保留 | V2.5 L32 历史件只读；本任务只读纪律 |
| D-05 | 是否为旧 229 目录补 README | **不补**（补写会与「既有证据目录只读」冲突）。若总控要补，应为**只读说明**且声明不复活 229 结论 | 同上 |
| D-06 | §10 任务包字段 | 不在本目录补齐 Owner/允许范围/反馈时间等（属看板职责）；本目录只作为被索引的交付物 | 总控 Q8 |
| D-07 | T02 的 Maven 级缺口 | 记为「探针级已证 + Maven 级未取证」，并在 `VERIFY-T01-T02.md` 给出**最小收口命令**（`-Dsurefire.failIfNoSpecifiedTests=false` 或去掉 `-am`）；**本任务只读，不代跑** | 总控第二版范围 ①「复核退出码与断言」 |
| D-08 | spark-jobs `Tests run: 0` | 记为**矛盾·未取证**，不判通过也不判失败；任何引用 `EXIT=0` 处必须同时引测试数 | `final-16` 聚合行 vs `local-readiness-20260914.md` L18–L20 |
| D-09 | ADS 表名 `_m` 后缀不一致 | **【13:0x 定案】**以**库内实名**为准：`analytics_metric` 内 8 张 ADS 全部带 `_m`（`ads_user_profile_m`/`ads_data_quality_m` 等），**代码与库一致**；不一致的是 DDL 文字（`warehouse/ddl/04-ads.sql` 无后缀）⇒ 待 `V25-C01` 冻结时订正 DDL 文字，本目录 `ADS-FIRST-RELEASE.md` 表名按 `_m` 读 | `raw/10` R8；`MetricAdsSpec.scala:42,45` |
| D-10 | §7.4 唯一性约束写法 | **【13:0x 按总控裁决定案】**以现有 **`uk_active_profile(runtime_profile_id, active_flag)`** 满足「按发布作用域唯一」，**不新增 `source_id` 列**（首版最小可实现）；§7.4 字面措辞待 `V25-C01` 按等价列改写，**不因此开迁移**。实测支持：该唯一键存在于 metric 族 4 库；`active_flag` 语义 = ACTIVE 行 1 / 非 ACTIVE 行 NULL（借唯一键允许多 NULL）；不变量①多 ACTIVE=0 ✔、②无 ACTIVE=0 ✔，但**样本仅 1 档案 / 12 行 / 1 ACTIVE**，**并发原子性仍未取证** | `raw/10` R7/R9/R10/R11/R12/R13；总控 D-10 裁决 |
| D-11 | 版本边界过渡期迁移清单 | 六处调用点文件行号已固化在 `MATRIX.md` §7-9 与 `raw/03`，供 Q01 直接消费 | 总控 Q2 |
| D-12 | 反馈节律 | 本泳道按看板 §4 登记时间回一条；期间不停机等待 | 总控第二版范围「不需要再等批准」 |

---

## 4. 事实性提醒（不是决策，不需要裁决）

1. **S01 未交付**：`docs/acceptance/v25-s01-it-safety-20260914/` 至 12:38 仍不存在（`VERIFY-T01-T02.md` §5）。
2. **T01 已绿、T02 未在 Maven 下跑过**（同上 §1/§2）；总控已将 T02 转 L2 复跑。
3. **工作树并发写**：12:19–12:22 期间其他泳道新建 `TestIsolationGuard*` 曾使 platform-common 由绿转红（`final-17`，HEAD 中不存在该文件，`git cat-file -e` 退出码 128）⇒ 任何「全绿」结论必须带时点与 HEAD。
4. **【13:0x 新增】9 个 `analytics_*` 库并存 ＋ 2 个商城库含同名表**：任何表级结论必须**写明 schema 名**，禁止裸表名（详见 `MATRIX.md` 风险 #16/#17）。权威库 = `analytics_metric`（依据：`application.yml:15` 默认值 ＋ 独有 `uk_active_profile` ＋ 15 列含 `definition_version`）。**此条属口径纪律，不是待裁决项。**
5. **【13:0x 新增】§4.2 五个对象已投产于 `generator_meta`**（DDL：`synthetic-data-generator/src/main/resources/db/generator/V1__generator_meta.sql:12,30,50,74,92`）⇒ 任何「生成器元数据未建表 / 未实现」的表述**均已失效**，包括本目录首版与总控 12:4x 普查的相应结论。
