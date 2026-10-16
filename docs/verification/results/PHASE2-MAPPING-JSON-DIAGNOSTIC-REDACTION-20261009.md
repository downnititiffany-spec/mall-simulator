# 阶段 2 映射/画像 JSON 解析错误详情脱敏

日期：2026-10-09
范围：`MappingExecutor` 原始事件解析、`MappingProfileLoader` 画像解析及其定向回归。

## 变更

- 两处解析失败不再把 Jackson 异常消息放进 `MappingIssue.detail`，统一返回稳定的 `INVALID_JSON`。解析异常消息可能包含输入 token，而映射 dry-run 会把 issue 详情作为 API 报告字段返回。
- 原始事件的完整文本仍保存在隔离用 `MappingOutcome.raw`，原始 SHA-256 仍按原文本计算；仅移除公开错误详情中的输入回显，不丢失隔离证据。
- 画像解析失败同样不回显画像输入片段。

## 验证

命令：

```powershell
mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am '-Dtest=MappingExecutorTest,MappingProfileLoaderTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`MappingExecutorTest` 37/37、`MappingProfileLoaderTest` 26/26；共 63/63，0 failure、0 error、0 skipped，三模块 reactor `BUILD SUCCESS`。

新增断言用 `UNTRUSTED_SECRET_MARKER` 作为非法输入，确认事件/画像错误详情只含 `INVALID_JSON`，事件原始文本仍可从隔离 outcome 读取。

## 边界

仅为离线单元测试证据；未调用 dry-run HTTP、真实认证、数据库或浏览器。没有访问 MySQL 3306，没有启停服务或修改持久化数据。本变更不改变 JSON 解析判定、隔离路由或映射语义；阶段 2/总体验收不据此关闭。
