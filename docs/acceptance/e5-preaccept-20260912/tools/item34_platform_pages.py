# 第3/4项：分析平台页面能否展示 任务状态 / 失败原因 / 结果所属数据源·日期·快照；
#          以及失败态是否显示失败、是否明确「旧结果仍可用」。
# 只读：只做导航 + 读取 DOM/文本 + 点击「查看指标」（纯查询）；不点「重试」「触发采集」。
# 用法: python docs/acceptance/e5-preaccept-20260912/tools/item34_platform_pages.py
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

RAW = Path(__file__).resolve().parent.parent / "raw"
PROBES = [
    "Cannot safely cast",          # run 46 的 errorMessage 片段（失败原因）
    "RUN_JOB_FAILED",              # 错误码
    "BUILD_DWD 作业失败",           # errorMessage 前缀
    "spark-ads",                   # /metrics/snapshots 的 source 字段值（数据源）
    "数据源", "sourceCode", "source_id",
    "旧结果", "仍可用", "上一份", "未发布", "不影响", "回退", "保留",
]
out = []


def log(s):
    print(s)
    out.append(s)


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    ctx = browser.new_context(viewport={"width": 1600, "height": 1100}, locale="zh-CN")
    page = ctx.new_page()
    page.goto("http://127.0.0.1:8091/login", wait_until="networkidle", timeout=30000)
    page.fill("#username", "admin")
    page.fill("#password", "admin123")
    page.click(".login-btn")
    page.wait_for_timeout(2500)

    for route, name in [("/pipeline", "8091-pipeline"), ("/ops", "8091-ops")]:
        page.goto("http://127.0.0.1:8091" + route, wait_until="networkidle", timeout=30000)
        page.wait_for_timeout(2500)
        html = page.content()
        text = page.evaluate("() => document.body.innerText")
        (RAW / f"dom-{name}.html").write_text(html, encoding="utf-8")
        (RAW / f"page-{name}.text.txt").write_text(text, encoding="utf-8")
        page.screenshot(path=str(RAW / f"page-{name}.png"), full_page=True)
        log(f"=== [{name}] url={page.url} innerText={len(text)} chars  DOM={len(html)} chars")
        for probe in PROBES:
            in_text = probe in text
            in_dom = probe in html
            log(f"    probe {probe!r}: innerText={in_text} DOM={in_dom}")
        # 表格行里是否有 title/aria 提示（悬浮说明）
        titles = page.eval_on_selector_all("[title]", "els => els.map(e => e.getAttribute('title'))")
        log(f"    DOM 中 title 属性值 = {json.dumps(titles, ensure_ascii=False)[:600]}")

    # ---- 点击 S20260901_43（ARCHIVED）的「查看指标」：验证历史（旧）结果是否仍可读 ----
    page.goto("http://127.0.0.1:8091/ops", wait_until="networkidle", timeout=30000)
    page.wait_for_timeout(2500)
    row43 = page.locator("tr", has_text="S20260901_43").first
    log("=== 点击 ARCHIVED 快照 S20260901_43 行的「查看指标」===")
    log("    点击前该行文本 = " + row43.inner_text().replace("\n", " | "))
    row43.get_by_role("button", name="查看指标").click()
    page.wait_for_timeout(2500)
    body = page.evaluate("() => document.body.innerText")
    (RAW / "page-8091-ops-after-view-43.text.txt").write_text(body, encoding="utf-8")
    page.screenshot(path=str(RAW / "page-8091-ops-after-view-43.png"), full_page=True)
    idx = body.find("快照 S20260901_43 指标")
    log("    「查看指标」后页面片段（快照 S20260901_43 指标 起 900 字）:")
    log(body[idx:idx + 900] if idx >= 0 else "(未找到『快照 S20260901_43 指标』标题)")

    # ---- S20260901_38（FAILED 快照）行：是否给出失败原因 ----
    row38 = page.locator("tr", has_text="S20260901_38").first
    log("=== FAILED 快照行 S20260901_38 ===")
    log("    行文本 = " + row38.inner_text().replace("\n", " | "))
    log("    行内 title 属性 = " + json.dumps(row38.eval_on_selector_all("[title]", "els => els.map(e => e.getAttribute('title'))"), ensure_ascii=False))

    (RAW / "item34-probe-results.txt").write_text("\n".join(out), encoding="utf-8")
    browser.close()
print("ITEM34_DONE")
