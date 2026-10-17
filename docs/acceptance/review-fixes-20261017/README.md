# 2026-10-17 本轮修复证据

基线：开发线 `a70ae13` 加本轮修复；各源码工作树字节SHA-256见 [SOURCE-SHA256SUMS](SOURCE-SHA256SUMS.txt)，不是Git规范化后的blob哈希。

- [路径反例修复前](paths-red.log)：44项、2失败；[修复后](paths-green.log)：44/44通过。
- [后端](default/default-analytics-server.log)：1400、F0/E0/S2；[商城](default/default-mall-simulator.log)：26/26；[生成器](default/default-synthetic-data-generator.log)：134/134。
- [Web测试](web-relative/web-test.log)：450/450；[Web构建](web-relative/web-build.log)：成功；[入口汇总](web-relative-console.log)：相对LogDir解析为预期绝对目录，exit0。
- [浏览器输出](browser-console.log)：匿名12路由、报告mock、RuntimeProfile mock均PASS；没有真实平台认证或数据库操作。
- [匿名登录页截图](anonymous-login.png)；[报告页截图（模拟API与身份）](reports-mock-ui.png)。
- [执行时浏览器脚本原件](browser-checks.py)。复现时将其放回仓库根 `.verify/review-fixes-20261017/browser-checks.py`，使用项目Python Playwright环境与web构建产物，在空闲loopback 5187上临时托管；脚本会运行既有报告/RuntimeProfile mock验证器并保存截图。模拟接口不外发数据。完成后按PID/命令/创建时间确认并关闭本轮预览进程。

入口命令与范围详见 [修复报告](../../verification/results/REVIEW-FIXES-20261017.md)。全部日志是本轮实际输出，不由摘要生成。测试中人为构造的异常不是实服故障；反例红轮明确标记，不计入修复后的通过结论。
