# 阶段14 报告 API 隐藏内部制品 URI（2026-10-10）

## 修改

- `ReportSummary` 不再将内部 `artifactUri` 序列化到创建、列表和详情响应；数据库字段继续由服务端持有。当前 Web 页面未使用该定位符。
- API 仍返回所需的模板/快照/窗口/owner/status/checksum元数据和摘要；完整 JSON 仅通过 owner-scoped artifact 端点读取。

## 验证与范围

- Maven 离线 Reactor：`mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`，7/7 modules `BUILD SUCCESS`。
- 未运行测试或真实 HTTP/DB/浏览器验收；未应用V37/V38；没有停止/重启当前隔离整栈，3306未连接。
- 阶段14与项目总验收开放；未提交/推送。
