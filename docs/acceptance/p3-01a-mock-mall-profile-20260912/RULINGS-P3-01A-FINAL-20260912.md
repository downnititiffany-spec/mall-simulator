# RULINGS-P3-01A-FINAL-20260912（D-140）

- 时点：2026-09-12 20:59（+08:00）
- 对象：P3-01-a「mock-mall 源画像」泳道交付（`b86e6d67-…`，20:56 自评 `DONE_LIMITED`）
- 父侧独立复核：本文件 §1–§2 的每条读数均由总控另行实测或读原始产物核对（复核方式逐条标注）
- 权威顺序：①冻结契约 ②指导书 V2.4 ③看板 V2.2 ④专项设计/实施书 ⑤V2.1 及更早

## §1 状态裁定：`DONE_LIMITED`

| 口径 | 结果 | 父侧复核方式 |
|---|---|---|
| E1 编译 | ✅ `BUILD SUCCESS` / `exit=0` / 9.817 s，日志 `raw/12-E1-compile.log`（3,381 B） | 读泳道原始日志 |
| E2 模块自动化 | 🟡 **全 reactor 537 测试 / 536 过 / 1 红**；画像相关两处全绿（`SourceProfileValidatorTest` 8/8、`SourceProfileFixtureTest` 4/4） | 见 §6，父侧已核对红灯前提 |
| E3 本地真链 | ✅ `POST /api/v1/sources/1/test` HTTP 200 / `ok=true` / **7 项 `passed=true` 且 `applicable=true`**；阴性对照 `POST /sources/999999/test` = 404 `SOURCE_NOT_FOUND`；`GET /sources/1` 的 `updatedAt` 与开工前逐字一致（未改状态）；**未调 `/activate`** | 读 `IMPL-REPORT.md` 原文响应体与 `raw/05`、`raw/14` |
| E4 集群 | ⬜ 不适用（本任务无集群面） | — |
| 本任务自建语义对账 | ✅ `raw/11`：I1–I9 **9 条全 PASS**，`verdict=ALL_PASS`；实测 2,620,776 行 / `event_type` distinct **12** / 阴性对照 0 | 读 `raw/11-profile-conformance-output.txt`（2,801 B） |
| 画像文件 | 3,323 B / mtime 2026-09-12 20:49:29 / sha256 **`0BB8A05C8D5E466864DCB90B8D7B97105DC65497DC3CCB463D104F27F021170B`** / 顶层键 **9** | **父侧自测**（`Get-FileHash` + `ConvertFrom-Json` 计数），非引用泳道值 |

**裁定**：记 **`DONE_LIMITED`**，**不升 `DONE`**。两条理由（均非泳道自评口径，为父侧认定）：
1. 全 reactor 存在 1 条红灯（§6，既有、与本任务无因果关系），本任务的"既有测试全绿"门槛未真正满足；
2. `enumSemantics` 的 `null` 语义在本裁决之前**未冻结**（§3），画像中确有 5 处依赖它。

**解除条件（二者同时满足即可升 `DONE`，由父侧复核）**：① F-86 由属主修掉或明确改为不读活 landing；② §3 的定义已回写 §4.2（`P3-01-c`）。

**泳道自评 `DONE_LIMITED` 而不升 `DONE` 的行为予以确认**：在无 `*.java` 写权限、无法使 reactor 转绿的前提下不宣称 `DONE`，属正确的克制；**拒绝"放宽测试换绿"**同样正确（`receiving-code-review` 口径：不得为绿而放宽断言）。

## §2 关于"自建断言全 PASS"的证据等级（父侧加注）

I1–I9 是**本任务自建**的断言，其通过是**自证**，**不构成独立验证**；它可用作"画像取值有实测出处"的证据，**不得**被引用为"平台已按该画像正确解析数据"。E3 的 `ok=true` 只是**浅检**（7 项均不校验 `formats` 元素语法、`fieldMapping` 取值、`enumSemantics` 的 `null`、`identityPolicy` 形状/代理键），**不得**读成"语义已合规"（承 D-139 §2.2）。

## §3 裁定：`enumSemantics` 的 `null` 是**受控扩展**，定义如下并冻结

泳道的诚实答复经父侧采纳：设计书 §4.2 **没有任何一行**定义 `null`（L129–132 的形状全是"字符串→字符串"，示例 `wishlist→favorite`、`SIGNED→PAID`）；而 §4.2 **L147** 明文「缺失的映射项 = "该源没有这个语义"（不是默认值）」。本源**实测确实存在**契约 enum 之外的取值（`purchase` 35、`web` 210、`20-29` 900、`45-54` 35、`city_level="2"` 63 …，出处见 `IMPL-REPORT.md` 与 `raw/08`），此时**省略键**等于陈述一句**被实测否证的假话**。故 `null` 并非设计书词汇，而是本会话指令引入的扩展。

**冻结定义（自本裁决生效）**：

> `enumSemantics` 中形如 `"<源取值>": null` 的映射项，语义为**「该取值已被实测观测到，但平台尚未裁定其规范语义（待裁定）」**；
> 它与 §4.2 L147 的**省略键**（＝「该源没有这个语义」）是**两种不同陈述**，实现方**不得**把二者归一；
> 出现 `null` 的漂移族一律：(a) 不参与 ADS 口径的规范化，(b) 须登记为开放裁定项（本源即 Q4），(c) 在数据质量侧可被统计但不进 quarantine。

**落地要求**：`P3-01-c`（新开）＝ 把上述定义**逐字追加**到 `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md` §4.2（append-only、带日期）；**Q4（枚举漂移：扩枚举升 1.1 还是修数据）保持未决**，本裁决不预设结论。`semantic_registry` / `dimension_registry` 表实测**不存在**（§4.2 §4.3 的引用属规格先于实现），故本定义**暂以设计书文本为属主**，待该表落地后迁移并留痕。

## §4 采纳泳道对我一条读数之否证：时间形态**不唯一**

我此前给出「2,622,616 个 `event_time` 全部为 `yyyy-MM-ddTHH:mm:ss+08:00`，其它形态 **0**」。泳道全树复算（`raw/07-time-shape-output.txt`，9,405 B）得：形态分类含 **`ISO_OFFSET_PLUS08_WITH_FRACTION`**（7 位 3,990/3,975、6 位 411/406、5 位 29/29、4 位 4、3 位 2），**父侧已读该原始输出确认**。

**更正口径**（以此为准）：**时区唯一**（全部 `+08:00`）成立；**形态不唯一** —— 约 **8.8 千行带小数秒**（3–7 位），我的"其它形态 0"**不成立**，撤回。

**裁定**：`timePolicy.formats` 里的 `ISO_OFFSET_DATE_TIME` 是**具名格式**（按 Java `DateTimeFormatter` 语义，**小数秒为可选**），**不是字面量模式匹配**；故这 8.8 千行**不构成拒绝风险**。**未实测**：全仓尚无消费 `formats` 的解析实现（承 D-139 §2.2），故本条为**口径裁定**而非实现证据；`P2-02` 实现时**必须**补一条"带小数秒"的用例，否则本裁定不可复核。

## §5 F-86（新）：全 reactor 唯一红灯与其跨泳道耦合

- 现象：`analytics-server/platform-app/src/test/java/com/graduation/analytics/ingestion/IngestionManifestSourceSchemaTest.java:168` 断言 `backfilled.isEmpty()` 失败，`Expecting empty but was: ["40.json","41.json","42.json"]`。
- 父侧核对前提：`landing/manifests/{40,41,42}.json` 确实存在，**mtime = 09-12 09:23:15 / 17:22:22 / 17:34:54，均早于本泳道开工（20:3x）**；`42.json` 顶层键确含 `sourceCode/sourceId/profileVersion/mappingVersion`（父侧逐键核对 = True）。
- 机制：该测试把**活 landing 目录**当作"历史清单"全集，而 P1-05 之后任何**真实入库**都会在该目录落**新代清单**，使"不存在新代清单"这一前提失效。**任何人再跑一次真实入库都会复现**。
- 裁定：**属主 = P1-05**。修法应为**改用固定夹具目录**（或只校验"运行前已存在"的子集），**不得**删除该断言、**不得**放宽其判据（D-037 裁决 6 的反回填语义仍然有效）。**记为 F-86，入台账。**
- **影响面**：从此"既有测试全绿"不再是可轻易宣称的门槛；凡以 E2 为由的 `DONE` 主张，必须说明所用命令的**作用域**与该红灯的关系。

## §6 P2-03-l（新）：`surrogate-key.v1.json` 向量 V01 指错字段

泳道附带查出：`surrogate-key.v1.json` 的 **V01** 把某个 UUID 当作 `entity=user` 的 `rawInput` 并标注 `why="真实 landing 取值"`；实测该 UUID 是 `user_registered` 事件的 **`event_id`**，同一行 `payload.user_id` 另有其值（即该规格自己的 V02）；泳道做了对照自检（landing 下 **624 文件**全扫，A=2>0、B≥目标、阴性=0）。

**裁定**：**属主修正 V01 或删除该向量**（P2-03 仍为 `REVIEW`，本项并列为出口待办）。理由采纳泳道原话：一个"看着有实测出处、其实指错字段"的向量**比没有向量更危险**。记 **P2-03-l**。

## §7 输入口径更正：金标 55 行文件的真实平台产出 = 51 accepted / 4 quarantined

由 M3 执行泳道实测并交叉取证（`landing/manifests/41.json` → `acceptedRecords=51 / quarantinedRecords=4`，文件 `r2-golden55-20260912-172221.jsonl`，`endOffset=18430`；`analytics_meta.ingestion_batch_file` id=117 → `record_count=51`；`file_checkpoint` id=110 → offset=18430），且与看板既有记录「51 接受 / 4 隔离」逐字一致；金标 `event_time` 日期分布实测 **53 行 09-01 ＋ 2 行 09-02**。

**裁定**：凡引用"55 行"均须区分**文件行数 55** 与**平台接受数 51**；M3 第 6 步的门槛由 55/0/1 **更正为 51/4/1**（`accepted>0` 即可继续）。**本条同时是对我自己的勘误**：我签发的 M3 工单把门槛写成 55/0，属**未实测即写门槛**。

## §8 勘误（我自己的错误，逐条留痕）

1. **D-139 pin 的画像指纹已过期**：我记 3,339 B / `FBA70E3C…`；**最终 = 3,323 B / `0BB8A05C8D5E466864DCB90B8D7B97105DC65497DC3CCB463D104F27F021170B`**（泳道在我出裁决**之后**又按 §2.4 自查修掉自身 5 处归一映射，故文件变了）。**P2-02 开工前须按此新值复测**；D-139 已加同日补记。
2. **"`PLATFORM_LANDING_LOCAL_ROOT` 可隔离本地输入"不成立**（§9）：我据**配置键名**推断其进入采集/流水线读路径，属**以名代实**；由 M3 执行泳道以五处 `file:line` 否决。
3. **"`event_time` 其它形态 0"不成立**（§4）。
4. 另记（同轮、非本泳道）：我曾把 `pipeline_run` id=40 的失败阶段误归为 `WAIT_LANDING`，实为 **`LOAD_ODS`**（`RUN_EMPTY_DATA`「accepted 无归属业务日事件」，`PipelineService.java:431`）；根因 = 该批 1,667 行事件**全为 dt=2026-09-11**，而 run 40 的 `business_time=2026-09-01`。

## §9 相关：M3 输入隔离的正确机制（裁定 A）

`LandingUri.java:46` → `Paths.get("./landing").toAbsolutePath()`，即 landing 根**按进程 CWD 解析**；`local-root` 实测只被 `RuntimeProfileServiceImpl.java:173`（LOCAL 探针 healthCheck）与 `PlatformBeans.java:55`（spark 提交日志根）消费。故 M3 第 6 步的输入隔离**只能**用文件系统手段或改 `landing_uri`。**裁定：采用方案 A**（把 `landing/events` 现有 61 个文件**移动到** `landing/_m3s8-parked/`，只移动不删除，`events/` 只留 1 个新命名金标副本；跑完立刻按原路径移回并核对 `newFileCount` 回 0；**不重启 8091**、**不写库**）。**否决方案 B**（`PUT /runtime-profiles/1` 改 `landing_uri`）——需要写库且改动共享配置行。

## §10 未做 / 未测（诚实申报）

未跑全 reactor 测试（父侧未复现 E2，只用原始日志与前提核对）；未实现任何代码、未改 DDL、未动 MySQL 业务数据；未调 `/activate`（种子源**未**激活，**不得**声称 P3-01 完成）；`null` 语义的**实现侧**行为未测（无消费方）；多源能力**无法测量**（`source_registry` 实测仅 1 行）；`surrogate-key.v1.json` 的 V01 修正**未做**（属主待办）；F-86 **未修**。
