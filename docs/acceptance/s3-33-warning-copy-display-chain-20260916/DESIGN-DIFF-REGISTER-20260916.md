# S3-33 设计差登记：告警文案展示链（信封内置映射 → 页面渲染）

- 日期：2026-09-16；分支 `feature/v3-development`；开轮 HEAD＝`bcb608f`（S3-32 收口后）。
- 题目来源：`docs/PROJECT_STATUS.md` backlog 行（改前行号 **L351**）「两个新降级码
  `RFM_RAW_VALUES_UNAVAILABLE`/`RFM_PERIOD_UNAVAILABLE` 在 `web/src/utils/envelope.js` 的
  `WARNING_TEXT` 表里**无中文文案**（当前回退 `String(code)` 原样展示，功能不受影响）」，
  类别登记为「**阶段5 开发项**（非阻塞）」⇒ 本 Agent 按 A 类自主实施。
- 一句话结论：**A 类（实现/加性）**，只动 `web/**` 4 文件（源码 2 ＋ 测试 2，+74/−5）；
  统一门禁三棵树与 spark 侧**零改动**；11 门逐门**否**。

---

## §0 本登记的证据边界

本文件只记录**真跑过的**命令、**逐字**输出与**未跑**的东西。凡未实测一律写「未测」，
不做推断性结论；所有证据文件位于 `%TEMP%`（`s333_*.log`），不进仓库。

---

## §1 题目实测改写（原行措辞方向对、落点不足）

| 编号 | 实测 | 结论 |
| --- | --- | --- |
| F1 | `AnalysisContext.vue:25`＝`warnings.map(warningTextAll).join('；')`；`AiAssistant.vue:96` 同（全 `web/src` grep `warningTextAll|warningText(`） | 页面**唯一**告警渲染入口＝`context.js` 的 `warningTextAll` |
| F2 | `envelope.js` 的 `warningText` 改前**零页面调用**，唯一调用点是 `web/tests/envelope.test.js:36`、`:38` | `envelope.js` 的 `WARNING_TEXT` 表对页面**不可达** |
| F3 | `context.js` 改前实现＝`WARNING_TEXT_EXTRA[code] \|\| String(code)`，而其自身 KDoc（原 L20）写着「**先查信封内置映射，再查本模块补全**」 | 实现与自身 KDoc **不符** ⇒ 信封码在页面上原样打出编码 |

⇒ 原行「在 `envelope.js` 的表里补两个码」若**只做表**，页面**看不到**；必须先把展示链修好，
文案才生效。这一条是本轮真正的技术内容。

---

## §2 改前取证（命令/锚点 → 实测）

| 编号 | 命令/锚点 | 实测 |
| --- | --- | --- |
| F4 | `analytics-server/metric-analysis/src/main/java/.../AnalysisViewModel.java:45-74` | 信封告警码共 **8** 个：`NO_ACTIVE_SNAPSHOT`(L46)、`UNKNOWN_SNAPSHOT`(L49)、`UNKNOWN_DIMENSION_TABLE`(L52)、`RFM_AMOUNT_UNAVAILABLE`(L55)、`RFM_RAW_VALUES_UNAVAILABLE`(L65)、`RFM_PERIOD_UNAVAILABLE`(L68)、`MULTIPLE_RULE_VERSIONS`(L71)、`QUALITY_STATUS_UNAVAILABLE`(L74) |
| F5 | `web/src/utils/envelope.js:48-51`（改前） | 表内只有 **2** 个键（`NO_ACTIVE_SNAPSHOT`/`UNKNOWN_DIMENSION_TABLE`）且按 F2 不可达 ⇒ 页面中文覆盖率 **0/8** |
| F6 | `context.js:12-17` | 前端**合成**码 4 个（`ENVELOPE_MISSING`/`AI_EVIDENCE_PARTIAL`/`QUALITY_RULE_FAILED`/`NO_SNAPSHOT_SELECTED`），语义 owner ＝ `context.js` 自身，**非**后端 |
| F7 | 两表键集合比对（F4 的 8 码 vs F6 的 4 码） | **交集为空** ⇒ 链式读取不产生「同一码两个 owner」，`WARNING_TEXT_EXTRA` 的语义定位不变 |
| F8 | 文案语义来源（**只抄不发明**）：`AnalysisViewModel` 各常量 KDoc（L45/48/51/54/57-63/67/70/73）＋ `docs/contracts/analysis-viewmodel-r7-4.md:9-10`、`:313`、`:315` ＋ `docs/PROJECT_STATUS.md` L159（S3-16 语义声明） | 8 条文案逐条有出处，见 §3 |

---

## §3 口径（本轮冻结：展示文案逐码对应后端 KDoc）

| 码 | 文案（本轮写入） | 语义出处 |
| --- | --- | --- |
| `NO_ACTIVE_SNAPSHOT` | 当前没有 ACTIVE 快照，指标库尚未发布可用数据。（**原有，未改**） | `AnalysisViewModel:45` |
| `UNKNOWN_SNAPSHOT` | 请求指定的快照在指标库中不存在，未读到该快照的任何数据（不假装读过）。 | `AnalysisViewModel:48` |
| `UNKNOWN_DIMENSION_TABLE` | 部分维度表本期不存在，对应维度为空。（**原有，未改**） | `AnalysisViewModel:51` |
| `RFM_AMOUNT_UNAVAILABLE` | 画像缺少消费额列：八类消费额无法从指标库取值（不用积分折算冒充金额）。 | `AnalysisViewModel:54` |
| `RFM_RAW_VALUES_UNAVAILABLE` | 画像缺少 R/F 原值列：R 已按「统计日 − 末次购买日」回算（与原值口径不同），F 原值为空。 | `AnalysisViewModel:57-63`；契约 L313 |
| `RFM_PERIOD_UNAVAILABLE` | 画像观察窗口缺失或行间不一致：窗口起止留空，不猜测窗口。 | `AnalysisViewModel:67`；契约 L315 |
| `MULTIPLE_RULE_VERSIONS` | 同一快照内出现多个画像规则版本，规则版本不唯一。 | `AnalysisViewModel:70` |
| `QUALITY_STATUS_UNAVAILABLE` | 质量结果查询失败：质量结论降级为「未知」（不伪造成通过）。 | `AnalysisViewModel:73` |

展示层**不新增语义**：文案不含任何后端契约未声明的判定/阈值/建议动作。

---

## §4 实现面（4 文件，全部 `web/**`，+74/−5）

1. `web/src/utils/envelope.js`（+18/−3）：`WARNING_TEXT` 由模块内 `const` 改为 **`export const`**
   （加性导出，供展示链复用），键数 **2 → 8**；`warningText` 实现**不变**（仍 `|| String(code)`，
   未知码原样透出）。KDoc 追加「本表＝信封内置码文案，owner 对应 `AnalysisViewModel`；缺码 ⇒
   页面原样打码」的实测说明。
2. `web/src/utils/context.js`（+6/−2）：`import { readEnvelope, WARNING_TEXT }`；`warningTextAll`
   改为链式 `WARNING_TEXT[code] || WARNING_TEXT_EXTRA[code] || String(code)`（＝其 KDoc 早已声明的
   顺序）；KDoc 记录 S3-33 实测缺陷。**未删任何码、未删表、未删回退分支。**
3. `web/tests/envelope.test.js`（+31/−0）：末位追加 S3-33 段 —— `ENVELOPE_WARNING_CODES` 8 码清单
   ＋ 2 用例（① 8 码全部有中文文案且非空、② 3 个 RFM 码文案命中「回算 / 不猜窗口 / 不冒充金额」语义关键词）。
4. `web/tests/context.test.js`（+19/−0）：追加 2 用例（① `warningTextAll` 链式解析信封码；
   ② 本模块补全码与未知码行为**不变**）。

---

## §5 证据（RED → GREEN → 探针 → 复原）

| 轮次 | 命令 | 逐字结果 |
| --- | --- | --- |
| RED（先写用例后改源码） | `npm test`（`web/`） | `ℹ tests 102 / ℹ pass 99 / ℹ fail 3`，`exit=1`；红＝新增 3 条（链式解析、8 码覆盖、RFM 语义），既有 98 条**无一变红**（102 ＝ 98 既有 ＋ 4 新增） |
| GREEN | `npm test` | `ℹ tests 102 / ℹ pass 102 / ℹ fail 0`，`exit=0` |
| 收口（探针复原后重跑） | `npm test` | 同 GREEN：`tests 102 / pass 102 / fail 0`，`exit=0` |
| 探针 A（拆链） | `warningTextAll` 退回只查 `WARNING_TEXT_EXTRA` | `tests 102 / pass 101 / fail 1`，`exit=1`；唯一红＝链式用例 ⇒ 链缺陷**有牙** |
| 探针 B（删 1 码） | 删 `RFM_PERIOD_UNAVAILABLE` 文案 | `tests 102 / pass 99 / fail 3`，`exit=1`；三重守卫同时命中（覆盖 / RFM 语义 / 显示链） |
| 探针复原校验 | `Get-FileHash` 与备份比对 | `context.js 4AFEA745CBE7A4D5`、`envelope.js B43DB3243E93D7EC` **逐一相同**；`git diff --numstat` 仅 4 文件，`web/**` 之外 **0** 文件 |

日志（不进仓库）：`%TEMP%\s333_red1.log`、`s333_green1.log`、`s333_green2.log`、`s333_probeA.log`、`s333_probeB.log`。

**统一门禁未跑（本轮）**，理由＝`analytics-server/**`、`spark-jobs/**`、`scripts/**` 本轮
`git diff --name-only` **零命中**（全部改动落在 `web/**`），门禁计数不可能变化；web 套件
**不在**统一门禁覆盖内（既有事实，与 S3-26/S3-27/S3-28 同款边界）。⇒ 本轮的 web 计数
（102）**不得**被表述为「统一门禁通过」。

---

## §6 类别判定与 11 门逐门

**类别：A 类（实现/加性）** —— 只动前端展示层与前端测试：无 DDL、无迁移、无后端字段语义、
零连库、零依赖新增。

| 门 | 判定 | 依据 |
| --- | --- | --- |
| ① DROP TABLE/COLUMN | 否 | 未触任何 DDL |
| ② 改已有字段类型/既有业务语义 | 否 | 只补前端文案；后端码语义**照抄** KDoc，未改判 |
| ③ 改已发布 Flyway migration | 否 | 未触 `**/db/migration/**` |
| ④ 写/迁移正式 3306 数据 | 否 | 零连库、零 SQL 执行 |
| ⑤ 切 ACTIVE | 否 | 未触快照状态 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 未触 `contract-specs/**`；`docs/contracts/**` 本轮**亦未改**（契约 L9-10/L313/L315 已先把两个码写清，无需按 L3-4 先改契约） |
| ⑦ 改 V3.0 总体架构 | 否 | 未新增组件/层 |
| ⑧ 改正式项目范围 | 否 | 原行本就是阶段5 登记项 |
| ⑨ 删除已发布功能 | 否 | **零删除**（仅新增导出与新增分支条件） |
| ⑩ 引入 V3.0 未规划大型基础组件 | 否 | 无依赖变化 |
| ⑪ 两种方案造成重大长期架构分叉 | 否 | 采用「链式复用既有两表」，未引入第三张表/新 owner |

**反熵声明**：未退休/删除任何逻辑或回退；`WARNING_TEXT`（信封码）与 `WARNING_TEXT_EXTRA`
（前端合成码）的**分工与 owner 不变**，两表键集合经实测**互斥**（F7）；本轮新增的
「链式读取」是 `warningTextAll` KDoc 早已声明、实现却缺失的行为，属**缺陷修复**而非新增 owner。

---

## §7 未测与边界（不得越界表述）

1. **`vite build` 未跑**（`web/node_modules` 不存在）⇒ SFC 模板编译未由编译器验证。本轮**未改
   任何 `.vue` 文件**，边界影响有限，但证据仍只有 `npm test`（node:test 纯逻辑）。
2. 页面**真实浏览器/像素级**展示未验收（无 DOM 走查与截图条件）⇒ 不得表述为「页面已验证显示中文」，
   只能表述为「文案解析链经单测验证」。
3. **跨树无自动守卫**：`ENVELOPE_WARNING_CODES` 是**前端镜像清单**，不是 owner。若后端
   `AnalysisViewModel` 新增第 9 个码，本链路**不会**自动发现 ⇒ 不得声称「永不漂移」（见 §8 R-1）。
4. 文案之后的**交互**（例如 `UNKNOWN_SNAPSHOT` 是否引导用户切快照）属阶段5 UI 决策，**未做**。
5. 未触及导出（export）路径、AI 证据包布局、质量规则结果区渲染，仅改「编码 → 中文」解析链。

---

## §8 遗留与后续

- **R-1（A 类候选，已登记 backlog）**：跨树对账守卫 —— 在 `metric-analysis` 测试侧用**既有**
  `RepoRoot`（单一 owner，**勿**新增 walk-up 实现）定位 `web/src/utils/envelope.js`，断言
  `AnalysisViewModel` 的 `WARN_*` 常量集合 ⊆（并反向核对）该表键集合；失败即「后端加了码、
  前端没文案」。这份守卫才能把 §7-3 的镜像风险变成可检测的失败。
- **R-2（范围裁决，待总控）**：原行只点名**两个 RFM 码**；本轮实测 F5 后一次补齐**同表同类的
  8 个信封码**（超范围 5 个：`UNKNOWN_SNAPSHOT`、`RFM_AMOUNT_UNAVAILABLE`、
  `MULTIPLE_RULE_VERSIONS`、`QUALITY_STATUS_UNAVAILABLE` ＋ 使既有 2 码可达）。若总控判为超范围，
  可保留 3 个 RFM 码文案；**但链式修复必须保留**，否则原行目标（页面显示中文）根本无法达成。
- **R-3（非功能）**：两表文案的措辞/长度规范（如「不猜窗口」这类表述是否统一为「不猜测」）
  属文案规范，不影响判定与展示。

---

## §9 结论

S3-33 收口：**原行（两个 RFM 降级码无中文文案）的实测面被改写并解决** —— 真正的缺陷是
`warningTextAll` 未按自身 KDoc 链式读取信封表（8 个信封码 0 覆盖），本轮修链 ＋ 同表同类补齐
8 个码的文案，RED→GREEN→双向探针→复原校验全部真跑；未测部分（`vite build`、真浏览器、
跨树守卫）逐条登记为边界，不作越界表述。
