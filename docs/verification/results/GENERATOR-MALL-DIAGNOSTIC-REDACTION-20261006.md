# 生成器商城诊断凭据脱敏（2026-10-06）

## 发现

ReferenceMall 与 SecondMall 的适配器会把商城错误字段、非法 JSON 正文、传输异常文本和完整目标 URL 放入异常。MALL_API 运行服务还会将异常信息写入 operation journal/运行结果并记录失败日志；如果目标服务回显 Bearer 值，或 base_url 的 userinfo/query 含敏感内容，就会突破“凭据值不得进入异常、日志、运行报告”的边界。

## 修复

- 增加 `MallDiagnosticSanitizer`，对当前请求令牌执行精确脱敏；解析器/HTTP 客户端原始异常不再作为 cause 链保留，改为不含原始 cause 的安全摘要。
- 两家适配器的 HTTP 错误 message、错误码、无效响应正文和传输异常诊断均脱敏后再限长。
- 目标错误和连通性诊断只显示 scheme、host、port，不显示路径、userinfo、query 或 fragment。
- `base_url` 含 userinfo、query 或 fragment 时，业务调用与能力探测均拒绝执行，并且不回显原值。
- 未改变 HTTP 方法、商城错误分类、有限安全方法重试或写操作“结果不明、禁止盲目重试”语义。

## 验证

命令：`mvn -o -f pom.xml test`（工作目录 `synthetic-data-generator`，JDK 17）。结果：**125 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**。本地假商城回归覆盖 ReferenceMall/SecondMall 对错误字段回显令牌、含凭据 userinfo/query 的目标地址拒绝；完整模块默认测试还覆盖商城适配器行为、有限重试、生成运行状态、静态管理台和隔离守卫。

初次定向执行曾因新增用例把此前正常请求也计入“URL 拒绝后未发送请求”的计数断言而失败；修正为比较调用前后请求数后，定向测试 **31/31 PASS**，默认模块 suite **125/125 PASS**。该初次失败是测试断言基线错误，不是产品行为失败。

## 边界

- 未访问真实外部商城、数据库或使用任何真实凭据；没有执行生成器到商城的真实 MALL_API 运行。
- 本结果证明本地假商城响应下的脱敏行为与 base_url fail-closed，不证明目标商城协议幂等能力、写请求 exactly-once 或阶段 7 完整验收。
- 3306/3307 均未访问，未启动/停止项目服务，未修改冻结 V3.0 指导书/设计文档。