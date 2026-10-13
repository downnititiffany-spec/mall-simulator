# 阶段 5 映射激活 D-030 语义对齐 — 2026-10-06

## 问题与依据

D-030 将 mapping dry-run 定义为 advisory preflight，并明确 `MappingProfileLoader` 是激活/摄取的权威语义。当前 `MappingActivationService` 却把逐行 `violations`、`capabilityGaps` 和汇总值 `activationEligible=false` 当作硬拒绝条件，导致激活门与已批准决策相冲突。

## 实施

- `MappingActivationService.requireActivatable` 现检查画像已被 Loader 接受、样本非空、无系统错误且无 Loader `activationBlocks`；dry-run 的逐行提示、能力提示及其汇总布尔值不单独阻断。
- `MappingDryRunReport` 的说明明确区分预览摘要与激活硬门槛。影响生产正确性的能力缺口仍须由 Loader 显式提升为 `activationBlocks`；报告继续向管理员展示 advisory 信息。
- 激活服务测试覆盖 `activationEligible=false` 但无 Loader 阻断时允许激活，并保留未接受画像、空样本、系统错误和显式 `activationBlocks` 的拒绝用例。

## 验证

- 定向 service 回归：`MappingActivationServiceTest` 16/16、`MappingDryRunServiceTest` 20/20、`MappingProfileLoaderTest` 26/26，合计 **62/62**；三模块 reactor BUILD SUCCESS。
- connection-ingestion 默认完整回归：**389 tests，0 failures，0 errors，2 skipped**；BUILD SUCCESS。
- HTTP 控制器回归：`MappingActivationControllerTest` **8/8**；七模块 reactor BUILD SUCCESS。
- `git diff --check` 通过。测试均为离线/单元或 MockMvc 范围；未访问服务、数据库或 3306，未执行真实映射激活。

## 结论边界

D-030 对齐实现和离线回归已完成。阶段 5 的真实浏览器激活、Loader 生产数据边界与端到端链路仍需按 V3.0 阶段门验证；本结果不构成阶段 5、阶段 2 或产品最终验收通过。

后续安全边界回归（2026-10-06）：在 D-030 的“样本行有必需字段缺失但 profile 本身可被 Loader 接受”用例中，额外用生产同款 `MappingExecutor` 映射该坏行，断言其被隔离且不产出 canonical。该断言与激活通过断言一起通过；加入 `MappingExecutorTest` 后的四类定向套件 **99/99 PASS**（0 failure/error/skipped，三模块 reactor BUILD SUCCESS）。

采集侧回归补证：在真实 `SourceMapper` 激活画像路径增加缺必需字段样本用例，确认实际采集产出 `MAPPING:EMPTY_FIELD@payload.order_id` 隔离结果且无 canonical。定向 105/105 PASS；connection-ingestion 当前全量为 **390 tests，0 failures，0 errors，2 skipped**。此前 389 项全量结果为增加此一条新测试前的计数。该证据不含真实服务、数据库或激活 API。阶段 5/2 及最终验收保持未关闭。

向导契约补证（2026-10-06）：新增 Web 源码回归，锁定 `activationEligible=false` 只显示预览提示，不成为激活按钮禁用条件；`npm test` 399/399 PASS，`npm run build` 680 modules PASS。此为源码/构建证据，不是浏览器或真实服务端激活证据。
