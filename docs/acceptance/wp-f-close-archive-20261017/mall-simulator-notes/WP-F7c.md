# WP-F7c 规约：万级规模全链实跑（总控亲跑包，非 agy 包）

## 前提（已就绪）
- WP-F7b 已关账：造数脚本 `wpf7-scale/generate_scale_b.py` 经总控双跑亲验（逐字节一致）；产出 `wpf7-scale/run1/scale_events.jsonl`（10,000 行）+ `expected_ledger.json`（GMV 363877.50 / 退款完成 91518.50 / 净额 272359.00 / AOV 363.88 / accepted 9995 / quarantine 5 / sha `21cc97a7…`）。
- 环境：中继在跑、3307 在跑；平台 8091 于 2026-10-17 04:2x 巡检按 G2 口径拉起（env=`~/workspace/toolchain/live-platform.env`，仓库根 CWD，jar=analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar，JVM 带 preferIPv4Stack + UTF-8），健康检查结果见当轮巡检记录。

## 目标
以隔离档案走生产路径全链：落地 scale_events.jsonl → 平台采集 → 流水线 run（八阶段）→ 新快照发布，登记各阶段耗时与计数，并与 expected_ledger 逐项对账。只做证据，不做性能承诺（WP-F7 原义）。

## 隔离与安全边界（硬性）
1. **不得污染既有 ACTIVE 链**：现 ACTIVE 为 WP-F1 发布的 run 4 快照（S 系，档案 local-vm-b / 源 fixture-shop-b）。规模跑必须用独立档案/独立落地目录：优先新建 LOCAL 档案（如 `scale-vm-b`）绑同一源 fixture-shop-b（画像相同，warehouse 前缀作用域隔离，ACTIVE 按 profile 作用域——WP-F7 盘点已实证）；若代码路径证明档案级隔离不足以隔离快照作用域，停下并在巡检记录中说明，改裁定后再跑，禁止直接在现档案上叠跑。
2. 落地文件放独立目录（如 landing/scale-f7c/），不覆盖既有批次落地件。
3. 红线不变：不碰 3306；不用 Docker；不拨 DeepSeek；密钥口令不打印不写文件。

## 执行步骤（总控亲跑，经平台自身 API，顺序遵 WP-N2B-CONTRACTS §0）
1. 登录取 token；查现有源/档案/快照基线（记录 ACTIVE 快照 id 与 metric_value 头条，跑前留档）。
2. 建/复用隔离档案 → test → activate（源已 ACTIVE 则免重复激活；映射已激活且画像校验和未变则复用）。
3. 落地 scale_events.jsonl 到隔离目录 → 触发采集 → 期望批次计数：accepted 9995 / quarantined 5（与账本一致才继续）。
4. 创建流水线 run → 轮询至终态；登记每阶段耗时与总耗时。
5. 发布后对账（逐项）：metric_value 头条 gmv / net / aov / paid_order_cnt=1000 / pv=5000 与 expected_ledger 对比；导出 10 表行数与 ads 层计数一致性；uv/dau 等行为指标按账本 distinct 值核对（distinct_behavior_buyers=100、distinct_paid_buyers=100）。
6. 跑后复查：原 ACTIVE 快照未被误动（隔离档案的新快照只在其作用域内 ACTIVE）；平台健康 200。

## 关账标准
- 批次计数、全链 run 全阶段 SUCCESS、对账逐项一致（或差异有实证解释并登记）→ PROJECT_STATUS 登记（各阶段耗时表 + 对账表）+ FULL-PROGRAM WP-F7 标记关账 + 提交（推送受 PAT Workflows 阻塞时如实登记本地提交）。
- 任一步失败：保留日志与现场，写明失败阶段与原始错误，下一巡检接着处置，不掩盖、不重跑冒充首跑。
