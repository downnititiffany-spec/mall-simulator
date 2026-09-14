# 仓外散落物处置执行记录（2026-09-14）

> 上游：本目录 `README.md`（L6 只读核查报告，346 行）＋ `raw/`（47 件，机械可复算）。
> 本文件只记录**处置动作与证据**，**不改写** L6 报告任何一个字。
> 执行者：总控；执行依据：用户 2026-09-14 对 4 个问题的逐项批准（**全部选 A**）。

## 1. 批准范围（逐项，用户原话选项）

| # | 问题 | 用户选择 | 含义 |
|---|---|---|---|
| ① | S1/S2 正式库转储 `D:\p103-e3`、`D:\p105-e3` | **A** | 先归并到**仓内 gitignored** 目录，复核后再删原件 |
| ② | W2 `m3-jdk8fix` 的 `.verify/` | **A** | 归并到 `docs/acceptance/m3-jdk8fix-evidence-20260914/verify/` **并同步改 `spark-jobs/pom.xml` 注释指向仓内** |
| ③ | S3 `graduation-lane-backup` 与引用 | **A** | 归并**进仓（tracked）** ＋ 追加**第二条日期化勘误**改引用路径，再删原件 |
| ④ | W1 / W3 / S4 低风险项 | **A** | 批准删除，但**先做删除前复验 ＋ sha256 归档** |

**未批准项**：`D:\Develop_code\GraduationProject-wt\m3-jdk8fix` **整棵 worktree 的删除**（问题②只批准归并 `.verify/` 与改注释）。

## 2. 删除前复验（Anti-Entropy：先证「快照后无增删」）

用 L6 的 `raw/*-sha256-all.txt` 清单行数 与 2026-09-14 实测文件数比对：

| 项 | 路径 | 实测文件数 | L6 清单行数 | 结论 |
|---|---|---|---|---|
| W1 | `D:\Develop_code\_gp_headcheck` | 2283 | 2283 | OK |
| W2 | `…GraduationProject-wt\m3-jdk8fix` | 1526 | 1526 | OK |
| W3 | `D:\Develop\GraduationProject\.f88-baseline-wt` | 1783 | 1783 | OK |
| S1 | `D:\p103-e3` | 562 | 562 | OK |
| S2 | `D:\p105-e3` | 382 | 382 | OK |
| S3 | `D:\Develop_code\graduation-lane-backup` | 182 | 182 | OK |
| S4 | `D:\Develop_code\.verify` | 1 | 1 | OK |

**7/7 相等 ⇒ 快照后无新增、无删除。** 另实测：`D:\Develop_code` 下未出现本项目新建目录（其余如 `D:\Develop_code\{AMybatisDemo,Java,yjxxt,…}` 属用户其他项目，**不在本项目处置范围**）；主仓 `git status` 条目数与上一时点一致（15）。

## 3. 归并动作与逐字节校验

`raw/exec/merge-verify-<项>-{src,dst}.txt` 为**源与目标各自的全量逐文件 `sha256 + 字节数`**（绝对根计算，路径相对化后比对）。

| 项 | 源 → 目标（**全部在仓内**） | 文件数（源/目标） | 比对 | 结论 |
|---|---|---|---|---|
| W2-verify | `…m3-jdk8fix\.verify` → `docs/acceptance/m3-jdk8fix-evidence-20260914/verify` | 24 / 24 | diff = 0 | **BYTE-IDENTICAL** |
| S3 | `graduation-lane-backup` → `docs/acceptance/graduation-lane-backup-20260912` | 182 / 182 | diff = 0 | **BYTE-IDENTICAL** |
| S1 | `D:\p103-e3` → `docs/backups/strays-20260914/p103-e3` | 562 / 562 | diff = 0 | **BYTE-IDENTICAL** |
| S2 | `D:\p105-e3` → `docs/backups/strays-20260914/p105-e3` | 382 / 382 | diff = 0 | **BYTE-IDENTICAL** |
| S4 | `D:\Develop_code\.verify\u1u3-cli-planappend.log` → `docs/acceptance/m1-4-u1u3-20260912/raw/u1u3-cli-planappend-20260912-100546.outside.log` | 1 / 1 | `301DE7D5A4EEB6DB983109C35BA35D3794D9B4A3DDFA8D2B8D24059610F2178D` 相同 | **BYTE-IDENTICAL** |

**gitignored 有效性实测**：`docs/backups/strays-20260914/` 归并了 **944 个文件 / 约 130 MB**，`.gitignore:53 backups/` 命中（`git check-ignore -v` → `.gitignore:53:backups/`，exit 0），归并后 `git status --porcelain` 中 **`backups` 相关条目 0 个** ⇒ 正式库转储**未进入版本库**，工作树体积增加而 `.git` 体积不增。

### 3.1 过程纠错（登记，不掩盖）

首轮比对脚本把**目标根传成相对路径**，而 `FullName` 是绝对路径 ⇒ `Substring($root.Length)` 截错，4 项全部报 `MISMATCH`（差异数恰为文件数×2）。
**该 MISMATCH 不是复制失败**（当时文件数已逐项相等）。按「校验不全绿一律不删」纪律，**首轮未执行任何删除**；改用 `Resolve-Path` 取绝对根重算后 **5/5 全绿**才进入删除。

## 4. 删除动作与结果

先决条件：第 3 节 5/5 BYTE-IDENTICAL。

| 项 | 命令 | 结果 |
|---|---|---|
| W1 | `git worktree remove --force D:/Develop_code/_gp_headcheck` ＋ `git worktree prune` | 目录 `exists=False` |
| W3 | `git worktree remove --force D:/Develop/GraduationProject/.f88-baseline-wt` ＋ `git worktree prune` | 目录 `exists=False` |
| S1 | `Remove-Item -LiteralPath 'D:\p103-e3' -Recurse -Force` | `exists=False` |
| S2 | `Remove-Item -LiteralPath 'D:\p105-e3' -Recurse -Force` | `exists=False` |
| S3 | `Remove-Item -LiteralPath 'D:\Develop_code\graduation-lane-backup' -Recurse -Force` | `exists=False` |
| S4 | `Remove-Item -LiteralPath 'D:\Develop_code\.verify' -Recurse -Force` | `exists=False` |

删除后 `git worktree list`：

```
D:/Develop_code/GraduationProject                1cb318b [remediation/r1-boundary]
D:/Develop_code/GraduationProject-wt/m3-jdk8fix  f26be26 (detached HEAD)
```

**残留（有意保留）**：`D:\Develop_code\GraduationProject-wt\m3-jdk8fix`（未获删除批准）与其空父容器 `D:\Develop_code\GraduationProject-wt`；`D:\Develop\GraduationProject`（原 W3 的父容器）保留为空目录，**未删**。

## 5. 引用链收口

| 引用处 | 原内容 | 现状态 |
|---|---|---|
| `spark-jobs/pom.xml` JDK8 闸门注释 ② | `（worktree 内）.verify/final-*.log` | **改指** `docs/acceptance/m3-jdk8fix-evidence-20260914/verify/final-*.log`（修掉 L6 缺陷⑤-1） |
| `docs/acceptance/ct-batch-20260912/PLAN.md:112` | 离仓冷备份路径 | **原文不改**；§8 追加第二条日期化勘误，指向 `docs/acceptance/graduation-lane-backup-20260912/` |
| `docs/acceptance/ct-batch-20260912/lane/PROGRESS.md:16` | 同上 | **原文不改**；文末追加第二条日期化勘误 |

## 6. 处置后状态与未取证

- **四级状态**：本通道＝**限定验收**（归并、校验、删除、引用收口均**有实测证据**）；**未达完整验收**，因为下列未取证项与 W2 残留未闭合。
- **未取证**：①三项「有独有内容」只做**机械哈希判定**，未做人工价值判读；②**未展开 jar/zip 内部**（`base.zip` 271 条目）；③**未连 3306 核对转储与现库一致性**（越界，且用户禁止写库）；④**未做全盘扫描**（范围仅限已识别的 7 项 ＋ `git worktree list` 注册项）；⑤未测 `git worktree prune` 的全部副作用（仅确认列表正确）。
- **永久纪律（新增）**：
  1. **外部产物巡检**：每次收尾实测「`D:\` 根／`D:\Develop_code` 是否有新目录」并与此前清单比对，发现即登记＋停用＋报批。
  2. **删除三件套**：任何删除前必须「归并入仓 ＋ 逐字节校验全绿 ＋ 文件数清单比对」，缺一不删。
  3. **泳道交付模板增列「新建文件全路径」**，避免再出现「文件建到仓外而总控不知情」。
- **保密警示**：`docs/backups/strays-20260914/` 内含**正式库真实数据**（PII／会话／审计／`sys_user` bcrypt 哈希）与**明文口令字面量**，该目录被 gitignore，**禁止提交、禁止外发**（另见该目录 `README-DO-NOT-COMMIT.md`）。

## 7. 补记：入库行尾归一化（2026-09-14 15:2x 实测）

`git ls-files --eol` 实测两批归并入仓目录：**`i/lf w/lf` 114 件、`i/lf w/crlf` 79 件、`i/-text w/-text` 2 件**（`i`＝索引 blob，`w`＝工作树）。

- ⇒ 第 3 节的 **BYTE-IDENTICAL 是对「工作树副本 vs 仓外原件」**（校验发生在 commit **之前**），结论**成立**；
- 但本仓 `core.autocrlf=true` ⇒ **CRLF 文本的索引 blob 被 LF 归一化**，`git cat-file blob` 的 sha256 **不等于**原文件
  （例：被 `pom.xml` 点名的 `final-positive-control.log` 原件 `D88E6090…F70BA`、`final-effective-scalac-args.log` 原件 `1E60F07F…CA021`）；
- 在 `core.autocrlf=true` 的克隆里 checkout 会还原 CRLF ⇒ 原字节可复原；**该配置被改变则不保证**。
  "原字节"的权威＝**工作树副本 ＋ 归档目录 `INVENTORY.txt`／本节 `raw/exec/merge-verify-*` 的 sha256**；
- 已同步写入 `m3-jdk8fix-evidence-20260914/README.md §6`、`graduation-lane-backup-20260912/README.md §6`
  （后者并提示 `*.patch` 应使用工作树副本应用）。
