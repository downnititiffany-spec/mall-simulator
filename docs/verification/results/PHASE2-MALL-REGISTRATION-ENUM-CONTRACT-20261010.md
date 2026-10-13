# 阶段2 Mall 注册事件枚举契约前置校验（2026-10-10）

## 问题与修复

- 当前隔离实链发现 `POST /api/v1/mall/users` 仅校验 `ageGroup`、`cityLevel`、`memberLevel` 非空。实测合成请求 `adult/tier2/basic` 被商城接受并生成 Outbox，但 `mock-mall.v1.json` 的 `ageGroup/memberLevel` 映射不含 `adult/basic`，平台采集随后将该事件隔离。
- Mall `CreateUserReq` 现增加 Bean Validation 白名单：年龄段 `under18/18-24/25-34/35-44/45+`，城市级别 `tier1/tier2/tier3/other`，会员等级 `normal/silver/gold/platinum`。符合现有 `synthetic-data-generator` 的 `ContractEnums` 与 `mock-mall.v1.json` 注册源映射；不兼容值在 API 请求校验阶段拒绝，不再进入 Mall 业务表/Outbox。
- 新增 `CreateUserReqContractTest`：合法枚举、已观察到的非法 `adult/basic`、blank 字段分别验证。

## 验证

- 定向 Maven：`mvn -o -f mall-simulator/pom.xml -Dtest=CreateUserReqContractTest test`，3/3 PASS，0 failure/error/skipped。
- Mall 模块默认回归：`mvn -o -f mall-simulator/pom.xml test`，26/26 PASS，0 failure/error/skipped；BUILD SUCCESS。
- JDK 17、本地离线 Maven；未重启当前隔离服务，未连接数据库/3306，未重放原隔离事件。新规则尚未在真实运行服务上做 HTTP 400 浏览器/接口验收。

该修复阻止当前已确认的一类源端枚举不匹配，不关闭阶段2的映射变更、隔离恢复、ODS/DWD/DWS/ADS、多来源及完整链路门槛，也不关闭阶段4或产品总验收。
