# m3-jdk8fix 生效证据归档（JDK 8 API 闸门）

> 2026-09-14 建立。本目录是 **`spark-jobs/pom.xml` build 注释明文点名**的两份「生效证据」的**仓内唯一所有者**。
> 归档来源：`D:\Develop_code\GraduationProject-wt\m3-jdk8fix\.verify\`（**仓外 worktree，用户原话：不要把文件到处创建**）。
> 归档动作由总控执行，**归并后逐字节校验通过**；仓外 `.verify/` 原件随后删除（批准项 W2）。

## 1. 为什么这个目录必须存在

`spark-jobs/pom.xml` 的 JDK 8 闸门注释（②生效证据）原先写的是 **worktree 内相对路径 `.verify/final-positive-control.log`**：

- 该 worktree 在**仓外**（`D:\Develop_code\GraduationProject-wt\m3-jdk8fix`）；
- 而仓内的 `.verify/` 被 `.gitignore:61` **整体忽略** ⇒ 这两个文件**在仓库内根本不存在、也永远无法入库**。

⇒ 结论：**「已提交的 pom.xml 注释引用了永远无法入库的路径」**（L6 只读核查 §6 缺陷 ⑤-1）。
删掉 worktree 就等于灭失「已入库源码明文点名」的证据；因此处置顺序必须是 **先归并入仓、改注释指向、再谈删除**。
本目录即该修复的落点：**注释已改指本目录**，证据随仓库版本化。

## 2. 归档清单

| 文件 | 字节 | 作用 |
|---|---|---|
| `verify/final-positive-control.log` | 2653 | **阳性对照**：最终配置下把 `MetricExportJob.scala` 的 `_.trim.isEmpty` 临时改回 `_.isBlank`，必须**编译失败并点名 isBlank** |
| `verify/final-effective-scalac-args.log` | 140080 | **实际传给 scalac 的选项行**（`-release` 出现次数／取值／谁生效） |
| `verify/` 其余 22 个文件 | — | 同批取证（bogus-arg 探针、effective-pom、fresh-compile 等） |
| `m3-jdk8fix-worktree-diff.patch` | 5131 | 该 worktree 相对其 HEAD `f26be26` 的 4 个已修改文件差异（**只读取证**） |
| `INVENTORY.txt` | — | 全目录逐文件 `sha256 + 字节数` 清单（可复算） |

`verify/` 共 **24 个文件**（其中 **11 个不在 git 里**，即 worktree 内未被跟踪的取证件）。

## 3. 逐字节校验（删除原件的前置条件）

| 项 | 源文件数 | 目标文件数 | 逐文件 sha256 比对 | 结论 |
|---|---|---|---|---|
| `m3-jdk8fix\.verify\` → `本目录\verify\` | 24 | 24 | diff = 0 | **BYTE-IDENTICAL** |

校验用**绝对根**逐文件算 `sha256` 后 `Compare-Object`（首轮曾因把相对路径当根而误报 4 项 MISMATCH，属**比对脚本 bug 非复制失败**，改用绝对根后 5/5 全绿；详见 `docs/acceptance/v25-stray-consolidation-20260914/EXECUTION-20260914.md`）。

## 4. worktree 的源码独有价值核定

该 worktree（detached `f26be26`）有 **4 个已修改 tracked 文件**，源码侧**无独有价值**：

| 文件 | 与主仓 HEAD 的关系 |
|---|---|
| `spark-jobs/pom.xml` | **逐字节相同**（sha256 `148180B477FEEE94…C6ED520E`，总控 2026-09-14 实测） |
| `MetricExportJob.scala` / `P2TestSupport.scala` | 与主仓已有 blob 相同（L6 哈希判定） |
| `WarehouseNamespaceSpec.scala` | **旧变体**，主仓已在其上追加 `srcSys` 形参（主仓更新） |

⇒ 该 worktree 的独有内容**只有 `.verify/` 这批证据**，而它们现已入仓。

## 5. 状态与未取证

- **本目录**：`提交完成` ✅（内容已在版本库）；**未做**人工价值判读（仅机械哈希判定）。
- **仓外 worktree `m3-jdk8fix`**：**本批未删**（用户批准范围只含 W1/W3/S1/S2/S3/S4 六项）⇒ 其独有内容已全部入仓，**现为「可删候选」，待用户逐项批准**；空父容器 `D:\Develop_code\GraduationProject-wt` 一并保留。
- **未取证**：`verify/` 内 22 个非点名文件的具体价值（未逐一人工判读）；jar/zip 内部未展开。
