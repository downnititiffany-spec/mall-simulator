# V25 离仓散落文件归并 —— L6 只读核查报告（待逐项批准）

- 核查通道：**L6（文档/证据通道）**
- 核查日期：2026-09-14
- 证据快照提交：**HEAD = `fd3cee5b806b3570ce273e2346e1d14014e0dae2`**，同刻 `origin/main` **= `fd3cee5b806b3570ce273e2346e1d14014e0dae2`**（两者同点）
- 项目：《基于 Spark 大数据平台和智能分析模型的电商用户行为分析系统设计与实现》
- 仓库根：`D:\Develop_code\GraduationProject`

---

## 0. 本文件的性质与边界（先读）

**本通道不做任何改动。** 本次核查全程只读：未删除、未迁移、未重命名任何离仓目录或文件；未执行 `git worktree remove/prune`；未执行任何 git 写操作（`add/commit/push/stash/checkout/reset`）；未对 3306 做任何写入或 DDL；未启停 8090/8091/8092、数据库、HDFS、集群；未改动 `analytics-server/**`、`mall-simulator/**`、`synthetic-data-generator/**`、`scripts/**`、`contract-specs/**`、指导书或看板。

**本通道允许写入的唯一根**：`D:\Develop_code\GraduationProject`，且唯一输出目录为本目录
`docs\acceptance\v25-stray-consolidation-20260914\`（本 `README.md` + `raw\`）。临时文件仅落 `%TEMP%`。

**本文结论的效力**：本文是**取证报告**，不是执行许可。第 4 节列出的每一项都必须由用户**逐项批准**后才可由执行通道动手。用户已裁定模式：

> **模式 A：冻结新增 ＋ 分类归并 ＋ 删除前逐项报批。**

### 0.1 口径声明（三处必须说清，否则数字会被误读）

1. **「可从 git 恢复」的判定口径。** 判定方法为：对每个离仓文件同时计算两种哈希 —— ① `git hash-object`（带 clean 过滤器，等同 `git add` 语义）与 ② `git hash-object --no-filters`（原始字节）—— 再用 `git cat-file --batch-check` 在**整个对象库（任意提交可达）**中查存。**两者命中其一即判为「可从 git 恢复」。**
   - 必须取并集的原因（本轮实测教训）：仓库无 `.gitattributes`，且有 46 个已入库的 `.log/.txt` 证据文件的 **blob 内部就是 CRLF**。对这类文件只用带过滤器的哈希会误判为「不在对象库」；只用 `--no-filters` 又会把绝大多数以 LF 入库的文件误判为「不在对象库」。单独使用任一单口径都会产出**错误结论**，本轮两种单口径的错误中间结果已保留在 `raw/redundancy-bytes.txt`（误判为 32.6%）与 `raw/redundancy-bytes-CORRECTED.txt`（误判为 58.1%）以备对照，**最终口径见 `raw/redundancy-FINAL-union.txt`**。
2. **「构建/缓存」排除正则**：`\\target\\|\\node_modules\\|\\.git\\|\\build\\|\\dist\\|\\.idea\\|\\.bloop\\|\\.metals\\`。该正则用于生成 `*-sha256-src.txt` 那一份「源码口径」清单。**注意**：该正则会把 `docs/acceptance/**` 下名字里带 `build` 的已入库证据目录一并划入「非源码」桶（例如 `_gp_headcheck` 的 295 个非源码文件里有 84 个属于 `docs/acceptance/**`）。**不得**据此认为这 295 个都是编译产物。
3. **工作树脏污。** 核查时刻主仓工作树有 **395 条**未提交条目（其他通道在途作业，含 `analytics-server/**`、`mall-simulator/**`、`scripts/run-demo.ps1`、`scripts/smoke-pipeline.ps1`、`docs/README.md` 及未跟踪的 `after-reboot-cleanup.ps1`、`alibaba-fix-src.txt` 等，见 `raw/mainrepo-status-porcelain.txt`）。因此**一切「独有内容」比较都基于已提交 blob，而非工作树文件**。

   **核查期间其他通道正在提交，HEAD 持续前进**：`f00462c` → `83afc49` → `fd3cee5` → **`1cb318ba`**。本文所有证据**锚定在快照 `fd3cee5b806b3570ce273e2346e1d14014e0dae2`**。

   **已做的复核**：在收尾时（HEAD/`origin/main` 均已前进到 `1cb318ba`）重跑了关键判定，结论不变 —— ① `fd3cee5` 仍是 `origin/main` 的祖先（`merge-base --is-ancestor` exit 0）；② 三个 worktree 的 HEAD（`f00462c`、`f26be26`、`dba4381`）**依然**同时是 `origin/main` 与当前 `HEAD` 的祖先；③ 7 个离仓项的路径与文件数**逐项未变**（2283 / 1526 / 1783 / 562 / 382 / 182 / 1）。③ 同时也证明**本通道全程未删除、未迁移任何一项**。
   如执行阶段 HEAD 再次前进，仍建议对 §4 拟执行的命令重跑 §3.1–§3.3 的取命令复核。

---

## 1. 结论摘要表

**口径**：体积/文件数由 `Get-ChildItem -Recurse -File -Force | Measure-Object Length -Sum` 实测；「可从 git 恢复」按 §0.1-1 的并集口径实测。总规模 **329.9 MB / 6719 文件**。

| # | 路径 | 体积 | 文件数 | 含正式库数据/口令 | 是否被仓内引用 | 仓内是否已有其独有内容 | 建议处置 | 风险 |
|---|---|---|---|---|---|---|---|---|
| **W1** | `D:\Develop_code\_gp_headcheck` | 93.77 MB | 2283 | 无转储、无新增口令（含已入库默认口令的 `application.yml` 副本） | **是**，1 处（看板 `V2.5:146`，即本次裁决本身） | **否**：2072/2283 可从 git 恢复（恰等于 HEAD 全量 tracked 数），211 项不可恢复者**全部**是 `synthetic-data-generator/target/`(209) + `synthetic-data-generator/logs/generator.log`(1) + `.git` 指针文件(1) | **可直接删除**（须先 `worktree remove`） | 低 |
| **W2** | `D:\Develop_code\GraduationProject-wt\m3-jdk8fix` | 46.93 MB | 1526 | 无转储 | **是**，105 处 | **是 —— 必须先归并**：`.verify/` 下 11 个文件不可从 git 恢复，其中 `final-positive-control.log`(2653 B)、`final-effective-scalac-args.log`(140080 B) **被已入库的 `spark-jobs/pom.xml` 注释点名引用为「生效证据（worktree 内）」** | **不得删除**，先归并 `.verify/` 证据后另批 | **高** |
| **W3** | `D:\Develop\GraduationProject\.f88-baseline-wt` | 55.68 MB | 1783 | 无转储、无口令 | **是**，2 处 | **否**：1691/1783 可从 git 恢复（恰等于其 tracked 数），92 项不可恢复者 = 2 个 `target/` 目录(91) + `.git` 指针(1) | **可直接删除**（须先 `worktree remove`；父目录 `D:\Develop\GraduationProject` 仅含此项） | 低 |
| **S1** | `D:\p103-e3` | 94.26 MB | 562 | **有**：`analytics_meta_p103.sql` 22 表 / 22 `INSERT` / ~1689 行值组，含 `sys_user`（**3 条 bcrypt `$2a$10$` 口令哈希**：admin/operator/analyst）、`user_session`、`ai_query_history`、`operation_audit_log`；`base/BOOT-INF/classes/application.yml` 含明文默认口令（`meta_app_pw_2026` 等） | **是**，143 处（含 `p1-03-*` 的 `e3-acceptance.ps1:7,8,74-82` 与 `raw/e3-02-instance-run{2,3,4,5}-final-stdout.log`） | **部分**：98/562 可从 git 恢复（30.76 MB，其中 `base/BOOT-INF/lib/*.jar` 与已入库 `docs/acceptance/m3-step8-parity-20260912/raw/post/export-import-rehearsal/build/lib/*` 逐字节相同）；**464 文件 / 63.50 MB 不可恢复** | **两选项待裁决**（删除前必须先归并） | **高**（敏感数据） |
| **S2** | `D:\p105-e3` | 36.22 MB | 382 | **有**：`analytics_meta_p105.sql` 22 表 / 22 `INSERT` / ~1689 行值组（与 p103 同源但字节不同），`analytics_metric_p105.sql` 11 表；`e3-p105.ps1` 内含明文口令字面量 `metric_pub_pw_2026` | **是**，135 处（`p1-05-manifest-source-20260911/raw/*`、`p1-06-golden55-20260912/**` 等） | **部分**：82/382 可从 git 恢复（30.61 MB，主要是已入库 fat-jar 依赖）；**300 文件 / 5.61 MB 不可恢复** | **两选项待裁决**（删除前必须先归并） | **高**（敏感数据） |
| **S3** | `D:\Develop_code\graduation-lane-backup` | 3.04 MB | 182 | 无转储；脚本/日志中的口令为已入库默认口令 | **是**，14 处（关键：`docs/acceptance/ct-batch-20260912/PLAN.md:112`、`docs/acceptance/ct-batch-20260912/lane/PROGRESS.md:16`） | **是 —— 必须先归并**：53 文件 / 1.84 MB 不可从 git 恢复（4 个 `*.patch`、`retired-*`/`pre-round2-*` 文件、`manifests/*`、`p2-01/logs/*`） | **先归入已提交的 `docs/acceptance/` 通道目录**，归并后另批删除 | 中 |
| **S4** | `D:\Develop_code\.verify` | 2,778 B | 1 | 无转储；含 `jdbc-url` 形态行 | **否**：精确路径在 tracked 文件中 **0** 命中 | **是**（单文件内容不可从 git 恢复；与仓内 `docs/acceptance/m1-4-u1u3-20260912/raw/u1u3-cli-planappend-20260912-100543.log` 是**不同**副本：3349 B / `AB6DE699…`） | 归并入 `docs/acceptance/m1-4-u1u3-20260912/raw/`，或直接删除 | 低 |

**一句话结论**：7 项里 **2 项（W1、W3）可直接删**；**2 项（W2、S3）有被已入库文档/源码点名的独有内容，必须先归并**；**1 项（S4）低风险**；**2 项（S1、S2）含正式库真实数据与口令形态内容，是本次风险最高的一项**，必须在两选项中裁决且**绝不能无归并直接删**。

---

## 2. 四级状态（分开写）

本次任务**未进入任何一级验收**，逐级状态如下：

| 等级 | 状态 | 说明 |
|---|---|---|
| **未验收** | 不适用 | —— |
| **部分验收** | 不适用 | —— |
| **限定验收** | **本次达到的上限，且仅对本通道的「只读取证」部分成立** | 成立条件：`raw/` 内 47 个证据文件已落盘且可复算；摘要表 7 项全部有实测支撑；未取证项已在 §5 逐条列明并说明原因。**限定范围**：只覆盖「离仓散落物的现状取证」，**不含**任何删除/迁移动作的正确性、不含归并后的完整性、不含任何功能或性能结论。 |
| **完全验收** | **未达到，且本通道无权达到** | 未达到的原因：① 处置动作**一项都未执行**（模式 A 要求逐项报批）；② S1/S2 的处置方案尚未裁决，属**用户决策**而非通道可自证事项；③ W2、S3 归并后的可回溯性需在归并动作完成后由独立通道复核。 |

---

## 3. 逐项实测证据（命令 + 原始输出）

原始输出全部落盘于 `raw\`，下文只摘录关键行。

### 3.1 全量基线：worktree 注册表

```powershell
git -C D:\Develop_code\GraduationProject worktree list --porcelain
```
输出（`raw/git-worktree-list-porcelain.txt`，16 行）：

```
worktree D:/Develop_code/GraduationProject
HEAD f00462c51023ce5b12e0b649e7813ed789f6f17e
branch refs/heads/remediation/r1-boundary

worktree D:/Develop/GraduationProject/.f88-baseline-wt
HEAD dba438135271de18b289caebaf500f0590a0345a
detached

worktree D:/Develop_code/_gp_headcheck
HEAD f00462c51023ce5b12e0b649e7813ed789f6f17e
detached

worktree D:/Develop_code/GraduationProject-wt/m3-jdk8fix
HEAD f26be2624262a12d92f0cdc7a97415c4f07a1b35
detached
```

⇒ 3 个离仓 worktree 的管理目录全部挂在 `D:\Develop_code\GraduationProject\.git\worktrees\` 之下（管理目录名分别为 `-f88-baseline-wt`、`_gp_headcheck`(近似)、`m3-jdk8fix`）。

### 3.2 「独有提交」判定 —— 三个 worktree 全部无独有提交

```powershell
git -C D:\Develop_code\GraduationProject merge-base --is-ancestor <WT_HEAD> origin/main   # exit 0
git -C D:\Develop_code\GraduationProject branch -r --contains <WT_HEAD>
```
实测结果（`raw/snapshot-head.txt`）：

| worktree | 现场 HEAD | 与登记一致 | 是 `origin/main` 祖先 | 含它的远端分支 |
|---|---|---|---|---|
| `_gp_headcheck` | `f00462c51023ce5b12e0b649e7813ed789f6f17e` | 是 | 是（exit 0） | `origin/HEAD -> origin/main`、`origin/main` |
| `m3-jdk8fix` | `f26be2624262a12d92f0cdc7a97415c4f07a1b35` | 是 | 是（exit 0） | `origin/HEAD -> origin/main`、`origin/main` |
| `.f88-baseline-wt` | `dba438135271de18b289caebaf500f0590a0345a` | 是 | 是（exit 0） | `origin/HEAD -> origin/main`、`origin/main` |

⇒ **三个 worktree 均无独有提交**，其提交内容全部已被 `origin/main` 覆盖。

### 3.3 工作树洁净度（权威口径）与独有内容

```powershell
git -C <WT> status --porcelain -uall
git -C <WT> status --porcelain --ignored=matching -uall | Where-Object { $_ -match '^!!' }
```
实测：

| worktree | 已修改/未跟踪（非忽略） | 忽略条目 |
|---|---|---|
| `_gp_headcheck` | **0** | `synthetic-data-generator/logs/`、`synthetic-data-generator/target/` |
| `m3-jdk8fix` | **4**（全部为已修改的 tracked 文件，0 未跟踪） | `.verify/`、`spark-jobs/hs_err_pid52716.log`、`spark-jobs/target/` |
| `.f88-baseline-wt` | **0** | `analytics-server/metric-analysis/target/`、`analytics-server/platform-common/target/` |

**「可从 git 恢复」并集口径实测**（`raw/redundancy-FINAL-union.txt`）：

| 项 | 文件数 | 总体积 | 可从 git 恢复 | 不可从 git 恢复 |
|---|---|---|---|---|
| `_gp_headcheck` | 2283 | 93.77 MB | **2072** / 91.55 MB（97.6%） | 211 / 2.22 MB |
| `m3-jdk8fix` | 1526 | 46.93 MB | 1359 / 44.05 MB（93.9%） | **167 / 2.88 MB** |
| `.f88-baseline-wt` | 1783 | 55.68 MB | **1691** / 55.16 MB（99.1%） | 92 / 0.52 MB |
| `p103-e3` | 562 | 94.26 MB | 98 / 30.76 MB（32.6%） | 464 / 63.50 MB |
| `p105-e3` | 382 | 36.22 MB | 82 / 30.61 MB（84.5%） | 300 / 5.61 MB |
| `graduation-lane-backup` | 182 | 3.04 MB | 129 / 1.20 MB（39.6%） | 53 / 1.84 MB |
| `D:\Develop_code\.verify` | 1 | 2,778 B | 0 | 1 |

**不可恢复项的成分**（`raw/*-not-recoverable-from-git.txt`）：

- `_gp_headcheck`：209 × `synthetic-data-generator/target/`、1 × `synthetic-data-generator/logs/`、1 × `.git` ⇒ **全部为构建产物与忽略日志，无独有源码/证据**。
- `.f88-baseline-wt`：52 × `analytics-server/metric-analysis/`、39 × `analytics-server/platform-common/`、1 × `.git` ⇒ 同上（两个 `target/`）。
- `m3-jdk8fix`：154 × `spark-jobs/target/`、11 × `.verify/`、1 × `spark-jobs/hs_err_pid52716.log`、1 × `.git` ⇒ **其中 `.verify/` 11 项构成真实独有内容，见 §3.4**。

### 3.4 W2 的独有内容 —— 本次核查最重要的发现（并推翻了一个既有结论）

三个 worktree 的 **4 个已修改文件**内容判定（`raw/m3-jdk8fix-diff.patch`、`raw/m3-jdk8fix-status-porcelain.txt`）：

| 文件 | 工作树改动 | 判定 |
|---|---|---|
| `spark-jobs/pom.xml` | `maven.compiler.source/target` 17→8，scala-maven-plugin 增加 `<release>8</release>`，加中文注释说明 JDK8 API 闸门归属 | 工作树 blob 与主仓 HEAD 的 `2a9bf46…` **逐字节相同**（该修复已入库为 `f8f1ea3 fix(spark-jobs,m3): F-80 修复（构建目标对齐集群 JDK 8）并集群闭环实证 10/10`） |
| `MetricExportJob.scala` | `_.isBlank` → `_.trim.isEmpty` | 与 HEAD `a837a25…` **逐字节相同** |
| `P2TestSupport.scala` | `ProcessHandle.current().pid()` → `ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@')` | 与 HEAD `9d9ee16…` **逐字节相同** |
| `WarehouseNamespaceSpec.scala` | `Files.readString(path, UTF_8)` → `new String(Files.readAllBytes(path), UTF_8)` | 工作树 `aff71b6…` vs HEAD `6a947c6…` **不同，但主仓更新（领先）**：主仓为 `TradeDwdJob.orderDetailInsertSql(ns, "20260901", srcSys)`，工作树为 `(ns, "20260901")` |

⇒ **4 个已修改文件的源码内容均无独有价值**（3 个与 HEAD 完全相同，1 个是主仓已在其上继续演进的旧变体）。三个 worktree 的这批改动**全部已在对象库中**，`git cat-file` 可正常解析。

**但 `.verify/` 是另一回事 —— 实测：**

```powershell
# 仓内 .verify\
Test-Path D:\Develop_code\GraduationProject\.verify\final-positive-control.log      # -> absent
# m3 工作树 .verify\
D:\Develop_code\GraduationProject-wt\m3-jdk8fix\.verify\final-positive-control.log  # -> FOUND, 2653 B
D:\Develop_code\GraduationProject-wt\m3-jdk8fix\.verify\final-effective-scalac-args.log # -> FOUND, 140080 B
D:\Develop_code\GraduationProject-wt\m3-jdk8fix\.verify\package-jdk8fix.log         # -> FOUND, 3911 B
D:\Develop_code\GraduationProject-wt\m3-jdk8fix\.verify\x-compile.log               # -> FOUND, 111566 B
```

而被**已入库**的 `spark-jobs/pom.xml` 在注释中明确点名了这两个文件（实测行号与原文）：

```
L68: ② 生效证据（worktree 内）：.verify/final-positive-control.log —— 最终配置下把
L70: .verify/final-effective-scalac-args.log —— 实际传给 scalac 的选项行（-release 出现次数/取值/生效者）。
```
（复算命令：`Select-String -LiteralPath D:\Develop_code\GraduationProject\spark-jobs\pom.xml -Pattern 'final-positive-control|final-effective-scalac|\.verify'`）

**结论**：`m3-jdk8fix` 的 `.verify/` 是本项**唯一的独有内容**，且它是**已入库源码点名的证据路径**。仓内 `.verify/` 因 `.gitignore:61 .verify/` 被整体忽略，该文件在仓内根本不存在。因此：

- **`m3-jdk8fix` 不得直接删除**；必须先把它 `.verify/` 的证据归并到仓内可提交位置（见 §4-W2）。
- 同时这暴露了一个**既有缺陷**（见 §6-⑤）：已提交的 `pom.xml` 注释引用了**永远无法入库**的路径。

### 3.5 S1 / S2 敏感内容取证（只摘 1–3 行样本，全文见 `raw/sensitive-samples.txt`）

**转储结构统计**（修正后口径，此前用 `Select-String -SimpleMatch` 配 `'^CREATE TABLE'` 得到的全 0 是错的，因为 `-SimpleMatch` 会把 `^` 当字面量；已改用正则 `(?m)^CREATE TABLE` / `(?m)^INSERT INTO`）：

| 文件 | 大小 | `CREATE TABLE` | `INSERT INTO` | 值组 |
|---|---|---|---|---|
| `D:\p103-e3\analytics_meta_p103.sql` | 3,265,127 B | 22 | 22 | ~1689 |
| `D:\p103-e3\analytics_metric_p103.sql` | 34,088 B | 11 | 11 | ~232 |
| `D:\p105-e3\analytics_meta_p105.sql` | 3,265,948 B | 22 | 22 | ~1689 |
| `D:\p105-e3\analytics_metric_p105.sql` | 34,478 B | 11 | 11 | ~232 |

- 转储头：`-- MySQL dump 10.13  Distrib 8.0.41, for Win64 (x86_64)`、`-- Host: localhost  Database: <db>`。
- `p103` 的 `analytics_meta` 含表：`sys_user`、`user_session`、`ai_query_history`、`operation_audit_log`、`metric_value`、`source_registry` 等。
- `sys_user` 的 `INSERT` 含 **3 条 bcrypt 口令哈希**（`$2a$10$…`），对应用户 `admin` / `operator` / `analyst`。
- `p103-e3\base\BOOT-INF\classes\application.yml` 含明文默认口令 `meta_app_pw_2026`、`metric_pub_pw_2026`、`metric_read_pw_2026`（端口 8091，形如 `${PLATFORM_*_PASSWORD:...}`）。
  - **重要澄清（避免误判为新增泄漏）**：`meta_app_pw_2026` 在 tracked 文件中已有 **16** 处命中、`metric_pub_pw_2026` 有 **15** 处，包括 `analytics-server/platform-app/src/main/resources/application.yml` 的 `${PLATFORM_META_PASSWORD:meta_app_pw_2026}`。**故 p103 的副本不是新增泄漏，而是与已入库内容重复。** 但这与 V25-S03 R-3「默认口令已移除」的说法存在张力，值得另立通道复核（见 §6-⑤）。
- `p105-e3\e3-p105.ps1`（21,733 B）：实测含明文口令**字面量** `metric_pub_pw_2026`（`contains 'metric_pub_pw_2026' = True`），不含 `--password=`，不用 `MYSQL_PWD`。
- `p105-e3\dump-meta.err` / `dump-metric.err`（各 86 B）内容为：`mysqldump: [Warning] Using a password on the command line interface can be insecure.` ⇒ 证明当时 mysqldump 是通过命令行传口令的；**口令本身未落进 `.err`**。

**内部重复度（影响处置成本）**：`p103-e3` 内 18 个 `*.sql` 高度重复（6 个共享 `5728BA68AED0` / 3,262,447 B；5 个共享 `A7F16963AFA0` / 32,496 B）；`p105-e3` 的 `run/` 下多份 `e3-sample.jsonl` 亦重复。

### 3.6 S3 的独有内容与引用链

`raw/graduation-lane-backup-not-recoverable-from-git.txt`（53 项 / 1.84 MB）关键成分：

- `ct-batch\sha256-before.txt`（7 行 sha256 清单，实测内容为 `canonical-event.v1.schema.json.before` 等 7 个文件的哈希）
- `fix-round\step{1,2,3,4}-*.patch`（4 个补丁）、`fix-round\files\pre-round2-*` / `retired-*`、`fix-round\e2-run{3,4}.txt`
- `m1-9-fix-round-recovery-20260912\fix-round-recovery\delivered-fix.patch` 等 8 项
- `manifests\New-TrackedManifest.ps1` + 3 份 `tracked-manifest-20260912-*.txt`
- `p2-01\bin\*`、`p2-01\files-manifest.tsv`、`p2-01\live-jar\spark-jobs-0.1.0-SNAPSHOT.jar`、`p2-01\logs\*`（12 个）、`p2-01\retired-probes\*.scala`（3 个）

**引用链（tracked 文件中 14 处命中）**，最关键两条：

- `docs/acceptance/ct-batch-20260912/PLAN.md:112`：「契约文件写前各留离仓冷备份：`D:\Develop_code\graduation-lane-backup\ct-batch\*.before`（含 sha256 清单）。」
- `docs/acceptance/ct-batch-20260912/lane/PROGRESS.md:16`：「冷备份完成：`D:\Develop_code\graduation-lane-backup\ct-batch\*.before`（7 个文件）＋ `sha256-before.txt`；」

其余命中：`docs/acceptance/ct-integration-20260912/README.md:173`、`docs/acceptance/f37-worktree-deletion-20260912/README.md:30,45,83,113`、`docs/acceptance/m1-9-second-adapter-20260912/README.md:229`、`docs/acceptance/p2-01-ods-v2-20260912/IMPL-REPORT.md:347`。

⇒ **删除 `graduation-lane-backup` 会直接切断这些已入库验收文档所声明的证据链**，必须在归并后才能删。

### 3.7 S4 取证

```powershell
Get-ChildItem D:\Develop_code\.verify -Recurse -File -Force
# -> u1u3-cli-planappend.log  2778 B  2026-09-12 10:05:46
```
- sha256 = `301DE7D5A4EEB6DB983109C35BA35D3794D9B4A3DDFA8D2B8D24059610F2178D`
- 对象库命中：**0/1** ⇒ 独有内容。
- 内容为 Spring Boot 启动日志（PID 52580），指向 `D:\Develop_code\GraduationProject\synthetic-data-generator\target\synthetic-data-generator-0.1.0-SNAPSHOT.jar`，含 HikariPool 启动行。
- 与仓内同名文件**不同**：`docs/acceptance/m1-4-u1u3-20260912/raw/u1u3-cli-planappend-20260912-100543.log` = 3,349 B / sha256 `AB6DE699370795F64B414FB5BF3BF42BCA48DBE05866B260B24536B7E5CC106B`。
- 精确路径在 tracked 文件中 **0** 命中（`u1u3-cli-planappend` 的 3 处命中全部指向仓内副本）。

### 3.8 时间与规模取证（`raw/volume-stats.txt`）

| 项 | 目录创建时间 | 目录最后写 | 文件数 | MB | 最早文件 | 最新文件 |
|---|---|---|---|---|---|---|
| `_gp_headcheck` | 2026-09-14 13:57:36 | 13:57:37 | 2283 | 93.77 | 13:57:36 | **13:58:13** |
| `m3-jdk8fix` | 2026-09-12 15:59:27 | 15:59:34 | 1526 | 46.93 | 15:59:27 | 16:12:17 |
| `.f88-baseline-wt` | 2026-09-12 22:08:54 | 22:08:55 | 1783 | 55.68 | 22:08:54 | 23:02:53 |
| `p103-e3` | 2026-09-11 20:40:31 | 20:59:04 | 562 | 94.26 | 1980-02-01 | 20:59:04 |
| `p105-e3` | 2026-09-12 08:57:51 | 09:03:46 | 382 | 36.22 | 1980-02-01 | 09:03:46 |
| `graduation-lane-backup` | 2026-09-12 12:13:04 | 16:35:12 | 182 | 3.04 | 2026-09-10 15:20:51 | 16:35:12 |
| `D:\Develop_code\.verify` | 2026-09-12 10:05:43 | 10:05:43 | 1 | 0.0028 | 10:05:46 | 10:05:46 |

**`_gp_headcheck` 存在检出后写入**：`synthetic-data-generator/logs/generator.log`（13:58:13，14,591 B）与 `target/classes/...`（13:57:44–47）⇒ 该 worktree 在检出后**执行过一次 Maven 构建并运行过应用**。（该目录由 L5 于 2026-09-14 13:57:36 创建，晚于本裁决；相关过程问题见 §6。）

**仓体规模（用于估算归并增量）**：`.git` = **95.82 MB / 7330 文件**；工作树（不含 `.git`）= **1929.41 MB / 15990 文件**；主仓 tracked = **2070 文件 / 91.54 MB**。

---

## 4. 待用户逐项批准清单（**逐项报批，未批准一律不动**）

**说明**：本通道**不会**执行下列任何命令。请对每一行单独给出「批准 / 驳回 / 改口径」。命令中的路径均为 Windows 形式。

### 4.1 归并目标（先定口径）

| 归并目标 | 是否被 git 忽略 | 适用内容 | 依据 |
|---|---|---|---|
| `D:\Develop_code\GraduationProject\docs\backups\strays-20260914\<项>\` | **是**（`git -C … check-ignore -v docs/backups/probe.txt` → `.gitignore:53:backups/`，exit 0；对 `docs/backups/` 与深层路径同样忽略） | **不宜入库**的原样存档：正式库转储、含口令的 `application.yml`、构建产物 | 实测命令：`git -C D:\Develop_code\GraduationProject check-ignore -v docs/backups/probe.txt` |
| `D:\Develop_code\GraduationProject\docs\acceptance\<通道目录>\` | 否（会被提交） | **已入库文档点名引用的证据**（W2 的 `.verify/`、S3 的补丁与清单、S4 的日志） | 该目录下已有 **56** 个通道目录；`docs/backups` 目录本身**已存在** |

### 4.2 逐项批准表

| 批准项 | 处置 | 批准后我会执行的精确命令 | 预估增量 | 风险 |
|---|---|---|---|---|
| **W1** | 删除 | `git -C D:\Develop_code\GraduationProject worktree remove --force D:/Develop_code/_gp_headcheck`<br>然后：`Remove-Item -LiteralPath 'D:\Develop_code\_gp_headcheck' -Recurse -Force`<br>核验：`git -C D:\Develop_code\GraduationProject worktree list --porcelain`（应只剩 2 条） | 仓内 **0**（不动 `.git` 内容；仅回收 93.77 MB 磁盘） | 低 |
| **W2** | **先归并，不得删** | ① 归并证据：`New-Item -ItemType Directory -Force 'D:\Develop_code\GraduationProject\docs\acceptance\m3-jdk8fix-20260912\raw'`<br>`Copy-Item 'D:\Develop_code\GraduationProject-wt\m3-jdk8fix\.verify\*' 'D:\Develop_code\GraduationProject\docs\acceptance\m3-jdk8fix-20260912\raw\' -Recurse -Force`<br>② 一并归并 4 文件 diff：`git -C D:\Develop_code\GraduationProject-wt\m3-jdk8fix diff > 'D:\Develop_code\GraduationProject\docs\acceptance\m3-jdk8fix-20260912\raw\m3-jdk8fix-diff.patch'`<br>③ 归并**完成后另批**再删（本批只批① ②） | 提交约 **2.88 MB**（`.verify/` 12 文件） | **高** |
| **W3** | 删除 | `git -C D:\Develop_code\GraduationProject worktree remove --force D:/Develop/GraduationProject/.f88-baseline-wt`<br>父目录仅含此项，可一并回收：`Remove-Item -LiteralPath 'D:\Develop\GraduationProject' -Recurse -Force`（**仅在确认为空时**） | 仓内 **0**；回收 55.68 MB | 低 |
| **S1** | **两选项待裁决** | **选项 (a) 先归并再删**：<br>`New-Item -ItemType Directory -Force 'D:\Develop_code\GraduationProject\docs\backups\strays-20260914\p103-e3'`<br>`Copy-Item 'D:\p103-e3\*' 'D:\Develop_code\GraduationProject\docs\backups\strays-20260914\p103-e3\' -Recurse -Force`<br>（为该目录生成 sha256 清单后）`Remove-Item -LiteralPath 'D:\p103-e3' -Recurse -Force`<br>**选项 (b) 仅留指纹后删**：先在 README 固化「文件清单 + sha256 + 内容摘要」，再 `Remove-Item -LiteralPath 'D:\p103-e3' -Recurse -Force` | (a) 工作树 **+94.26 MB**、`.git` **+0**（被忽略）<br>(b) 工作树 **+0**、`.git` **+约 200 KB**（仅清单） | **高**（敏感） |
| **S2** | **两选项待裁决** | 同 S1，路径替换为 `D:\p105-e3` → `docs\backups\strays-20260914\p105-e3` | (a) 工作树 **+36.22 MB**、`.git` **+0**<br>(b) 工作树 **+0**、`.git` 约 +150 KB | **高**（敏感） |
| **S3** | **先归并，不得删** | ① `New-Item -ItemType Directory -Force 'D:\Develop_code\GraduationProject\docs\acceptance\graduation-lane-backup-20260912'`<br>`Copy-Item 'D:\Develop_code\graduation-lane-backup\*' 'D:\Develop_code\GraduationProject\docs\acceptance\graduation-lane-backup-20260912\' -Recurse -Force`<br>② 更新 `PLAN.md:112` / `lane/PROGRESS.md:16` 的路径指向（**属文档改动，需另批**）<br>③ 归并完成后另批再删 | 提交约 **3.04 MB** | 中 |
| **S4** | 归并或删除 | **归并**：`Copy-Item 'D:\Develop_code\.verify\u1u3-cli-planappend.log' 'D:\Develop_code\GraduationProject\docs\acceptance\m1-4-u1u3-20260912\raw\u1u3-cli-planappend-20260912-100546.outside.log' -Force`<br>**或直接删除**：`Remove-Item -LiteralPath 'D:\Develop_code\.verify' -Recurse -Force` | 归并提交 **2.7 KB**；删除 **0** | 低 |

### 4.3 请用户明确裁决的三件事

1. **S1/S2 选 (a) 还是 (b)？** (a) 保住可复算的原始转储但把 130.5 MB 搬进仓（被忽略，不入 `.git`）；(b) 仓内只留指纹，磁盘彻底回收但**原始真实库状态不可再复算**。
2. **W2 的 `.verify/` 归并到哪个通道目录**（`docs/acceptance/m3-jdk8fix-20260912/` 是否与既有命名一致）？
3. **S3 归并后是否同步修正 `PLAN.md:112`、`lane/PROGRESS.md:16` 的路径**（否则归并后引用链仍指向将被删除的离仓路径）？

---

## 5. 未取证项与原因（**明确列出，不掩盖**）

| # | 未取证项 | 原因 |
|---|---|---|
| 1 | **三项「有独有内容」的判定未做内容级人工判读**。W2 的 `.verify/` 12 文件、S3 的 53 文件、S4 的单文件，只做了「是否在对象库」的机械判定，**未逐个人工阅读以判断其证据价值**。 | 本通道职责是取证与清单，不是价值裁量；且部分文件（如 `p2-01/logs/*`）体量大、需领域判断。**归并前建议由对应通道判读。** |
| 2 | **`p105-e3\classes\` 302 文件的逐个人工核对未做**。 | 已做机械哈希比对（82/382 可从 git 恢复），未逐个判断语义。 |
| 3 | **全部离仓 `.jar` 的内部内容未展开比对**。只比对了整文件 sha256。 | 展开 271+ 条目的 Spring Boot loader 结构超出本次取证目的。 |
| 4 | **`p103-e3\base.zip`（271 条目）未展开核对**。 | 同上；仅记录其为 Spring Boot loader classes。 |
| 5 | **未验证「S1/S2 中的真实库数据是否与当前 3306 库状态一致」**。 | 需连接 3306 并查询，属**写/读库操作**，超出本通道只读取证边界（且用户明令禁止对 3306 做任何写入）。 |
| 6 | **未清点 3306 之外的其他可能离仓落点**（如 `D:\` 根下其他目录、用户目录、其他盘符）。 | 本次核查范围是用户已识别的 7 项 + `git worktree list` 注册项；**全盘扫描未做**，属独立任务。若需，应新开通道。 |
| 7 | **未核验 `.git\worktrees\` 下的管理目录是否有孤儿项**（存在管理目录但工作树已删，或反之）。 | `git worktree list` 显示注册项与磁盘实际一致（3 项），但**未做 `git worktree prune --dry-run` 级别的完整一致性核验**（该命令在冻结期内亦不宜执行）。 |
| 8 | **未取证 `graduation-lane-backup` 之外的其他历史「离仓冷备份」是否已消失**。 | 文档中提到的其他冷备份路径未在本轮清单内。 |
| 9 | **`reference-audit.txt`（688 行 / 437 KB）中的引用命中未逐条人工判读**。 | 只做了计数（191/105/143/135/14/3/61 等）与关键行抽取。 |
| 10 | **未测归并动作本身的可行性与耗时**（如 94 MB 复制的实际用时、磁盘余量）。 | 属执行阶段事项；本通道不执行。**执行前需先确认目标盘余量。** |

---

## 6. 核查中发现的无关既有缺陷（**非本次散落造成，供另立通道处理**）

| # | 缺陷 | 实测证据 | 影响 |
|---|---|---|---|
| ⑤-1 | **已提交源码引用了永远无法入库的证据路径**。`spark-jobs/pom.xml`（tracked）注释写「生效证据（worktree 内）：`.verify/final-positive-control.log`」，但 `.gitignore:61` 忽略 `.verify/`，且该文件在仓内 `.verify\` 中**不存在**（实测 absent），只存在于离仓 `m3-jdk8fix\.verify\`。 | `Get-Content .gitignore` → 第 61 行 `.verify/`；`Test-Path` 仓内路径 → absent；离仓 → FOUND 2653 B | 该「生效证据」在仓库内**不可复算、不可追溯**；是本次 W2 无法直接删的直接原因 |
| ⑤-2 | **默认口令在 tracked 文件中大量存在**。`meta_app_pw_2026` 16 处、`metric_pub_pw_2026` 15 处命中，含 `analytics-server/platform-app/src/main/resources/application.yml`。 | `git grep -n -I` 计数 | 与 V25-S03 R-3「默认口令已移除」的说法**存在张力**，需复核口径 |
| ⑤-3 | **对象库存在游离对象**：主仓 `git fsck --connectivity-only` 报 **395 dangling blob + 22 dangling tree**。 | `raw/` 中 fsck 记录 | 无害但说明历史上有未被引用的写入；W2 的 4 个修改文件正是靠游离 blob 才可恢复 |
| ⑤-4 | **索引与工作树存在 831 处体积不一致**，其中约 91% 可由 `core.autocrlf=true` 的 CRLF 膨胀解释，其余为其他通道的真实在途改动。 | `raw/repo-index-vs-worktree-size-mismatch.txt`（836 行） | 任何「体积对比」结论都必须注明该口径，否则会误判 |
| ⑤-5 | **16 个 tracked blob 合法为 0 字节**（`.log.err` / `.title.txt` 类证据文件，磁盘上亦为 0 字节）。 | `raw/repo-zero-byte-blobs.txt`（21 行） | 曾一度被误读为「文件内容丢失」，实为正常空证据文件 |
| ⑤-6 | **主仓工作树长期脏污**：395 条未提交条目。 | `raw/mainrepo-status-porcelain.txt`（395 行） | 使「工作树 vs 离仓副本」的直接比较不可靠，必须以 blob 为准 |
| ⑤-7 | **`_gp_headcheck` 在检出后被构建并运行过**（`target/classes` 13:57:44–47、`logs/generator.log` 13:58:13）。 | `raw/volume-stats.txt` + 文件时间戳 | 该目录由 L5 于 2026-09-14 13:57:36 创建，晚于本裁决；属**冻结期内新增散落**，与「冻结新增」的裁定冲突（看板 `V2.5:146` 已记录） |
| ⑤-8 | **本通道自身踩过的取证陷阱（记录以免他人重蹈）**：① `Select-String -SimpleMatch` 配 `'^CREATE TABLE'` 会匹配字面 `^`，产出**全 0** 的错误计数；② `[HashSet[string]]` 在 PowerShell 中无实例 `.ToArray()`，异常导致**全 0** 的错误冗余表；③ `git hash-object` 单口径（带/不带过滤器）会把**已入库文件误判为未入库**（详见 §0.1-1）。 | 见 §0.1、§3.5 | 本报告已全部改用修正口径；`raw/redundancy-bytes.txt` 与 `raw/redundancy-bytes-CORRECTED.txt` 保留为**错误中间结果对照** |

---

## 7. `raw\` 证据清单（47 文件 / 2.97 MB）

**基线**：`git-worktree-list-porcelain.txt`(16 行)、`git-worktree-list.txt`(4)、`snapshot-head.txt`(6)、`volume-stats.txt`(12)、`mainrepo-status-porcelain.txt`(395)

**冗余判定（三口径对照）**：`redundancy-FINAL-union.txt`(**最终口径**)、`redundancy-bytes.txt`(单口径·误)、`redundancy-bytes-CORRECTED.txt`(单口径·误)、`git-odb-redundancy.txt`、`already-in-repo-map.txt`(278)、`repo-index-vs-worktree-size-mismatch.txt`(836)、`repo-zero-byte-blobs.txt`(21)

**各项「不可恢复」清单**：`gp_headcheck-not-recoverable-from-git.txt`(211)、`m3-jdk8fix-not-recoverable-from-git.txt`(167)、`f88-baseline-wt-not-recoverable-from-git.txt`(92)、`p103-e3-not-recoverable-from-git.txt`(464)、`p105-e3-not-recoverable-from-git.txt`(300)、`graduation-lane-backup-not-recoverable-from-git.txt`(**53**)、`verify-not-recoverable-from-git.txt`(1)
（另有同族 `*-not-in-git-odb.txt` 为单口径中间结果，仅供对照）

**per-item sha256 清单**：`{gp_headcheck,m3-jdk8fix,f88-baseline-wt,p103-e3,p105-e3,graduation-lane-backup,verify}-sha256-{all,src}.txt`（all = 全量；src = 排除构建/缓存正则后的源码口径）

**状态快照**：`m3-jdk8fix-status-porcelain.txt`(4)、`m3-jdk8fix-status-ignored.txt`(7)、`f88-baseline-wt-status-ignored.txt`(2)、`mainrepo-status-porcelain.txt`(395)

**敏感与引用**：`sensitive-samples.txt`(221 行，仅 1–3 行样本/项)、`reference-audit.txt`(688 行)、`graduation-lane-backup-not-in-repo.txt`(94)

**补丁**：`m3-jdk8fix-diff.patch`(81 行)

---

## 8. 建议（含**最强烈反对**的选项）

1. **推荐路径（按此顺序，每步逐项报批）**：
   ① 先做**纯归并**、不做删除：W2 `.verify/` → `docs/acceptance/m3-jdk8fix-20260912/raw/`；S3 → `docs/acceptance/graduation-lane-backup-20260912/`；S4 → `docs/acceptance/m1-4-u1u3-20260912/raw/`。
   ② 归并提交后，独立通道复核引用链（`PLAN.md:112`、`lane/PROGRESS.md:16`、`pom.xml` 注释）是否已改指仓内。
   ③ 再对 W1、W3 执行删除（这两项已证明无独有内容、无敏感数据）。
   ④ 最后才裁决 S1/S2。
2. **S1/S2 的倾向**：倾向 **选项 (a)**（先归并到被忽略的 `docs/backups/strays-20260914/`，再删原件）。理由：这两个转储是**正式库真实状态**（含 PII、会话、审计日志与 bcrypt 口令哈希），是唯一可复算副本；而 `docs/backups/` 已被 `.gitignore:53` 忽略，归并不增加 `.git` 体积，成本只是 130.5 MB 磁盘。
3. **最强烈反对的选项（请勿采用）**：**不做事先归并，直接把 `D:\p103-e3` 与 `D:\p105-e3` 删除。**
   理由：① 二者含**正式库真实数据**（`user_session`、`ai_query_history`、`operation_audit_log`、`sys_user` 的 3 条 bcrypt 哈希），删除后**唯一副本即告灭失**；② 二者被 **143 处 / 135 处** tracked 文档引用（含 `p1-03-*` 的 `e3-acceptance.ps1` 与实例运行日志、`p1-05-*`、`p1-06-golden55-*`），直接删会**同时切断已入库验收证据链**；③ 在 `.git` 中**无法恢复**（464 / 300 文件不可从 git 恢复）。次劣选项是**在归并前删除 `graduation-lane-backup`**（会切断 `PLAN.md:112`、`lane/PROGRESS.md:16` 声明的「离仓冷备份」链），以及**删除 `m3-jdk8fix`**（会灭失被已入库 `pom.xml` 点名的 `.verify/final-positive-control.log` 等 11 个证据文件）。
4. **同时建议另开通道处理 §6 的 8 项既有缺陷**，特别是 ⑤-1（已提交注释引用不可入库路径）与 ⑤-2（默认口令遗留）——后者涉及安全口径，优先级高于本次归并。
5. **模式 A 的「冻结新增」需重申**：`_gp_headcheck` 是 2026-09-14 13:57:36 在裁决后新建并被构建运行的（§6 ⑤-7），说明冻结令尚未被所有通道遵守；建议在删除执行前先确认无新增散落。

---

## 9. 修订记录

| 日期 | 修订 |
|---|---|
| 2026-09-14 | 首版。快照 `fd3cee5b806b3570ce273e2346e1d14014e0dae2`（= 同刻 `origin/main`）。全部结论基于 `raw/` 内 47 个证据文件，可复算。**未执行任何删除、迁移或 git 写操作。** |
| 2026-09-14 | 收尾复核修订：① 记录核查期间 HEAD 继续前进至 `1cb318ba`，并在新 HEAD 下复验三个 worktree 的祖先关系与 7 项文件数（结论不变）；② 将 `spark-jobs/pom.xml` 的引用行号由「第 68 行附近」更正为精确的 **L68 / L70** 并附原文与复算命令。 |
