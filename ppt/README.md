# ppt/ — 毕业答辩网页 PPT

单文件横滑演示（瑞士国际主义 · IKB 克莱因蓝 · 16 页），基于 guizang-ppt-skill 风格 B 模板生成。

## 使用

```bash
# 直接打开（推荐全屏浏览器）
start ppt/index.html

# 交互：← → 翻页 · 滚轮/触屏 · ESC 索引 · B 静态模式（低功耗防闪）
```

## 结构

| 页 | 版式 | 内容 |
|---|---|---|
| 1-3 | S01/S09/S18 | 封面 · 背景问题 · Why Now |
| 4-8 | S17/S11/S03/S14/S04 | 架构 · 数据链 · 设计立场 · 受控 T2SQL · 四层校验 |
| 9-13 | S06/S21/S20/S08/S19 | Spark 链 · 性能 · 对账安全 · Before/After · 创新点 |
| 14-16 | S12/S02/S10 | 宣言 · 决策闭环 · 致谢 |

## 定制

- **换主题色**：`index.html` 的 `:root` 块整体替换为 `references/themes-swiss.md` 的柠檬黄/柠檬绿/安全橙变量；
- **改文案**：直接编辑各 `<section>` 内的中文文本（数据务必与 `experiments/` 归档一致）；
- **版式约束**：正文页必须保留 `data-layout="Sxx"` 登记版式（见 skill 的 swiss-layout-lock.md），不新增结构；
- **校验**：`node <skill>/scripts/validate-swiss-deck.mjs ppt/index.html`；
- **截图重采**：起后端 → `scripts/run-demo.ps1` → `node scripts/shot.js`（产出到 docs/thesis-materials/screenshots/）。

## 数据来源声明

页内全部数字来自本机实测（2026-09-06），对应：

- `experiments/perf-web-tier1.json`（P95 21-29ms / 254×）
- `experiments/spark-chain-local-*.json`（13 万事件全链）
- `experiments/spark-scale-local-20260906.json`（三档规模）
- `experiments/ai-eval-20260906-160900.json`（50/50 拦截、58 修复）
- 黄金对账/决策效果：`docs/acceptance-checklist.md` 与测试报告