# 阶段 4 AI 审计分页实现记录

日期：2026-10-06

## 改动

- 新增 `GET /api/v1/ai/audit/history/page` 和 `GET /api/v1/ai/audit/calls/page`，原 `/audit/history?limit=`、`/audit/calls?limit=` 数组接口继续保留。
- 分页参数校验：page >= 1，size 为 1–100；排序字段和方向均采用白名单。非 ID 排序添加 ID 降序次级排序以稳定分页。
- 总数查询与行集查询置于只读 `REPEATABLE_READ` 事务，返回 items/page/size/total/totalPages/sort。
- Ops 页面问答历史与调用审计使用服务端分页，页大小 10/20/50，可选择白名单排序，导出范围为当前页。

## 验证

- Maven 平台 reactor（七模块）：`AiControllerAuditPaginationTest` 2/2 PASS、`MetricControllerPaginationHttpTest` 1/1 PASS，BUILD SUCCESS。
- Web：`node.exe --test tests/opsPipelinePagination.test.js` 5/5 PASS。
- Web production build：Vite 5.4.21，680 modules，BUILD SUCCESS。
- `git diff --check` 无空白错误；仅有既有 CRLF/LF 转换提示。

## 限制

- 本轮未将新版本部署到运行栈，也未做真实浏览器验证。
- 未使用非空真实 `ai_query_history` / `ai_call_log` 数据验证跨页、排序和并发一致性；分页 SQL 只有离线控制器测试覆盖。
- 本条只记录代码与离线验证，不关闭阶段 4，也不等于产品验收。
