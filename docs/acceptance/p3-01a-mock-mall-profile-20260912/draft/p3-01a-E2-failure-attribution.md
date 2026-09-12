# E2 那一条红灯的归因（P3-01-a 泳道，2026-09-12）

## 结论

`mvn -o -f analytics-server/pom.xml -pl platform-app -am test` 结果 **537 个测试 / 1 失败 / 0 错误 / 0 跳过**：

各模块：`platform-common` 41 ✓、`connection-ingestion` 156 ✓、`warehouse-pipeline` 111 ✓、
`metric-analysis` 38 ✓、`ai-decision` 91 ✓、`platform-app` **100（1 失败）**。

```
[ERROR] IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate:168
        Expecting empty but was: ["40.json", "41.json", "42.json"]
        as: 前提校验：历史清单一个都不许被回填/改写（D-037 裁决 6）
```

**该失败与本泳道交付物 `mock-mall.v1.json` 无关，且在本泳道开工之前就已存在。**

## 证据链

### 1. 本泳道改了哪些文件（`git status --porcelain`，只读）

```
?? analytics-server/source-profiles/mock-mall.v1.json
?? docs/acceptance/p3-01a-mock-mall-profile-20260912/draft/
?? docs/acceptance/p3-01a-mock-mall-profile-20260912/raw/
```

即：**只新增**一个画像文件 + 本泳道证据目录。
**没有任何一条** ` M landing/manifests/...`；本泳道从未写入 `landing/`。
（`?? docs/acceptance/m3-step8-parity-20260912/raw/` 是**别的泳道**的未跟踪目录，不是本泳道产物。）

### 2. 失败断言读的是什么

`IngestionManifestSourceSchemaTest.java:159-168`：

```java
if (fieldNames(node).stream().anyMatch(NEW_SOURCE_KEYS::contains)) { backfilled.add(name); }
...
assertThat(backfilled).as("前提校验：历史清单一个都不许被回填/改写（D-037 裁决 6）…").isEmpty();
```

其中 `NEW_SOURCE_KEYS = List.of("sourceCode", "sourceId", "profileVersion", "mappingVersion")`（第 56-57 行）。
该断言要求：磁盘上**没有任何**清单带这四个键。

### 3. 那三个清单是谁写的、什么时候

| 文件 | 字节 | 落盘时间 |
|------|------|---------|
| `landing/manifests/40.json` | 1,051 | 2026-09-12 **09:23:15** |
| `landing/manifests/41.json` | 1,365 | 2026-09-12 **17:22:22** |
| `landing/manifests/42.json` | 763 | 2026-09-12 **17:34:54** |

三个时间**均早于**本泳道开工（本泳道首条证据时间戳 2026-09-12 20:3x，见 `raw/00-timestamps.log`）。
三者都是**真实入库运行**产出的**新一代清单**（带 P1-05 新增的源字段）——
本泳道早先独立读到的 `manifests/42.json` 内容即含
`sourceCode: "mock-mall"`、`sourceId: 1`、`profileVersion: "1.0"`、`mappingVersion: null`（`raw/10` 第 [6] 节）。

⇒ 机制清楚：**别的泳道在今天真实跑了入库**，写下了带新源字段的清单 40/41/42；
而该测试的"前提校验"是在这些运行**发生之前**写死的"磁盘上不存在新代清单"这一假设。
新代清单一旦出现，前提高悬即失败 —— **与画像文件毫无关系**（画像不在 `landing/` 下，也不参与清单字段集）。

### 4. 反向验证：画像相关测试全绿

同一份 `raw/13-E2-tests.log` 里：

| 测试类 | 结果 |
|--------|------|
| `com.graduation.analytics.source.SourceProfileValidatorTest` | **Tests run: 8, Failures: 0, Errors: 0** |
| `com.graduation.analytics.source.SourceProfileFixtureTest` | **Tests run: 4, Failures: 0, Errors: 0** |

⇒ 唯一与源画像有关的两个测试类，在新增画像文件之后**全部通过**。

## 本泳道为什么没有修它

1. 修它需要改 `*.java` 测试文件 —— **本泳道白名单明令禁止**触碰任何 `*.java`。
2. 该测试文件属 P1-05 的资产，不是本泳道属主；
   正确修法是让"前提校验"承认"新代清单由运行产生"（例如改为只校验**运行前已存在**的清单子集）。
3. 更重要的：**放宽或改写它，正是本任务明令禁止的"放宽测试以制造绿灯"**。

## 交总控裁决

- 若按"画像相关既有测试全绿 + 编译通过 + 端点 7/7"判定 ⇒ 本泳道目标已达成。
- 若要求 **reactor 全绿**才可判 DONE ⇒ 需由 P1-05 属主（或总控授权）修
  `IngestionManifestSourceSchemaTest` 的前提校验；**本泳道不自行升级状态为 DONE**。
- 建议记入看板：**"真实入库运行会产生新代清单，从而使 P1-05 的 `allOnDiskManifestsStillValidate`
  前提校验失败"** —— 这是一条**跨泳道的可复现耦合**（任何人再跑一次真实入库都会复现），
  不是本泳道的一次性现象。
