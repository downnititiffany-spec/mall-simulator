# WP-F3-FIX 修复轮规约（总控验收不合格，2026-10-16 21:46 巡检）

## 背景与不合格证据（总控亲跑，非推测）
总控亲跑 `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite web`（RunId wpf3acc202610162146，日志 /tmp/v25tests-wpf3acc/）：
- `npm test` 退出码 0，日志中汇总行齐全：`ℹ tests 450 / ℹ pass 450 / ℹ fail 0 / ℹ cancelled 0 / ℹ skipped 0 / ℹ todo 0`（web-test.log 第 455 行起）；
- `npm run build` 退出码 0；
- 但脚本解析结果 `tests=-1, pass=-1, fail=-1, ...`，判定 DRIFT → `[FAIL exit=7]`。

## 根因（总控已用最小实验确证）
`Get-WebTestTotals` 里 7 条解析正则都以 `^\s*(?:[^\w\s]\s*)?<字段>\s+(\d+)\s*$` 开头，意图用 `[^\w\s]` 吃掉汇总行前缀符号 `ℹ`。但在 .NET 正则中 `ℹ`（U+2139）属于 `\w`（实测 `"ℹ" -match '^\w$'` = True），故 `[^\w\s]` 永远匹配不到它，可选组落空后 `tests` 又对不上行首的 `ℹ`，整行永不命中，全部计数保持 -1。

## 修复要求（仅改 `scripts/run-tests.ps1` 中 `Get-WebTestTotals` 的解析部分）
1. 修正 7 个字段（tests/suites/pass/fail/cancelled/skipped/todo）的行首前缀处理，使其能匹配真实日志行 `ℹ tests 450` 这类格式；前缀处理不得依赖「符号不是 \w」这一错误假设（例如改用 `\S+\s+` 式前缀或等价的稳健写法，由你择一，但必须对 `ℹ ` 前缀与无前缀两种行都成立）。
2. 保持既有语义：多处出现时取最后一次出现；解析不到时仍返回 -1 并由上层判 FAIL（不得把解析失败伪装成 0 或放行）。
3. 不得改动基线数值（450）、判定逻辑、退出码约定、其他档（default/isolated/spark/all）的任何行为；不得改动 web/ 下文件；不得新增文件。
4. 注意：日志行除 `ℹ tests 450` 外还有形如 `✔ <测试名> (0.12ms)` 的逐条行，正则必须锚定字段名本身，不得误吞逐条行中的数字（既有「字段名 + 空格 + 纯数字 + 行尾」锚定方式应保留）。

## 环境约束
- 你只有文件读写权限，不要尝试运行任何命令；验收由总控亲跑：`-Suite web` 须输出 tests=450 MATCH、fail=0、build PASS、总退出码 0 方为合格。

## 完成标准
修复完成且满足上述 1–4；完成后在回复末尾输出一行：WPF3FIX-DONE
