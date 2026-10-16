# S3-40 设计差异登记 —— 窗口口径指标（`repeat_rate`）的观察期展示与比例口径（阶段5）

- 日期：2026-09-16
- 轮次：S3-40（V3.0 持续执行模式，goal `goal-dd741915-9d85-4d36-a1ca-c92f6a534702`）
- 分支/worktree：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 起点 HEAD：`2a56662`（S3-39 收口，HEAD == origin）
- 来源 backlog 行：`docs/PROJECT_STATUS.md` **L447**「`repeat_rate`/`repeat_period_start`/`repeat_period_end` **尚无消费方**」
  （该行自 S3-26 起标注「**下一候选**」）
- 类别：**A 类（实现/加性）** —— 只**消费**既有字段（`metric_value.period` 原样透传值）＋ 加性导出列；
  **未改任何既有字段语义**、**未改 `contract-specs/**`**、**零 Java/Scala/SQL/迁移/连库**、**零 DDL**
- 交付面：`web/**` 4 文件（1 新增生产文件、1 改视图、2 新增测试）＋ `docs/**` 4 文件（契约 v1.9、本登记、PROJECT_STATUS、历史 F-73）

---

## §1 开工前实测（改前取证，可复现）

命令均在 worktree 根执行（`Set-Location D:\Develop_code\GraduationProject-wt\v3-dev`）。

| # | 实测 | 命令 | 结果（实测） |
| --- | --- | --- | --- |
| F1 | 前端是否消费过 `repeat_rate` | `git grep -n -E 'repeat_rate\|repeatRate\|repeat_period' -- 'web/src/*' 'web/tests/*'` | **0 命中**（`exit=1`）⇒ 前端此前**完全没提过**这个码 |
| F2 | 页面卡片清单 | `web/src/views/Overview.vue:98-107` | `CARD_META` **8** 码（gmv/net_sale/paid_order_cnt/pv/uv/dau/avg_order_value/refund_rate），**不含** `repeat_rate` |
| F3 | 清单外指标怎么显示 | `Overview.vue:122-126` | 「后端返回但不在固定清单内的指标也照实展示」⇒ `formatNumber(m.value, 2)` |
| F4 | 后端是否已把该值发出来 | `analytics-server/metric-analysis/.../AnalysisService.java:518-542` | `metrics(snapshotId,names)` **遍历全部** `metric_value` 行（`metricStore.query(new MetricStore.MetricQuery(snapshotId,false))`），`MetricItem(metricCode,metricName,value,unit,period,definitionVersion)` **自带 `period`** ⇒ **不按码过滤** |
| F5 | 发布侧是否真的落该码 | `.../metric/publish/MetricPublisher.java:58,65,68,69` | `OVERVIEW_TO_METRIC.put("repeat_rate","repeat_rate")`；`WINDOWED_REPEAT_RATE="repeat_rate"`（**必须**声明 `window:`）；`PERIOD_START_COLUMN="repeat_period_start"`、`PERIOD_END_COLUMN="repeat_period_end"` |
| F6 | 契约此前的取值域 | `docs/contracts/analysis-viewmodel-r7-4.md:205`（改前） | 示例只给 `"period": "day:2026-09-01"`；`window:` 形态**未写进契约**（字段本身存在、原样透传） |
| F7 | 两个 ADS 列在响应里出现吗 | 契约 §3.1 响应体（`metrics`/`salesTrend`/`activeTrend`/`quality`/`metricDictionary`） | **不出现** —— 它们只被发布侧折进 `metric_value.period` ⇒ 读侧没有"ADS 列直读"这条路 |
| F8 | web 套件改前基线 | `cd web; npm test` | `tests 118 / pass 118 / fail 0`，`exit=0`（与 S3-38 登记一致） |
| F9 | 证据目录是否被忽略 | `git check-ignore -v '.verify/s340-display/probe.mjs'` | `.gitignore:65:.verify/` ⇒ 证据不进版本库 |

**实测结论（本行措辞与实测不符之处，据实登记，不改判类）**：

1. 该行「**尚无消费方**」对**值**而言**已过期** —— `repeat_rate` 经发布侧写入 `metric_value` 后，由
   `AnalysisService.metrics()` 的"遍历全部行"路径**已经**出现在 `/dashboards/overview` 的 `data.metrics[]` 里
   （F4/F5；该轮**未**验真库是否真发布过该行，见 §7 R-1）。
2. 「尚无消费方」对 **`repeat_period_start`/`repeat_period_end` 两个 ADS 列**而言**仍成立**（F7）。
3. **真正的缺口在展示侧**（F1–F3 实测）：`repeat_rate` 落到"清单外指标"分支 ⇒ ① `0.3333` 被 `formatNumber(v,2)`
   打成 **`0.33`**（比例丢失百分比语义，与 `refund_rate` 走 `formatPercent` 不一致）；② **不显示观察期**；
   ③ 与单日指标同处一面卡片墙 ⇒ 用户会把 **30 天窗口值读成当日值**（混口径）。
   用**真实模块**真跑（不是复述源码，见 §5 E4）：改前 `0.33` / 周期提示 `(无)`。

## §2 类别判定（为什么是 A 类，而不是 HARD DECISION）

- 11 条 HARD DECISION 逐条对照：①无 DROP；②**未改既有字段类型/业务语义**（`period` 字段语义原样、只是
  把它的**既有取值域**写进契约并**如实展示**）；③未改已发布 Flyway 迁移；④未写/迁正式 3306 数据；
  ⑤未切 ACTIVE；⑥未改 `contract-specs/**`；⑦未改 V3.0 总体架构；⑧未改项目范围；⑨未删已发布功能；
  ⑩未引入 V3.0 未规划大型基础组件（新增的是一个**纯逻辑 utils**，无依赖）；⑪不构成长期架构分叉。
- 该行**自己**给出的边界条件：「若实测必须改既有字段语义（门②邻域）则转 HARD DECISION 并改做其它无依赖项」——
  实测**不需要**改任何既有字段（F4/F6：字段早就在响应里，缺的是展示义务）⇒ 维持 **A 类**，可自主实现。
- 落地位置的选择（该行要求"先实测再定落 `OverviewData` 还是 `AnalysisViewModel`"）：实测显示**两处都不用改** ——
  值已在 `OverviewData.metrics[]`（透传），`AnalysisViewModel` 只承载信封/告警；故本轮**不改后端任何文件**
  （最小改动面），把义务落在读侧展示与契约文本上。

## §3 本轮冻结的口径（写进契约 v1.9，逐字见契约文首）

1. `metrics[*].period` 取值域＝ `day:<yyyy-MM-dd>`（单日）或 **`window:<start>..<end>`**（窗口/观察期，ISO 两端）。
2. 窗口口径指标**必须**与单日指标视觉可区分：展示观察期（取自该行 `period` 尾段）＋ 限制说明；
   **不得**看起来像"当日值"。
3. 该码是 decimal **比例** ⇒ 按百分比格式化（与 `refund_rate` 同一 `formatPercent`）；数值**不重算**。
4. `period` 畸形/缺省 ⇒ 只显示占位符：**不猜**观察期、**不补**默认窗口（同 §1.4 空值规则）。
5. **唯一解析属主** `web/src/utils/metricPeriod.js`；视图**不得**各自解析 `window:` 前缀（防第二属主）。
6. `/dashboards/overview` 导出 CSV **加性**加「口径周期」列；既有列与元信息行（`filters`/`snapshotId`/生成时间）不变。
7. `repeat_period_start`/`repeat_period_end` **仍不暴露**；**不得**声称"观察期来自 ADS 列直读"。

## §4 实现面（本轮改了什么）

| 文件 | 变更 | 说明 |
| --- | --- | --- |
| `web/src/utils/metricPeriod.js` | **新增**（约 90 行，纯逻辑） | 唯一属主：`isIsoDay`/`parseMetricPeriod`/`isWindowPeriod`/`periodText` ＋ 常量 `WINDOW_METRIC_NOTE`；**只解析格式**，畸形一律 `unknown`+`null` |
| `web/src/views/Overview.vue` | 8 处加性改动 | ①卡片加 `v-if="m.periodText"` 观察期副标题 ②卡片墙下 `v-if="windowNote"` 限制说明 ③`CARD_META` 加 `repeat_rate`（`percent: true`）④两处 `cards` 分支带 `periodText` ⑤`windowNote` computed ⑥导出 rows 加周期列 ⑦导出表头加「口径周期」⑧import 唯一属主 |
| `web/tests/metricPeriod.test.js` | **新增** 8 条 | 纯逻辑单测：day/window 两端/同日/单端/两端皆缺/畸形 15 类输入/空白/文案常量 |
| `web/tests/overviewWindowMetric.test.js` | **新增** 8 条 | 源码文本守卫：唯一属主、视图不得自解析、`web/src` 内 `window:` 字面量唯一、`percent: true`、副标题渲染、`v-if="windowNote"`、导出列、属主反向无视图依赖 |

**零改动面（实测）**：生产 Java/Scala **0 行**、SQL/迁移 **0 行**、`contract-specs/**` **0 行**、
`scripts/run-tests.ps1`（门禁基线）**0 行**、`db/**` 0 行、连库 **0 次**。

## §5 证据（真跑，非推断）

- **E1 RED（TDD，先测后码）**：先写两个测试文件、未写实现 ⇒ `cd web; npm test` ＝ `exit=1`、
  `tests 120 / pass 118 / fail 2`（两个新文件整体红：属主模块不存在 ⇒ import 失败）。
- **E2 GREEN**：实现后 ⇒ `exit=0`、**`tests 134 / pass 134 / fail 0`**（基线 118 → **134**，+16 条）。
- **E3 变异探针（打在真实被测物上，4 条，逐字复原）**：

  | 探针 | 变异 | 结果 |
  | --- | --- | --- |
  | P1 | `repeat_rate` 改回 `percent: false` | `exit=1 pass=133 fail=1` ⇒ 仅「按比例展示」红 |
  | P2 | 卡片改 `v-if="false"`（不渲染观察期） | `exit=1 pass=133 fail=1` ⇒ 仅「观察期副标题」红 |
  | P3 | 畸形窗口改为造默认观察期 `2026-01-01..2026-01-31` | `exit=1 pass=132 fail=2` ⇒ 「两端都缺不猜」「畸形不臆造」红 |
  | P4 | 非属主文件（`web/src/utils/csv.js`）追加一行含 `window:` 的注释 | `exit=1 pass=133 fail=1` ⇒ 仅「唯一属主」红 |

  四条探针**全部**按预期红在**指定守卫**上；探针后按 SHA256 **逐字复原**
  （`web/src/views/Overview.vue` `DBEEB756…`、`web/src/utils/metricPeriod.js` `C869199A…`、
  `web/src/utils/csv.js` `6650418F…` 探针前后**相同**）。
- **E4 真实模块展示实测**（`.verify/s340-display/probe.mjs`，直接 import `web/src/utils/number.js`
  与 `metricPeriod.js`，喂后端真实形状行）：改前 `formatNumber(0.3333,2)` ＝ **`0.33`**、周期提示 `(无)`；
  改后 `formatPercent(0.3333)` ＝ **`33.33%`**、`periodText('window:2026-08-07..2026-09-05')` ＝
  **`观察期 2026-08-07 ~ 2026-09-05`**；`day:` 行 ⇒ `null`（不加噪声）；`window:..`／`window:abc..def` ⇒ `null`（不猜）。
- **E5 旁证（零字节变化实测，非重跑统一门禁）**：
  `git diff --stat HEAD -- analytics-server spark-jobs scripts mall-simulator synthetic-data-generator db contract-specs`
  **为空**（本轮只碰 `web/**` ＋ `docs/**`）⇒ S3-39 收口轮 `s339-final-1` 的
  `analytics 960 MATCH`／`mall 13`／`generator 106`／`三棵树 1079`／`[FAIL exit=7]`（唯一红＝已登记环境性红
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`）**逐字适用**；
  `spark = 308`、`isolated = 55` 同样**未重跑**（零 `spark-jobs/**` 与隔离档文件改动）。
- **E6 证据目录**：`git check-ignore` 确认 `.verify/` 被忽略（F9），所有中间产物留在 `.verify/s340-display/`。

## §6 契约变更（先改契约再改代码，符合契约文首纪律）

`docs/contracts/analysis-viewmodel-r7-4.md`（**可编辑**，非 `contract-specs/**`）：**加性**新增文首 **v1.9** 段
（33 行）＋ §3.1 一条 **v1.9** bullet。**既有 v1.0–v1.8 文本一律未改**（v1.8 注记里"仍未实现"清单**原文保留**，
v1.9 段内声明"只关闭其中『`repeat_rate` 周期展示』一项，其余项不变"）。契约改写后体检：
`CRLF=377 / bareLF=0 / 无 BOM`（改写前 `CRLF=344 / bareLF=0`）。

## §7 未测与边界（不得越界表述）

- **R-1 真库发布未验**：本轮未连库（3306/3307 均未碰）⇒「真实快照的 `metrics[]` 里**确实**有 `repeat_rate` 行」
  **未实测**（沿用 S3-03 的证据面：只证到「V6 迁移文本 ↔ Java 白名单」一致；真库列存在性属 backlog L442）。
  故本轮**不得**表述为"页面已展示真实复购率"。
- **R-2 运行时渲染未验**：`web/node_modules` 不存在 ⇒ `vite build`／SFC 模板编译／浏览器渲染**均未跑**
  （与 S3-24/26/27/28/38 同一已登记限制）。本轮证据是 `npm test`（node 纯逻辑＋源码文本守卫）＋ E4 的
  **模块级**真跑；`Overview.vue` 的模板与 `computed` **未由编译器/运行时执行过**。
- **R-3 守卫是文本守卫、不是渲染断言**：`percent: true`／副标题／导出列的守卫读的是**源码文本**；
  若有人把逻辑搬到别处（例如在 `useAnalysis` 里预先格式化），守卫**看不见**。
- **R-4 反熵未做**：`repeat_period_start`/`repeat_period_end` 两列**仍无读侧暴露**（登记见 PROJECT_STATUS 新增行）；
  `repeat_rate` 的**口径版本展示**（v1.8 注记同列项）**未做**；快照级 `metric_snapshot.period`
  与窗口型 `metric_value.period` **并存**（backlog L446 **R-4**）**未变**；`V2` 种子公式文案漂移（L443 **R-1**）未碰。
- **R-5 未改动任何既有行为**：`day:` 行与清单外其他指标的行为**逐字不变**（`periodText` 对它们返回 `null`，
  模板 `v-if` 为假）；导出只在表尾**加一列**（既有 4 列顺序不变）。
- **R-6 门禁口径**：本套件**不在**统一门禁 `scripts/run-tests.ps1` 内 ⇒ 统一门禁计数**未变**
  （analytics 960 / 三棵树 1079 / spark 308 / isolated 55）；web 套件基线 118 → **134** 只登记在
  `PROJECT_STATUS.md` 计数口径链。

## §8 顺带台账（**不删行、不改判类**）

- backlog **L447**：补「**（S3-40 更新描述，不删行、不改判类，2026-09-16）**」—— 记实测结论（值已通、两列未通、
  真缺口在展示侧、已收口），**保留**原判类「阶段4/5 开发项（非阻塞）」。
- backlog **新增 1 行**（紧接 L447 下方）：登记本轮**未**解决的残余面（两 ADS 列无读侧暴露、口径版本展示未做、
  快照级 period 并存、真库/渲染未验），避免"展示缺口已收口"被读成"复购率链路已完整"。
- 契约 §1.4/§3.1 既有文本、`docs/contracts/metric-dictionary.md`、`db/meta/V2` **均未改**（R-1/R-4 待批注项）。

## §9 复现命令

```powershell
Set-Location D:\Develop_code\GraduationProject-wt\v3-dev
git grep -n -E 'repeat_rate|repeatRate|repeat_period' -- 'web/src/*' 'web/tests/*'   # F1 改前 0 命中
cd web; npm test                                                                      # E1 RED → E2 GREEN
node ..\.verify\s340-display\probe.mjs                                                # E4 展示实测
cd ..; git diff --stat HEAD -- analytics-server spark-jobs scripts db contract-specs   # E5 旁证（应为空）
```
