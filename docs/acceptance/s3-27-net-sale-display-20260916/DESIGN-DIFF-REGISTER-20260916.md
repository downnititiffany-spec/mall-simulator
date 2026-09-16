# S3-27 设计差异登记：阶段5「页面显示**净销售额**」——逐日 `netSaleAmount`（契约 v1.4 字段）读侧消费落地

- 任务编号：**S3-27**（V3.0 持续执行模式，goal round 6）
- 日期（项目内时点）：**2026-09-16**
- 分支/工作区：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 前置：`82141ad`（S3-26 代码）／`c7d360c`（S3-26 文档），两者已 `push` 到 `origin/feature/v3-development`
- 本轮判定：**A 类（实现／加性，读侧消费）**；11 门逐门**否**（§5）

## 0. 证明边界（先说不能证明什么）

1. 本轮改的**只有** `web/**`（4 个源文件 + 2 个测试文件）与 `docs/contracts/analysis-viewmodel-r7-4.md`（追加 v1.7 说明段）。**零** Java/Scala/SQL/迁移/配置改动，**零**连库，**未**触 3306/3307/ACTIVE，**未**改 `contract-specs/**`。
2. **没有 SFC 编译证据**：`web/node_modules` **不存在**，`vite build` 在本环境**不可运行**（装依赖需联网，未授权）⇒ 「模板能被编译器接受」**未验证**。模板层证据**只有** `web/tests/netSaleDisplay.test.js` 的**源码文本守卫**。
3. **没有浏览器/DOM 证据**：未起前端、未起 8091、无任何截图或 DOM 断言 ⇒ 下列结论均**不是**渲染结果证据：柱序列是否真的画出、两柱是否真的并排、提示文案是否真的可见。
4. **没有真实 HTTP 证据**：本轮未起服务、未发请求 ⇒ 「真实响应里的 `netSaleAmount` 被页面吃到」**未实测**；字段存在性与值语义来自 S3-20 的后端证据与契约 v1.4（**沿用**，非本轮新证）。
5. **没有真实数据证据**：`0` 是「未计算占位」还是「当日零净额」在**当前数据上不可区分**（契约 v1.4 边界 2），本轮**也没有**去区分，页面只是**原样展示**并**文字标注**该限制。
6. 本轮**未**实现净销售额的**区间过滤**：`/analysis/sales` 的 `from`/`to` 仍只回显不过滤（backlog 已登记）⇒ 图中曲线是**快照逐日**，不是「所选区间净额」。
7. `web` 前端测试**不在**统一门禁 `scripts/run-tests.ps1` 内（脚本自述「web 前端（整理阶段范围外）」）⇒ `npm test` 的 92/92 **不是**任何档位的通过凭证；default 档回归只证明「没把既有三棵树改坏」。

## 1. 逐字锚点（权威三件套 + 契约）

| 来源 | 位置 | 逐字要点 | 本轮对应动作 |
| --- | --- | --- | --- |
| 指导书 V3.0 | §7 阶段5 **L164** | 「页面统一加载/空数据/成功/失败状态，显示**更新时间、来源、快照和限制**」 | 新增**限制**文字（`NET_SALE_NOTE`，两页共用常量） |
| 设计 V3.0 | §11.2 **L428** | 「净销售｜**同口径支付金额 − 成功退款金额**｜真实收入方向，**退款归属期需冻结**」 | 文案如实写明「退款归属期未冻结、跨业务日退款不回改」；**不**称最终到账净收入 |
| 设计 V3.0 | §9.3 **L333** | 「`ads_sale_trend`：历史已发布，**net_sale 等字段需补**」 | 后端侧 S3-20 已补；本轮补**页面消费** |
| 设计 V3.0 | §12.3 **L506** | 「同归属口径 **ADS GMV ≥ 净销售 ≥ 0**」 | **仍未实现**：页面**不做**启发式纠正（测试守卫 `netSaleAmount` 两侧无算术） |
| 契约 r7-4 | **v1.4 L40-62**（尤其 L44-49、边界 1/2/3/4） | `netSaleAmount` = ADS 列**原样透传**；`netSale`（汇总）与 `netSaleAmount`（逐日）**不是一个东西**；null＝缺失/畸形**不臆造 0**；`0` 可能是占位；不保证 `GMV ≥ 净销售 ≥ 0` | 实现面**逐条对齐**；同轴并列展示；null 断点；0 原样 |
| 契约 r7-4 | **v1.4 边界 5（L59-60）** | 「阶段5 页面**尚未**展示该字段（`Sales.vue`、`Overview.vue` 本版未改）⇒ 不构成『页面已展示净销售额』」 | **本轮交接项**：本句自 S3-27 起**已过期**（v1.7 已声明只读作历史） |
| 契约 r7-4 | **本文件 L3-4** | 「若实现中确需改动，**先改本文件再改代码**」 | 本轮**先**追加 v1.7 说明段，**再**改代码（无「先码后契」顺序偏差） |
| `docs/PROJECT_STATUS.md` backlog | **L359** | 「~~`ads_sale_trend_m.net_sale_amount` **尚无消费方**：`AnalysisService.T_SALE_TREND`/`AnalysisViewModel` 仍按旧 4 列直通~~ **已实施**（S3-20）…」＋其后「阶段5 未展示净销售额」 | 本轮的**选取依据**（滚动第一个未满足项） |
| 同上 | **L354** | 「净销售额的**退款归属期跨业务日重结未实现**…属潜在门 ②/⑪ 邻域」 | **未触碰**；只在页面文字中如实标注 |

> 注：`docs/PROJECT_STATUS.md` L359 行原文由 S3-20 改写为「已实施（后端读侧）」，其**页面侧**残留即本轮项；L354 的归属期口径**未裁决**，本轮**不**改判。

## 2. 开工前实测事实（F1–F6，改前取证）

| 编号 | 实测命令／依据 | 结果（改前） |
| --- | --- | --- |
| F1 | `git grep -n "netSale" -- web/src`（HEAD=`c7d360c`） | **仅 2 处命中**：`Sales.vue:90` `defaults` 的 `netSale: null` 与 `Sales.vue:97` 汇总卡片「净销售额」；**`netSaleAmount` 全仓 0 命中** |
| F2 | `read web/src/utils/chartOptions.js:10-28` | `salesTrendOption` 只构造 3 个序列（`销售额` bar / `订单数` line / `买家数` line），`legend.data` 亦 3 项 ⇒ **趋势图不含净销售额** |
| F3 | `read web/src/views/Sales.vue:103-109`、`:142-143` | 明细 `columns` 5 列（日期/订单数/销售额/买家数/客单价）、导出 `headers` 同 5 列 ⇒ **表格与 CSV 均无净销售额列** |
| F4 | `read web/src/views/Overview.vue:121` | 经营概览 `salesOption = salesTrendOption(data.value.salesTrend)` ⇒ 与 F2 同图，**同一缺口** |
| F5 | 契约 v1.4 边界 5（上表） | 后端字段**已发布且已实测**（S3-20），契约**明文登记**「页面尚未展示」＝**待交接缺口**（非猜测） |
| F6 | `npm test`（改前基线，S3-26 收口） | **84/84 全绿**、`fail 0`（node v24.16.0）；`web/node_modules` **不存在** |

## 3. 口径声明（本轮冻结的判据）

1. **只展示、不重算**：净销售额仅做 `netSaleAmount` → 图表/表格/CSV 的**原样映射**（`num()` 只做字符串→数字，`formatNumber` 只做千分位），**不得**出现任何以 `GMV`/退款率反推净额的算式（测试守卫：三个源文件内 `netSaleAmount` 两侧不得出现算术运算符、不得出现 `Math.max(...netSaleAmount...)`）。
2. **不臆造 0、也不把 0 当缺值**：缺失/`null`/空串 ⇒ 图表 `null`（断点）、表格空串、CSV 空串；**数值 `0` 原样展示**为 `0.00` ⇒ 页面**不**把 `0` 改写成「—」（因为 `0` 与「未计算占位」不可区分，改写等于**替后端下结论**）。
3. **两个「净销售额」不得互相代入**：卡片「净销售额」（`data.netSale`，快照汇总 `metric_value`）与趋势/明细/CSV 的「净销售额」（`netSaleAmount`，逐日 ADS 列）**同页并存但语义不同**，故限制文案**必须**写明这一点（测试守卫：`NET_SALE_NOTE` 含「汇总」）。
4. **限制文案单一来源**：`web/src/utils/chartOptions.js` 导出 `NET_SALE_NOTE`，`Sales.vue`/`Overview.vue` **只引用不复制**（测试守卫：两视图均 `import ... NET_SALE_NOTE`，且**不得**内联出现「退款归属期未冻结」等关键句）。
5. **同轴可比**：净销售额柱与销售额柱**同 `yAxisIndex`（未设＝0，金额轴）**，才可能肉眼比较 GMV 与净额的差距；**不**放次轴（放次轴会给出「量纲不同」的错误暗示）。
6. **本轮不改契约语义**：v1.7 只登记「读侧出现首个消费方」与展示层口径，**不**新增/改字段、**不**改 null 语义、**不**触碰 v1.1–v1.6 任何已发布段落（只**追记** v1.4 边界 5 已过期）。

## 4. 实现面（6 个文件：4 源 + 2 测试；＋1 契约文档）

| # | 文件 | 改动 | 加性判据 |
| --- | --- | --- | --- |
| 1 | `docs/contracts/analysis-viewmodel-r7-4.md` | **先改**：v1.6 段后追加 **v1.7（S3-27 加性补充·读侧消费）**：展示面清单、展示层口径 4 条、仍未实现清单、声明 v1.4 边界 5 只读作历史 | 纯追加，未改任何既有字段语义 |
| 2 | `web/src/utils/chartOptions.js` | 新增导出常量 `NET_SALE_NOTE`（单一限制文案）；`salesTrendOption` 的 `legend.data` 与 `series` **各插入 1 项**「净销售额」（bar，同轴，值 `num(r.netSaleAmount)`）＋口径注释 | 序列**插入**、原 3 序列的 name/data/轴不变；无既有取值逻辑改动 |
| 3 | `web/src/views/Sales.vue` | 趋势图标题补「净销售额」；图下新增 `NET_SALE_NOTE` 提示；明细 `columns` 插入 `{ key: 'netSaleAmount', title: '净销售额(元)' }`；数据行插入 1 个 `td`；空行 `colspan 5→6`；导出 `headers` 插入「净销售额(元)」、行数据插入 `formatNumber(r.netSaleAmount, 2, '')`；import 增 `NET_SALE_NOTE` | 表格列**新增**（旧列 key/顺序不变，新列插在销售额之后）；导出列**新增**；无删列、无改列语义 |
| 4 | `web/src/views/Overview.vue` | 销售趋势标题补「净销售额」；图下新增 `NET_SALE_NOTE` 提示；import 增 `NET_SALE_NOTE` | 图序列由共用构造函数带来，**本文件 0 行取值逻辑改动** |
| 5 | `web/tests/chartOptions.test.js` | 原「销售趋势」用例按**新序列布局**更新索引断言（`series[2]`=订单数、`series[3]`=买家数）；**新增 3 条**：并列展示（legend/name/同轴）、null 与 `0` 的三态映射（`[null,null,null,0]`）、`NET_SALE_NOTE` 四要点断言；原「空数组系列仍存在」用例 `series.length 3→4`；import 增 `NET_SALE_NOTE` | 测试面加性 |
| 6 | `web/tests/netSaleDisplay.test.js`（**新文件**） | 5 条**源码结构守卫**：①常量存在并导出；②`chartOptions` 含「净销售额」/`netSaleAmount`/「逐日口径」；③`Sales.vue` 含列定义、表格列、导出表头与导出取值；④两视图均 import 并渲染 `NET_SALE_NOTE`、且**不得**内联复写关键限制句；⑤三源文件内 `netSaleAmount` **无算术**（不做启发式纠正） | 新增文件 |

**未改**（明确声明）：`web/package.json`（**零新依赖**）、`web/src/api.js`、`web/src/utils/envelope.js`、`web/src/utils/chartState.js`、`web/src/utils/exportCsv.js`、`web/src/utils/number.js`、其余 7 个视图、任何 Java/Scala/SQL/迁移/脚本。

## 5. 11 门 HARD DECISION 逐门判定（全部「否」）

| # | 门 | 判定 | 依据 |
| --- | --- | --- | --- |
| ① | DROP TABLE/COLUMN | **否** | 无 DDL；SQL 全仓 0 改动 |
| ② | 改既有字段类型或业务语义 | **否** | 只读已发布字段；`netSale`（汇总）与 `netSaleAmount`（逐日）语义**各自不变**；退款归属期口径**未动** |
| ③ | 改已发布 Flyway migration | **否** | 无迁移改动 |
| ④ | 写/迁移正式 3306 数据 | **否** | 0 次连库 |
| ⑤ | 切 ACTIVE | **否** | 未触画像/激活 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **否** | `contract-specs/**` **0 命中**；`docs/contracts/**` 仅**追加** v1.7 说明段，未改任何字段定义 |
| ⑦ | 改 V3.0 总体架构 | **否** | 页面层单点展示 |
| ⑧ | 改正式项目范围 | **否** | 属阶段5 已登记项（backlog L359 的页面侧残留） |
| ⑨ | 删除已发布功能 | **否** | 无删除；旧序列/旧列/旧导出列**全部保留** |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **否** | 零新依赖、零新组件（复用既有 `chartOptions`/`ChartState` 模式） |
| ⑪ | 两种方案造成重大长期架构分叉 | **否** | 与 S3-20/S3-26 同型：读侧加性消费，无分叉 |

## 6. 实测证据（未实测不写结论）

### 6.1 定向套件（RED → GREEN，真跑）

- **RED**（先写用例、后写实现；`$env:TEMP\s327_red.log`）：`npm test` ⇒ **`tests 80 / pass 75 / fail 5`**、`exit=1`。5 红＝**1 条文件级模块错误**（`tests\chartOptions.test.js` 因 `NET_SALE_NOTE` 尚未导出而链接失败、其 12 条用例未被计入 ⇒ 92−12＝80）＋**4 条断言红**（`NET_SALE_NOTE` 未导出；`chartOptions` 无「净销售额」序列；`Sales.vue` 无净销售额列/导出列；两视图未渲染 `NET_SALE_NOTE`）⇒ 用例**有判别力**。
- **GREEN**（实现后；`$env:TEMP\s327_green1.log`）：`npm test` ⇒ **`tests 92 / pass 92 / fail 0`**、**`exit=0`**、`duration_ms 152.1408`（node v24.16.0、npm 11.13.0）。增量 **+8**＝`chartOptions.test.js` +3、新文件 `netSaleDisplay.test.js` +5（84 → 92）。
- **未做**：本轮**未**另做变异探针（RED 已直接证明判别力；S3-26 同口径）。

### 6.2 档级回归（default 档，证明「未波及既有三棵树」）

- 运行：`& .\scripts\run-tests.ps1 -Suite default -RunId s327_20260916_def1 -LogDir .verify/s327_def1 -Confirm`（**未加** `-AllowCountDrift`），日志 `.verify/s327_def1/`（gitignored）。
- 结果：`analytics-server exit=1 Tests run: 949 (F=1 E=0 S=1)`、明细 `93+350+163+93+93+157`、`tests=949 MATCH`；mall `13 MATCH`（`exit=0`）；generator `106 MATCH`（`exit=0`）；**三棵树 1068 ＝ 基线 1068**、**无 DRIFT**；脚本末行 `[FAIL exit=7] 所选档未全部通过。`
- **唯一红 ＝ 已登记环境性红** `com.graduation.analytics.ingestion.IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（platform-app 汇总 `Tests run: 157, Failures: 1, Errors: 0, Skipped: 0`；断言原文 `[清单快照 39＋P1-05 前批 4（40-43.json）＝P1-05 期望真实采集数= 43] expected: 43 but was: 0`，`IngestionManifestRuntimePatrolTest.java:61`；同轮该测试自身打印 `清单目录不可用: …\landing\manifests … 历史清单不在仓库`＋`新增的文件计数（红）: 0`）⇒ 本档结论 ＝「**计数 MATCH ＋ 唯一红＝该已登记环境性红**」；脚本 `[FAIL exit=7]` 为**预期**，**不得**写成 `[PASS exit=0]`。本轮**未修**该用例、**未**复制/伪造 manifest、**未**用开关掩盖（**未加** `-AllowCountDrift`）。
- **该档证明的**：本轮改动**没有**波及三棵树的用例计数；**未证明的**：任何前端行为（`web/**` 不在统一门禁内）。
- **`spark` 档本轮未跑**（零 Scala 改动），spark＝**303 沿用 S3-25 `s325_20260916_spark2`**。
- **环境更正（如实登记）**：首次以 `powershell -NoProfile -File scripts/run-tests.ps1` 启动时脚本**解析失败**（Windows PowerShell 5.1 按 ANSI 读 UTF-8 脚本 ⇒ 中文串乱码报 `Unexpected token`），**未产生任何测试证据**；改用 PowerShell 7（`& .\scripts\run-tests.ps1 …`，与既往轮次同路径）后正常执行 ⇒ 该次失败**不**计入门禁结论。

## 7. 未测边界与未做（不得越界表述）

1. **`vite build` 未通过**（`web/node_modules` 不存在 ⇒ `'vite' is not recognized…`）⇒ SFC 模板编译**未由编译器验证**；模板层证据只有源码守卫（§0.2）。
2. **无浏览器/DOM 走查**（未起前端与 8091）⇒ 不构成「页面已正确渲染净销售额」。
3. **真实 HTTP 响应含 `netSaleAmount` 未被本轮消费实测**（沿用 S3-20 后端证据 + 契约 v1.4）。
4. **`0` 的语义未区分**：占位 vs 真实零净额在现有数据上不可区分（契约 v1.4 边界 2），页面只**标注**、不**判定**。
5. **`from`/`to` 区间过滤仍未实现**（backlog L342）⇒ 图/表/CSV 的「净销售额」是**快照逐日**值，**不是**「所选区间净额」。
6. **设计 §12.3 L506 不等式未实现**（读侧不做纠正）⇒ 页面**不保证** `GMV ≥ 净销售额 ≥ 0`，也不得据此判断数据异常。
7. **退款归属期未冻结**（设计 §11.2 L428、backlog L354）⇒ 不得读作「最终到账净收入」。
8. **阶段5 其余项未做**：`quality.ruleVersions` 展示（backlog L360）、`repeat_rate` 周期/口径版本消费（L367）、支付复购率变体（L352）、E5-c 剩余一半（**业务源身份** `source_id`/`sourceCode` ＋ `/pipeline` 页不挂 `AnalysisContext`）。
9. **本轮未跑** `isolated`（55）与 `spark`（303）两档；**未**跑任何 IT；**未**连库。

## 8. 结论

- **S3-27 ＝ A 类（实现/加性，读侧消费）**：`netSaleAmount`（契约 v1.4 已发布字段）在**阶段5 页面**获得**首个消费方**——销售趋势图并列柱、销售明细表列、导出 CSV 列，并**首次**为该指标给出**统一限制说明**（`NET_SALE_NOTE`，两视图共用）。
- 契约按程序**先改**（v1.7 追加段），代码后改，**无顺序偏差**；11 门逐门**否**；零新依赖、零连库、零 DDL、零迁移。
- **定向证据**：RED `80/75/5` → GREEN **`92/92/0`**（exit 0）；**档级证据**：default `s327_20260916_def1` 计数 **949／13／106 ＝ 1068 MATCH**、唯一红＝已登记环境性红、`[FAIL exit=7]` 预期；spark 303 沿用 S3-25。
- **不得声称**：页面已渲染（无 DOM/编译器证据）、净销售额可用于真实收入结论（归属期未冻结）、区间净额可用（真过滤未实现）、`GMV ≥ 净销售 ≥ 0` 成立、阶段5/E5-c 已完成。
- **下一候选（滚动顺序）**：`docs/PROJECT_STATUS.md` backlog **L360**「阶段5 未展示 `quality.ruleVersions`」。
