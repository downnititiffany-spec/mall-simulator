# WP-F7b 规约：万级 B 源造数脚本（实施包，agy 只写文件）

## 背景与验收前提
WP-F7 盘点报告（WP-F7-INVENTORY-REPORT.md）已经总控抽验采信（核心论断属实），但有以下勘误，实施时以本规约为准：
1. 报告中 `warehouse-spark/...` 路径有误，实际为 `spark-jobs/src/main/scala/com/graduation/analytics/...`；`MySqlMetricStore.java` 实际在 `analytics-server/metric-analysis` 模块。
2. `sample-data/normal.jsonl` 实为 38 行（与夹具逐字节一致，总控 cmp 亲验）；`fault.jsonl` 实为 1308 B。
3. 报告 §3.6 的期望账数字（pv 4992、gmv 178500.00 等）是**示意占位**，不得写死进脚本——期望账必须由脚本从自己生成的数据实时计算。
4. 总控裁定偏离报告 §3.3：**全部 10,000 行落在同一业务日 2026-09-18**，不掺跨日行（跨日边界已由基线夹具 O8 实证；单日使各层计数恒等式干净闭环）。

## 交付物（全部写在 notes 目录，不进仓库、不改仓库任何文件）
1. `~/workspace/projects/mall-simulator-notes/wpf7-scale/generate_scale_b.py` —— 确定性造数脚本（本包唯一代码交付物）。
2. `~/workspace/projects/mall-simulator-notes/WP-F7b-REPORT.md` —— 简短说明：分布表、字段格式来源、期望账计算口径、自检清单。回复末尾输出一行：WPF7B-DONE

**你只有文件读写工具：不要运行脚本，不要运行任何命令，不要连数据库。** 脚本由总控在验收轮亲跑并核对产出。

## 脚本要求
- Python 3 标准库 only；`random.Random(42)` 固定种子；同一脚本重复运行产出逐字节一致（输出前按 (occur_at, evt_no) 排序、JSON 紧凑分隔符、UTF-8、每行一个事件、文件末尾换行）。
- 命令行：`python3 generate_scale_b.py <output_dir>`，在 output_dir 下写 `scale_events.jsonl` 与 `expected_ledger.json`；成功时 stdout 打印 `SCALE-GEN-OK` 及各类计数；任一自检失败以非零退出并打印失败项。
- 行格式逐项照抄夹具 `fixtures/source-b/fixture-shop-b/normal.jsonl` 与 `fault.jsonl` 的实际字段与枚举（先读这两个文件再写脚本）：信封 `evt_no/state/occur_at/src/rev/tr/data`；`src="fixture-shop-b"`、`rev="1.0"`；`occur_at` 与各 `*_tm` 均为 `2026-09-18T...+08:00` 的 ISO_OFFSET 格式；金额 `*_minor` 全为整数分。
- ID 形态照夹具：`evt_no`=`B-EVT-`+5 位序号（从 10001 起，全局唯一、随时间序递增）、`tr`=`B-TR-`+同序；`buyer_no` 为 19 位数字字符串（100 个买家池，全程复用）；`trade_no`/`pay_no`/`refund_no` 为全局唯一的数字字符串；`sku_no`=`1001`..`1020`；`cat_no`/`brand_no` 照夹具取值形态从已上架商品行派生（行为/订单行引用的 sku 必须已在 ITEM_LISTED 中上架，且 lines 的 unit_minor 与该 sku 的 price_minor 一致）。

## 分布（精确计数，合计 10,000 行）
正常行 9,995：
- MEMBER_JOINED 100（买家池 100 人，每人 1 行）
- ITEM_LISTED 20（sku 1001..1020）
- PAGE_VIEW 5000、SAVE_ITEM 875、CART_ADD 1000（行为行 buyer/sku 从池中抽，sess_no 形态照夹具）
- 订单链 3,000 行：ORDER_PLACED 1200、PAID_OK 1000、BUYER_CANCELLED 200、REFUND_OPENED 300、REFUND_CLOSED 300。
  订单队列构成（恰好）：700 笔已支付无退款 + 200 笔未支付取消 + 100 笔已支付部分退款 + 200 笔已支付全额退款。
隔离行 5：逐类复刻 fault.jsonl 的 5 种错误形态（未登记 state / 金额非整数 / 时间格式非法 / 缺 evt_no / rev 不符），evt_no 编号接续正常行（缺 evt_no 的那行除外），其余字段形态照 fault.jsonl。

## 订单链不变量（脚本内自检，必须全部断言）
- 每单：1–2 个 lines；`line_minor = qty*unit_minor - line_disc_minor`（disc 为 0 或小于行额的整数）；`grand_minor = Σ line_minor`；已支付单 `paid_minor == grand_minor`；全额退款 `refund_minor == paid_minor`，部分退款 `0 < refund_minor < paid_minor`；REFUND_OPENED 与 REFUND_CLOSED 同 refund_no 且金额一致。
- 时间序：同单 placed < paid < refund_opened < refund_closed；取消单 placed < cancelled；全部事件落在 2026-09-18 08:00–23:59 内且整体不递减（排序后）。
- 全局唯一：evt_no（除 fault 缺失行）、tr、trade_no、pay_no、refund_no。

## 期望账（expected_ledger.json，全部由脚本从生成数据计算，禁止写死业务数值）
至少含：total_lines、accepted_count、quarantine_count、per_state_counts、created_orders、paid_orders、cancelled_orders、gmv_yuan（Σ paid_minor/100，字符串两位小数）、refund_completed_yuan、net_yuan（gmv − refund_completed）、aov_yuan（gmv/paid_orders，两位小数）、pv、fav_count、cart_add_count、distinct_behavior_buyers、distinct_paid_buyers、scale_events_sha256（对写出的 jsonl 计算）。

## 红线
不碰 3306/3307、不拨 DeepSeek API、不用 Docker、不改仓库文件、不打印任何密钥。
