// ============================================================================
// P2-01 临时探针占位文件（已停用，保留以避免使用删除类文件操作）
//
// 原内容：`P2ProbeSpec`（probe-01…probe-06）—— 实测 Spark 3.5.1 下
//   read.json(闭合 schema) / read.json(推断) / read.text 三条路径对「payload 原始字节」
//   的行为，用于在实现前确定 payload_json 的取值路径（spec §5.6 R3 / 未取证 U1）。
//
// 结论（原始输出见 D:\Develop_code\graduation-lane-backup\p2-01\logs\probe-0*.log）：
//   1. 闭合 schema 读法把 payload 解析成 31 个标量，原始对象字符串**已不存在**；
//      `to_json(payload)` 是重序列化产物，SHA-256 与源行不同 ⇒ **不能**用作 payload_json。
//   2. `read.text` + `JsonObjectSlicer.slice` 切出的子串为 122 UTF-8 字节，
//      SHA-256 = 385dee5b723e23f0778d6726140ced55b5e9f5aa45f79d42869b1f753e260445，
//      与本机 PowerShell 独立算出的 oracle **逐字节相同** ⇒ 这条路径成立。
//   3. `_metadata.file_path` 可用（`file:/D:/…/golden-20260901.jsonl`），
//      而 `input_file_name()` 在相对路径输入下返回空串 ⇒ landing_file 必须用 _metadata。
//
// 已固化成的正式断言：
//   - 纯 JVM：`com.graduation.analytics.sql.JsonObjectSlicerSpec`（A5/A5b/A4b，不建 SparkSession）
//   - 端到端：`com.graduation.analytics.OdsV2ByteFidelitySpec` / `OdsV2EdgeCaseSpec`
//             （P2-01 起改为走真 spark-submit，E3 级证据）
//
// 本文件不含任何可执行代码。
// ============================================================================
