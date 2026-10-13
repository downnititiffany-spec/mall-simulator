# 阶段 4 管理员用户列表分页记录

日期：2026-10-06

## 实现

- 新增 `GET /api/v1/admin/users/page`，返回 `items/page/size/total/totalPages/sort`；保留原数组 `/api/v1/admin/users`。
- 排序仅支持 id、username、role、createdAt，页大小 1–100；非 ID 排序用 ID 降序稳定打散。Count 和列表在只读 `REPEATABLE_READ` 事务内执行。
- 仅输出 `UserView`（id、username、realName、role），不返回密码哈希。Ops 用户列表加入翻页、页大小和排序控件，创建后回到第一页。

## 验证

- 七模块 Maven reactor BUILD SUCCESS：`AuthServiceUserPaginationTest` 2/2、`UserAdminPaginationHttpTest` 1/1、`AiControllerAuditPaginationTest` 2/2，合计 5/5。
- `node.exe --test tests/opsPipelinePagination.test.js`：6/6 PASS。
- `npm run build`：Vite 5.4.21，680 modules，BUILD SUCCESS。
- `git diff --check` 结果在收口时复核。

## 限制

- 未部署新 JAR，也未用真实非空管理员用户列表验证 SQL 排序、翻页或真实浏览器呈现。
- 旧数组端点继续保留以兼容其它消费者。
- 本记录仅证明实现与离线定向验证，阶段 4 及产品总验收保持未关闭。
