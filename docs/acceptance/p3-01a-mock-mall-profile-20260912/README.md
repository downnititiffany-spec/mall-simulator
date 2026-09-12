# P3-01-a 证据目录索引：`mock-mall.v1.json` 源画像

- 泳道：**P3-01-a**　日期：**2026-09-12**　状态自评：**`DONE_LIMITED`**
- 交付物：`analytics-server/source-profiles/mock-mall.v1.json`
- 一句话结论：**画像文件存在且合规**（E1 编译通过／画像相关既有测试全绿／端点浅检 7/7／语义断言 I1–I9 全 PASS）
- **主报告 → [`IMPL-REPORT.md`](IMPL-REPORT.md)**（27,848 B / `6217e380288668683320cf3106e6abc5a13a95ee304c36822f37a33553838a54`）

## 怎么读这个目录

| 想确认什么 | 看哪 |
|-----------|------|
| 画像里**每个取值**凭什么这么写 | `draft/p3-01a-profile-rationale.md`（逐键举证，含被否证的父会话读数） |
| **语义**是否合规（端点管不到的部分） | `raw/11-profile-conformance-output.txt`（I1–I9，`verdict=ALL_PASS`） |
| 编译过没过 | `raw/12-E1-compile.log` |
| 测试过没过、那 1 条红灯是谁的 | `raw/13-E2-tests.log` + `draft/p3-01a-E2-failure-attribution.md` |
| 真机端点 | `raw/14-E3-post-state.txt`（后置）／`raw/05-E3-pre-state.txt`（前置＝天然阴性对照） |
| 全量数据怎么数的 | `raw/04-stats-output.txt` + `raw/04b-scan-inventory.txt`（权威文件清单） |

## 关键读数（一处汇总）

| 项 | 读数 |
|----|------|
| 画像文件 | **3,323 B** / `0bb8a05c8d5e466864dcb90b8d7b97105dc65497dc3ccb463d104f27f021170b` |
| 顶层键 | 恰为设计 §4.2 的 **9 个**（I1 已与 `SourceProfileValidator` 源码解析结果比对：**同名同序**） |
| 扫描范围 | 346 个 `*.jsonl`（landing 345 + 黄金集 1）/ **2,620,776** 可解析行 |
| 事件类型 | 实测 **12** distinct，全部在契约 12 `$defs` 内，**0** 未映射 |
| payload 字段 | 实测 **31** 个：30 个恒等命中契约骨架，**1** 个契约外（`event_time_utc` → `@keep`） |
| `event_time` 形态 | **100%** 被契约 `iso8601_time` 解析；非 `+08:00` 的 = **0**（其中 8,852 行带小数秒） |
| E1 | `BUILD SUCCESS` / `exit=0` / 9.817 s |
| E2 | **537 测试 / 536 通过 / 1 失败 / 0 错误 / 0 跳过**；失败＝`IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate`（**既有、与本泳道无关**） |
| E2（画像相关） | `SourceProfileValidatorTest` **8/8** ✅、`SourceProfileFixtureTest` **4/4** ✅ |
| E3 | `POST /api/v1/sources/1/test` ⇒ HTTP 200、`ok=true`、**7/7** `passed`（**浅检**，D-139 §2.2） |
| E3 阴性对照 | `POST /api/v1/sources/999999/test` ⇒ **HTTP 404** `SOURCE_NOT_FOUND` |
| 状态变更 | 未调用 `/activate`；`GET /api/v1/sources/1` 的 `updatedAt` 与开工前**逐字一致** |

## 目录结构

```
docs/acceptance/p3-01a-mock-mall-profile-20260912/
├── README.md                                ← 本文件（索引）
├── IMPL-REPORT.md                           ← 主报告（五段式 + 文件清单 + 未实测清单）
├── RULINGS-P3-01A-20260912.md               ← 总控裁决（非本泳道产物）
├── RULINGS-P3-01A-BATCH2-20260912.md        ← 总控裁决 D-139（非本泳道产物）
├── draft/
│   ├── p3-01a-profile-rationale.md          ← 逐键举证（含 R1–R12 待裁定）
│   └── p3-01a-E2-failure-attribution.md     ← 那 1 条红灯的归因证据链
└── raw/                                     ← 全部「命令 + 原始输出」
    ├── 00-timestamps.log
    ├── 03-db-source-registry.txt
    ├── 04-stats-script.py / 04-stats-output.txt / 04b-scan-inventory.txt
    ├── 05-E3-pre-state.txt
    ├── 06-db-semantic-registry.txt
    ├── 07-time-shape-probe.py / 07-time-shape-output.txt
    ├── 08-enum-identity-probe.py / 08-enum-identity-output.txt
    ├── 09-skeleton-crosscheck.py / 09-skeleton-crosscheck-output.txt
    ├── 10-uuid-and-tree.txt
    ├── 10-uuid-coverage-gap.txt             ← **不完整证据**（4 行、无输出），由 10b/10c 取代
    ├── 10b-uuid-search.py / 10c-uuid-search.txt
    ├── 11-profile-conformance.py / 11-profile-conformance-output.txt
    ├── 12-E1-compile.log
    ├── 13-E2-tests.log
    └── 14-E3-post-state.txt
```

## 全部文件（路径 ＋ 字节 ＋ sha256）

### 交付物 / 白名单修改

| 路径 | 字节 | sha256 |
|------|------|--------|
| `analytics-server/source-profiles/mock-mall.v1.json` | 3,323 | `0bb8a05c8d5e466864dcb90b8d7b97105dc65497dc3ccb463d104f27f021170b` |
| `analytics-server/source-profiles/README.md` | 4,430 | `61c74155f8888d156c712f573f1b98669cce2f0521e0690419db24b168fedbf1` |

> 后者为**纯追加补记**：`git diff --numstat` = `29  0`（**29 增 / 0 删**，原文一字未改）。

### 本目录

| 路径 | 字节 | sha256 |
|------|------|--------|
| `IMPL-REPORT.md` | 27,848 | `6217e380288668683320cf3106e6abc5a13a95ee304c36822f37a33553838a54` |
| `RULINGS-P3-01A-20260912.md` | 4,954 | `d54017764739b44695f254d08e7479d8ae53c3bdc087c4bea9b39ca2ae8433b5` |
| `RULINGS-P3-01A-BATCH2-20260912.md` | 4,148 | `b854b158c53aeed919440fd3595f1a5b8a1b5dfb7be021bbbd19d9409b87fb66` |
| `draft/p3-01a-profile-rationale.md` | 22,816 | `0e3859e4c2aa91bd877b14f6614534e510212c2c97d41cc145a44e5e551cc591` |
| `draft/p3-01a-E2-failure-attribution.md` | 4,420 | `f76d10e5d16005bc83d164d15d823710d36ba6360aa6def98001a6ae1cb66614` |
| `raw/00-timestamps.log` | 42 | `4971863cebd7d6a1caf6af4855b969fe4d05f1d8c431bc225dca24b46f8c6e17` |
| `raw/03-db-source-registry.txt` | 2,325 | `ac37b8d4347d0989b6edabca57defdcfdf59d4dccd7abd1f56727d8122bac461` |
| `raw/04-stats-script.py` | 9,732 | `df3bd0611f8d21938999ed0a5d046a9ca43e7780c668904c8777f5d52e69133c` |
| `raw/04-stats-output.txt` | 16,501 | `b8dfea33ad986da823a79bd392514d2333d1f6978588d5b59e00f6c9d08da90a` |
| `raw/04b-scan-inventory.txt` | 44,353 | `add1bbcb3fd7361e647bfd2e1e2e4d8c33d77c68b3cfab227b9072d3b94fec21` |
| `raw/05-E3-pre-state.txt` | 2,463 | `026e3a3617a99c9f3b8a40ccffe9a581159239a1c709ca8f51efa71eed7ab380` |
| `raw/06-db-semantic-registry.txt` | 1,607 | `24597186c36b602d594efdcebf9d2124180ad5d1c66df1f224e64130ecb78d21` |
| `raw/07-time-shape-probe.py` | 4,764 | `02baabffa7d0b8f2e6254100054c63e6ce00323918e5d3ce8cafb6dc05e29350` |
| `raw/07-time-shape-output.txt` | 9,405 | `b9f0a1fa1049e3078135e41cd0c645e0681e755c909e8d538c14fd66808e3bcd` |
| `raw/08-enum-identity-probe.py` | 7,678 | `b5d7d195df43ea0a34a437d715a676c6d36bcb5683f2cfc09ce1d08bc3cc3a54` |
| `raw/08-enum-identity-output.txt` | 17,721 | `0c4c56eee2f1dd19ff6de3d2274b1b55243d9fb516e476de2086ead58757410b` |
| `raw/09-skeleton-crosscheck.py` | 7,102 | `3dd97cbace7b6fcab6dea6c479bf75c408d31bc278ca8595f35071e4b793629d` |
| `raw/09-skeleton-crosscheck-output.txt` | 9,607 | `4c0cb1fae13e431e4616153247e8382f6ce6b89ebcb74572efb5c58148cf8789` |
| `raw/10-uuid-and-tree.txt` | 2,780 | `cbfbb6cd67642bb2e2cc53d509599f5633e7dbedfaf4234596ca17c3c02ee77c` |
| `raw/10-uuid-coverage-gap.txt` | 377 | `8bbb5deab19830bdda4d5c9b62c69de84821fbd913bc88632bcbdb91d73e24ff` |
| `raw/10b-uuid-search.py` | 4,232 | `7d0bcce3868675b797658e24318f95ce850d0d8b11f52fcc067cf36df65d05fa` |
| `raw/10c-uuid-search.txt` | 1,766 | `d3a773987b399fe441d06e0a523600fae5767e06dd0bb5046fdddde7e69fe8b5` |
| `raw/11-profile-conformance.py` | 13,226 | `c366db0bb00699554fa78ec5aca8bb993dc3c62121bb7ea9dcd8a89985a9fda9` |
| `raw/11-profile-conformance-output.txt` | 2,801 | `4b508726ec9290f3c4fe81c98d551b3aad2b41298dece58c446ad6bf82172cdd` |
| `raw/12-E1-compile.log` | 3,381 | `360f07716797ef13aeeda97e07acf2c0a9de2ee56eb19c48c72e004597e9dab6` |
| `raw/13-E2-tests.log` | 89,104 | `cf03bfa4770db96af91ed15797a442e9d48dbb99a86cc4771c8fceda6ad0077e` |
| `raw/14-E3-post-state.txt` | 2,433 | `f7067ae60aad9372f35b26d06051610209fb7fc2bc92ba3748be8c9d58c148b5` |

> 本文件（`README.md`）是索引，无法自哈希；其余文件均已列出。

## 三条必须一起读的诚实声明

1. **E3 的 `ok=true` 是浅检结论**（D-139 §2.2）——该端点不校验 `timePolicy.formats` 元素语法、
   `fieldMapping` 取值合法性、`enumSemantics` 的 `null` 语义、`identityPolicy` 的 `shape`/`surrogate` 合法性。
   **不得**把 `ok=true` 读成"画像语义已合规"；语义证据是 `raw/11`。
2. **`null` 尚未冻结**（详见 `IMPL-REPORT.md` §G0）：设计书**没有任何一行**定义 `enumSemantics` 里的 `null`；
   而设计书 §4.2 **L147** 又规定"缺失的映射项 = 该源没有这个语义"，
   于是对本源真实存在（有计数）的契约外取值，**省略＝说假话**，`null` 是总控指令引入的**扩展**。待裁定。
3. **未调用 `/activate`**，故**不得**声称种子源已激活、E3 全链路已通过。
   存储态 `status=ACTIVE` 与端点 `/activate` 的 409 是**两件事**（D-136 措辞）。
