# 会话交接单（2026-09-07）

> 写给下一会话：**先读本文件 + `docs/remediation-status.md` + `docs/项目整改实施指导书 V1.0.md`（1370 行，只读 §27 执行顺序 / §28 自检 / §29 进度表），再动手。**

## 1. 项目一句话

毕业设计《基于 Spark 大数据平台和智能分析模型的电商用户行为分析系统》：仓库 `D:\Develop_code\GraduationProject`，
权威 = 设计文稿 V2.2 + 整改指导书 V1.0（后者定义了"真实数仓链路"的整改路线，当前主线）。

## 2. 当前 Git 状态（关键！）

- 分支：`remediation/r1-boundary`（整改主线；基线 tag `v0.9-protype-baseline`）
- 最近提交：`b589134`（R1 平台代码迁移完成，81 个 Java 文件）
- **工作树有未提交改动**：
  1. `analytics-server/connection-ingestion/.../auth/AuthInterceptor.java`：**第 30 行语法损坏**（白名单替换正则把括号改坏，报 `')' expected`）——**接手第一件事就是修它**
  2. `analytics-server/platform-app/src/main/resources/` 迁移目录刚从父工程 resources 移入
  3. 本次会话新建：`PlatformDataSources/PlatformBeans/MetaFlywayInitializer`、`scripts/remediate/`（拆分/迁移脚本）

## 3. 正在进行的任务（R1 收尾：platform-app 运行接线）——精确定位

**目标**：platform-app 能独立启动，Flyway 在 `analytics_meta` 建平台表，`/api/v1/health` 公开 200。

**已排查并修复的问题（勿重复踩）**：
| 问题 | 解法 |
|---|---|
| Mapper 未扫 | AnalyticsApplication 已加 @MapperScan（6 个 mapper 包） |
| SqlSessionFactory 缺失 | 删除迁移带入的 `ai/sql/PrimaryDataSourceConfig` + `ReaderDataSourceConfig`（与平台 metaDataSource 双 @Primary 冲突）；SqlExecutor 注入改为 `@Primary` 类型注入 + 可选 `@Qualifier("metricReadDataSource")` |
| EventClock bean 缺失 | 已加 `config/PlatformBeans`（Asia/Shanghai） |
| Flyway No migrations | `analytics-server/src/main/resources/db`（三套迁移）→ 已移入 `platform-app/src/main/resources/db`（classpath 必须） |
| health 被拦截 401 | AuthInterceptor WHITELIST_PATHS 需含 `/api/v1/health`——**正在改第 30 行时引入语法错误** |

**接手步骤**：
1. 修 `AuthInterceptor.java` WHITELIST_PATHS（当前应是 3 项：auth/login、metrics/health、api/v1/health + 别破坏逗号/括号，`List.of(...)`）
2. `mvn -q install -f analytics-server/pom.xml -DskipTests`（父 reactor，勿单模块）
3. 后台启动：`java -jar analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar`（用 run_in_background 防 120s 工具超时；探测命令单独发，`Start-Sleep 20` 后再 curl）
4. 验证：health 200；`mysql -e "USE analytics_meta; SHOW TABLES;"`出现平台表（ingestion/pipeline/quality/decision/sys_user/ai_audit…）；`platform-r*.log` 有"迁移完成: 执行 N 个脚本"
5. 通过后提交（提交信息按整改书风格，如 `feat(remediation/R1): platform-app boots with analytics_meta migration`）

**预期后续坑**（本次已识别，无需重新排查）：
- AuthInterceptor 拦的 controller 还有 `/api/v1/auth/**` 之外的业务端点——白名单只加 health；其余走登录（后续接 AuthService）
- 业务 controller（Analysis 等）依赖的 Service 可能因 meta 库空表而 500——属正常（R7 才接 metric 库数据）
- `meta_ds` Hikari 密码默认 `meta_app_pw_2026`（init-three-dbs.sql 创建，生产必须改）

## 4. 已完成里程碑（本次会话 + 前期）

| 里程碑 | 提交 |
|---|---|
| 阶段 1-10 原型 + 111 测试全绿 + 单进程打包 + UI 主题（整改前基线） | 41 次提交（主线历史） |
| R0 冻结：docs 备份 12 份 / tag / 分支 / README 原型标注 | 843d1b2 |
| R1 骨架：三库 + 四账号 + analytics-server 六模块 + 迁移拆分 | 71189d1 |
| R1 平台代码迁移（81 文件） | b589134 |
| UI 设计系统（ui-ux-pro-max Data-Dense Dashboard: Navy #1E40AF + Fira） | 1f83702 |

## 5. 环境事实

- MySQL 8.0（root/123456 本地模式）：三库 `mall_business`/`analytics_meta`/`analytics_metric` + 账号 mall_app/meta_app/metric_pub/metric_read（`warehouse/migrations/init-three-dbs.sql`）；旧库 mall_simulator(+test) 仍用于商城兼容期
- 端口：商城 8090、平台 8091、前端 dev 5173
- 常用命令模板（本会话惯例）：`$env:MALL_DB_PASSWORD='123456'`；`$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Duser.language=en'`；mvn 全量测试/打包前先设 MYSQL_PWD
- 验证技巧：长命令改 `run_in_background` + `job_output`；输出先 `Select-Object -First N` 截断；日志 `-Tail K` 看关键行

## 6. 下一步路线（整改书 §27 顺序）

1. **R1 收尾**：platform-app 启动+迁移验证（见 §3）→ 三数据源接线（meta 主 + metric 读）→ 边界测试（禁止平台引用 `mall.*`：`grep -r "com.graduation.mall" analytics-server` 必须为空；平台停止不影响商城）
2. **R2 RuntimeProfile**：实体/表/Service/API + Local/HDFS LandingStorage + JobSubmitter
3. R3 采集 manifest+字节偏移 → R4 全主题 ODS/DWD → R5 真实 DWS/ADS（漏斗/热度修复）→ R6 异步流水线 → R7 Hive→MySQL 发布+看板只读 MetricStore → R8 AI/权限 → R9 验收
4. 进度登记都在 `docs/remediation-status.md`（改前先备份，规则见整改书 §2）

## 7. 纪律提醒（整改书 §25/§28）

- 每阶段以验收证据判完成（黄金数据链优先）；禁止用 Java 内存统计冒充正式链路
- 提交自检：输入输出、有无读商城库/JSON 捷径、有无新硬编码、成功+失败测试、页面可溯源 snapshotId
- 文档（整改书/设计文稿/论文）修改前必须备份到 `docs/backups/`