# 阶段14 OPS_WEEKLY 空值汇总完整性修正（2026-10-10）

- 源码审阅发现：周报日趋势缺少某些日期时，旧逻辑仍可能把已有日的部分和作为整周总计返回；销售/净销售金额本身也允许空值。订单数与行为数 DTO 是 primitive，缺日点才表示该日计数不可用。
- 修改 `analytics-server/ai-decision/src/main/java/com/graduation/analytics/report/ReportInstanceService.java`：订单数、销售额、净销售额、行为数分别要求七个日期测量齐全才输出周汇总；任何日期缺少对应值时该汇总留空，并输出相应 `WARN_WEEKLY_*_INCOMPLETE`。日级数据保留原始 null，不补零；客单价仅在订单数及销售额完整且订单数大于零时计算。
- 验证：`mvn -o -pl ai-decision -am -DskipTests compile` 首轮捕获到订单/行为计数 DTO 为 primitive 的比较错误；改为按整日日趋势缺失判断后，同命令 BUILD SUCCESS，编译 ai-decision 48 个源码文件。新增测试 `ReportInstanceServiceTest` 两项周报缺数用例；定向类共 7/7 PASS，0 failure/error/skipped。未部署或连接数据库。
- 真实隔离平台恢复仍受凭据可见性阻塞：预期的九个 `PLATFORM_META_*`/`PLATFORM_METRIC_*` 变量在当前任务进程未发现；WSL 会话同样未继承变量；task-owned RunId `cloud_261010_061315_23e7b3` 的 8091 关闭。未启动服务、未触库，3306未连接。
- 阶段14与项目总验收保持开放。
