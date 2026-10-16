# 阶段 4 决策列表创建时间排序分页验收

日期：2026-10-09  
范围：扩展只读分页验收器，覆盖决策页面实际提供的“创建时间新到旧”排序及第二页边界。

## 改动与验证

- 在既有 ID 升/降序分页检查外，新增 sort=createdAt,desc 的真实 API 验收；检查 sort 回显、total/totalPages、页内次序及 page 1→2 边界。
- 相同 createdAt 的记录要求按 ID 降序稳定排列；若下一页首行与上一页末行时间相同，则继续检查 ID 边界严格递减。
- 为保留 1–9 位小数的 LocalDateTime 精度，验收器从原始 JSON 响应提取 createdAt 字符串。PowerShell ConvertFrom-Json 会自动将 ISO 时间转成 DateTime，可能丢失超过 7 位的小数，不能直接用于此边界检查。
- loopback mock 使用 25 条合成记录、4 条同时间分组，使一个同时间组跨越 10 行页边界；时间包含 9 位小数。脚本 PASS，ID 双向排序、createdAt DESC、两类跨页边界、分页元数据及 logout 均通过；mock 请求序列通过。
- PowerShell AST 与 Node.js 语法检查通过。

## 证据边界

以上仅验证脚本与 loopback mock，不证明当前隔离服务的管理员认证、真实数据库排序/分页或浏览器展示。阶段 4 仍开放，未访问 3306。
