# 本地真实链路、DeepSeek与角色页面验收

日期：2026-10-17（Asia/Shanghai）。开发基线：`232787bd5d16f884174f0804e0945a7a9971a8fd`；本报告及源码修复属于其后的本轮工作，确切源码/制品哈希见证据目录。冻结V3.0指导书/设计不变。

## 授权与环境

用户明确指定DeepSeek V4.1 Flash、允许少量真实请求，确认云电脑不可用。按最多3次平台模型尝试执行，实际2次；只有合成业务数据及其已发布ADS证据摘要外发。用户管理的DPAPI加密密钥在仓库外，解密/注入仅在进程内；没有保存明文密钥、token或DB凭据。

官方接口模型名为`deepseek-flash`：[模型发布说明](https://api-docs.deepseek.com/zh-cn/news/news260910/)。凭据模型列表GET返回deepseek-flash和deepseek-v4-pro；GET仅证明认证可用，真实业务调用另列。

统一脚本`cloud-single-node.sh preflight`通过依赖、已初始化HDFS身份/配置和端口守卫，再新建RunId `cloud_261017_073320_76fcc0`。MySQL仅127.0.0.1:3307，四schema均为RunId前缀。复用已有HDFS namespace、不格式化；新MySQL/Derby/warehouse不覆盖历史RunId。12个目标端口全部监听后开始验收。

## 当前代码真实连续链

1. 当前源码重新打包platform、mall、generator、spark四制品，package均成功。Spark用JDK8打包；实际运行用统一脚本的Spark3.5.1/local[1]，平台JDK17。
2. 商城正式API创建2个合成用户、加购、下单及支付2笔，发布Outbox；不是向DB直接插入业务指标。14个已记录业务/状态请求成功。
3. 常驻Flume File Channel投递10行；HDFS单文件内容与商城源文件相同，SHA256 `60a6e8fa80927757516e64691cfee9a2b2db1b9b75227ab4ee4a4476ebcfd2fa`。首次helper因调用shell缺JAVA_HOME在复制前拒绝，显式补JDK后通过，没有盲目重投。
4. 正式采集API：batchId=1，10 accepted、0 quarantine/error，4738 accepted bytes，产出READY manifest。
5. pipeline runId=1全部8阶段SUCCESS，发布`S20261017_1`；12项metric_value、12张ADS服务表行数/发布值核对通过，质量4/4。详见完整pipeline结果中的发布检查明细。
6. 独立业务oracle：商品单价29.90、2笔各1件已支付订单，期望GMV59.80；真实指标返回59.8，paid_order_cnt=2、repeat_rate=0。8/8业务/报告检查通过。

数仓计算使用RunId专属嵌入式Derby；独立19083 Hive Metastore不是pipeline共享目录，因此本结果不关闭RES-02。

## 真实LLM与修复

| 尝试 | 结果 | 证据 |
|---|---|---|
| 1 | NETWORK，providerUsed=template | ai_call_log id1，FAILED/NETWORK；约19.25秒。真实失败保留，不算成功。 |
| 2 | 真实摘要采用，providerUsed=openai-compat | ai_call_log id2，OK/model=deepseek-flash/sourceId=1/snapshotId=S20261017_1；1117输入、78输出token；Provider耗时1278ms，API端到端1437ms。 |

根因与改动：

- WSL curl通过本机代理访问API，Java未自动采用HTTP_PROXY/HTTPS_PROXY。无凭据Java GET探针直连超时、显式HTTPS代理后返回401，确认传输差异；平台仅本RunId重启，显式使用127.0.0.1:7890 HTTPS代理。没有关闭TLS校验或代理本地数据库。
- 摘要提示要求纯文本，原Provider却对所有请求强制json_object。AiRequest新增显式OutputFormat：既有三参数构造仍默认JSON_OBJECT，证据摘要使用TEXT，HTTP端不再强制JSON；SQL结构化请求保持JSON。
- 可选`LLM_REASONING_EFFORT`支持none/low/high/max，空值保持供应商默认，非法值网络调用前拒绝。本轮使用none和1024输出token上限，避免默认高强度推理挤占简短摘要预算。[官方推理模式](https://api-docs.deepseek.com/guides/thinking_mode/)
- 本地请求指纹版本v2纳入输出格式；不把指纹解释成供应商幂等键或响应哈希。成功审计实际字段是requestFingerprint，status是OK，不伪记SUCCESS。

只有证据摘要改写路径取得真实成功。没有把此结果扩大成真实Text-to-SQL、SQL修复、所有建议/反馈路径及业务效果均通过。

## 回归与浏览器

| 范围 | 实际结果 |
|---|---|
| Provider/证据解释/来源/指纹定向 | 77/77，F0/E0/S0 |
| 官方default三树 | 收集1563、通过1561、跳过2，F0/E0，exit0；analytics1403/mall26/generator134基线MATCH |
| 四角色真实浏览器 | admin/data_dev/operator/analyst，12路由+注销各13项，总52/52；0 mock、0页面异常/失败API |
| OPS_DAILY真实报告页面 | 8/8，7 API请求，页面异常0，详情与完整制品读取后注销 |
| 业务oracle与报告owner隔离 | 8/8；管理员创建READY报告，analyst读取其详情/制品均REPORT_NOT_FOUND |
| 未知快照/空态 | UNKNOWN_SNAPSHOT空信封、无旧指标/禁导出、返回当前恢复；决策空态截图 |

新增3项Provider回归，因此统一analytics基线1400→1403。指纹格式隔离断言随default最终重验通过。2个跳过仍为Windows符号链接测试；没有删除安全用例。Web450和Spark389的离线全套沿用同日既有结果，本轮没有重跑；本轮Spark证据是实际完整小样本生产作业。

浏览器首次运行使用的bundled Python不含playwright，在导入时退出、未操作浏览器；改用已有Python3.14/playwright后通过。未知快照验证器初版错误要求HTTP>=400，但项目契约返回HTTP200+UNKNOWN_SNAPSHOT空信封；检查真实信封后修正断言，保留初版失败结果，不修改产品迎合测试。

## 收尾与剩余项

统一stop exit0，核对本轮PID/RunId后停止服务；12目标端口全部closed，数据库/HDFS文件保留。密钥不再留在本轮服务进程；用户原加密文件保持不变。未连接/探测3306、未清理历史数据、未修改冻结文档。

RES-01最小真实调用、RES-06本地角色基础走查已取得证据。共享HMS/远程集群仍未验（云电脑不可用）；正式库迁移仍冻结；历史日志不补造；完整业务生命周期、性能/灾难恢复及最终签收不由本轮局部结果推定。最终结论仍按总控限定验收口径。

证据：[本轮原始结果与复现说明](../../acceptance/local-live-llm-20261017/README.md)。
