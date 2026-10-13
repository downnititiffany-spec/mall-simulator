# 阶段4 决策分页真实合成记录复核（2026-10-10）

- task-owned RunId：`cloud_261010_005249_1c73ec`；沿用已确认的隔离栈 `127.0.0.1:8091`，没有连接 3306，也没有切换或修改 RuntimeProfile。
- 按用户授权，通过平台正式 `POST /api/v1/decisions` 创建两条标题含 `PAGINATION-SYNTH` 与 RunId 的合成 DRAFT（ID 1、2）。草稿描述明确为分页验收用途；没有提交、审批或执行。
- 真实管理员分页验收：`scripts/verify-decision-pagination.ps1 -BaseUrl http://127.0.0.1:8091 -PageSize 1`，结果 **PASS**：total=2、totalPages=2；`id desc`、`id asc`、`createdAt desc` 契约有效；两种排序的跨页边界均严格有序，无重复 ID。验证器成功注销临时会话。
- ingestion batch 仍没有可追溯输入；没有调用会消费 ACTIVE Landing 文件并推进断点的 `/api/v1/ingestion/runs`，因此其非空分页门槛仍未完成。此前真实只读请求显示 total=0。
- 范围：这是隔离环境两条决策 DRAFT 的真实 API 与分页证据；不代表业务链路或阶段4整体验收。无直接 SQL 业务写入、无 profile 写入、无 3306 接触。阶段4和总控验收保持开放。
