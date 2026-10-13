# 阶段14 报告详情摘要与制品完整性（2026-10-10）

## 修改

- `GET /api/v1/reports/{id}` 不再将完整报告 JSON 制品作为 summary 返回。服务端现在返回字段白名单摘要：实例模板/窗口/快照、证据计数、质量摘要及模板核心汇总；`GET /artifact` 仍提供完整结构化制品。
- 报告详情摘要与完整制品读取统一经过 SHA-256 校验；checksum 缺失或不匹配时返回稳定 `REPORT_ARTIFACT_CHECKSUM_MISMATCH`，不解析/返回损坏制品。

## 验证与范围

- Maven 离线 Reactor：`mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`，7/7 modules `BUILD SUCCESS`。
- 未运行测试，未应用 V37/V38，未调用真实 HTTP/DB/浏览器或部署，未连接 3306。编译不能证明摘要映射、checksum 负例及真实数据库读取行为。
- 阶段14真实链路和项目总验收保持开放；未提交/推送。
