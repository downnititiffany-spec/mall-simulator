# BATCH-G31-07 — 部署交接与本版验收计划（07.1~07.5）

- **批次**：G31-07（V3.1 指导书 §7 批次序列第 8 批/末批）
- **日期**：2026-09-25（机器证据时钟）
- **状态**：已批准执行（按 2026-09-19 用户指令「连续执行 G31-00~07 不停止，自主决策记录」推进）
- **被测对象**：当前工作树（未提交改动保留，不 commit 不 push）+ 常驻平台栈（g3103 域，05.6 RESTORE PASS 后 RUNNING，stub LLM 18080）

## 0. 计划依据与诚实登记（必须先读）

**V3.1 §7 G31-07 的验收原文已不可恢复**（用户 2026-09-19 会话消息、非仓内文件）。本计划按既定裁决（与 G31-04/G31-05 同模式）**从冻结的 V3.0 权威文档推导**验收项，并在此登记推导链：

1. **V3.0 指导书 §7 阶段 8「部署与交付」L185–L191**：① 冻结实际部署拓扑、版本、端口、数据目录、服务账号、启动/停止及恢复步骤；② 按最终首版范围做一次可复现产品验收，**总控**判断通过、限定或未通过；③ 整理正/负样本、真实链、界面和同环境实验；④ 论文/答辩材料在结果冻结后统一写；⑤ 后续 Doris 对比不伪装成首版已实现能力。
2. **V3.0 L212 + L243**：代码 Agent 不可自行宣布完整验收；「完整验收」只有总控决定 ⇒ 本批产物 = 部署交接冻结 + 可复现验收证据包 + 验收报告（**推荐裁定 = 限定通过候选**），签收权归总控。
3. **V3.0 L203（Stage 7 完成标准）** 与 `docs/verification/STAGE7-REMAINING-SCOPE-20260919.md` 滚动清单对账。
4. **V3.1 主序 G31-06 真实AI = BLOCKED**（D-039 登记：真实模型凭据不可得，不得伪造；stub-local 已按 D-039 启用且如实透出）⇒ 本批把 G31-06 BLOCKED 作为**未闭环项**正式列入验收报告，不重试、不伪造。
5. **C6 补验已于 G31-03 追加验证关闭**（2026-09-23：登录 Enter、AI 输入 Enter、建议 chip 各自按预期提交且各恰发 1 次 `/ai/queries`；真实 Chromium 证据 `target/v25-it/g3103-fresh-20260923-a1/attempt-v31-20260923-1717/`）——BATCH-W C6 排期（V31-D04 → G31-03/G31-07）就此闭合，本批仅引用不重跑。

## 1. 「一次可复现产品验收」的运行形态（不重复消耗的解释，诚实登记）

阶段 8 ②「做一次可复现产品验收」按以下**当日实链 + 终验腿**组成，全部带复现脚本路径，不重跑整链：

- **采集→数仓→Spark→发布实链（当日）**：G31-05（RunId `g3105_20260925_121316`）——Flume→HDFS raw、平台 HDFS 摄取、WSL Spark 10/10 全链、LOCAL 对照腿 pipeline 8 阶段 SUCCESS → 14/14 指标 == S1 oracle；故障恢复（G31-04 `g3104b_20260925_104346`：杀作业/杀进程/停库三形态 + 恢复语义）。
- **终验腿（本批新增，07.2）**：对**运行中**常驻栈做只读验收核验（健康/登录/ACTIVE 唯一/14 指标指纹对照/质量态/F2b 零 3306/stub LLM 探针），证据根 `target/v25-it/g3107_<ts>/`。
- **界面**：BATCH-W 真实 Chromium E2E（W-1~W-9）+ G31-02 向导 E2E + G31-03 C6 补验（均引用既有证据）。

## 2. 执行项与 PASS 判据

### 07.1 部署交接冻结（L187①）
- 产出 `docs/handover/deployment-freeze-20260925.md`：实际部署拓扑（Windows LOCAL 主档 8091/8090 + 3307 隔离库 + WSL 单节点 HDFS 档 19000 已验 + stub LLM 18080）、版本（jar SHA 台账）、端口、数据目录、服务账号（文档化演示账号 + 隔离账号通道，**零明文口令**）、启动/停止/恢复步骤（start-all / stop-platform-by-pidfile / daemon3307 / item3-restore 恢复驱动）、运维绑定约束（D-042 WSL 两条 + 零 3306 红线）。
- **PASS**：文档落位；无任何口令/密钥明文；与 D-040/D-041 变更请求边界一致（记录缺陷不改码）。

### 07.2 可复现验收终验腿（只读，对 RUNNING 常驻栈）
- 新证据根 `target/v25-it/g3107_<ts>/`，驱动 `scripts/g3107-acceptance.ps1`：
  1. `GET /api/v1/metrics/health` → ok（metric_read 通）。
  2. login admin 冒烟 + login analyst（AI 探针用）。
  3. `GET /api/v1/runtime-profiles` 状态留证（记录不断言具体 landingUri）。
  4. `GET /api/v1/pipeline-runs` 最近 5 条留证。
  5. `GET /api/v1/metrics/snapshots`：S20260921_20 **ACTIVE 唯一**（API 层等价 mini-gate）。
  6. `GET /api/v1/dashboards/overview`：ACTIVE=S20260921_20、source=spark-ads、businessTime=2026-09-21、qualityStatus=PASS、**14 指标 == item4 既有指纹逐项相等**（oracle：pv=31, uv=8, paid_order_cnt=13, fav_cnt=2, cart_add_cnt=1, buy_rate=0.875, cart_rate=0.125, refund_rate=0.1538, full_refund_rate=0.1538, dau=8, avg_order_value=92.31, repeat_rate=0.5714, net_sale=1000.0, gmv=1200.0；容差 0.0005）。
  7. F2b 日志守卫：`g3103_20260920_052106/logs/platform.log` 尾部 200KB 零 `:3306`，且 3307 JDBC URL + landing root 有据。
  8. stub LLM 探针（analyst）：POST `/api/v1/ai/queries` → providerUsed=stub-local、status=EXECUTED、suggestions≥1（D-039 授权形态）。
- **PASS**：8 腿全绿；每腿 JSON 留证；state JSON outcome=PASS。全程零写库（除登录/AI 探针的设计内记录）、零重启、零 3306。

### 07.3 本版验收报告（L185②③ + L189 清册）
- 产出 `docs/verification/results/G31-07-DEPLOY-HANDOVER-ACCEPTANCE-RESULT.md`：
  - V3.1 链收口状态表（G31-00~G31-05 PASS + G31-06 BLOCKED + G31-07 本批）；
  - 首版范围对账（Stage 7 五任务 × 证据指针；L203 口径）；
  - 正/负样本、真实链、界面、同环境实验清册（L189③）；
  - 缺陷与变更请求清单（F-G4-1→D-040、D-041；运维约束 D-042）；
  - 未交付项（REMOTE_CLUSTER 档、真实 LLM、论文/答辩材料【结果冻结后统一写，L190】、Doris 对比【L191 不伪装】）；
  - **推荐裁定：限定通过候选**（限定项逐一列明），签收权归总控。
- **PASS**：报告落位且每项结论均有证据指针，无越界表述。

### 07.4 滚动清单对账
- 更新 `docs/verification/STAGE7-REMAINING-SCOPE-20260919.md` 状态列：#1 Flume→HDFS DONE（BATCH-U/V + G31-05）；#2 HDFS 环境档 = WSL 单节点 DONE（G31-05）/ REMOTE_CLUSTER 未验；#3 浏览器 E2E DONE（BATCH-W + G31-02/G31-03）；#4 真实 LLM BLOCKED；#5 第二来源 DONE（G31-02）；#6 故障样本 DONE（G31-04 + G31-03 恢复语义）。
- **PASS**：六项状态与证据批次一一对应，无「未证当已证」。

### 07.5 登记三件套 + 备份
- PROJECT_STATUS.md 新段（编辑前备份 `*.bak-20260925-g3107`）；CURRENT_BATCH.md 状态行刷新；DECISION_LOG 追加 **D-043**（G31-06 BLOCKED 终验登记 + G31-07 交付形态与推荐裁定边界；编辑前备份）。

## 3. 边界声明

- 本批**不宣称完整验收**（V3.0 L212/L243）：报告只给「限定通过候选」推荐，签收权在总控。
- 零产品代码改动；既有未提交改动原样保留；不 commit 不 push；3306 全程零接触。
- 终验腿为只读核验：不重启平台、不跑 prep、不执行任何 DROP/清理；AI 探针按 D-039 stub 形态执行。
- 本批不证明：REMOTE_CLUSTER、真实 LLM、YARN/多节点、共享 Hive Metastore、论文/答辩材料（post-freeze）。
