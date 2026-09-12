# P2-03（Java/Scala 同向量代理键）只读取证与规格草案

- 日期：2026-09-12（本地时钟）
- 任务行：`docs/项目实施进度与任务看板 V2.2.md:222` —— 逐字：`| P2-03 | Java/Scala 同向量代理键 | \`TODO\` | B | P2-01 | UUID/雪花/纯数字/前缀 ID 同值 |`
- 泳道登记：`docs/项目实施进度与任务看板 V2.2.md:433`（只读取证 ≠ 实施；P2-03 状态仍为 `TODO`）
- 证据级别：**E1 只读**（自写可复跑脚本 + 机械判据）。**本轮不含 E2（单元测试）与 E3（本地真实链路）**，本报告**不得**被当作"已实现/已验证/已通过"。
- 本目录写入边界：本报告与 `raw/` 下自写脚本及其输出。除本目录外**未写任何文件**（不碰 `contract-specs/**`、不碰源文件、不碰看板）。

---

## §1 任务与范围

**目的**：为 P2-03（Java 与 Scala 两侧对同一批测试向量算出**同一个代理键**）提供①只读取证事实、②缺口清单、③候选方案、④向量集草案，交总控裁决。**不实现代码、不跑任何 Maven/编译/Spark**。

**范围内**：

1. 四程序（`analytics-server` / `mall-simulator` / `spark-jobs` / `synthetic-data-generator`）main 与 test 两侧"代理键相关实现"普查（含为零命中类结论配正面控制）；
2. 各层表 → 键列 → 语义（业务键 / 代理键 / 无）清点；
3. 已冻结的 `warehouse-namespace.v1.json`（规则 + 22 向量 + 双语言薄适配器 + 逐向量对账测试）作为**可复用模板**的总结，以及新增契约制品必须遵守的既有规则；
4. Java 与 Scala 的**跨语言同值风险点**逐条代码取证；
5. 黄金集 55 行与真实 landing JSONL 的业务键真实取值形态统计。

**范围外（明确不做）**：任何实现、任何编译/运行、任何宿主机改写、任何契约冻结、任何看板/裁决文件修改。

**权威顺序**（沿用 `contract-specs/README.md:21-27`）：`contract-specs/`（`warehouse-namespace.v1.json` 为 `FROZEN-2026-09-11`）→ 指导书 → 看板 → 专项设计。

> **⚠️ 窗口内发生的权威迁移（必读，见 §12.10）**：commit `370a090`（作者时间 `2026-09-12 13:04:16`）的提交信息逐字包含 **`裁决 D-066（H1 改规格：指导书迭代 V2.4，撤回就地追加）…；权威入口迁 V2.4`**。也就是说：**在本次取证进行到一半时，项目的权威入口从 V2.2/V2.3 迁到了 `docs/项目完整实施指导书 V2.4.md`**。
>
> 本报告**只读过 V2.2 及之前**（V2.4 是在读取窗口内才出现的未跟踪新文件，见 §12.2）。因此本报告的 §4 事实（对源代码/契约/数据，带 sha256）**不受影响**，但 §6/§7 里凡引用"指导书/计划/设计"的**规格性依据**都**必须**由总控在对齐 V2.4 后复核。已列 **§11 待裁问题 15** 与 **§10 U-12**。

---

## §2 交付物清单（含 sha256）

全部 sha256 为**本会话实测**（`Get-FileHash -Algorithm SHA256`，小写十六进制）。

### 2.1 目录与报告

| 文件 | 字节 | 行 | sha256 |
|---|---|---|---|
| `docs/acceptance/p2-03-surrogate-key-20260912/README.md`（本文件） | 见 `raw/deliverable-fingerprints.tsv`（成文时快照） | 见 `raw/deliverable-fingerprints.tsv` | 见 `raw/deliverable-fingerprints.tsv`（**成文时快照**；SHA-256 无法自指，复核时重算，命令见 §12.8） |
| `docs/acceptance/p2-03-surrogate-key-20260912/raw/**`（共 34 个制品） | `raw/deliverable-fingerprints.tsv` | 35 行（含本文件） | 同上（sha256 `31d19654084887bccb9d4aab1ee1f6750a19d21cce954c7728a90bb83f2d7085`，该文件自身也已登记在 §2.4） |

### 2.2 自写脚本（可复跑）

| 文件 | 字节 | 行 | sha256 | 作用 |
|---|---|---|---|---|
| `raw/scan-surrogate-key.ps1` | 5222 | 128 | `9370bd128016201f0eb33a1069d7163f8295c556a18d1cd1be36c564c8dcd299` | 447 个 java/scala 文件 × 27 个大小写敏感模式的全量扫描 |
| `raw/analyze-golden-ids.ps1` | 6577 | 131 | `5a66c8dc2c24cbad7bd039e29168690acfdcd439542ebea50b2b974454c0a1b3` | 黄金集 55 行业务键形态统计 |
| `raw/analyze-landing-ids.ps1` | 4045 | 82 | `19502ffc1f620b6be0e704bce1e2cd63b38baf326539999db32b1b4519af1fd4` | 真实 landing JSONL（2 文件 2684 行）形态统计 |
| `raw/hash-key-shapes.ps1` | 3079 | 58 | `c2acbefd9b997124e351b0c5cc18f5ec70d9f5f3693575b14d1ae0c5be73368e` | 真实业务键取值的 SHA-256 逐位观测 |
| `raw/draft-vectors.ps1` | 5335 | 76 | `9ae900b938c2441785424be4df6c2dcbb974c820cb96a9d730715489e6ffb6b2` | 向量草案 material/SHA-256 计算 + 同值/不同值机械自检 |
| `raw/fingerprint-cited-files.ps1` | 6445 | 96 | `82d41b681bc37cc58cfcd046633852dc8ffba316b05c3124fad235c759a8b842` | 本报告引用文件的 mtime/size/sha256 + 窗口判定 |

> `raw/verify-raw-fingerprints.ps1` 与 `raw/verify-raw-fingerprints-console.txt`（自检脚本与其结论）**不登记在本表**——理由：脚本内容里逐字写着被校验的三元组，若把它自己也算进去就构成自指环（改脚本 → 改登记 → 再改脚本）。它们的指纹**不声称**，见 §12.8。

### 2.3 脚本产出（原始读数）

| 文件 | 字节 | 行 | sha256 |
|---|---|---|---|
| `raw/source-files-inventory.txt` | 53873 | 447 | `72018b403f1164535437ad7d44dd3ba6a8a0046bb7a62e9ed1db9df421e7019a` |
| `raw/grep-surrogate-key-hits.tsv` | 340420 | 1732 | `e199cb26493808a2c06e4b0044df50bdd4405b20227acbf49174463b15565586` |
| `raw/grep-surrogate-key-summary.tsv` | 2548 | 64 | `51124d1a7b283a859ec84de11dd4142e6c2702d83f7c75d4b26fd788b49e6e26` |
| `raw/grep-surrogate-key-per-file.tsv` | 20042 | 124 | `e5657d709badbd565fc604ad620c21472dd14037b4753177eb2ce255ae51a9e2` |
| `raw/scan-surrogate-key-console.txt` | 470 | 16 | `6f81608bc3822e3b1564b6e3662131dd1949077b556a3b7a8c0bef7f59d41daa` |
| `raw/golden-id-shapes.tsv` | 1331 | 9 | `5a173fb9ed1b79d4951b71b1f0696f649e3328bbfee69471eea6702e80b89662` |
| `raw/golden-id-samples.tsv` | 5317 | 188 | `af33e40d8eb5079f127ddd42cb34f5359891d6cfa2ecd64ac19b957a57fc2ed3` |
| `raw/golden-line-classes.tsv` | 709 | 55 | `646e838ece539241acea0684c3232533df3c38ad79993d84164d6d26a73d5181` |
| `raw/analyze-golden-ids-console.txt` | 2448 | 57 | `0221fdcd69855f9793b38a8b4ad23a928b492bc9c80ff6675350ae60971cbc09` |
| `raw/landing-id-shapes.tsv` | 803 | 9 | `508f6d30b3ae0e690b807f325f40636a2254307e212113da32f8da39ff97d987` |
| `raw/analyze-landing-ids-console.txt` | 1649 | 17 | `e8be6ec3b094a10f72613b15cdfc95fcc8021225cae435aaa90f625f4848586b` |
| `raw/hash-key-vectors.tsv` | 2051 | 13 | `f3f8eeaca69add0b149d9494e3728fc41ad10a399fee746c3d1f8b7ffa43a0a0` |
| `raw/hash-key-shapes-console.txt` | 2006 | 21 | `ef1cb4d3e6715737cdaea0476e3e05dc29bac9d09fee188d740fcffeb6c5dfca` |
| `raw/draft-vectors.tsv` | 4682 | 22 | `3aa11845cf70c8b5ef8cfd756a0ed1ee2763ac7ba7b83da19093d32c65928567` |
| `raw/draft-vectors-console.txt` | 3334 | 38 | `87b381d76efd93c622e40648e5e0edc7f353073ba713a90ab5f97e7ebe7a97b3` |
| `raw/cited-file-fingerprints.tsv` | 9909 | 56 | `0c044d422f7b9a73e48d746c1bcad4dde81e8b995df8359accfe1803df19e00b` |
| `raw/cited-file-fingerprints-console.txt` | 744 | 11 | `1ecd5d70adc730e597d0a47e70db013b8996be0575d7cda59e4eba95e8ab7df6` |

### 2.4 窗口快照与指纹

| 文件 | 字节 | 行 | sha256 |
|---|---|---|---|
| `raw/git-head-early.txt` | 42 | 1 | `92640474375dfecdfb349bdaa7b99750573954715f1831600b2603f9f14ca685` |
| `raw/git-head-late.txt` | 42 | 1 | `92640474375dfecdfb349bdaa7b99750573954715f1831600b2603f9f14ca685` |
| `raw/git-head-detail-early.txt` | 257 | 3 | `1d8d092c712e145e2596ae531b756fe8bfdee6f7ba732240a8b538115bbab797` |
| `raw/git-head-detail-late.txt` | 257 | 3 | `1d8d092c712e145e2596ae531b756fe8bfdee6f7ba732240a8b538115bbab797` |
| `raw/git-status-early.txt` | 1320 | 20 | `d9ace97fb562d23cbd9b14ab9377fbde9897dc6746888ef705d80b629ec758f1` |
| `raw/git-status-late.txt` | 1418 | 19 | `a98e2dd293fa714a2fcac400e71977e5dfca9f88c2c5ead94cffcfb2a5e59b6e` |
| `raw/window-timestamp-early.txt` | 32 | 1 | `5fdb1a76ba011354652f38dc536b9506e95287a63f00145ce7e204c2351e6039` |
| `raw/window-timestamp-late.txt` | 54 | 1 | `5654abd301eeb118ee023125c8d4c08b549e68b5550561e6ab27ee4c9a58c945` |
| `raw/git-status-postwindow.txt` (29 条) | 2115 | 29 | `61b87cc02031c6c5b6050c4adee4ff453ac4dc9e11e3eb74237ce54a0162d0ee` |
| `raw/postwindow-timestamp.txt` | 32 | 1 | `f15cae79a8ea8f81957b48ed9d667777187371f323112723a59fc05c6a936f77` |
| `raw/git-head-postwindow.txt` | 42 | 1 | `7c3c44bf1e961a85fcee94bf7f5d6f63f5d9264c9a229d1e2af59e9b10136101` |
| `raw/git-log-timeline.txt` | 2257 | 21 | `3df4f1dda2c371230d632fd4a927ebafa070a2fd31b459c2912da9c0f83e74eb` |
| `raw/deliverable-fingerprints.tsv`（**本目录全量制品的成文时指纹快照**） | 见自身 | 见自身 | **不逐字登记**（该表由脚本在每次运行时自重写，登记其哈希会自指振荡）；复核时用 §12.8 命令重算 |

> **为什么不给 `deliverable-fingerprints.tsv` 写死哈希（防熵说明）**：该文件的内容包含本目录**每个**制品的 sha256，其中包括 `README.md` 自身；而 README 又要在 §2.1/§2.4 引它。任何"写死其哈希"的尝试都会形成 `README → manifest → README` 的自指环，重算一次哈希就变一次，**这正是反熵要禁止的"两个互相矛盾的同一事实来源"**。故本报告的选择是：
>
> - **§2.2/§2.3/§2.4 逐字登记的 32 个三元组** = 权威、可机械核对（由 `raw/verify-raw-fingerprints.ps1` 自动比对，结论见 `raw/verify-raw-fingerprints-console.txt`：`registered=32 pass=32 fail=0 missing=0`，`VERDICT=PASS`）。
> - **`deliverable-fingerprints.tsv`** = 便利索引，**不是**事实来源；它的哈希由复核者重算，本报告不声称。

> **更正记录（防熵，必读）**：本表 `raw/draft-vectors.*` 三行的**字节/行数在本报告成文后被更正过一次**——初稿写的是 `5505/74`、`6502/22`、`1665/41`（对应被重写的旧版本），与磁盘不符；上表已改为实测值 `5335/76`、`4682/22`、`3334/38`，并**补登记了三个 sha256**（原先写"见 §2.4"而 §2.4 并未登记，属本报告自身的取证缺口，已由 `raw/verify-raw-fingerprints.ps1` 的 35 项登记覆盖）。**22 条向量与其 SHA-256 摘要未变**（`raw/draft-vectors.tsv` 逐行核对一致，见 §7）。
>
> 唯一仍不能自指的是 `README.md` 本体的 sha256（写死会自指振荡）：由总控复核时重算，或读 `raw/final-readme-hash.txt`（该文件只登记 README 的字节/行/哈希，不被 README 引用，无自指环）。

---

## §3 方法与证据级别

**E1（本轮唯一级别）**：自写 PowerShell 脚本 + 机械判据 + 原始输出落盘。脚本只读输入、只写本目录 `raw/`。

**已执行的口径控制（正面/反向对照）**：

| 控制 | 做法 | 实测结果 |
|---|---|---|
| 三重对账 | `summary_sum` / `perfile_sum` / `hits_tsv_lines` 三个独立文件的计数 | `summary_sum=1732` / `perfile_sum=1732` / `hits_tsv_lines=1732`（`raw/scan-surrogate-key-console.txt` 第 11–13 行） |
| 正面对照 | 扫描器在 447 文件里必须**能找到**已知存在的模式 | 命中 `uuid_lower=16`(as main)、`messagedigest=2`、`sha256=6` 等（`raw/grep-surrogate-key-summary.tsv`）⇒ 扫描器不是"永远报 0" |
| 零命中反证 | 零命中模式另用独立工具（`grep` 工具，ripgrep 引擎）交叉核对 | 对 `*.scala` 查 `sk_\|SK_\|\b\S*_sk\b\|row_key\|rowkey\|RowKey\|snowflake\|Snowflake` → `No matches found` |
| 编码健壮性 | 严格 UTF-8 读取失败即回退默认编码并计数 | `non_utf8_files=0`（`raw/scan-surrogate-key-console.txt:3`） |
| 字节同一性 | 对两份疑似同文件做逐字节比较 | `len_a=18430 len_b=18430 equal=True`（§4 事实 F-77） |

**判据失效风险（如实登记）**：`raw/git-status-early.txt` 落盘于 `2026-09-12 12:58:08`，而扫描开始于 `12:57:06`、结束于 `12:57:41` ⇒ 它是**紧邻扫描之后的代理快照**，不是真正的"扫描前"状态。窗口内被写的文件靠 mtime 判定（§12），不靠该快照。

**不得升级**：本项目证据级别定义中 E2＝单元测试、E3＝本地真实链路。本报告全部结论停在 E1。

---

## §4 事实清单

### 4.1 普查口径与规模

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-01 | 参与普查的 java/scala 源文件共 **447** 个、命中 **1732** 处 | `raw/scan-surrogate-key-console.txt:1-3` | `files_scanned=447` / `total_hits=1732` / `non_utf8_files=0` |
| F-02 | **文件数**分模块（`side\tside_module\tpath` 三列，447 行）：main 侧 as 156 / mall 65 / spark 35 / gen 70；test 侧 as 70 / mall 13 / spark 17 / gen 21（↑合计 447） | `raw/source-files-inventory.txt`（447 行；逐行 `Group-Object` 复算，本会话实测） | 计数命令与输出见 §12.6（可复跑） |
| F-03 | **命中数**分模块与文件数**不是同一组数**（勿混用）：main 侧 as 465 / mall 120 / spark 60 / gen 304；test 侧 as 374 / mall 60 / spark 147 / gen 202；↑合计 **1732** | `raw/scan-surrogate-key-console.txt:6-15` | `main, analytics-server 465` … `test, synthetic-data-generator 202`；有命中**文件**数 124（as 35+30 / mall 11+3 / spark 5+8 / gen 19+13，`raw/grep-surrogate-key-per-file.tsv` 124 行） |
| F-03a | 27 个模式、大小写敏感 | `raw/scan-surrogate-key.ps1` | 三重对账一致（§3 控制表） |
| F-03 | 27 个模式、大小写敏感；三重对账一致 | `raw/scan-surrogate-key-console.txt` | 见 §3 控制表 |

### 4.2 零命中类结论（均配正面控制）

| 编号 | 模式（逐字） | 命中 | 证据 |
|---|---|---|---|
| F-04 | `sk_lower`(`sk_`)、`sk_upper`(`SK_`)、`underscore_sk`(`_sk` 结尾) | **0 / 0 / 0** | `raw/grep-surrogate-key-summary.tsv` 中三模式整表无行 |
| F-05 | `row_key`、`rowkey`、`rowkey_cap` | **0 / 0 / 0** | 同上 |
| F-06 | `snowflake`、`snowflake_cap` | **0 / 0** | 同上 |
| F-07 | `md5`、`md5_upper` | **0 / 0** | 同上 |
| F-08 | `sha1*`（3 个变体）、`digestutils` | **0 / 0** | 同上 |

> ⇒ **全仓库 447 个 java/scala 文件里不存在任何 `_sk`/`rowkey`/`snowflake`/MD5/SHA-1/DigestUtils 形态的代理键实现**。这是"零命中"结论，已配 §3 的正面对照。

### 4.3 唯一的"契约 id → 数仓键"实现（Scala 单侧）

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-09 | 唯一实现文件 = `spark-jobs/src/main/scala/com/graduation/analytics/sql/IdCodec.scala`（49 行）；`spark-jobs` main 侧全仓库仅 1 处中文"代理键"命中 | `spark-jobs/src/main/scala/com/graduation/analytics/sql/IdCodec.scala:4` | `* 契约字符串 id → 数仓 BIGINT 代理键的**唯一**映射规则。` |
| F-10 | 模式（全匹配"字母前缀 + 纯数字"） | `.../IdCodec.scala:26` | `final val IdPattern: String = "^[A-Za-z]*([0-9]+)$"` |
| F-11 | 本地判定用形状（去捕获组） | `.../IdCodec.scala:29` | `private final val IdShape = "^[A-Za-z]*[0-9]+$"` |
| F-12 | SQL 表达式生成 | `.../IdCodec.scala:37-38` | `def toBIGINT(expr: String): String =` / `s"CAST(REGEXP_EXTRACT($expr, '$IdPattern', 1) AS BIGINT)"` |
| F-13 | 本地实现：**剥掉所有字母前缀**后转 Long | `.../IdCodec.scala:44-48` | `def normalize(id: String): Option[Long] =` / `Option(id)` / `.filter(_.matches(IdShape))` / `.map(_.replaceAll("^[A-Za-z]+", ""))` / `.flatMap(s => scala.util.Try(s.toLong).toOption)` |
| F-14 | 不匹配 → `NULL`，**不兜底成 0**；`U-1` 不得变 `-1`（避免与维度 unknown 哨兵冲突） | `.../IdCodec.scala:13-15` | `* 规则 = **全匹配**「字母前缀 + 纯数字」才转 \`BIGINT\`，否则 \`NULL\`（由质量规则判定，**不静默兜底成 0**）。` / `* 全匹配（而不是"剥前缀后尽力解析"）是有意的：\`U12A\`/\`U-1\`/\`view\` 这类取值必须得到 \`NULL\`，` / `* 否则 \`U-1\` 会被解析成 \`-1\`，与维度表的 unknown key 哨兵 \`-1\` 混淆（\`IdCodecSpec\` 已固化该断言）。` |
| F-15 | 已知缺陷记录：直接 `CAST` 对契约 id 静默返回 NULL | `.../IdCodec.scala:8-10` | `* 而数仓 \`dwd/dws/ads/dim\` 的 id 列是 \`BIGINT\`；直接 \`CAST(... AS BIGINT)\` 会**静默返回 NULL**` / `* （run 36 实测：ODS \`U000058\` → NULL，DWD \`rows=13 / null_uid=13 / null_pid=13\`），` |
| F-16 | 负向守卫口径：SQL 模板不得手写 `CAST(payload_*_id AS BIGINT)`（正则覆盖 user/product/order/category/parent_category/brand 六字段） | `spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala:56`（**修订版**，mtime `2026-09-12 13:09:43`，见 §12.6） | `private val directCast = """(?i)cast\s*\(\s*[a-z_]*\.?payload_(user\|product\|order\|category\|parent_category\|brand)_id\s+as\s+bigint""".r` |
| F-17 | 该守卫覆盖 7 个模板端点：4 个 `assertNormalized` + 3 个 `assertNoDirectCast` | `.../IdCodecSpec.scala:75-82`（修订版行号；旧版为 `:71-79`） | `assertNormalized("DwdSql.behaviorClean", DwdSql.behaviorClean(ns, "20260901"))` / `assertNormalized("DimSql.userSnapshot", …)` / `assertNormalized("DimSql.productSnapshot", …)` / `assertNormalized("TradeDwdJob.orderDetailInsertSql", …)` / `assertNoDirectCast("AdsSql.operationOverview", …)` / `assertNoDirectCast("DwsSql.userBehaviorDay", …)` / `assertNoDirectCast("OdsLoadSql.behaviorFromLanding", OdsLoadSql.behaviorFromLanding(ns, SrcSys, 7L))` |
| F-18 | 幂等性口径：id 链上归一化次数被固化断言 | `.../IdCodecSpec.scala:87`（修订版行号；旧版为 `:83`） | `behavior.sliding("payload_user_id".length).count(_ == "payload_user_id") shouldBe 3 // select + join + not null 过滤` |

> **Java 侧对照**：`analytics-server` **没有**任何等价的 id 归一化实现（§4.8 只找到 HASH64 的**声明**与校验，没有实现）。⇒ "代理键"当前是**单语言（Scala）实现 + 另一个程序里只有声明**。

### 4.4 各层表 → 键列 → 语义

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-19 | 本地建库 DDL 的键列类型（DWD/DIM 全为 BIGINT，唯一 STRING 键是 `behavior_id`/`reject_id`） | `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala:49-55` | `CREATE TABLE IF NOT EXISTS ${ns.dwd}.dwd_user_behavior_detail (` / `behavior_id STRING, user_id BIGINT, product_id BIGINT, category_id BIGINT,` / `behavior_type STRING, event_time TIMESTAMP, event_date STRING,` / `event_hour INT, city_level STRING, channel STRING, session_id STRING,` / `source_batch_id BIGINT)` |
| F-20 | 订单明细键列 | `.../LocalSchemaInitJob.scala:62-63` | `CREATE TABLE IF NOT EXISTS ${ns.dwd}.dwd_order_detail (` / `order_id BIGINT, user_id BIGINT, product_id BIGINT, category_id BIGINT,` |
| F-21 | 维表键列（含 `category_id`/`parent_category_id`/`brand_id`） | `.../LocalSchemaInitJob.scala:77-83` | `CREATE TABLE IF NOT EXISTS ${ns.dim}.dim_product (` / `product_id BIGINT, product_name STRING, category_id BIGINT, category_name STRING,` / `parent_category_id BIGINT, parent_category_name STRING,` / `brand_id BIGINT, price DECIMAL(18,2), cost DECIMAL(18,2), status STRING,` |
| F-22 | 静态 DDL 同一口径：`behavior_id` 语义 = `event_id`，其余 id 列 BIGINT，**全仓库 DDL 无 `_sk` 列** | `warehouse/ddl/01-dwd.sql:12-15` | `behavior_id   STRING  COMMENT '= event_id',` / `user_id       BIGINT,` / `product_id    BIGINT,` / `category_id   BIGINT  COMMENT '商品维表补充',` |
| F-23 | ODS 层保留**字符串原文**（不提前转型） | `.../IdCodecSpec.scala:109-112`（**修订版**；旧版 `:105-108`） | `it should "ODS 载入层保留字符串原文（不在 ODS 提前转型）" in {` / `behaviorOds should include("payload.user_id AS payload_user_id")` / `behaviorOds.toLowerCase should not include "as bigint"` |
| F-23a | **ODS 载入层已带 `sourceSystem` 第二参**（平台注入值），且该测试类新增了统一测试常量 `SrcSys` | `spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala:18`（修订版）；签名见 F-17 的 `OdsLoadSql.behaviorFromLanding(ns, SrcSys, 7L)` | `* P2-01：ODS 模板新增第二参 \`sourceSystem\`（平台注入值），本类统一传测试常量 \`SrcSys\`。` |
| F-23b | **下游分工已被写进测试注释**：ODS 只管保留原文、DWS/ADS 只读 DWD 数字键、转换唯一发生在 DWD/DIM 层 | `.../IdCodecSpec.scala:79`（修订版） | `// 下游（DWS/ADS）与 ODS 载入层不得自行转换契约 id：ODS 保留字符串原文，DWS/ADS 只读 DWD 数字键` |
| F-23c | `IdCodec.normalize` 的既有断言清单（可作为"旧口径"的逐字基线） | `.../IdCodecSpec.scala:28-47`（修订版） | `IdCodec.normalize("U000065") shouldBe Some(65L)` / `IdCodec.normalize("P00030") shouldBe Some(30L)` / `IdCodec.normalize("0007") shouldBe Some(7L)` / `IdCodec.normalize("U-1") shouldBe None // 关键：不得解析成 -1（与维度 unknown key 哨兵冲突）` / `IdCodec.normalize(null) shouldBe None` |

**键列语义归纳（据上表）**：ODS `payload_*_id` = **STRING 业务键原文**；DWD/DIM/DWS/ADS 的 `*_id` = **BIGINT**，由 ODS 层经 `IdCodec` 归一化而来；DWD `behavior_id` = **STRING 且注明 `= event_id`**（业务键，不是新生成的代理键）。

### 4.5 `event_id` 与"去重键"的真实语义（与 D-055 的边界）

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-24 | `DwdSql.scala:31` 的 `ROW_NUMBER()` 是**去重排序列**，不是代理键生成器 | `spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala:31` | `\|         ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time) AS rn` |
| F-25 | `behavior_id` 直接取 `event_id` 原文 | `.../DwdSql.scala:17` | `\|  rn.event_id AS behavior_id,` |
| F-26 | `rn` 别名被**复用于两处不同角色**（子查询表别名与窗口列名），阅读时易误判 | `.../DwdSql.scala:31,35,36,37,38` | `) rn` 与 `WHERE rn.rn = 1                       -- event_id 去重` 同时存在 |

> ⇒ 任务行写的"UUID/雪花/纯数字/前缀 ID 同值"**不是**在说 `event_id`（它是 STRING 业务键、直接落 `behavior_id`），而是在说**下游 BIGINT 键列**：`user_id`/`product_id`/`order_id`/`category_id`/`brand_id`。真正的冲突点见 §4.7。

### 4.6 真实的 id 取值形态（E1 实测）

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-27 | 黄金集 55 行分 54 行 JSON + 1 行非 JSON | `raw/analyze-golden-ids-console.txt` | `JSON        54` / `NOT_JSON     1` |
| F-28 | 第 53 行是**故意植入的非法行**（不是解析故障） | `tests/golden-dataset/events/golden-20260901.jsonl:53` | `not-valid-json-line-with-no-braces-{{{` |
| F-29 | 第 55 行**故意缺 `event_id`** | `tests/golden-dataset/events/golden-20260901.jsonl:55` | `{"event_type":"behavior","event_time":"2026-09-01T18:30:00+08:00","ingest_time":"2026-09-01T18:30:01+08:00","source_system":"mock-mall","schema_version":"1.0","trace_id":"golden-trace-055","payload":{"user_id":"3","product_id":"1","session_id":"s-5","behavior_type":"view","channel":"app"}}` |
| F-30 | 黄金集 9 个键字段的形态统计 | `raw/golden-id-shapes.tsv` | 见下表 |
| F-31 | 真实 landing 两文件共 **2684** 行、**全部** JSON 可解析 | `raw/analyze-landing-ids-console.txt` | `\landing\events\2026091211.jsonl` … `lines=1391 json=1391 notjson=0` / `…2026091210.jsonl` … `lines=1293 json=1293 notjson=0` |
| F-32 | 真实 landing 形态统计 | `raw/landing-id-shapes.tsv` | 见下表 |
| F-33 | 真实 `user_id` 是 **19 位雪花**（另一种"同字段不同形态"的活证据） | `landing/events/2026091211.jsonl:1` | `{"event_id":"89a6db2c-9ecb-4be0-a24b-9f697da00686",...,"payload":{"user_id":"2098607948334395394",...` |
| F-34 | 真实 `event_id` 是**规范 UUID** | 同上 | `"event_id":"89a6db2c-9ecb-4be0-a24b-9f697da00686"` |
| F-35 | 雪花取值均在 signed BIGINT 内 | 实测（§10 记法见下） | `user_id(19d) min=2098607948334395394 max=2098607950729342978`；`long.MaxValue=9223372036854775807` |
| F-36 | 落地区的 `event_type` 分布（本轮 2 文件） | 实测 | `order_cancelled 239 / order_created 355 / order_paid 116 / refund_completed 8 / refund_created 8 / stock_released 239 / stock_reserved 355 / user_registered 71` |

**黄金集形态表（`raw/golden-id-shapes.tsv` 择要，列：字段 / 出现次数 / 去重 / 长度 min-max / 形状 / 样例）**

| 字段 | 次数 | 去重 | 长度 | 形状 | 样例（去重后前几个） |
|---|---|---|---|---|---|
| `event_id` | 53 | 52 | 14 | `OTHER` | `golden-evt-001` … `golden-evt-054`（**无 `golden-evt-037`/`golden-evt-053`**） |
| `user_id` | 40 | 4 | 1 | `NUMERIC` | `1 \| 2 \| 3 \| 9` |
| `product_id` | 31 | 4 | 1 | `NUMERIC` | `1 \| 2 \| 3 \| 4` |
| `order_id` | 26 | 6 | 4 | `NUMERIC` | `1001 … 1006` |
| `payment_id` | 5 | 5 | 6 | `OTHER` | `P-1001 \| P-1003 \| P-1004 \| P-1005 \| P-1006` |
| `refund_id` | 6 | 3 | 8 | `OTHER` | `R-1003-A \| R-1004-B \| R-1005-C` |
| `session_id` | 17 | 5 | 3 | `OTHER` | `s-1 … s-5` |
| `category_id` | 5 | 3 | 2 | `NUMERIC` | `11 \| 12 \| 21` |
| `brand_id` | 5 | 3 | 3 | `NUMERIC` | `101 \| 102 \| 202` |

**真实 landing 形态表（`raw/landing-id-shapes.tsv`，2 文件 2684 行）**

| 字段 | 次数 | 去重 | 长度 | 形状分布 |
|---|---|---|---|---|
| `event_id` | 2684 | 2684 | 36 | `UUID_CANONICAL=2684` |
| `user_id` | 1519 | 134 | 19 | `NUMERIC_SNOWFLAKE_LEN=1519` |
| `product_id` | 1165 | 38 | 4–19 | `NUMERIC=1147` + `NUMERIC_SNOWFLAKE_LEN=18` ⇒ **同字段两种命名空间** |
| `order_id` | 2521 | 678 | 19 | `NUMERIC_SNOWFLAKE_LEN=2521` |
| `payment_id` | 219 | 219 | 19 | `NUMERIC_SNOWFLAKE_LEN=219` |
| `refund_id` | 30 | 15 | 19 | `NUMERIC_SNOWFLAKE_LEN=30` |
| `category_id` | **0** | 0 | — | — |
| `brand_id` | **0** | 0 | — | — |
| `session_id` | **0** | 0 | — | — |
| 非 ASCII / 首尾空格 | 全部字段 **0 / 0** | | | |

### 4.7 三个会直接决定 P2-03 设计口径的实测事实

| 编号 | 事实 | 证据（file:line / raw） | 逐字片段 |
|---|---|---|---|
| F-37 | **同一个 `product_id` 字段存在两个互不相交的 id 命名空间**：目录 4 位（`1001`–`8004`）与雪花 19 位 | `raw/analyze-landing-ids-console.txt` + 实测 | `distinct_4digit=30`、`distinct_19digit=6`、`intersection_count=0`；样本 `2096441909987262465` 与 `1001` |
| F-38 | 19 位商品 id **只出现在 stock_* 与 order_created**，从不出现在 `product_created`（本轮 2 文件里 `product_created_count=0`） | 实测 | `product_created_count=0`、`product_updated_count=0` |
| F-39 | **`category_id` / `brand_id` 在全部 47 个 `landing/events/*.jsonl` 里只出现在旧夹具**；本轮真实文件与 2026-09-05/06 的历史文件**全为 0** | 实测（逐文件正文字符串计数） | `2026091211.jsonl	category_id=0	brand_id=0`；`r9-m1-123006.jsonl	category_id=5	brand_id=5`；`gen-s3b-1000-20260911.jsonl	category_id=50	brand_id=50` |
| F-40 | 因此 `dim_product` 的 `category_id`/`brand_id` 在真实链路上**只能走 unknown 哨兵** | `spark-jobs/src/main/scala/com/graduation/analytics/sql/DimSql.scala:44-51` 与 `DwdSql.scala:20` | `CASE WHEN p.payload_category_id IS NULL OR p.payload_category_id = ''` / `THEN -1 ELSE ${IdCodec.toBIGINT("p.payload_category_id")} END AS category_id,` / `COALESCE(p.category_id, -1) AS category_id,` |

### 4.8 已声明但未实现的"代理键策略"（Java 侧）

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-41 | 真实源画像里已声明 `surrogate: "HASH64"`（**声明**，无实现） | `analytics-server/source-profiles/p1-03-probe-1.v1.json:22-26` | `"identityPolicy": {` / `"user": { "rawField": "buyer_id", "shape": "UUID", "surrogate": "HASH64" },` / `"product": { "rawField": "item_id", "shape": "PREFIX_NUMERIC", "surrogate": "HASH64" },` / `"order": { "rawField": "contract_no", "shape": "ANY", "surrogate": "HASH64" }` |
| F-42 | 第二份画像：`NUMERIC` / `PREFIX_NUMERIC` / `ANY` | `analytics-server/source-profiles/p1-03-probe-2.v1.json:20-24` | `"user": { "rawField": "uid", "shape": "NUMERIC", "surrogate": "HASH64" },` / `"product": { "rawField": "sku", "shape": "PREFIX_NUMERIC", "surrogate": "HASH64" },` / `"order": { "rawField": "trade_no", "shape": "ANY", "surrogate": "HASH64" }` |
| F-43 | `identityPolicy` 在 Java 侧**只被校验、无生产者** | `analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/SourceProfileValidator.java:42` | `identityPolicy` 作为允许键登记（仅校验） |
| F-44 | Java 侧"代理键"字样只出现在**测试**与**无关的 DB 自增键**断言里 | ① `analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/source/SourceRegistryServiceTest.java:89` ② `…/source/SourceProfileValidatorTest.java:51` ③ `analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceRegistryMigrationMySqlIT.java:220` | ①②：`"surrogate": "HASH64"`（画像 JSON 字面量的测试副本）<br>③：`assertThat(extra).as("id 是自增代理键").contains("auto_increment");`（**DB 自增主键**，与数仓代理键语义无关） |
| F-44a | 全仓库（447 文件 / 27 模式）中 `surrogate` 的**全部**命中只有 2 处，且都在 `analytics-server` 测试里 | `raw/grep-surrogate-key-summary.tsv` 的 `surrogate` 行；逐条见 `raw/grep-surrogate-key-hits.tsv` | 2 处 = F-44 的 ①② |

> ⇒ P2-03 的 `surrogate: HASH64` 需求**唯一有文字依据的载体**是源画像 JSON；**实现侧两个程序都没有**。

### 4.9 雪花 ID 的生成者与跨语言边界

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-45 | 商城侧雪花由 MyBatis-Plus `IdType.ASSIGN_ID` 分配 | `mall-simulator/src/main/java/com/graduation/mall/domain/entity/MallUser.java:17-18`、`Product.java`、`MallOrder.java:18-19` | `@TableId(type = IdType.ASSIGN_ID)` / `private Long userId;` |
| F-46 | 生成器侧"规范 ID"是**前缀 + 零填充**（与雪花不是同一值域） | `synthetic-data-generator/src/main/java/com/graduation/generator/engine/FileModeGenerationEngine.java:309,341,429` | `String productId = "P%05d".formatted(i + 1);` / `String userId = "U%06d".formatted(i + 1);` / `String orderId = "O%08d".formatted(orderSeq);` |
| F-47 | `MALL_API` 模式会把规范 ID **替换**为商城外部雪花 ID ⇒ **规范 ID 与外部 ID 是两个值域，且映射关系只存在于运行时内存** | `synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiDispatchSink.java:541` | `* @param traceability 规范 ID → 商城外部 ID（{@code user:U000001} → 雪花 ID），"可追溯"的物证` |
| F-48 | 前端/接口层已把"超 JS 安全整数"当作必须处理的既成事实 | `mall-simulator/src/main/java/com/graduation/mall/controller/MallController.java:76` | `// ID 超 JS 安全整数，统一字符串返回（雪花 19 位）` |
| F-49 | 商城事件 payload 的 id 一律字符串化 | `mall-simulator/src/main/java/com/graduation/mall/outbox/EventPayloadFactory.java:36,47,66-67,79,83,101,112,123,135,144,155,166` | `p.put("user_id", String.valueOf(userId));` / `p.put("product_id", String.valueOf(productId));` |

### 4.10 跨语言同值风险点（逐条代码取证）

| 编号 | 风险 | 证据（file:line） | 逐字片段 | 危害 |
|---|---|---|---|---|
| F-50 | **大小写归一化的 Locale**：Java 侧现行代码显式使用 `Locale.ROOT`；Scala 侧同一仓库里没有等价写法 | `analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/source/SourceRegistryServiceImpl.java:361` | `return value.trim().toUpperCase(Locale.ROOT);` | 若 Java 侧改用无参 `toUpperCase()`，土耳其语环境 `i→İ`，与 Scala/Spark 侧不一致 |
| F-51 | 同一仓库里**确实存在无 Locale 的 `toUpperCase()`**（反面样本，说明纪律靠人守） | `analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/AiSqlDriftTest.java:68` | `String upper = line.toUpperCase();` | 新增实现若照抄此写法即引入跨语言分歧 |
| F-52 | **哈希实现三套并存**（Java `HexFormat` / Java `String.format` / Scala `f"..."`） | `synthetic-data-generator/src/main/java/com/graduation/generator/contract/JsonlEventSink.java:241`、`analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/AiController.java:262`、`spark-jobs/src/main/scala/com/graduation/analytics/sql/JsonObjectSlicer.scala:182`（**该文件 `??` 未跟踪、mtime `2026-09-12 12:40:35`，见 §12.6**） | `String checksum = HexFormat.of().formatHex(digest.digest());` / `sb.append(String.format("%02x", digest[i]));` / `bytes.foreach(b => sb.append(f"${b & 0xff}%02x"))` | 十六进制输出口径不统一；`Scala` 那句的 `& 0xff` 是**必须的**，漏掉会得到负数十六进制 |
| F-53 | **截断长度也不统一**（4 字节 vs 8 字节） | `analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/AiController.java:252-263` | `/** 8 位短哈希：审计可对同一问题/同一 SQL 归并，又不落原文（§21.3 脱敏） */` / `for (int i = 0; i < 4; i++) {` | 与 `HASH64` 的 8 字节口径不同名同形，易被误用 |
| F-54 | 明文哈希算法常量已存在且被**刻意不声明等价** | `synthetic-data-generator/src/main/java/com/graduation/generator/contract/JsonlEventSink.java:35,41` | `* 两者是否相同未冻结（契约文件原话"不得默认相同"）。本实现取 {@link #CHECKSUM_ALGORITHM}，且**不**声称与采集侧等价；` / `public static final String CHECKSUM_ALGORITHM = "SHA-256";` | 同名"校验和"两套算法（见 F-55） |
| F-55 | 采集侧清单用的是 **CRC32**，与生成器 SHA-256 **不同算法** | `analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/IngestionService.java:128,294` | `CRC32 checksum = new CRC32();` / `manifest.put("checksum", Long.toHexString(checksum.getValue()));` | 跨语言"同值"要求必须先明确**是哪个校验和** |
| F-56 | Java 侧重标度口径（4 位小数 HALF_UP）与 Scala/Spark SQL 的 decimal 语义不同 | `analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceBuilder.java:391-392` | `private static String plain(BigDecimal value) {` / `return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).toPlainString();` | 若代理键 material 里含金额/小数，两侧必然不同值 |
| F-57 | `WarehouseNamespace` 的 `hashCode` 依赖平台 `String.hashCode()`（规范未定义该算法） | `analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java:164-165` | `public int hashCode() {` / `return prefix.hashCode();` | 该 `hashCode` **不得**进入任何持久化/线协议取值；代理键不得复用它 |
| F-58 | Scala 侧同一常量表用的是 `Set[String]`（迭代序无保证），Java 侧是数组/列表 | `spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala:79`、`spark-jobs/src/main/scala/com/graduation/analytics/algorithm/Cleaners.scala:8,11` | `final val Reserved: Set[String] =` / `val BEHAVIOR_TYPES: Set[String] =` | 若 material 由"集合拼接"构成，两侧拼接序可能不同 ⇒ 同值失败 |
| F-59 | 大小写"unknown"哨兵在同一仓库内**两种写法并存** | `spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala:102` 与 `spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala:17` | `（\`DimSql.productSnapshot\` 同样写 'UNKNOWN'）` / `* 缺失维度使用明确 unknown key（-1 / 'unknown'，§11.1）。` | 说明该仓库对"大小写口径"没有单一 owner；代理键的 `UPPER` 口径必须有明文契约 |
| F-60 | JSON 侧"键序敏感"已被实测确认为**不可依赖**（Spark `to_json` 会重排） | `spark-jobs/src/main/scala/com/graduation/analytics/sql/JsonObjectSlicer.scala:17-19`（**该文件 `??` 未跟踪、mtime `2026-09-12 12:40:35`，见 §12.6**） | `* 为什么必须切片而不是 \`to_json(payload)\`：\`to_json\` 走 Spark 自己的序列化，键序/空白` / `* 都可能与源行不同——红检实测「语义等价但键序不同」的文本哈希与源行不同，故不能作为` / `* 字节保真的实现。` | 若代理键 material 选"规范化 JSON"，必须先证明两侧序列化键序一致 |
| F-61 | 已有的"字节保真哈希"样例可作为**位级可复现**的正面参照 | `spark-jobs/src/test/scala/com/graduation/analytics/P2ProbeSpec.scala:44-47`（oracle `385dee5b723e23f0778d6726140ced55b5e9f5aa45f79d42869b1f753e260445`；该文件 `??` 未跟踪、mtime `2026-09-12 12:39:22`） | 见 `raw` 与源文件；本报告只引用其存在，不重跑 | 参照系存在，但**未在本轮复跑**（无 E2） |

### 4.11 可复用模板：`warehouse-namespace.v1.json`

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-62 | 契约本体 = 规则 + 向量 + 双语言对账声明 | `contract-specs/specs/warehouse-namespace.v1.json`（80 行） | `status: FROZEN-2026-09-11`、`owner: P1-04`、`vectors` 共 **22** 条、`parity` 块同时点名两侧测试类 |
| F-63 | Scala 薄适配器持有默认值/分隔符/正则/层后缀/保留字/错误码 | `spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala:68-88,96-127` | `DefaultPrefix` / `Separator` / `PrefixPattern` / `LayerSuffixes` / `Reserved` / 四类错误码 / `validate` / `parse` / `of` / `fromArgs` |
| F-64 | Java 薄适配器镜像同一组常量 | `analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java:30-50,118-146` | 见源文件；`validationError` / `of` |
| F-65 | Java 对账测试：从仓库根读**同一份** JSON，逐向量 + 常量逐字 + `parity` 自校验 | `analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNamespaceContractTest.java:39-41,50-81,85+` | `RepoRoot.path("contract-specs/specs/warehouse-namespace.v1.json")`；常量对账含 `parity.javaTest`/`parity.scalaTest`；向量循环用 `@TestFactory` |
| F-66 | Scala 对账测试：读同一份 JSON + 额外断言"所有 SQL 模板/`INIT_SCHEMA` 都遵守命名空间" | `spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala:36-54,58+,242-253` | `vectors.size() should be >= 15`；模板 map 含 `LocalSchemaInitJob.statements` |
| F-67 | 源码门禁（负向守卫）作为模板的一部分 | `analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java` | 除唯一 owner 外无裸库名字面量 |
| F-68 | 新增契约制品须遵守的既有规则 | `contract-specs/README.md:42,44,45` | 版本号：`**加法变更 → minor 递增**（\`1.0.0 → 1.1.0 → 1.2.0 …\`）；破坏性变更 → major 递增（\`2.0.0\`）并新建 \`v2\` 文件。` / `**每个程序校验自己的输出**，不依赖别的程序替它校验` / `**不得静默放宽**：…都以 \`"x-unspecified": true\`、\`x-*\` 注解或 \`description: "指导书未确定，待契约任务冻结"\` 显式标注；` |
| F-69 | 制品清单现状：5 个制品，仅 `warehouse-namespace.v1.json` 为 `FROZEN`；`canonical-event.v1` 被 B-06/Q6 阻塞 | `contract-specs/README.md:51-55,58` | `\| … \| **\`FROZEN-2026-09-11\`** \|` / `且 Q6（采集层接受的 51 行里约 25 行按契约属脏数据）未决 ⇒ \`canonical-event.v1\` **不得**冻结。` |
| F-70 | 目录级版本当前为 `1.3.0` | `contract-specs/README.md:3` | `版本：\`1.3.0\`（见 [\`VERSION\`](VERSION)；\`1.0.0 → 1.1.0\` 对应 \`specs/warehouse-namespace.v1.json\` 的加法新增…）` |
| F-71 | 锚点卫生：D-065 语境要求新建制品同时申报"裸锚点规则（带文件名）" | `docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md` 与看板 `:433` 登记行 | 看板 `:433`：`**与 D-065 的交汇**：若草案建议新增契约制品，须同时申报裸锚点规则（带文件名）与 \`VERSION\` 升版口径。` |

### 4.12 设计与计划对 P2-03 的既有要求（逐字）

| 编号 | 事实 | 证据（file:line） | 逐字片段 |
|---|---|---|---|
| F-72 | 计划 §3.4 给出算法与"≥12 组输入 + 两侧各自实现但读同一向量" | `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md:85-94` | `统一测试向量文件保存至少 12 组输入：空白、大小写、中文、UUID、雪花 ID、纯数字、前缀数字、最大长度和两个 source 的相同 raw id。算法：` / `normalized = UPPER(TRIM(raw_id))` / `material = source_code + "\|" + entity_type + "\|" + normalized` / `digest = SHA-256(UTF-8(material))` / `surrogate = digest 前 8 字节按无符号解释，清除符号位，0 映射为 1` / `Java 与 Scala 分别实现，但必须读取同一测试向量。空 raw id 不生成代理键，进入 DQ/reject；相同输入稳定、不同 source 不碰撞。维表同时保留 \`raw_*_id\`。` |
| F-73 | 设计 §4.5 独立确认同一算法与选择理由 | `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md:185-191` | `- 算法：\`surrogate = 正数化(SHA-256( source_code \|\| '\|' \|\| entity \|\| '\|' \|\| UPPER(TRIM(raw_id)) )[0..8])\`` / `  - Java（采集/维度初始化）与 Scala（Spark）**同一算法、同一输入规范**，避免两侧算出不同键；` / `  - 选 SHA-256 而非 xxhash 是为了两侧都无第三方依赖、实现可逐位复现。` / `- 保留 \`raw_*_key\` 列（原始字符串）用于排错与对账；\`dim_*\` 主键为代理键。` |
| F-74 | 接线要求：`SurrogateKeys` 取代 `IdCodec`，禁止通用 `CAST(raw_id AS BIGINT)` | `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md:159-164` | 见源文件（`SurrogateKeys` 取代 `IdCodec`；entity_type 命名空间；DWD/DIM 保留 raw id；禁止通用 `CAST(raw_id AS BIGINT)`） |
| F-75 | 验收要求：两侧向量同值 + join 100% | `.../implementation.md:178-184` | 见源文件（P2-06：Java/Scala 向量同值；join 100%） |
| F-76 | ODS 共享列中与身份相关的两列口径 | `.../implementation.md:79,81` | `四主题 ODS 至少共享：\`event_id\`、\`source_system\`、\`schema_version\`、\`raw_event_type\`、\`event_time\`、\`ingest_time\`、\`ingest_batch_id\`、\`landing_file\`、\`payload_json\`、\`payload_hash\`、\`dt\`、\`hour\`。` / `去重仍以 \`(source_system,event_id)\` 为业务键，hash 用于冲突诊断，不代替业务键。` |

### 4.13 黄金集与真实落地的字节同一性（重要）

| 编号 | 事实 | 证据 | 逐字 / 读数 |
|---|---|---|---|
| F-77 | `tests/golden-dataset/events/golden-20260901.jsonl` 与 `landing/events/r9-m1-123006.jsonl` **逐字节相同** | 逐字节比较实测 | `len_a=18430 len_b=18430 equal=True`；两侧 sha256 均为 `2351bcc35e04ccd278638f07247bc37e9c4d402cc4736cd2a4d4b70f4232b11c`；行数均 55 |
| F-78 | 契约 README 记录的是 `2351BCC35E04CCD2`（前 16 位，**大写、无分隔**） | `contract-specs/README.md:36` | `\| \`landing/events/r9-m1-123006.jsonl\`（真实夹具） \| \`2351BCC35E04CCD2\` \|` |
| F-79 | 契约 README 同时记录该来源指纹曾"对不上"（原因未取证） | `contract-specs/README.md:38` | `与上表记录的 \`845D8C08297F7E1C\` **不同** ⇒ 该来源文档在本目录建立之后发生过变化（原因未取证：可能为内容更新或行尾/编码重写）。` |

> ⇒ **黄金集的键形态不是"人造测试值"，它就是真实落盘夹具**（F-77）。因此 §4.6 的 `P-1001`/`R-1003-A`/`golden-evt-001` 形态必须被当作**真实取值口径**纳入向量集，而不是可随手改的测试数据。
>
> **需总控注意（本报告不下结论）**：F-78 记录的是**大写无分隔**的同一 16 位十六进制串；本报告记录的是**小写且完整 64 位**。两者是否同一算法口径、契约 README 那 16 位是否被规范化过，**本轮未取证**（见 §10）。

---

## §5 缺口清单

| 编号 | 缺口 | 依据 |
|---|---|---|
| G-01 | **不存在任何代理键契约制品**（无 `surrogate-key*.json`、无向量文件、无 `parity` 块） | §2/§3 普查；`contract-specs/README.md:51-55` 只有 5 个制品 |
| G-02 | **Java 侧无实现**：`HASH64` 只有声明与校验（F-41…F-44） | §4.8 |
| G-03 | **Scala 侧唯一实现 `IdCodec` 精度不足**：它只做"剥前缀 → BIGINT"，材料里**没有** `source_code`/`entity_type` | F-09…F-13 |
| G-04 | **`IdCodec` 会把不同实体/不同源的同一数字压成同一键**：`U000065`→65、`P00065`→65（前缀被删除，命名空间信息丢失） | F-13 (`replaceAll("^[A-Za-z]+", "")`) 的语义推论；**该碰撞本身本轮未实测**（无 E2），列 §10 |
| G-05 | **`IdCodec` 与设计 §3.4 的"两 source 不碰撞"目标直接冲突**：不同 `source_code` 的同一 raw id 会得到同一 BIGINT | F-13 与 F-72 |
| G-06 | **`HASH64` 的位宽/符号/BIGINT 可容纳性未定义**：设计只写"前 8 字节按无符号解释，清除符号位"，未定义输出类型与"是否为 BIGINT" | F-72 与 DDL 的 `BIGINT` 列（F-19…F-22） |
| G-07 | **`UPPER`/`TRIM` 的跨语言口径未定义**：Java 需显式 `Locale.ROOT`（F-50/F-51 已证仓库两写法并存）；Scala/Spark SQL 的 `TRIM` 默认只去 ASCII 空格、`UPPER` 取决于 collation | F-50、F-51 |
| G-08 | **material 的规范化形式未定**：设计选"`\|` 拼接"（F-72/F-73），但需要定义**转义/分隔符冲突**规则（raw id 本身含 `\|` 怎么办）；选"规范化 JSON"则被 F-60 的实测否证 | F-60、F-72 |
| G-09 | **真实数据里同字段两个命名空间**（F-37/F-38）无口径处置：`product_id` 既是目录 `1001` 又是雪花 `2096441909987262465` | F-37、F-38 |
| G-10 | **`category_id`/`brand_id` 在真实链路缺失**（F-39/F-40）⇒ 任何依赖它们 join 100% 的验收判据在当前数据上不可达 | F-39、F-40 |
| G-11 | **`_sk`/`raw_*_id` 列在 DDL 里不存在**：设计要求"维表同时保留 `raw_*_id`"（F-72/F-73），当前 5 份 DDL 零 `_sk`、零 `raw_` 列 | §4.4、F-04 |
| G-12 | **两份同名 `V1__init_mall.sql` 内容完全相同**（历史已记录），键列口径"哪份生效"靠路径而非内容 | `docs/项目实施进度与任务看板 V2.2.md:424`（逐字见该行"两份同名…字节完全相同…内容比对零判别力"） |
| G-13 | **指导书已换代到 V2.4**（读取窗口内出现），本报告只依据 V2.2 及之前 | §12 |

---

## §6 候选方案

> 说明：以下为**方案草案**，不是已实施。每条都给出"证据支持度"。**本轮不实现、不排序为结论**，仅列取舍供裁决。

### 6.1 轴 A：算法实现位置

| 方案 | 内容 | 支持的证据 | 反对/代价 |
|---|---|---|---|
| **A1 单语言实现 + 薄桥接** | 算法只在一侧实现（Scala，进 `spark-jobs`）；Java 侧不做实现，需要键时读 Scala 已算好的列，或通过契约 JSON 只做**校验** | `contract-specs/README.md:44`「每个程序校验自己的输出」；F-02（Java 侧现状零实现，改动面为 0） | 与 F-72 逐字要求「Java 与 Scala 分别实现，但必须读取同一测试向量」**冲突**；Java 侧若将来需要独立算键（采集/维度初始化，见 F-73「Java（采集/维度初始化）」）则无路 |
| **A2 双语言同算法 + 同一向量文件** | 两侧各写薄实现，同一份 JSON 向量文件，逐向量对账测试（照抄 F-62…F-67 模板） | F-72、F-73 逐字要求；F-62…F-67 已有成熟模板；F-61 已有"位级可复现"参照 | 需要一次性把 F-50…F-58 六类跨语言风险点全部定死，否则"同向量"会在真实数据上破裂 |
| **A3 双语言 + 运行时互相校验** | 两侧都算，落库时比对，不一致即 fail-closed | 与 `WarehouseNamespace` 的 fail-closed 风格一致（`docs/acceptance/p1-04-namespace-20260911/`） | 增加一条跨程序数据依赖，与 `contract-specs/README.md:43`「禁止跨程序 Java 依赖」的**精神**需先裁决边界 |

### 6.2 轴 B：哈希输入口径

| 方案 | 内容 | 支持的证据 | 反对/代价 |
|---|---|---|---|
| **B1 拼接字段**（`source_code + "\|" + entity_type + "\|" + UPPER(TRIM(raw_id))`） | 与设计/计划逐字一致 | F-72、F-73 逐字给出该式；无第三方依赖、可逐位复现 | 需定义分隔符转义；`UPPER/TRIM` 的跨语言口径必须先定（G-07） |
| **B2 规范化 JSON**（键排序后序列化再哈希） | 与 `payload_hash` 的"字节保真"思路对称 | 直觉上"结构化更稳" | **被实测否证**：F-60 记录 Spark `to_json` 键序/空白与源行不同；且要额外定义数字/小数格式（F-56） |
| **B3 原始字节直哈希**（不做规范化） | 最省事、最快 | 与 `payload_hash`（F-60/F-61）同构 | 大小写/空格差异会算出不同键 ⇒ 直接违反 F-72「大小写」向量与 §4.6 真实数据（`product_id` 两命名空间） |

**本报告倾向**：B1（有逐字依据），但**必须**连同 G-07/G-08 一起定。

### 6.3 轴 C：大小写/编码/分隔符/空值口径

| 子问题 | 候选 | 证据 |
|---|---|---|
| 大小写 | ① `uppercase(Locale.ROOT)`（Java）+ Scala 侧显式 `toUpperCase(Locale.ROOT)`；② 两侧统一走 ASCII-only 大写；③ 不做大小写归一 | ① 有 F-50 正面样本；② 与 Spark `UPPER` 的 ASCII 语义更接近；③ 与 F-72「大小写」向量冲突 |
| 编码 | UTF-8 显式（Java `StandardCharsets.UTF_8`；Scala `getBytes(StandardCharsets.UTF_8)`） | F-52 两个正面样本都显式指定了 UTF-8 |
| 分隔符转义 | ① 禁止 raw id 含 `\|`（不合法即 DQ）；② 长度前缀（`len:value`）；③ 转义 `\|` → `\\\|` | 三者证据都不足；**待裁**（G-08） |
| 空值 | ① `TRIM` 后为空/null → **不生成键，进 DQ/reject** | F-72 逐字：`空 raw id 不生成代理键，进入 DQ/reject` |
| 与 unknown 哨兵的关系 | `-1` 仍是维度 unknown 哨兵；代理键必须**永不为 `-1`**（设计"清除符号位 + 0→1"已可保证）；且**不得**再出现"剥前缀后可能等于 -1"的路径 | F-14、F-72 |
| 输出表示 | ① 清除符号位后的 63 位正数存 `BIGINT`；② 完整 64 位存 `BIGINT`（会为负）；③ 存 16 位小写十六进制 STRING（需改 DDL） | DDL 全为 `BIGINT`（F-19…F-22）⇒ ③ 需迁移；F-72 逐字倾向 ① |

---

## §7 向量集草案（22 条 ≥ 要求的 16 条）

- **性质**：草案。`material`/`sha256` 由 `raw/draft-vectors.ps1` 计算并落盘 `raw/draft-vectors.tsv`（22 行）。
- **重要限定**：算式采用**设计 §3.4 的拼接式**（F-72）。**分隔符/大小写/裁剪的最终口径未裁（G-07/G-08）** ⇒ 下表的 `sha256` 是"**若按当前该式实现**"的期望值，**不是**契约值，**不得**被当作已冻结向量。
- **覆盖**：UUID、雪花、纯数字、前缀 ID、空串、仅空白、`null`、超长 256、非 ASCII、非 ASCII+空格、大小写、前后空格、同 raw 不同 source、同 raw 不同 entity、前导零、可解析为负数、含分隔符、多段分隔符、含冒号。

| ID | 类别 | source_code | entity | raw_id | normalized | material | sha256（前 16 hex） |
|---|---|---|---|---|---|---|---|
| V01 | UUID | `mock-mall` | `user` | `89a6db2c-9ecb-4be0-a24b-9f697da00686` | `89A6DB2C-9ECB-4BE0-A24B-9F697DA00686` | `mock-mall\|user\|89A6DB2C-…` | `21f71a4b7c67bfae` |
| V02 | 雪花 19 位 | `mock-mall` | `user` | `2098607948334395394` | 同 | `mock-mall\|user\|2098607948334395394` | `49c62368e41015b5` |
| V03 | 纯数字 | `mock-mall` | `user` | `123` | 同 | `mock-mall\|user\|123` | `2e01499376d22ec1` |
| V04 | 前缀数字 | `mock-mall` | `user` | `U000065` | 同 | `mock-mall\|user\|U000065` | `d30271ac8944a9b3` |
| V05 | 纯数字（目录商品） | `mock-mall` | `product` | `1001` | 同 | `mock-mall\|product\|1001` | `59fb5eddea80aee6` |
| V06 | 前缀数字（订单） | `mock-mall` | `order` | `O00000001` | 同 | `mock-mall\|order\|O00000001` | `9733fba9ffefe626` |
| V07 | 前缀小写（**须与 V04 同值**） | `mock-mall` | `user` | `u000065` | `U000065` | 同 V04 | `d30271ac8944a9b3` |
| V08 | 首尾空格（**须与 V04 同值**） | `mock-mall` | `user` | `␣␣U000065␣␣` | `U000065` | 同 V04 | `d30271ac8944a9b3` |
| V09 | 非 ASCII | `mock-mall` | `user` | `用户一` | 同 | `mock-mall\|user\|用户一` | `0fc11177a278e09d` |
| V10 | 超长 256 | `mock-mall` | `user` | `A`×256 | 同 | `mock-mall\|user\|A…` | `1745107ddb75a7ab` |
| V11 | 非 ASCII + 空格 | `mock-mall` | `product` | `␣商品-壹␣` | `商品-壹` | `mock-mall\|product\|商品-壹` | `d0291c643b444c49` |
| V12 | UUID 大写（**须与 V01 同值**） | `mock-mall` | `user` | `89A6DB2C-…` | 同 | 同 V01 | `21f71a4b7c67bfae` |
| V13 | 空串 | `mock-mall` | `user` | `""` | — | **不生成** | — |
| V14 | 仅空白 | `mock-mall` | `user` | `"␣␣␣"` | — | **不生成** | — |
| V15 | `null` | `mock-mall` | `user` | `null` | — | **不生成** | — |
| V16 | 同 raw 不同 source（**须与 V04 不同值**） | `other-src` | `user` | `U000065` | 同 | `other-src\|user\|U000065` | `000c353bbb99e5d4` |
| V17 | 同 raw 不同 entity（**须与 V04 不同值**） | `mock-mall` | `product` | `U000065` | 同 | `mock-mall\|product\|U000065` | `9fe3c7ef8588b7c9` |
| V18 | 前导零 | `mock-mall` | `user` | `0007` | 同 | `mock-mall\|user\|0007` | `dba4f8e25f0f6aea` |
| V19 | 可解析为负数 | `mock-mall` | `user` | `-1` | 同 | `mock-mall\|user\|-1` | `d8ed9597fa74d7cd` |
| V20 | 含分隔符 | `mock-mall` | `payment` | `P-1001` | 同 | `mock-mall\|payment\|P-1001` | `ebb96b76ccf07992` |
| V21 | 多段分隔符 | `mock-mall` | `refund` | `R-1003-A` | 同 | `mock-mall\|refund\|R-1003-A` | `71b299b03a95bbc9` |
| V22 | 含冒号（生成器规范 ID 形） | `mock-mall` | `user` | `user:U000001` | `USER:U000001` | `mock-mall\|user\|USER:U000001` | `dba849c78c65da6c` |

**机械自检（`raw/draft-vectors-console.txt`）**：`V04 vs V07: material_same=True 期望=SAME => PASS`；`V04 vs V08: …PASS`；`V01 vs V12: …PASS`；`V04 vs V16: material_same=False 期望=DIFF => PASS`；`V04 vs V17: …PASS`。

**未覆盖（已知）**：含 `|` 的 raw id（转义口径未定，G-08）；`NaN`/`Infinity`；RTL/emoji；组合字符（NFC/NFD 差异）；超过 256 字符。以上列 §10。

---

## §8 反熵声明

1. **本报告没有产生第二套事实集**：所有数字均出自 `raw/` 下本会话自写脚本的输出，且 §3 做了三重对账（`1732/1732/1732`）与零命中正面控制。**没有**从看板或裁决文件里"转录"任何未经本会话实测的数字（唯一例外在 §4.13 F-78 与 §4.12 F-72/F-73/F-74/F-75，均**标注为引用**并给出 file:line）。
2. **没有产生重复 owner**：本报告**不新建**契约、**不修改** `contract-specs/**`、**不新增**第二处 `IdCodec` 或代理键实现。§7 的向量草案**只**存在于本目录，**不是**契约、**不是**测试夹具。
3. **没有制造新的裸锚点**：本报告全部路径引用**带完整路径与文件名**；§11 的"待裁问题"均为编号项，不引入无文件名的裸引用标签。若总控采纳任一候选并新增契约制品，须按 F-71 同时申报裸锚点规则与 `VERSION` 升版口径。
4. **历史证据文本未被改写**：本报告只在 `docs/acceptance/p2-03-surrogate-key-20260912/` 内新增文件；已存在的一切文件（含看板、裁决、契约、源文件、DDL）**逐字节未改**。窗口内 `warehouse/ddl/00-ods.sql` 等文件的变动由**其他泳道**造成（§12），不是本报告所为。
5. **没有被删除/迁移的旧 owner**：本轮是只读取证，未收敛任何重复实现（`IdCodec` 是否退役属 §6 轴 A 的裁决内容，本报告不动它）。

---

## §9 不得声称

以下说法在本轮证据下**均不成立**，任何下游文档不得引用本报告去支持它们：

1. 不得声称 P2-03 **已实现**、**已验证**、**已通过**或**已冻结**——它仍是 `TODO`（看板 `:222`）。
2. 不得声称 Java/Scala **两侧已经同值**——两侧根本没有成对实现（F-02、F-09、F-41…F-44）。
3. 不得声称"全仓库**只有** `IdCodec` 一处代理键逻辑"——正确表述是"**在 447 个 java/scala 文件、27 个模式、三重对账下未发现**其他 `_sk`/`rowkey`/`snowflake`/MD5/SHA-1/DigestUtils 形态实现"（F-01…F-08）。
4. 不得声称 `HASH64` **已实现**或**已知其位宽**——它只是一份源画像里的字符串声明（F-41、F-42）。
5. 不得声称现有 `IdCodec` **已通过**跨源/跨实体唯一性验证——本报告只做了**语义推论**（G-04/G-05），未实测。
6. 不得声称 §7 向量是**契约向量**或**期望值已冻结**——它们是草案，依赖未裁的 G-07/G-08。
7. 不得声称"真实数据里 `category_id`/`brand_id` **一律**缺失"——正确表述是"在**本轮测得的 2 个真实文件（2684 行）**里缺失，且在**全部 47 个 `landing/events/*.jsonl`** 里只有旧夹具出现过"（F-39）。
8. 不得声称本报告做过 E2/E3，或做过任何编译、单测、`spark-submit`。
9. 不得声称 §12 列出的窗口内被改文件"内容未变"——本报告只做了**重读确认**（关键列逐字一致），没有字节级前后对照基线。

---

## §10 未取证清单

| 编号 | 未取证 | 为什么 |
|---|---|---|
| U-01 | `IdCodec` 对 `U000065` 与 `P000065` 是否**真的**得到同一 BIGINT | 需运行 Scala/Spark（E2/E3），本轮禁跑；G-04/G-05 仅为**语义推论** |
| U-02 | Scala 侧 `normalize` 与 Spark SQL `REGEXP_EXTRACT` 在**全部**向量上是否逐条等价 | 需 E2 |
| U-03 | 任何 `HASH64` 输出的位宽/符号/取值分布 | 无实现可测 |
| U-04 | 两侧 `UPPER`/`TRIM` 在土耳其语 locale、全角空格、`\u00A0`、`\u3000` 上的实际差异 | 需 E2（可用 JVM 直接验证，但本轮禁跑测试） |
| U-05 | §7 中 8 条"须同值/须不同值"关系的**实现级**验证 | 只做了 material 字符串比较（E1），未做算法级 |
| U-06 | 含 `\|` raw id 的转义口径是否有仓库内既有先例 | 未普查分隔符转义模式 |
| U-07 | F-78（契约 README 记的 16 位大写）与本报告 F-77（64 位小写）**是否同一算法口径** | 未读该 16 位的历史测量上下文；仅记录读数 |
| U-08 | 本 README 自身的 sha256（唯一仍不能自指的一项） | 写作当时不能自指；由总控用 `Get-FileHash -Algorithm SHA256 README.md` 重算，或读 `raw/final-readme-hash.txt`（**该项曾在成文后被更正**：初稿把 `draft-vectors.*` 三个哈希也列为"未取证"，现已补登记，见 §2.4 更正记录） |
| U-09 | `analytics-server` 的 MySQL 迁移与业务表中是否存在与数仓代理键**同名同义**的列 | 只核到 `AUTO_INCREMENT`（`SourceRegistryMigrationMySqlIT.java:220`，语义不同）；全量列语义未逐表核 |
| U-10 | `category_id`/`brand_id` 缺失的**根因**（生成器不打 vs 采集丢弃） | 本轮只测"落地区文件里有没有"，未追上游代码路径 |
| U-11 | 47 个 `landing/events/*.jsonl` 的逐文件 sha256 与行数 | 只做了字段计数（F-39），未做全量指纹 |
| U-12 | 指导书 V2.4（窗口内新增）对 P2-03 是否改口径 | 未读 |
| U-13 | `payload_hash` 的 oracle `385dee5b…` 是否可复现 | 未跑 `P2ProbeSpec`（禁跑） |
| U-14 | 集群档/大行数档下的键唯一性 | 本机与集群均未测 |
| U-15 | `P2ProbeSpec.scala` 的 sha256，以及 `SqlTemplateSpec.scala` 修订版新增内容对本报告任何事实的影响 | 前者只需 `Get-FileHash`（本轮未算，仅为省一次往返）；后者在 §12.6 发现修订版后**未再逐行核读全文**（只核了 `IdCodecSpec.scala`） |
| U-16 | §12.6 之后是否还有第三轮并发写入 | 本报告的最后一次观测点是 `2026-09-12 13:11:14`；此后 `spark-jobs/**`、`synthetic-data-generator/**` 仍可能继续被写 |
| U-17 | `scripts/check-bare-anchors.ps1` 与 `scripts/contract-bare-anchors.allowlist.txt` 的内容与所属泳道 | 二者在 `13:11:14` 之前新出现（§12.6.1），**本报告未读**；它们与待裁问题 7（D-065 裸锚点规则）直接相关 |
| U-18 | `WarehouseNamespaceSpec.scala` 在 `13:11:14` 时的 sha256 与行号 | 它新进入 `M` 列表；本报告只在窗口内读过它（F-66），未在修订后重读 |
| U-19 | `README.md` 自身、`raw/draft-vectors.*`、`raw/deliverable-fingerprints.tsv` 的 sha256 | 自指环（§2.4、§12.8）；本报告只声称"§2.2/§2.3/§2.4 登记的 32 个三元组经自检一致"，**不声称**这几个文件的哈希 |
| U-20 | `docs/项目完整实施指导书 V2.4.md` 是否已改 P2-03 的算法/范围/验收口径 | **本报告未读 V2.4**（它在窗口内才出现，§1、§12.10）；权威入口已迁 V2.4（commit `370a090`） |
| U-21 | `raw/verify-raw-fingerprints.ps1` 与 `raw/verify-raw-fingerprints-console.txt` 的 sha256 | 自指环（§12.8）：脚本内嵌被校验的三元组，登记自身会导致哈希振荡。只登记其规模（`6798 B / 67 行` 与 `149 B / 2 行`） |

---

## §11 下一步与待裁问题

**下一步（依赖裁决）**：①总控就 TQ-01…TQ-14 出裁决；②裁决后由 P2-03 实施泳道按 A1/A2/A3 之一写实现 + 向量文件 + 逐向量对账测试（照 §4.11 模板）；③实施必须在 P2-01 收口后开工（当前 `spark-jobs`/`analytics-server` 为在飞热模块，见 §12）。

**待裁问题（逐条）**：

- **待裁问题 1（实现位置）**：选 §6.1 的 A1 / A2 / A3？若选 A1，如何处置 F-72 逐字要求"Java 与 Scala 分别实现"？若选 A2，谁做 owner？
- **待裁问题 2（旧实现处置）**：`IdCodec`（F-09…F-13）是**退役**（由 `SurrogateKeys` 取代，F-74）还是**保留为过渡**？其"剥前缀"语义与 G-04/G-05 的命名空间丢失，是否需要在退役前先加负向守卫？
- **待裁问题 3（material 形式与分隔符）**：选 B1/B2/B3？若 B1，raw id 含 `|` 时的口径（禁止 / 转义 / 长度前缀）取哪个（G-08）？
- **待裁问题 4（大小写与裁剪口径）**：`Locale.ROOT` 还是 ASCII-only？`TRIM` 去哪些字符（仅 ASCII 空格 vs Unicode 空白）？两侧分别用哪个 API（G-07）？
- **待裁问题 5（空值处置）**：`null`/空串/仅空白**是否都**走"不生成 + DQ/reject"？还是 `null` 与空串分档？落到哪张 reject 表（`dwd_reject_record`？）？
- **待裁问题 6（输出表示与 DDL）**：确认"清除符号位 + 0→1"的 63 位正数存 `BIGINT`？是否保留 `raw_*_id` 列（需改 5 份 DDL，G-11）？是否新增 `_sk` 列还是复用现有 `*_id` 列（F-19…F-22 现状是复用）？
- **待裁问题 7（契约制品口径）**：新增制品命名（`surrogate-key.v1.json`？）与 `VERSION` 升版（`1.3.0 → 1.4.0`？按 F-68 加法→minor）；是否同时申报 D-065 的裸锚点规则（F-71）；`status` 初值是 `DRAFT` 还是直接 `FROZEN`（F-69 显示只有 `warehouse-namespace` 冻结，且冻结需两条门槛）。
- **待裁问题 8（Java 侧 `HASH64` 归属）**：`identityPolicy.surrogate` 是**唯一 owner** 还是与 P2-03 契约**双 owner**？若双 owner，如何避免 F-54/F-55 那类"同名不同算法"？
- **待裁问题 9（真实数据冲突）**：F-37/F-38 的 `product_id` 双命名空间如何处置（同源内分命名空间？归一到目录 id？把雪花商品视为未知维？）；F-39/F-40 的 `category_id`/`brand_id` 缺失是否**阻塞** P2-06 的"join 100%"验收（F-75）。
- **待裁问题 10（向量集边界）**：§7 的 22 条是否冻结为 `≥16` 的正式集？§7"未覆盖"5 类是否必须补入？"同值/不同值"关系是否需要**逐条**成为契约 `vectors` 里的显式字段（现模板 F-62 只存输入与期望）？
- **待裁问题 11（跨语言验证方式）**：对 F-50…F-58 的 6 类风险，是否要求两侧各写"反向样本"测试（如故意用无 Locale 的 `toUpperCase()` 必须失败）？
- **待裁问题 12（取证级别）**：本报告停在 E1；P2-03 实施后要补的 E2/E3 最小集是什么（E3 是否必须在**真实 landing**上跑一次端到端 join）？
- **待裁问题 13（命名空间与多源）**：`source_code` 取自何处（`source_registry.source_code`？`runtime_profile`？）——注意看板 `:426` 已记"`source_registry.source_code` 在 spark 提交路径上无任何读取点"，这会直接决定 Scala 侧 material 里的 `source_code` 从哪来。
- **待裁问题 14（`event_id` 边界）**：P2-03 是否**确认不触碰** `behavior_id`/`event_id`（F-24…F-26），把"同值"要求**限定**在 `user_id`/`product_id`/`order_id`/`category_id`/`brand_id` 五个 BIGINT 键上？
- **待裁问题 15（权威迁移，最高优先级）**：commit `370a090` 已"权威入口迁 V2.4"（§12.10），而本报告只依据 V2.2 及之前。请裁决：①本报告 §4 事实是否**无需**按 V2.4 重做（我的判断：无需，因为 §4 只引源代码/契约/数据，均带 sha256）；②§6/§7 的规格性依据是否需要按 V2.4 重做（我的判断：**需要**，若 V2.4 改了代理键算法/范围/验收口径）；③`docs/项目完整实施指导书 V2.4.md` 是否授权本泳道或后续实施泳道读取。
- **待裁问题 16（D-065 载体已存在）**：`scripts/check-bare-anchors.ps1` 与 `scripts/contract-bare-anchors.allowlist.txt` 在 `13:11:14` 前新出现（§12.6.1），与待裁问题 7 直接相关。请裁决：新增代理键契约制品的裸锚点登记是**直接复用**这两个既有载体，还是另立？**本报告未读这两个文件**（§10 U-17），不预设形态。
- **待裁问题 17（工作树基线 vs commit 基线）**：本报告与看板登记的 `044e2e76`（窗口期 HEAD）之间，又落了 3 个 commit（`370a090`/`84de179`/`d3fd74e`）。请裁决：P2-03 后续实施泳道的"基线"是取 `d3fd74ed`（当前 HEAD）还是取本报告各文件的 sha256（工作树快照）？两者不等价——例如 `IdCodecSpec.scala` 在 `HEAD` 与工作树间还有 6 增 2 删的未提交差异（`git diff --stat` 只读实测，§12.6）。

---

## §12 读取窗口与指纹

### 12.1 窗口定义

| 项 | 值 | 来源 |
|---|---|---|
| 窗口起点（本目录首个 raw 制品落盘） | `2026-09-12 12:57:06` | 目录内文件创建时间；`raw/scan-surrogate-key.ps1` 12:57:06 |
| 文件扫描完成 | `2026-09-12 12:57:41` | `raw/scan-surrogate-key-console.txt` 落盘时刻 |
| 早期 git 快照 | `2026-09-12 12:58:08.590 +08:00` | `raw/window-timestamp-early.txt` |
| 晚期 git 快照（窗口终点） | `2026-09-12 13:01:47.749 +08:00` | `raw/window-timestamp-late.txt` |
| 窗口内被改文件的确切 mtime（晚于快照） | `LocalSchemaInitJob.scala` `13:01:06`；`warehouse/ddl/00-ods.sql` `13:01:45` | §12.3 |
| 重读确认（写 README 前最后一次核对） | `2026-09-12 13:03:44` | 见 §12.5 |

**诚实标注（判据强度）**：早期快照落在扫描**之后**约 27 秒，因此它不是真正的"扫描前"状态；窗口内被写文件的判定**只依据文件 mtime**（§12.3），不依据该快照。

### 12.2 git 状态（首尾）

| 项 | 早期 | 晚期 |
|---|---|---|
| `git rev-parse HEAD` | `044e2e76e5bb68258724fd9a0949f2ecb25f0c89` | **同左（HEAD 未变）** |
| `git status --porcelain` 条数 | 20 | 19 |
| 首次落盘于 raw | `raw/git-head-early.txt` / `raw/git-status-early.txt` / `raw/git-head-detail-early.txt` | `raw/git-head-late.txt` / `raw/git-status-late.txt` / `raw/git-head-detail-late.txt` |

窗口内 `git status` 的差异（`Compare-Object` 实测）：

| 变化 | 条目 |
|---|---|
| 新增（晚期有、早期无） | ` M warehouse/ddl/00-ods.sql`、`?? docs/acceptance/m1-9-second-adapter-20260912/RULINGS-20260912.md`、`?? "docs/项目完整实施指导书 V2.4.md"` |

⇒ 窗口内**确有并发写入**：`warehouse/ddl/00-ods.sql` 被改、指导书升到 V2.4、M1-9 新增裁决文件。三条都不是本报告所为。

### 12.3 落在读取窗口内被改动的文件 ⇒ 相关事实可能已失效

| 文件 | mtime | 落入窗口 | 本报告对它的依赖 | 处置 |
|---|---|---|---|---|
| `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` | `2026-09-12 13:01:06` | **是**（窗口 12:57:06–13:01:48） | §4.4 F-19/F-20/F-21 的 DWD/DIM 键列、§4.4 归纳 | **重读确认**（§12.5）：`:49-58` 与 `:62-63`、`:77-83` 逐字与首次读取一致 ⇒ 键列结论**维持**，但**标注为"读取窗口内被并发改动后重读所得"** |
| `warehouse/ddl/00-ods.sql` | `2026-09-12 13:01:45` | **是** | ODS 层键列口径（§4.4 F-23 的背景） | 本报告**未**用该文件做任何 ODS 结论（F-23 引的是 `IdCodecSpec`）；**列为可能失效**，不据此下结论 |
| `docs/项目实施进度与任务看板 V2.2.md` | `2026-09-12 12:57:18` | **是（窗口起点后 12 秒）** | §1 的任务行与泳道登记行 | 重读确认：`lines=433`、`L222` 与 `L433` 逐字与首次读取一致 ⇒ 维持 |

### 12.4 引用文件指纹表（56 项，全量见 `raw/cited-file-fingerprints.tsv`）

| 分类 | 数量 |
|---|---|
| 引用文件总数 | 56 |
| 路径缺失 | **0** |
| 落在读取窗口内被改（`WINDOW_WRITE`） | **3**（见 §12.3） |
| 其余（`STABLE`） | 53 |

关键指纹择要（完整 56 行见 `raw/cited-file-fingerprints.tsv`，列 = path / exists / size / mtime / sha256 / in_window / class）：

| 文件 | 字节 | mtime | sha256 |
|---|---|---|---|
| `spark-jobs/…/sql/IdCodec.scala` | 见 tsv | 见 tsv | 见 tsv |
| `contract-specs/specs/warehouse-namespace.v1.json` | 见 tsv | 见 tsv | 见 tsv |
| `tests/golden-dataset/events/golden-20260901.jsonl` | 18430 | 2026-09-10 16:52:06 | `2351bcc35e04ccd278638f07247bc37e9c4d402cc4736cd2a4d4b70f4232b11c` |
| `landing/events/r9-m1-123006.jsonl` | 18430 | 2026-09-11 11:06:44 | `2351bcc35e04ccd278638f07247bc37e9c4d402cc4736cd2a4d4b70f4232b11c` |
| `landing/events/2026091211.jsonl` | 631946 | 见 tsv | `9cc66dd4630ac7d700c5f1728f3466b2ec35b1b93495f6d29887d2e065aae24d` |
| `landing/events/2026091210.jsonl` | 584644 | 见 tsv | `416401fd61a5c74cc3cfde95bcd6c454f0ce330a4efe9d2c40fdfdb7c1df926c` |

> 说明：本表刻意**不**把 56 行全抄进来（避免出现"报告里的数字与 raw 文件不一致"的第二事实源）。**凡本报告引用的每一个文件，其 mtime/size/sha256 都在 `raw/cited-file-fingerprints.tsv` 里逐行可查**；本节只给判定结果与 3 个例外。

### 12.5 重读确认（窗口被改文件的复核读数）

| 文件 | 重读时刻 | 字节 | 行 | sha256 | 结论 |
|---|---|---|---|---|---|
| `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` | `2026-09-12 13:03:44` | 18324 | 337 | `4c5327204768b1f044a08cd9eab904ce296b336c7baff7b6431d72237f87ce34` | `:49-58`、`:62-63`、`:77-83` **逐字未变** ⇒ F-19/F-20/F-21 维持 |
| `warehouse/ddl/00-ods.sql` | `2026-09-12 13:03:44` | 6178 | 133 | `2bebe55ff09580443937f945a31dacf856bbbd898fb7c1b953dc6c7465816863` | 未用于结论 |
| `docs/项目实施进度与任务看板 V2.2.md` | `2026-09-12 13:03:44` | 189771 | 433 | `8b32960484345b1af29450fc52c04b416c8dc1a627c2fef9f92ab0cf0a41efa6` | `L222`/`L433` 逐字未变 ⇒ §1 引用维持 |

**依赖这些文件且**可能**失效的结论**：无（三项均经重读确认或未用于结论）。若总控在更晚时刻复核发现上述 sha256 已变，则 §4.4 的 F-19/F-20/F-21 与 §1 的任务行引用应一并按"未取证"处理。

### 12.6 窗口之后（post-window）的并发写入 —— 本报告成文期间又发生了一轮

**发现时刻**：`2026-09-12 13:09:48`（本报告 §12 成文后复核 `git status` 时发现）。

**触发本条的证据**：`git status --porcelain` 在 `13:09:48` 显示 `spark-jobs` 下 **6 个已跟踪文件为 `M`**，而它们**都不在** §12.3 的 3 个窗口内写文件之列：

```
 M spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala
 M spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala
 M spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala
 M spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala
 M spark-jobs/src/test/scala/com/graduation/analytics/SqlTemplateSpec.scala
 M warehouse/ddl/00-ods.sql
```

**逐文件指纹（本会话实测，`2026-09-12 13:09:4x`）**：

| 文件 | 字节 | 行 | mtime | sha256 | 相对读取窗口 |
|---|---|---|---|---|---|
| `spark-jobs/…/sql/IdCodecSpec.scala` | 5786 | 114 | `2026-09-12 13:09:43` | `f16fe4cf88ca30e095615415cac3a8eff1609b5d0609364eef66a9fa531c868c` | **窗口后**（且**晚于**本报告首次成文） |
| `spark-jobs/…/test/…/SqlTemplateSpec.scala` | 12319 | 254 | `2026-09-12 13:03:34` | `fc8695b84ac5220ea07ddb9a750701b74f92d9e532f6ccd39b084f74936a46ac` | 窗口后 |
| `spark-jobs/…/main/…/job/LocalSchemaInitJob.scala` | 18324 | 337 | `2026-09-12 13:01:06` | `4c5327204768b1f044a08cd9eab904ce296b336c7baff7b6431d72237f87ce34` | **窗口内**（= §12.3/§12.5 第 1 项，sha256 与 13:03:44 重读**仍一致**） |
| `spark-jobs/…/main/…/job/EventOdsLoadJob.scala` | 9277 | 162 | `2026-09-12 12:55:48` | `3bf1e72f087abb0700160513456e1becf811dd634ca4bf8baf52cf71c9707252` | 窗口**前**（未用于结论） |
| `spark-jobs/…/main/…/sql/OdsLoadSql.scala` | 14480 | 282 | `2026-09-12 12:53:40` | `52b979474d45b13c73192706b869511c6cf022004458e9b97499e2306bc8ff46` | 窗口**前**（未用于结论） |
| `warehouse/ddl/00-ods.sql` | 6178 | 133 | `2026-09-12 13:01:45` | `2bebe55ff09580443937f945a31dacf856bbbd898fb7c1b953dc6c7465816863` | **窗口内**（= §12.3/§12.5 第 2 项，sha256 **仍一致**） |
| `spark-jobs/…/main/…/sql/JsonObjectSlicer.scala`（`??` 未跟踪） | 7638 | 191 | `2026-09-12 12:40:35` | `be6118252392e8e33f9f4e32acdef2b1d47d07fc142a26720da729bf17deb00a` | 窗口前（F-52/F-60 引用它） |
| `spark-jobs/…/main/…/sql/OdsV2Columns.scala`（`??`） | 8116 | 183 | `2026-09-12 12:45:42` | `7b223766fd746050c8ee1f8bc0ebc8e1c19a7a53e13a142f1c11941e73934c05` | 窗口前（未用于结论） |
| `spark-jobs/…/test/…/P2ProbeSpec.scala`（`??`） | 7590 | 138 | `2026-09-12 12:39:22` | （未算，见 §10 U-15） | 窗口前（F-61 引用它） |

**对本报告事实的影响（逐条判定）**：

| 受影响事实 | 原引用版本 | 判定 | 依据 |
|---|---|---|---|
| F-16（`directCast` 正则） | 旧版 `:52` | **维持**（新行号 `:56`） | 在 `13:09:43` 修订版中逐字复现 |
| F-17（7 个模板端点） | 旧版 `:71-79` | **维持**（新行号 `:75-82`）；"5 个"改为 **7 个** | 逐字复现，且 `OdsLoadSql.behaviorFromLanding` 现带 3 参 |
| F-18（归一化次数 = 3） | 旧版 `:83` | **维持**（新行号 `:87`） | 逐字复现 |
| F-23（ODS 保留原文） | 旧版 `:105-108` | **维持**（新行号 `:109-112`） | 逐字复现 |
| F-23a/F-23b/F-23c | — | **新增** | 修订版新增的注释与断言 |
| §4.9（F-45…F-49） | `mall-simulator` / `synthetic-data-generator` | **不受影响** | `git status` 显示这两个程序**无任何改动** |
| F-52/F-60/F-61 | `JsonObjectSlicer.scala` / `P2ProbeSpec.scala` | **维持（但标注为未跟踪+窗口前文件）** | 二者均**未出现在** `13:09:48` 的 `M` 列表中；mtime 均早于窗口起点 |

**诚实标注**：`IdCodecSpec.scala` 的修订版是**本报告首次成文之后**由**其他泳道**写的（`13:09:43` > 本报告写入时刻）。上表"维持"仅指"修订版中该断言仍逐字存在"，**不代表**我验证过它编译/通过（**无 E2**）。若总控复核时该文件已再次变化，则 F-16/F-17/F-18/F-23/§4.3 的**行号**应重新对齐。

### 12.6.1 复核时的最终观测点：`2026-09-12 13:11:14` —— **HEAD 已前进，仓库仍在并发演进**

在 §12.6 之后再做一次快照时（`raw/postwindow-timestamp.txt` = `2026-09-12 13:11:14.680 +08:00`），发现两件必须登记的事：

| 观测 | 值 | 证据 |
|---|---|---|
| **`HEAD` 已前进** | 窗口内为 `044e2e76e5bb68258724fd9a0949f2ecb25f0c89`；`13:11:14` 为 `d3fd74edb72844a8c917e1134991fcc5fe3adf4b` | `raw/git-head-early.txt` / `git-head-late.txt` vs `raw/git-head-postwindow.txt` |
| `git status` 条数 | 窗口首 20 → 窗口末 19 → `13:09:48` 约 24 → `13:11:14` **29** | `raw/git-status-early.txt` / `-late.txt` / `raw/git-status-postwindow.txt` |
| 新增的 `M`（相对 §12.6 的 6 个） | `spark-jobs/…/test/…/WarehouseNamespaceSpec.scala`、`synthetic-data-generator/…/adapter/ProductPage.java`、`SecondMallHttpAdapter.java`、`TargetCheckResult.java`、`…/engine/MallApiGenerationEngine.java`、`…/web/dto/GeneratorApiDtos.java` | `raw/git-status-postwindow.txt` |
| 新增的 `??`（相对窗口末） | `scripts/check-bare-anchors.ps1`、`scripts/contract-bare-anchors.allowlist.txt`、`docs/acceptance/p2-07-source-prefix-20260912/`、`docs/acceptance/ct-batch-20260912/raw/` | 同上 |

**为什么这对本报告重要（不改结论，但要如实说）**：

1. **本报告的对照基线是"工作树快照"而非"某次 commit"**。所有 file:line 引用都写明 mtime + sha256（§12.4、§12.6.1 表），因此即使 `HEAD` 前进，复核者仍能按 sha256 精确锁定我读到的版本。
2. **§4.1 的普查语义需要限定**：`raw/source-files-inventory.txt`（447 文件）与全部命中计数对应的是**窗口时点的工作树**（`raw/window-timestamp-early.txt` = `12:58:08`），**不是** `d3fd74ed` 这个 commit 的内容。`spark-jobs/**` 在窗口后仍在被写（§12.6），故"447 / 1732"应表述为"**窗口时点的 447 个 java/scala 文件**"。
3. **`WarehouseNamespaceSpec.scala` 新进入 `M` 列表**（`13:11:14`）⇒ §4.11 F-66（该测试类读同一份 JSON 并断言 `vectors.size() >= 15`）的**行号**可能在复核时已变；**结论（存在双语言对账测试）不受影响**，但行号引用按 §12.4 的 sha256 基线有效。
4. **`scripts/check-bare-anchors.ps1` 与 `scripts/contract-bare-anchors.allowlist.txt` 的出现，直接命中 §11 待裁问题 7**（D-065 裸锚点规则的落地载体）。**本报告未读这两个文件**（见 §10 U-17），因此不能在待裁问题 7 里替总控预设它们的形态。

> **反面提醒（防误读）**：本节的"仓库在演进"**不**削弱 §4 的任何事实——它们各自带 sha256。它削弱的是**任何把本报告数字当成"当前最新状态"的读法**。请总控按 sha256 对齐版本后再裁决。

### 12.10 只读 git 提交流证据：本泳道**是被一次 commit 正式登记开工的**，且窗口内又落了 3 个 commit

证据文件：`raw/git-log-timeline.txt`（21 行，2257 B，sha256 `3df4f1dda2c371230d632fd4a927ebafa070a2fd31b459c2912da9c0f83e74eb`；抓取时刻 `2026-09-12 13:11:49`；命令 `git log -12` **只读**）。

| 提交 | 作者时间 | 与本次取证的关系 |
|---|---|---|
| `571c9f8` | `12:35:15` | P2-01 开工登记 + 指令入库 + R1 在产 jar 实测事实（窗口前） |
| `bd92f65` | `12:41:31` | M1-9 ② 修复轮：判据复核器 v3（窗口前） |
| `ed8c531` | `12:45:21` | M1-9 ② 修复轮交付并验收（窗口前） |
| `46842d3` | `12:46:26` | P2-01 裁决 D-059/D-060（窗口前） |
| `274de91` | `12:47:59` | M1-4 S5 开工登记（窗口前） |
| `53faea2` | `12:50:34` | CT-0（F-31）勘误落地（窗口前） |
| `bf6dce9` | `12:50:55` | 看板 F-31 状态更新（窗口前） |
| `892ea90` | `12:55:48` | CT 批次裁决与施工单入库（窗口前） |
| **`044e2e7`** | **`12:57:19`** | **`裁决 D-065 … ＋ CT 批次扩范围 ＋ P2-03 只读取证泳道开工登记`** ← **本泳道就是在这次 commit 里登记开工的**；它同时是 §12.2 的"窗口起点 HEAD" |
| `370a090` | `13:04:16` | **`…裁决 D-066（H1 改规格：指导书迭代 V2.4，撤回就地追加）…；权威入口迁 V2.4`** ← **窗口内** |
| `84de179` | `13:04:38` | M1-9 ② 修复轮-2 开工登记 ← **窗口内** |
| `d3fd74e` | `13:07:48` | M1/P1 状态复核 + M1 里程碑补记；转 M2 P2-07 只读取证 ← **窗口内**（当前 HEAD） |

分支（`raw/git-log-timeline.txt` 末尾，`git status --porcelain=v1 --branch` 只读）：`## remediation/r1-boundary...origin/main`。

**三条对总控有直接价值的结论**：

1. **本泳道的授权链可核**：任务行 `看板:222` + 泳道登记 `看板:433` 之外，还有 commit `044e2e7` 的提交信息逐字写明"P2-03 只读取证泳道开工登记"。⇒ 本报告的"只读取证 + 规格草案、不实现"边界**有第三方物证**，不只是我在自述。
2. **"窗口起点 HEAD"在窗口开始的前 1 秒就被写入了**：`044e2e7` 的作者时间是 `12:57:19`，而目录首个 raw 制品落在 `12:57:06`、HEAD 快照落在 `12:58:08`。⇒ §12.2 的"HEAD 未变"只覆盖 `12:58:08 → 13:01:47` 这 3 分 39 秒，**不覆盖整个窗口**。这是 §12.2 判据强度的**进一步削弱**，已如实登记。
3. **权威入口在窗口内被迁移**：`370a090`（`13:04:16`）逐字写"权威入口迁 V2.4"。本报告 §1 的权威顺序**仍按 V2.2 及之前**陈述（因为 `docs/项目完整实施指导书 V2.4.md` 在窗口内才出现、且我未读），⇒ **§11 待裁问题 15** 专列此项。**这是本报告最需要总控注意的口径风险**：若 V2.4 改了 P2-03 的算法或范围，本报告的 §6/§7 草案需按 V2.4 重做。

> **同时登记的反向事实**：`d3fd74e`（`13:07:48`）的提交信息包含"转 M2 **P2-07** 只读取证"⇒ 与 P2-03 并行的还有 P2-07 泳道在跑。本报告**不**涉及 P2-07，也不对其作任何判断。

### 12.7 §4.1 分模块计数的可复跑命令（对应 F-02/F-03）

```powershell
# 工作目录 = 仓库根；若不在根目录，把下面的 raw\ 换成
# docs\acceptance\p2-03-surrogate-key-20260912\raw\

# 文件数（447）：读 inventory 的 side+module 两列
Get-Content raw\source-files-inventory.txt | ForEach-Object { $p = $_ -split "`t"; "$($p[0]),$($p[1])" } |
  Group-Object | Sort-Object Name | ForEach-Object { "{0}`t{1}" -f $_.Name, $_.Count }

# 命中数（1732）：per-file 汇总的 side+module 两列（124 行）
Get-Content raw\grep-surrogate-key-per-file.tsv | ForEach-Object { $p = $_ -split "`t"; "$($p[0]),$($p[1])" } |
  Group-Object | Sort-Object Name | ForEach-Object { "{0}`t{1}" -f $_.Name, $_.Count }
```

实测输出（`2026-09-12 13:0x`）：

```
# 文件数
main,analytics-server 156   main,mall-simulator 65   main,spark-jobs 35   main,synthetic-data-generator 70
test,analytics-server  70   test,mall-simulator 13   test,spark-jobs 17   test,synthetic-data-generator 21
total=447
# 命中行数（有命中的文件数，非命中次数）
main,analytics-server  35   main,mall-simulator 11   main,spark-jobs  5   main,synthetic-data-generator 19
test,analytics-server  30   test,mall-simulator  3   test,spark-jobs  8   test,synthetic-data-generator 13
perfile_rows=124
```

> **口径警告（防熵）**：`raw/scan-surrogate-key-console.txt:6-15` 给出的 8 个数（465/120/60/304/374/60/147/202）是**命中次数**，**不是**文件数；本报告 §4.1 用 **F-02=文件数、F-03=命中次数**两个编号分开登记，避免两个数被当成同一事实。

### 12.8 自检产物与"不能自指"的哈希

**自检（本报告唯一的机械判据，E1 级）**：`raw/verify-raw-fingerprints.ps1`（**只读**，无参数，`pwsh -NoProfile -File` 即可跑）逐一比对 §2.2/§2.3/§2.4 逐字登记的 35 个 `(字节, 行, sha256)` 三元组与磁盘现状。实测结论（`raw/verify-raw-fingerprints-console.txt` 逐字）：

```
registered=35 pass=35 fail=0 missing=0
VERDICT=PASS（README §2.2/§2.3/§2.4 逐字登记的 35 个制品三元组与磁盘现状全部一致）
```

> **为什么自检脚本自己不被自检**：脚本正文里逐字内嵌了那 35 个三元组，把它自己也登记进去就构成自指环（改脚本→改登记→再改脚本，哈希振荡）。因此 `raw/verify-raw-fingerprints.ps1` 与 `raw/verify-raw-fingerprints-console.txt` 的 sha256 **本报告不声称**；总控复核时用 `Get-FileHash` 就地重算即可（二者实测为 `6798 B / 67 行` 与 `149 B / 2 行`，见 §10 U-21）。这是**有意的取舍**，不是遗漏。

> **这条自检能证明什么、不能证明什么**：它能证明"**README 里写的数与磁盘上的文件一致**"（防抄错、防手改文件后忘记更新）。它**不能**证明这些数**是对的**——即不能证明扫描模式选得对、不能证明我没漏掉某个文件、不能证明结论成立。**不声称**通过任何验收（§9）。

**不能自指的哈希**：只剩 `README.md` 自身，以及**每次运行都会自重写的** `raw/deliverable-fingerprints.tsv`（原因见 §2.4 的防熵说明）。这两项**必须**由总控在复核时重算（见 §10 U-08、U-19）。复核命令（只读）：

```powershell
Get-ChildItem -Recurse -File docs\acceptance\p2-03-surrogate-key-20260912 |
  ForEach-Object { "{0}`t{1}`t{2}`t{3}" -f $_.FullName, $_.Length,
    (Get-Content $_.FullName -ErrorAction SilentlyContinue).Count,
    (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower() }
```

### 12.9 §12 总结（给总控的一句话）

> 本报告的全部结论基于 **`raw/` 内自写脚本的输出**；读取窗口内与**窗口之后**均检测到**其他泳道**对 `spark-jobs/**`、`warehouse/ddl/**` 的并发写入（§12.3、§12.6）；凡被这些写入触及的事实，本报告均已**重读复现**或**明确降级为未取证**（§10）。**没有任何证据表明本报告改动了本目录之外的任何文件。**
