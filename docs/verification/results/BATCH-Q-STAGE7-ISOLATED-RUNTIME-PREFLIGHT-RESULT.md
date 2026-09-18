# BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT — Execution Result

- Executor: Code Agent (Execution Tester)
- Command entry: `VERIFY_CURRENT_BATCH`（`docs/verification/CODE_AGENT_COMMANDS.md` @ `origin/feature/v3-development`）
- Protocol synced at: `origin/feature/v3-development` = `0bb09cf76bdcf70e75b959a132bca34409c93245`
  - `docs/verification/CODE_AGENT_COMMANDS.md`（自 `31fbbaf` 未变）
  - `docs/verification/TEST_EXECUTION_PROTOCOL.md`（自 `31fbbaf` 未变）
  - `docs/verification/CURRENT_BATCH.md`（状态 READY，本批定义）
  - 永久计划：`docs/verification/batches/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-PLAN.md`（已完整读取并按 §1–§10 执行）
- Batch ID: `BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT`
- RunId: `stage7q_20260918_1100`
- Exact code baseline / tested commit: `2f3e79f676e1b614fe9a57e71e7ecad68106a51f`
  - `git rev-parse HEAD` 精确匹配 = True
  - **注意**：`origin/feature/v3-development` 当时为 `0bb09cf76bdcf70e75b959a132bca34409c93245`（验证文档提交），本批**未测该 HEAD**，只测批次指定代码基线。
- Accepted predecessor: `BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS` @ `2f3e79f676e1b614fe9a57e71e7ecad68106a51f` / PASS（Web 307/307）
- Branch context: `feature/v3-development`（本地 detached 检出，未创建/移动任何分支）
- Git status before: clean（`git status --short` 空）
- Git status after: clean（`git status --short` 空）
- Workspace: `D:\Develop_code\GraduationProject-wt\v3-dev`（主检出未触碰；本批未修改任何 tracked 文件）

## Overall

```text
Overall: BLOCKED_ENV
```

阻塞类别（计划 §7 明列的第一类）：**WSL MySQL 3307 未监听、隔离实例尚未交付/安装**，因此无法完成隔离准备，按计划在统一 isolated 通道之前停止。

- 未执行 `scripts/run-tests.ps1 -Suite isolated`（计划 §5.3 前置条件「dry-run 正确 **且** 3307 可用」未满足；§7 要求 preparation 无法完成时「stop before the isolated lane」）。
- 因此本批**没有** mall / generator / analytics 计数，也**没有** runner 退出码——不是 0 tests 假绿，而是**未开跑**（下面逐项标注 N/A 及原因）。
- **绝无 3306 回退**：全程只以 `-Port 3307` 调用仓库脚本；对 3306 只做了一次保护性反证，脚本按设计拒绝（见下）。

## Commands executed（含退出码与观察事实）

```text
git fetch origin                                                exit=0    cc01ac0..0bb09cf  feature/v3-development
git checkout --detach 2f3e79f676e1b614fe9a57e71e7ecad68106a51f  exit=0
git rev-parse HEAD                                              exit=0    = 2f3e79f676e1b614fe9a57e71e7ecad68106a51f
git status --short                                              exit=0    空（clean）

# §5.2 Preparation dry-run
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId stage7q_20260918_1100 -Port 3307 -DryRun
                                                                exit=0    目标清单正确（见下）

# §5.3 Real isolation preparation
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId stage7q_20260918_1100 -Port 3307 -Confirm -AllowRootOnIsolated
                                                                exit=3    ← 本批阻塞点
  输出关键行：
    [admin] 未提供 V25IT_ADMIN_PWD：按 WSL 内 root 免密(auth_socket)尝试；失败则退出码 3。
    ERROR 2003 (HY000): Can't connect to MySQL server on '127.0.0.1:3307' (111)
    [2/2] ⚠️ 建库/授权失败（wsl mysql 退出码 1）。
          常见原因：W03 的 3307 实例未起、或 WSL 内 mysql 客户端不在 PATH。

# 3306 保护性反证（仅确认门禁有效，未建立任何连接）
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId stage7q_20260918_1100 -Port 3306 -Confirm -AllowRootOnIsolated
                                                                exit=2
  输出：
    拒绝：-Port 3306 不在允许的隔离实例端口清单 3307 内。
      3306 是宿主正式 MySQL 实例：它上面的任何库（含 *_test）都不是隔离环境。

# §5.5 未执行（按 §7 停止）
scripts/run-tests.ps1 -Suite isolated -RunId stage7q_20260918_1100 -Confirm     NOT RUN（N/A）

# §5.6 Final workspace check
git rev-parse HEAD                                              exit=0    = 2f3e79f676e1b614fe9a57e71e7ecad68106a51f（未变）
git status --short                                              exit=0    空（clean）
```

## Dry-run target summary（计划 §5.2 / PASS 判据 2）

```text
实例        : 127.0.0.1:3307（WSL 内独立 MySQL；宿主 3306 不参与）
runId       : stage7q_20260918_1100
将创建（幂等，IF NOT EXISTS）：
  数据库     : stage7q_20260918_1100_mall
  数据库     : stage7q_20260918_1100_generator
  受限账号   : stage7q_20260918_1100_mallapp@'%'（只对 stage7q_20260918_1100_mall 有权限）
  受限账号   : stage7q_20260918_1100_genapp@'%'（只对 stage7q_20260918_1100_generator 有权限）
将写入凭据 : <wt>\mall-simulator\credref-stage7q_20260918_1100-mall.properties
             <wt>\synthetic-data-generator\credref-stage7q_20260918_1100-generator.properties
```

- 目标清单与计划 §5.2 要求的四项对象**完全一致**，端口只出现 3307。
- **未出现** 3306、正式库（`mall`/`generator`/`*_test`/`metric_*`/`meta_*`）或正式账号（root / `metric_pub` / `mall_app` / `meta_app`）——dry-run 结论：**正确**。
- 脚本硬门只读核对（`scripts/it-prepare-isolation.ps1`）：`$AllowedPorts = @(3307)`；`-Port 3306` 显式拒绝；执行需 `-Confirm` **且** `-AllowRootOnIsolated` 双门；root 仅用于 3307 隔离对象创建。

## Environment facts（阻塞的客观证据）

```text
宿主 TCP:        127.0.0.1:3307 = 不监听（Test-NetConnection False）
                 127.0.0.1:3306 = 监听（Windows 服务 MySQL80 / Running；本批未连接、未使用）
WSL 发行版:      Ubuntu、docker-desktop（wsl --list --quiet）
WSL Ubuntu 内:   ss -ltn 无任何 3306/3307 监听
                 pgrep -a mysqld = 无进程
                 mysqld 不在 PATH，/usr/sbin/mysqld 亦不存在（未安装 server）
                 /var/lib/mysql 不存在（无数据目录）
                 systemd 单元 mysql.service 不存在
                 mysql 客户端存在：/usr/bin/mysql
仓库脚本自身诊断: "常见原因：W03 的 3307 实例未起、或 WSL 内 mysql 客户端不在 PATH。"
WSL 客户端:      /usr/bin/mysql 存在 ⇒ 阻塞原因不是缺客户端，而是 **3307 实例本身尚未交付/未安装**
```

结论：本机当前**不存在**可作为隔离目标的 WSL MySQL 3307 实例（既未安装 server，也无数据目录与服务单元）。这属于计划 §7 的纯外部运行时不可用，不是源码/测试缺陷。

## PASS criteria（计划 §6，逐条）

```text
1)  精确 SHA = 2f3e79f676e1b614fe9a57e71e7ecad68106a51f                            满足
2)  dry-run 仅列出 fresh runId 作用域的 3307 对象                                 满足
3)  真实准备仅经仓库脚本针对 3307 执行                                            满足（脚本已执行；因实例缺失而 fail-closed）
4)  统一 isolated runner 退出 0                                                   N/A（按 §7 未开跑）
5)  mall = 30/30                                                                 N/A（未开跑）
6)  generator = 19/19                                                            N/A（未开跑）
7)  analytics = 6/6                                                              N/A（未开跑）
8)  total = 55/55                                                                N/A（未开跑）
9)  各套件 failures/errors/skips = 0                                              N/A（未开跑）
10) analytics 日志证明 IsolationGuardMySqlIT 实际执行                             N/A（未开跑，无日志）
11) 隔离守卫实测事实证明 3307 与预期隔离实例身份                                  不满足（无 3307 实例可连；未产生任何实测 fingerprint）
12) 未使用任何正式库/账号目标                                                     满足
13) 源码工作区保持 clean                                                         满足
14) 验证期间未做任何修复/源码改动                                                 满足
```

→ 判据 11 因环境缺失无法成立，且判据 4–10 依计划 §7 不得在 preparation 失败后继续；故 **PASS 不可给出**，按 §7 记 `BLOCKED_ENV`。

## 门禁有效性反证（本批顺带取得）

```text
- 3306 请求被仓库脚本拒绝（exit=2），输出明确指认 3306 为宿主正式实例、其上 *_test 亦非隔离环境；
- 3307 未可达时脚本不降级、不改端口、不静默继续，直接以 exit=3 终止；
- 全程未对 3306 建立任何连接（Windows MySQL80 服务未被我方命令触碰）。
```

## Workspace / side effects

```text
执行前: HEAD = 2f3e79f…；git status --short 空
执行后: HEAD = 2f3e79f…（未变）；git status --short 空

副产物（计划 §5.6 明确允许，gitignored、不得作为 tracked 变更）：
  mall-simulator\credref-stage7q_20260918_1100-mall.properties                288 bytes
  synthetic-data-generator\credref-stage7q_20260918_1100-generator.properties 286 bytes
  → 由仓库脚本 `it-prepare-isolation.ps1` 在 [1/2] 阶段生成（口令不回显、未打印）；
  → 未出现在 git status（gitignore 覆盖），未提交；
  → 由于 3307 实例未交付、受限账号未创建，这两个文件当前不含可用凭据；
     实例修好后按脚本提示重跑（幂等）会重新生成并覆盖。
  → 未手工创建/删除任何数据库、账号或授权；未做历史 3307 清理。
```

## Why not FAIL

- 无源码/测试缺陷参与：本批未运行任何断言，未产生任何测试失败；
- 阻塞点完全位于外部运行时（WSL MySQL 3307 未安装/未启动）；
- 计划 §7 明确规定该情形记 `BLOCKED_ENV` 而非 FAIL，并禁止以放宽门禁方式绕过。

## Unblocking requirement（供总控决策，非本批执行项）

```text
需要 W03 交付物就位：在 WSL 内提供监听 127.0.0.1:3307 的独立 MySQL 实例
（含 server 安装、数据目录与服务/启动方式）。
就位后本批可原样重跑（RunId stage7q_20260918_1100 保留、幂等）：
  pwsh -File scripts/it-prepare-isolation.ps1 -RunId stage7q_20260918_1100 -Port 3307 -Confirm -AllowRootOnIsolated
  → 载入受限凭据后 pwsh -File scripts/run-tests.ps1 -Suite isolated -RunId stage7q_20260918_1100 -Confirm
```

## Next gate

- 计划 §10：真实 HTTP ingestion → pipeline 链的下一批**不得**在 Batch Q 未 PASS 时开启。
- 本批未运行该链，也未运行任何 3306 相关验证。

## Result files

```text
Local result path:    .verify/CURRENT_BATCH_RESULT.md
GitHub result branch: verification-results
GitHub result path:   docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md
GitHub result commit: <见文末 GitHub publish 段>
```
