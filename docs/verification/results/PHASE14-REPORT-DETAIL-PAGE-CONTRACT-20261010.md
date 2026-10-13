# 阶段14 报告详情与分页契约实现（2026-10-10）

## 修改

- `GET /api/v1/reports/{id}` 返回受字段白名单限制的结构化摘要；完整证据继续由独立 `/artifact` 接口返回。
- 摘要与完整制品读取均校验持久化 JSON 的 SHA-256；checksum 缺失或不符返回 `REPORT_ARTIFACT_CHECKSUM_MISMATCH`，不解析或返回损坏制品。
- 报告分页 count 与 rows 使用只读 `REPEATABLE_READ` 事务，和决策列表分页保持一致，避免并发创建期间返回互相矛盾的总数/行集。

## 验证与范围

- `mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`：7/7 reactor modules `BUILD SUCCESS`。
- 未运行测试、未应用 V37/V38、未做真实 HTTP/DB/浏览器验收或部署；3306未连接。编译不能证明 checksum 负例或数据库并发一致性。
- 阶段14和项目总验收开放；未提交/推送。
