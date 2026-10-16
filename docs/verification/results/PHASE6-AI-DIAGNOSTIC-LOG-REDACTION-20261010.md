# 阶段6 AI 证据诊断日志脱敏（2026-10-10）

## 修复

`AiController` 在证据包构建失败和 operation audit 写入失败时曾把 `RuntimeException.getMessage()` 原文写入日志；`EvidenceBuilder` 的分类/城市等级维度及商品维度读取失败也有同样行为。数据库/驱动异常消息可能包含连接或查询细节。现统一只记录异常简单类型，不写原始异常消息；接口回退/空证据行为保持不变。

## 验证与边界

- 对 `AiController`、`EvidenceBuilder`、`ExplanationService`、`TextToSqlService` 执行静态搜索，目标文件中未再发现 `log.*getMessage()`。
- `mvn -o -f analytics-server/pom.xml -pl platform-app -am -DskipTests compile`：Reactor 至 platform-app 的 7 个模块均 `BUILD SUCCESS`；platform-app 实际编译 28 个源码文件。
- 未运行测试、真实 Provider、数据库或部署；未连接 3306。仅源码及离线构建证据，不关闭阶段6或总验收。
- 编辑前动态文档备份后缀：`bak-20261010-ai-diagnostic-log-redaction`。
