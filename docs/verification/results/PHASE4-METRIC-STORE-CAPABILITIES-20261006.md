# 阶段 4 MetricStore 能力契约补齐

日期：2026-10-06  
范围：`MetricStore` 的实际能力描述及受权限保护的只读 API；不代表整体验收。

## 改动

- `MetricStore.Capabilities` 显式返回 Store 类型、快照/维度能力、最大行数、实际查询超时和即时可用性。
- `MySqlMetricStore` 报告 `mysql`、支持快照、不支持维度过滤；`maxRows=null` 明确表示当前 Store 查询没有声明行数上限，不伪称 AI SQL 的独立 200 行限制适用于全部 Store 查询。
- 查询超时取自已配置的 `metricReadJdbcTemplate.getQueryTimeout()`；可用性通过同一只读源执行 `SELECT 1` 即时判定。
- 新增 `GET /api/v1/metrics/capabilities`，要求 `dashboard:view` 权限。返回布尔可用性，不带健康探测异常细节；健康接口和现有分页/指标响应保持兼容。
- Web 权限对账将该服务端能力端点列入未接前端原因清单。

## 验证

- `MySqlMetricStoreCapabilitiesTest`：2/2 PASS，覆盖正常和不可用健康探测；确认诊断原文不进入能力对象。
- `MetricControllerPaginationHttpTest` + `ControllerPermissionCoverageTest`：8/8 PASS，七模块 Maven reactor BUILD SUCCESS；验证 JSON 能力字段、未认证 401 与端点权限映射。
- `node --test tests/permissionReconcile.test.js`：7/7 PASS。
- 均为 JDK 17 离线 mock/HTTP harness 与源码权限对账；没有部署新 JAR、访问真实数据库或执行浏览器认证请求。隔离运行栈仍需重新部署后再做 API 实服验证。

结果：设计 §12.6 的能力描述现有契约与受保护读取入口；阶段 4 整体仍部分完成。
