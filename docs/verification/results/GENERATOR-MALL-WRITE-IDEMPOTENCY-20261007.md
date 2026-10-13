# 生成器参考商城写操作幂等键扩展（2026-10-07）

## 范围

在此前参考商城下单幂等切片上，扩展稳定业务键到生成器发出的用户注册、行为、下单、支付、取消和退款命令。键按 `runId + virtualUser + logicalActionIndex` 的确定性动作身份派生；退款适配为两次 POST 时使用 `:apply`、`:complete` 子键。参考商城适配器在相应写请求上传递 `Idempotency-Key`。第二商城不因此获得幂等能力或自动重试声明。

参考商城为除下单以外的写操作新增事务幂等账本 V9：按键记录操作名、规范化请求摘要和结果；同操作同请求回放既有结果，不同操作或请求复用同键返回 `IDEMPOTENCY_CONFLICT`。下单继续使用 V8 `mall_order` 唯一键与请求摘要机制。未带键的既有调用仍保持兼容。

## 验证

环境：Windows，JDK 17，Maven 离线依赖。

- `synthetic-data-generator`: `mvn -o test` **130/130 PASS**，0 failures/errors/skipped。覆盖默认模块套件及 fake HTTP/Mockito 测试。
- `mall-simulator`: `mvn -o test` **18/18 PASS**，0 failures/errors/skipped。默认配置不运行标记为 `it` 的真实数据库集成测试；MySQL 幂等集成类仅经 `testCompile` 编译。
- `git diff --check` 通过；Git 显示部分文件存在 LF/CRLF 转换提醒，本轮未批量改写行尾。
- 未连接数据库、未应用 Flyway V8/V9、未启动/重启服务、未执行真实商城写入，MySQL 3306 未访问。

## 限制与未验证项

- 未证明目标 `base_url` 当前部署的 Mall 版本已应用 V8/V9；也未在隔离 MySQL 上验证迁移、事务回放、唯一键竞争或并发结果。
- 参考商城以外的目标端协议/状态核实能力仍未知。命令携带键不代表远端尊重该键；第二商城不能据此声称幂等。
- 实现没有提供无条件 exactly-once 语义。超时结果未知时仍须按目标商城已验证的查询/幂等协议核实；目标不支持的能力应报告 `UNSUPPORTED`。
- 本结果只记录离线实现和模块测试，不关闭 V3.0 §3.3、阶段 7 或项目最终验收。

## 动态文档备份

编辑前备份：`docs/PROJECT_STATUS.md.bak-20261007-mall-write-idempotency`、`docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261007-mall-write-idempotency`、`docs/verification/CURRENT_BATCH.md.bak-20261007-mall-write-idempotency`。
