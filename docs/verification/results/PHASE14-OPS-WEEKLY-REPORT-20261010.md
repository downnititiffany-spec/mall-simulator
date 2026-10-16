# 阶段14 D-060 经营周报实现切片（2026-10-10）

## 实现范围

- `POST /api/v1/reports` 对 `OPS_WEEKLY` 接受 `fromDate` / `toDate`，要求恰好连续七个自然业务日，且 `toDate` 必须等于显式 `businessDate` 和所选快照业务日；持久化报告窗口。
- 固定同一已发布 snapshot/source 调用现有 `AnalysisService.overview`，读取 ADS 日级销售与活跃趋势。制品逐日列出订单数、销售额、净销售额、DAU、行为量；分别列出销售缺失日和行为缺失日，不补零。
- 窗口订单、销售额、净销售额、行为量只汇总其日级可加总字段。周客单价由窗口销售额/订单数重算，订单数为零或金额证据缺失时留空。DAU为逐日 distinct，不跨日求和；没有虚构周 distinct 人数。净销售额不表述为最终到账收入。
- Reports 页面新增周报可用项，默认窗口为所选快照业务日及此前六日，并在报告列表展示起止日期。

## 验证与限制

- `mvn -o -f analytics-server/pom.xml -pl ai-decision,platform-app -am -DskipTests compile`：7/7 Reactor modules `BUILD SUCCESS`。
- Vite production build 成功，输出至唯一 TEMP 路径；没有覆盖 `web/dist`。`git diff --check` 通过。
- 未运行测试、未应用 V37/V38、未调用真实 API/数据库/浏览器、未部署；未连接 3306。未证明实际 ADS 七日覆盖、真实数据质量结果或报告制品回读。
- 经营周报代码/页面切片完成，阶段14完整验收仍开放；`DECISION_EFFECT` 仍未实现，D-039真实 Provider不变。
