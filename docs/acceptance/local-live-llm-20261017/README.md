# 本地真实链路与DeepSeek验收证据

日期：2026-10-17（Asia/Shanghai）。说明与边界见 [验收报告](../../verification/results/LOCAL-LIVE-LLM-20261017.md)。本目录不包含数据库文件、jar、环境快照、密钥/凭据文件或登录token。

## 入口

- `synthetic-business-api.json`：商城业务API合成订单过程，登录响应未保存。
- `flume-delivery.log`、`ingestion.json`、`pipeline-current.json`、`snapshots.json`：10条事件、READY清单、8阶段终态和ACTIVE快照。
- `business-oracle-result.json`、`oracle-overview.json`：2×29.90=59.80、支付订单2、复购率0，与真实指标API对账。
- `llm-initial-*`：首轮NETWORK失败和模板回退原始记录。
- `llm-fixed-audit.json`、`llm-fixed-response.json`：真实Provider采用的摘要及数据库审计API回读；审计id2/OK，model=deepseek-flash、1117输入/78输出token。`llm-fixed-result.json`是原始验证器摘要，其requestHash/responseHash为空，因为产品实际字段为requestFingerprint；真实指纹见原始audit，不能把不存在的响应哈希视为已经实现。
- `real-browser-result.json`及`live-admin-*`：四角色52/52真实页面取数，无mock。data_dev测试账号通过正式用户管理API创建；不保存其口令。
- `report-created.json`、`report-artifact.json`、`report-real-browser.log`：真实OPS_DAILY报告、owner隔离和浏览器8/8。
- `snapshot-browser-states.json`、`live-unknown-snapshot.*`、`live-empty-decisions.*`：未知ID空信封、不显示旧指标、禁导出、恢复与空态。
- `snapshot-browser-states-initial-assertion.json`：验证器初版错误要求HTTP>=400的失败，原样保留；正确契约为HTTP200+UNKNOWN_SNAPSHOT空信封，随后校准验证器复测通过。
- `llm-targeted-tests.log`：77/77定向；`default-suite.log`、`default/*.log`：最终1563收集/1561通过/2跳过、F0/E0、基线MATCH。
- `platform-restart.json`：只重启任务平台前的RunId/PID/13环境变量与3307前缀守卫；凭据仅内存传递。
- `stack-start.log`、`stack-stop.log`、`stack-final-status.log`、`preflight-after-stop.log`：统一启停与安全预检；最终12端口closed、数据保留。
- `SOURCE-SHA256SUMS.txt`、`ARTIFACT-SHA256SUMS.txt`：本轮源码/运行制品校验和；jar本身不入Git。
- `MANIFEST-SHA256SUMS.txt`：本目录全部文件（自身除外）的字节校验和；gitattributes保留原始字节。

## 复现条件

使用当前源码和`scripts/run-tests.ps1 -Suite default`回归；真实运行按`cloud-single-node.sh preflight/start`，明确配置本地工具路径和已有HDFS身份，新建独立RunId。通过商城正式API造少量合成订单，经`cloud-flume-submit-active-run.sh`与平台采集/Pipeline API发布快照，再调用`POST /api/v1/ai/explanations`及读取`GET /api/v1/ai/audit/calls`。

API key只从用户安全通道注入任务进程。此机器Java需显式HTTPS代理127.0.0.1:7890；本机数据库保持直连。模型配置`LLM_BASE_URL=https://api.deepseek.com`、`LLM_MODEL=deepseek-flash`、`LLM_REASONING_EFFORT=none`，本轮最终输出上限1024。未开启HTTP请求调试，不记录Authorization或环境内容。不要复制旧RunId凭据、重放商城写请求或把代理端口当作其他机器的默认值。

归档保留结果和必要日志；临时API驱动使用隔离演示种子账号，不作为可发布凭据模板入库。既有真实报告浏览器脚本`scripts/verify-reports-real-browser.py`通过安全环境变量获取管理员口令。历史原日志、整个云端验收、真实Text-to-SQL、共享HMS/远程集群和正式3306不在本目录证据范围内。
