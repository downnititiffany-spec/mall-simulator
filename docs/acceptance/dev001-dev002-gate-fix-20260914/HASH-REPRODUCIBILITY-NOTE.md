# DEV-001／DEV-002 泳道证据 —— 哈希复现口径说明（HASH-REPRODUCIBILITY-NOTE）

- 补记时间：2026-09-15 10:30 +0800（**纯证据说明 commit**：未改任何代码、runner、原 `REPORT.md`、原 `raw/**` 证据与指导书／设计文档）
- 基线 commit：`ffbd996278f5e990ed29751546f202af55881553`（DEV-001／DEV-002 泳道落库后的 HEAD）；本文件为该基线之上追加的第三个 commit 的内容，message 为 `docs(acceptance): clarify DEV-001 DEV-002 evidence hash reproducibility`
- **结论边界（写死）**：本说明**只解释 CRLF／LF 与 SHA256 的复核前提**，**DEV-001／DEV-002 的修复与关闭结论不变**，不改变任何测试或验收结论。

---

## 一、当前事实（仓库与检出策略，实测）

| 事实 | 实测值 |
|---|---|
| `core.autocrlf` | `true`（`git config --get core.autocrlf`；仓库**无** `.gitattributes`） |
| Git 库内 blob 行尾 | **LF**（入库时 CRLF→LF 归一） |
| Windows 工作区 checkout 行尾 | **部分文件 CRLF、部分文件 LF**（取决于入库前工作区字节） |
| 直接后果 | **同一逻辑内容在不同 checkout 策略下的字节级 SHA256 可能不同** |

本泳道 52 份文件的实测分布：**50 份**入库前工作区为 **CRLF**（blob 比工作区少 CRLF 字节，差异合计 **5,548 B**）；**2 份**入库前工作区即为 **LF**（blob 与工作区字节完全相同：`REPORT.md`、`raw/layer4-3307-guard-facts.txt`）。

---

## 二、原 manifest 的哈希口径

`raw/n4-evidence-manifest.txt` 中登记的 SHA256，**对应当时 Windows 工作区在 `core.autocrlf=true` 下检出（或直接写出）的文件字节**。

**它不是「跨平台 Git blob SHA256」。** 因此把原 manifest 的 SHA256 直接与「Linux checkout／`autocrlf=false` checkout」的工作区字节比对，对上述 50 份文件**必然出现差异**，而该差异**全部来自行尾（CRLF↔LF）**，内容差异为 0。

---

## 三、已完成的保真验证（本节全部为实测，未取证项不升级）

1. **差异范围**：52 份泳道文件中 **50 份**存在「仅 CRLF/LF」的 blob／worktree 字节差异，差异字节合计 **5,548 B**；**内容差异 = 0**（行一一对应，仅行尾不同）。
2. **往返保真（删除工作区文件后 `git checkout` 还原）**：在 `core.autocrlf=true` 的 Windows 工作区删除文件并 `git checkout -- <path>`，**重新得到原 manifest 登记的字节与 SHA256**。已验证 5 个样本：

| 样本 | 字节 | 还原后 SHA256（＝原 manifest 登记值） |
|---|---|---|
| `raw/n4-3306-readonly-recheck.txt` | 433 | `F9363A0163934DBF012F92102B71E947D7DC6CC6FBCE51D2CFBDEA54DEC3EDBA` |
| `raw/n4-mall-default-tier-maven.log` | 2513 | `EF0C31D80E702267F6A1D547847F5D1057A1C9476A15692725FBA6A9D84C7494` |
| `raw/n4-isolated-mall-newrunid.log` | 38276 | `43263CA1B68B3DE745D78018BD0CAE08B6FB3AFA75C20549F7843F1492035DDE` |
| `raw/n4-isolated-generator-newrunid.log` | 43095 | `F8BBE0254E48642BEE86A3344D53CB97D925C8B4E05C1E558278705933EB0844` |
| `raw/n4-3307-inventory-before.txt` | 712 | `1267BDAAC50B27EB8B1ADB1F1CEEB84BFFF3A0ECB098108884F87136829F87EC` |

3. **LF 原样文件的例外（本轮实测发现的边界，必须登记）**：`REPORT.md` 与 `raw/layer4-3307-guard-facts.txt` 入库前工作区即为 **LF**（字节与 blob 相同）。对这类文件，`autocrlf=true` 的 `git checkout` 会把工作区写成 **CRLF**：实测 `REPORT.md` checkout 后为 **47,289 B / `B9C9C47D108A6F88E3BA63094640F5EE305204F6F9140821B6B2CEA29D28DFC6`**，与登记的 **46,872 B / `6D53F5A5A51135615625BE1B56B580CAE15595C5ECC2AD697BF11E731579D29027`** 不同 —— 即 **checkout 不能复现这 2 份的登记字节**，正确复核方式见第四节 B。
   - 该 checkout 副作用**已还原**：`REPORT.md` 已按 blob 原始字节恢复为 **46,872 B / `6D53F5A5A51135615625BE1B56B580CAE15595C5ECC2AD697BF11E731579D29027`**，与 `HEAD:REPORT.md` blob **逐字节相同**（blob id `ea1bf615a1b393018eff9bf62a64f170f5cfbc62`）；还原后再次全泳道核对，**52/52 工作区字节与本说明及第二套清单登记值一致**。
   - 附带现象（不涉及内容）：`autocrlf=true` 下若把 blob 为 LF 的文件按 LF 写回工作区，`git status` 可能因 stat 缓存与「checkout 预期 CRLF」不一致而报 ` M`，但此时 `git diff` 为空、工作区字节与 blob 完全相同；执行一次 `git add -- <path>` 刷新 stat 后状态恢复干净，**blob 未变**（`REPORT.md` blob 前后均为 `ea1bf615a1b393018eff9bf62a64f170f5cfbc62`，未产生任何内容改动）。
4. **「无该差异」的文件同样登记**：
   - `REPORT.md`：46,872 B，SHA256 `6D53F5A51135615625BE1B56B580CAE15595C5ECC2AD697BF11E731579D29027`，blob id `ea1bf615a1b393018eff9bf62a64f170f5cfbc62`（blob 字节 = 工作区字节）
   - `raw/layer4-3307-guard-facts.txt`：912 B，SHA256 `31FBC2828E889E26DCFF305CFF26C842DCC7727EF9260968C1B18C1F3CD0DF55`，blob id `12f96a20a3113448ac12c1d10f950a67fa430135`（blob 字节 = 工作区字节）

---

## 四、跨平台复核规则（**不得仅凭哈希不等就判定证据被篡改**）

复核者**不得**仅因为「Windows manifest SHA256 ≠ Linux checkout SHA256」判定证据被篡改 —— 该差异已被证实为行尾（CRLF↔LF）差异、内容差异为 0。跨平台复核应优先使用以下任一方式：

- **A. 在 `core.autocrlf=true` 的 Windows checkout 中复算原 manifest**：与 `raw/n4-evidence-manifest.txt` 逐份比对 SHA256，本泳道 **50/52** 份应逐份一致（另 2 份按 B 复核）。
- **B. 对 Git blob／LF 规范化内容单独计算，并注明「Git blob/LF 口径」**：即对 `git cat-file blob <commit>:<path>` 的内容算 SHA256，或直接使用 Git 对象 id（`git rev-parse <commit>:<path>`，跨平台恒定、不受行尾策略影响）。**该口径不得与原 Windows worktree 哈希混为同一口径。**
- 两套口径的对应关系：
  - 50 份 CRLF 入库文件：原 manifest 的 `work_sha256` 可在 `autocrlf=true` 检出上复现；第二套清单的 `blob_sha256`／`blob_sha1` 可在任意平台复现。
  - 2 份 LF 入库文件：`work_sha256` 与 `blob_sha256` **相等**，任意平台用 B 方式均可复现。
- **第二套清单（可选产物，已生成）**：`raw/n4-evidence-gitblob-manifest.txt` —— 逐份登记 `work_bytes`／`work_sha256`（原口径）与 `blob_bytes`／`blob_sha1`／`blob_sha256`（Git blob/LF 口径），基线 commit `ffbd996…`，覆盖该 commit 内泳道全部 52 文件（本文件与第二套清单自身除外，因自指哈希不可登记）。**它不覆盖、不替代 `raw/n4-evidence-manifest.txt`。**

---

## 五、PROJECT_STATUS 变更范围与本次提交范围

- `docs/PROJECT_STATUS.md`：**只新增一条「证据复现口径」说明**（并在页首「最后更新时间」同步），**DEV-001／DEV-002 结论不变**，测试与验收结论不变。
- 本次提交只含 3 个路径：本文件（新增）、`raw/n4-evidence-gitblob-manifest.txt`（新增）、`docs/PROJECT_STATUS.md`（更新）。
- **未改动**：原 `REPORT.md`、原 `raw/n4-evidence-manifest.txt`、其余 `raw/**`、任何代码、runner、指导书 V2.8、项目设计文档 V2.5。
