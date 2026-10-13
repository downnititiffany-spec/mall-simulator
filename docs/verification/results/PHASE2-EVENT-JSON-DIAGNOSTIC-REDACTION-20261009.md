# 阶段 2：事件校验诊断原始值脱敏（2026-10-09）

## 变更

`EventContractValidator` 不再把 Jackson parser message、未知事件类型/版本、非法行为枚举或无效金额原文拼进隔离原因。原因保留稳定分类和字段定位（例如 `金额格式违规: price`）。隔离原文仍按既有采集/隔离流程保留在受控原始文件中，本切片没有改变原始数据留存策略。

新增 4 个回归断言，覆盖含 `UNTRUSTED_SECRET_MARKER` 的不完整 JSON、未知事件类型、未支持版本、非法行为类型及非法金额，确认原因不回显输入值且金额诊断保留字段名。

## 验证

命令：

```text
mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am -Dtest=EventContractValidatorRedactionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

定向命令：`EventContractValidatorRedactionTest` 4/4 PASS，0 failure/error/skipped。模块回归命令 `mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am test`：**412 项，0 failure/error、2 skipped**；platform-common 与 connection-ingestion reactor `BUILD SUCCESS`。

## 证据边界

这是离线单测，不证明真实数据库隔离记录、HTTP 响应或浏览器行为；没有启动服务或连接数据库，未访问 3306。只证明当前校验器原因字段不拼接这些原始值，不代表阶段 2 或阶段 4 完成，也不改变项目总体验收状态。
