# 阶段6：AI反馈 API 首版实现（2026-10-10）

## 实现

- 按总控 D-060 批准的 V3.0 §13.2 草案新增 `POST /api/v1/ai/history/{id}/feedback`，要求 `ai:query` 权限；仅接受本人 `EXECUTED` 历史、已完整绑定 source/snapshot/definition/evidence 的记录，所有身份和血缘字段均由服务端从会话及历史行派生。
- 新增独立 `ai_feedback` 追加表，`history_id` 唯一约束限制一条历史一次反馈；reasonCode 使用离散 v1 白名单；响应仅包含反馈 id、rating、reasonCode、createdAt，不返回问题或 SQL。
- V37 加性迁移增加历史血缘列和反馈表。Text-to-SQL 返回新生成 historyId；控制器确认查询快照/source 与证据包完全一致后才回填反馈锚点。旧历史保持空锚点、不可反馈；我的历史列表只返回当前用户自身的 `feedbackSubmitted`。
- AI 页面仅对执行成功且证据锚点完整的历史显示反馈入口；原因使用固定选择项，无自由文本。重复提交返回 409 `AI_FEEDBACK_ALREADY_SUBMITTED`。

## 验证

- Maven 离线定向 Reactor 共 11/11 PASS：`AiFeedbackServiceTest` 4、`AiControllerFeedbackAnchorTest` 1、`AiFeedbackMigrationScriptTest` 1、`ControllerPermissionCoverageTest` 5；0 failure/error/skipped，BUILD SUCCESS。
- Web `node --test tests/aiFeedback.test.js tests/permissionReconcile.test.js tests/aiHistoryConcurrency.test.js`：15/15 PASS。
- `npm run build`：Vite 5.4.21 build success，690 modules。

## 边界

V37 尚未应用到隔离或正式数据库；未进行真实 HTTP/DB 回归、浏览器交互或 3306 连接。报告仅记录实现与离线证据，阶段6、项目阶段门和总验收仍开放。真实 LLM Provider仍受 D-039 限制。代码及契约边界见 D-060 和 `docs/contracts/ai-feedback-contract-draft.md`。