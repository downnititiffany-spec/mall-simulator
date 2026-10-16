# BATCH-S-STAGE7-PRODUCER-MALL-OUTBOX — Verification Plan

> 状态：READY
> Exact source/test SHA：`b850493b11fb71b17db38a59b1d4196d519168a3`
> Branch：`feature/v3-development`
> RunId：`stage7q1_20260918_152245`
> Predecessor：R4 business-chain PASS + R5 harness-cleanup PASS

## 1. Purpose

验证 Stage 7 生产者侧真实链：

`synthetic-data-generator :8092 → REFERENCE_MALL_HTTP → mall-simulator :8090 → order/pay/refund → Outbox → rolling JSONL`

本批不启动 analytics :8091，也不声称完成三程序端到端闭环。

## 2. Exact input

- scenario = `refund_rise`
- seed = `20260401`
- event_count = `600`
- mode = `MALL_API`
- dirty_profile = `none`

该 seed/event_count 已由现有确定性单测证明能够覆盖 order/pay/refund；本批不依赖概率碰退款。

## 3. Safety boundary

1. mall / generator JDBC 只允许 `127.0.0.1:3307`。
2. 数据库只允许 Q-R1 RunId 派生的 mall / generator 库。
3. 口令只从 gitignored credref 读取进子进程环境，不进命令行、不回显。
4. Mall Bearer token 只存在于当前 harness / generator 子进程环境；target 只存 credential_ref 名字。
5. 8090 / 8092 已有监听时拒绝复用。
6. 为保证同一 run-scoped 库可重复验证，商城商品库存只通过受保护 admin HTTP
   `POST /api/v1/admin/products/{productId}/stock` 调整到测试容量，不直写 DB。
7. 不回退 3306，不手写 DML 绕过业务接口。

## 4. Production correction under test

早期 discovery 暴露真实生产缺陷：

- 参考商城按真实商品目录价 × quantity、discount=0 计算订单成交额；
- generator MALL_API 过去仍沿用计划估价/随机折扣作为支付与退款金额；
- 真机出现 `REFUND_EXCEEDS_PAID`。

当前被测 SHA 已累计收口：

- order_created 明细价/折扣/金额/总额重写为商城真实事实；
- order_paid / refund_created / refund_completed 金额与真实订单成交额一致；
- refund_id 重写为商城真实退款 ID；
- generation_event_stat 与 run result 的 GMV/net sale/avg order value 基于重写后的真实事件。
- producer harness 会先收敛历史 Outbox，并把无法发布的历史残留记为 baseline；本次 run 只允许“新增 pending / 新增 failed event”使门禁失败，避免历史坏事件永久污染后续隔离验证；
- mall / generator readiness 都会检查子 JVM 是否提前退出，避免把“端口还没起来”误报成普通 HTTP 连接失败；
- ReferenceMallHttpAdapter 已复用统一的商城 HTTP client/timeout/认证路径，避免探活与真实业务调用存在两套网络实现。

FakeMall 已按真实商城逻辑拒绝超额退款；当前关键定向测试（`GeneratorBoundarySourcePolicyTest`、`MallHttpClientTimeoutGuardTest`、`ReferenceMallHttpAdapterTest`、`MallApiGenerationEngineTest`）全部 PASS，其中 `MallApiGenerationEngineTest 15/15 PASS`。

## 5. Harness evidence requirements

PASS 不能只看 generation_run=SUCCESS。必须同时满足：

- target probe: product/user/order/refund = SUPPORTED；
- generation run = SUCCESS；
- success_count = 600；
- failed_count = 0；
- operation-journal.jsonl 不含 FAILED；
- real HTTP operation 至少出现 createOrder / pay / refund；
- Outbox 显式 drain 后 pendingCount = 0；
- rolling JSONL 至少包含 order_created/order_paid/refund_created/refund_completed；
- **本次** operation journal 的所有 createOrder external_id 都出现在本 attempt 的 order_created；
- 本次 pay 对应订单都出现在 order_paid；
- 本次真实 refund external_id 同时出现在 refund_created 与 refund_completed；
- harness exit 0；
- evidence outcome=PASS。

最后四项关联规则用于防止同一 run-scoped 数据库里的历史 Outbox 事件冒充本次 run。

## 6. Execution

控制端已在 exact SHA 上重新 package mall/generator；default fresh 已确认 analytics **1034 MATCH** / mall **13 MATCH** / generator **111 MATCH**（唯一红仍为既有 manifest patrol）。

真实双服务执行必须使用**能够维持长生命周期后台 JVM 的交互式 PowerShell**。当前 coding-tool 的受管命令执行环境中，`Start-Process` 启动的 mall/generator 子 JVM 会在启动数秒后收到 Windows `0xC000013A`（控制台中断）；同一 mall JAR 在前台探针中可稳定存活，故该现象登记为 controller execution-host 限制，不作为项目服务启动失败。

在交互式 PowerShell 中运行：

~~~powershell
pwsh -NoProfile -File .\scripts\stage7-producer-isolated.ps1 -RunId stage7q1_20260918_152245 -Confirm
~~~

本批不需要用户提供 analytics/admin secret。

## 7. Boundary

PASS 只证明 8092→8090 生产者侧真实业务链、Outbox 与 rolling log。

它不证明：

- rolling log 已被 analytics 8091 消费；
- Flume；
- 三程序全链；
- REMOTE_CLUSTER；
- 浏览器 E2E；
- 真实 LLM provider。

