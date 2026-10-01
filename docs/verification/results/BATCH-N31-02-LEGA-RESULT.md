# N31-02 腿 A 结果：连续小链与页面对账

- 日期：2026-09-29
- 状态：**PASS（仅当前 WSL 单节点与隔离 3307 环境）**
- 运行根：`target/v25-it/n3102iso_20260929_095610/`
- 批次计划：`docs/verification/batches/BATCH-N31-02-CONTINUOUS-CHAIN-PLAN.md`
- 边界：未格式化既有 HDFS；只使用本 run 的 landing 前缀和 `n3102iso_20260929_095610_*` 隔离 schema；未接触 3306。

## 结果摘要

以 99 行受控 `source-a-e3` 输入先钉输入指纹和独立 oracle，再执行 Flume→HDFS→平台摄取→Spark ODS/DWD/DWS/ADS→隔离 MySQL 发布→API→Vue 页面。链路身份记录为 `sourceId=1 / batchId=1 / runId=1 / snapshotId=S20260918_1`。摄取 accepted 99、quarantine 0；Spark 管线最终 SUCCESS；14 项指标以容差 0.0005 完成 ADS 导出、指标服务库、API、渲染页面四方核对，14/14 相等。

页面证据 `evidence/page-overview-a8.png` 显示 14 项指标及诚实的数据时效横幅：业务日期 2026-09-18，检查日 2026-09-29，滞后 11 天。该滞后是样本数据的业务时点，不代表前端资源仍是旧版本。

## 9 月 19 日静态资源的根因与处置

当时 Vue 源码与 `web/dist` 已包含时效横幅，但正在运行的 Spring Boot jar 内仍是 2026-09-19 构建的前端静态资源。平台由 jar 内 `platform-app/src/main/resources/static` 提供 SPA；单独运行 Maven `clean package` 不会自动执行 Vite 构建和 `web/dist` 复制。既有组合脚本 `scripts/build-web-and-package.ps1` 才包含“构建 web→同步静态资源→打包 platform-app”的完整发布步骤。本次只重建了发布静态包，没有改 Vue/后端业务源码。

- Vite：`npm run build` 成功，675 个模块。
- 静态资源同步后 `index.html` SHA-256：`476127c5091676499999faca1278d0f544a63fb88f31e9922a7bde74c685db98`。
- 旧 platform jar：`79abfc234e177aa85919b759bc07588dfea6136adb780795ec30127efe320df8`。
- 新 platform jar：`075de7f3839a1c417b5955942be2a0951bd830721a03d25d653db19775d09515`，83,579,422 bytes。
- jar 内新 index、Overview chunk 与时效横幅标记均已核验；91 个后端 class entry 逐项一致，变化仅在前端静态包。
- A8 浏览器实测新包可见横幅；14 个页面指标与快照标识 `S20260918_1` 一致。A9 对账 14/14 PASS。

## 证据与限制

- 核心证据：`evidence/driver-state.json`、`evidence/lineage-chain-a9.json`、`evidence/metric-four-way-compare-a9.json`、`evidence/page-evidence-a8.json`、`evidence/packaging-refresh-a8.json`、`evidence/page-overview-a8.png`。
- 首次 A8 页面解析器按显示标签映射失败，随后按真实页面标签校正临时验收映射并重跑成功；首次诊断文件和截图保留，没有被覆盖。
- 页面默认趋势区间为 2026-09-23 至 2026-09-29，而当前隔离快照业务日是 2026-09-18，因此趋势图区间为空是筛选范围不重叠，不是 Spark/ADS 发布失败。
- Maven 打包使用 `-DskipTests`；它只证明构建成功，不替代 Java 全量回归。浏览器与真实 WSL 单节点链路已按本腿判据实测，但不证明 REMOTE_CLUSTER、共享 HMS 或生产集群。

