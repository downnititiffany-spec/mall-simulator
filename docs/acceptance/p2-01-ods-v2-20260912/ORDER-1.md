# P2-01 实施泳道指令 ORDER-1（2026-09-12，总控下达）

> 状态：**已定稿，待派发**。派发时机 = M1-9 ② 修复轮验收收口（E2 全绿 ＋ 复核器 v2 ＋ 人读）并提交之后
> —— 理由：**同一时刻只允许一个实施泳道**（构建资源争用会污染 E2 读数）。本文件是派发时**逐字**使用的指令正文。
> 依据（权威顺序）：① 冻结契约 ② 指导书 V2.2/V2.3 ③ 看板 V2.2 ④ 本任务专项：`docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md`（D-052…D-058）＋ `draft/p2-01-spec-draft.md`（施工规格）＋ `p2-01-readonly-evidence.md`（只读取证，2026-09-12）。

## 1. 任务

按 `draft/p2-01-spec-draft.md` §5.1「推荐口径」与 §5.2「改动面」，实现 **ODS v2 公共列 ＋ payload 原样保真（加法扩列、双写）**，并用 §5.3 的 **A1–A12** 十二条断言 ＋ §5.4 的正向对照设计做验收。级别：**E1 ＋ E2**（本泳道**不**跑真实链、**不**碰真实数仓、**不**碰运行中的三个服务）。

## 2. 边界（越界即停手报告，不得自行拍定）

**做**（D-052…D-058 已裁决）：
1. 只做加法：v1 的 9 个业务列 ＋ `trace_id` ＋ v1 `payload_*` 列全部保留（名字/类型/顺序不变）；新增 `raw_event_type`、`landing_file`、`payload_json`、`payload_hash` 四列（D-052 公共列 12 列；`event_time` 保持 `STRING`）。
2. 双写：`payload_*` 现行为填充不变（DWD/DIM/DWS/ADS 零改动仍可跑），同时新增 `payload_json`/`payload_hash`（D-054；`payload_json` 为 payload **唯一所有者**；`payload_hash` = SHA-256(UTF-8 原始字节) 小写十六进制，**不参与去重**）。
3. `payload_json` = 落地区 JSON 行中 `payload` 键对应对象的**原始文本**（原样字节；禁止解析后重排/美化/压缩）。
4. `source_system` 由**平台参数通道**注入（`JobCommandBuilder` 的 `extra` 通道，先例 `hiveDatabasePrefix`），取值来源 `source_registry.source_code`；**不得**信任行内值、**不得**依赖 `mock-mall.v1.json`（D-056/G-12）。
5. `landing_file` 取真实来源文件标识（候选实现 `input_file_name()`）；`source_file` 的常量 `'landing'` **必须消失或改真实值**，且 `source_file == landing_file`（D-057）。
6. 结构不匹配**只出"需重建"判定与计划**；执行必须是显式 `INIT_SCHEMA` 审计步骤；**禁止**任何静默 `DROP`／`DELETE`／`OVERWRITE`（D-054/D-052）。
7. 建立 ODS 公共列的**唯一所有者**（Scala 常量或制品），`LocalSchemaInitJob` 从中派生；`warehouse/ddl/00-ods.sql` 保持静态 SQL 文本，但由**对账门禁**钉住（D-052 §5.1-8）。
8. `items` 列**原样保留**（D-057）；`raw_event_type` 的映射规则**留 P5**（本泳道只加列，不定映射表）。

**不做**（明确排除，做了就是越界）：
- 源级数仓前缀/per-source namespace ⇒ 在册行 **P2-07**（D-058）⇒ **不得**改 `WarehouseNamespace.scala` 的命名空间语义；
- DWD 切到 `get_json_object(payload_json, …)` ⇒ **P2-04**；
- 契约文件（`contract-specs/**`、`VERSION`）任何写入 ⇒ CT-1/CT-2/CT-3/CT-0 由总控在 P2-01 **之后**成批处理；
- DWD 去重键改动 ⇒ 不动（D-055，补契约文字由 CT-2 承担）；
- 真实链/E3/E4、真实 MySQL/Hive 写入、集群动作 ⇒ 不在本泳道。

## 3. 硬约束（安全与纪律）

1. **R1（最高优先）**：运行中的平台按 `sparkJobJarUri` 指向的 jar 执行下一次真实运行。**该值不是配置文件项，而是数据库值**（`analytics_meta.runtime_profile.spark_job_jar_uri`）。总控已于 2026-09-12 只读实测：
   - `runtime_profile` id=1 `local-dev`、`type=LOCAL`、`status=ACTIVE`、`version=3`、`source_id=1`；
   - `spark_job_jar_uri` = `D:/Develop_code/GraduationProject/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`（**就是构建产物本身**）；
   - 该 jar 当前指纹：**234,038 B，mtime 2026-09-11 18:19:03，sha256 `F9E879AADEA71C9301B079FC70D714C3DEF6DE30902DB39FF92F428C596318A8`**；
   - `hive_database_prefix` = **NULL**（源级前缀尚未做，属 P2-07）；`source_registry` 当前**只有 1 行**：`source_code=mock-mall`、`status=ACTIVE`、`profile_version=1.0`、`profile_path=analytics-server/source-profiles/mock-mall.v1.json`（**该文件在盘上不存在**，属 P3-01 ⇒ 再次印证 G-12「不得依赖该文件」）。
   ⇒ 你的第一步：只用只读 SQL（`SELECT`）复核上述 jar 路径与 sha256；把该 jar **逐字节备份**到 `D:\Develop_code\graduation-lane-backup\p2-01\`，**收工前还原**并复测"路径/字节/sha256"三项全等，把前后对照写进报告。若实测值与我给的**不一致** ⇒ **停手报告**，不要凭猜测构建。
   注意：`mvn -o -f spark-jobs/pom.xml package` **会覆盖这个在产 jar**，因此还原是**强制步骤**，不是可选收尾。
   **R1 暴露窗口最小化协议（强制，逐步照做）**：平台在下一次真实运行时会用**当时盘上的那个 jar**，所以在产 jar 被替换的每一分钟都是真实暴露窗口。做法：
   (a) 先备份（原 jar → `…\p2-01\live-jar\spark-jobs-0.1.0-SNAPSHOT.jar`，记录三项指纹）；
   (b) 跑 E1 `package -DskipTests`（**只在需要验证"能打包"时跑**，不要反复跑）；
   (c) 构建成功后**立即**把新 jar 复制到 `…\p2-01\built\spark-jobs-0.1.0-SNAPSHOT.jar`（作为"E1 通过"的证据）；
   (d) **立即**用备份覆盖回 `spark-jobs\target\`，并复测三项指纹全等；
   (e) **E2（`test-compile scalatest:test`）不需要 jar**（scalatest 直接跑 classes），因此 E2 不构成暴露窗口——**不要**为了 E2 再跑一次 `package`；
   (f) 收工报告里给出：备份指纹 / 构建后（复制件）指纹 / 还原后指纹 三组数值＋还原校验结论。
2. **真实数仓只读**：`D:\Develop_code\GraduationProject\spark-warehouse`（含 `dw_ods`/`dw_dwd`/`dw_dim`/`dw_dws`/`dw_ads`/`probe_r613` 六个库）**绝不允许**被测试写入；测试一律用隔离 warehouse（如 `D:\Develop\tmp\spark-warehouse` 或 `spark.sql.warehouse.dir` 指向临时目录），并在报告里给出你实际用的路径。
3. **禁止删除类命令**：`Remove-Item`/`rm`/`del`/`erase`/`ri`/`git clean`/`git rm`/`mvn clean`/`robocopy /MIR` 一律不用。另：**任何仓库内 PowerShell helper 都不得取与内置别名同名的名字**（`rd`=Remove-Item、`gc`=Get-Content 等；PS 解析顺序「别名 > 函数 > cmdlet」）——本工作区已因此类遮蔽两次删除 33 个文件（F-37），必须避免。
4. **每改一个文件前**先复制一份到 `D:\Develop_code\graduation-lane-backup\p2-01\files\`；发现任何文件从工作区消失 ⇒ **立即停手报告**（不要试图"顺手修回来"）。
5. **禁 git 写**：不 `add`/`commit`/`push`/`stash`/`checkout`/`restore`（只读 `status`/`diff`/`log`/`show` 可以）。
6. **禁改**：`docs/**`、看板、`contract-specs/**`、`RULINGS.md`（证据与状态由总控写）。
7. **禁启停** 8090/8091/8092（三个进程正在运行，属用户在用系统）；**不碰** `mall_simulator`/`analytics_meta`/`analytics_metric`/`generator_meta` 四个库。
8. **同一时刻只跑一个 Maven**（本泳道独占，总控不会并发跑构建）。
9. 报告纪律：**未实测不写结论**；每条断言给"输入 → 期望 → 原始输出"；未做到的事写进**未取证清单**，不用"应该可以"代替证据。

## 4. 方法（TDD，红→绿，逐条留痕）

1. **先红**：按 §5.3/§5.4 为 A1–A12 各写断言，**先在改动前的代码/文本上跑一次**，把原始输出留档（`red-<A#>.txt`）。已知必红的两处：`spark-jobs/src/test/scala/.../SqlTemplateSpec.scala:39` 的 `'landing' as source_file` 断言、以及 payload 投影类断言；**预期保持绿**的：`WarehouseNamespaceSpec.scala:156-168` 的 `statements.size should be(37)`（加列不改语句数——若它变红，说明你的改法与"只加列"不符，回退重做）。
2. **再绿**：改实现直到全部绿；每条断言必须**配正向对照**（"零命中/字段为空/无改动"类断言没有正向对照＝无效，本项目已因缺正向对照出现过假绿灯）。
3. **命令**（E1/E2，逐字用，报告里给原始输出）：
   - E1：`mvn -o -f spark-jobs/pom.xml package -DskipTests`
   - E2：`mvn -o -f spark-jobs/pom.xml test-compile scalatest:test`
   - 平台侧（若你改了 `JobCommandBuilder`/`PipelineService`）：`mvn -o test -f analytics-server/pom.xml -pl platform-app -am '-DforkCount=0'`，环境 `SPARK_DRIVER_MEMORY='512m'`
4. 改动面以 §5.2 为起点，但**以"最小加法"为准**：能不改的文件就不改；每多改一个文件都要在报告里给理由。
5. 交付前自检：`git status --porcelain` 的改动文件清单 ＋ 每个文件的 `sha256`（改动前后），并声明"未提交"。

## 5. 交付物（报告格式固定）

1. 开工登记：任务、范围、计划改的文件、**平台实际使用 jar 的解析结果**；
2. 红检原始输出（每断言一文件，路径＋字节＋sha256）；
3. 绿检原始输出（E1/E2 全量日志，落 `D:\Develop_code\graduation-lane-backup\p2-01\`，报路径＋字节＋sha256；含 `Tests run: x, Failures: x, Errors: x, Skipped: x` 原文行）；
4. 改动清单：文件 → 改动性质 → 前后 sha256；
5. A1–A12 逐条：断言原文 / 期望 / 实测 / 证据文件名（含正向对照）；
6. jar 还原对照：备份值 vs 还原后值（路径/字节/sha256 三项全等）；
7. **未取证清单**（没做的事、没测的路径、没验证的假设）；
8. 「不得声称」自述：明确写出本次**不能**声称的事（至少包含：真实链未跑、契约未改、per-source namespace 未做、payload 字节保真只在 E2 级验证过）。

## 6. 总控收到报告后的动作（不由泳道执行）

独立复核：自己重跑 E2 ＋ 重建 jar 后**再次还原并校验** ＋ 人读 A1–A12 证据 ＋ 对账门禁反向对照（故意改坏一行确认门禁会红）⇒ 追加 `docs/acceptance/p2-01-ods-v2-20260912/README.md` 验收记录 ⇒ 提交推送 ⇒ 再排 CT 批次（CT-1/CT-2/CT-3 一次升版 `1.3.0 → 1.4.0`）。
