# WP-F7c 实跑报告（总控亲跑，2026-10-17 04:59–05:08 CST）

## 执行概要
- 隔离档案 `scale-vm-b`（id=3，新建）绑源 fixture-shop-b（id=2），独立落地根 `/home/hatch/workspace/live/landing-scale-f7c`；档案 test 四项适用全 PASS → activate 成功（期间档案 2 转 DISABLED，跑后已复激档案 2 为 ACTIVE 并验健康 200）。
- 落地文件 sha256 = `21cc97a7…` 与 WP-F7b 账本一致，10,000 行。
- 采集批次 3（ing-20261017045943-92bd3b80）：status=QUARANTINED、recordCount=**9995**、quarantineCount=**5**、errorCount=0 —— 与账本逐项一致。
- 流水线 run 5（DAILY_CORE，businessDate 20260918）：**8 阶段全 SUCCESS**，发布快照 **S20260918_5**（档案 3 作用域 ACTIVE）；总耗时约 496 秒（04:59:48→05:08:04）。

## 阶段耗时（阶段行起止）
| 阶段 | records | 耗时 s |
|---|---|---|
| WAIT_LANDING | 9995 | 0.1 |
| INIT_SCHEMA | 43 | 30.5 |
| LOAD_ODS | 10035 | 76.6 |
| BUILD_DWD | 8508 | 112.5 |
| BUILD_DWS | 100 | 46.2 |
| BUILD_ADS | 160 | 64.4 |
| QUALITY_CHECK | 12 | 51.2 |
| PUBLISH_METRIC | 320 | 114.4 |

发布门全过：MP_ADS_ROWS_DB_MATCH（10 表行数 overview1/sale_trend1/funnel4/active_trend1/hot20/conversion20/user_profile100/quality4/category5/region4）、MP_METRIC_VALUE_DB_MATCH 14/14、MP_ACTIVE_SNAPSHOT 指向 S20260918_5。

## 对账（发布值 vs 规模账本）——两项结构性发现
规模账本（scale-only）：gmv 363877.50 / 退款 91518.50 / 净额 272359.00 / paid 1000 / pv 5000 / cart 1000 / fav 875 / uv·dau 100。
发布值（S20260918_5）：gmv **365160.30** / 净额 **238087.15** / aov 362.26 / paid **1008** / pv **5008** / cart **1001** / fav **876** / uv·dau 100。

**发现 1（数仓按 (源, dt) 累计，档案不隔离数仓层）**：档案隔离只作用于快照 ACTIVE 作用域；数仓命名空间按源前缀（fxsb_*）+ dt 分区累计。LOAD_ODS 输出 10035 = 9995 + 既有 40（基线 B 两批次 accepted 总数）；gmv 差 +1282.80、paid +8、pv +8、cart +1、fav +1 恰等于基线 S20260918_4 全量。故发布值 = 规模 + 基线累计，此项差异有完整实证解释。原 ACTIVE 链未受污染：档案 2 的 S20260918_4 跑后仍为其作用域 ACTIVE（metric_snapshot 实查）。

**发现 2（真实缺陷：退款按订单行数倍增，立 WP-F7d）**：按累计口径退款应为 91518.50 + 398.00（基线）= 91916.50，发布隐含退款 = 365160.30 − 238087.15 = **127073.15**，多出 **35156.65**。总控取证：Σ(每笔退款 × 该单行数) = 126675.15，126675.15 + 398.00 = 127073.15 **分毫不差**；且 126675.15 − 91518.50 = 35156.65 与多出额一致。机制定位：`TradeDwdJob` 产出订单行粒度明细，每行携带整单级 `refund_amount`（OrderTradeCompiler 按 trade 编译）；`DwsSql.tradeDay` 对行粒度表 `SUM(refund_amount)` → 按行数倍增。GMV 不受影响（amount 为行级）。基线数据退款单均为单行，未暴露；万级数据含多行退款单而暴露。退款订单数口径正常（refund_rate 0.2996 ≈ 302/1008 = 300 规模 + 2 基线）。

## 关账裁定
- WP-F7（规模冒烟）按纲领原义（走完整链 + 登记耗时与计数一致性 + 只做证据）**关账**：全链在万级规模跑通，耗时与计数已登记，差异全部实证解释。
- 发现 2 转 **WP-F7d** 修复包（见 WP-F7d.md），含修复后复跑验收预言值：同数据同累计口径下 gmv 365160.30 不变、退款应为 91916.50、**净额应为 273243.80**、aov 362.26 不变。
- 证据原件：/tmp/f7c_evidence.json（run 5 全阶段证据）、/tmp/f7c_run.log、metric-staging/S20260918_5/_export.json（gitignored 运行件）。
