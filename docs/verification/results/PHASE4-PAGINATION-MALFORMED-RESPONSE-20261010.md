# 阶段4 决策/流水线分页响应格式错误空态修复（2026-10-10）

## 发现

`Decisions.vue`、`Pipeline.vue` 与 `Ops.vue` 原先将缺失或错误类型的 `items` 静默替换为 `[]`，并把缺失/非法分页元数据默认成零或第 1 页。这会把接口契约错误显示成空表，造成错误空态。Ops 中也包括系统用户、快照、质量结果、AI问答/调用审计、流水线和采集批次分页。

## 修复

新增 `web/src/utils/pageResponse.js`，校验共享分页响应对象、`items` 数组、一基 `page`、正数 `size`、非负 `total`、至少一页、`totalPages = max(1, ceil(total / size))`、页码在范围内以及当前页行数不超过页大小。Decisions、Pipeline 和 Ops 的所有分页响应先校验再更新页面状态；格式错误进入既有页面错误提示。合法空数组和空结果分页仍被接受。

## 验证

- `node --test tests/pageResponse.test.js tests/opsPipelinePagination.test.js tests/paginationPageSize.test.js tests/pipelinePage.test.js`：21/21 PASS。
- `npm test`：428/428 PASS，0 fail/cancelled/skipped。
- `npm run build -- --outDir web/tmp-build-page-response`：Vite 5.4.21，687 modules，构建成功；独立输出目录未覆盖 `web/dist`。系统拒绝了清理该临时目录的命令，目录仍保留。
- 第一次带 `--test-name-pattern` 的 npm 命令因参数位置未按预期过滤而实际执行整套测试，并因新增静态断言正则过窄失败；修正断言后，定向及全量测试均通过。

## 证据边界

本轮是离线前端测试和生产构建；没有执行真实管理员认证、决策/流水线 API、数据库或浏览器现场验证。阶段4和最终验收继续开放，未访问 MySQL 3306。
