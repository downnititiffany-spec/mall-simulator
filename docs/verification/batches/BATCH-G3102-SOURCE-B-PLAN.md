# BATCH-G3102 · 第二来源 fixture-shop-b（G31-02，02.1～02.6）执行计划

> 依据：V3.1 指导书 §6（`D:\Develop_code\GraduationProject\docs\guidance\项目完整实施指导书 V3.1.md` 行 134-170）。
> 被测基线：本地 `feature/v3-development` HEAD `c6968f2`（push 授权已用尽，全程仅本地提交）。
> 环境边界：仅 3307 隔离数据域（stage7q1 四库）；3306 永久冻结零接触；平台 secret 仅进程 env。

## 1. 任务范围与验收对照

| 任务 | 交付物 | 本批执行方式 | 验收（指导书原文） |
|---|---|---|---|
| 02.1 | A/B 输入、字段映射、oracle、hash 清单 | 夹具+画像+ORACLE.md（§2 清单）；本计划即 hash 清单载体 | 逐事件预期与金额/人数独立算出，不调用待测计算器生成 expected |
| 02.2 | B 注册、画像预览/激活 | 02.2 运行期 | processed=accepted+quarantined+systemErrors；空样本不可激活；hash 漂移 409 |
| 02.3 | B 采集→ODS→DWD/DWS/ADS→MySQL | 02.3 运行期 | 真实任务 ID/日志/分区/制品；source 和版本全程一致 |
| 02.4 | A/B 并存与切源 | A-B-A 三相切换（§5） | A-B-A 切换无旧图残留；同 ID 不串表/快照/缓存/AI 证据 |
| 02.5 | 最小管理员接入向导 | Vue 管理页新增接入向导（选源→受控样本→预览→激活） | 不要求编辑服务器文件；复用现有 API（D-027） |
| 02.6 | 平台独立性 | 停源后历史指标可读 + 停源时效警告 | 关闭商城/生成器后历史指标可读；来源停机显示时效警告，不假报最新 |

## 2. 02.1 制品清单（hash 清单）

| 制品 | 路径 | SHA256 | 说明 |
|---|---|---|---|
| B 画像 v2 | `analytics-server/source-profiles/fixture-shop-b.v2.json` | `3ee7d8fae3a4a5057696105019bfd7dc475fb67c34e234f18624ff1c1e848da1` | 8 类事件全映射；激活以三方向 checksum 为准 |
| B 正常集 | `fixtures/source-b/fixture-shop-b/normal.jsonl` | `783a7609a8a315d7c2258d83a817edfaab629f5dc6f131ca26da5a58704ffb7e` | 38 行（36 行 D 日 + 2 行次日语义） |
| B 故障集 | `fixtures/source-b/fixture-shop-b/fault.jsonl` | `626a76171f7ccad598e762c41bec532a9bf65a20d1dd2fddc1a77bcfb0a37216` | 5 行，5 类独立映射违规 |
| 独立 oracle | `fixtures/source-b/fixture-shop-b/ORACLE.md` | — | 人工推导，全部预期值不经被测计算器 |
| A 金样本 | `target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/mall-landing/events/2026091819.jsonl` | `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc` | 1011 行，BATCH-W 已验 PASS |

02.1 验收声明：ORACLE.md §4 全部金额/人数由手算（sale=59.80+59.80+298.00+298.00+119.60+89.70+298.00=1222.90；refund=100.00+298.00=398.00），RFM 桶号按 AdsSql NTILE 排序键逐人推导（§4.4 注），未调用被测代码生成 expected。夹具事实另经独立 python 脚本复核（38 个 evt_no 唯一、Σ行金额=grand=paid、dt 分布 36+2）。

## 3. 02.2 注册与预览/激活（隔离 3307）

前置：平台起于 8091，`PLATFORM_*` 全量注入（scripts/assert-platform-env.ps1 守卫）；`platform.mapping.sample-root` 指向本批样本目录（D-028）。

1. `POST /api/v1/sources`：sourceCode=`fixture-shop-b`，displayName 带 fixture 标记，ingestMode=FILE，profilePath=`analytics-server/source-profiles/fixture-shop-b.v2.json`，timezone=Asia/Shanghai，currency=CNY，status=DRAFT，warehousePrefix=`fxsb`（D-023）。
2. dry-run normal（sampleRef=`fixture-shop-b/normal.jsonl`，limit=100）：预期 eligible=true、0 隔离、覆盖率 38/38。
3. dry-run fault：预期 5 quarantined / 0 accepted / 0 systemErrors；invariant processed=accepted+quarantined+systemErrors；单独合跑 normal+fault 时 processed=43。
4. 负例：空样本文件 → activationEligible=false 且 reason=processedCount=0（零事实不判可激活）；dry-run 后修改画像文件使 checksum 漂移 → activate 返回 409。
5. `POST .../mappings/activate`（reportId+expectedProfileChecksum 三方向一致）→ `POST /api/v1/sources/{id}/activate`（lockActive→requireSourcePrefix→SOURCE_PROFILE_INVALID 检查）。

## 4. 02.3 链路校验键（对照 ORACLE.md §4）

- fxsb_ods：dt=20260918×36、dt=20260919×2、source_system 全 `fixture-shop-b`。
- fxsb_dwd：behavior 10 行 / order 9 行（final_paid_flag=1 计 8）。
- fxsb_dws：trade_day 7 单/4 买/1222.90/824.90/174.70；funnel 4/2/4/4；region tier1/2/3=179.40/387.70/655.80（净 179.40/287.70/357.80）；user_trade_period 四人值。
- fxsb_ads 8 张：overview pv8/uv4/dau4/order7/sale1222.90/refund_rate 2/7/full 1/7/repeat 0.5；hot_product rank 1002>1001>1003；user_profile 四人段位（U1 一般发展/新用户、U2 一般挽留、U3 一般保持、U4 重要发展）；data_quality 4 规则全 passed；ADS_STAGING_PRESENT 8/8。
- 跨源同 ID：`95d20dd2…`（A=order_created，B=behavior view）与 `c6556173…`（A=stock_changed，B=behavior view）在 dw_ods 与 fxsb_ods 各存一行、互不淘汰（(source_system,event_id) 作用域）。

## 5. 02.4 A-B-A 切换编舞

- 独立 landing 根 `target/v25-it/g3102_<ts>/landing`；runtime_profile id=1 的 landing_uri 指向它（stage7q1 原目录不动）。
- Phase A：拷 A 金样本为 `events/2026091819.jsonl` → ingest+pipeline → 快照 SA1，锚点=ORACLE §6（80/35/15112.10/11859.40/188.90/漏斗 49→35/RFM 合计 35/PV·UV·DAU=0）。
- Phase B：A 文件移出 landing，置入 B 夹具 `events/2026091821.jsonl` → ingest+pipeline → 按 §4 全量对照。
- Phase A 回程：B 移出，A 拷回为 **`events/2026091920.jsonl`**（不同绝对路径，绕开 (profileId,sourceId,绝对路径) checkpoint，D-026）→ 快照 SA2 锚点必须逐项=SA1；fxsb_* 六库留存但 ACTIVE=mock-mall；同 order_id `2100917058081423362` dw_dwd（A 口径）vs fxsb_dwd（B 59.80）互不串写。

## 6. 02.5 最小管理员接入向导（复用现有 API，D-027）

管理端新增「来源接入向导」：选/建源（受控字段表单）→ 预置受控样本引用（`fixture-shop-b/normal.jsonl` 等，不开放任意服务器路径）→ dry-run 预览错误/覆盖率/隔离明细 → 确认激活（mappings/activate + sources/{id}/activate）。不实现浏览器上传样本与字段拖拽（指导书明示后续增强不阻塞）。完成后浏览器实操留证。

## 7. 02.6 平台独立性

- 停源（PAUSED）+ 无新批次：历史快照/ADS 指标仍可读（页面+API 双证）。
- 来源停机时效警告：数据最新时间如实展示（快照/批次时间），不假报最新。

## 8. 本批决策（D-023 起，详见 DECISION_LOG.md）

- **D-023**：B warehousePrefix=`fxsb`（≤24 字符合法、与 dw 区分、语义可读）。
- **D-024**：B 夹具在 A 金样本缺失面上补齐 product_created/behavior/user_registered 事件，使 8 类事件映射全面覆盖（A 金样本只有交易+行为衍生，不含行为原始事件）。
- **D-025**：故障集取 5 类互异映射违规（未映射事件类型/FEN 非整数/时间格式不匹配/必填缺失/schema 版本不支持）；重复 evt_no 案例剔除——同源内 event_id 重复属去重语义而非映射违规，避免与 DWD 去重键混淆。
- **D-026**：A-B-A 回程落盘文件名改用 `2026091920.jsonl`，以不同绝对路径绕开 LandingInput 检查点幂等（checkpoint 键含绝对路径；同名重放会被判已处理）。
- **D-027**：02.5 向导全部复用既有端点（sources CRUD / mappings dry-run / mappings activate / sources activate），无新增管理 API ⇒ 按 V3.1 行 410 规则判定**无需设计 V3.1 差异表**；上传样本与可视化拖拽列为后续增强。
- **D-028**：dry-run 样本根经 `platform.mapping.sample-root` 环境变量指到本批隔离目录；canonical 夹具提交于 `fixtures/source-b/fixture-shop-b/`，运行期用副本，不改仓库制品。

## 9. 收口物

结果卷 `docs/verification/results/G31-02-SOURCE-B-RESULT.md`（逐锚点对照表+运行期证据路径）、DECISION_LOG D-023~、CURRENT_BATCH.md / PROJECT_STATUS.md 更新；两次本地提交（代码+制品、docs），不 push。
