# 边界测试（停掉 8090/8092 之后）：用 8091 页面再查一次「同一快照」的指标，并与停之前对照。
# 只读：仅导航 + 读取渲染文本 + 截图。
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

RAW = Path(__file__).resolve().parent.parent / "raw"
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
    for route, name in [("/overview", "after-stop-overview"), ("/ops", "after-stop-ops")]:
        page.goto("http://127.0.0.1:8091" + route, wait_until="networkidle", timeout=30000)
        page.wait_for_timeout(2500)
        text = page.evaluate("() => document.body.innerText")
        (RAW / f"page-8091-{name}.text.txt").write_text(text, encoding="utf-8")
        page.screenshot(path=str(RAW / f"page-8091-{name}.png"), full_page=True)
        log(f"=== [{route}] url={page.url} 可见文本 {len(text)} chars")
        for line in text.splitlines():
            if any(k in line for k in ("S2026", "GMV", "质量", "快照", "业务时间", "数据更新", "uv", "pv", "gmv", "销售额", "订单")):
                log("    | " + line.strip()[:160])
    # 页面是否报「采集/商城/生成器不可用」
    text = page.evaluate("() => document.body.innerText")
    for kw in ("不可用", "离线", "连接失败", "商城", "生成器", "采集"):
        log(f"    keyword {kw!r} 出现于页面文本: {kw in text}")
    (RAW / "boundary-after-stop-page.log").write_text("\n".join(out), encoding="utf-8")
    browser.close()
print("BOUNDARY_AFTER_STOP_DONE")
