# 阶段14 D-060 报告证据读取错误稳定化（2026-10-10）

## 实现

- `EvidenceService.build` 的底层运行时读取异常映射为 `REPORT_EVIDENCE_UNAVAILABLE`；EvidenceService 原生 `PlatformBizException` 业务错误码保持不变。
- `OPS_WEEKLY` 的 overview ADS 趋势读取异常映射为 `REPORT_WEEK_EVIDENCE_UNAVAILABLE`。
- `USER_OPERATIONS` 的用户聚合读取异常映射为 `REPORT_USER_EVIDENCE_UNAVAILABLE`。
- `PRODUCT_ANOMALY` 原有商品证据读取 fail-closed，错误码保持 `REPORT_PRODUCT_EVIDENCE_UNAVAILABLE`。
- 这些路径避免将可识别的证据暂不可用误报为未分类服务端 500；不会用空数组或零值伪造成功。

## 验证与限制

- Maven 离线 Reactor：`mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`，7/7 modules `BUILD SUCCESS`。
- Web Vite production build 成功，输出到唯一 TEMP 路径；`git diff --check` 通过。
- 未运行测试、未调用真实 API/数据库/浏览器、未部署；V37/V38未应用，未连接3306。代码编译不证明错误码运行时映射或端到端报告制品回读。
- 阶段14完整验收及项目总验收保持开放。
