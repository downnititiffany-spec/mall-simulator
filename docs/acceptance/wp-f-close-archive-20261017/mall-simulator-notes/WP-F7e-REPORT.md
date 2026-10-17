# WP-F7e 同数据复跑验收报告（总控亲跑，2026-10-17 08:22 巡检轮）

## 执行概要
- 重建产物：spark-jobs jar（08:37，BUILD SUCCESS，含 WP-F7d DwsSql 修复 + WP-F9a AdsSql 留存）与 platform-app jar（08:40，含 V14 等 F9a 平台侧改动）均总控亲跑重建；平台以新 jar 重启（pid 135517），启动期 metric 库 Flyway **实迁移 13→14**（执行 1 个脚本，`ads_user_retention_m` 落活库），HTTP 200。
- 复跑方式：档案 scale-vm-b（id=3）test 全过→activate（档案 2 转 DISABLED）；对已消费批次 3 走显式重算入口 `POST /admin/pipeline-runs/recalculate`（理由已审计落库），得 run 6（目标快照 S20260918_6）。
- 结果：**run 6 于 QUALITY_CHECK 被 BLOCKING 门阻断，未发布**（08:41:34→08:48:19，约 405 秒）。前六阶段全 SUCCESS：WAIT_LANDING 9995 / INIT_SCHEMA 45（F7c 为 43，+2 = 留存正式+暂存两表）/ LOAD_ODS 10035 / BUILD_DWD / BUILD_DWS / BUILD_ADS 全过。

## 已验证（不因发布阻断而打折）
1. **DWS 退款修复在生产链生效**：质量门 `ADS_CATEGORY_SALE_RECONCILE` 的取证 detail 自带 DWS 侧期望值——expectedSale=**365160.30**、expectedNet=**273243.80**，与 WP-F7d 预言逐分一致（gmv/aov 不变、退款 91916.50 由净额反推一致）。即 dws_trade_day 在万级同数据下已产出修复后数值。
2. **留存真实数据探针通过（暂存层）**：新快照暂存 `ads_user_retention__staging/S20260918_6` 产出行 dt=20260918、cohort_user_count=**100**、retained/retention D+1/D+7/D+30 全 **NULL**。总控独立复算（直读 DWD parquet）：dt=20260918 distinct user=**100**、max(dt)=20260918——horizon 外严格 NULL 与 cohort 逐项吻合。
3. **原 ACTIVE 链未污染**：metric_snapshot 实查 _4=ACTIVE（档案 2）、_5=ACTIVE（档案 3），无 _6 行；跑后已复激档案 2 为 ACTIVE，平台 HTTP 200。
4. 另一 failed 规则 `ADS_STAGING_SNAPSHOT_ISOLATION` 为 WARN（非阻断），与 run 5 唯一 failed 规则同型（旧快照暂存分区留存），属既有模式。

## 阻断根因（实证）
`ADS_CATEGORY_SALE_RECONCILE` BLOCKING failed：ads_category_sale actualNet=**238087.15**（旧行扇出口径）vs expectedNet=273243.80，差 35156.65 与 WP-F7c 扇出多计数分毫不差。run 5 时 DWS 与品类同错、对账假过；DWS 修复后该门必然阻断——WP-F7d 对 `AdsSql.categorySale` 的递延（跨品类分摊属别层）在全链发布口径下不可维持。总控 DWD 实证：退款单 302 笔中跨 ≥2 品类 64 笔（退款 27743.20），(category,order) 去重 Σ=119659.70 仍不精确；整单退款必须恰好分摊一次。
**总控裁定**：立 **WP-F7f**——categorySale 整单退款按行金额比例分摊 + 分级最大余数校正（逐单逐分相等），规约见 `WP-F7f.md`，已派 agy（Flash）。F7f 验收后由总控再走 recalculate 复跑（run 7）核对预言值与留存发布行，WP-F7e 届时关账。

## 状态
WP-F7e：**未关账（发布阻断，根因已实证并立包）**。DWS 修复与留存计算已在生产链实证；余项 = F7f 关账后复跑发布 + 发布层留存行/指标逐项对账。

## 第二轮（2026-10-17 09:37 巡检，总控亲跑）——关账
- 重建 spark-jobs jar（09:42 CST，含 WP-F7f 双修复；平台未重启，spark-submit 逐 run 读档案 jar 路径）；档案 3 test 全过→activate→recalculate 批次 3 得 **run 7**，目标快照 S20260918_7。
- run 7 八阶段全 SUCCESS（约 504 秒）：WAIT_LANDING 9995 / INIT_SCHEMA 45（24s）/ LOAD_ODS 10035（62s）/ BUILD_DWD 8508（110s）/ BUILD_DWS 100（44s）/ BUILD_ADS 161（68s）/ QUALITY_CHECK 12（57s）/ PUBLISH_METRIC 322（118s）。
- 预言全中：gmv 365160.30 / net 273243.80 / aov 362.26 / 隐含退款 91916.50；品类表 Σsale=365160.30、Σnet=273243.80 逐分勾稽；留存发布行 cohort=100、D+1/7/30 全 NULL 与独立复算吻合。
- 收尾：_7 在档案 3 作用域 ACTIVE、_5 转 ARCHIVED；_4（档案 2）未动；档案 2 已复激 ACTIVE。
- 状态：**WP-F7e 关账**（2026-10-17 09:5x 巡检）。证据：/tmp/f7e2_poll.log、/tmp/f7e2_verify.sh 输出、PROJECT_STATUS 同名节。
