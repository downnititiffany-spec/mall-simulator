# V3.0 §14.4 业务报告实例 API 契约草案

状态：总控已于 2026-10-10 按 D-060 批准作为实现契约；不构成 V3.0 设计变更；实现仍须遵守本文副作用、权限和证据边界。

## 1. 目标与约束

将已发布指标快照生成可重读、可追溯的业务报告实例。数字和图表由确定性程序生成；AI 只可补摘要、建议和限制，不能创建或改写指标值。报告不得跨 source、snapshot 或 definitionVersion 拼接事实。默认生成绑定请求时唯一 ACTIVE RuntimeProfile 的当前 ACTIVE 快照；读取历史报告或生成归档快照报告必须显式提供 snapshotId，并执行现有 `dashboard:view` 权限。未知、未发布或不匹配快照 fail-closed，不回退到当前 ACTIVE。

报告是只读分析制品，不执行营销触达、调价、补货、用户操作或决策流转。没有库存/成本证据时不得输出库存风险结论；用户运营报告只输出聚合人群统计，不含用户级个人数据。

## 2. 六种模板

| templateCode（提案） | 名称 | 必需证据 | 明确限制 |
|---|---|---|---|
| `OPS_DAILY` | 经营日报 | 指定业务日概览、销售、行为和质量 | 只说明观察到的变化，不作因果归因 |
| `OPS_WEEKLY` | 经营周报 | 连续七个完整业务日的概览/销售/行为聚合与每日数据完整性 | 比例必须由程序用分子分母重算；不得平均日比例 |
| `ORDER_ANALYSIS` | 订单专题 | 固定快照内订单量、支付/退款与金额专题 | 不包含用户个人信息；退款归属遵循已冻结指标定义 |
| `PRODUCT_ANOMALY` | 商品异常 | 商品转化、热度及质量证据 | 缺库存/成本时不推断库存风险或利润 |
| `USER_OPERATIONS` | 用户运营 | RFM/活跃/复购等已发布聚合指标 | distinct 指标依定义计算，不跨日累加日级 UV/DAU |
| `DECISION_EFFECT` | 决策效果 | 已完成决策任务及其已计算效果评价 | 只复述对比结果，不声称行动导致变化 |

模板需保存不可变 `templateVersion`。每个模板的指标码、单位、时间窗口、空值、质量门槛和文字段落还需由总控/API 清单冻结；此表不替代这些详细定义。

## 3. 建议 API

### 创建

`POST /api/v1/reports`

```json
{
  "templateCode": "OPS_DAILY",
  "templateVersion": "1",
  "snapshotId": "S...",
  "businessDate": "2026-10-10",
  "includeAiNarrative": false
}
```

`OPS_WEEKLY` 使用 `fromDate` / `toDate` 且必须恰好覆盖七个完整日；其它模板使用其冻结的最窄业务窗口。客户端不能提交 sourceId、definitionVersion、creatorId、artifactUri、checksum 或报告状态；服务端从固定快照和登录会话派生这些值。`includeAiNarrative` 缺省为 false；开启前需满足 D-039，且模板回退与真实 provider 结果必须可区分。

若全部读取均为已发布 ADS 且在同步请求预算内，可同步返回 READY 报告；若未来证据读取超出该预算，则按 V3.0 §16.1 返回 taskId 和可轮询状态。不得在接口中等待 Spark 作业完成。

### 列表、详情与制品

- `GET /api/v1/reports/page?page=&size=&sort=`：当前用户可见报告的分页列表，不跨用户泄露私有 AI 草稿；排序白名单和稳定 ID 并列键由 API 清单冻结。
- `GET /api/v1/reports/{id}`：返回元数据和结构化摘要，不内嵌任意文件路径。
- `GET /api/v1/reports/{id}/artifact`：鉴权后返回固定格式制品；响应格式/下载头由 API 清单冻结。

建议读写都要求 `dashboard:view`；若区分创建权限，应由正式权限矩阵裁决并同步前后端守卫。创建者来自登录主体，不接受请求体覆盖。

## 4. 持久化字段提案

`report_instance` 至少保存：id、templateCode/templateVersion、sourceId、snapshotId、definitionVersion、业务窗口、promptVersion（无 AI 时为空）、creatorId、status、artifactUri（仅服务端受控相对引用）、artifactChecksum（SHA-256）、createdAt/updatedAt、stableErrorCode。索引建议覆盖 `(creator_id, created_at, id)` 与 `(source_id, snapshot_id)`。迁移必须加性新增，不修改已发布迁移；DDL、状态字典、保留/清理策略须另行评审。

Artifact 初版建议采用版本化 JSON（事实、维度、质量、限制、可选 AI 文本）并由服务端模板渲染；不得存原始商城行、凭据或服务端绝对路径。checksum 对最终不可变字节计算。是否同时产出 HTML/PDF、文件存储位置、保留周期与失败重试语义待裁决。

## 5. 建议状态及错误边界

建议最小状态 `READY` / `FAILED`；若按异步生成，追加 `PENDING` / `RUNNING`。重复请求是否返回已有报告由幂等键策略冻结；不能将未支持的重复抑制称为 exactly-once。未知/未发布快照返回稳定业务错误并不生成实例；AI 失败允许按模板回退，但必须将 `providerUsed` 和 fallback 原因记录到实例或其审计关联中。

## 6. 需要总控/API 清单冻结的决策

1. 是否采用上述六个 templateCode 和模板边界；逐模板冻结指标、输入窗口、质量要求及空态。
2. 是否采用三条建议 API 路径，是否同步生成、分页排序字段、响应 DTO 和错误码。
3. 权限采用统一 `dashboard:view` 还是增加 `report:create` / `report:view`；报告所有者和管理员各自可读范围。
4. Artifact 格式（JSON/HTML/PDF）、物理存储、保留期限、重试和幂等键语义。
5. 是否允许可选 AI 摘要及其 prompt/provider 审计字段；真实调用仍须满足 D-039。

批准前，本草案仅供评审；实现需另立工作包，补 Controller/Service/Mapper、加性迁移、权限覆盖、模板一致性和真实快照证据。

## 实施补记（2026-10-10｜DECISION_EFFECT）

- 创建决策效果报告时，客户端提供 `decisionId`；不要求也不接受客户端为此模板指定 `snapshotId`、`businessDate`、source、definition 或评价值。服务端只允许已完成且已有最终评价的任务，并读取其最新 `DecisionEvaluation`。
- 报告锚点从该评价派生：完整结果锚定 actual 窗口末快照；`INSUFFICIENT_DATA` 锚定 baseline 窗口末快照。报告元数据窗口为基线窗口起始日至实际窗口结束日；制品保留评价记录中的前后窗口、样本数、定义版本和快照引用。
- 前后窗口引用是同一决策评价已经冻结的比较证据集合，并非允许报告任意混拼快照。服务端校验 task/evaluation 的 sourceId、runtimeProfileId、metric definition 与锚点快照证据一致；旧评价缺这些字段或合法锚点时拒绝生成。
- 报告服务不调用决策 evaluate、不重算改善率/分级、不改任务状态；只复述已保存结果并声明前后变化不构成因果证明。此项明确当前 `POST /api/v1/reports` 的 DECISION_EFFECT 请求字段和受限快照语义。
