# 阶段14 周报制品连续日期读取守卫（2026-10-12）

## 改动

`ReportInstanceService` 读取 `OPS_WEEKLY` 制品时，原守卫检查了 `daily` 恰好七项、制品起止日等于实例窗口，却未核对逐日日期。重复某一天并漏掉另一日的结构仍可能通过校验。现要求报告窗口本身恰为七日，并检查 `daily[i].date == fromDate + i`，因此每日日期必须有序、连续且覆盖完整窗口。六模板结构回归的周报有效夹具改为明确日期；错误窗口、缺日、重复日期均应拒绝。

## 验证

执行：`mvn -o -f analytics-server/pom.xml -pl ai-decision -am '-Dtest=ReportInstanceServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

结果：`ReportInstanceServiceTest` 15/15 PASS，0 failure、0 error、0 skipped；Reactor `BUILD SUCCESS`。此次把周报异常结构纳入既有测试，测试总数仍为15。

## 范围

这是离线服务单测及当前代码行为，不是数据库、HTTP、浏览器、部署或阶段14实链验收。更正：首次检查的是当前 PowerShell/WSL shell，其未继承运行进程的 task root；按当前进程命令行、PID登记和显式 task root 复核后，确认 RunId cloud_261012_012653_d3b0fc 的 12/12 服务端口正在监听。平台进程环境含隔离 MySQL 连接变量（只检查变量名，未读取/输出值）；当前任务环境未见 GRADUATION_TEST_ADMIN_PASSWORD，故本轮没有认证 API/浏览器验收。此次测试仍仅为离线单测，没有调用服务或数据库写操作，未触碰 3306。

编辑 `PROJECT_STATUS.md` 前已备份为 `PROJECT_STATUS.md.bak-20261012-weekly-date-guard`。冻结的 V3.0 指导书和设计文档未修改。

