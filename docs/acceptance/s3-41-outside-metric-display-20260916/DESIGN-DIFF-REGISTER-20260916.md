# S3-41 设计差异登记 —— 概览「清单外指标」的口径展示收口（比例/计数/名称/字典缺行）（阶段5）

- 日期：2026-09-16
- 轮次：S3-41（V3.0 持续执行模式，goal `goal-dd741915-9d85-4d36-a1ca-c92f6a534702`）
- 分支/worktree：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 起点 HEAD：`1237466`（S3-40 收口，HEAD == origin）
- 来源：S3-40 收口时**同一面**卡片墙复核实测发现的**同类残余**（S3-40 只关了 `repeat_rate` 的量纲与观察期），
  以及 backlog 行 **L463**（`cart_rate` 措辞过期）＋ **L464**（`MP_METRIC_VALUE_COUNT` 下限与实际不符）
- 类别：**A 类（实现/加性）** —— 只**消费**既有 `metrics[*]` 字段（值/单位/名称原样），
  **未改任何既有字段语义**、**未改 `contract-specs/**`**、**零 Java/Scala/SQL/迁移/连库**、**零 DDL**
- 交付面：`web/**` 4 文件（1 新增生产文件、1 改视图、2 新增测试）＋ `docs/**` 4 文件（契约 v1.10、本登记、PROJECT_STATUS、历史 F-74）

---

## §1 开工前实测（改前取证，可复现）

命令均在 worktree 根执行（`Set-Location D:\Develop_code\GraduationProject-wt\v3-dev`）。

| # | 实测 | 命令/位置 | 结果（实测） |
| --- | --- | --- | --- |
| F1 | 清单外指标怎么显示 | `web/src/views/Overview.vue:128-132`（改前） | 「后端返回但不在固定清单内的指标也照实展示」⇒ **`formatNumber(m.value, 2)` 一律两位小数** |
| F2 | 改前真模块输出 | `.verify/s341-outside/probe-before.mjs`（import 真 `number.js`） | `cart_rate 0.2531 ⇒ "0.25"`、`buy_rate 0.0812 ⇒ "0.08"`、`full_refund_rate 0.0123 ⇒ "0.01"`、`fav_cnt 12345 ⇒ "12,345.00"`、`cart_add_cnt 6789 ⇒ "6,789.00"` |
| F3 | 发布侧到底产哪些码 | `.../metric/publish/MetricPublisher.java:45-61,241-274` | `OVERVIEW_TO_METRIC` **12** 个值（`pv/uv/dau/paid_order_cnt/gmv/net_sale/avg_order_value/refund_rate/full_refund_rate/repeat_rate/fav_cnt/cart_add_cnt`）＋ 漏斗率 **2** 个（`buy_rate ← overall_buy_rate`、`cart_rate ← overall_cart_rate`）＝ **14** 码 |
| F4 | 后端是否按码过滤 | `.../analysis/AnalysisService.java:518-528` | `metrics()` **遍历 `metricValues(snapshotId)` 全部行**、**不按码过滤**；`MetricItem(code, names.getOrDefault(code,"") /* 字典缺该码就留空，不臆造名称 */, value, unit, period, definitionVersion)` |
| F5 | 字典种子有没有这 3 个码 | `analytics-server/platform-app/.../db/meta/V2__platform_pipeline_quality.sql:63-78` | 种子 **无** `full_refund_rate`/`fav_cnt`/`cart_add_cnt` 行 ⇒ `metricName` **空串** ⇒ 卡片标题回退**裸码**；`cart_rate`(L67)/`buy_rate`(L68) **有行**（名称「加购率」/「购买转化率」，`unit=''`） |
| F6 | 单位从哪来 | `MetricPublisher.java:278-292` | `valueOf(...)` 的 `unit`/`definitionVersion` 取自 `request.definitionVersions().get(metricCode)`（＝字典行）⇒ **单位不是硬编码** |
| F7 | web 套件改前基线 | `cd web; npm test` | `tests 134 / pass 134 / fail 0`，`exit=0`（S3-40 收口值） |
| F8 | 固定卡片清单 | `Overview.vue` `CARD_META` | **9** 码（S3-40 已加 `repeat_rate`）；与 F3 的 14 码**差 5 个** ⇒ 5 码全走清单外分支（正是 F2 那 5 行） |
| F9 | backlog 措辞复核 | `docs/PROJECT_STATUS.md` **L463** | 「`cart_rate` 只进 `metric_value`（`period=day:`）与漏斗 ADS 列，**未进概览 API 返回**」——**与 F4 实测不符**（它确在 `metric_value` 里 ⇒ 必然出现在 `data.metrics[]`）；该行后半「`AnalysisService` 仍只读 `overall_buy_rate`」指的是**漏斗页**路径（`AnalysisService.java:397-409` `FunnelData.overallBuyRate` ← ADS `overall_buy_rate`），**仍成立** |
| F10 | 禁改面是否被碰 | `git status --porcelain -- contract-specs scripts mall-simulator synthetic-data-generator spark-jobs analytics-server db README.md docs/guidance docs/design` | **为空** |
| F11 | 证据目录是否被忽略 | `git check-ignore -v '.verify/s341-outside/probe-after.mjs'` | `.gitignore:65:.verify/` ⇒ 证据不进版本库 |

**实测结论**：同一面卡片墙上**并列三种口径缺陷**（F2 逐条）——
①**比例当小数**（`cart_rate`/`buy_rate`/`full_refund_rate` ⇒ `0.25` 而非 `25.31%`）；
②**计数带小数尾**（`fav_cnt`/`cart_add_cnt` ⇒ `12,345.00`）；
③**字典缺行时标题是裸码**（`cart_add_cnt`）。
三者都撞阶段5 完成标准 **L201**「员工能完成主要操作并**理解结果**」。

## §2 类别判定（为什么是 A 类，而不是 HARD DECISION）

- 11 条 HARD DECISION 逐条对照：①无 DROP；②**未改既有字段类型/业务语义**（`metrics[*]` 结构、`unit`、`period`、
  `definitionVersion` 一律原样，改的是**读侧怎么显示**）；③未改已发布 Flyway 迁移（**V2 种子缺行属"加性迁移"**，
  本轮**不碰**，见 §7 R-4）；④未写/迁正式 3306 数据（**零连库**）；⑤未切 ACTIVE；⑥未改 `contract-specs/**`
  （只改可编辑的 `docs/contracts/analysis-viewmodel-r7-4.md`）；⑦未改 V3.0 总体架构；⑧未改项目范围；
  ⑨未删已发布功能（既有 4 列导出、9 张固定卡**行为不变**）；⑩未引入 V3.0 未规划大型基础组件
  （新增的是一个**无依赖纯逻辑 utils**，格式化函数**复用** `number.js`）；⑪不构成长期架构分叉。
- **为什么不是门②**：量纲信息（比例/计数）**本来就不在响应里强类型化**——后端只给 `value`（decimal）
  与 `unit`（字典给，`cart_rate`/`buy_rate` 为空串），**前端必须自己知道量纲**才能显示。本轮把这份
  「读侧量纲知识」从**散落的隐式两位小数**变成**唯一的显式登记表**（＋守卫），属**加性实现**，
  不是改既有语义。
- **为什么不是门⑥**：`contract-specs/**` 未碰；契约文件文首既有 **v1.0–v1.9 文本一律未改**，
  只在文首**追加 v1.10** 段与 §3.1 一条 bullet（契约文首纪律：「先改本文件再改代码」）。

## §3 本轮冻结的口径（写进契约 v1.10，逐字见契约文首）

1. `metrics[*]` 是**通用条目**、后端**不按码过滤**（发布侧当前可产出 **14** 码）；`CARD_META` 的 9 码走固定卡片，
   其余码走「清单外」分支、**照实展示**。
2. **比例型**（decimal 比值）按百分比格式化（复用 `number.js` 的 `formatPercent`）；**计数型**按整数
   （`formatInteger`）；**未登记的码不猜量纲**，照实按两位小数展示；数值**前端不重算**。
3. **名称三级回退**：后端字典名 → 前端登记表 → **指标码**；登记表**不得覆盖**字典名，也**不得臆造**未知码名称。
4. **字典非空而缺该码**时，卡片给**限制说明**「字典未登记口径」（**不写公式**、不推断口径）；
   **字典整体为空**时**不逐卡重复**同一原因（口径面板已统一说明「当前快照未提供指标口径字典」）。
5. **唯一属主** `web/src/utils/metricDisplay.js`（量纲/展示名/提示文案）；视图**不得**自持第二份比例换算
   （不得出现 `* 100`/`toFixed`/`'%'` 字面量）。
6. 导出 CSV 既有 5 列（含 S3-40 加的「口径周期」）**不变**。

## §4 实现面（本轮改了什么）

| 文件 | 变更 | 说明 |
| --- | --- | --- |
| `web/src/utils/metricDisplay.js` | **新增**（约 70 行，纯逻辑） | 唯一属主：`METRIC_KIND_PERCENT`/`METRIC_KIND_INTEGER`、`OUTSIDE_METRIC_KINDS`（5 码冻结）、`OUTSIDE_METRIC_NAMES`（3 码兜底）、`DICTIONARY_MISSING_NOTE`、`outsideMetricKind`/`displayMetricName`/`formatOutsideMetricValue`；**复用** `number.js`，**不依赖 vue**，**不重算** |
| `web/src/views/Overview.vue` | 5 处加性改动（165 → 179 行） | ①import 唯一属主 ②模板加 `v-if="m.dictionaryMissing"` 限制说明行 ③`cards` 内建字典码集合与 `dictionaryMissing(code)`（**字典为空则整卡豁免**）④固定卡片 push 带标记 ⑤清单外 push 改走 `displayMetricName`＋`formatOutsideMetricValue`＋标记（**原 `formatNumber(m.value, 2)` 已消失**） |
| `web/tests/metricDisplay.test.js` | **新增** 8 条 | 纯逻辑：5 码量纲、**与 `CARD_META` 不重叠且并集＝发布侧 14 码**、未登记码 ⇒ `null` 且不乘百分比、比例/计数/零/空值输出、名称三级回退、名称表不含固定卡码 |
| `web/tests/overviewOutsideMetric.test.js` | **新增** 6 条 | 源码文本守卫：唯一属主导入、清单外分支按量纲（且旧 `formatNumber(m.value, 2)` **必须不存在**）、视图/属主均不得自持比例换算、**`web/src` 内 `OUTSIDE_METRIC_KINDS`/`METRIC_KIND_PERCENT`/`DICTIONARY_MISSING_NOTE` 的属主唯一性**、字典缺行标记与整卡豁免、固定卡与登记表交集为空 |

**零改动面（实测 F10）**：生产 Java/Scala **0 行**、SQL/迁移 **0 行**、`contract-specs/**` **0 行**、
`scripts/run-tests.ps1`（门禁基线）**0 行**、`db/**` 0 行、连库 **0 次**。

## §5 证据（真跑，非推断）

- **E1 RED（TDD，先测后码）**：先写两个测试文件、未写实现 ⇒ `cd web; npm test` ＝ `exit=1`、
  **`tests 136 / pass 134 / fail 2`**（两文件整体红：属主模块不存在 ⇒ import 失败）。
  日志 `.verify/s341-outside/red.log`。
- **E2 GREEN**：实现 3 个文件后 ⇒ `exit=0`、**`tests 148 / pass 148 / fail 0`**（基线 134 → **148**，+14 条）。
  日志 `.verify/s341-outside/green.log`。
- **E3 变异探针（打在真实被测物上，4 条，逐字复原）**：

  | 探针 | 变异 | 结果 |
  | --- | --- | --- |
  | M1 | 清单外分支退回 `formatNumber(m.value, 2)` | `fail=1` ⇒ 红在「清单外分支按量纲」 |
  | M2 | 去掉清单外 push 的 `dictionaryMissing` 标记 | **首轮 `fail=0`（守卫漏判！）** ⇒ 已加固守卫后 `fail=1` |
  | M3 | `full_refund_rate` 量纲改判为计数 | `fail=2` ⇒ 红在量纲与关系断言 |
  | M4 | 展示名表塞进固定卡码 `refund_rate` | `fail=1` ⇒ 红在「名称表不含固定卡码」 |

  **M2 是本轮价值最高的一条**：首版守卫只断言「函数定义存在」＋「模板引用文案」⇒
  **删掉真实标记后仍全绿**（守卫看见了函数、看不见接线）。已**加固**为逐段断言
  `dictionaryMissing: dictionaryMissing(m.metricCode)` 与 `dictionaryMissing: dictionaryMissing(meta.code)`；
  加固后复跑 4/4 全部按预期红在指定守卫上。探针后两文件按 **SHA256 逐字复原**
  （`Overview.vue C7B3536A…`、`metricDisplay.js 6EAA8BAB…` 探针前后相同）。日志 `.verify/s341-outside/mutants.out.txt`。
- **E4 真实模块展示实测**（`.verify/s341-outside/probe-after.mjs`，**直接 import 真属主模块**，喂 F2 同一批码/值）：

  | 码 | 改前 | 改后 |
  | --- | --- | --- |
  | `cart_rate` `0.2531` | `0.25` | **`25.31%`** |
  | `buy_rate` `0.0812` | `0.08` | **`8.12%`** |
  | `full_refund_rate` `0.0123` | `0.01` | **`1.23%`** |
  | `fav_cnt` `12345` | `12,345.00` | **`12,345`** |
  | `cart_add_cnt` `6789` | `6,789.00` | **`6,789`** |
  | `roi` `0.5`（未登记） | `0.50` | **`0.50`**（**不**擅自乘百分比） |

  标题侧：字典给名时**用字典名**；字典缺该码 ⇒ `full_refund_rate ⇒ 全额退款率`、`fav_cnt ⇒ 收藏次数`、
  `cart_add_cnt ⇒ 加购次数`；**未知码 ⇒ 原样裸码**（不臆造）。输出 `.verify/s341-outside/probe-after.out.txt`。
- **E5 统一门禁重跑（本轮不是"零字节旁证"，是真跑）**：`pwsh -File scripts\run-tests.ps1 -Suite default
  -RunId S3-41-default-01 -LogDir .verify\s341-outside\gate -Confirm` ⇒
  `analytics-server exit=1 Tests run: **960 MATCH**（F=1 E=0 S=1，明细 93+350+169+97+94+157）`／
  `mall-simulator 13 MATCH`／`synthetic-data-generator 106 MATCH`／**三棵树 1079（基线 1079）MATCH**／
  **`[FAIL exit=7]`**。唯一红＝**已登记环境性红** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`
  （`expected: 43 but was: 0`；**未修、未复制 manifest、未用开关掩盖**）⇒ 与 S3-39 收口轮 `s339-final-1` **逐字相同**。
  `spark`(308)/`isolated`(55) 两档**未重跑**（零 `spark-jobs/**` 与隔离档相关文件改动）。
- **E6 证据目录**：F11 确认 `.verify/` 被忽略，所有中间产物留在 `.verify/s341-outside/`。

## §6 契约变更（先改契约再改代码，符合契约文首纪律）

`docs/contracts/analysis-viewmodel-r7-4.md`（**可编辑**，非 `contract-specs/**`）：
**加性**新增文首 **v1.10** 段（37 行）＋ §3.1 一条 **v1.10** bullet ＋ v1.10 第 3 条内的「字典整体为空」例外细化。
**既有 v1.0–v1.9 文本一律未改**（v1.9 的「仍未实现」清单原文保留，v1.10 只关闭其中『清单外指标量纲/展示名』一项）。
v1.10 内另附**勘误（指向 v1.9 引用）**：v1.9 第 3 条引用的「§1.4 空值规则」在契约内**不存在该编号**
（该编号在 S3-40 只出现在登记文件里），正确依据＝设计 §11.2 L428「空值规则和版本」＋契约 §1 总原则 3
＋ `number.js` 的占位符约定；**v1.9 原文不改**（只做指向性勘误）。
契约体检（改写后实测）：`CRLF=418 / bareLF=0 / 无 BOM / 34944 bytes`（改写前 377 行；两侧均 CRLF-only）。

## §7 未测与边界（不得越界表述）

- **R-1 真库未验（零连库）** ⇒「真实快照的 `metrics[]` 里**确实**含这 5 个码、且 `unit` 就是字典给的值」
  **未实测**（真库列/行存在性属 backlog L442/L450）⇒ **不得**表述为"页面已正确展示真实加购率/收藏次数"。
  本轮只证：**给定后端形状的行，展示文本正确**（E4）。
- **R-2 运行时渲染未验**：`web/node_modules` 不存在 ⇒ `vite build`／SFC 模板编译／浏览器渲染**均未跑**
  （与 S3-24/26/27/28/38/39/40 同一已登记限制）。证据＝`npm test`（node 纯逻辑＋源码文本守卫）＋ E4 **模块级**真跑；
  `Overview.vue` 的模板与 `computed` **未由编译器/运行时执行过**（含新增的限制说明行与整卡豁免分支）。
- **R-3 守卫是文本守卫、不是渲染断言，也不是污点分析**：唯一属主/接线断言读的是**源码文本**；
  若有人把逻辑搬到别处（例如在 `useAnalysis` 里预先格式化、或用 `v-html` 拼串），守卫**看不见**。
- **R-4 未做（**已登记**，不得当作已完成）**：①**字典行缺失未补** —— `metric_definition` 里
  `full_refund_rate`/`fav_cnt`/`cart_add_cnt` 三行仍**不存在**（补行属**加性迁移**，且已发布的
  `V2__platform_pipeline_quality.sql` **不得编辑**⇒需新迁移文件，涉发布链 ⇒**未做**）；
  ②**跨树守卫未做** —— 「发布侧 Java 可产出码集合 ↔ 前端量纲登记表」的自动对账**不存在**，
  本轮的 14 码是**镜像清单**（登记在新增 backlog 行）；
  ③`repeat_rate` 口径版本展示、两 ADS 列读侧暴露、快照级 `period` 并存（L446 R-4）、V2 种子公式文案漂移（L443 R-1）**均未碰**。
- **R-5 未改动任何既有行为**：9 张固定卡片、既有 4 列导出、`metricDictionary` 口径面板、`windowNote` 限制说明
  行为**逐字不变**（只**加**：卡片行可能多一条限制说明；清单外两列文本按量纲）。
- **R-6 门禁口径**：web 套件**不在**统一门禁 `scripts/run-tests.ps1` 内 ⇒ 门禁计数**未变**
  （analytics 960 / 三棵树 1079 / spark 308 / isolated 55）；web 套件基线 134 → **148** 只登记在
  `PROJECT_STATUS.md` 计数口径链。门禁本轮**已重跑**（E5），结果与基线逐字相同。
- **R-7 反向断言不得被读成"字典缺行是好事"**：R-4① 的字典缺行是**真实缺口**（口径面板里这 3 码确实没有口径行）；
  本轮的兜底名**只是展示兜底**，**不**代表口径已登记。

## §8 顺带台账（**不删行、不改判类**）

- backlog **L463**（`cart_rate`）：补「**（S3-41 更新描述，不删行、不改判类，2026-09-16）**」—— 记 F4/F9 实测结论
  （`cart_rate` **确在** `metric_value` ⇒ 必然出现在 `data.metrics[]`；「`AnalysisService` 只读 `overall_buy_rate`」
  指的是**漏斗页** `FunnelData.overallBuyRate`），**保留**原判类。
- backlog **L464**（`MP_METRIC_VALUE_COUNT` 下限 8）：补注**当前实测值**（发布侧可产出 **14** 码；下限 8 仍远低于实际），
  **保留**原判类「非阻塞」。
- backlog **新增 1 行**（紧接 L464 下方）：登记本轮**未**解决的两个残余面 ——
  ①`metric_definition` 缺 `full_refund_rate`/`fav_cnt`/`cart_add_cnt` 三行（补行＝加性迁移，需另行裁决）；
  ②「发布侧可产出码集合 ↔ 前端量纲登记表」跨树自动守卫（当前为镜像清单）。
- 契约 v1.0–v1.9 既有文本、`docs/contracts/metric-dictionary.md`、`db/meta/V2__…sql` **均未改**（R-4 待批注项）。

## §9 复现命令

```powershell
Set-Location D:\Develop_code\GraduationProject-wt\v3-dev
node .verify\s341-outside\probe-before.mjs   # F2 改前：0.25 / 12,345.00
node .verify\s341-outside\probe-after.mjs    # E4 改后：25.31% / 12,345
cd web; npm test                             # E1 RED（136/134/2）→ E2 GREEN（148/148/0）
cd ..; pwsh -NoProfile -File .\s341_mutants  # E3 变异探针（脚本在 %TEMP%，逐条见 mutants.out.txt）
pwsh -NoProfile -File scripts\run-tests.ps1 -Suite default -RunId S3-41-default-01 -LogDir .verify\s341-outside\gate -Confirm  # E5
git status --porcelain -- contract-specs scripts mall-simulator synthetic-data-generator spark-jobs analytics-server db   # F10 应为空
```
