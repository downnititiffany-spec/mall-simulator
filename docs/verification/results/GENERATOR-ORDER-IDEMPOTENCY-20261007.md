# 生成器参考商城下单幂等回放（2026-10-07）

## 范围

实现 V3.0 §3.3 中可在本项目第一方参考商城验证的下单幂等切片：生成器为每个订单逻辑动作派生稳定键；参考商城下单接口接收该键；商城按键唯一约束及请求摘要避免请求已提交但响应丢失后再次扣库存或写入 Outbox。

键值为 `SHA-256(runId + NUL + externalUserId + NUL + deterministicEventId)` 的小写十六进制表示。事件 ID 的序号由生成器确定性序列产生，因此同一 run 的同一逻辑动作可重放；runId、用户或动作 ID 不同则键不同。

商城将 `idempotency_key` 与 `request_hash` 保存在 `mall_order`。同键同请求返回已有订单 ID；同键不同请求返回稳定业务码 `IDEMPOTENCY_CONFLICT`。订单、订单项、库存预扣和 Outbox 仍在同一商城事务中。唯一键碰撞分支通过 `SELECT ... FOR UPDATE` 读取并发已提交订单。

生成器 `SafeHttpRetry` 只对携带非空 `Idempotency-Key` 的 POST 开放有限重试，且沿用完全相同的请求键；限于已有暂时性网络异常和 HTTP 502/503/504 分类，最多 2 次尝试。未带该头的写请求保持单发。头部仅由参考商城下单调用添加。

## 验证

环境：Windows 11，JDK 17.0.12，Maven 3.9.14，离线依赖。

- `synthetic-data-generator`: `mvn -o "-Dtest=SafeHttpRetryTest,MallApiIdempotencyKeyTest,ReferenceMallHttpAdapterTest" test`：**14/14 PASS**，0 failures/errors/skipped。覆盖真实 localhost fake HTTP 的头部传递与缺省行为、带键 POST 暂时性 503 后同键重试、不带键写请求不重试、键确定性与身份隔离，以及现有适配器探测测试。
- `mall-simulator`: `mvn -o "-Dtest=MallBusinessServiceIdempotencyTest" test`：**2/2 PASS**，0 failures/errors/skipped。Mockito 验证同键同请求返回旧订单且不插入新订单、同键不同请求以 `IDEMPOTENCY_CONFLICT` 拒绝。
- `mall-simulator`: `mvn -o -DskipTests package`：`BUILD SUCCESS`，编译主代码与测试代码并打包，跳过测试。
- 完整默认模块回归：`synthetic-data-generator` 执行 `mvn -o test` **129/129 PASS**；`mall-simulator` 执行 `mvn -o test` **16/16 PASS**，均 0 failures/errors/skipped。商城 POM 默认排除 `it` 标签，因此这些结果未运行真实数据库 IT。
- `git diff --check`：通过（注意到仓库既有多文件行尾属性提示，不据此改写它们）。

## 限制与未验证项

- Flyway V8 追加迁移尚未应用；没有连接商城数据库、启动/重启任何服务或调用真实商城。
- 没有验证 MySQL 并发事务/唯一键竞争行为；虽然冲突读取采用 `FOR UPDATE`，仍须在隔离实库验收。
- 第二商城及支付、取消、退款、行为等其它 MALL_API 写操作仍单发，也没有目标端幂等或状态查询协议证据。不能声称所有写操作 exactly-once，也不能把本切片记作 §3.3 或阶段 7 验收完成。
- 未触碰 MySQL 3306。
