# 第1项补充取证：三程序首屏是否陈述「自身职责」。只读：导航 + 读取渲染文本。
from pathlib import Path
from playwright.sync_api import sync_playwright

RAW = Path(__file__).resolve().parent.parent / "raw"
KWS = ["职责", "定位", "本页", "本系统", "本平台", "事件来源", "上游", "下游", "数据来源系统", "模拟商城", "分析平台", "生成器"]
out = []


def log(s):
    print(s)
    out.append(s)


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
    page = ctx.new_page()
    # 8091 登录页（未登录首屏）
    page.goto("http://127.0.0.1:8091/login", wait_until="networkidle", timeout=30000)
    page.wait_for_timeout(1500)
    t = page.evaluate("() => document.body.innerText")
    (RAW / "page-8091-login.text.txt").write_text(t, encoding="utf-8")
    page.screenshot(path=str(RAW / "page-8091-login.png"))
    log("=== [8091 /login 未登录首屏] 全文 ===")
    log(t)
    log("  关键词命中: " + str({k: (k in t) for k in KWS}))
    # 登录后再看 /overview 是否有职责陈述
    page.fill("#username", "admin")
    page.fill("#password", "admin123")
    page.click(".login-btn")
    page.wait_for_timeout(2500)
    page.goto("http://127.0.0.1:8091/overview", wait_until="networkidle", timeout=30000)
    page.wait_for_timeout(2000)
    t2 = page.evaluate("() => document.body.innerText")
    log("=== [8091 /overview] 关键词命中: " + str({k: (k in t2) for k in KWS}))
    (RAW / "item1-responsibility-probe.txt").write_text("\n".join(out), encoding="utf-8")
    browser.close()
print("ITEM1_RESP_DONE")
