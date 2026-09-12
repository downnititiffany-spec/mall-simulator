# 第3/4项（补跑）：探针结果落盘 + FAILED 快照行提示 + 运营大盘关键词探针
# 只读：仅导航与读取 DOM。
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

RAW = Path(__file__).resolve().parent.parent / "raw"
PROBES = [
    "Cannot safely cast", "RUN_JOB_FAILED", "BUILD_DWD 作业失败", "spark-ads",
    "数据源", "sourceCode", "source_id", "旧结果", "仍可用", "上一份", "未发布",
    "不影响", "回退", "保留", "失败原因", "errorMessage",
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

    for route, name in [("/overview", "8091-overview"), ("/pipeline", "8091-pipeline"), ("/ops", "8091-ops")]:
        page.goto("http://127.0.0.1:8091" + route, wait_until="networkidle", timeout=30000)
        page.wait_for_timeout(2500)
        html = page.content()
        text = page.evaluate("() => document.body.innerText")
        (RAW / f"dom-{name}.html").write_text(html, encoding="utf-8")
        (RAW / f"page-{name}.text.txt").write_text(text, encoding="utf-8")
        page.screenshot(path=str(RAW / f"page-{name}.png"), full_page=True)
        log(f"=== [{name}] url={page.url} innerText={len(text)}chars DOM={len(html)}chars")
        for probe in PROBES:
            log(f"    {probe!r}: innerText={probe in text} DOM={probe in html}")

    page.goto("http://127.0.0.1:8091/ops", wait_until="networkidle", timeout=30000)
    page.wait_for_timeout(2000)
    for sid in ["S20260901_38", "S20260901_43"]:
        row = page.locator("tr", has_text=sid).first
        titles = [e.get_attribute("title") for e in row.locator("[title]").all()]
        log(f"=== FAILED/ARCHIVED 快照行 {sid} 文本 = " + row.inner_text().replace("\n", " | "))
        log(f"    行内 [title] 提示 = {json.dumps(titles, ensure_ascii=False)}")
    (RAW / "item34-probe-results.txt").write_text("\n".join(out), encoding="utf-8")
    browser.close()
print("ITEM34B_DONE")
