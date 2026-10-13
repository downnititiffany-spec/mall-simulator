# 参考商城共享幂等键空间

日期：2026-10-07

## 修复

V8 将 `Idempotency-Key` 唯一约束放在 `mall_order`，V9 将其它商城写操作登记到 `mall_request_idempotency`；分开的唯一键空间允许同一键被不同操作分别占用。

- 下单现先在通用账本登记 `createOrder`、请求摘要和结果 ID，再执行订单业务效果；原 V8 订单唯一键仍作为额外保护，并兼容旧 V8 订单重放。
- V9 建立账本后，把升级前已有 V8 `mall_order` 的非空幂等键、请求摘要和订单 ID 回填到共享账本。摘要为空的异常存量会使迁移失败，不静默漏登记。
- 新增回归覆盖同键跨操作冲突、成功操作结果账本、请求摘要冲突及 V9 回填 SQL 的静态守卫；隔离数据库 IT 另加“订单键不能复用于注册”的断言。

## 验证

- 定向离线测试：`MallBusinessServiceIdempotencyTest` 与 `MallIdempotencyMigrationScriptTest`，**8/8 PASS**。
- 商城默认 Maven 套件：**22/22 PASS**，0 failure/error/skipped，`BUILD SUCCESS`；JDK 17、离线依赖。
- 默认构建编译了全部 21 个测试源码，包括 `MallOrderIdempotencyIT`，但默认 Surefire 配置排除 `it` 标签，未执行该真实数据库 IT。
- `git diff --check` 通过（仅有仓库其它既存文件 LF→CRLF 提示）。

## 限制

- V9 未应用到数据库；未验证迁移回填、并发事务或目标部署版本。
- 真实 IT 需要隔离 MySQL 3307 及其独立管理员/应用凭据。本轮未连接数据库，3306 零接触。
- 本实现不证明第二商城协议或端到端 exactly-once；阶段 2/7 不关闭。
