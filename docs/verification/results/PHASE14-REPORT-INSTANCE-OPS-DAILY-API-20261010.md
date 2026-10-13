# 阶段14：业务报告实例 OPS_DAILY API 首个纵向切片（2026-10-10）

## 实现

- 按 D-060 批准的 §14.4 契约新增报告实例创建、本人分页列表、本人详情和制品读取 API，均受 `dashboard:view` 保护；列表摘要不含制品 JSON，详情/制品访问使用 creatorId 过滤。
- V38 加性迁移增加 `report_instance` 表。报告实例固定绑定服务端读取的 source、snapshot、definitionVersion 和业务日，保存 EvidencePackage 序列化结果及 SHA-256；请求不能指定 owner 或血缘字段。
- 首个完整模板仅实现 `OPS_DAILY`，缺失/不匹配的业务日或证据锚点 fail-closed；AI 摘要因 D-039 未满足而拒绝。其余五个已批准模板返回 `REPORT_TEMPLATE_NOT_READY`，不会用通用 EvidencePackage 冒充模板完成。
- 服务端支持 owner-only 分页与排序白名单；空结果保持 page=1、totalPages=0，不发负 offset 查询。
- Web 新增“业务报告”入口，使用共享已发布快照选项生成 OPS_DAILY，并提供本人列表分页、证据详情和完整制品查看；未就绪模板明确禁用展示，分页信封校验覆盖空列表语义。

## 验证

- 离线 Maven Reactor 定向测试：`ReportInstanceServiceTest` 5、`AiFeedbackServiceTest` 4、`AiControllerFeedbackAnchorTest` 1、`AiFeedbackMigrationScriptTest` 1、`BusinessReportMigrationScriptTest` 1、`ControllerPermissionCoverageTest` 5；合计 17/17 PASS，0 failure/error/skipped，`BUILD SUCCESS`。
- Web `npm test`：447/447 PASS（含报告分页响应和权限端点对账）；首轮全量测试发现 AI 历史按钮旧结构守卫与当前含反馈控件的标记不一致，已将按钮样式迁至 scoped CSS 并更新结构/禁用语义守卫，复跑全绿。
- Vite 5.4.21 production build 转换 694 modules 成功，产物写入 TEMP 新目录；未覆盖 `web/dist`。
- `git diff --check` 退出码 0，无空白错误；有既存工作树的 CRLF/LF 转换提示。

## 未验证与边界

- V37/V38 尚未应用到数据库；未执行真实 HTTP、DB、认证会话或浏览器报告工作流。本轮未连接 MySQL 3306。
- 仅 `OPS_DAILY` 后端模板完成证据装配；另外五种模板仍未实现。当前 Web 页面尚无真实服务/浏览器验证。迁移部署、owner 隔离实库验证、下载格式和保留策略仍需后续按契约落实。
- 本结果是实现与离线测试证据，不关闭阶段14、阶段6、阶段门或项目总验收。AI 真实 Provider 继续受 D-039 限制。
