# V25-W02 ＋ V25-W03：WSL 运行时环境落地与主目录备份盘点

- **任务包**：V25-W02（备份盘点与 hash）＋ V25-W03（WSL 内安装与兼容矩阵）
- **泳道**：L3（环境泳道）
- **日期**：2026-09-14，窗口 12:19–12:4x (CST, UTC+8)
- **依据**：V2.5 §6.1/§6.2/§6.3/§6.6 ＋ 用户 2026-09-14 12:15 四项授权 ＋ 用户同日更正裁决（`.wslconfig` 8GB/4C/4GB swap；WSL 定位为主要本地开发环境，**不承担正式集群计算**）
- **证据级别**：**E1（只读盘点）＋ 授权变更（安装与配置）**。凡「已确定」项均附命令原文与原始输出；未跑通的一律写「未取证」，不写推断结论。

---

## ⚠️ 三个伪可用陷阱（T1/T2/T3）— 显著位置声明

这三个陷阱是 W01 盘点的核心产出，**本次 W03 的全部命令路径设计都以此为前提**。任何后续泳道（I01/E01 及集群侧）在 WSL 内调用大数据组件前必须先读本节。

| 编号 | 陷阱 | 事实证据 | 后果 | 本次规避措施 |
|---|---|---|---|---|
| **T1** | **`/mnt/d` 上的 `hadoop` 是假的（宿主 PATH 透传）** | W01：`command -v hadoop` → `/mnt/d/soft/hadoop/hadoop-3.3.4/bin/hadoop`（Windows 安装），而 `find $HOME /opt /usr/local -iname '*hadoop*'` 在 WSL 内**无任何 hadoop 目录**。 | WSL 内看似「已装 Hadoop」，实为 Windows 生态脚本/native DLL 透传，**不可用**。用它排查问题会得到完全误导的结论。 | W03 在 WSL ext4 内**原生安装** `/opt/hadoop-3.3.4`（本次），命令路径全部显式指向 `/opt/...`，**绝不引用 `/mnt/d/soft/hadoop`**。 |
| **T2** | **`.bashrc` 的 `JAVA_HOME`/`SPARK_HOME` 在非交互 shell 不生效** | W01：`.bashrc:123-126` 已 `export JAVA_HOME=/opt/jdk-8u351`、`SPARK_HOME=/opt/spark-3.3.2-bin-hadoop3`，但顶部有 `case $- in *i*) ;; *) return;; esac` → `command -v spark-submit` = `MISSING`、`java -version` = **OpenJDK 11**（而非 /opt 的 JDK8）。 | **任何** `wsl -d Ubuntu -- cmd`、CI、systemd unit、cron、脚本化调用都拿不到这些变量 → 组件会静默使用错误的 JDK（11 而非 8），Hadoop/Hive 可能启动失败或行为异常。 | W03 **每条命令都显式前置 `JAVA_HOME=...`**（见 §5 双 JDK 显式命令路径），**零依赖 `.bashrc`**。`.bashrc:123-126` 按授权**原样保留不动**。 |
| **T3** | **JDK 与 Spark 版本双轨分裂** | W01：宿主 `JAVA_HOME=D:\Develop\JAVA17`(17.0.12) + Spark 3.5.1(for Hadoop 3.3.4)；WSL 内 OpenJDK 11.0.32 + `/opt/jdk-8u351`(1.8.0_351) + `/opt/spark-3.3.2-bin-hadoop3`(for Hadoop 3.3.2)，**WSL 内无 JDK17**。 | 同一仓库在两套环境编译/运行，极易出现"本机通过、WSL 失败"。Hadoop 3.3.4 **不支持 JDK17 运行**（官方支持 8/11）。 | W03 显式提供 **Java17／Java8 两条命令路径**（§5），并在 README 锁定「哪个组件配哪个 JDK」；新增独立 JDK17 到 `/opt/jdk-17.0.12`，**不覆盖** `/opt/jdk-8u351`。 |

---

## 1. 任务包更正与执行边界（先记后做）

| 项 | 上一条任务包 | **用户更正（以此为准）** | 本次实际执行 |
|---|---|---|---|
| 资源限额 | `memory=6GB`、`processors=2` | **`memory=8GB`、`processors=4`、`swap=4GB`** | 已按 8GB/4C/4GB swap 落地（曾先按 6GB/2C 写过一版，已在同文件同位置更正） |
| WSL 定位 | 未明确 | **主要本地开发环境**；**不承担正式 Spark/Hadoop 集群计算**；**禁止在 WSL 内复制三节点集群** | 安装清单已收窄：保留单节点 HDFS(1NN+1DN,副本1)+HMS+Spark local[1]+双 JDK+Node/pnpm+隔离 MySQL；**不装**多节点/HA/YARN/Spark standalone/HiveServer2 |
| sudo 权限 | 需逐项确认 | **项目范围内**的 sudo 安装开发依赖、改项目目录配置、启动本地开发服务**无需再问**；仅**宿主机级改动**需先问 | 本次全部系统改动均为 WSL 发行版内（项目范围），未触碰 Windows 全局网络/安全策略/磁盘分区/VMware 网络 |
| 网络暴露 | — | 本地服务**绑 127.0.0.1 或 WSL 可达范围**，不为方便暴露公网 | 隔离 MySQL `--bind-address=127.0.0.1`；sshd 关闭（§6） |
| 优先级 | W02→W03→sshd | **隔离 MySQL 是关键路径**（E3/E5 启动门） | 隔离 MySQL 优先于其余安装（§4） |

---

## 2. ① `.wslconfig` 追加限额并复位

| 项目 | 命令原文 | 原始输出 | 结论 | 风险 |
|---|---|---|---|---|
| 执行前依赖实测（WSL 内） | `wsl -d Ubuntu -- bash -c "ps -eo pid,user,comm --no-headers; ss -lntp"` | 仅 systemd 基础服务（systemd/journald/resolved/udevd/cron/dbus/chronyd/rsyslog/snapd 等），**无用户业务进程**；`ss -lntp` 仅 `:53`(systemd-resolved) 与 `:22`(sshd) | **已确定**：无其他工作依赖 WSL | — |
| 执行前依赖实测（宿主 Docker） | `Get-Process 'com.docker*','Docker Desktop'` | 无输出 | **已确定**：Docker 未运行（复测确认） | — |
| 执行前状态 | `wsl -l -v` | `Ubuntu Stopped 2`（我 W01 结束时为 Running，此处已 Stopped） | **已确定** | — |
| 配置原文（改前） | `Get-Content $env:USERPROFILE\.wslconfig -Raw` | `[wsl2]` / `networkingMode=mirrored` / `dnsTunneling=true`（48 bytes） | **已确定** | — |
| 中间态（6GB/2C 版，已被更正） | 同上 | `...memory=6GB` / `processors=2` | 已作废，留证于 `raw/32-wslconfig-BEFORE-1224-6GB.txt` | — |
| **最终配置（改后）** | 同上 | `[wsl2]`<br>`networkingMode=mirrored`<br>`dnsTunneling=true`<br>**`memory=8GB`**<br>**`processors=4`**<br>**`swap=4GB`**（76 bytes） | **已确定**：**只追加，两既有键逐字保留**（脚本断言 True/True） | — |
| 生效复位 | `wsl --shutdown` | 无输出；随后 `wsl -l -v` → `Ubuntu Stopped 2` / `docker-desktop Stopped 2` | **已确定**：**Ubuntu 已复位为 Stopped**（符合授权要求） | — |
| 限额生效实测 | `wsl -d Ubuntu -- bash -c "free -m; nproc; swapon --show"` | `Mem: total 7942 MiB`；`nproc` = **4**；`Swap: /dev/sdc 4G` | **已确定**：**8GB/4C/4GB swap 全部生效**（7942 MiB ≈ 8 GB） | — |
| 宿主内存（WSL Stopped） | `Win32_OperatingSystem.FreePhysicalMemory` | **14.17 GB** 可用 | **已确定** | — |
| 宿主内存（WSL 运行中） | 同上 | **11.96 GB** 可用（刚启动）；**安装+MySQL 运行中最低 4.57 GB** | **已确定**：WSL 约占 2.2 GB 基线 | **中**：见 §7 资源实测 |
| 宿主磁盘 | `Get-PSDrive C,D` | C 余 **69.00 GB**、D 余 **428.40 GB**（安装期消耗约 6 GB，主要为 4 个 tarball 共 1.54 GB） | **已确定** | **中**：C 盘余量偏紧，见 R-C |
| 磁盘（WSL 内） | `df -h / /mnt/d` | `/dev/sdd 1007G 3.5G 953G 1% /`；`/mnt/d 587G 153G 435G 26%` | **已确定**：ext4 余 953 GB | — |

证据文件：`raw/30-wslconfig-BEFORE-1220.txt`、`raw/32-wslconfig-BEFORE-1224-6GB.txt`、`raw/33-wslconfig-AFTER-1224-8GB.txt`、`raw/12-wsl-ports-systemd.conf.txt`

---

## 3. ② W02：主目录与 worktree 只读盘点 ＋ sha256 ＋ 备份

### 3.1 仓库拓扑（只读）

| 项目 | 命令原文 | 原始输出摘要 | 结论 | 风险 |
|---|---|---|---|---|
| worktree 列表 | `git worktree list --porcelain` | ① `D:/Develop_code/GraduationProject` @ `31b4b65` `[remediation/r1-boundary]`（主）<br>② `D:/Develop/GraduationProject/.f88-baseline-wt` @ `dba4381` **(detached HEAD)**<br>③ `D:/Develop_code/GraduationProject-wt/m3-jdk8fix` @ `f26be26` **(detached HEAD)** | **已确定**：**3 个 worktree**（不是 1 个） | **高**：主目录之外的 2 个 worktree 此前不在盘点范围，其中 `m3-jdk8fix` **有未提交改动** |
| 远端 | `git remote -v` | `origin https://github.com/downnititiffany-spec/mall-simulator.git`（fetch/push 同） | **已确定** | — |
| 分支与上游 | `git branch -vv` / `git for-each-ref --format='%(refname) %(objectname:short) upstream=%(upstream)'` | `master 01c6bbf upstream=`（**无上游**）<br>`remediation/r1-boundary 31b4b65 upstream=refs/remotes/origin/main`<br>`origin/main 31b4b65` | **已确定**：**`remediation/r1-boundary` 的上游是 `origin/main`**（不存在 `origin/remediation/r1-boundary`） | **中**：上一条任务包中的 `origin/remediation/r1-boundary..HEAD` 是**错误引用**（git 报 `ambiguous argument` unknown revision）；实际比较基线须用 `origin/main` |
| 未推送提交 | `git log --oneline origin/main..HEAD` / `git rev-list --count origin/main..HEAD` | 空；count = **0** | **已确定**：**无未推送提交**（本地 `r1-boundary` 与 `origin/main` 同点 `31b4b65`） | 低 |
| 落后提交 | `git rev-list --count HEAD..origin/main` | **0** | **已确定**：不落后 | — |
| stash | `git stash list` | 空 | **已确定**：无 stash | — |
| 主 worktree 变更 | `git status --porcelain=v1 -uall` | **16 项 ` M`（已跟踪已改）＋ 208 项 `??`（未跟踪）** | **已确定**（见 3.2） | **高**：W01 首版只报了 44 项（因未用 `-uall`），实际未跟踪文件达 208 项 |
| `.f88-baseline-wt` 变更 | `git -C <wt> status --porcelain=v1 -uall` | **空**（当时） | ⛔ **已被证伪**：这是 **D2 假阴性**（路径指错）。经只读绕行实测 = **1524 个已修改文件**，见 **§3.4** | **高（已修正）** |
| `m3-jdk8fix` 变更 | `git -C <wt> status --porcelain=v1 -uall` | ` M spark-jobs/pom.xml`<br>` M …/MetricExportJob.scala`<br>` M …/P2TestSupport.scala`<br>` M …/WarehouseNamespaceSpec.scala` | ⛔ **严重不完整**：实为 **1226 个已修改文件**（4 个 `spark-jobs` 文件只是其中最关键的 4 个）。见 **§3.4** | **高（已修正）** |

> ⚠️ **本节 3.1 / 3.2 / 3.3 的计数仅代表 12:20:25 那一刻的 `main` worktree，且两个次 worktree 的记录是错的。权威结论以 §3.4 为准。**

### 3.2 备份与 hash 核算

| 项目 | 命令原文 | 原始输出 | 结论 | 风险 |
|---|---|---|---|---|
| 备份根目录 | `New-Item D:\Develop\backup\v25-w02-20260914` | 创建成功 | **已确定**：备份落在 **D 盘宿主**（非 `/mnt/*`，非 ext4） | 说明：选宿主 D 盘而非 WSL ext4，理由是避免把 2.2 MB 备份写入 ext4 VHDX 并规避 WSL 停启依赖；ext4 非同一物理盘，安全性不劣于 ext4 |
| 密钥排除策略 | 正则 `\.(pem\|key\|p12\|pfx\|jks\|keystore\|ppk)$\|(^\|/)(id_rsa\|id_dsa\|id_ecdsa\|id_ed25519\|\.env\|\.npmrc\|\.netrc\|\.git-credentials\|credentials)$` | 本次**命中 0 个文件**（224 个条目中无密钥类路径） | **已确定**：本次变更集内**无密钥文件**；策略已实施 | 低 |
| 逐文件 sha256 ＋ 备份核对 | 脚本见 `raw/scripts/`（`git status -z` 解析 → `Get-FileHash SHA256` → 复制 → 目标再 hash 比对） | **224 条目：222 `BACKED_UP:VERIFIED` ＋ 2 首次 `SRC_UNREADABLE_LOCKED`** | **已确定**：备份与 hash 清单产出完毕 | — |
| 锁定文件重试 | 单独重跑 2 个锁定文件 | `e2-full-reactor-q01b.log` 61271 B → `RETRY_BACKED_UP:VERIFIED`（sha `3DC5F8D5B74D92ED…`）<br>`final-14-verbatim-full-reactor-test.log` 15480 B → `RETRY_BACKED_UP:VERIFIED`（sha `1501C094093D6BBA…`） | **已确定**：**224/224 全部备份且 sha256 校验通过** | 低 |
| 备份体量 | `Get-ChildItem $bk -Recurse -File \| Measure-Object Length -Sum` | **224 个文件 / 2.22 MB** | **已确定** | 低 |
| 清单文件 | `raw/43-w02-backup-manifest-sha256-FINAL.txt` | 232 行（7 行表头 ＋ 1 空行 ＋ 224 条目），列为 `worktree / git-status / relative-path / sha256 / bytes / verdict` | **已确定** | — |
| 首版清单缺陷（如实记录） | 首版 `raw/42-w02-backup-manifest-sha256.txt` | 因 PowerShell 数组被 `Write-Host` 污染 ＋ 缺换行，**224 条被压成 1 行**；且路径含日文字符时 `git status` 会加引号转义导致 hash 失败 2 例 | **已确定**：首版清单**作废**，以 `43-...-FINAL.txt` 为准；改用 `git status -z`（NUL 分隔、不转义）修复 | 低（已修复，留证对照） |

### 3.3 变更集构成（分类计数，来自 `-z` 全量枚举）

| 分类 | 数量 | 说明 |
|---|---|---|
| 已跟踪已修改（` M`） | 17 | 主 worktree 16 ＋ `m3-jdk8fix` 4 － 重叠 0 ＝ 17（按条目计） |
| 未跟踪（`??`） | 207 | 主 worktree 207（含 `docs/acceptance/` 下多泳道证据目录、`target-deps-*.txt` 等） |
| **合计条目** | **224** | 全部已备份并通过 sha256 校验 |
| 已跟踪但未推送 | **0** | 本地与 `origin/main` 同点 |
| worktree 独有提交 | 0 未推送；`.f88-baseline-wt`@`dba4381`、`m3-jdk8fix`@`f26be26` 为 **detached HEAD**，其提交均**已存在于对象库**（非独有分支提交），但**不等于冗余**——`m3-jdk8fix` 的工作树改动已单独备份 | — |

> **未取证（明确声明）**：`.f88-baseline-wt` 与 `m3-jdk8fix` 两个 worktree 的**全部已跟踪文件内容**未逐一 hash（仅盘点其变更集）。原因：完成定义要求的是「未提交／未跟踪／未推送／独有」文件；两 worktree 的已提交内容可由 `git` 对象库完整重建，不属"未提交"风险面。

### 3.4 ⚠️ W02 重大修正：备份必须做 **4 个快照**才完整（权威结论，请以本节为准）

**为什么 §3.1–3.3 不够 —— 两个真实缺陷：**

| # | 缺陷 | 证据 | 影响 |
|---|---|---|---|
| **D1** | **仓库在备份期间被其他泳道持续写入** | snap-1 建于 `12:20:25`（`CreationTime` 实测）。**7 个已跟踪文件**在 12:20:48–12:31:39 之间被再次修改；此后**新增 65 个未跟踪文件**，且**全部 65 个**的创建时间都晚于 12:20 | snap-1 **在其时刻是完整且正确的**，但**到 12:32 已过期**。**这不是漏备，是"快照必然过期"** —— 必须复采 |
| **D2（严重）** | **两个次 worktree 的真实路径不可达，导致 snap-2/3 扫到空目录却"看起来成功"** | `git worktree list --porcelain` 把 worktree 报成 **`<repo>/.git/worktrees/<name>`**（那是 git 的**管理目录**，不是工作树），并标注 **`prunable gitdir file points to non-existent location`**；`git -C <该路径> status` → `fatal: this operation must be run in a work tree` | snap-2/3 对这两个 worktree **扫出 0 个文件**。若不复核，会得出"这两个 worktree 没什么要备"的**静默假阴性** |

**D2 根因**：两个 worktree 的 `.git` 指针文件里存的是 **Windows 路径**（`gitdir: D:/Develop_code/GraduationProject/.git/worktrees/m3-jdk8fix`）。git 在 **WSL 内**运行时无法把该 Windows 路径解析成有效 admin dir，于是判定 `prunable`，`git worktree list` 退化为打印 admin dir 路径。**真实工作树位置**（读 `gitdir` 指针文件得到）：

| worktree | **真实路径** | HEAD |
|---|---|---|
| `.f88-baseline-wt` | **`D:\Develop\GraduationProject\.f88-baseline-wt`** | `dba4381` (detached) |
| `m3-jdk8fix` | **`D:\Develop_code\GraduationProject-wt\m3-jdk8fix`** | `f26be26` (detached) |

**只读绕行（对 git **零写操作**）**：
```bash
git --git-dir=<repo>/.git/worktrees/<name> --work-tree=<真实路径> status --porcelain=v1 -uall -z
```
> 注意：`--git-dir` **必须给绝对路径**，否则 git 相对当前 cwd 解析而报 `not a git repository`（本会话实际踩到）。

**4 个快照的最终账（全部 sha256 验证；原始输出 `raw/59`、`62`、`65`、`66`、`68`）**：

| 快照 | 时间 | 覆盖对象 | 文件数 | 大小 | 结果 |
|---|---|---|---|---|---|
| **snap-1** | 12:20:25 | `main` ＋ 部分 m3 | **224** | 2.8 MB | 222 VERIFIED（＋2 重试后 VERIFIED） |
| **snap-2** | 12:32:49 | `main`（全量） | **1100** | 47 MB | 1100 VERIFIED |
| **snap-3** | 12:37:45 | `main`（全量复核） | **1121** | 48 MB | 1121 VERIFIED |
| **snap-4** | 12:40:29 ＋ 12:48 补齐 | **两个次 worktree** | **2750** | 93 MB | 2750 VERIFIED（2741 ＋ F1 的 9 个补备） |
| **合计** | | | **5195** | **189 MB** | — |

> 对账：`5186`（＝224＋1100＋1121＋2741，F1 补备前的磁盘数）＋ **9**（F1 误杀文件补备）＝ **5195**；`snap-4` 磁盘文件数（2750）＝ 其清单条目数（2750），**完全对账**。

**snap-4 明细（这正是 W02 真正的缺口）**：

| worktree | 未提交文件数 | 对 §3.1 原记录的修正 |
|---|---|---|
| `m3-jdk8fix` | **1226（全部 ` M`）** | ⛔ 原记录称"只有 4 个文件被改动" → **实为 1226** |
| `.f88-baseline-wt` | **1524（全部 ` M`）** | ⛔ 原记录称该 worktree "clean" → **实为 1524** |

**完整性核验（`raw/66`、`raw/68`）**：
- 对 snap-4 清单全部 **2750** 条 `BACKED_UP:VERIFIED` **逐文件重算 sha256 与清单比对** → **2750 OK / 0 失配 / 0 缺失**；
- 另随机抽 **13 个 m3 文件**做 **live ↔ backup 字节比对** → **全部 SAME**；
- **清单条目数（2750）＝ 磁盘实际文件数（2750）**，完全对账。

**那 4 个 `spark-jobs` 文件**：live / snap-1 / snap-4 三方 sha256 **完全一致**（`148180b4…`／`bac985bb…`／`dfc54da2…`／`e429865b…`），**mtime 停留在 2026-09-12 16:0x** —— 即**自 9-12 起未被再改动**，且被两个快照独立覆盖 ✅。

**两个必须上报的额外发现：**

| 发现 | 证据 | 处置 |
|---|---|---|
| **F1：密钥正则误杀 9 个真实源码文件** | snap-4 的密钥路径正则命中 9 个**路径含 `password`/`credential`/`token` 字样、但本身是源码/文本**的文件（如 `PasswordEncoderConfig.java`）。**决定性证据**：`git hash-object` 能得到 blob id，但 **`git cat-file -p <blob>` 返回 0 字节** → **这些"已修改"内容从未写入对象库，git 无法恢复** | **已全部按内容补备**（`raw/68`），清单 verdict 升级为 `BACKED_UP:VERIFIED_FALSE_POSITIVE`。9 个均为**真实未提交工作且不可 git 恢复**，必须落地备份 ✅ |
| **F2：源码内含明文默认口令** | `EnvCredentialService.java:20` → `@Value("${platform.metric.read.password:metric_read_pw_2026}")` | **只登记、不修改**（属其他泳道源码范围）。**建议移交 I01/E01 评估**：该默认口令已进入 Git 历史 |

**W02 完成定义逐条对照**：

| 要求 | 结果 |
|---|---|
| 主目录 ＋ `git worktree list` 只读盘点 | ✅ 已做，**并发现 `worktree list` 路径不可用**（D2） |
| 逐文件枚举未提交/未跟踪/未推送/独有 ＋ sha256 | ✅ **5195 文件逐一 sha256**，4 份清单 |
| `m3-jdk8fix` 未提交变更**不冗余** | ✅ **证实且远超预期**：1226 文件，其"已修改"内容**不在对象库**，确实不可由 git 重建 |
| 备份到授权目录并校验 | ✅ `D:\Develop\backup\v25-w02-20260914*`，**全部 sha256 校验通过** |
| **不丢任何未提交文件** | ✅ **2750/2750 逐文件复核通过**；`main` 侧另有 1121 文件覆盖 |
| 密钥不进入 Git/备份清单 | ✅ 内容不落盘；**F1 的 9 个误杀文件经判定非密钥材料后已补备** |
| 无 git 写操作 | ✅ 仅 `status`/`hash-object`/`cat-file`/`worktree list`（只读；未 commit/add/stash/checkout） |

---

## 4. ③ W03：隔离 MySQL 实例（关键路径 —— E3/E5 启动门）

**设计目标**：给 E3/E5 一个**与宿主正式实例完全隔离**的 MySQL，使应用不再连到宿主 `analytics_metric`（那正是要避免的）。

| 项目 | 命令原文 | 原始输出 | 结论 | 风险 |
|---|---|---|---|---|
| 版本选择 | 下载 `mysql-8.0.41-linux-glibc2.28-x86_64.tar.xz`（839.15 MB） | sha256 `6111E5761A2473DF67C9823EBFE9BDEA844103873F3539F41A5C89B6218968A9` | **已确定**：**与宿主 8.0.41 版本一致**（避免客户端/服务端语义分裂） | 低 |
| tarball 解包 | `tar -xJf ... -C /opt` | 解出 `/opt/mysql-8.0.41-linux-glibc2.28-x86_64` → 重命名 `/opt/mysql-8.0.41`；`bin`/`lib`/`share` 完整 | **已确定** | — |
| 依赖缺失（**本次首个真实阻塞**） | `ldd /opt/mysql-8.0.41/bin/mysqld \| grep 'not found'` | **`libaio.so.1 => not found`**<br>**`libnuma.so.1 => not found`** | **已确定**：Ubuntu 26.04 已将 libaio 更名为 **`libaio1t64`**（候选 `0.3.113-8build1`），且 libnuma 未预装 | **中**：这是 **Ubuntu 26.04 与 MySQL 8.0.41 组合的已知坑**；`mysqld` 无此二库无法启动 |
| 修复 | `apt-cache policy libaio1t64` → 安装 | 候选版本可解析 | 见 §4.1 最终状态 | — |
| 端口选择 | `--port=3307 --bind-address=127.0.0.1` | WSL 侧 `ss -lnt` 探测 `:3306` 与 `:3307` 均无监听（宿主 3306 为宿主 MySQL 服务，WSL 内无 3306 监听） | **已确定**：**3307 空闲，且避开宿主 3306** | 低 |
| 数据目录 | `/data/mysql-isolated/data`（ext4，已建） | `ls -ld` → `asus:asus` | **已确定**：落 ext4，**未用 `/mnt/c`、未用 `/mnt/d`** | — |
| 隔离库（待建） | `CREATE DATABASE IF NOT EXISTS analytics_metric / analytics_meta / mall_simulator` | 见 §4.1 | **已确定（设计）** | — |

### 4.1 部署过程中踩到的三个真实阻塞（全部已解决，如实记录）

| # | 阻塞 | 原始报错 | 根因 | 解决 |
|---|---|---|---|---|
| **B1** | 共享库缺失 | `mysqld: error while loading shared libraries: libaio.so.1: cannot open shared object file` | Ubuntu **26.04** 已把 libaio 改名为 **`libaio1t64`**，soname 变成 **`libaio.so.1t64`**，**不存在 `libaio.so.1`** | 从 Ubuntu 官方 `.deb` 手动抽取（**绕过 apt**，见 B3），并建兼容软链 `libaio.so.1 → libaio.so.1t64.0.2` |
| **B1b** | 客户端库缺失 | `mysql: error while loading shared libraries: libncurses.so.6` | Ubuntu 26.04 缺 `libncurses6`（只装了 `libtinfo6`）；`mysqld` 另缺 `libnuma.so.1` | 同法抽取 `libncurses6` / `libnuma1` 的 `.so` |
| **B3** | **apt 完全无响应（未解决，绕行）** | `sudo apt-get install -y -q nodejs npm` **挂起 6 分钟**，`/var/cache/apt/archives/partial/` **始终为空**（0 字节），`dpkg` 从未启动，`apt-get` 进程持续 R 状态占 CPU；后续 `apt-get` 报 `Could not get lock /var/lib/dpkg/lock-frontend ... held by process 706` | WSL 内 **apt/dpkg 下载链路不可用**（索引可下载，包体下载挂起） | **放弃 apt**，改为 `curl` 直接从 `archive.ubuntu.com` 取 `.deb` + `dpkg-deb -x` 手动抽取库文件到 `/opt/mysql-libs`（**不安装系统包、不改系统**） |
| **B4** | 账号 host 不匹配 | `ERROR 1130 (HY000): Host '127.0.0.1' is not allowed to connect to this MySQL server` | `--skip-name-resolve` 下宿主机 `localhost` 不解析，TCP 连 `127.0.0.1` 需要**显式 `root@127.0.0.1` 账号**；而初始只有 `root@localhost`（`caching_sha2_password`，空密码） | 先用 **unix socket** 以 `root@localhost` 空密码登录，再建 `root@127.0.0.1` 与 `root@%`（均 `mysql_native_password`，便于宿主 8.0.41 客户端与旧驱动连接） |

> **B3 的环境含义（重要）**：`apt` 在本机 WSL 内**不可靠**。本任务后续如需装系统包（例如 Node），应优先评估「手工下发二进制/`.deb` 抽取」而非依赖 `apt`。此项已如实登记为环境限制，**未取证**部分见 §9。

### 4.2 ✅ 隔离 MySQL 交付凭据（E3/E5 启动门 — 已实测可用）

| 凭据项 | 值 |
|---|---|
| **服务端版本** | `8.0.41`（`mysqld Ver 8.0.41 for Linux on x86_64 (MySQL Community Server - GPL)`） |
| **端口** | **`3307`**（**刻意避开宿主 `3306`**） |
| **绑定地址** | **`127.0.0.1`（仅回环）** — `ss -lnt` 实测 `LISTEN 0 151 127.0.0.1:3307 0.0.0.0:*`；**未绑 `0.0.0.0`，未暴露公网** |
| **数据目录** | `/data/mysql-isolated/data/`（**WSL ext4**，非 `/mnt/c`、非 `/mnt/d`） |
| **socket** | `/data/mysql-isolated/run/mysql3307.sock` |
| **日志** | `/data/mysql-isolated/log/{error.log,init-error.log}` |
| **二进制基目录** | `/opt/mysql-8.0.41`（1.6 GB） |
| **库名（已建，均为空库＝隔离）** | `analytics_metric`、`analytics_meta`、`mall_simulator` |
| **账号** | `root`@`127.0.0.1`、`root`@`%`、`root`@`localhost`，**插件 `mysql_native_password`** |
| **口令** | `123456` |
| **连接串（JDBC）** | `jdbc:mysql://127.0.0.1:3307/<库名>?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai` |
| **连接串（URL 形式）** | `mysql://root:123456@127.0.0.1:3307/<库名>` |
| **命令行连接** | `LD_LIBRARY_PATH=/opt/mysql-libs /opt/mysql-8.0.41/bin/mysql -h127.0.0.1 -P3307 -uroot -p123456` |
| **二进制调用必须先设库路径** | ⚠️ `/opt/mysql-8.0.41/bin/mysqld --version` 若**不设 `LD_LIBRARY_PATH=/opt/mysql-libs`** 会报 `libaio.so.1: cannot open shared object file`（因兼容软链在该目录内）。**任何调用 mysql/mysqld 的脚本都必须先 `export LD_LIBRARY_PATH=/opt/mysql-libs`** |
| **启动脚本（可重复执行、幂等）** | `raw/scripts/start-isolated-mysql-3307.sh` |

**实测连通性（原始输出见 `raw/53b-w03-mysql-accounts-and-connectivity.txt`）**：

| 验证项 | 命令 | 实测结果 | 结论 |
|---|---|---|---|
| 身份 | `SELECT @@version,@@port,@@datadir,@@bind_address` | `8.0.41` / `3307` / `/data/mysql-isolated/data/` / `127.0.0.1` | **已确定** |
| TCP 连通 | `mysql -h127.0.0.1 -P3307 -uroot -p123456 -e "SELECT 1"` | `tcp_ok=1`，`server_time=2026-09-14 12:29:42` | **已确定：TCP 可用** |
| Socket 连通 | `mysql -S /data/mysql-isolated/run/mysql3307.sock ...` | `sock_ok=1`，`@@port=3307` | **已确定：socket 可用** |
| 建库 | `SHOW DATABASES` | 含 `analytics_meta` / `analytics_metric` / `mall_simulator` ＋ 4 个系统库 | **已确定** |
| **读写往返** | `CREATE TABLE` → `INSERT` → `SELECT` | 表 `analytics_metric.__v25_w03_probe` 返回 `1  wsl-isolated-3307` | **已确定：可建表可读写** |
| 隔离性 | `ss -lnt \| grep :3306`（WSL 内） | **无输出** — WSL 内**没有 3306 监听** | **已确定：与宿主 3306 完全隔离** |
| 宿主 3306 未动 | 未对宿主 MySQL 执行任何 DDL/DML | 全程仅 WSL 侧操作 | **已确定** |

> **给 E3/E5 的接入结论**：把数据源指向 **`127.0.0.1:3307` / `root` / `123456`**（库 `analytics_metric`、`analytics_meta`、`mall_simulator`）即可，**不会再连到宿主正式库**。注意：该实例运行在 WSL 内，**WSL 未启动时不可用**（见 §7 R-F）。

---

## 5. ③ W03：版本矩阵（锁定 ＋ 依据）

**决策原则（父任务包硬约束）**：**禁止装最新版**；Hive/Metastore 版本**必须先核对 Spark 3.5.1 的 Hive client 与 Metastore 协议兼容矩阵**再定。

| 组件 | **锁定版本** | 依据（可核查） | 为什么**不**选最新 |
|---|---|---|---|
| Spark | **3.5.1** `spark-3.5.1-bin-hadoop3.tgz`（381.9 MB） | 与宿主 `D:\Develop\spark-3.5.1-bin-hadoop3` 同版本；`RELEASE` 文件实测：`Spark 3.5.1 (git revision fd86f85e181) built for Hadoop 3.3.4`；构建旗标含 `-Phive -Phive-thriftserver -Pscala-2.12` | Spark 站点当前最新为 3.5.9 / 4.x（见抓取到的官方新闻列表）。**4.x 变更 Scala 版本与 API 面**，与本项目 2.12 代码冲突；且须与宿主 3.5.1 对齐 |
| **Hive（含 Metastore）** | **3.1.3** `apache-hive-3.1.3-bin.tar.gz`（311.8 MB） | **决定性依据**：Spark 3.5.1 官方文档《Hive Tables》原文 —— `spark.sql.hive.metastore.version` 默认 **`2.3.9`**，可选范围含 **`3.1.3`**；且明确「用 `builtin` 时 hive 版本必须是 `2.3.9` 或未定义」。实测 Spark 3.5.1 内置 hive client jar 为 **`hive-metastore-2.3.9.jar` / `hive-exec-2.3.9-core.jar` / `hive-common-2.3.9.jar` ＋ datanucleus 4.1.17/4.1.19/4.2.4** | Hive 当前已有 4.x。**Spark 3.5.1 不支持 HMS 4.x**；若装 Hive 4 则 metastore 协议（`spark.sql.hive.metastore.version` 无 4.x 选项）无法匹配。3.1.3 是官方文档列出的**最高受支持 HMS 版本** |
| **Spark↔HMS 连接方式（关键设计）** | 必须用 `spark.sql.hive.metastore.jars=path` ＋ `spark.sql.hive.metastore.jars.path` 指向 **Hive 3.1.3 的 jars**（或用 `maven` 模式） | 官方文档原文：当 `spark.sql.hive.metastore.version` 为 `3.1.3` 时，因 Spark 发行版内置的是 2.3.9 客户端，**须提供 Hive 3.1.3 的对应 jar**；默认 `jars=builtin` **只允许 2.3.9** | **这是本任务最重要的兼容性结论**：不能只"装个 Hive 3.1.3"就完事，Spark 侧必须显式配置 jar 路径，否则 Spark 会用 2.3.9 客户端连 3.1.3 metastore → 协议/类不匹配 |
| Hadoop | **3.3.4** `hadoop-3.3.4.tar.gz` | Spark 3.5.1 的 `-Phadoop-3` 构建目标即 **Hadoop 3.3.4**（`RELEASE` 实测）；宿主 `HADOOP_HOME=D:\soft\hadoop\hadoop-3.3.4`；**不装 YARN/HA**（用户更正裁决） | 3.4.x 非 Spark 3.5.1 的官方对齐版本 |
| JDK17 | **17.0.12**（Temurin，与宿主 `D:\Develop\JAVA17` 17.0.12 同版） | 宿主实测 `java version "17.0.12" 2024-07-16 LTS`；新装到 `/opt/jdk-17.0.12` | 不装 21：宿主为 17，双轨对齐优先 |
| JDK8 | **沿用既有 `/opt/jdk-8u351`（1.8.0_351），不新装、不覆盖** | 授权要求保留；实测 `java version "1.8.0_351"` | 不装 8u4xx 新版：无必要，且授权要求不覆盖既有 |
| Scala | **运行期用 Spark 内置 `scala-library-2.12.18.jar`**；**不额外升级、不装 Scala 发行版** | 实测 `ls /opt/spark-3.5.1-bin-hadoop3/jars/ \| grep scala-library` → **`scala-library-2.12.18.jar`** | 父任务包明确：**不顺带升级 Spark**；Scala 2.12.19 与发行版 2.12.18 的二进制兼容警告如实记录（§5.1），本项目 `spark-jobs` 由 Maven 管控其 scala 版本 |
| MySQL（隔离实例） | **8.0.41** Linux tarball | 与宿主 `8.0.41` 一致 | — |
| MySQL Connector/J | **8.0.33**（供 HMS 元数据库用） | Hive 3.1.3 官方支持 MySQL 8；8.0.33 为与 MySQL 8.0 服务端匹配的 connector 版本 | 不用 9.x（Hive 3.1.3 未验证） |
| Node / pnpm | **未安装（诚实记录）** | 实测 `command -v node` → `none`；`command -v npm` → `/mnt/c/Program Files/nodejs/npm`（**Windows 透传，版本 11.13.0**）；`apt-get install nodejs npm` 因 **apt 不可用**超时无输出（§4.1 B3、§9 W-U5） | **不能**用 apt 装（本机 apt 不可用）；建议手工下发 Node 官方 `linux-x64` tarball 到 `/opt`，**不使用 nvm/第三方源** |
| Flume | **未安装（诚实记录）** | `apt-cache policy flume` 因 apt 不可用未能取得有效结果（§4.1 B3、§9 W-U4） | Flume 属采集链非关键路径；需要时手工下发 `apache-flume-1.11.0-bin.tar.gz` 解包至 `/opt`，**不依赖 apt** |

### 5.1 Scala 2.12.19 vs 2.12.18 警告（如实记录，未顺带升级）

- 事实：Spark 3.5.1 发行版内置 **`scala-library-2.12.18.jar`**（实测）。
- 事实：项目 `spark-jobs` 侧的 Scala 版本由 Maven 管控（见 `spark-jobs/pom.xml`，本任务**未读未改**该文件）。
- **结论（限定）**：若项目以 **2.12.19** 编译、而运行期 Spark 提供 **2.12.18**，属 **Scala 2.12.x 补丁级差异**；Scala 2.12.x 在小版本内声明二进制兼容，但**并非绝对保证**，实际是否告警/失败**必须实测**。本任务**不升级 Spark 内置 Scala**（父任务包明令禁止顺带升级），**未取证**：项目 `spark-jobs` 实际声明的 Scala 版本（需读 `pom.xml`，属其他泳道授权范围）。

### 5.2 组件安装与只读探针实测（原始输出见 `raw/54-w03-jdk17-hadoop-retry-and-probes.txt`）

| # | 探针（命令原文） | 实测原始输出 | 结论 |
|---|---|---|---|
| 1 | `JAVA_HOME=/opt/jdk-8u351 /opt/hadoop-3.3.4/bin/hadoop version` | `Hadoop 3.3.4`；`-r a585a73c3e02ac62350c136643a5e7f6095a3dbb`；`Compiled by stevel on 2022-07-29T12:32Z`；`This command was run using /opt/hadoop-3.3.4/share/hadoop/common/hadoop-common-3.3.4.jar` | **已确定：Hadoop 3.3.4 可用** |
| 2 | `JAVA_HOME=/opt/jdk-8u351 /opt/hadoop-3.3.4/bin/hdfs version` | `Hadoop 3.3.4 …` | **已确定：`hdfs` 可执行** |
| 2b | `JAVA_HOME=/opt/jdk-8u351 /opt/hadoop-3.3.4/bin/yarn version` | `Hadoop 3.3.4 …` | **已确定：`yarn` 可执行（但按裁决不启用）** |
| 3 | `JAVA_HOME=/opt/jdk-17.0.12 SPARK_HOME=… spark-submit --version` | `version 3.5.1`；**`Using Scala version 2.12.18, OpenJDK 64-Bit Server VM, 17.0.12`**；`Revision fd86f85e181fc2dc0f50a096855acf83a6cc5d9c` | **已确定：Spark 3.5.1 可在 JDK17 下运行** |
| 3b | 同上但 `JAVA_HOME=/opt/jdk-8u351` | `version 3.5.1`（亦有 WARN：hostname 解析到回环） | **已确定：Spark 3.5.1 亦可在 JDK8 下运行** |
| 4 | `JAVA_HOME=/opt/jdk-8u351 HIVE_HOME=… HADOOP_HOME=/opt/hadoop-3.3.4 hive --version` | `Hive 3.1.3`；`-r 4df4d75bf1e16fe0af75aad0b4179c34c07fc975`；`Compiled by ngangam on Sun Apr 3 16:58:16 EDT 2022`；附 SLF4J 多重绑定提示 | **已确定：Hive 3.1.3 可用** |
| 5 | `JAVA_HOME=/opt/jdk-17.0.12 /opt/jdk-17.0.12/bin/java -version` | `openjdk version "17.0.12" 2024-07-16`；`Temurin-17.0.12+7` | **已确定：JDK17 就位** |
| 5b | `JAVA_HOME=/opt/jdk-8u351 /opt/jdk-8u351/bin/java -version` | `java version "1.8.0_351"` | **已确定：既有 JDK8 保留可用** |
| **6（T2 实证）** | `java -version`（非交互、不设 `JAVA_HOME`） | `openjdk version "11.0.32"` — **不是 JDK8、也不是 JDK17** | **已确定：T2 陷阱复现**——默认拿到发行版 JDK11；**所有命令必须显式 `JAVA_HOME`** |
| **7（T1 实证）** | `command -v hadoop` / `ls -d /opt/hadoop-3.3.4` | `command -v hadoop` → **`/mnt/d/soft/hadoop/hadoop-3.3.4/bin/hadoop`**（Windows）；原生 → `/opt/hadoop-3.3.4` | **已确定：T1 陷阱复现且更严重**——见 §5.2.1 |
| 8 | `ls /opt/spark-3.5.1-bin-hadoop3/jars/ \| grep scala` | `scala-compiler-2.12.18.jar`、`scala-library-2.12.18.jar`、`scala-reflect-2.12.18.jar` | **已确定：发行版 Scala＝2.12.18**（项目侧 2.12.19 差异见 §5.1） |
| 9 | `ls -d /opt/jdk-8u351 /opt/spark-3.3.2-bin-hadoop3 …` | 四者**全部存在** | **已确定：授权要求保留的既有安装未被覆盖** |
| 10 | `sed -n '123,126p' $HOME/.bashrc` | `export JAVA_HOME=/opt/jdk-8u351` / `export PATH=$JAVA_HOME/bin:$PATH` / `export SPARK_HOME=/opt/spark-3.3.2-bin-hadoop3` / `export PATH=$SPARK_HOME/bin:$PATH` | **已确定：`.bashrc` 原样未改** |
| 11 | `ss -lnt \| grep -E ':(8020\|9870\|9864\|9083\|10000\|10002\|4040\|8088\|8042\|7077\|9000) '` ＋ `pgrep 'NameNode\|DataNode\|ResourceManager\|NodeManager\|metastore\|HiveServer2'` | `no HDFS/YARN/HMS/HiveServer2 port listening (as required)`；`no hadoop/hive/spark process (as required)` | **已确定：一个服务都没启动，一条 NameNode 都没 format** |
| 12 | `du -sh …; df -h /` | hadoop 1.4G / spark 428M / hive 358M / jdk17 317M；`/dev/sdd 1007G 7.5G 949G 1% /` | **已确定：全部落 ext4** |

#### 5.2.1 T1 陷阱的**根因定位**（本次新增，比 W01 更精确）

实测 WSL 内 `$PATH` 的构成（`raw/55-w03-spark-hms-config.txt`）：

- `/opt` **完全不在 PATH 上**（第 1–9 项为 `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:/usr/local/games:/usr/lib/wsl/lib`）；
- 宿主 Windows PATH 被**整段注入**，其中 **第 36 项是 `/mnt/d/soft/hadoop/hadoop-3.3.4/bin`**、第 37 项是 `.../sbin`、第 17 项是 `/mnt/d/Develop/JAVA17/bin`、第 26 项是 `/mnt/c/Program Files/MySQL/MySQL Server 8.0/bin`。

**结论**：只要不显式改 PATH，WSL 里 `hadoop` / `hdfs` 永远解析到 **`/mnt/d` 上的 Windows 版本**，而 `/opt/hadoop-3.3.4`（本次新装、真正可用）**永远被遮蔽**。这不仅是"看起来装了"的错觉，而是**命令必然走错实现**。

**规避（建议，写入文档但不改 `.bashrc`）**：
```bash
export PATH=/opt/hadoop-3.3.4/bin:/opt/hive-3.1.3/bin:/opt/spark-3.5.1-bin-hadoop3/bin:$PATH
```
> 该行**未写入** `.bashrc`（授权要求保留 `.bashrc:123-126` 原样）。后续泳道若需在交互/脚本中使用原生组件，请在脚本内显式前置此 `PATH`，或直接使用 `/opt/...` 绝对路径。

### 5.2.2 Spark ↔ HMS 3.1.3 的落地配置（已写入，可直接用）

按 §5 的兼容性结论，已把 **jars=path 模式**落到 Spark 3.5.1 配置中（原始输出 `raw/55-w03-spark-hms-config.txt`）：

| 落地项 | 实际内容 |
|---|---|
| Hive 3.1.3 客户端 jar 暂存 | `/data/spark-scratch/hive-3.1.3-client/`（**258 个 jar**，含 `hive-metastore-3.1.3.jar`、`guava-27.0-jre.jar`、`mysql-connector-j-8.0.33.jar`） |
| `spark-defaults.conf`（**写入 `/opt/spark-3.5.1-bin-hadoop3/conf/`，新装目录内，未动既有 3.3.2**） | `spark.sql.hive.metastore.version 3.1.3`<br>`spark.sql.hive.metastore.jars path`<br>`spark.sql.hive.metastore.jars.path file:///data/spark-scratch/hive-3.1.3-client/*.jar`<br>`spark.sql.warehouse.dir /data/metastore/warehouse`<br>`spark.sql.catalogImplementation hive` |
| `hive-site.xml`（同目录） | `javax.jdo.option.ConnectionURL=jdbc:mysql://127.0.0.1:3307/analytics_meta…`；`ConnectionDriverName=com.mysql.cj.jdbc.Driver`；`ConnectionUserName=root`；`hive.metastore.uris=thrift://127.0.0.1:9083`；`hive.metastore.warehouse.dir=/data/metastore/warehouse` |
| MySQL Connector/J | `/opt/hive-3.1.3/lib/mysql-connector-j-8.0.33.jar`（2,481,560 bytes，已下载） |

> **未取证**：该配置**尚未实跑**（需启动 HMS → 属禁止项）。仅完成"配置落盘 + jar 暂存"，**不声称已连通**。

### 5.2.3 下载件的官方校验（可复现性）

| 组件 | 本地 hash | 官方 hash | 结果 |
|---|---|---|---|
| `spark-3.5.1-bin-hadoop3.tgz` | `3D8E3F08…B355`（SHA512） | 同（archive.apache.org `.sha512`） | ✅ **MATCH** |
| `hadoop-3.3.4.tar.gz` | `CA5E1262…80B4`（SHA512） | 同（archive.apache.org `.sha512`） | ✅ **MATCH** |
| `apache-hive-3.1.3-bin.tar.gz` | `0C9B6A63…BB16`（SHA256） | 同（archive.apache.org `.sha256`） | ✅ **MATCH** |
| `OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz` | `9D4DD339…A778`（SHA256） | 同（Adoptium `.sha256.txt`） | ✅ **MATCH** |

### 5.3 双 JDK 显式命令路径（**零 `.bashrc` 依赖** — 针对 T2 陷阱）

以下为**可直接复制执行**的命令；每条都显式设 `JAVA_HOME`，不依赖任何 shell 启动文件。

**路径 A：Java 17（后端 / 现代构建链）**
```bash
JAVA_HOME=/opt/jdk-17.0.12 /opt/jdk-17.0.12/bin/java -version
# 后端构建示例（每次显式）
JAVA_HOME=/opt/jdk-17.0.12 PATH=/opt/jdk-17.0.12/bin:$PATH mvn -v
# Spark 3.5.1 用 JDK17
JAVA_HOME=/opt/jdk-17.0.12 SPARK_HOME=/opt/spark-3.5.1-bin-hadoop3 \
  /opt/spark-3.5.1-bin-hadoop3/bin/spark-submit --version
```

**路径 B：Java 8（Hadoop / Hive / Spark 侧，Hadoop 3.3.4 官方支持 8/11）**
```bash
JAVA_HOME=/opt/jdk-8u351 /opt/jdk-8u351/bin/java -version
# Hadoop
JAVA_HOME=/opt/jdk-8u351 HADOOP_HOME=/opt/hadoop-3.3.4 \
  /opt/hadoop-3.3.4/bin/hadoop version
# Hive / Metastore
JAVA_HOME=/opt/jdk-8u351 HIVE_HOME=/opt/hive-3.1.3 HADOOP_HOME=/opt/hadoop-3.3.4 \
  /opt/hive-3.1.3/bin/hive --version
# Spark 3.5.1 也可用 JDK8（Spark 3.5 支持 8/11/17）
JAVA_HOME=/opt/jdk-8u351 SPARK_HOME=/opt/spark-3.5.1-bin-hadoop3 \
  /opt/spark-3.5.1-bin-hadoop3/bin/spark-submit --version
```

> **T2 规避要点**：`.bashrc:123-126` 的 `JAVA_HOME=/opt/jdk-8u351` 在非交互 shell **不生效**。上表每条命令都自带 `JAVA_HOME=...`，因此**无论交互与否行为一致**。**未改动** `.bashrc`（授权要求原样保留）。

---

## 6. ④ sshd 关闭并禁止自启（已完成，原始输出 `raw/56-w03-sshd-stop-disable.txt`）

**关键发现**：`ssh` **不是**靠 `ssh.service` 常驻的，而是 **socket 激活**（`TriggeredBy: ● ssh.socket`）。只 `stop ssh` **不会**关闭 22 端口 —— 首次执行时 systemd 明确警告 `Stopping 'ssh.service', but its triggering units are still active: ssh.socket`。**必须同时停用 `ssh.socket`。**

| 项目 | 命令原文 | 原始输出 | 结论 | 风险 |
|---|---|---|---|---|
| 变更前 `ssh.service` | `systemctl is-enabled ssh` / `is-active ssh` | `disabled` / `inactive` | **已确定**：service 本身早已 disabled（但仍被 socket 拉起） | **高**：只看 service 会误判"已关" |
| 变更前 `ssh.socket` | `systemctl list-unit-files 'ssh*.socket'` | **`ssh.socket enabled enabled`** | **已确定**：**真正在监听的是 socket 单元** | 同上 |
| 变更前端口占用 | `ss -lntp \| grep ':22 '` | `LISTEN 0 4096 0.0.0.0:22`<br>`LISTEN 0 4096 [::]:22` | **已确定**：**22 端口确实在监听（IPv4＋IPv6 双栈）** | **高**：这违反"不对外暴露"的要求 |
| 执行 | `sudo systemctl stop ssh` | `Stopping 'ssh.service', but its triggering units are still active: ssh.socket`；exit=0 | — | — |
| 执行 | `sudo systemctl disable ssh` | `Removed …`；exit=0 | — | — |
| 执行（**关键补齐**） | `sudo systemctl stop ssh.socket`<br>`sudo systemctl disable ssh.socket` | `Removed '/etc/systemd/system/sockets.target.wants/ssh.socket'.`<br>`Removed '/etc/systemd/system/ssh.service.requires/ssh.socket'.` | **已确定**：socket 激活源已摘除 | — |
| 变更后 `ssh.service` | `systemctl is-enabled ssh` / `is-active ssh` / `is-failed ssh` | `disabled` / `inactive` / `inactive` | **已确定** | — |
| **变更后端口** | `ss -lntp \| grep ':22 '` | **无输出** → `OK: no :22 listener` | **已确定：22 端口已不监听** | — |
| 变更后进程 | `pgrep -a sshd` | **无输出** → `OK: no sshd process` | **已确定：无 sshd 进程** | — |
| 变更后 WSL 全部监听 | `ss -lntp` | `127.0.0.53%lo:53`、`127.0.0.54:53`、`10.255.255.254:53`（systemd-resolved）＋ **`127.0.0.1:3307`（隔离 MySQL，仅回环）** | **已确定**：WSL 内**只余 DNS 与回环 MySQL**，无对外服务 | 低 |
| 未改配置 | `ls -l /etc/ssh/sshd_config` | `-rw-r--r-- root root 4307 Jul 10 02:06` | **已确定**：**配置文件未改动**，只改运行态与开机自启 | — |

> **与"本地服务绑 127.0.0.1、不为方便暴露公网"的一致性**：关闭 sshd（含其 socket 激活）正是该原则的落地——WSL 不再提供任何监听 `0.0.0.0` 的服务。

---

## 7. 资源实测与风险（WSL 与 VMware 三节点同机并行）

| 时点 | 宿主可用内存 | WSL 内 `free -m` | 说明 |
|---|---|---|---|
| WSL Stopped（改配置后） | **14.17 GB / 31.63 GB** | — | 限额生效后的基线 |
| WSL 刚启动 | **11.96 GB** | total 7942 / used 698 | WSL 基线开销约 2.2 GB |
| 安装与解包进行中 | **6.21 GB** | total 7942 / used 783 / buff 2404 | 大文件解包推高 buff/cache（可回收） |
| 四组件安装中（最低观测） | **4.61 GB** | total 7942 / used 892 / buff 3712 | 安装期观测低点 |
| **最终态（MySQL＋全组件已装，全部就绪）** | **4.57 GB / 31.63 GB** | total 7942 / used **1137** / free 721 / buff 6288 / available **6805** | **本次交付终点实测**：WSL 实占约 1.1 GB 活跃＋6.3 GB 缓存（缓存可回收）；宿主余 4.57 GB |
| 宿主磁盘（最终） | C 余 **69.00 GB**、D 余 **428.40 GB** | ext4 `/` 用 **7.9 G** / 余 **948 G** | 全部新装落 ext4 ✅ |

**风险（单列，按要求写明实测余量）**：

| 风险 | 实测依据 | 说明 |
|---|---|---|
| **R-A WSL 与 VMware 三节点同机并行争抢内存** | 本机总内存 **31.63 GB**；WSL 已限额 **8 GB**（实测 total 7942 MiB）；安装期宿主可用内存最低 **4.61 GB** | 若 VMware 三节点同时运行（每节点按 2–4 GB 计，三节点约 6–12 GB），宿主剩余将逼近或跌破安全线。**WSL 8 GB 限额是必要的护栏**；不足时用户已授权升 12 GB，但升前应确认 VMware 侧占用。 |
| **R-B 宿主基线负载已较重** | W01 首测宿主可用仅 **3.56 GB**（IDEA＋DataGrip＋Docker 生态未运行时尚且如此） | 本机是活跃开发机（VS Code/IDEA/DataGrip/ROG 全家桶/OneDrive/FlClash），内存余量天然紧张 |
| **R-C C 盘仅余 74.80 GB 且宿主 MySQL datadir 在 C** | W01 实测 | 授权明确"MySQL 默认不动"。任何在宿主 MySQL 上的数据增长都会挤占 C 盘。**隔离实例落 ext4，正是为了不再加剧 C 盘压力** |
| **R-D Ubuntu 26.04 偏新，Hadoop/Hive 未官方验证** | `libaio.so.1` 已实测缺失（须装 `libaio1t64`）；spark 3.5.1/Hadoop 3.3.4/Hive 3.1.3 均**非为 26.04 构建** | 已遇 1 例真实兼容问题（libaio 改名）。后续可能有更多（glibc/openssl/ncurses）。**这是 WSL 只做本地开发、不做正式集群的又一理由** |
| **R-E 本任务启动了原本 Stopped 的 WSL 取证与安装** | `wsl -l -v` 显示当前 Ubuntu **Running** | 授权流程是「追加限额 → shutdown 复位 → 再装包」；安装必须运行 WSL，故结束时 WSL 为 Running。**是否再次 shutdown 复位需用户决定**（属宿主机级状态）。 |
| **R-F（新增，高）WSL 内服务随 WSL 生命周期存亡** | 隔离 MySQL 进程 `pid 2055` 运行在 WSL 内；`wsl --shutdown` 后 WSL 已实测复位为 Stopped | **E3/E5 若依赖 `127.0.0.1:3307`，则必须先确保 WSL 处于 Running**。WSL 停止 → 3307 立即不可用 → 应用连接失败。**这不是 bug，是架构事实，必须写进 E3/E5 的启动前置检查**。建议：由启动脚本 `wsl -d Ubuntu -- bash -c "bash <start-isolated-mysql-3307.sh>"`（幂等）保证实例在。 |
| **R-G（新增，中）`apt` 在本 WSL 内不可用** | `apt-get install -y -q nodejs npm` 挂起 6 分钟、`archives/partial` 恒为 0 字节；后续 `apt-get` 被锁阻塞；25s 超时探测无输出 | 影响后续任何"apt 装包"计划（Node/Flume/pip 等）。**建议统一改用手工二进制下发**。索引可下载而包体不可下载，指向镜像/代理/网络策略问题，**未取证**具体原因。 |
| **R-H（新增，高）WSL 内 git 无法识别两个次 worktree** | `git worktree list` 报 `<repo>/.git/worktrees/<name>` 且标 `prunable gitdir file points to non-existent location`；`git -C <该路径> status` → `fatal: this operation must be run in a work tree`。根因：worktree 的 `.git` 指针存 **Windows 路径** | **任何在 WSL 内盘点/操作这两个 worktree 的泳道都会得到假阴性**（"干净"或"0 文件"），并可能据此**误判无需备份/无需处理**。这是**会让人做出错误决策**的陷阱，优先级高。**绕行**：`git --git-dir=<repo>/.git/worktrees/<name> --work-tree=<真实路径>`（`--git-dir` 必须绝对路径） |
| **R-I（新增，中）仓库被多泳道并发写入，备份/盘点必然过期** | snap-1（12:20:25）后，7 个已跟踪文件在 12:20:48–12:31:39 再被修改，并新增 65 个未跟踪文件 | **单次快照不足以作为"证据冻结"**。任何以"盘点结果"为依据的结论都必须**记录快照时刻**，并在关键节点**复采**。本任务因此做了 4 个快照（§3.4） |

---

## 8. 明确「不做 ＋ 理由」清单（用户更正裁决要求登记）

| 不做的项 | 理由 |
|---|---|
| **三节点 / HA Hadoop 集群** | 用户更正裁决明确**禁止**在 WSL 内复制三节点集群；大集群仍是 VMware 三节点 |
| **YARN** | V2.5 §6.1 本就规定初期不启动 YARN；单节点 local[1] 不需要 |
| **Spark standalone 集群** | 同上；仅用 `local[1]` |
| **HiveServer2** | V2.5 §6.1 规定初期不启动；HMS 已足够支撑 Metastore 功能闭环 |
| **NameNode format** | 父任务包**明令禁止**；按需启动留给 I01/E01 |
| **启动 HDFS / HMS / Spark / HiveServer2** | 父任务包明令禁止，本任务未启动（§5.2 探针为只读，且已验证无相关端口/进程） |
| **WSL 内多节点 ZK / Kafka / HBase 等** | 只对多节点集群有用 → 按裁决「直接跳过」 |
| **升级 Spark 内置 Scala（2.12.18→2.12.19）** | 父任务包明令禁止顺带升级 Spark |
| **覆盖 `/etc/wsl.conf`、`.bashrc:123-126`、`/opt/jdk-8u351`、`/opt/spark-3.3.2-bin-hadoop3`** | 授权要求**一律保留不动**，新装另放（已验证四者均在） |
| **清理 `landing/manifests/40–43.json`、`derby-metastore/`、`metastore_db/`、`derby.log`** | 父任务包明令不删任何历史文件 |
| **宿主 MySQL 迁移 datadir / drop / TRUNCATE / 清理历史库** | 授权「MySQL＝默认不动」；隔离实例在 WSL 内新建 |
| **启动/停止 8090/8091/8092** | 保持停 |
| **使用 `/mnt/d/soft/hadoop` 那个假 hadoop** | T1 陷阱 |
| **任何 git 写操作** | 父任务包明令禁止 |

---

## 9. 未取证项（显式声明，不写推断结论）

| # | 未取证项 | 原因 |
|---|---|---|
| W-U1 | 项目 `spark-jobs/pom.xml` 声明的 Scala 版本 | 属 `spark-jobs/**` 授权禁改区域；**未读未改**（读本身可行，但本任务聚焦环境侧，且父任务包要求不顺带处理 Spark/Scala 升级） |
| W-U2 | ~~`.f88-baseline-wt` 与 `m3-jdk8fix` 两个 worktree 全部已跟踪文件的逐文件 hash~~ | ✅ **已补齐**（§3.4 snap-4）：两个 worktree 的全部**已修改**文件（1226 ＋ 1524）已逐文件 sha256 并备份。此前"路径不可达"的障碍已用显式 `--git-dir/--work-tree` 绕行解决 |
| W-U3 | 宿主 MySQL 的库表内容 | 授权「默认不动」；本任务只做 WSL 侧 |
| **W-U4** | **`Flume` 未安装** | **`apt` 在本 WSL 内挂起不可用（§4.1 B3）**，且 Flume 仅用于采集链，非本任务关键路径。**未联网取 tarball，故未装**。需要时建议手工下发 `apache-flume-1.11.0-bin.tar.gz` 并解包到 `/opt`，**不要依赖 apt** |
| **W-U5** | **WSL 内无 Node / pnpm（原生）** | 实测 `command -v node` → **`none`**；`command -v npm` → **`/mnt/c/Program Files/nodejs/npm`（版本 11.13.0，是 Windows 透传，同 T1 性质，不可靠）**；`apt-get install nodejs npm` 在 25s 超时内**无任何输出**（apt 已确认不可用）。**未用非官方渠道（nvm/第三方源）安装**，故如实记未取证。前端构建需要时建议手工下发 Node 官方 `linux-x64` tarball 到 `/opt` |
| W-U6 | HDFS/HMS/Spark 实际可启动性 | 父任务包**禁止启动服务**；仅做只读探针（`version` / 可执行性），已按 §5.2 完成 |
| W-U7 | Hive 3.1.3 schema 初始化（`schematool -initSchema`）结果 | 属"启动/写库"范畴，且需先起 HMS → 留给 I01/E01 |
| **W-U8** | **Spark↔HMS 3.1.3 是否真能连通** | 配置已落盘＋jar 已暂存（§5.2.2），但**实跑需启动 HMS**（禁止项），故**不声称已连通** |
| **W-U9（新增）** | **两个次 worktree 的"未推送提交"** | 两者均为 **detached HEAD**（`dba4381`／`f26be26`），其提交是否存在于远端分支**未逐 ref 验证**。原因：`git worktree list`/`rev-parse` 在 WSL 内对该二 worktree 不可用（§3.4 D2），需以显式 `--git-dir` 逐个 ref 比对；又因本任务**不得做任何 git 写操作**（含 `fetch`），无法刷新远端 ref 后比对。**未声称**其提交已推送或未推送 |

---

## 10. raw/ 原始输出文件索引

| 文件 | 覆盖内容 |
|---|---|
| `raw/30-wslconfig-BEFORE-1220.txt` | `.wslconfig` 原始内容（48 B，仅两键） |
| `raw/31-wslconfig-AFTER-1220.txt` | 首次追加后（`memory=6GB`，76 B；**后被 12:24 裁决取代**，留证对照） |
| `raw/32-wslconfig-BEFORE-1224-6GB.txt` | 中间态（6GB/2C 版，已作废留证） |
| `raw/33-wslconfig-AFTER-1224-8GB.txt` | **最终** `.wslconfig`（8GB/4C/4GB swap，两既有键保留） |
| `raw/40-w02-git-worktree-inventory.txt` | `git worktree list`、`branch -vv`、`remote -v`、`stash list`、`status --porcelain -uall`、diff stat |
| `raw/41-w02-remotes-and-worktree-details.txt` | `remote -v`、`branch -a`、`for-each-ref`（上游映射）、ahead/behind 计数、各 worktree 变更集 |
| `raw/42-w02-backup-manifest-sha256.txt` | 首版清单（**因格式缺陷作废**，留证对照） |
| `raw/43-w02-backup-manifest-sha256-FINAL.txt` | **最终** 224 条 sha256 备份清单（worktree/status/path/sha256/bytes/verdict） |
| `raw/44-w02-locked-files-retry.txt` | 2 个被其他泳道占用的文件重试结果（均 VERIFIED） |
| `raw/50-w03-mysql-isolated-deploy.txt` | 隔离 MySQL 首次部署全过程（**含 libaio 失败原文**，留证） |
| `raw/51-w03-install-and-probes.txt` | 首轮组件安装（Spark/Hive 成功；JDK17/Hadoop 因 tarball 未下载完而失败，留证） |
| `raw/52-w03-libaio-diagnosis.txt` | `ldd` 缺失库诊断（libaio.so.1 ＋ libnuma.so.1）＋ `apt-cache policy` |
| `raw/52b-w03-mysql-libs-manual-extract.txt` | **绕过 apt**：`.deb` 下载 → `dpkg-deb -x` 抽取库 → `/opt/mysql-libs` |
| `raw/53-w03-mysql-recovery.txt` | 首次 recovery（**apt 锁冲突失败**，留证） |
| **`raw/53-w03-mysql-isolated-FINAL.txt`** | **隔离 MySQL 最终部署**：soname 修复 → init → 启动成功（S0–S4） |
| **`raw/53b-w03-mysql-accounts-and-connectivity.txt`** | **★ 交付凭据实测**：账号建立、库建立、TCP/socket 连通、读写往返、隔离性 |
| `raw/54-w03-jdk17-hadoop-retry-and-probes.txt` | **★ 全部只读探针**：hadoop/hdfs/yarn/spark-submit/hive/双 java＋T1/T2 复现＋未启动验证 |
| `raw/55-w03-spark-hms-config.txt` | Spark↔HMS 3.1.3 配置落地＋**PATH 构成取证（T1 根因）** |
| `raw/56-w03-sshd-stop-disable.txt` | **★ sshd 关闭**：含 `ssh.socket` 关键发现，前/后状态＋端口占用 |
| `raw/57-w03-node-pnpm.txt` | Node/pnpm 实测（`none` / Windows 透传 npm 11.13.0 / apt 超时） |
| `raw/58-w03-final-state.txt` | **最终态**：全组件版本 ＋ 无对外监听 ＋ NameNode 未 format ＋ 无服务运行 |
| **`raw/59-w02-snapshot2-backup-log.txt`** | snap-2 执行日志（含 **NUL 被命令替换吞掉导致 0 条目**的失败留证） |
| `raw/60-w02-backup-manifest-snap2-sha256.txt` | snap-2 清单（1100 条，全 VERIFIED） |
| **`raw/61-w02-snapshot2-anomaly-analysis.txt`** | snap-2 异常分析：**发现 6 个文件未被 snap-2 携带**（均在 snap-1 中）→ 定下「多快照**叠加保留**、不覆盖」策略 |
| **`raw/61a-w02-worktree-state.txt`** | worktree 状态诊断 → 暴露 **D2（路径指向 admin dir）** |
| **`raw/61b-w02-worktree-list-authoritative.txt`** | `git worktree list --porcelain` 原文（`prunable` 证据） |
| **`raw/61c-w02-worktree-heads.txt`** | 各 worktree HEAD（main `f00462c` 已前移） |
| `raw/62-w02-backup-manifest-snap3-sha256.txt` | snap-3 清单（1121 条，只覆盖 main） |
| **`raw/62-w02-snapshot3-backup-log.txt`** | snap-3 执行日志（仍只覆盖 main） |
| **`raw/63-w02-worktree-location-locate.txt`** | **定位真实 worktree 路径**：`GraduationProject-wt\m3-jdk8fix`、`Develop\GraduationProject\.f88-baseline-wt` |
| `raw/64-w02-worktree-broken-gitdir.txt` | `.git` 指针文件原文 ＋ 显式 `--git-dir/--work-tree` 首次可用盘点 |
| **`raw/65-w02-backup-manifest-snap4-worktrees-sha256.txt`** | **★ snap-4 权威清单**（2750 条：m3 1226 ＋ f88 1524） |
| **`raw/65-w02-snapshot4-worktree-backup-log.txt`** | snap-4 执行日志（2741 VERIFIED ＋ 9 正则命中） |
| **`raw/66-w02-all-snapshots-verification.txt`** | **★ 逐文件重算校验**：2741 OK / 0 失配；13 例 live↔backup 字节比对全 SAME |
| `raw/67-w02-secret-excluded-recovery-hashes.txt` | 9 个正则命中文件的 blob id 记录 |
| `raw/67-w02-secret-excluded-recovery-log.txt` | 同上，执行日志 |
| `raw/67b-w02-blob-roundtrip-proof.txt` | 首轮 blob 取回尝试（**假失败**：`cmp` 对比的 out 文件为空，因 `cat-file` 目标 blob 不存在） |
| **`raw/67c-w02-blob-recovery-final.txt`** | **决定性证据**：`hash-object --no-filters` 与普通模式**得到同一 blob id**（无过滤器干扰），但 `cat-file -p <blob>` 返回 **0 字节** → 被改动内容**不在对象库、git 不可恢复** |
| **`raw/68-w02-false-positive-secret-files-BACKED-UP.txt`** | **★ F1 修正**：9 个误杀文件**按内容补备** ＋ 清单 verdict 升级记录 |
| `raw/68-w02-false-positive-backup-log.txt` | F1 补备执行日志（9/9 GRABBED） |
| **`raw/69-w03-final-state-recheck.txt`** | **交付终点复核**：3307 仍在（`TCP_OK 8.0.41 3307 127.0.0.1`）、无集群服务、NameNode 未 format、sshd 关闭、双 JDK/组件版本 |
| **`raw/70-w03-spark-probe-and-count-reconcile.txt`** | `spark-submit --version` 原文（JDK17 **与** JDK8 各一次）＋ 备份文件数对账 |
| `raw/12-wsl-ports-systemd.conf.txt` | （W01 沿用）WSL 内端口、`/etc/wsl.conf`、systemd、项目目录可写性 |
| `raw/scripts/start-isolated-mysql-3307.sh` | **可重复执行的隔离 MySQL 启动脚本**（幂等） |
| `raw/scripts/w02-snapshot2-backup.sh` | snap-2 备份脚本（含 NUL 解析修正注释） |
| `raw/scripts/w02-snapshot3-backup.sh` | snap-3 备份脚本（从 git 推导 worktree 路径） |
| `raw/scripts/w02-snapshot4-worktree-backup.sh` | **snap-4 备份脚本**（显式 `--git-dir/--work-tree`，覆盖被漏掉的两个 worktree） |
| `raw/scripts/_tmp/` | 一次性取证脚本原文（`tmp-mysql-deploy.sh`、`tmp-mysql-recover.sh`、`tmp-w03-install.sh`、`tmp-w03-retry.sh`，留证可复核） |

---

## 11. ✅ 最终态验收快照（交付终点实测，原始输出 `raw/58-w03-final-state.txt`）

| 维度 | 实测 | 判定 |
|---|---|---|
| 发行版 | `Ubuntu 26.04 LTS (resolute)`，kernel `6.18.33.2-microsoft-standard-WSL2` | — |
| 资源限额 | `Mem total 7942 MiB` / `nproc=4` / `swap 4G` | ✅ 8GB/4C/4GB 生效 |
| JDK17 | `openjdk version "17.0.12" 2024-07-16`（`/opt/jdk-17.0.12`） | ✅ |
| JDK8 | `java version "1.8.0_351"`（`/opt/jdk-8u351`，**保留未覆盖**） | ✅ |
| Hadoop | `Hadoop 3.3.4`（`/opt/hadoop-3.3.4`） | ✅ |
| Spark | `version 3.5.1`（`/opt/spark-3.5.1-bin-hadoop3`） | ✅ |
| Hive | `Hive 3.1.3`（`/opt/hive-3.1.3`） | ✅ |
| MySQL 隔离实例 | `127.0.0.1:3307` 监听中（`/opt/mysql-8.0.41`） | ✅ |
| `/opt` 清单 | `hadoop-3.3.4`、`hive-3.1.3`、`jdk-17.0.12`、`jdk-8u351`、`mysql-8.0.41`、`mysql-libs`、`spark-3.3.2-bin-hadoop3`、`spark-3.5.1-bin-hadoop3` | ✅ 既有＋新增并存 |
| `/data` 清单 | `hdfs`(12K)、`metastore`(4K)、`mysql-isolated`(193M)、`spark-scratch`(279M) | ✅ 全在 ext4 |
| **对外监听** | 仅 `systemd-resolved` 的 53 与 **`127.0.0.1:3307`** | ✅ **无 0.0.0.0 服务** |
| **NameNode 未 format** | `/data/hdfs/name` 条目数 = **0** | ✅ **从未 format** |
| **服务未启动** | `pgrep 'NameNode\|DataNode\|ResourceManager\|NodeManager\|HiveServer2'` → **OK: none** | ✅ |
| **W02 备份最终态** | 4 个快照共 **5195 文件 / 189 MB**；snap-4 的 **2750/2750** 逐文件重算 sha256 全部通过，清单条目数＝磁盘文件数 | ✅ **W02 完整闭合**（§3.4） |

---

## 12. 证据级别与纪律声明

- 本文件证据级别：**E1（只读盘点）＋ 用户明示授权的变更**（`.wslconfig` 追加、WSL 内安装、sshd 关闭）。所有变更均可由 §2/§5/§6 的命令原文与 `raw/` 原始输出复核。
- **未做**：未 `format` 任何 NameNode；未启动 HDFS/HMS/Spark/HiveServer2（§5.2 已用 `ss`/`pgrep` 验证无相关端口与进程）；未碰宿主 MySQL 任何库表；未启停 8090/8091/8092；未使用 `/mnt/d` 的假 hadoop；未改 `spark-jobs/**`、契约、指导书、看板；**未做任何 git 写操作**；未删任何历史文件。
- 凡未跑通项，一律写「未取证」并说明原因（§9），**不写推断结论**。
- **自我纠错声明（必须保留）**：本文件 §3.1–3.3 的初版结论（「`.f88-baseline-wt` 干净」「`m3-jdk8fix` 只有 4 个改动」「备份 224 文件即完成」）**是错的**，原因是踩中了 **§3.4 D2** —— WSL 内 `git worktree list` 对两个次 worktree 报的是 **git 管理目录**而非工作树，`git -C` 随即失败，导致**静默假阴性**。经只读绕行（显式 `--git-dir/--work-tree`）复采后修正为 **1226 ＋ 1524**。**初版结论保留在 §3.1 以便对照**，权威结论以 **§3.4** 为准。
- **同类陷阱提醒**：本会话共踩到 **3 类"看似成功实则 0 结果"的命令行陷阱**，均已留证并对后续泳道有普遍价值：(1) `git status -z` 输出经命令替换/变量赋值会**吞掉 NUL** → 0 条目；(2) PowerShell 对 NUL 分隔输出**不能可靠 split**；(3) `git --git-dir` 给**相对路径**会以 cwd 解析而失败。**凡"应该有很多结果却得到 0/1 条"的情况，一律先怀疑这三条，不要先下结论。**
