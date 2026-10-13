# 阶段 4 运维数据质量结果分页（离线验证）

日期：2026-10-06（Asia/Shanghai）

## 实现

- 新增 `GET /api/v1/metrics/quality/page`，支持可选 `runId`、`page`、`size`、`sort`，每页 1–100 条；排序字段限定为 `id`、`createdAt`、`runId`、`ruleCode`、`layer`、`passed`，非 ID 排序以 ID 倒序稳定打散。
- 接口继续要求 `ops:log:view`。分页 count 与当前页行使用 `metaTransactionManager` 的只读 `REPEATABLE_READ` 事务读取。
- 保留旧 `GET /api/v1/metrics/quality?limit=` 数组接口。运维页数据质量表已接入分页、排序和页大小控件；质量上下文状态继续按原最新 20 条结果计算，不受历史分页选择影响。

## 验证

- JDK 17 Maven 定向 reactor：`mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=MetricControllerPaginationHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，2/2 PASS，0 failure/error/skipped，BUILD SUCCESS。MockMvc 覆盖分页元数据、`ops:log:view` 会话、匿名 401 和非法页大小 400；不连接数据库。
- Web：`node --test tests/opsPipelinePagination.test.js`，7/7 PASS。
- Vite：`npm run build`，680 modules transformed，production build 成功。

## 限制

- 未把新版后端部署到运行中的隔离栈；没有真实非空质量记录的 MySQL 查询或浏览器分页证据。
- MockMvc 不验证 `REPEATABLE_READ` 在真实数据库上的一致性；本项只完成实现及离线验证，阶段 4 仍部分完成。
- 编辑动态状态文件前的备份：`docs/PROJECT_STATUS.md.bak-20261006-quality-pagination`、`docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261006-quality-pagination`、`docs/verification/CURRENT_BATCH.md.bak-20261006-quality-pagination`。
