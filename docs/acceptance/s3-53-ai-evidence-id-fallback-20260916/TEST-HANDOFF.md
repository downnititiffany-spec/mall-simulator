# S3-53 TEST HANDOFF — AI evidenceId 形状兼容

## 1. 被测版本

- 生产逻辑 commit：`0054870cf9a31905880c4a2ac59d5c915ab0fbbd`
- developer tests commit：`0ebd0e80c567077d66f3edd98e20ee55939b8c77`
- 决策记录 commit：`f1e1205487fea527ae7de5c56581e48bc8cbbb2a`
- 测试方必须针对至少包含上述三者的精确 commit 运行；不得用“当前最新”代替 SHA。

## 2. 模块目标

修复阶段6已登记残余面：当后端没有在 AI 查询响应顶层提供 `evidenceId`，但 `explanation.evidence.evidenceId` 中存在真实 EvidencePackage ID 时，前端此前仍显示“未提供”，AI 建议转决策草稿也无法使用已有证据包 ID。

本轮只做**响应形状兼容**：

1. 顶层真实 `evidenceId` 优先；
2. 顶层缺失/占位时回退嵌套 `explanation.evidence.evidenceId`；
3. 两处都无真实 ID 时保持 `null`；
4. evidence package ID 仍优先于 snapshot 锚点；
5. 前端不得创建、猜测、改写 evidenceId。

不修改后端 AI 契约、不修改 DecisionService 状态机、不修改权限、不修改 API 路径。

## 3. Code Agent — Execution Tester

GitHub 只读。请 checkout/fetch 精确被测 commit 后执行：

```bash
cd web
npm test
npm run build
```

若依赖未安装，按项目现有方式安装依赖；不要修改或提交远端代码。

重点确认：

- 新增 `web/tests/aiEvidenceIdFallback.test.js` 5 例全部通过；
- 既有 `web/tests/aiDecisionDraft.test.js`、`web/tests/context.test.js` 无回归；
- 全量 `npm test` 无新增失败；
- `npm run build` 成功；
- 若现有仓库本身有已知失败，逐条区分“既有”与“S3-53 新增”。

返回格式：

```text
Commit SHA:
Command:
Exit code:
Tests run / pass / fail:
Expected:
Actual:
Evidence/log path:
```

## 4. Codex Work — Adversarial Verifier

GitHub 只读。不要只重复 developer tests，主动寻找反例。允许本地临时改文件、写探针、写测试，但不得 commit/push。

至少攻击以下边界：

1. 顶层与嵌套同时给不同真实 ID：必须稳定选择顶层；
2. 顶层 `unknown` / `UNKNOWN` / 空白，嵌套真实：必须选择嵌套；
3. 顶层真实、嵌套 `unknown`：不得被嵌套覆盖；
4. 两处都无 ID，但 snapshot 真实：草稿必须退到 `suggestionSnapshotId`；
5. 两处都无 ID 且 snapshot 也无效：草稿入口必须保持不可创建；
6. 嵌套 ID 是数字、对象、数组等非字符串：不得被当合法 ID；
7. evidenceId 含首尾空格时，确认当前归一化与 `decisionDraft.isRealEvidenceId` 的行为是否一致，若发现值被意外保留空格请报告；
8. 构造 evidence package ID 后，`buildDraftBody` 不得同时提交 `evidencePackageId` 与 `suggestionSnapshotId`；
9. 现有 `/ai/queries` 顶层 evidenceId 路径必须保持兼容；
10. 检查 `AiAssistant.vue` 是否确实只消费 `buildAiEvidenceContext(...).evidence.evidenceId`，避免页面另有第二套取值导致本修复不可见。

只返回 finding，不修远端代码。每个 finding 至少包含：

```text
Severity:
Affected commit:
Expected:
Actual:
Minimal reproduction:
Evidence:
Why this is a production/design issue rather than a test-only issue:
```

## 5. 已知边界 / 不得越界声称

- 本轮没有真实浏览器、真实 8091、真实 LLM Provider 或 MySQL 验证；
- 本轮不证明 `/ai/explanations` 页面已接线；只证明前端上下文构造器能兼容嵌套 EvidencePackage 形状；
- 本轮不关闭“创建草稿后是否自动跳转/刷新决策中心”的体验项；该项需要独立 UX 设计，不与 evidenceId 修复混在一起；
- 本轮不证明 AI 真实模型调用已验收；
- developer tests 通过不等于独立验证通过。

## 6. 验证通过后的收口动作

Code Agent 与 Codex Work 结果都返回 ChatGPT 后：

1. ChatGPT 判断 finding；
2. 如有实现/设计问题，由 ChatGPT 修改 GitHub；
3. 重新交两方验证；
4. 两方通过后，再由 ChatGPT 更新 `PROJECT_STATUS.md` 的当前事实，避免把“待测实现”提前写成“已验证完成”。
