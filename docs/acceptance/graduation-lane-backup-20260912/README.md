# 泳道冷备份归档（原仓外 `graduation-lane-backup`）

> 2026-09-14 建立。原位置 **`D:\Develop_code\graduation-lane-backup`（仓外）**，按用户硬约束「**产物一律不得在仓库之外创建**」
> （2026-09-14）迁入仓库；**归并后逐字节校验通过**，仓外原件随后按批准删除。
> 本目录是**被已入库文档引用的证据**，因此必须 **tracked**（不能放 gitignored 的 `backups/`）。

## 1. 为什么迁进版本库

该目录被**已入库文档**当作证据链引用（L6 只读核查实测 14 处），其中明文点名两处：

- `docs/acceptance/ct-batch-20260912/PLAN.md` §7「契约文件写前各留**离仓**冷备份：`D:\Develop_code\graduation-lane-backup\ct-batch\*.before`」；
- `docs/acceptance/ct-batch-20260912/lane/PROGRESS.md` L16「冷备份完成：…（7 个文件）＋ `sha256-before.txt`」。

另有 `docs/acceptance/p1-03-source-registry-api-20260911/e3-acceptance.ps1:7` 与 `p1-05-manifest-source-20260911/raw/*` 引用 `p103/p105` 类路径。

⇒ 若直接删除仓外原件而不改引用，**引用链断裂**；若只改引用而不归并，**证据灭失**（53 个文件在 `.git` 中不可恢复）。
**处置＝先归并入仓（本目录）＋追加日期化勘误改引用路径，再删原件**（用户 2026-09-14 批准）。

## 2. 内容与规模

- 规模：**182 文件 / 3.04 MB**（迁入后本目录文件数 **182**，与原目录逐项相等）。
- 结构：`ct-0/`、`ct-batch/`（7 个 `*.before` 契约写前副本 ＋ `sha256-before.txt`）、`retired/`、`manifests/`、`p2-01/`（patch 与日志）等。
- 独有内容：**53 个文件 / 1.84 MB** 在仓内**不存在**（4 个 patch、retired 文件、manifests、p2-01 日志）⇒ 是本次归并的主要价值。
- `INVENTORY.txt`：全目录逐文件 `sha256 + 字节数` 清单（可复算）。

## 3. 逐字节校验（删除原件的前置条件）

| 项 | 源文件数 | 目标文件数 | 逐文件 sha256 比对 | 结论 |
|---|---|---|---|---|
| `D:\Develop_code\graduation-lane-backup` → `本目录` | 182 | 182 | diff = 0 | **BYTE-IDENTICAL** |

## 4. 勘误（append-only，原文不改写）

本目录建立时**追加**了两条日期化勘误，明确「离仓冷备份」这一做法**作废**：

- `docs/acceptance/ct-batch-20260912/PLAN.md` §8 勘误（第一版：冻结新增，未迁移）；
- `docs/acceptance/ct-batch-20260912/lane/PROGRESS.md` 文末勘误（第一版同上）；
- 同两文件的**第二条勘误**（本次）：冷备份**新位置＝本目录**，仓外目录已按批准删除。

⇒ 阅读历史文本时以**文末勘误节为准**：隔离靠 **gitignore／命名空间／端口／runId**，**不靠把文件搬出仓库**。

## 5. 状态与未取证

- **本目录**：`提交完成` ✅（内容已在版本库，引用路径已改指仓内）；**未做**人工价值判读（仅机械哈希判定）。
- **仓外原件**：已删除（批准项 S3，删除前校验 BYTE-IDENTICAL）。
- **未取证**：`retired/` 内文件是否仍被运行期代码引用（仅做了仓内文本引用统计，未做运行期核对）。

## 6. 入库行尾归一化（fidelity 实测，2026-09-14 补记）

- 本仓 `core.autocrlf=true`。本目录与 `m3-jdk8fix-evidence` 合并实测（`git ls-files --eol`）：
  **`i/lf w/lf` 114 件、`i/lf w/crlf` 79 件、`i/-text w/-text` 2 件**。
- ⇒ **§3 的 BYTE-IDENTICAL 是对「工作树副本 vs 仓外原件」**（校验发生在 commit 之前），成立；
  但其中 **CRLF 文本的索引 blob 被 LF 归一化**，`git cat-file blob` 的 sha256 与原文件不同。
- 逐文件原字节 sha256 见 `INVENTORY.txt`（在工作树副本上计算）与
  `…v25-stray-consolidation-20260914/raw/exec/merge-verify-S3-*.txt`。
- 在 `core.autocrlf=true` 的克隆里 checkout 会还原 CRLF ⇒ 原字节可复原；**若该配置被改变则不保证**。
  ⚠️ 其中 `*.patch` 类文件若被工具改写成 LF 再应用，可能补丁失配——**应用补丁请用本目录工作树副本**。
