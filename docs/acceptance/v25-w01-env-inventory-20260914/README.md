# V25-W01：WSL 与宿主只读盘点

- **任务 ID**：V25-W01
- **泳道**：L3
- **日期**：2026-09-14 12:06–12:10 (CST, UTC+8)
- **分支**：`remediation/r1-boundary`
- **证据级别**：**E1（只读盘点）**
- **目标**：为「单个 WSL2 Ubuntu 单节点内运行 HDFS + Hive Metastore + Spark local[1]」这一主集成环境决策，提供开工前的现状事实。
- **动作边界**：本任务**只执行只读查询**。未安装/卸载任何软件，未执行 `wsl --shutdown`/`--terminate`/导入导出，未修改任何配置或环境变量，未下载、未 `sudo` 提权写入，未启动或停止任何项目服务，未写数据库，未 `git add/commit/push`。

> **重要副作用声明**：`Ubuntu` 发行版在本任务开始时为 `Stopped`，为取证其在发行版内部执行只读命令而被启动。**启动发行版是本次盘点唯一的系统状态变化**（未改配置、未装包、未启服务）。这与「禁止 `wsl --shutdown`」不冲突（未执行关闭）。发行版当前处于 `Running`，是否关闭需用户决定（见问题 Q6）。

---

## 0. 摘要在手：与本决策直接相关的 8 条硬事实

| # | 事实 | 证据 |
|---|---|---|
| F1 | WSL 默认且唯一 Linux 发行版 = `Ubuntu`，**实际为 Ubuntu 26.04 LTS（resolute）**，不是资料假设的 22.04 | A2 |
| F2 | 该发行版根盘 VHDX 在 **`D:\Develop\wsl\Ubuntu\ext4.vhdx`**（4.66 GB 实占），WSL 内可见 `/` 为 1007 G / 953 G 可用 | A2 |
| F3 | `.wslconfig` 存在，**只设了 `networkingMode=mirrored` + `dnsTunneling=true`，未设 memory/processor/swap** | A1 |
| F4 | WSL 内**已是 root 免密 sudo**（`sudo -n true` 成功），apt 源在线可 `HTTP 200`，`apt-get --dry-run` 可解依赖 | A3 |
| F5 | WSL 内**已装**：OpenJDK 11 / Maven 3.9.12 / MySQL **客户端** 8.4.10 / Python 3.14.4 / git / sshd；**未装**：Hadoop、Spark、Hive、MySQL **服务端**、node、pnpm | A3 |
| F6 | WSL 内**已有非 apt 的本地安装**：`/opt/jdk-8u351`（JDK8）、`/opt/spark-3.3.2-bin-hadoop3`（Spark 3.3.2, built for Hadoop 3.3.2）；`.bashrc` 已 `export JAVA_HOME=/opt/jdk-8u351` 与 `SPARK_HOME=/opt/spark-3.3.2-bin-hadoop3` | A3 |
| F7 | **8090/8091/8092 及 HDFS/Hive/Spark 相关端口（9870/9864/9083/10000/10002/8020/9000/7077/4040/8088/8042）全部无监听**；唯一相关占用是 3306（宿主 MySQL80 服务） | A6 |
| F8 | 宿主与 WSL 端口空间**互通**（mirrored 网络 + `/etc/hosts` 式转发）：宿主 `3306` 从 WSL 探测为 `OPEN`，WSL 内无 mysqld | A7 |

---

## 1. A1 — WSL 宿主侧与配置

| 项目 | 命令原文 | 原始输出摘要 | 结论 | 风险 |
|---|---|---|---|---|
| 已安装发行版 | `wsl -l -v` | `* Ubuntu  Stopped  2` / `docker-desktop  Stopped  2`（`*`=默认） | **已确定**：默认=`Ubuntu`，2 个发行版，均 WSL2 | 启动前均为 Stopped（低） |
| WSL 状态 | `wsl --status` | 默认发行版: Ubuntu；默认版本: 2 | **已确定** | — |
| WSL 版本 | `wsl --version` | WSL `2.7.10.0`；内核 `6.18.33.2-2`；WSLg `1.0.73.2`；MSRDC `1.2.6676`；Windows `10.0.26100.4652` | **已确定** | 内核 6.18 较新，Hadoop 3.3.4 native lib 兼容性未验证（中） |
| 可安装发行版 | `wsl -l -o` | 列出 `Ubuntu`、`Ubuntu-26.04`、`Ubuntu-24.04`、`Ubuntu-22.04`、`Debian`、`kali-linux`、`openSUSE-*`、`AlmaLinux-*`、`FedoraLinux-44/43`、`archlinux`、`OracleLinux_*`、`eLxr`、`SUSE-Linux-Enterprise-*` | **已确定** | 无需新建发行版（低） |
| `.wslconfig` 路径 | `Test-Path $env:USERPROFILE\.wslconfig` | `C:\Users\ASUS\.wslconfig` → `True` | **已确定** | — |
| `.wslconfig` 内容 | `Get-Content $env:USERPROFILE\.wslconfig -Raw` | `[wsl2]` / `networkingMode=mirrored` / `dnsTunneling=true`（**无** memory/processors/swap） | **已确定**：内存/CPU 上限**由 WSL 默认策略决定**，非显式配置 | **中**：WSL 默认取宿主内存约 50%（实测 15.8 GB/31.6 GB）。若要与宿主 MySQL/IDE 并存，需显式设限 → 属用户偏好，**Q3** |
| 发行版根盘位置 | `Get-ItemProperty HKCU:\...\Lxss\*` | `Ubuntu \| BasePath=D:\Develop\wsl\Ubuntu \| ver=2 \| flags=15 \| DefaultUid=0`；`docker-desktop \| BasePath=D:\Develop\Docker\DockerDesktopWSL\main` | **已确定**：Ubuntu 根盘在 **D 盘**，不在 C 盘 | **低**（D 盘余量充足） |
| 发行版根盘文件 | `Get-ChildItem D:\Develop\wsl\Ubuntu -Force` | `ext4.vhdx` **4.66 GB**；`shortcut.ico` | **已确定** | 中：VHDX 会随 HDFS 数据增长，D 盘余 434.9 GB（充足） |
| 发行版根盘可用空间 | WSL 内 `df -h /` | `/dev/sdd 1007G 3.5G 953G 1% /` | **已确定**：**盘内 953 GB 可用** | **低**：与 `D:` 余量不是同一池（VHDX 稀疏，宿主实占 4.66 GB） |
| 默认登录用户 | WSL 内 `whoami` / `/etc/wsl.conf` | `asus`；`[user] default=asus`；`/etc/wsl.conf` 另含 `[boot] systemd=true` | **已确定** | — |
| systemd | WSL 内 `ps -p 1 -o comm=` / `systemctl is-system-running` | `systemd`；`running`（14 个活动 unit：chrony/cron/dbus/rsyslog/snapd/ssh/unattended-upgrades/wsl-pro…） | **已确定**：**systemd 已启用且运行中** | **低→中**：HDFS/Hive 可用 systemd unit，但也会引入开机自启行为，需用户确认（**Q7**） |

## 2. A2 — WSL 发行版内部

| 项目 | 命令原文 | 原始输出摘要 | 结论 | 风险 |
|---|---|---|---|---|
| 发行版标识 | `lsb_release -a` | `Ubuntu` / `Ubuntu 26.04 LTS` / `Release: 26.04` / `Codename: resolute` | **已确定** | **高**：见下方「关键落差」 |
| 内核 | `uname -a` | `Linux dahaishui 6.18.33.2-microsoft-standard-WSL2 #1 SMP PREEMPT_DYNAMIC ... x86_64` | **已确定** | 主机名 `dahaishui` |
| OS release | `cat /etc/os-release` | `VERSION="26.04 LTS (Resolute Raccoon)"` | **已确定** | — |
| CPU 可见核数 | `nproc` / `grep -c ^processor /proc/cpuinfo` | `32` / `32` | **已确定**：宿主 i9-14900HX 全 32 逻辑核**全部透传** | **中**：未设 `processors=` 上限，WSL 可与宿主争抢全部 32 线程 |
| 内存 | `free -m` | `Mem: total 15797 MiB / used 1241 / free 13403 / available 14555`；`Swap: 4096 MiB` | **已确定**：**15.8 GiB 可用 RAM、4 GiB swap**（宿主 31.63 GB 的约 50%） | **中**：HDFS + HMS + Spark 单节点可跑，但余量不宽裕 → 内存上限属用户偏好（**Q3**） |
| `/opt` | `ls -la /opt` | `jdk-8u351`(asus:asus 755)、`spark-3.3.2-bin-hadoop3`(asus:asus 755) | **已确定** | 非 root 拥有，可直接读写 |
| `/usr/local` | `ls -la /usr/local` | 仅系统默认目录，**无 spark/hadoop/hive** | **已确定** | — |
| `$HOME` | `ls -la $HOME` | `/home/asus`：`.bashrc`(+3 个 .bak)、`.ssh`、`.cache`、`.config`、`.local`、`bd-case/`、`ds_logs/`、`spark-dl/` | **已确定** | — |
| `$HOME` 遗留目录内容 | `ls -la $HOME/{bd-case,ds_logs,spark-dl}` | `bd-case/data/`(空)；`ds_logs/ds_logs.tgz`(**0 字节**)；`spark-dl/spark-3.3.2-bin-hadoop3.2.tgz`(**0 字节**) | **已确定**：上次安装尝试的**失败残留（0 字节下载文件）** | 低：不含有效数据，但**是否清理需用户决定**（本任务不动） |
| `$HOME` 相关目录检索 | `find $HOME /opt /usr/local -maxdepth 2 -iname '*spark*' -o ... '*hive*' -o ... '*hadoop*'` | 仅 `spark-dl`、`spark-dl/*.tgz`、`/opt/spark-3.3.2-bin-hadoop3`；**无 hive、无 hadoop** | **已确定** | — |
| 磁盘全景 | `df -h` | `/` 1007G/953G 可用；`/mnt/c` 338G/75G；`/mnt/d` 587G/435G；`/mnt/e` 1.9T/354G；`/tmp` tmpfs 7.8G | **已确定** | — |
| 块设备 | `lsblk` | `sda` 356.9M(ro)、`sdb` 159.4M(ro)、`sdc` 4G `[SWAP]`、`sdd` 1T `/mnt/wslg/distro` + `/` | **已确定** | — |
| 项目检出可见性 | `ls /mnt/d/Develop_code/GraduationProject` / `test -w` | 可见（`README.md`、`analytics-server`、`docs`、`mall-simulator`…）；`WRITABLE_BY_ASUS` | **已确定** | **高**：`/mnt/d` 跨 9P 文件系统的 HDFS 元数据/IO 性能与权限语义是已知坑点，**不建议把 HDFS datadir 放在 `/mnt/d`** |
| 网络/DNS | `curl -o /dev/null -w ... archive.ubuntu.com` / `dlcdn.apache.org` / `getent hosts` | `HTTP=200 time=0.97s` / `HTTP=200 time=1.21s` / `28.0.0.217` | **已确定**：**有外网出网能力**，apt 与 Apache 下载站均可达 | 低；但**是否允许在 WSL 内联网装包须用户确认**（**Q1**） |
| resolv.conf | `cat /etc/resolv.conf` | `nameserver 10.255.255.254`（WSL 自动生成，NAT/mirrored 网关） | **已确定** | — |

### 关键落差（必须记入风险）

| 落差 | 事实 | 影响 |
|---|---|---|
| **发行版版本** | 资料/预期为 **Ubuntu 22.04 LTS**；实测 **Ubuntu 26.04 LTS (resolute)**。旁证：`C:\Users\ASUS\AppData\Local\Packages\CanonicalGroupLimited.Ubuntu22.04LTS_79rhkp1fndgsc` 存在，但该目录**不是** Lxss BasePath（BasePath=`D:\Develop\wsl\Ubuntu`），说明此 22.04 包目录是**历史残留或仅含壳层数据**，当前发行版已升级到 26.04 | Ubuntu 26.04 是很新的版本，**Hive 3.1.x / Hadoop 3.3.4 官方并未针对 26.04 验证**；OpenJDK 11 可用，但 Hadoop/Hive 的 glibc/openssl 兼容性需实测 |
| **`.bashrc` 未生效** | `.bashrc:123-126` 已 `export JAVA_HOME=/opt/jdk-8u351`、`SPARK_HOME=/opt/spark-3.3.2-bin-hadoop3`，但 `command -v spark-submit` = `MISSING`、`java -version` = OpenJDK **11**（非 `/opt` 的 JDK8） | 因为 `.bashrc` 顶部有 `case $- in *i*) ;; *) return;; esac` — **非交互 shell 不加载**。任何脚本化/`wsl -d Ubuntu -- cmd` 路径都拿不到这些变量 → 后续落地必须显式设置或改用 `~/.profile`/systemd unit |
| **JDK 版本双轨** | WSL 内 `java` = OpenJDK **11.0.32**（apt，`/usr/lib/jvm/java-11-openjdk-amd64`）；`/opt/jdk-8u351` = **1.8.0_351**。**无 JDK17** | 宿主 `JAVA_HOME=D:\Develop\JAVA17`(17.0.12)。Hadoop 3.3.4 需 Java 8/11（**可用**）；但项目 `analytics-server` 本体编译在宿主用 JDK17，**WSL 内跑 Spark/Hadoop 需 Java 8 或 11** → 双 JDK 并存策略须用户拍板（**Q4**） |
| **Spark 版本落差** | WSL 内 `/opt/spark-3.3.2-bin-hadoop3`（**built for Hadoop 3.3.2**）；宿主 `D:\Develop\spark-3.5.1-bin-hadoop3`（built for Hadoop 3.3.4） | WSL 内现有 Spark 3.3.2 与计划用的 Hadoop 3.3.4 存在 minor 落差；且 Spark 3.3.2 只能配 Java 8/11（不能 Java 17）。**是否复用 3.3.2 还是装 3.5.1 需用户决定** |
| **`spark-dl` 空包** | `$HOME/spark-dl/spark-3.3.2-bin-hadoop3.2.tgz` = **0 字节** | 上次下载失败残留；但 `/opt/spark-3.3.2-bin-hadoop3` 目录**内容完整**（含 `bin/spark-submit` 可执行、`RELEASE` 文件）——说明发行版内已有一份可用 Spark 3.3.2 |

## 3. A3 — WSL 内已装软件清单（逐项）

| 项目 | 命令原文 | 原始输出摘要 | 结论 | 风险 |
|---|---|---|---|---|
| dpkg 已装包总数 | `apt list --installed \| wc -l` | `677` | **已确定** | — |
| OpenJDK 11 | `dpkg -l \| grep openjdk` / `java -version` / `ls -l /etc/alternatives/java` | `openjdk-11-jdk:amd64 11.0.32+9-1ubuntu1~26.04`；`openjdk version "11.0.32" 2026-07-21`；`/etc/alternatives/java -> /usr/lib/jvm/java-11-openjdk-amd64/bin/java` | **已确定** | 版本满足 Hadoop 3.3.4（Java 8/11） |
| JDK 8（本地包） | `/opt/jdk-8u351/bin/java -version` | `java version "1.8.0_351"` | **已确定** | 路径 `/opt/jdk-8u351` |
| Maven | `dpkg -l \| grep maven` / `command -v mvn` | `maven 3.9.12-1`；`/usr/bin/mvn` | **已确定** | WSL 版 3.9.12 vs 宿主 3.9.14（轻微落差） |
| MySQL 客户端 | `mysql --version` | `mysql Ver 8.4.10-0ubuntu0.26.04.1 for Linux on x86_64 ((Ubuntu))` | **已确定**：**只有 client**（`mysql-client`, `mysql-client-core`, `mysql-common`） | 客户端 8.4 连宿主 8.0.41 服务端**默认 `caching_sha2_password` 可通**，但 8.4 客户端已移除 `mysql_native_password` 支持 → 若宿主账号用旧插件会握手失败（**中**） |
| MySQL 服务端 | `dpkg -l \| grep -E 'mysql-server\|mariadb-server'` / `systemctl is-active mysql mariadb` | `NO_MYSQL_SERVER_NO_PG_SERVER`；`inactive` `inactive` | **已确定：WSL 内无 MySQL 服务端** | 见 A7 拓扑 |
| Python | `python3 --version` / `dpkg -l \| grep python3.14` | `Python 3.14.4`；`python3 3.14.3-0ubuntu2`、`python3.14 3.14.4-1ubuntu0.1` | **已确定** | — |
| **pip3** | `command -v pip3` | `MISSING` | **已确定：未装 pip** | **中**：PySpark 要用需先装 python3-pip（属安装动作，未做） |
| Spark（apt） | `command -v spark-submit` | `MISSING`（apt 无 spark 包） | **已确定** | — |
| Spark（本地包） | `head -3 /opt/spark-3.3.2-bin-hadoop3/RELEASE` / `ls -l .../bin/spark-submit` | `Spark 3.3.2 (git revision 5103e00c4c) built for Hadoop 3.3.2`；`-rwxr-xr-x ... spark-submit` | **已确定：WSL 内已有一份可用 Spark 3.3.2** | 与宿主 3.5.1 并存，见落差表 |
| Hadoop | `command -v hadoop` | `/mnt/d/soft/hadoop/hadoop-3.3.4/bin/hadoop`（**经 PATH 继承指向 Windows 安装**），`find` 显示 **WSL 内无 hadoop 目录** | **已确定：WSL 内未装 Hadoop**；所见 `hadoop` 是宿主 PATH 透传 | **高**：跨 `/mnt/d` 调用宿主 Hadoop 脚本在 WSL 内**不可用/半坏**（脚本为 `.cmd` 生态、native lib 为 Windows DLL），是伪可用陷阱 |
| Hive | `command -v hive` / `beeline` / `find ... -iname '*hive*'` | 三者均 `MISSING` / 无匹配 | **已确定：WSL 内未装 Hive（含 beeline）** | 需全新安装（含 Metastore schema 初始化） |
| Node/npm/pnpm | `command -v node npm pnpm` | `node` MISSING；`npm`=`/mnt/c/Program Files/nodejs/npm`；`pnpm`=`/mnt/c/Users/ASUS/AppData/Roaming/npm/pnpm` | **已确定：WSL 内未装 node**；npm/pnpm 是宿主透传 | 前端不在本泳道范围（若需，须用户决定装 WSL 原生 node） |
| Scala | `command -v scala` | `/mnt/d/Develop/scala/bin/scala`（宿主透传） | **已确定：WSL 内未装 Scala** | Spark 发行版自带 scala-library，一般无需单独装 |
| git | `git --version`（WSL） | 存在 `/usr/bin/git` | **已确定** | — |
| sshd | `ss -lntp` | `0.0.0.0:22` / `[::]:22` LISTEN | **已确定：WSL 内 sshd 在 22 端口监听** | **中**：与宿主无冲突（宿主 22 未监听），但新增一个外部可达面 |
| sudo 能力 | `sudo -n true && echo SUDO_NOPASSWD_OK` | `SUDO_NOPASSWD_OK` | **已确定：`asus` 在 `sudo` 组且 root 免密** | **中**：技术上门槛为 0，但**是否允许 sudo 执行安装属用户授权**（**Q1**） |
| apt 可用性 | `apt-get install --dry-run --no-download -y openjdk-11-jdk` | 仅模拟提示 `This is only a simulation!`；`openjdk-11-jdk is already the newest version`；`0 upgraded, 0 newly installed`；`33 not upgraded` | **已确定：apt 依赖树可解析，apt 可用** | 有 33 个待升级包（**未动**） |
| apt 源 | `apt-cache policy` | `resolute-security`、`resolute-updates`、`resolute-backports`（`archive.ubuntu.com` + `security.ubuntu.com`） | **已确定** | 源为 26.04 官方源 |
| cgroup 版本 | `cat /sys/fs/cgroup/memory.max` | `cgroup v1` | **已确定：cgroup v1** | 低（WSL2 默认） |
| 运行时长 | `uptime` | `12:08:19 up 0 min, 1 user, load average: 0.11` | **已确定**：本次盘点刚启动该发行版 | — |

## 4. A4 — 宿主系统

| 项目 | 命令原文 | 原始输出摘要 | 结论 | 风险 |
|---|---|---|---|---|
| OS | `Get-CimInstance Win32_OperatingSystem` | `Microsoft Windows 11 家庭版 中文版`；`10.0.26100`；Build `26100`；64 位 | **已确定** | — |
| CPU | `Get-CimInstance Win32_Processor` | `Intel(R) Core(TM) i9-14900HX`；**24 核 / 32 逻辑处理器**；2200 MHz | **已确定** | 15th/14th gen 混合架构（P-core/E-core），Spark 本地模式线程调度可能非均质（低） |
| 内存 | `Win32_ComputerSystem.TotalPhysicalMemory` / `Win32_OperatingSystem` | 总 **31.63 GB**；当前**可用仅 3.56 GB** | **已确定** | **高**：盘点当时宿主仅余 3.56 GB 可用（VS Code/IDEA/DataGrip/Docker/OneDrive/ROG 全家桶在跑）。若 WSL 同时占 15.8 GB，会挤压宿主 |
| 机型 | `Win32_ComputerSystem` | `ASUSTeK COMPUTER INC.` / `ASUS TUF Gaming F16 FX607JIR_FX607JIR` | **已确定** | 笔记本，非 7×24 服务器；断电/休眠会中断集群（**中**） |
| Hypervisor | `Win32_ComputerSystem.HypervisorPresent` | `True` | **已确定** | WSL2 依赖此 |
| 卷可用空间 | `Get-Volume` | `C` OS NTFS 337.30 GB / **余 74.80 GB**；`D` 新加卷 NTFS 587.00 GB / **余 434.90 GB**；`E` 新加卷 NTFS 1863.00 GB / **余 353.10 GB** | **已确定** | **中**：C 盘仅余 74.8 GB。**WSL 根盘在 D（安全）**；但 `/mnt/c` 若被误用会迅速告急 |
| 磁盘硬件 | `Get-Disk` | Disk0 `WD PC SN560 SDDPNQE-1T00-1102` 954 GB（C+D+E）；Disk1 `WD Blue SN5000 2TB` 1863 GB（E 所在） | **已确定** | 均为 NVMe |
| PSDrive | `Get-PSDrive -PSProvider FileSystem` | C/D/E 三卷 | **已确定** | — |
| Docker | `Get-Process 'com.docker*','Docker Desktop'` | **无输出（未运行）** | **已确定：Docker Desktop 当前未运行** | 低；但 `docker-desktop` 发行版已注册，可能被拉起 |

## 5. A5 — 宿主工具链（逐项绝对路径＋版本）

| 工具 | 期望路径（资料） | 实测路径存在 | 命令原文 | 原始输出 / 版本 | 结论 | 风险 |
|---|---|---|---|---|---|---|
| Java 17 | `D:\Develop\JAVA17` | ✅ True | `D:\Develop\JAVA17\bin\java.exe -version` | `java version "17.0.12" 2024-07-16 LTS`（build 17.0.12+8-LTS-286） | **已确定** | — |
| Java 8 | `D:\Develop\JDK1.8` | ✅ True | `D:\Develop\JDK1.8\bin\java.exe -version` | `java version "1.8.0_202"`（build 1.8.0_202-b08） | **已确定** | — |
| Maven | `D:\apache-maven-3.9.14` | ✅ True | `D:\apache-maven-3.9.14\bin\mvn.cmd -v` | `Apache Maven 3.9.14`；Maven home `D:\apache-maven-3.9.14`；Java `17.0.12` runtime `D:\Develop\JAVA17`；**platform encoding GBK** | **已确定** | **中**：`platform encoding: GBK` — 与 WSL/UTF-8 环境交互时中文/编码问题高发 |
| Maven 本地仓库 | `D:\maven_repository` | ✅ True | `Test-Path` | True | **已确定：存在** | 未取证：未统计 jar 数量/体积（非决策必需） |
| MySQL 8.0 | `C:\Program Files\MySQL\MySQL Server 8.0` | ✅ True | `...\bin\mysql.exe --version` | `Ver 8.0.41 for Win64 on x86_64 (MySQL Community Server - GPL)` | **已确定** | 见 A6 |
| Spark | `D:\Develop\spark-3.5.1-bin-hadoop3` | ✅ True | `Get-Content ...\RELEASE` | `Spark 3.5.1 (git revision fd86f85e181) built for Hadoop 3.3.4`；`-Phive -Phive-thriftserver` | **已确定**；`bin`/`conf`/`jars`/`sbin`/`python` 均在 | **中**：`Phive` 已带，但 `SPARK_HOME` **未设**（见下） |
| Hadoop | `D:\soft\hadoop\hadoop-3.3.4` | ✅ True | `Get-ChildItem ...\share\hadoop\common -Filter hadoop-common-*.jar` | `hadoop-common-3.3.4.jar`、`hadoop-common-3.3.4-tests.jar`；`etc/hadoop` 含 `core-site.xml`(774B)、`hdfs-site.xml`(775B)、`yarn-site.xml`、`mapred-site.xml`、`workers`(10B) | **已确定**：Hadoop 3.3.4 已装，配置文件存在 | **中**：`core-site.xml`/`hdfs-site.xml` 仅 774/775 B，几乎肯定是**未配置的模板态**；未取证（未读内容——读它属只读，但配置内容属 R1 范围，本任务不做判断） |
| Python | `D:\Develop\Python314` | ✅ True | `D:\Develop\Python314\python.exe --version` | `Python 3.14.5` | **已确定** | **中**：Python 3.14 对 PySpark 生态偏新，多数三方轮子未有 3.14 wheel |
| Node | — | — | `node --version` | `v24.16.0` | **已确定** | — |
| npm | — | — | `npm --version` | `11.13.0` | **已确定** | — |
| pnpm | — | — | `pnpm --version` | `11.21.0` | **已确定** | — |
| Git | — | — | `git --version` | `git version 2.53.0.windows.2` | **已确定** | — |
| Scala | `D:\Develop\scala` | ✅ True | `Test-Path`（`SCALA_HOME`=`D:\Develop\scala`） | True | **已确定** | 未取证：未跑 `scala -version` |
| Tomcat | — | — | PATH 含 `D:\apache-tomcat-10.1.53\bin` | — | **已确定：PATH 中存在（旁证）** | 未取证：未访问该路径 |
| Hive | — | — | `Test-Path D:\soft\hive` / `D:\Develop\hive` / `D:\apache-hive-3.1.3-bin` | **全部 False** | **已确定：宿主未装 Hive** | 但仓库根有 `derby-metastore/`、`metastore_db/`、`derby.log` 残留（见 A8） |

### 环境变量（原文）

| 变量 | Machine | User | 结论 |
|---|---|---|---|
| `JAVA_HOME` | `D:\Develop\JAVA17` | 空 | **已确定**：JDK17 |
| `HADOOP_HOME` | `D:\soft\hadoop\hadoop-3.3.4` | 空 | **已确定** |
| `MAVEN_HOME` | `D:\apache-maven-3.9.14` | 空 | **已确定** |
| `SCALA_HOME` | `D:\Develop\scala` | 空 | **已确定**（额外发现） |
| **`SPARK_HOME`** | **空** | **空** | **已确定：未设置** — 与「资料显示 Spark 已装」不符 |
| `HIVE_HOME` | 空 | 空 | **已确定：未设置** |
| `M2_HOME` / `M2_REPO` | 空 | 空 | **已确定**：本地仓库路径仅由 Maven `settings.xml` 隐含决定（未取证） |
| `PYTHON_HOME` / `NODE_HOME` | 空 | 空 | **已确定：未设置** |
| `WSLENV` | 空 | 空 | **已确定：未设置**（无变量跨 WSL/Windows 桥接） |

**Machine `PATH` 中相关条目（原文顺序）**：`D:\Develop\JAVA17\bin`；`D:\apache-maven-3.9.14\bin`；`C:\Program Files\MySQL\MySQL Server 8.0\bin`；`D:\soft\hadoop\hadoop-3.3.4\bin`；`D:\soft\hadoop\hadoop-3.3.4\sbin`；`D:\Develop\scala\bin`（重复 2 次）；`C:\Program Files\Git\cmd`；`C:\Program Files\nodejs\`（另有 User PATH 的 npm 全局目录）。

> **注意**：`D:\Develop\spark-3.5.1-bin-hadoop3\bin` **不在 PATH 中**，且 `SPARK_HOME` 未设 → 宿主 Spark 只能靠绝对路径调用。

## 6. A6 — 宿主端口与进程

| 项目 | 命令原文 | 原始输出摘要 | 结论 | 风险 |
|---|---|---|---|---|
| 监听端口全表 | `Get-NetTCPConnection -State Listen`（按端口去重） | **50** 个唯一监听端口（12:06 快照，原文见 `raw/03-host-ports-services.txt`）：135、139、445、902、912、1040、1042、1053、1832、**3000(node)**、3248、**3306(mysqld)**、5040、5283、5357、6079、6301(java)、6817(java)、6850、7680、7778、7890、9010、9012、9013、9014、9180、9602、13030、13031、13032、22112、24830、27339、28317、**33060(mysqld)**、37977(java)、42050、45654、47890、49664、49665、49666、49669、49670、49671、50100、50923、51100、63342、63343 | **已确定**（时点性事实，见下方注） | 见下 |
| 端口集合的时点漂移 | 12:06 快照 → 12:12 复核 | 集合内容**完全一致**（50 项逐项相同）；`Sort-Object -Unique` 计数由 50 变为 51 系 12:12 复核时 `LocalPort` 去重后按 `LocalAddress` 展开的瞬态差异（宿主应用在动态起停监听），**未出现任何新增的 HDFS/Hive/Spark 相关端口** | **已确定：与本次决策相关的端口结论不受影响** | 低：宿主是活跃开发机，端口快照天然有时点性 |
| **8090/8091/8092** | `Get-NetTCPConnection -LocalPort <p> -State Listen` | `PORT 8090 : no listener` / `8091 : no listener` / `8092 : no listener` | **已确定：三者均无监听**（与任务描述一致） | 低 |
| HDFS/Hive/Spark 端口 | 同上，对 `9870,9864,9083,10000,10002,8020,9000,7077,4040,8088,8042,5432` | **全部 `no listener`** | **已确定：无冲突** | **低** — 单节点方案无端口冲突 ✅ |
| 3306 | 同上 | `PORT 3306 : LISTENING -> PID 9460 mysqld` | **已确定** | **中**：若将来在 WSL 内起 MySQL 服务端将冲突（方案用的是宿主 MySQL，故不冲突，但需明确「不装 WSL MySQL」） |
| 33060 | 全表 | `:: 33060 mysqld`（MySQL X Protocol） | **已确定** | 低 |
| 3000 | 全表 | `127.0.0.1 3000 node` PID 55100 | **已确定** | **中**：DSH Web GUI 占用 3000（本会话自身） |
| MySQL 服务 | `Get-Service -Name '*mysql*'` | `MySQL80  Running  Automatic` | **已确定：宿主 MySQL 作为 Windows 服务自启** | 低 |
| java 进程 | `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` | PID 49736 = `D:\Develop\JDK1.8\bin\java.exe`（IDEA Scala nailgun runner）；PID 55804 = DataGrip JBR；PID 68088 = `D:\Develop\JAVA17\bin\java.exe`（Maven） | **已确定：无项目服务类 java 进程** | 低 |
| Docker | `Get-Process 'com.docker*'` | 无输出 | **已确定：未运行** | 低 |
| vmware-authd | 全表 | `0.0.0.0 902 / 912 vmware-authd` | **已确定** | 与 WSL2 的 Hyper-V 后端可能互斥（低，当前共存） |

## 7. A7 — MySQL 实例拓扑（只读）

**命令原文**（仅 `SELECT`/`SHOW`；未做任何写操作）：

```
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p123456 --batch --raw --default-character-set=utf8mb4 -e "SELECT @@version, @@datadir, @@port, @@socket;"
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p123456 --batch --raw --default-character-set=utf8mb4 -e "SHOW DATABASES;"
```

| 项目 | 原始输出摘要 | 结论 | 风险 |
|---|---|---|---|
| 版本 | `8.0.41` | **已确定** | — |
| **datadir** | `C:\ProgramData\MySQL\MySQL Server 8.0\Data\` | **已确定：数据目录在 C 盘** | **高**：C 盘仅余 **74.80 GB**，而 datadir 已在 C。`mall_simulator` 单库 650.94 MB；若在 WSL 链路上新增数据/临时表，C 盘是最先触顶的资源。**是否迁移数据目录属用户决定（Q8）** |
| 端口 / socket | `port 3306` / `socket "MySQL"`（Windows 命名管道名） | **已确定** | 见 Q9（连接方式） |
| 客户端警告 | `mysql: [Warning] Using a password on the command line interface can be insecure.` | **已确定**（预期内） | 低 |
| 库总数 | `SHOW DATABASES` = **42 个**（含 `information_schema`、`mysql`、`performance_schema`、`sys` 4 个系统库 → **38 个非系统库**） | **已确定** | — |
| 非系统库清单 | `analytics_meta`、`analytics_meta_p103`、`analytics_meta_p105`、`analytics_meta_p105it`、`analytics_meta_v17probe`、`analytics_metric`、`analytics_metric_p103`、`analytics_metric_p105`、`analytics_verify_m3_parity`、`blog_system`、`company`、`crm`、`emp`、`generator_meta`、`itcast`、`library`、`mall_business`、`mall_simulator`、`mall_simulator_test`、`mybatis`、`mybatis_db`、`mybatis_library`、`mydb`、`regexp_test`、`sakila`、`school`、`school1`、`school2`、`scott`、`shop_db`、`shopping`、`spark`、`springboot_demo`、`teaching_db`、`teachingdb`、`test-0628`、`test260720`、`test_db`、`world` | **已确定：仅列名，不作判断** | — |
| 规模（`information_schema.tables` 聚合） | 总量 **698.09 MB**。前 6：`mall_simulator` 30 表 / 834,550 行 / **650.94 MB**；`analytics_meta` 22 表 / 1,928 行 / 7.14 MB；`sakila` 23 表 / 46,416 行 / 6.45 MB；`analytics_meta_p103` / `analytics_meta_p105it` / `analytics_meta_p105` 各 22 表 / ~1,640 行 / ~6.03 MB；`analytics_meta_v17probe` 22 表 / 1,657 行 / 6.03 MB；`generator_meta` 6 表 / 2,460 行 / 1.11 MB；`mall_simulator_test` 30 表 / 170 行 / 0.86 MB | **已确定：仅列名与行数，不做判断性结论** | — |
| 命名模式提示（事实陈述，非结论） | 9 个库名带项目前缀/实验后缀：`analytics_meta`、`analytics_meta_p103`、`analytics_meta_p105`、`analytics_meta_p105it`、`analytics_meta_v17probe`、`analytics_metric`、`analytics_metric_p103`、`analytics_metric_p105`、`analytics_verify_m3_parity`。另有 `test-0628`、`test260720`、`test_db`、`mall_simulator_test` 4 个测试命名库 | **仅记录事实**。**哪些属隔离/测试遗留、可否清理，需用户裁决（Q10）** —— 本任务不做删除、不做判断 | **高**：任何 drop 都在本任务边界外 |
| **WSL→宿主 3306** | WSL 内 `/dev/tcp/127.0.0.1/3306` → `OPEN/CONNECTABLE`；WSL 内 `systemctl is-active mysql` → `inactive` | **已确定：mirrored 网络下 WSL 可直达宿主 MySQL** | **低→中**：连通性已证；但**认证是否可用未取证**（未用 root/123456 从 WSL 登录 —— 属连接行为，本任务未做）。需 R1 阶段验证 |

## 8. A8 — 补充发现（超出清单但影响决策）

| 发现 | 证据 | 结论 | 风险 |
|---|---|---|---|
| 仓库根已存在 Hive/Derby 残留 | `ls /mnt/d/Develop_code/GraduationProject` 输出含 `derby-metastore`、`metastore_db`、`derby.log` | **已确定：存在**（本任务未读取其内容） | **中**：说明此前有 Hive Metastore（Derby 后端）尝试；内容与可用性**未取证**，属 R1 范围 |
| `~/.bashrc` 有 3 份备份 | `.bashrc.bak.1787751187`、`.bashrc.bak.migrate`、`.bashrc.bak2.1787751228` | **已确定** | 低：近期有环境改造历史 |
| Ubuntu 22.04 包目录残留 | `C:\Users\ASUS\AppData\Local\Packages\CanonicalGroupLimited.Ubuntu22.04LTS_79rhkp1fndgsc` 存在，但 Lxss BasePath 指向 `D:\Develop\wsl\Ubuntu` | **已确定：该包目录非当前发行版根盘** | 低：可从 C 盘回收空间（**未动**） |
| git 工作区有未提交改动 | `git status --porcelain` | M: `analytics-server/**` 10 个文件、`docs/README.md`；??: `after-reboot-cleanup.ps1`、`alibaba-fix*.{ps1,txt}`、`docs/acceptance/b13-db-forensics-20260912/raw/14-binlog-coverage-map.txt`、`docs/acceptance/f88-dq-severity-20260912/` 等 | **已确定** | **中**：开工前工作区非干净态；本任务**未做任何 git 操作** |

---

## 9. 未取证项（显式声明）

| # | 未取证项 | 原因 | 如何补齐 |
|---|---|---|---|
| U1 | 从 WSL 用 `root/123456` 登录宿主 MySQL 是否成功 | 该操作涉及凭据传输，超出「只读查询」严格边界，未执行 | R1 阶段执行一条 `SELECT 1` |
| U2 | `D:\maven_repository` 的 jar 数量/体积 | 非本次决策必需 | `Get-ChildItem -Recurse \| Measure-Object` |
| U3 | 宿主 `Hadoop etc/hadoop/{core,hdfs,yarn,mapred}-site.xml` 的实际配置内容 | 属配置审计（R1 范围），本任务不越界 | R1 读取 |
| U4 | `derby-metastore/`、`metastore_db/`、`derby.log` 的内容与完整性 | 同上 | R1 读取 |
| U5 | `scala -version`（宿主 `D:\Develop\scala`） | 与 HDFS/HMS/Spark 单节点链路无直接关系 | 可选 |
| U6 | `D:\apache-tomcat-10.1.53` 是否存在及版本 | 仅从 PATH 旁证 | `Test-Path` |
| U7 | 宿主 MySQL 的 `my.ini` 关键参数（`innodb_buffer_pool_size`、`max_connections`） | 属配置审计范围 | R1 读取 |
| U8 | Docker Desktop 是否会在下次启动时抢占 WSL 资源/端口 | 需实际启动才知道（**启动 Docker 属状态变更，禁止**） | 用户决策（Q11） |
| U9 | Ubuntu 26.04 上 Hadoop 3.3.4 native library 是否可加载 | 需实际运行 HDFS（**属启动服务，禁止**） | R1 阶段实测 |
| U10 | `C:\Users\ASUS\AppData\Local\Packages\CanonicalGroupLimited.Ubuntu22.04LTS_*` 占用空间 | 非决策必需 | `Get-ChildItem -Recurse \| Measure-Object Length -Sum` |

---

## 10. 待用户确认问题清单（本任务不代为决定）

| 编号 | 问题 | 为什么必须由用户决定 |
|---|---|---|
| **Q1** | 是否允许在 WSL `Ubuntu` 内**联网安装**运行时（Hadoop 3.3.4 / Hive 3.1.3 / Spark 3.5.1 / openjdk-8）？apt 与 `dlcdn.apache.org` 均可达（HTTP 200），`asus` 为 **root 免密 sudo**，技术上无障碍。 | 这是**引入新软件与网络访问**的授权决定，涉及供应链与磁盘占用，且不可逆（会改变发行版状态）。盘点只能证明「可行」，不能替代授权。 |
| **Q2** | 是否允许在 WSL 内**新建项目目录**（如 `/opt/hadoop`、`/opt/hive`、`/data/hdfs`、`~/v25`）？当前 `/opt` 已由 `asus:asus 755` 持有两个目录。 | 目录布局是**环境架构决策**，会决定后续所有路径、权限与备份策略。 |
| **Q3** | WSL 内存/CPU 上限是否显式设定？当前 `.wslconfig` **未设**，WSL 取到 **15.8 GiB / 32 线程**；盘点时宿主**仅余 3.56 GB 可用**。若要显式设限需改 `.wslconfig`。 | 改 `.wslconfig` 是**改配置 + 需重启 WSL**；且 15.8 GB 是否够跑 HDFS+HMS+Spark 而宿主不饿死，取决于用户对宿主并行负载（IDEA/DataGrip/Docker/浏览器）的取舍。 |
| **Q4** | WSL 内用哪个 JDK 跑 Hadoop/Hive/Spark？选项：(a) 沿用 `/opt/jdk-8u351`（1.8.0_351）；(b) apt **OpenJDK 11.0.32**（已装，Spark 3.3/3.5 均支持）；(c) 另装 JDK17。**注意宿主 `JAVA_HOME=D:\Develop\JAVA17`，项目本体编译用 17。** | Hadoop 3.3.4 官方支持 8/11（**不支持 17 运行**），而 Spark 3.5.1 需 8/11/17。JDK 选型决定了整个单节点栈的版本组合，且与宿主 17 形成**双轨**。这是架构决策。 |
| **Q5** | **源码位置策略**：(a) 仅宿主 `D:\Develop_code\GraduationProject`，WSL 经 `/mnt/d/...` 访问（当前状态，`WRITABLE_BY_ASUS`）；(b) 在 WSL 内**放第二份**（`ext4` 内，性能好）；(c) 迁进 WSL、宿主只留一份。 | 决定**单一事实源**位置。`/mnt/d`（9P/Plan9 协议）跑 HDFS/Spark IO 有明确性能与权限语义风险；双份会引入漂移风险。属架构决策，且影响后续所有脚本路径。 |
| **Q6** | 是否允许 `wsl --shutdown`（或 `--terminate`）后再重启发行版？本次盘点为取证**启动了 `Ubuntu`（原为 Stopped），当前为 Running**。 | 关闭会**终止 WSL 内一切运行态**；且团队此前明确实测「8090/8091/8092 保持停」，说明对状态变更极敏感。是否允许、何时允许，只应由用户定。 |
| **Q7** | 是否允许为本地链启用 **systemd 服务单元**？现状：`/etc/wsl.conf` 已 `[boot] systemd=true` 且 systemd **running**（14 个 unit 活跃）。 | systemd 已可用，但**是否把 HDFS/HMS/Spark 做成开机自启 unit** 是运维决策：会带来「WSL 一启动就吃 15 GB 内存」的行为。 |
| **Q8** | 宿主 MySQL `datadir` 在 **C 盘**（`C:\ProgramData\MySQL\MySQL Server 8.0\Data\`），C 盘**仅余 74.80 GB**。是否允许/需要迁移 datadir 到 D 盘？ | 迁移 datadir 是**高危不可逆运维操作**（需停服、拷数据、改 `my.ini`）；且是否值得做取决于用户对 C 盘压力的判断。**本任务绝不动。** |
| **Q9** | 本地链路连宿主 MySQL 的方式：(a) WSL 经 mirrored 网络连 `127.0.0.1:3306`（连通性已证）；(b) 用宿主 `mysql.exe` 走 Windows 命名管道 `socket "MySQL"`；(c) 其他。 | 影响所有 JDBC/CLI 连接串与认证插件选择（WSL 内是 **8.4.10 客户端**，宿主是 **8.0.41 服务端**，`mysql_native_password` 已被 8.4 客户端移除）。属集成契约决策。 |
| **Q10** | 宿主 MySQL 的 **38 个非系统库**中，哪些属隔离/测试遗留、可否清理？事实记录：9 个 `analytics_*` 前缀库中 `_p103/_p105/_p105it/_v17probe` 为明显实验后缀，另有 `test-0628`、`test260720`、`test_db`、`mall_simulator_test`。总计 698.09 MB。 | **任何 drop 都在本任务边界外，且不可逆。** 判断「哪个库是遗留」需要项目历史知识，只有用户/项目负责人具备。**本任务只列名与行数。** |
| **Q11** | `docker-desktop` 发行版已注册（当前 Stopped，Docker 未运行）。是否允许在集成链路期间启动 Docker？ | 会额外争抢内存与端口（Docker 自身的 2375/2376、以及 `docker-desktop` 的 WSL 内存开销），在宿主仅余 3.56 GB 时可致命。 |
| **Q12** | WSL 内 **sshd 已在 22 端口监听**（systemd 拉起）。是否需要关闭？ | 新增了一个外部可达面。是否接受取决于用户的安全偏好；关闭属状态变更，**本任务未动**。 |

---

## 11. 与本任务结论直接相关的落地风险（供 R1 参考，非本任务行动）

| 风险 | 依据 | 缓解方向（待用户/上级决策） |
|---|---|---|
| R-1 **Ubuntu 26.04 太新**，Hadoop 3.3.4 / Hive 3.1.3 官方未验证 | A2 `lsb_release` | 优先用发行版内已存在的 `/opt/spark-3.3.2` + Java 8/11 组合先跑通；或评估退回 Ubuntu 22.04/24.04（属重大决策，需用户定） |
| R-2 **内存天花板 15.8 GB，宿主余 3.56 GB** | A2 `free -m`、A4 | 显式设 `.wslconfig` memory（Q3）；调小 HDFS/HMS/Spark 堆 |
| R-3 **C 盘仅余 74.8 GB 且 MySQL datadir 在 C** | A4、A7 | Q8；HDFS datadir **务必放 WSL ext4（953 GB 可用）而非 `/mnt/c` 或 `/mnt/d`** |
| R-4 **跨 `/mnt/d` 视为同构环境** | A3 `command -v hadoop` 指向 `/mnt/d/...` | WSL 内的 Hadoop 必须**原生安装到 ext4**；不可复用宿主 `D:\soft\hadoop` |
| R-5 **`.bashrc` 非交互不加载** | A2 落差表 | 落地脚本显式 `export`，或迁 `~/.profile`，或用 systemd unit |
| R-6 **双 Spark、双 JDK 版本分裂** | A2 落差表、A5 | Q4 定 JDK；同步统一 Spark 版本 |
| R-7 **无 `SPARK_HOME`（宿主）** | A5 环境变量 | 需显式设置或用绝对路径 |
| R-8 **无 pip3（WSL）** | A3 | 若用 PySpark 需先装 `python3-pip`（属 Q1 授权范围） |
| R-9 **本任务启动了原为 Stopped 的 Ubuntu** | 本文件开头声明 | 需用户决定是否 `wsl --shutdown` 复位（Q6） |
| R-10 **工作区非干净态** | A8 git status | 后续改动前先确认这 10 M + 若干 ?? 的归属 |

---

## 12. raw/ 原始输出文件索引

| 文件 | 覆盖内容 |
|---|---|
| `raw/00-wsl-host-side-inventory.txt` | `wsl -l -v` / `--status` / `--version` / `-l -o`、`.wslconfig` 内容、Lxss 注册表 BasePath、`D:\Develop\wsl\Ubuntu` 目录 |
| `raw/01-host-system.txt` | Win32_OperatingSystem / Processor / ComputerSystem、`Get-Volume`、`Get-Disk`、`Get-PSDrive` |
| `raw/02-host-toolchain-versions.txt` | 各安装根 `Test-Path`、java17/java8/mvn/mysql/python/node/npm/pnpm/git 版本、Spark RELEASE、Hadoop jar、Hive 路径探测 |
| `raw/03-host-ports-services.txt` | 监听端口全表（48 项）、16 个目标端口逐项探测、MySQL 服务、java 进程命令行 |
| `raw/04-env-vars-and-path.txt` | 12 个环境变量的 Machine/User 值、进程 PATH 全量条目 |
| `raw/10-wsl-internal-inventory.txt` | A2 全部（lsb_release/uname/os-release/nproc/free/df/whoami/sudo 探测/工具链 command -v/java/python 版本/HOME/opt/usr-local/apt-policy/systemd） |
| `raw/11-wsl-packages-config-network.txt` | dpkg 已装包清单（java/spark/hadoop/hive/mysql/python/maven/scala/node 过滤）、alternatives 符号链接、HOME 子目录深查、find 结果、RELEASE、JDK8 版本、bashrc exports、mysqld 状态、ss、mysql client 版本、apt dry-run、apt 已装计数、出网探测、resolv.conf、DNS、lsblk、根盘 df、项目可见性、WSL_INTEROP |
| `raw/12-wsl-ports-systemd.conf.txt` | WSL 内 16 端口连通性探测、`/etc/wsl.conf`、systemd is-system-running + 活动 unit 列表、cgroup 版本、cpuinfo 计数、uptime、date、`/mnt/d` df、项目目录可写性、hive/mysql-server 复查、`/opt` 属主、`free -h` |
| `raw/20-mysql-topology.txt` | `@@version/@@datadir/@@port/@@socket`、`SHOW DATABASES`（42 项） |
| `raw/21-mysql-schema-sizes.txt` | 非系统库逐库 表数/近似行数/大小（MB）、总大小 |

---

## 13. 证据级别与纪律声明

- **本文件全部结论的证据级别 = E1（只读盘点）**。所有「已确定」项均有上表所列命令原文与原始输出支撑，原始输出完整存放于 `raw/`。
- 凡命令失败或不存在，均**如实记录原文**（如 `dpkg-query: no path found matching pattern /usr/bin/java`、`MISSING`、`NOT FOUND`、`inactive`、`no listener`），未做任何绕过尝试。
- **未取证项 10 项**已在第 9 节显式列出并说明原因（未安装／无权限／服务未运行／超出只读边界）。
- **待用户确认问题 12 项**已在第 10 节列出，均未代为决定。
- 本任务**未写任何实现代码、未改任何系统状态（除启动原为 Stopped 的 Ubuntu 发行版以取证）、未改看板与指导书**。
