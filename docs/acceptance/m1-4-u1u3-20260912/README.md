# M1-4 U1/U3 真机正向取证（2026-09-12 10:02–10:14，总控执行）

**结论一句话**：M1-4 欠的「大规模真机 SUCCESS」与「真机 refund 终态 SUCCESS」两项**已取证**——单次 `MALL_API` 真机运行 `m1-4-u1u3-live-20260912-v1-20260912-101242-c0ba` 终态 `SUCCESS`、`success_count=640 / failed_count=0`，操作日志 641 行**全部 `status=OK`、`supported=false` 0 条、无一条「库存不足」**，商城侧真实新增 272 单 / 53 用户 / 88 支付 / **6 条 `COMPLETED` 退款**。

**不得由此宣称**：U11（真机 HTTP 调用计数）已取证、`behavior`/`reset_state` 能力已具备、平台采集与流水线已端到端（本轮**未触发** 8091 任何采集/流水线）、M1-4 整体 DONE（S5 页面/CLI 仍未开工）。逐条见 §8。

---

## 1. 授权与边界

| 项 | 内容 |
|---|---|
| 依据 | D-041「批准给自建商城补库存（追加性写入，非破坏性），使大规模真机 SUCCESS 可取证；执行时点排在 P1-06 之后，与 8090/8092 起停一并做」 |
| 本轮边界 | 只做**追加性写入**：库存追加、生成器运行、商城真实下单/支付/取消/退款；不改任何源码、不跑采集与流水线、不动 `analytics_meta`/`analytics_metric`、不清理 `generator_meta` 现场、不删任何历史证据 |
| 不在边界内 | 平台侧任何写入（实测见 §7 未改动核对）、商城目录/用户治理、`source_registry` 与 `runtime_profile` 变更 |
| 时序 | 09:40–09:53 补库存（`m1-4-restock-20260912`）→ 10:05 CLI 建计划 → 10:06 首次运行尝试（被 409 拦下，留档）→ 10:11 生成器带凭据重启 → 10:12:42 正式运行 → 10:13 后态补收 |

## 2. 本轮四处偏差（如实登记，不改写任何既有结论）

| # | 偏差 | 事实与理由 |
|---|---|---|
| ① | **补库存范围由 D-041 的三件扩到 29 件** | 理由：`productsFor(640)=32`，池内 5 件（`2096441182850224129/…2913138689/…2976053249/…3038967809/…3038967810`）**根本没有 `inventory` 行**（商城库存接口也不建行，实测 `version` 无变化），不可买；其余 27 件 `available_qty` 多在个位到十几，撑不住 640 事件预算。接口与档位完全同 D-041（`POST /api/v1/admin/products/{id}/stock`、`changeType=inbound`），**只扩大商品数**。原始证据：`raw/u1u3-restock-20260912-100436.txt`（29 次调用全部 `code=OK`、`available_qty→500`、`reserved_qty` 不变、`version` +1；`1001/1002/1003` 保持 09:40 补后的 1000 未重复追加）；无 token 对照 `POST /admin/products/1004/stock` → **HTTP 401** |
| ② | **8092 带环境变量重启（10:11:55，pid 10412→24692）** | 生成器 `credential_ref=GENERATOR_TARGET_TOKEN` 是**环境变量名**，由 **8092 进程** `System::getenv` 解析。原 8092 是 `start-all.ps1` 启动的，**脚本不注入该变量** ⇒ 所有路由判 `UNDETERMINED` ⇒ `GenerationRunService` 预检直接 409。重启时 `non_terminal=0`（无在跑运行）、jar 未重编译（26,216,592 B / mtime 2026-09-11 21:04:06 不变）、**8090（pid 41228）与 8091（pid 47132）全程未动**。凭据值只注入进程环境，**不落盘、不打印**（S4b 的 `verify/tmp-mall-token.txt` 已因同一原因被删）。证据：`raw/u1u3-gen-cred-restart-20260912-101153.log` |
| ③ | 增量口径取 `generation_run.started_at`（`2026-09-12 10:12:42`） | 不用「POST 返回时刻」（10:12:42.081）；两者相差 99 ms，取库内真实起点更保守 |
| ④ | 时间：10:06:20 的**首次运行尝试失败**并留档 | 不是代码问题，是我自己的脚本缺陷（`Invoke-RestMethod` 对 409 **不抛异常**、返回 `$null`，脚本于是拿空 `run_id` 轮询 60 次 404，且未断言 HTTP 状态码）。该次**对商城零影响**（前后计数一致）。证据：`raw/u1u3-exec-20260912-100620.log` + `raw/u1u3-mallapi-409-invalid-state-20260912.txt`。修法：改用 `curl.exe -s -w '|HTTP=%{http_code}'` + 硬断言（非 200 即 `exit 2`），该修法随后生效 |

## 3. 执行链（可复现）

| 步 | 动作 | 结果 |
|---|---|---|
| 1 | 商城管理员登录取 token（32 字符，只注入进程环境、不落盘） | `POST /api/v1/auth/login` → `code=OK` |
| 2 | 生成器 CLI 建计划（**无 HTTP 入口建计划**，CLI 是契约定下的入口） | `--generator.cli=plan-append --plan-id=m1-4-u1u3-live-20260912 --mode=MALL_API --target-id=118 --scenario=normal --seed=20260912 --start=2026-09-01T00:00:00+08:00 --end=2026-09-08T00:00:00+08:00 --event-count=640` → `generation_plan` id **216**。**首次真机使用 CLI 建计划成功**。注意：命令行里 `-Dfile.encoding=UTF-8` 必须**加引号**（PowerShell 会拆参数，实测报 `找不到或无法加载主类 .encoding=UTF-8`）。证据：`raw/u1u3-cli-planappend-20260912-100543.log`（同目录 `…-100531.log` 是未加引号的失败尝试） |
| 3 | 目标探针（能力与凭据门禁） | `POST /api/v1/targets/118/test` → **HTTP 200**、`reachable=true`、11 条代表路由全 `HTTP_ANSWERED(200)`、`product/user/order/refund/admin=SUPPORTED`、`behavior/reset_state=UNDETERMINED` |
| 4 | 发起运行 | `POST /api/v1/generation-runs` body `{"plan_id":"m1-4-u1u3-live-20260912","version":1}` → `HTTP 200`、`{"runId":"m1-4-u1u3-live-20260912-v1-20260912-101242-c0ba"}` |
| 5 | 轮询终态 | 3 次（3 s 间隔）到 `SUCCESS`：10:12:42 → 10:12:48（约 6.2 s 完成 640 事件 + 真实调用） |
| 6 | 后态补收 | 商城计数/增量/退款明细/下单分布/库存逐件/journal 汇总/run-report/落地区/三程序身份 |

## 4. 预注册预测 vs 实测（预测写于发起运行之前，见 `raw/u1u3-exec-20260912-101242.log` §0）

| 预测 | 内容 | 实测 | 判定 |
|---|---|---|---|
| P1 | 终态 `SUCCESS` | `SUCCESS`（库内 `generation_run` id=154，10:12:42.182→10:12:48.387） | ✅ |
| P2 | `failed_count=0`、`success_count=640` | `success_count=640 / failed_count=0`（RunView 全文 + 库内行 + run-report 三处一致） | ✅ |
| P3 | `createOrder` 尝试 ≈274（区间 200–340） | **272** | ✅ |
| P4 | `user_registered=53`、`product_created=32`、`order_created≈274` | 53 / 32 / **272** | ✅（前两项与引擎公式逐字相符） |
| P5 | 至少 1 条 `refund_completed`（预期 2–4） | **6 条** | ⚠️ 判据（≥1）命中，**区间预测未命中**（高于上界，如实登记） |
| P6 | 商城增量 `mall_order≈+274`、`mall_user≈+53`、`refund(COMPLETED)≥+1` | +272 / +53 / +6 | ✅ |
| P7 | 无任何 `INSUFFICIENT_STOCK` | journal 全部 `OK`，`detail` 命中「库存不足/INSUFFICIENT」**0 条** | ✅ |
| P8 | 落地区出现当前小时文件（若商城定时发布器在跑） | `landing/events/2026091210.jsonl` **10,869 B（10:04:40）→ 493,481 B（10:12:50）** | ✅（发布器在跑，本轮事件已发布） |

**脚本自身两处缺陷（证据文件内可见，不改写）**：① §2 轮询打印 `success=`/`failed=` 为空——RunView 字段名是 `success_count`/`failed_count`，我按 `successCount` 取；权威值以 §3 全文与 §5 库内行为准。② `raw/u1u3-pre-*.txt` §B 商品 id 打印为空——我用了 `$p.id`，真实字段是 `productId`（该文件保留原样，正确清单见 `raw/u1u3-post-*.log` §11）。③ 外层 `Select-Object -First 95` 在管道满足后**掐断了执行中的脚本**（§8 之后未落盘）⇒ 同轮后态另存为 `raw/u1u3-post-*.log` 并注明是续录。

## 5. U1 判据逐条（大规模真机全成功）

**判据**：大规模真机运行终态 SUCCESS、`failed_count=0`、商城侧有等量真实写入。

| 判据 | 观测 | 证据 |
|---|---|---|
| 终态 SUCCESS | `{"status":"SUCCESS","success_count":640,"failed_count":0,"checksum":"c381c857…3636cc","cancel_requested":false}` | `raw/u1u3-exec-20260912-101242.log` §3；库内 `generation_run` id=154 §5 |
| 事件统计 | `order_created=272`（21,393.04）、`order_cancelled=183`、`order_paid=88`（6,454.40）、`user_registered=53`、`product_created=32`、`refund_created=6`、`refund_completed=6`（368.00） | §6 + `run-report.json` §13 |
| 真机调用零拒绝 | journal **641 行 / 6 类操作 / 非 OK 0 条 / `supported=false` 0 条**，全部 `status=OK`：`createSyntheticUser 53`、`listProducts 33`、`createOrder 272`、`pay 88`、`cancel 183`、`refund 12` | `raw/u1u3-journal-status-and-payment-20260912-101416.txt` §A |
| 制品可核 | `events-0001.jsonl` 271,019 B / 640 行 / sha256 `C381C857…3636CC`（HTTP 返回、库内 `generation_artifact`、磁盘重算三处一致）；`operation-journal.jsonl` 136,256 B / 641 行；`run-report.json` 3,610 B | exec §7/§8 |
| 商城真实写入 | `mall_order 5057→5329`（+272）、`order_item 5058→5330`（+272）、`mall_user 1851→1904`（+53）、`payment 4135→4223`（+88，窗口内 `paid_at` 亦为 88）、`refund 183→189`（+6，全 `COMPLETED`） | `raw/u1u3-post-*.log` §9/§10 |
| outbox 逐类对齐 | `order_created 1664→1936`（+272）、`order_cancelled 428→611`（+183）、`order_paid 1233→1321`（+88）、`refund_created/completed 49→55`（+6/+6）、`user_registered 458→511`（+53）、`stock_reserved 1664→1936`（+272）、`stock_released 428→611`（+183）、`behavior 869708` **不变**（能力 UNDETERMINED，符合预期）、`stock_changed 32` 不变（本轮未补库存） | post §9 |
| 订单状态自洽 | `CANCELLED=183 + PAID=82 + REFUNDED=6 + CREATED=1 = 272`，与 journal 的 `cancel 183 / pay 88`（88 = 82+6 退款单来自已支付单）一致 | post §10 |
| 库存守恒（关键完整性信号） | 逐件 `available_qty + reserved_qty` 与补库存后读数**逐件相等**（1001 `924+176=1100`；1004 `485+82=567`；2001 `495+57=552`；3004 `496+20=516`；8004 `499+11=510`）⇒ 272 单 / 183 取消 / 88 支付 / 6 退款**没有漏记或重复扣减**；全表 `total_avail 18079→17900`、`sold_out=0`、`min=92` | post §11 + `u1u3-journal-status-and-payment…` §D |
| 下单分布 | 29 件商品有真实下单（池内 32 件）；头部 `1001 99 单/198 件`、`1002 30/71`、`1003 18/39`、`1004 16/32` | post §10 |
| 平台侧未被触碰 | `ingestion_batch 40`、`pipeline_run 40`、`metric_snapshot 9`、`file_checkpoint 105` 与运行前**完全一致**；`product 43`、`inventory 38` 行不变（`product_created` 事件是**对齐**商城既有目录商品，不新建商品） | 本轮末次核对（见 §7） |

## 6. U3 判据逐条（真机 refund 终态 SUCCESS）

**判据**：真机运行中至少一条退款经真实 HTTP 走到终态成功，且商城库内为终态。

| refund_id | order_id | status | amount | reason | created | journal |
|---|---|---|---|---|---|---|
| 2098595630867312642 | 2098595630737289217 | `COMPLETED` | 19.26 | wrong_item | 10:12:43.082 | seq107 `POST /api/v1/mall/orders/{orderId}/refunds` `OK`；seq108 `POST /api/v1/mall/refunds/{refundId}/complete` `OK` |
| 2098595641101414402 | 2098595640975585281 | `COMPLETED` | 43.08 | wrong_item | 10:12:45.518 | seq333/334 同路由 `OK` |
| 2098595642334539778 | 2098595642267430913 | `COMPLETED` | 141.00 | quality_issue | 10:12:45.822 | seq365/366 `OK` |
| 2098595648080736258 | 2098595648013627393 | `COMPLETED` | 63.71 | user_regret | 10:12:47.181 | seq509/510 `OK` |
| 2098595651335516161 | 2098595651272601601 | `COMPLETED` | 58.09 | late_delivery | 10:12:47.959 | seq599/600 `OK` |
| 2098595652639944706 | 2098595652509921282 | `COMPLETED` | 42.86 | quality_issue | 10:12:48.270 | seq635/636 `OK` |

- 6 条退款的 `external_id` 均为**商城真实 `refund_id`**（雪花 ID），不是账本自造；同批订单在商城侧状态为 `REFUNDED=6`。
- 第二次调用 `…/refunds/{refundId}/complete` 的 `detail` 为「复用已完成退款」⇒ 创建即已终态，二次调用是幂等复核，不是把状态推成成功的动作（**这一点如实登记**：终态由商城侧产生，不由适配器"补写"）。
- 事件侧 `refund_created=6 / refund_completed=6`（金额 368.00 = 19.26+43.08+141.00+63.71+58.09+42.86，逐条相加相符）。

## 7. 未改动核对（本轮副产物）

| 对象 | 运行前 | 运行后 |
|---|---|---|
| `analytics_meta.ingestion_batch` / `pipeline_run` / `metric_snapshot` / `file_checkpoint` | 40 / 40 / 9 / 105 | **40 / 40 / 9 / 105（同值）** |
| `mall_simulator.product` / `inventory` 行数 | 43 / 38 | 43 / 38 |
| `generator_meta.generation_run` 行数 | 75 | 76（只多本轮一行） |
| 三程序进程 | 8090 pid 41228（09:40:49）· 8091 pid 47132（09:14:59）· 8092 pid 10412（09:48:10） | 8090 pid 41228 · 8091 pid 47132 **（未重启）** · 8092 pid **24692**（10:11:55，见 §2 偏差②） |
| `landing/events` | 57 文件 / 404,907,418 B | 57 文件 / **405,390,030 B**（同小时文件被商城发布器追加，非新文件） |

## 8. 未取证 / 不可宣称（本轮结束后仍然成立）

1. **U11（真机 HTTP 调用计数）未取证**：journal 中 33 条 `listProducts` 是否等价于真实 GET 次数**未判定**（D12「journal 缺 `real_http`/`local_accounting` 字段」未修）；本轮只测得「641 行 journal 全部 OK」，**不等价于** 641 次真实 HTTP。
2. **`behavior` / `reset_state` 仍 `UNDETERMINED`**：本轮 `behavior` 事件 0 条、未做库存重置；run-report 自述「因能力缺口跳过 0 次」指本轮事件构成不涉及该能力。
3. **5 件无库存行商品不可买**、3 件池内商品本轮零下单——「不可买」是**代码级 + 补齐算术**结论（`MallBusinessService.reserveStock` 的 `ge` 守卫 + 库存接口不建行），**未做逐件下单负向探针**。
4. **令牌有效期、凭据轮换、target 配置版本回滚**未测；`credential_ref` 指向的环境变量一旦缺失即整体 409（无降级、无跳过），属**有意设计**，但其运维含义（谁注入、何时轮换）尚无脚本承载（见 F-17）。
5. **不构成 T2/E5 证据**：本轮未触发平台采集与流水线，未产生新的 `ingestion_batch`/`metric_snapshot`；T2（55 条黄金链）与员工可用页面（E5）与 M1-4 S5 仍未开工。
6. **M1-4 整体仍为 `DONE_LIMITED`**：U1/U3 关闭，但 S5（页面/CLI 之外的交付面）、U11、D10（失败路径 `event_stats`）、D12（journal 账本语义）等未关闭。

## 9. 文件清单（本目录）

| 文件 | 字节 | 用途 |
|---|---|---|
| `raw/u1u3-pre-20260912-100237.txt` | 7,329 | 运行前基线：三程序身份/健康、目录顺序、库内计数、落地区、生成器现场。**§B 商品 id 列为空**（脚本用了 `$p.id`，真实字段 `productId`），保留原样并在 §4 登记 |
| `raw/u1u3-restock-20260912-100436.txt` | 6,352 | 偏差①：29 次 `inbound` 追加库存（含无 token 401 对照、逐件前后 `avail\|reserved\|version`、outbox `stock_changed`） |
| `raw/u1u3-cli-planappend-20260912-100543.log` | 3,349 | CLI `plan-append` 建计划成功（id 216） |
| `raw/u1u3-cli-planappend-20260912-100531.log` | 145 | 同命令未加引号 `-Dfile.encoding` 的失败留档（1 条类名报错） |
| `raw/u1u3-mallapi-409-invalid-state-20260912.txt` | 556 | 偏差④根因证据：`INVALID_STATE` + 三路由 `UNDETERMINED` + 凭据引用名（**不含凭据值**） |
| `raw/u1u3-exec-20260912-100620.log` | 8,646 | 首次失败尝试：§0 预注册预测、POST 返回空、60 次 404、零影响的商城读数 |
| `raw/u1u3-gen-cred-restart-20260912-101153.log` | 3,673 | 偏差②：`non_terminal=0` → 停止旧实例 → 带环境变量重启（新 pid 24692）→ 探针 200 全绿 |
| `raw/u1u3-exec-20260912-101242.log` | 9,659 | **正式运行**：§0 预测、POST+HTTP 断言、终态 RunView、制品清单、库内运行行/事件统计/制品行、磁盘 sha256（§9 之后被外层 `Select-Object -First` 掐断，续录见下） |
| `raw/u1u3-post-20260912-101329.log` | 13,620 | 同轮后态续录：商城计数、增量、6 条退款明细、订单状态与下单分布、池内 32+11 件库存逐件、journal 汇总、`run-report.json` 全文、落地区、三程序身份 |
| `raw/u1u3-journal-status-and-payment-20260912-101416.txt` | 2,235 | 补充判据：journal 按 `operation×status` 分组（非 OK 0 条）、`external_id` 去重 363、`payment` 真实时间列 `paid_at` 增量 88、库存守恒逐件核对 |

脚本：本轮脚本已随证据入库到 `scripts/`——`u1u3-run.ps1`（其 `pre` 阶段产出运行前基线 `raw/u1u3-pre-*.txt`；`run` 阶段假定 `code/data` 信封、与生成器真实响应（裸 `{"runId":…}`）不符，已被取代，保留作演进留痕）、`u1u3-restock.ps1`、`u1u3-gen-cred-restart.ps1`、`u1u3-exec.ps1`、`u1u3-post.ps1`（工作副本在 gitignored 的 `.verify/`）。**脚本不含凭据值**：登录响应只打印 `code` / `role` / token 长度，凭据仅以进程环境变量形式传给 8092，不落盘、不打印。

## 10. 复现命令要点

```powershell
# 0) 前置：8092 必须以环境变量方式持有商城管理员令牌（值不落盘、不打印）
$env:GENERATOR_TARGET_TOKEN = <32 字符商城 token>   # 仅注入 8092 进程环境
# 1) 建计划（仅 CLI 有入口；-Dfile.encoding 必须加引号）
java '-Dfile.encoding=UTF-8' -jar synthetic-data-generator/target/*.jar `
  --spring.main.web-application-type=none `
  --generator.cli=plan-append --plan-id=<plan> --mode=MALL_API --target-id=118 `
  --scenario=normal --seed=<seed> --start=2026-09-01T00:00:00+08:00 --end=2026-09-08T00:00:00+08:00 --event-count=640
# 2) 能力/凭据门禁
curl.exe -s -X POST http://127.0.0.1:8092/api/v1/targets/118/test
# 3) 发起运行（必须断言 HTTP 码；Invoke-RestMethod 对 4xx 不抛异常）
curl.exe -s -w '|HTTP=%{http_code}' -X POST http://127.0.0.1:8092/api/v1/generation-runs `
  -H 'Content-Type: application/json' -d '{"plan_id":"<plan>","version":1}'
# 4) 轮询 + 后态：见 .verify/u1u3-exec.ps1、.verify/u1u3-post.ps1
```

**环境**：8090 商城 / 8091 平台 / 8092 生成器；`mall_simulator`、`analytics_meta`、`generator_meta`；MySQL `-N --raw --default-character-set=utf8mb4`（`-B` 会打表头、`2>$null` 会吞未知列错误——本轮两次踩到）。

## 11. 对既有记录的影响

- M1-4 的 U1/U3 从「未取证」转为**已取证**（run id `m1-4-u1u3-live-20260912-v1-20260912-101242-c0ba`），M1-4 行仍为 `DONE_LIMITED`（理由见 §8.6）。
- 新增事实 **F-17**：`scripts/start-all.ps1` 不注入 `GENERATOR_TARGET_TOKEN` ⇒ 由该脚本启动的 8092 永远无法执行 `MALL_API`（能力全 `UNDETERMINED` → 409）。这是**交付物可复现性缺口**，不只是本轮操作障碍；本轮不改脚本，只登记。
- 新增事实 **F-18**：证据脚本三类坑（外层 `Select-Object -First N` 掐断子进程致证据截断；`Invoke-RestMethod` 对 4xx 静默返回 `$null`；`mysql` 未知列错误被 `2>$null` 吞掉致"零行"假象）——都造成过**看起来正常的空证据**，列入脚本规范。
- §3.9 的「未取证①」在本轮被关闭；§3.9 原文不改写，新增 §3.10 承接。
