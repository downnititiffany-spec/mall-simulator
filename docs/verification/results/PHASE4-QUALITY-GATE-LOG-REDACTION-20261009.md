# 阶段 4 质量门数据库诊断日志脱敏（2026-10-09）

## 实现

- `AnalysisService.qualityStatus()` 继续将质量门查询失败降级为 `UNKNOWN` 并返回既有 `QUALITY_STATUS_UNAVAILABLE` 告警；日志改为记录运行 ID 与异常类型，不再输出底层数据库异常消息。
- 扩展 `AnalysisServiceTest.qualityStatusReflectsGateResult`：使用 Logback `ListAppender` 捕获日志，确认异常类型可诊断、诊断原文和敏感样式值未写入日志。

## 验证

- 命令：`mvn -o -f analytics-server/pom.xml -pl metric-analysis -am -Dtest=AnalysisServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：42 项，0 failures/errors/skipped；platform-common → metric-analysis reactor `BUILD SUCCESS`。
- 仅离线单测和日志捕获，不含真实数据库、HTTP 或浏览器验证；未访问数据库或 3306。本项不关闭阶段4。

## 扩大回归（同日补充）

- 命令：`mvn -o -f analytics-server/pom.xml -pl metric-analysis -am test`
- 依本次新鲜 Surefire XML 汇总 platform-common 121 + metric-analysis 152 = **273 项，0 failure/error/skipped**；两个模块均 `BUILD SUCCESS`。
- 此依赖模块离线回归仍不含真实数据库、HTTP、浏览器或最终验收。
