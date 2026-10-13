# 阶段14 报告制品血缘与结构 fail-closed（2026-10-10）

## 修改

- 报告制品读取在 SHA-256 通过后，继续核对 JSON 模板码/版本、evidence snapshot/definitionVersion、lineage snapshot/sourceId 与 report_instance 元数据一致。
- 同时要求证据包核心集合和质量字段具有预期 JSON 结构；缺字段、错误类型、错血缘统一返回 `REPORT_ARTIFACT_INVALID`，详情摘要和完整制品端点共用该守卫。

## 验证与范围

- Maven 离线 Reactor：`mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`，7/7 modules `BUILD SUCCESS`。
- 未运行测试、未应用 V37/V38、未做真实 HTTP/DB/浏览器验收或部署，3306未连接。结构、错血缘和checksum负例尚无运行期回归证据。
- 阶段14和项目总验收保持开放；未提交/推送。
