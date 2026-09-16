# S3-51 登记件：门禁入口并发健壮性 —— 缺省 RunId 唯一化 ＋ 日志目录互斥

- 轮次：S3-51（阶段6 反熵 · 入口健壮性）
- 日期：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 开工前 HEAD：`6fe07563b6f5bf6be4db9328969579a9675e9234`（短 `6fe0756`，＝ `origin/feature/v3-development`，实测 `git rev-parse HEAD` / `git rev-parse --short origin/feature/v3-development`）
- 差异类别：**A 类（纯加性 / 门禁脚本-only）** —— 零生产代码改动、零 Java/Scala/前端改动、零契约改动、零 DDL、零迁移、零连库、零新依赖
- 来源：`docs/PROJECT_STATUS.md` backlog **L504**「`scripts/run-tests.ps1` 的日志目录由**分钟级 RunId** 派生 ⇒ 同一分钟内两个门禁并发会撞 `Tee-Object`（实测报 `The process cannot access the file … because it is being used by another process`，两次运行**都不产生测试证据**）」

---

## §1 开工前实测（改前事实，全部本轮实跑取得）

- **F1 旧缺省 RunId 派生式**（改前 `scripts/run-tests.ps1:762`）：`'dev003c_' + (Get-Date -Format 'yyyyMMdd_HHmm')`。本轮实测：同一分钟内两次求值**恒等**（`dev003c_20260916_2113` ＝ `dev003c_20260916_2113`）——**这是代码级求值事实，不是运行证据**；分钟粒度决定了同分钟派生值必然相同。
- **F2 旧日志目录派生式**（改前 `:766`）：`Join-Path $env:TEMP ("v25tests-{0}" -f $RunId)` ⇒ 同 RunId ⇒ **同一路径**；紧接着 `New-Item -ItemType Directory -Force`（改前 `:767`）**不拦**既有目录 ⇒ 改前**无任何占用检测**。
- **F3 证据写点三处**（改前）：①`Invoke-MavenRun` `:819` ＝ `& $MavenCmd @mvnArgs 2>&1 | Tee-Object -FilePath $log | Out-Host` ②`Invoke-IsolatedSuite` `:926` ＝ `Tee-Object -FilePath $isoConsole` ③`Invoke-SparkSuite` `:982` ＝ `Tee-Object -FilePath $jdkLog`。三处都写「`$LogDir` ＋ 固定文件名」⇒ 并发同 LogDir ⇒ 文件独占冲突 ⇒ **两轮都不落证据**（backlog 行记录的历史报错原文即此）。
- **F4 受影响面不止日志**：脚本自身在 RunId 形状校验失败文案里声明「库名/账号名/Spark 测试根目录都由它派生」（改前 `:764`）；`scripts/run-isolated-tests.ps1` 的 `-RunId` 为 **Mandatory** 且由本脚本透传（改前 `:922`）⇒ isolated 档并发同 RunId 不仅撞日志，还会**互踩同一批库与受限账号**。
- **F5 旧缺省口径的全仓引用面**（扫 `*.ps1/*.md/*.java/*.scala/*.sql/*.xml/*.py/*.js`，排除 `target|node_modules|.git|.verify`）：唯一**实现点**＝本脚本改前 `:762`；唯一**文档记录点**＝`docs/acceptance/dev003c-unified-test-entry-20260915/REPORT.md:19`（历史验收报告，按「不改写已发布历史」纪律**保持原样**）；本脚本头注释改前**未记录**缺省值（本轮补记）⇒ **无任何脚本/测试逻辑依赖旧缺省值**。
- **F6 廉价探针可行性**：`-Confirm` 只在 `isolated`/`all` 档被要求（改前 `:779–:789`）⇒ 可用 `-Suite all`（不给口令）在 **maven 之前**被拒，而 `$LogDir` 已派生 ⇒ 可用 `$env:TEMP` 目录增量**观测缺省 RunId**，无需跑全档。
- **F7 改前脚本规模**：**1075** 行（CRLF 1075 / 裸 LF 0）。
- **F8 改后缺省唯一化实测**：同一分钟内两次缺省 `-RunId` 调用 ⇒ 新增 **2** 个日志目录 `v25tests-dev003c_20260916_211326_41ab0c`、`v25tests-dev003c_20260916_211326_9ae8ca`（同秒时间戳 `211326`、随机后缀不同）；去前缀后长度 **30**，形状匹配 `^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$` ＝ True；两次调用均 `exit=5`（缺口令）且**未进入 maven**（无 `Tests run:` 行）。证据：`.verify/s351/s351-default-runid.txt`。
- **F9 互斥名可外部复算（自证）**：对 `$LogDir = C:\Users\ASUS\AppData\Local\Temp\v25tests-s351-lock` 独立复算（`ToLowerInvariant()` → UTF8 → SHA256 → 前 8 字节 hex）＝ `Local\v25tests-d1fc8d0db0c9cd2f`，与脚本控制台实际打印值**一致** ⇒ 派生式不是黑盒。
- **F10 改后脚本规模**：**1125** 行（CRLF 1125 / 裸 LF 0），`Parser::ParseFile` 语法错误 **0**。

---

## §2 11 项 HARD DECISION GATE 逐项判定

| 门 | 判定 | 依据 |
| --- | --- | --- |
| ① DROP TABLE/COLUMN | **否** | 零 DDL、零 SQL 改动 |
| ② 改已有字段类型或既有业务语义 | **否** | 未动任何字段类型与业务语义；改的是**门禁脚本的缺省值**与**新增互斥**，不涉及业务规则 |
| ③ 改已发布 Flyway migration | **否** | 未触碰 `V*.sql` |
| ④ 写/迁移正式 3306 数据 | **否** | 零连库、零写入；本轮只跑 default 档（单测替身），`isolated`/真库档**未跑** |
| ⑤ 切 ACTIVE | **否** | 未涉及 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **否** | `contract-specs/**` 零改动；`docs/contracts/**` 亦零改动 |
| ⑦ 改 V3.0 总体架构 | **否** | 未动架构、模块划分、技术选型 |
| ⑧ 改正式项目范围 | **否** | 处理的是已登记 backlog 入口健壮性项，未增删范围 |
| ⑨ 删除已发布功能 | **否** | 无删除；显式 `-RunId`/`-LogDir`/`-Suite` 语义保留 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否** | 零新依赖，只用 .NET BCL（`System.Threading.Mutex`、`System.Security.Cryptography.SHA256`） |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 门禁脚本内部实现细节，无架构分叉 |

**结论：A 类，11 门全「否」⇒ 自主设计→实现→实测→提交。**

---

## §3 口径（本轮冻结）

### 3.1 互斥键＝**日志目录**，不是 RunId
键 ＝ `$LogDir.ToLowerInvariant()` 的 `SHA256` 前 **8 字节**（16 hex）。理由：只按 RunId 加锁会漏掉「不同 RunId 但显式传同一 `-LogDir`」的撞车；只按 LogDir 加锁则同时覆盖两种撞车面（缺省派生撞车、显式同 RunId／同 LogDir 撞车）。

### 3.2 用 OS 命名互斥量，不用锁文件
命名空间取 `Local\`（本会话内有效）。理由：持有进程退出或异常终止时由 OS **自动释放** ⇒ **天然无陈旧锁**，不需要「PID 存活探测 ＋ 陈旧锁判定 ＋ 清理时机」这一整套口径。锁文件方案（`Open(CreateNew)`）本轮**否决**：陈旧锁裁决本身是新口径面，且崩溃后会把合法重跑误拒。

### 3.3 抢不到锁 ⇒ `[REFUSE exit=5]`
沿用既有「执行前被拒」退出码语义（该码原用于「缺 `-Confirm`／缺口令／RunId 形状非法」）。**不**静默改用备用目录 —— 否则证据路径不可预期、调用方脚本化的取证会静默漂移。

### 3.4 取锁点＝全部前置门禁之后、任何 maven 之前（改后 `:1061–:1081`）
被拒的运行（缺 `-Confirm`／缺口令）**不占锁、不留痕**；同时保证「锁存在 ⇔ 真的在跑」。

### 3.5 缺省 RunId 唯一化
`'dev003c_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + ([guid]::NewGuid().ToString('N').Substring(0,6))`。**显式 `-RunId` 的采用口径不变**（调用方自担唯一性 —— 但撞车现在会被**拒绝**，而不是静默丢证据）。

### 3.6 被否掉的三个候选方案
1. **只加互斥、不改缺省** ⇒ 同一分钟两次缺省运行必有一次被拒，「默认即用」被破坏，且缺省路径仍依赖人工规避。
2. **只改缺省、不加互斥** ⇒ 显式同 RunId 仍静默丢证据；`-LogDir` 撞车完全无保护。
3. **把时间戳追加进 `-LogDir` 派生式** ⇒ 漂移了 `-LogDir` 语义，超出必要改动。

### 3.7 探针纪律（沿用 S3-49/S3-50，本轮新增一条）
①互斥名**独立复算**并与脚本打印值比对（自证派生式）②运行中判「锁被持有」③退出后判「已释放」，且**必须带正/负对照** —— 否则「调用抛异常」无法区分「锁不存在」与「调用写法错误」（本轮首测正是踩到此坑，见 §5.2）④真跑一轮完整 default 档作收口证据。

---

## §4 实现面（1 个文件，`+52/-2`）

`scripts/run-tests.ps1`（改前 **1075** → 改后 **1125** 行；CRLF 1125 / 裸 LF 0；语法错误 0）：

| # | 位置（改后行号） | 内容 | 行数 |
| --- | --- | --- | --- |
| ① | `:47` ＋ 用法段 `:57–:59` | 头注释：退出码 5 触发面补「RunId/日志目录已被另一轮门禁占用」；补记缺省 `-RunId` 口径与互斥行为 | `+4/-1` |
| ② | `:751–:768` | S3-51 说明块（来源／实测／修法／未改／未测边界） | `+18` |
| ③ | `:784–:790` | 缺省 RunId 唯一化（秒级时间戳 ＋ 6 位随机后缀） | `+7/-1` |
| ④ | `:1061–:1081` | 并发互斥块（SHA256 派生 `Local\` 互斥量、`WaitOne(0)` 抢锁、失败 `[REFUSE exit=5]`、成功打印锁名） | `+22` |

零生产代码、零 Java/Scala/前端改动、零依赖、零 DDL、零连库。**未改**任何计数基线（`$BaselineDefault` 996/13/110、`$BaselineSpark` 308、`$BaselineIsolated` 30/19/6 全部保持原值）。

---

## §5 证据

### 5.1 诚实表述（不得越界）
- 本轮**不是**「修好了一个会挂的门禁」，而是：**把「同日志目录并发 ⇒ 静默丢证据」改成「响亮拒绝」＋ 消除缺省路径的撞车**。
- **不证明**并发门禁能全部跑完；**不证明** `isolated`/`spark`/`all` 档的并发行为（只测了 `default` 档）。
- `AbandonedMutexException` 接管分支**未测**（代码在，本轮未构造「前一轮异常终止」场景）。
- 收口轮仍 `[FAIL exit=7]` ⇒ **不得**表述为「门禁已通过」。

### 5.2 廉价实测：缺省 RunId 唯一化（改后）
同一分钟内两次缺省 `-RunId`、`-Suite all`（不给口令）：

- 两次调用均 `exit=5`，均含 `[REFUSE exit=5]`（未给出 `-Confirm`），均**未进入 maven**；
- 新增日志目录 **2** 个且互不相同：`v25tests-dev003c_20260916_211326_41ab0c`、`v25tests-dev003c_20260916_211326_9ae8ca`（**同秒**时间戳、不同随机后缀）；
- 去前缀后长度 30，形状匹配 `^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$` ＝ True；
- 旧式同分钟恒等为**代码级事实**（见 F1），非运行证据。

证据：`.verify/s351/s351-default-runid.txt`。

**被否记录（首测证据不足）**：第一版「锁释放」探针只记到 `MethodInvocationException`（PowerShell 对 .NET 静态方法调用的包装异常类型）⇒ **无法区分**「锁确实不存在」与「调用写法错误」⇒ 该次测量判为**证据不足、未采用**，改为带正/负对照重测（§5.3 的 P3）。

### 5.3 并发真跑（`.verify/s351-probe.ps1`，单作业内四段；结果 `.verify/s351/s351-probe.json`）

- **A 轮（完整 default 档，RunId `s351-lock`；同时就是 §5.4 的收口门禁）**：`exit=7`，耗时 **56.9 s**；
  `tests=996 MATCH`／`tests=13 MATCH`／`tests=110 MATCH`；`default 三棵树 合计 = 1119（基线 1119）`；
  模块汇总 `analytics-server exit=1 Tests run: 996 (F=1 E=0 S=1)`（明细 99+353+172+97+118+157）、`mall-simulator 13 (F=0)`、`synthetic-data-generator 110 (F=0)`；
  唯一失败类与方法 ＝ `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`（**已登记环境性红**，`expected: 43 but was: 0`，根因 `landing/` 被 gitignore）；
  日志中**无** `being used by another process`；控制台出现 `并发锁    : Local\v25tests-d1fc8d0db0c9cd2f（已取得；进程退出即释放，无陈旧锁）`。证据 `.verify/s351/A-console.log`。
- **P1（A 运行中）**：`OpenExisting` ＋ `WaitOne(0)` ⇒ **被持有 ＝ True**，且此刻 A 仍在运行 ＝ True ⇒ 锁在跑动期间确实生效。
- **P2（A 运行中发起同 RunId 并发第二轮）**：`exit=5`、耗时 **0.5 s**、报文含 `[REFUSE exit=5]` 与「日志目录已被另一轮门禁占用（RunId='s351-lock' LogDir='C:\Users\ASUS\AppData\Local\Temp\v25tests-s351-lock'）」、**未**进入 maven（无 `Tests run:`／`BUILD SUCCESS`）；发起时点 A 仍在运行 ＝ True，且 A 后续仍全 MATCH ⇒ **拒绝未干扰 A 的证据产出**。证据 `.verify/s351/B-console.log`。
- **P3（A 退出后判「无陈旧锁」，带正负对照）**：①正对照（本进程自建同名锁）`OpenExisting` 成功 ＝ True ②负对照（确定不存在的名字）内层异常 ＝ `System.Threading.WaitHandleCannotBeOpenedException` ③目标名字 `OpenExisting` 失败，内层异常**同为** `WaitHandleCannotBeOpenedException` ④同名新建 ＋ `WaitOne(0)` ＝ **True**（无他人持有）⇒ 两条独立检查一致：**无陈旧锁**。证据 `.verify/s351/s351-lock-release.txt`。

### 5.4 门禁（默认档；即 §5.3 的 A 轮）

| 断言 | 实测 |
| --- | --- |
| analytics-server | `tests=996 MATCH`（基线 996） |
| mall-simulator | `tests=13 MATCH`（基线 13） |
| synthetic-data-generator | `tests=110 MATCH`（基线 110） |
| 三棵树合计 | `1119（基线 1119）` |
| 档位结论 | `default   FAIL （1119 个用例）` ⇒ `[FAIL exit=7]` |
| 唯一红 | `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`（已登记环境性） |

- 与改前基线**逐项一致** ⇒ 本轮构成「改后脚本仍能跑完整 default 档」的**回归证据**。
- **本轮不改任何计数基线**。
- 工具事实（沿用 S3-50 登记）：脚本摘要走 `Write-Host`（信息流）⇒ 外层落盘必须 `*>&1`；`2>&1` 抓不到摘要。

---

## §6 对外行为变更（脚本自身契约）

1. **缺省 `-RunId` 形状变化**：`dev003c_<yyyyMMdd_HHmm>` → `dev003c_<yyyyMMdd_HHmmss>_<6 位随机>`（长度 30，仍满足形状校验）。唯一历史记录点 `docs/acceptance/dev003c-unified-test-entry-20260915/REPORT.md:19` 按「不改写已发布历史」纪律**保留原样**，以本登记件与脚本头注释为准。
2. **退出码 5 的触发面扩大**：新增「日志目录已被另一轮门禁占用」⇒ 脚本化解析 `exit=5` 的调用方需读报文区分（报文含 RunId 与 LogDir）。
3. **其余零变化**：`-Suite`/`-RunId`/`-MavenCmd`/`-MavenRepoLocal`/`-JdkDefault`/`-JdkSpark`/`-LogDir`/`-AllowCountDrift`/`-Confirm` 语义、各退出码含义、计数基线均不变；`contract-specs/**` 零改动；未新增任何 HTTP/DB/前端契约。

---

## §7 未测与边界（不得夸大）

1. **跨会话/跨用户不互斥**：命名空间用 `Local\`（未用 `Global\`），也未测多用户/多会话并发。
2. **`AbandonedMutexException` 接管分支未测**：代码存在（前一轮异常终止时接管），本轮**未构造**该场景，也未做「运行中途崩溃后下一轮能否接管」的端到端验证。
3. **路径等价形态不互斥**：互斥名由 `$LogDir` 字符串 `ToLowerInvariant()` 派生，**不做**路径规范化（未 `Resolve-Path`、未解析符号链接、未处理 `..` 或 8.3 短名）⇒ 同一物理目录的两种写法可能各持一把锁。缺省路径为 `$env:TEMP` 下的规范路径，不受影响。
4. **被拒运行仍会创建空 `LogDir`**：`New-Item` 在门禁之前（本轮实测：两条缺省探针各留一个空目录，已清理；现存历史默认档空目录 **25** 个）。是否延后建目录**未改**（改它会动 `-LogDir` 语义）。
5. **只测了 default 档并发**：`isolated`/`spark`/`all` 档并发**未测**；其中 isolated 档还涉及按 RunId 建库建受限账号，属真实副作用面。
6. **不证明并发能全部跑完**：只证明「缺省不再撞车」「撞车会响亮拒绝」「锁不残留」。
7. **未测 Windows PowerShell 5.1**：本项目统一 `pwsh` 7。
8. **未测**：两轮并发各自跑完后的**证据完整性交叉核对**（本轮只核对被拒方未启动 maven、持有方全 MATCH）。

---

## §8 顺带台账（登记，不在本轮处理）

1. backlog **L504** 关闭（行内追加 S3-51 已实施说明）；**新增 1 行** backlog「S3-51 后继残余面」（含 §7 ①②③④⑤）。
2. `docs/acceptance/dev003c-unified-test-entry-20260915/REPORT.md:19` 的缺省 RunId 描述自此成为**历史记录**（按纪律**不改写**）。
3. `docs/PROJECT_STATUS.md` 滚动执行位置块（5 段／9 行）与阶段6 逐轮记录（新增 7 条）同步；`docs/status-history/开发过程事实与决策记录.md` 追加 **F-84**。
4. 下一开发项候选（本轮**不**处理）：backlog **L505**（Spark SQL 反斜杠转义静态守卫；「注释/KDoc 误报」可由既有 `WarehouseNameLiteralScanner.CommentSyntax.strip` 化解，但**需先定白名单口径**）。

---

## §9 复现命令

```powershell
# 0) 前置：工作树 D:\Develop_code\GraduationProject-wt\v3-dev（分支 feature/v3-development）

# 1) 缺省 RunId 唯一化（廉价：同一分钟内两次缺省调用；均缺口令 ⇒ maven 之前被拒，但日志目录已派生）
$before = @(Get-ChildItem $env:TEMP -Directory -Filter 'v25tests-dev003c_*' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite all
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite all
$after  = @(Get-ChildItem $env:TEMP -Directory -Filter 'v25tests-dev003c_*' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
# 期望：$after 比 $before 多 2 个且互不相同（同秒时间戳 ＋ 不同随机后缀）；两次 exit=5

# 2) 并发探针（A 全档 ＋ P1 锁被持有 ＋ P2 同 RunId 并发拒绝 ＋ P3 释放〔带正负对照〕）
pwsh -NoProfile -File .verify\s351-probe.ps1        # 约 60 s；结果 .verify\s351\s351-probe.json

# 3) 收口门禁（即探针 A 轮；如需单独重跑）
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default -RunId s351-lock -Confirm *>&1 > a.log
# 期望：tests=996 / 13 / 110 全 MATCH；三棵树 1119（基线 1119）；[FAIL exit=7]（唯一红＝已登记环境性用例）
# 注意：摘要走 Write-Host（信息流）⇒ 外层落盘必须用 *>&1（S3-50 已登记的工具事实）。

# 4) 并发拒绝复现：A 运行中再以同一 -RunId 起一轮
#    期望：≤1 s 内 [REFUSE exit=5]，报文含 RunId 与 LogDir，且未启动 maven
```
