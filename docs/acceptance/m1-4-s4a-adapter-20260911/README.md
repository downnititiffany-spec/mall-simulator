# M1-4 S4a 证据：目标商城适配器 SPI + 能力探测（模板解耦的第一步）

- 日期：2026-09-11 19:3x–19:4x
- 任务：看板 `M1-4`（切片 **S4a**，Owner A）；决策：`docs/开发过程事实与决策记录.md` **D-033**
- 范围：只改 `synthetic-data-generator/**`。未触碰 `mall-simulator/**`、`mall-frontend/**`、`analytics-server/**`、`contract-specs/**`
  （契约里的加性扩展只是**建议**，写在 D-033 里请总控冻结，本轮未改契约文件）。

## 1. 这一片解决什么

用户的固定指令是"分析系统不能被写死在某一个模拟商城里，商城的数据种类也不能写死"。生成器这一侧的对应物是
**目标商城适配器**：换一台商城应当是"新增一个 `MallTargetAdapter` 实现并注册"，而不是去改检查服务里的 `if` 分支。

改造前的事实（旧代码自述）：`TargetProbeService` 把"TCP 端口通不通 + 本地目录可不可写"当成"商城可用性"，
能力说明里写着"本次未验证"——即**用端口探活冒充能力结论**。S4a 把它换成真实探测，并把结论做成机器可读的三态。

## 2. 改了什么（唯一所有者）

| 文件 | 作用 |
|---|---|
| `adapter/MallTargetAdapter.java` | SPI：`adapterType()` + `test(TargetConfig)`（**逐字用指导书 §4.1 的方法名与返回类型**） |
| `adapter/MallTargetAdapterRegistry.java` | `adapter_type` → 实现的唯一派发点；重复注册启动即失败；未知类型 `require` 抛 `IllegalArgumentException`（列出已注册类型） |
| `adapter/ReferenceMallHttpAdapter.java` | `REFERENCE_MALL_HTTP`：对 8090 的代表路由只发 `OPTIONS`，把 2xx/401/404 判成三态能力 |
| `adapter/FileModeTargetAdapter.java` | `CANONICAL_EVENT_FILE`：原"目录可写 + 拒绝 landing"逻辑**逐字搬家**到这里（现为这条规则的唯一所有者） |
| `adapter/MallCapability.java` / `CapabilityVerdict.java` / `TargetConfig.java` / `TargetCheckResult.java`（包名不叫 `target`，原因见 3.4.1） | 能力词表（`product/user/order/refund/behavior/reset_state/admin`）、三态判定、目标配置、检查结论 |
| `service/TargetProbeService.java` | 瘦成"按 `adapter_type` 派发 + 把结论转视图"；旧 TCP 探活分支**已删除**（不留兜底/双写） |
| `web/dto/GeneratorApiDtos.java` | `TargetCheckView` 加性新增 `capabilities`（`Map<String,String>`，键=能力、值=三态） |
| `config/GeneratorBeans.java` | 装配 `MallTargetAdapterRegistry`（文件模式输出根 + 探测超时来自配置） |

设计要点（详见 D-033）：**三态而不是两态**（`ABSENT`＝商城确实没这个接口，`UNDETERMINED`＝我没测出来，两者绝不能合并）；
**实测不回写声明**（`generator_target.capabilities` 是运营方声明，检查结论只回给调用方）；**只发安全方法**；
**凭据只存引用**（`credential_ref` 是环境变量名，值永不进 detail、永不入库）；**未冻结的路径不猜**（行为埋点/重置接口路径必须由
`config_json.behavior_path` / `reset_path` 声明，未声明即 `UNDETERMINED`）。

## 3. 证据

### 3.1 E1：先红后绿（TDD 的红色是真的编译红）

新增 SPI 后第一次 `mvn -o test-compile -f synthetic-data-generator/pom.xml` 失败，报的全是新类型的**找不到符号**
（`CapabilityVerdict`、`TargetCheckResult`、`ReferenceMallHttpAdapter`、`TargetConfig` 等），即测试先于实现落地；
实现写完后同命令通过。

### 3.2 E2：模块自动化全绿

```powershell
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'
mvn -o test -f synthetic-data-generator/pom.xml     # 不加 -Dtest 过滤、不排除任何类
```

`BUILD SUCCESS`：**14 个测试类 / 64 用例 / 0 失败 / 0 错误 / 0 跳过**（改造前 52，本片 +12）。

| 新增测试类 | 用例 | 覆盖 |
|---|---|---|
| `adapter/MallTargetAdapterRegistryTest` | 4 | 大小写/空白不敏感派发；未知类型不静默兜底；错误信息列出已注册类型；重复注册响亮失败 |
| `adapter/ReferenceMallHttpAdapterTest` | 8 | 真实 HTTP 夹具（`com.sun.net.httpserver`）下的：能力全支持；缺路由=ABSENT；缺凭据=UNDETERMINED 且 detail 点名变量；**凭据也必须发给公开路由**；未声明路径不猜；不可达=全 UNDETERMINED（不是 ABSENT）；只发安全方法；detail 不回显令牌 |

"凭据要发给公开路由"这条规则是**先红后绿**的：先加断言跑出 `expected: <SUPPORTED> but was: <UNDETERMINED>`
（`e2-s4a-red-credential-on-public.log`），再改实现转绿。这不是为了凑流程，而是因为它来自下面 3.3 的现场实测。

模块内的既有守卫也一并通过：`GeneratorBoundarySourcePolicyTest`（三程序边界）、`GeneratorContractParityTest`（契约一致性）、
`GeneratorApiSmokeTest`（真实 MySQL + 真实 HTTP，含"未知 `adapter_type` → 400"与"landing 目录必须判不可写"）。

### 3.3 E3：真实链（真实 jar + 真实 8092 API + 真实 8090 商城）

被测产物：重建的 `synthetic-data-generator-0.1.0-SNAPSHOT.jar`，
**26,149,497 B / sha256 `D888D1A1F1574DC97C36334243389F5EE9F5574B33311A40C7764D0D3FBEF333` / 2026-09-11T19:47:36（`mvn -o clean package -DskipTests`，jar 内 `adapter` 包 10 个 class、`generator/target/` 残留条目 0）**
（`e3-jar-identity.txt`；并解包核对 jar 内确为改名后的 `TargetCheckResult.class`，无 `TargetCheck.class`）。
运行方式：`java -Dfile.encoding=UTF-8 -jar …jar`（8092），进程环境注入 `GENERATOR_TARGET_TOKEN`（8090 `admin` 登录换来的令牌）。

| 场景 | 目标 id | 实测结论（响应原文见 `verify/`） |
|---|---|---|
| A 参考商城 + `credential_ref` | 64 | `reachable=true`；11 条路由全 `HTTP_ANSWERED(200)`；**product/user/order/refund/admin=SUPPORTED**，behavior/reset_state=UNDETERMINED（未声明路径） |
| B 参考商城 + 无 `credential_ref` | 65 | `reachable=true`；11 条路由全 `GATED(401)`；**7 项能力全 UNDETERMINED**（"匿名"不等于"不支持"） |
| C `base_url=http://127.0.0.1:9` | 66 | `reachable=false`；`detail="无法连接 …（ConnectException: null）"`；7 项全 UNDETERMINED（**没有**写成 ABSENT） |
| D `adapter_type=ANOTHER_MALL_X` | 67 | HTTP **400** `INVALID_ARGUMENT`，`detail="未注册的 adapter_type：ANOTHER_MALL_X（已注册：CANONICAL_EVENT_FILE, REFERENCE_MALL_HTTP）"` |
| E `adapter_type=CANONICAL_EVENT_FILE`（回归） | 68 | `reachable=true`，`detail="输出目录可写并已清理探针文件：…\target\it-s4a-probe"`（旧行为逐字保留） |
| F 参考商城 + `config_json.behavior_path=/api/v1/mall/behaviors` | 69 | **behavior=ABSENT**（该路由 `MISSING(404)`）——"这台商城确实没有公开行为埋点接口"的机器可读结论（对应 B-04 缺口） |

### 3.4 现场实测推翻的一个假设（本片最有价值的一条）

第一版适配器只在 `/api/v1/admin*` 上附 `Authorization`，理由是"公开路由不需要凭据"。**对 8090 的实测否掉了它**：
匿名 `OPTIONS /api/v1/mall/products` 回 **401**——参考商城的 `AuthInterceptor` 对 `/api/v1/**` 一律要求 Bearer，
`/api/v1/admin` 只是**另加** admin 角色校验。于是第一次带凭据的 E3 探测里公开能力全被判 `UNDETERMINED`；
改成"能取到凭据就对所有代表路由附带头"后，同一目标实测 `product/user/order/refund/admin=SUPPORTED`。

对下游的硬约束（已写入 D-033）：**S4b 的 `MALL_API` 引擎每一次调用都必须带凭据**，不能假设"公开接口免认证"。

### 3.4.1 包名 `target` 被 `.gitignore` 静默吞掉（现场发现，已改名 `adapter`）

本片最初的包名是 `com.graduation.generator.target`，**10 个源文件对 git 完全不可见**：仓库 `.gitignore` 有 `target/`
（Maven 构建产物约定），而无前导斜杠的模式在**任意层级**匹配，于是
`synthetic-data-generator/.gitignore:2:target/` 命中了 `…/generator/target/MallTargetAdapter.java`。实测命令与输出：

```text
$ git check-ignore -v synthetic-data-generator/src/main/java/com/graduation/generator/target/MallTargetAdapter.java
synthetic-data-generator/.gitignore:2:target/  synthetic-data-generator/src/main/java/com/graduation/generator/target/MallTargetAdapter.java
```

`git status` 里这批文件根本不出现，**测试全绿也照样提交不上去**——这正是"绿信号掩盖真实缺口"的一类陷阱。
抉择：**不往 `.gitignore` 里加例外**（父目录被排除时对文件的反选无效，而且会长期留下"包名与构建产物目录同名"的坑），
直接把包名改成 `adapter`（其内容本就是"目标商城适配器 + 其配置/结论类型"，语义也更准）。改名后 `git check-ignore` 无输出、
`git status` 可见新包，模块仍 64/64 绿、E3 六场景结论逐项不变。

> 教训（已写入 D-033）：**包名/目录名撞上构建产物约定（`target/`、`dist/`、`build/`）时选改名，不要加 ignore 例外**；
> 交付前用 `git status --short <模块>` 核对"新写的文件确实出现在待提交列表里"，不能只看测试是否绿。

### 3.5 未取证（诚实标注）

1. **§4.5 的生成器页面仍缺**：现在只有 CLI（`GeneratorCli` 的 `plan-append` / `run-start`）与 HTTP API，没有 `src/main/resources/static/` 页面 ⇒ S5 待开工。
2. **§4.1 的其余七项未实现**：`capabilities()`、`listProducts`、`createSyntheticUser`、`emitBehavior`、`createOrder`、`pay`、`cancel`、`refund` 属 S4b（MALL_API 生成引擎），本轮只有 `adapterType()` 与 `test()`。
3. **只有一个适配器实现**：§3.4-6 要求的"第二个 `MallTargetAdapter` 或不同字段夹具"未取证（= 看板 `M1-9`）。
4. **Spring 对 `OPTIONS` 的边界只测到"本商城实际返回的 2xx/401/404"**；其它状态码（如 405/500）走 `UNEXPECTED` 分支只有单测覆盖，无现场样本。
5. **`reset_state` 在参考商城仍无解**：没有受保护的重置接口，`config_json.reset_path` 该指向什么属业务裁决（B-04），本轮未定，故实测为 UNDETERMINED。
6. **未把它写进常驻启动链**：8092 当前是本轮手工拉起的实例；`scripts/start-all.ps1` 未改（脚本改动属 M1-8 范围），重启机器后需重新拉起。
7. **未在其它机器/CI 复跑**；本机 Windows + JDK17 + Maven 3.9.14 单点验证。
8. `contract-specs/**` 只读 ⇒ 契约侧登记与 `VERSION` 升版待总控。

### 3.6 现场副作用（如实记录）

- `generator_meta.generator_target` 新增了本轮 E3 造的目标行（含 `test_environment=true` 的一次性目标）；未删除（删除属数据变更，需单独确认）。
- 8092 旧实例被停止并换成新 jar；**未改动任何业务库、数仓或 `analytics_meta` 数据**，未新增 `pipeline_run`、未动 `metric_snapshot` 的 ACTIVE 指针。

## 4. 复核命令

```powershell
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'
# E1 + E2（模块全量，64 用例）
mvn -o test -f synthetic-data-generator/pom.xml
# 只跑本片新增的 12 个用例
mvn -o test -f synthetic-data-generator/pom.xml '-Dtest=MallTargetAdapterRegistryTest,ReferenceMallHttpAdapterTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# E3 复现（需 8090/8092 在监听；8092 需在进程环境里带 GENERATOR_TARGET_TOKEN）
$token = (Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8090/api/v1/auth/login' -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}').data.token
$id = (Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8092/api/v1/targets' -ContentType 'application/json' `
  -Body (@{ name="recheck-$(Get-Date -Format HHmmss)"; adapter_type='REFERENCE_MALL_HTTP'; base_url='http://127.0.0.1:8090'; credential_ref='GENERATOR_TARGET_TOKEN'; test_environment=$true } | ConvertTo-Json)).id
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8092/api/v1/targets/$id/test" | ConvertTo-Json -Depth 4
```

## 5. 证据文件指纹（sha256 / 字节数，2026-09-11 19:48 定稿）

| 文件 | 字节 | sha256 |
|---|---|---|
| `verify/e2-generator-module.log` | 13,548 | `9E017523A01B9D21EF0911AF19ECC848BCCAD8A29D7FADC9D2A4D33626A246C4` |
| `verify/e2-s4a-unit.log` | 4,491 | `11EC3547B26ABA49B8643BDB48E13603069BB61587A7B424C96E6EC308A6236D` |
| `verify/e2-s4a-red-credential-on-public.log`（先红证据，改动前生成） | 7,273 | `E3160AA30A5685FF2B66D323100E050C1C6996FD1E5720832D5200B1C96DDF0E` |
| `verify/e3-jar-identity.txt` | 118 | `D7E5FCE230772732D77EC8877E8E6B4FCDE1B22D10511882EC3E495318A37622` |
| `verify/e3-a-reference-mall-with-credential.json` | 1,266 | `A31E6FBF5BFF533AD02F4C9CA79501D7F4934AF37F85D36E83F784924ACC7E88` |
| `verify/e3-b-reference-mall-without-credential.json` | 1,247 | `18BB0C82C68B09527D0A8CC2A3D6D5620CA46FA69C2ABD6FC082ED15FA0CF3BD` |
| `verify/e3-c-unreachable.json` | 294 | `845F21218E8F3F658BC7962EFD48C4406F18D25D05E396F3CD1A613119124A0B` |
| `verify/e3-d-unregistered-adapter-type.json` | 186 | `DF9FAD61D8E293C4B22EFBD27C53B97C3D54C822991521BFC6CA6E845557B603` |
| `verify/e3-e-file-target.json` | 165 | `01582005E990F7DF6D746BE1BB8D97D48ADD680CF8AB042467D7E4A8EC4F70B6` |
| `verify/e3-f-reference-mall-declared-behavior-path.json` | 1,152 | `48379E79AAC6C4CFE4D09B08C149576BA399AD469F16743307943550BDEC6E96` |
| `verify/e3-target-ids.json` | 152 | `6283ED064BCD266733F92B3D098D62E40DE10FE76280937409E8AECCC8642320` |

口径说明：E3 的响应原文是**最终 clean 构建**（jar sha256 `D888D1A1…`）跑出来的；`e2-s4a-red-credential-on-public.log` 是**改动前**捕获的红色证据（当时 jar 与包名都是旧的），按"证据不可改写"原则原样保留，不用新构建覆盖。
