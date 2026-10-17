# WP-F 与收官原始报告归档

接收日期：2026-10-17。来源：用户提供的 `毕设验收报告归档-WP-F与收官.zip`，由云电脑 `~/workspace/projects/mall-simulator-notes/` 中的选定报告与幸存临时日志打包；原报告此前未进入任何GitHub分支。

原ZIP SHA-256：`6e2abcd0a3c31c2336449e147fd9800cba15075942907d38ee94578549ca47ab`。

本目录保留ZIP中的65个普通文件，逐文件SHA-256全部与解压原件一致。ZIP共67项，其中2项为目录；不能把67项称为67份报告。详见 [接收回执](ARCHIVE-RECEIPT.json) 与 [原件哈希清单](MANIFEST-SHA256SUMS.txt)。未导入任何凭据文件；接收前完成目录越界、符号链接及token/私钥/明文口令候选扫描。

本目录通过 `.gitattributes -text` 保留原始换行与字节，避免Windows检出自动转换破坏哈希。原报告的Markdown双空格换行、空日志等均保持原样，不为消除空白检查提示而格式化原件。

## 查阅入口

- [FULL-PROGRAM 原纲领](mall-simulator-notes/FULL-PROGRAM.md)
- [CRITICAL-PATH 原登记](mall-simulator-notes/CRITICAL-PATH.md)
- [WP-CLOSE 收官盘点原报告](mall-simulator-notes/WP-CLOSE-INVENTORY-REPORT.md)
- [WP-F9c 原报告](mall-simulator-notes/WP-F9c-REPORT.md)
- [WP-F7e 同数据复跑原报告](mall-simulator-notes/WP-F7e-REPORT.md)
- [合并后main的后端原始日志](archlogs/v25tests-mainclose20261017/default-analytics-server.log)
- [商城原始日志](archlogs/v25tests-mainclose20261017/default-mall-simulator.log)
- [生成器原始日志](archlogs/v25tests-mainclose20261017/default-synthetic-data-generator.log)

历史状态中的 `notes/<文件名>` 引用，对应本目录 `mall-simulator-notes/<文件名>`；原件中的云端 `file:///home/...` 路径与 `/tmp/...` 引用原样保留，不能在其他机器直接使用。本README提供可移植入口，不改写原始报告。

## 证据边界

1. 这是原件接收与归档，不是本轮重跑云端实服、数据库或Spark生产任务。报告中的“计划”“草案”“完成标记”和指令仅为历史材料。
2. 收官报告包含A/B两份裁定草案；最终正式裁定以 [PROJECT_STATUS](../../PROJECT_STATUS.md) 的2026-10-17收官登记为准：采用B，维持限定验收。归档不使被驳回的A草案生效。
3. 用户已明确说明：云电脑多次更换导致早期 `/tmp` 原始运行日志绝大部分不可恢复。不能用报告中的数字生成替代日志，不能把该历史缺口写成“原始日志完整”。
4. 幸存main日志可独立汇总为analytics 1400（F0/E0/S2）、mall 26、generator 134，总收集1560、实际通过1558、跳过2。它仅支持当时default档；不补证其他真链或Spark执行。
5. `archlogs/agy_wpcloseinv_loop_outer.log` 为0字节原件，保留仅为完整性，不作为执行成功依据。
6. 本轮本地修复、独立测试与浏览器模拟检查另见 [修复报告](../../verification/results/REVIEW-FIXES-20261017.md)，不同时间与环境的结果不合并成一次完整验收。
7. 用户云端notes目录原有107个文件，本包只含选定的60份文稿及5份日志，并非整个目录。包中未包含WP-N1/N2及WP-F1原报告；这些早期工作包目前仍按PROJECT_STATUS登记引用，不能宣称对应原件已随本包交付。

真实LLM、共享HMS/远程集群、正式库迁移授权等剩余项见 [剩余验收清单](../../verification/RESIDUAL_ACCEPTANCE.md)。
