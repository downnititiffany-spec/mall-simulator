# R2 进度（append-only）

格式：`时刻 | 步骤 | 结论/读数 | 证据`

- 2026-09-12 17:18:33 | G1 复测（git/指纹） | HEAD 实测 = `f0049603d1ac5746a52f0f347202d41a3bf43595`（**不是施工单写的 `095118a`**）；`git status --porcelain` = 0 行；`095118a` 是 HEAD 的祖先（exit 0）。差异提交 = `f004960 docs(r2): 开工登记`，只加 3 个 docs 文件（+112 行，0 源码）⇒ CT 集成未受影响 | 本文件 + raw/state-pre-20260912-172011.txt
- 2026-09-12 17:18:33 | G1 复测（冻结指纹） | `contract-specs/specs/warehouse-namespace.v1.json` 5238 B、CR=0、LF 规范化 sha256 = `463D9DC3503D563D8DD8EB844C1AB5EDE9AAEE073251F07957D410C13911AE5A` ⇒ **与冻结前 16 位 `463D9DC3503D563D` 一致，未变** | raw/gates-r2-rerun.txt
- 2026-09-12 17:18:33 | G4 跑前 jar | 旧制品 `49859A1CDB66AB47` / 33,135,969 B / mtime 15:01:13（不含 CT）⇒ 按 ORDER-1 §1 G3 意图重建 | raw/jar-fingerprint.txt（跑后写入新值）
- 2026-09-12 17:19:15 | E3-g/E3-f 跑前 | 时点 17:19:15：landing\events 59 文件/406,114,133 B、manifests 40/36,733 B；spark-warehouse 972 parquet/4,977,445 B（分库 dw_ads 30/54737、dw_dim 3/8198、dw_dwd 57/317273、dw_dws 14/28547、dw_ods 866/4567412、probe_r613 2/1278） | raw/state-pre-20260912-172011.txt
- 2026-09-12 17:20:11 | **陷阱/新缺陷 F-82** | 首采把快照读在 `analytics_meta` ⇒ 得到 ACTIVE=`S20260907_11`(2026-09-07 19:33) 而真 ACTIVE=`S20260901_41`。根因：`application.yml` L15/L19 的 metric publish/read 双源均指向 **analytics_metric**；`analytics_meta.metric_snapshot|metric_value` 是 `db/meta/V2`(L81/L99)＋V7(L72-73) 遗留的**孤儿表**（自 09-07 19:33 冻结、version 全 1）。父侧 gates-pre-run.txt L48 与提示的「metric_snapshot 9 行(ACTIVE=1)」正是读的孤儿表 —— 数字对、语义错。R2 已改用 analytics_metric 并双写标记 | raw/trap-orphan-metric-tables.txt
- 2026-09-12 17:20:11 | E3-c 跑前基线 | `analytics_metric` 8 张 ADS 镜像表按 snapshot_id 分组 COUNT(*)：`_39` 与 `_41` 各 1/4/4/9/1/9/1/1 = 30 行，**逐表序列相同** ⇒ 支持 P5 的「同一快照 1/4/4/9/1/9/1/1」口径。注意 `ads_user_profile_m` 早期快照为 3 行（`_22.._30`），`_39/_41` 为 1 行 ⇒ 该表口径在 run 38/39 之间发生过变化（记为观察项，非本单结论） | raw/state-pre-20260912-172011.txt §4b
- 2026-09-12 17:20:34 | 8091 停机（本单范围） | `Stop-Process -Id 39220 -Force` ⇒ 端口 8091 无监听；**8090 pid 41228 / 8092 pid 19268 未受影响**（同刻复测在听） | raw/restart-8091.txt
2026-09-12 17:42:11 | R2 主跑 run 42 终态 SUCCESS / S20260901_42 / 8 阶段齐 | raw/e3a-poll-run42-20260912-172304.txt
2026-09-12 17:42:11 | P5 预测**未命中**：实测 8 表按快照 = 1/4/4/4/1/4/1/3 = 22 行，预测为 1/4/4/9/1/9/1/1 = 30 行（照实登记，不改写） | raw/state-post-20260912-172840.txt
2026-09-12 17:42:11 | R2-b 预测落盘 | raw/predictions-control-run.txt (mtime 17:33:43)
2026-09-12 17:42:11 | R2-b 输入纯净度 PASS：events 60/60 有断点、差集 0、假文件名正向对照命中 | raw/control-input-purity.txt
2026-09-12 17:42:11 | R2-b 注入件落盘：r2ctl-b31replay-20260912-173418.jsonl 363848 B sha=5397907A…0257（与 accepted/31 源件逐字节相同）
2026-09-12 17:42:11 | R2-b 采集 batch 42 = SUCCESS recordCount=1000 quarantineCount=0 | raw/control-ingestion-response-20260912-173418.txt
2026-09-12 17:42:11 | R2-b 对照 run 43 终态 SUCCESS / S20260901_43 / 8 阶段齐 | raw/e3a-poll-run43-20260912-173500.txt
2026-09-12 17:42:11 | R2-b 判定 PASS：D1 十指标逐值全等 / D2 8 表 1,4,4,9,1,9,1,1=30 逐表相同 / D3 1000-0 / D4 8-8 / D5 库-导出-接口三通道一致 / D6 checksum 89b82028 内容级同源 | raw/control-verdict-run43-20260912-174203.txt
---

## 补记 2026-09-12 19:38 · 「落地（push）」受阻记录（网络层，非仓库问题）

- 本地提交 **`e5c0bec`**（52 文件 / +4,604 −0）已完成；`git status` 干净。
- `git push origin HEAD:main` **连续 3 次失败**（19:35:25 / 19:35:58 / 19:36:32），报错一致：
  `Failed to connect to github.com port 443 after ~21 s: Could not connect to server`；
  首次报 `Recv failure: Connection was reset`。
- 现场判定（已实测）：`Resolve-DnsName github.com` **正常**（→ `20.205.243.166`）；`git config` 无 `http.proxy`，环境变量 `HTTP_PROXY/HTTPS_PROXY/ALL_PROXY` **均为空**；
  本会话早前（17:2x）推送同一远端**成功** ⇒ 判为**网络侧 TCP 443 连通被重置/中断**，非凭据、非仓库、非配置问题。
- 现状：`origin/main` = `f004960…`（落后本地 **1** 个提交）。
- 恢复后执行一次即可：`git push origin HEAD:main`（无其它待办）。
