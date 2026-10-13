# 阶段4 真实管理员 RuntimeProfile 只读与分页门槛复核（2026-10-10）

## 隔离环境与账户

- RunId：`cloud_261010_005249_1c73ec`。重新核对 RunId 登记、MySQL/PID 文件、平台 PID 和活动服务；平台数据源指向该 RunId 自己的 `*_meta` schema，TCP 仅 `127.0.0.1:3307`。统一状态显示 12/12 任务端口 LISTEN。
- Web 5173 原已关闭；通过当前源码 `scripts/cloud-single-node.sh recover-web` 恢复。Windows PID helper 先确认登记进程已停止，随后只清理该 RunId 的失效登记并启动 Vite；数据库/HDFS 未停启，复核后 5173/5174 均 LISTEN。
- V3.0 平台用户迁移定义演示账户 `admin` 演示种子账户。对隔离库唯一启用的 admin 行核对后，BCrypt 摘要已与迁移种子摘要一致；尝试写入相同摘要返回 `ROW_COUNT()=0`，因此凭据没有实际改变。对本轮正式验收使用已知种子密码，不记录或输出密码。该种子只适用于本隔离演示栈。

## 真实浏览器与 API 结果

- `scripts/run-runtime-profile-authenticated-readonly.ps1 -Verifier runtime-profile -BaseUrl http://127.0.0.1:5173`：**PASS**。真实管理员登录 HTTP 200、业务码 `OK`；RuntimeProfile 页面 1 行、1 个 ACTIVE；只读 `/api/v1/runtime-profiles` 与 `/api/v1/sources` 均为 2xx、`code=OK`、`data` 数组；退出成功、页面异常 0、业务 profile 写入 0。共观察到 5 个 API 请求。
- 决策分页：真实管理员认证和只读分页响应正常，排序 `id desc`、`id asc`、`createdAt desc` 的页面契约一致，但 `total=0`、`items=0`。
- 采集批次分页：真实管理员认证和只读分页响应正常，`id desc` / `id asc` 契约一致，但 `total=0`、`items=0`。
- 两个现有非空分页验证器因空数据正确拒绝报告 PASS；各自退出码 1。没有为了通过门槛而插入决策或批次业务数据。因无记录，排序内容、跨页边界、重复 ID 检查均未获得真实非空证据。

## 边界与结论

- 本轮只对 `127.0.0.1:3307` 做了 RunId/数据源核验、管理员摘要比较和一条摘要相同的 no-op UPDATE；没有连接 MySQL 3306。没有改管理员凭据、创建/删除业务数据、profile 变更或切档。认证登录会建立短时会话，之后成功注销。
- 证明当前隔离栈真实管理员登录与 RuntimeProfile/source 只读查询可用；不证明 profile 测试/激活、HS2 管理探测、真实非空分页、业务链路或最终产品验收。
- 阶段4继续部分完成；需在 task-owned 栈中经正常业务 API/工作流产生可追溯的隔离测试记录后，再复跑决策与批次非空分页。阶段4与总控验收保持开放。