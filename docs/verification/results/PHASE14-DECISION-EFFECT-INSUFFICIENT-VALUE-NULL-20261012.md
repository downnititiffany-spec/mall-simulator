# 阶段14 DECISION_EFFECT 未充分数据值空值语义修复（2026-10-12）

## 根因

决策评价服务在 `INSUFFICIENT_DATA` 时会保存部分观测的 `actualPeriodValue` 和 `actualSampleCount`，但不设置可用于分级的 `actualValue`。报告模板之前把 `actualValue` 为空时一律回退到 `actualPeriodValue`，会将部分窗口聚合值显示成已评价的实际值。

## 修复

- DECISION_EFFECT 为 `INSUFFICIENT_DATA` 时，报告 `actualValue` 固定保留 null，不再从 `actualPeriodValue` 回退。
- 仍保留评价中实际记录的 `actualSampleCount`；不把缺值与已有部分样本混为一谈。
- 其它评价状态的历史兼容回退行为不变。
- 测试夹具将 `actualPeriodValue=120.00`、`actualSampleCount=3` 与 `actualValue=null` 并存；修复前反例失败，修复后通过。

## 验证与范围

`mvn -o -pl platform-app -am '-Dtest=ReportInstanceServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：15 tests，0 failures/errors/skips；七模块 Reactor BUILD SUCCESS。仅离线服务单测，未重启当前 RunId 平台以加载新类，未进行认证 HTTP/真实报告/浏览器/DB验收；3306未连接，阶段14继续开放。