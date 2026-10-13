# 阶段14 报告列表元数据投影（2026-10-10）

## 修改

- 报告分页 SQL 显式选取列表 DTO 需要的元数据列，排除 LONGTEXT `artifact_json`。列表仍在只读 `REPEATABLE_READ` 事务中执行 count + rows；详情/制品端点才读取完整 JSON。
- 与上一后端切片合并：detail仅返回字段白名单摘要；artifact/summary读取验证SHA-256、模板和snapshot/source/definition血缘及证据包结构。

## 验证与范围

- `mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`：7/7 reactor modules `BUILD SUCCESS`。
- 未运行测试、未应用 V37/V38、未做真实 HTTP/DB/浏览器验收或部署，3306未连接。列表投影、checksum/错血缘负例和数据库分页隔离仍待运行期验证。
- 阶段14与项目总验收开放；未提交/推送。
