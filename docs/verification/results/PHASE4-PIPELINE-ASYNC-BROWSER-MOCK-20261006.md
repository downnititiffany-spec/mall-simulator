# 阶段 4 流水线异步反馈浏览器验证（模拟 API）

日期：2026-10-06  
范围：Pipeline 页面触发一次采集/流水线后的 RunId、进度轮询、临时轮询失败恢复和终态呈现。  
证据等级：本地 Vite + Chromium，API 全部由 Playwright 路由模拟；不是平台服务或数据库验收。

## 执行与结果

- 通过 `webapp-testing` 的 `with_server.py` 启动本工作树 Vite，使用独立端口 5175；浏览器登录状态通过测试专用 localStorage 注入。
- 模拟 API 流程：页面读取空运行列表；采集请求被接受；创建运行返回 RunId 77 / PENDING；首次详情轮询返回 HTTP 503；随后依次返回 RUNNING（SPARK，12 条）和 SUCCESS（PUBLISH，12 条）；终态列表返回一条成功运行。
- 浏览器观察到轮询失败提示，同时 RunId 保留且没有被误标为 FAILED；页面随后展示 SPARK 阶段、12 条记录，最终显示 SUCCESS 和列表行。
- 请求核对：创建请求 1 次、详情轮询 3 次、列表读取 3 次；页面 JavaScript 异常 0。
- 定向源码测试 `pipelineAsyncFeedback.test.js` + `pipelinePage.test.js`：**10/10 PASS**。另有此前 Vite production build 680 modules PASS。
- 截图：`target/v25-it/pipeline-async-browser-20261006/final.png`；Playwright 脚本及 JSON 结果同目录。

## 限制与清理

API 响应由浏览器路由模拟；本次没有调用后端、MySQL、Hive/HDFS 或真实流水线，也没有部署。测试助手报告 Vite 停止后，现场仍检测到其 npm/cmd/Vite 子进程监听 5175；按已核对的 PID 和命令行仅结束本轮进程，并确认 5175 已释放。另一个既有 Vite 进程监听 5174，本轮未连接、使用或停止。

因此此证据关闭的是本地浏览器交互切片，不关闭阶段 4 或项目总验收。
