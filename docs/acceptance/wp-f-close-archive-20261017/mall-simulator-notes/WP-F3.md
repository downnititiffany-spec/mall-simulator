# WP-F3 前端统一测试入口（规约）

## 目标
让 `scripts/run-tests.ps1` 支持 `-Suite web`：一条命令跑完前端测试与构建冒烟，并像其他档一样做基线计数比对，使前端验证进入统一测试入口（现况：web 测试只能单独跑 npm test，不在统一脚本内）。

## 必读
- `scripts/run-tests.ps1` 全文（现有 default/spark/isolated/all 四档的结构、基线变量、MATCH/DRIFT 判定、-AllowCountDrift 语义、退出码约定：0=通过、7=失败或漂移）。
- `web/package.json` 的 scripts（test = `node --test "tests/**/*.test.js"`，build = vite build）。

## 交付
仅修改 `scripts/run-tests.ps1` 一个文件：
1. `-Suite` 的 ValidateSet 增加 `web`；`all` 的构成是否纳入 web 由你按现有 all 的定义方式判断并在代码注释中写明（倾向：all 保持原构成不变，web 为独立档，避免改变既有验收口径）。
2. 新增 web 档执行段：定位 web/ 目录 → 运行 `npm test` 并解析其输出中的 tests/pass/fail 计数（node --test 的 ℹ 汇总行）→ 基线变量 `$BaselineWebTests = 450`（在文件既有基线区按同风格登记，附一行日期注释 `2026-10-16 WP-F3 web 基线登记：450`）→ tests 与基线比对输出 MATCH/DRIFT，fail>0 或计数漂移（且未 -AllowCountDrift）判 FAIL；随后运行 `npm run build` 作为构建冒烟，非 0 退出判 FAIL。
3. 汇总输出风格与既有档一致（每档一行 PASS/FAIL + 计数）；退出码约定与既有档一致。
4. 不得改动既有四档的任何行为、基线数字与判定逻辑；不得新增其他文件；不得改动 web/ 下任何文件。

## 环境约束
- 你只有文件读写权限，不要尝试运行任何命令（构建与测试由总控亲跑验收）。
- npm 在本机 PATH 中由总控运行时保证；脚本内不要写死 npm 绝对路径，沿用既有档对外部命令的调用风格（如 mvn 是经参数传入的——web 档如需参数请按同风格加 `-NpmCmd` 参数且给默认值 `npm`）。

## 完成标准
修改后的 run-tests.ps1 满足上述 1–4；完成后在回复末尾输出一行：WPF3-DONE
