# M1-9 ② 修复轮：交付面与恢复件（修复泳道 · 第二班）

本目录是**恢复件存放点**，不是交付说明。因为本轮取证期间工作区的
`synthetic-data-generator/src/**` **被外部进程整片删除过两次**（详见下方"事故记录"），
修复泳道把 8 个交付文件的副本与补丁放在 `docs/` 下（`docs/**` 未被删除过），
以便在删除进程停止后**逐字节**恢复交付面，且任何人可独立校验。

## 1. 交付面是什么（基线 `ceeddec` → 工作区）

| # | 文件（相对仓库根） | 字节 | sha256（前 16 位） |
|---|---|---|---|
| 1 | `synthetic-data-generator/src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java` | 54960 | `2B0A2AD9E737DAC7` |
| 2 | `synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ProductPage.java` | 3333 | `2BB4DF5FC16F00E1` |
| 3 | `synthetic-data-generator/src/main/java/com/graduation/generator/adapter/MallStatusVocabulary.java` | 2013 | `EAAD61AC47B45645` |
| 4 | `synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ExternalProduct.java` | 2671 | `0F0875CC75DB7C69` |
| 5 | `synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiGenerationEngine.java` | 33398 | `41ACFF060B09ABB6` |
| 6 | `synthetic-data-generator/src/test/java/com/graduation/generator/fixture/SecondMallFakeServer.java` | 27132 | `85696943A5BC0B0E` |
| 7 | `synthetic-data-generator/src/test/java/com/graduation/generator/adapter/SecondMallAdapterOperationsTest.java` | 32608 | `9A011837D38F5AC8` |
| 8 | `synthetic-data-generator/src/test/java/com/graduation/generator/engine/SecondMallDualTargetTest.java` | 31066 | `56A10C25BD9A2511` |

变更规模（`git diff --stat HEAD -- synthetic-data-generator/src`）：**8 files changed, 583 insertions(+), 162 deletions(-)**。

## 2. 恢复步骤（外部删除进程停止后执行）

在仓库根执行，按顺序三步：

```powershell
# 步骤 1：把被删的其余文件从基线提交恢复（它们本来就与 ceeddec 一致）
git checkout -- synthetic-data-generator/src

# 步骤 2：把 8 个交付文件覆盖回来（本目录 files/ 内是逐字节副本）
Copy-Item docs\acceptance\m1-9-second-adapter-20260912\fix-round-recovery\files\*.java -Destination <见下表> -Force
# 或等价做法：git apply delivered-fix.patch

# 步骤 3：校验（应输出 IDENTICAL = True）
$tmp=[IO.Path]::GetTempFileName(); cmd /c "git diff > `"$tmp`""
(Get-FileHash $tmp -Algorithm SHA256).Hash -eq 'EBE852BB9856E8D77FB49E27E435672953D2769117254370A28A0F8A71065EBA'
```

`files/` 的目标路径与第 1 节表格一一对应（文件名相同，目录不同）：
第 1–5 项 → `src/main/java/com/graduation/generator/{adapter,engine}/`；
第 6 项 → `src/test/java/com/graduation/generator/fixture/`；
第 7–8 项 → `src/test/java/com/graduation/generator/{adapter,engine}/`。

## 3. 交付补丁的指纹

| 文件 | 内容 | 字节 | sha256（前 16 位） |
|---|---|---|---|
| `delivered-fix.patch` | `git diff`（基线 `ceeddec` → 交付工作区）全量，含 8 个文件 | 90516 | `EBE852BB9856E8D7` |
| `step1-main-rewrite.patch` | 中间存档（S1/S2/S3/S4/S5/S7 的 main 侧改动） | 55784 | `B2AF88C55D257E7B` |
| `e2-run3.txt` | 通过时的 E2 原始输出（见第 4 节） | 24178 | `9C8EB03EDCC16DB8` |

`delivered-fix.patch` 的 sha256 与"恢复后的工作区 `git diff` 输出的 sha256"**完全一致**，
这是"恢复面 == 交付面"的机械证明（同一哈希 `EBE852BB…5EBA`）。

## 4. E2 取证的有效性说明（重要）

`e2-run3.txt` 是在**第二次删文件之前**跑出来的，且日志可自证它编译的是交付面：

- 该次 E2 的 `testCompile` 行是 `Changes detected - recompiling the module! :source` +
  `Compiling 21 source files with javac [debug release 17]`，即**重新编译了全部测试源**；
- 交付面 8 个文件里最新的一次写入是 `12:21:34`（`SecondMallAdapterOperationsTest.java`），
  该次 E2 的 `Finished at: 2026-09-12T12:22:20+08:00`，**晚于**全部源码写入；
- 结论行 `Tests run: 113, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（`Total time: 22.995 s`）。

也就是说：这份日志对应的工作区状态与 `delivered-fix.patch` 是同一状态（同一哈希），
但**复核者应在自己的环境里重跑一次 E2** 再下结论 —— 修复泳道不宣布"验收通过"。

## 5. 事故记录（供复核者判断取证范围）

| 时刻（本机 2026-09-12） | 事件 |
|---|---|
| 12:2x（第一班事故，已由需求方恢复） | `src/**` 被整片删除一次 |
| 12:23 前后（第二班，第 1 次） | 复核脚本运行到 W9 时抛"路径不存在"，`git status` 显示 33 个 `D`（含适配器、引擎、全部测试）；`fix-round/files/` 备份完好，git 恢复 + 覆盖后 `git diff` 哈希与交付补丁一致 |
| 12:24 前后（第二班，第 2 次） | 再次删除，`src` 下 `.java` 由 94 降到 59；再次按同样步骤恢复，哈希仍一致 |

被删除后仍残留的文件（第二次）为：`adapter/{ExternalProduct,MallStatusVocabulary,ProductPage,MallTargetAdapter,ReferenceMallHttpAdapter,…}.java`、`config/GeneratorBeans.java` 等 ——
**删除范围不可预期**，因此本目录的副本是恢复交付面的唯一可靠依据（`D:\Develop_code\graduation-lane-backup\fix-round\` 下亦有一份同哈希副本）。

修复泳道未执行任何删除类命令（无 `Remove-Item -Recurse`／`git clean`／`git rm`／`mvn clean`／`robocopy /MIR`），
也未移动或重命名任何既有文件；删除是外部进程所为。
