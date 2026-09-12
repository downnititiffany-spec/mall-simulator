# F-37 事故记录：工作区 33 个源文件被删（2026-09-12）

> 本目录是**事故证据归档**，不是交付验收记录。登记事实、现场指纹、恢复动作与**未取证清单**；
> 看板 `§5.1` 的 `F-37` 行只保留状态与指向本文件的指针（单一所有者：细节在本文件）。
> 本文件不含任何"原因已定位"的结论——**执行者未取证**。

## 1. 任务与范围

| 项 | 内容 |
| --- | --- |
| 事件 | 仓库工作区 `synthetic-data-generator/src` 下 33 个**已跟踪**文件在无人工干预下从磁盘消失 |
| 发现时刻 | 2026-09-12 12:10 前后（总控执行冻结复核器冒烟时因读不到测试文件而暴露） |
| 影响面 | 全部 21 个 `src/test/java/**` 文件 ＋ 12 个 `src/main/java/**` 文件（含第二家商城适配器与引擎） |
| 本次不做 | 不追责、不改写任何历史证据文本、不动契约与看板正文以外的任何文件 |

## 2. 事实时间线（均为 2026-09-12，UTC+8）

| 时刻 | 事实 | 证据来源 |
| --- | --- | --- |
| 11:57:51 | 提交 `25b0fe7`（M1-9 ② 泳道交付原样入库，15 文件） | `git log -1 --format=%cI 25b0fe7` |
| 12:09:05 | `adapter/ProductPage.java` 被写入（= 修复泳道当时正在改它） | 文件 `LastWriteTime` |
| 12:09:09 | 提交 `ceeddec` 落库。**该次提交前的 `git status --short` 只显示 2 个修改（`ProductPage.java`、`8091-stdout.log`），无任何删除** ⇒ 33 个文件此刻**仍在** | 提交输出（已推送，`origin/main` = `ceeddec`） |
| 12:10:18 | 总控写入复核器 `scripts/verify-fix.ps1`（本目录同级 `scripts/`） | 文件 `LastWriteTime` |
| 约 12:10:3x | 复核器冒烟运行：读 `src/test/java/.../SecondMallAdapterOperationsTest.java` 抛 `Could not find file`；随即 `git status --short` 显示 **33 行 ` D`**（工作区删除，索引侧无变更） | `.verify/m1-9-verify/verify-fix-smoke.err.txt`、当次命令输出 |
| 12:10:50 | 复核：`src` 下现存 61 文件、`test/java` 现有 **0** 文件；5 秒后复测**仍为 33 个删除** ⇒ 不是"移动中"的瞬态 | 当次命令输出 |
| 12:11 | 中断修复泳道（`02874585-…`，interrupt）以防继续写入叠加 | agent 运行时通知 |
| 12:12:12 | 保全现场：工作区残余 63 文件快照 ＋ `git status --porcelain` 原文 ＋ 删除面清单 | `.verify/m1-9-verify/tree-snapshot-1211/` |
| 12:12 前后 | `git diff --cached` **为空** ⇒ 索引与 `HEAD` 无差异 ⇒ 删除**只发生在工作区**，可从索引/`HEAD` 逐字节还原 | 当次命令输出 |
| 12:13 | 执行恢复：`git checkout -- <33 路径>`，退出码 0；恢复后 `src` = 94 文件、`test/java` = 21，`git diff --name-only -- synthetic-data-generator/src` = **1**（仅保留的 `ProductPage.java`） | 当次命令输出 |
| 12:13 | 建立**仓库外**备份 `D:\Develop_code\graduation-lane-backup\module-clean-ceeddec\`（95 文件 ＝ 94 `src` ＋ `pom.xml`，清单 sha256 `9319763B60732D1B6AE58CACDAFCB99958FF50F3D8F9A1A002CD0749AD8B98C4`）＋ `fix-round\`（供第二班分组备份） | 当次命令输出 |
| 12:1x | 派发第二班修复泳道（`16a5eca0-…`），指令加硬约束：**禁止一切删除类命令**、分组即时备份到仓库外、发现文件消失立刻停止并报告 | agent 运行时 |
| 12:1x | 向被中断的第一班发出**只读**询问（命令清单、是否删除/重写过 `src/**`、被中断时在做什么、S1/S2/S4/S6/W1–W3 的改法）；要求其**不得再改任何文件**。回复见 §13 补记（尚未收到） | agent 运行时 |

**窗口收敛**：33 个文件在 `12:09:09`（提交时的 status 无删除）与 `约 12:10:3x`（读文件失败）之间从工作区消失。

## 3. 现场指纹（可事后核验）

| 制品 | 路径 | 字节 | sha256 |
| --- | --- | --- | --- |
| 残余现场快照（63 文件） | `.verify/m1-9-verify/tree-snapshot-1211/src`、`pom.xml` | — | 逐文件哈希见该目录 |
| `git status --porcelain` 原文 | `.verify/m1-9-verify/tree-snapshot-1211/git-status-porcelain.txt` | — | `67AD12E7B3B046344EF4203855EEFEE5D0F8F813BC479F8E65F69C4334B8F68D` |
| 删除面清单（33 行） | `.verify/m1-9-verify/tree-snapshot-1211/deleted-list.txt` | — | `A7480815BF67D9D1834FB9E033F2BD5BF66FB04C2DB4A09DD8595088162AC9D2` |
| 第一班存活改动（补丁） | `.verify/m1-9-verify/tree-snapshot-1211/lane-surviving-ProductPage.patch` | 4,131 | `783093329CCF99179A137931107A021B8858E71D4126594594CB20F3013BD63F` |
| 第一班存活改动（原文） | `.verify/m1-9-verify/tree-snapshot-1211/ProductPage.java.asleft` | 3,333 | `2BB4DF5FC16F00E113C0527683F65504BFCA3251F9CAD3861A72B8F292EB384E` |
| 仓库外干净备份清单 | `D:\Develop_code\graduation-lane-backup\module-clean-ceeddec-manifest.txt` | — | `9319763B60732D1B6AE58CACDAFCB99958FF50F3D8F9A1A002CD0749AD8B98C4` |

> `.verify/**` 与仓库外备份按仓库既有规矩**不入库**（`.gitignore:61`）；此处以"路径＋字节＋sha256"引用，任何人可复算。

## 4. 恢复动作与恢复核验（做了什么、凭什么说恢复了）

1. 判定可恢复性：`git diff --cached --name-status` **0 行**（索引 = `HEAD`）⇒ 删除未进索引 ⇒ `git checkout --` 可还原索引内容。
2. 保全现场后执行 `<33 路径> | git checkout --`：退出码 **0**。
3. 核验（三条独立口径，全部通过）：
   - 文件数：`src` 下 **94**（= `git ls-files -- synthetic-data-generator/src` 的 94）、`src/test/java` **21**（= 索引 21）；
   - 内容：`git diff --name-only -- synthetic-data-generator/src` = **1 行**，且该行正是**有意保留**的 `adapter/ProductPage.java`（索引中的 33 个文件逐字节等于 `HEAD`）；
   - 关键文件：`SecondMallHttpAdapter.java`（48,637 B, `E83F3D25…`）、`MallApiGenerationEngine.java`（31,766 B, `9BCB50D7…`）、`SecondMallDualTargetTest.java`（25,793 B, `1A3BFCCC…`）均在位。

**结论（限此范围）**：**已提交交付面零内容损失**。

## 5. 损失评估

| 对象 | 状态 |
| --- | --- |
| 已提交交付（`25b0fe7` / `ceeddec`） | **无损失**，逐字节还原（§4） |
| 第一班对 33 个文件的**未提交**改动 | **已灭失且不可恢复**：索引干净、`git stash list` 为空、无任何备份、无编辑器本地历史可用 |
| 第一班唯一存活改动（`ProductPage.java`，+34/−4） | **已保全**（补丁与原文双份，§3）；其设计方向（缺口随返回值走、缺失字段与未映射原词分开）**正确**，第二班沿用 |
| 三个运行中进程（8090/8091/8092）与 8091 日志 | 未受影响；`8091-stdout.log` 的既有修改为 F-26 残留 |

## 6. 关键判断与**未取证**（不得越过这条线说话）

- **已取证**：删除真实发生（5 秒复测稳定）；发生在工作区而非索引；窗口在 `12:09:09`–`12:10:3x`；可用 `HEAD` 逐字节还原；无 git 写操作痕迹（`git reflog` 自上轮提交后无本会话写命令）。
- **未取证（一条都不能当结论）**：
  1. **执行者**：没有进程级直证（无文件审计策略、无句柄快照、无 ETW）。同一工作区确有外部写入者（`codex.exe` pid 27920；`contract-specs` 目录 ACL 含 `CodexSandboxUsers`）——这是 **F-32 已登记的事实**，但**本次事件不能归因于它**，也不能归因于修复泳道。
  2. **方式**：一次删除/多次删除、用什么程序、是否与"镜像/覆盖式复制"有关，均未取证。
  3. **触发原因**：未取证。
  4. **是否与第一班泳道的命令有关**：未取证（已发只读询问，见 §13）。
  5. **是否还会再发生**：不可知。

## 7. 已采取的加固措施

1. 事件当场**中断**在写的修复泳道，避免写入叠加在残缺文件树上（该泳道被中断前的最后状态：`ProductPage.java` 已改，其余改动灭失）。
2. 第二班修复泳道指令加硬约束：**禁止一切删除类命令**（`Remove-Item -Recurse` / `git clean` / `git rm` / `mvn clean` / `robocopy /MIR` / 移动重命名既有文件），只许改内容与加新文件；**发现文件消失立刻停止并报告**。
3. 第二班须**按组即时备份**到仓库外 `D:\Develop_code\graduation-lane-backup\fix-round\`（`stepN.patch` ＋ 新增文件副本）⇒ 同类事件最多损失最后一组改动。
4. 建立仓库外干净基线备份（`module-clean-ceeddec`，95 文件 ＋ 清单）⇒ 即使 `git` 侧也受损，仍有第二来源。
5. 复核器 `scripts/verify-fix.ps1` 内建 **W0 并发写者守卫**：本次复核前后对 `src` 全量 sha256 取样，若运行期间发生漂移则**本次结论作废**（不把被污染的读数当证据）。
6. 本轮所有制品**立即入库并推送**（不留未跟踪状态给外部写入者）。

## 8. 不得声称（本记录带来的"禁止清单"）

- 不得声称"原因已定位/是某个进程干的"——**执行者未取证**；
- 不得声称"已彻底防住"——加固只是降低损失面，没有任何机制能阻止外部写入者；
- 不得声称第一班在 33 个文件上的工作"已找回/可找回"——**已灭失**；
- 不得声称"工作区现在是安全的"——`codex.exe` 仍在同一工作区运行。

## 9. 未取证清单

1. 删除执行者与执行方式（§6.1/6.2）。
2. 第一班在被中断前的完整命令清单与它对 S1/S2/S4/S6/W1–W3 的改法（已发询问，未收到）。
3. 事件窗口的精确秒级边界（只有 `12:09:09` 与 `12:10:3x` 两个端点）。
4. 是否有其它未跟踪文件在同期一并消失（现场只剩 `?? scripts/verify-fix.ps1` 一个未跟踪文件，无法反推"曾经有多少未跟踪文件"）。

## 10. 勘误（总控自身的测量错误，登记备查）

- **E-1（2026-09-12 12:11，已自查更正）**：事故排查中我一度读到 `git ls-files synthetic-data-generator/src` 计数 = **1**，据此怀疑"索引也被清空"。12:12 用 `git ls-files -- 'synthetic-data-generator/src'` 复核为 **94**，且 `git diff --cached` 为空 ⇒ **索引自始完整，前述读数是我的计数写法错误**（管道计数写法），**删除始终只发生在工作区**。此处保留该错误记录，以免后人据错误读数推断出错误的因果。

## 11. 变更清单

| 动作 | 对象 |
| --- | --- |
| 新增 | 本文件 `docs/acceptance/f37-worktree-deletion-20260912/README.md` |
| 恢复 | `synthetic-data-generator/src` 下 33 个被删文件（自 `HEAD` 内容，经索引） |
| 保全 | `ProductPage.java`（第一班改动，保留在工作区）＋ 补丁/原文双份备份 |
| 仓库外 | `D:\Develop_code\graduation-lane-backup\module-clean-ceeddec\`、`fix-round\`（**不入库**） |
| 看板 | `§5.1` 新增 `F-37` 行；`F-32` 行尾追加指向 `F-37` 的指针；`§6` 新增 1 条日志 |
| 不入库 | `.verify/m1-9-verify/tree-snapshot-1211/**`（gitignore） |

## 12. 下一步

1. 收第一班只读询问的回复并记入 §13 补记（**只记录、不改结论**）。
2. 第二班修复泳道交付后，用已冻结的 `scripts/verify-fix.ps1` 逐条机检 ＋ 人读，再决定 `F-35`/`F-36` 是否可关。
3. **提请用户裁决（高优先级）**：本工作区存在外部写入者，且本次已造成**不可逆损失**（未提交工作灭失）。建议二选一：① 两个 agent 分工作区（各自 `git worktree` 或独立克隆）；② 串行化（同一时刻只有一个 agent 持有本工作区）。裁决前，生成器模块的所有实施泳道一律执行 §7 的"禁用删除类命令 ＋ 分组即时备份"。

## 13. 补记

### 补记 1（2026-09-12 12:14）：被中断的第一班只读回答（**当事方自述**）

第一班（agent `02874585-…`）被中断后，按总控"只读、不得再改任何文件"的要求交回以下内容。**除第 3、4 条外均为当事方自述**，未经独立取证，仅登记事实与线索：

1. **命令清单**：自述共 9 次 `pwsh -Command`（只读 8 次 ＋ 1 次写盘，写在 `$env:TEMP\m1-9-fix\src-before.sha256`，不碰仓库）；自述**没有**执行过 `Remove-Item` / `rm` / `del` / `git clean` / `git checkout` / `git restore` / `git stash` / `git reset` / `git rm` / `mvn clean` / `Move-Item` / `Rename-Item` / `Copy-Item` / `robocopy`，也没有任何 git 写操作（只用了 `status` / `diff` / `show` / `log` / `reflog` / `ls-tree` / `cat-file`）。
2. **落盘改动**：自述通过 DSH `edit` / `write` 工具（逐文件、字面串替换，非 shell、非批量）改过 4 个既有文件；其中 `adapter/SecondMallHttpAdapter.java`、`fixture/SecondMallFakeServer.java` 在删除中一并消失，已随还原回到 `HEAD` 原样 ⇒ **这两处改动确认灭失**；`adapter/ProductPage.java` 存活（§3 已保全）。
3. **独立时间证据（本补记最有价值的一条）**：第一班自述其在删除前后执行过"`Start-Sleep 10` ＋ 两次 `git status --porcelain` 比对"，并观测到 **`adapter/` 目录 mtime = 2026-09-12 12:10:24** ⇒ 把本记录的删除窗口由"`12:09:09`–`12:10:3x`"**收紧到约 `12:10:24`**。
4. **可恢复性交叉印证**：第一班用 `git cat-file -e HEAD:<path>` 逐个核验并自述 **33/33 全在 `HEAD`** —— 与总控 §4 的恢复结果一致（两条独立口径互证）。
5. **归因**：第一班自述"删除不是我造成的"，并称无法判定是哪个进程所为。按纪律记为**当事方自述**，**不作独立证据**；§6"执行者未取证"的结论**不变**。
6. **交回的设计**（总控已转交第二班用于重放）：S2 常量拆分（`CONFIG_FORMAT_KEY` / `CONFIG_FORMAT_VALUE`）＋ `capabilities()` 单一门控（把原来**并列的第二个 `if`** 即 BEHAVIOR 分支移进同一 `if`，那正是 S2 的旁路）；S3 保留 `ABSENT` 并注明"静态声明、未做探测"；S1 整块删除 `ORDER_STATE_ALIAS` 与别名拼接；S5/S7 删累计型实例 `Map` ＋ `listProducts` 内用本次读取的局部收集器 ＋ `toProduct` 改三参 ＋ `sortedDistinct`；S6 去 `catalogPricesBySku` 逐件请求 ＋ 单价改读下单应答 `lines[].unit_price_cents` ＋ 读不到抛 `MallOperationException`；W1–W3 改严格断言；两条新证据用例（`跨运行`、`价格缺失`）＋ 夹具改动（`putItemWithoutPrice` / `overrideItemState` 允许 `null` / `itemNode` 条件写字段——随夹具还原丢失，需重做）。
   **总控更正一条**：第一班拟对"商城没给 state"返回字面量 `"UNKNOWN"` —— 这同样是**商城没给过的值**（与 S1 同类问题），**不采用**；缺 state 保持 `null`（与 `ProductPage` Javadoc"未映射原词对应 `status()==null` 的件数"一致）。

### 补记 2（2026-09-12 12:14）：归因排查的**排除项与线索**（只读取证，**不是归因结论**）

| 排查项 | 口径 | 结果 |
| --- | --- | --- |
| Windows Defender | `Microsoft-Windows-Windows Defender/Operational` 11:55–12:20 ＋ `Get-MpThreatDetection` 近 1 小时 | 窗口内仅 1 条事件（12:07:53 id=2010「使用云保护获取附加安全智能」，非处置动作）；**威胁检测 0 条** ⇒ **不支持"杀软隔离"假设** |
| 本会话 git 写命令 | `git reflog` | 自上轮提交后无写操作出现 ⇒ 非本会话 git 命令所致（与 F-32 同期结论一致） |
| 外部写入者活动 | `~/.codex/**` 文件 `LastWriteTime` 落在 12:00–12:20 | **`12:09:16` 有 12 个文件被写入**（`plugins\cache\openai-curated-remote\**` 安装元数据）⇒ 外部写入者在**删除窗口内确有活动**，但**与该删除的因果未取证** |
| 索引侧 | `git diff --cached` | 0 行 ⇒ 删除未进索引（这是"能从 `HEAD` 逐字节还原"的前提） |
| 两班写入面 | `git status --short` / 校验和 | 第一班改的 4 个文件中 2 个被删、1 个存活、1 个未落盘；删除面含**未被告知修改**的 21 个测试文件 ⇒ 删除**不是**按"修复允许清单"范围发生的 |

> 说明：以上是**排除项与线索**，不构成归因。§6「执行者未取证」与 §8「不得声称原因已定位」**继续有效**。
> **【已被补记 3 取代，见下】——就本次 33 个文件删除而言，归因已于 2026-09-12 12:5x 成立；上文这段声明以补记 3 为准。**

### 补记 3（2026-09-12 12:5x）：**归因成立——执行者是总控自己的复核器 `verify-fix.ps1`**（以此为准）

**结论**：2026-09-12 `12:10:24` 删除 33 个工作区文件的执行者，是**总控本人写的复核器 `docs/acceptance/m1-9-second-adapter-20260912/scripts/verify-fix.ps1`**（一个程序，不是外部写入者、也不是任何一班泳道）。§6「执行者未取证」、§8「不得声称原因已定位」、§9 第 1 条就**本次 33 文件删除**而言**作废**，以本补记为准。

**机制（一行可复现）**：该脚本的读文件 helper 命名为 `Rd`，而本机 PowerShell 7.6.6 的内置别名 `rd` = `Remove-Item`，**PS 命令解析顺序是「别名 > 函数 > cmdlet」**⇒ 脚本里每一条 `Rd <路径>` 实际执行的是 `Remove-Item <路径>`：既**删除该文件**，又返回空串。

**证据链（五环，全部可复核）**：

| # | 证据 | 出处／数值 |
| --- | --- | --- |
| 1 | 脚本落盘时刻 | `verify-fix.ps1` 文件 `LastWriteTime` = **`12:10:18`**，13,174 B |
| 2 | 该脚本的**首次（冒烟）运行**在同一分钟内发生，产物留存 | `.verify/m1-9-verify/verify-fix-smoke.txt`（2,095 B）与 `verify-fix-smoke.err.txt`（388 B），两者 `LastWriteTime` 均 = **`12:10:24`**（＝第一班独立观测到的 `adapter/` 目录 mtime，§13 补记 1 第 3 条） |
| 3 | 删除面**恰好等于**脚本的读取面 | v1 中的 `Rd` 调用点：`:83 $ad = Rd $adapter`（删 `adapter/SecondMallHttpAdapter.java` 1 个）、`:108` 遍历 `engine/**` 所有 `*.java`（删 **11** 个）、`:118` 遍历 `test/java/**` 所有 `*.java`（删 **21** 个）⇒ 1＋11＋21 = **33**，与 §4「全部 21 个 `test/java` ＋ 12 个 `main/java`」**逐项吻合** |
| 4 | 运行产物自己就写着 `Remove-Item` | `verify-fix-smoke.err.txt` 原文首行 = `Remove-Item: D:\…\scripts\verify-fix.ps1:136`，帧 `136 |  $ta = Rd $testA`，报错「找不到路径…SecondMallAdapterOperationsTest.java，因为该路径不存在」——即 `:83/:108/:118` 已把文件删光，脚本走到 `:136` 才因"文件没了"而抛错终止 |
| 5 | **同一缺陷当场复现**（不是推理，是实测） | 2026-09-12 `12:33`，v2 初稿以同一 helper 名运行：`git status --porcelain` 实测 `D=33`，删除面与第 3 环**完全相同**；stderr 同样以 `Remove-Item: …verify-fix-v2.ps1:146`＋`$ta = Rd $testA` 报错收尾 |

**对原记录中若干推断的更正**：

1. §13 补记 2 第 5 行「删除面含**未被告知修改**的 21 个测试文件 ⇒ 删除不是按修复允许清单范围发生的」——**该推断方向错了**：删除面不是按"允许清单"发生，而是按**脚本的读取面**发生（凡脚本要读的文件都被删）。
2. 原排查把嫌疑集定为「外部写入者（`codex.exe`）」＋「两班泳道的命令」，**漏了总控自己的工具链**；而这次"冒烟运行"本身**没有登记为命令**（所以第一班自述"无删除类命令"为真，且我当时也没把它列入嫌疑）。
3. `~/.codex/**` 于 `12:09:16` 的写入活动与本删除**无关**（时间、对象、机制均不符）；F-32 的其余观察（`contract-specs/README.md` 于 11:53 被改写、11:56/12:0x 消失、看板本体 12:1x 消失）**不由本机制解释**（v1 只读 generator 的 `src` 与 `-LogPath`，不读这两个文件）⇒ **那些事件的归因仍然未取证**，`codex.exe` 线索亦仍未取证。
4. §8「不得声称第一班的工作已找回」**继续有效**（那次灭失是真的）；但 **`12:33` 那次事故的损失为零**：第二班 8 个文件的改动经其自备份全量恢复，工作区与备份 `sha256` **8/8 全等**（`SecondMallHttpAdapter 2B0A2AD9…`、`MallApiGenerationEngine 41ACFF06…`、`SecondMallAdapterOperationsTest 9A011837…`、`SecondMallDualTargetTest 56A10C25…`、`SecondMallFakeServer 85696943…`、`ExternalProduct 0F0875CC…`、`MallStatusVocabulary EAAD61AC…`、`ProductPage 2BB4DF5F…`）⇒ §7 第 3 条"分组即时备份"这条加固**已被实战验证有效**。

**加固（已落地并入库，提交 `0458f16`）**：复核器 v2 三件一起做——① 读文件 helper 由 `Rd` 改名 `Read-SrcText`；② 启动自检：任何 helper 若被解析成 Alias／未定义即 `throw`，拒绝运行（位置纪律：必须放在**全部 helper 定义之后**，否则会把"尚未定义"误报成"被遮蔽"）；③ helper 内"存在 ＋ 非空"双断言——文件没了立刻抛错，**不再返回空串让判据静默通过**；W8 另加正向对照，禁止空洞 PASS。**验证**：v2 连续两次运行，`synthetic-data-generator/src` 树 **94 个文件 / 812,542 B 运行前后完全不变**；启动自检在错位时确实拒绝运行（实测触发过一次）。

**据此新增的判据纪律（写入验收方法）**：任何"零命中即通过"的机械判据，**必须**配一条正向对照（读到的文件里应当存在的东西），否则"取证对象被删空"会表现为**假绿灯**——本次事故中 v1 的 W2/W3/W4/W5/W6/W7 全部报 FAIL（因为读到空串）而 **W8 报了 PASS**（因为"零命中"恰好满足其判据），这就是假绿灯的实例。