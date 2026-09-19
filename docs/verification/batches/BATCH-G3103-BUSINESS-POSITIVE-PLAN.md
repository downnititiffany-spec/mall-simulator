# BATCH-G3103-BUSINESS-POSITIVE 批次计划（指导书 V3.1 §7 G31-03）

- 指导书条目：§7 G31-03「从『页面不报错』到『能完成业务』」（03.1~03.7）
- 批次 ID：`BATCH-G3103-BUSINESS-POSITIVE`
- 数据域：复用 stage7q1_20260918_152245 四库 3307 域（与 G31-02 同模式；RunId `g3103_20260920_052106` 仅为证据/落盘标签）
- 证据根：`target/v25-it/g3103_20260920_052106/`
- 被测代码：feature/v3-development HEAD（写计划时 `git rev-parse HEAD` 快照见 start-result.json）

## 1. 数据集分工（§7.1）

| 层 | 内容 | 本批落点 |
|---|---|---|
| E0 空态 | 0 条；验空态/无 ACTIVE/未知快照，不伪造数据 | 复用 BATCH-W 空态证据 + 新增未知 snapshotId 探针（错误响应，无假数据） |
| E1 边界 | 已由 G31-01/G31-02 单测与夹具覆盖 | 不重复 |
| E2 业务链 | 来源→计算→发布→页面，可复用已冻结 1011 事件 | stage7q1 存量证据（S20260918_17） |
| E3 正样本（A 腿） | 20~100 条，映射/算法/边界 + 独立 oracle | **本批新建** `fixtures/source-a-e3/e3-events.jsonl`（86 条，跨 09-17/09-18），一次 pipeline（businessTime 2026-09-18）→ 快照 A |
| E4 跨日样本（B 腿） | 数十条跨基期/观察期样本，供决策评估 | **本批新建** `fixtures/source-a-e3/e4-events.jsonl`（71 条，dt 2026-09-21），pipeline（businessTime 2026-09-21）→ 快照 B |

约束遵守：合成行为只来自手写夹具（canonical-event.v1 生成器文件夹具，等价 stage7q1 黄金夹具的构造方式）；不声称 mock-mall 有新端点；库存/成本不编造（不触发库存事件）。

## 2. E3 数值设计（独立 oracle 的输入，完整推导见 fixtures/source-a-e3/ORACLE.md）

- 15 用户 g3u01..g3u15（09-17 注册 g3u01..g3u08）、5 商品 g3p01..g3p05（09-17 product_created；09-18 product_updated 保 dim 名）。
- 09-17（仅入 ODS/DWD 证明跨日装载）：8 行为 + 1 支付单 g3o0001（g3u01, g3p01, 100）+ 1 取消单。
- 09-18：行为 45（view 30 → pv=30/uv=10/dau=10；favorite 6；cart_add 8（5 用户）；cart_remove 1）；交易 12 支付单（gmv=1010，aov=84.1667）+ 2 取消单 + 1 单全额退款 80（net_sale=930，refund_rate=full_refund_rate=1/12=0.0833）。
- 漏斗（03.1）：view_users=10 / intent_users=6 / cart_users=6 / order_users=8 / pay_users=6；intent=0.6、order=8/6=1.3333、pay=0.75、overall_buy=0.6、cart=0.6；「分页稳定」用 hot_product API 分页两次结果一致验证。
- 排行并列（03.1）：heat(g3p02)=heat(g3p03)=11.833421（pv=6,fav=2,cart=2,buy=3 全同）→ 并列按 (buy DESC, product_id ASC) 稳定列位；至少 3 商品（5 个）。
- RFM（03.2）：6 支付用户原值 r_days=0，f=4/3/2/1/1/1，m=400/270/160/70/60/50（全异 → m_ntile 确定性）；r/f/value_group 做「与排序+并列组一致」一致性断言（Spark NTILE 并列分组次序非确定，不假钉）；repeat_rate=2/6=0.3333，period 2026-09-18..2026-09-18（窗口声明随行）。
- F-35 边界：退款归属订单业务日（同日退款），跨日重结不实现、不测试。

## 3. E4 数值设计（B 腿）

- dt 2026-09-21（未来业务日，验证 insufficientReason 下界）；7 注册用户 + 8 行为用户：31 view（pv=31/uv=8）+ 2 favorite + 1 cart_add。
- 13 支付单（7 买家）金额 1200（g3p01×5@100 + g3p02×4@88 + g3p03×4@87），2 单全额退款各 100 → net_sale=1000，aov=1200/13=92.3077，refund_rate=2/13=0.1538。

## 4. 任务→证据映射

| 任务 | 手段 | 证据 |
|---|---|---|
| 03.1 正样本 | 快照 A 后 API + 浏览器：排行≥3 含并列、分页稳定、漏斗可对账（人数与率双查） | `20-*.json`（A cells/漏斗/排行）+ 截图 |
| 03.2 销售/RFM 窗口 | trade/RFM API 原值核对（窗口、退款归属、R/F/M 原值、distinct 人数）；不足场景=无数据日不可计算 | `22-*.json` |
| 03.3 上下文固定 | 五点一致（sourceId/snapshotId/definitionVersion/window/值）+ 慢 A 响应不得覆盖已选 B（Playwright route 延迟拦截；兜底=现有 vitest + 代码引用） | `30-*.json`/截图 |
| 03.4 决策正向 | 4 决策全链 DRAFT→PENDING_REVIEW→APPROVED→IN_PROGRESS→COMPLETED→评价，真实身份（analyst 建、admin 审）真实 API | `40-*.json` |
| 03.5 决策负向 | analyst 越权审批→403+审计 FAILED；伪造 createdBy 忽略；跳状态/重复提交→IllegalDecisionStateException+审计 FAILED；跨源证据（snapshotId=fixture-shop-b 快照 S20260918_15）→ 拒绝（D-034 新守卫）| `50-*.json` |
| 03.6 评价四类 | D1=INSUFFICIENT_DATA（评于 B 发布前，actual==baseline）→补数据（E4）后重评 EFFECTIVE；D2=PARTIAL（pv 30→31, 0.0333）；D3=INEFFECTIVE（refund_rate DOWN，−0.8463）；D4=EFFECTIVE（gmv 1010→1200, 0.1881） | `60-*.json` |
| 03.7 员工验收 | 真实 Chromium：analyst 登录→Overview 筛选→Products→AI 解释→转决策草稿→Decisions 查进度；管理页对 analyst 服务端 403 | `70-*.png` |
| C6 补验 | 浏览器：Login/AiAssistant Enter 提交、chip 单次 POST 网络捕获、DOM click 通道 | `80-*.json` |

决策锚定（03.3 固定语义）：D1 avg_order_value UP target 90；D2 pv UP；D3 refund_rate DOWN；D4 gmv UP。基线全部=A（评估时 A ACTIVE）；B 发布后 D2/D3/D4 评估，D1 先评（INSUFFICIENT）再重评（EFFECTIVE，验证 INSUFFICIENT_DATA 可再 EVALUATING，不冻结）。

## 5. 边界（本批显式声明）

- 不新增 ADS 表（现有 8 张够用）；不改已冻结 DWS/ADS 口径；F-35 同日退款；跨业务日重结不做。
- 3306 永久零接触；口令通道 = V25_IT_* 进程内（零落盘）；platform secret 不入日志。
- push 授权已用尽：仅本地提交，不 push。
- fixture-shop-b（源 2）状态不动：跨源证据用其已发布快照 S20260918_15，不新增源 2 数据。
- 平台生命周期：g3102 平台先按 pidfile 停止 → g3103-start（F2a/F2b 守卫）→ 批内保持运行。
