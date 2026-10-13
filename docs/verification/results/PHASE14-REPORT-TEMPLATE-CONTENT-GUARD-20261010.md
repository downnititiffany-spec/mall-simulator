# 阶段14 报告模板制品结构守卫（2026-10-10）

## 修改

- 制品读取除通用 EvidencePackage/schema 与 snapshot/source/definition 血缘校验外，现逐模板验证 `templateContent` 结构。
- OPS_WEEKLY 要求7个日项、销售/行为缺失日期数组且窗口匹配实例；ORDER_ANALYSIS、PRODUCT_ANOMALY、USER_OPERATIONS 校验必需数组/摘要字段；DECISION_EFFECT 校验决策终态、ID和报告窗口。OPS_DAILY 允许其既定空 templateContent。
- 任一缺失、错类型或窗口不一致均返回 `REPORT_ARTIFACT_INVALID`；详情摘要与完整制品共用验证。

## 验证与范围

- Maven 离线 Reactor：`mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`，7/7 modules `BUILD SUCCESS`。
- 未运行测试、未应用 V37/V38、未做真实 HTTP/DB/浏览器验收或部署，3306未连接。各模板有效/畸形 JSON 运行期负例仍待验证。
- 阶段14及项目总验收开放；未提交/推送。
