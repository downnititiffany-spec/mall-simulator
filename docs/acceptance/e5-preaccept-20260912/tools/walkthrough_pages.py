# E5 预验收 - 页面走查（Playwright，headless chromium）
# 只做「打开页面 / 登录 / 读取渲染文本 / 截图」，不点任何写操作按钮。
# 用法: python docs/acceptance/e5-preaccept-20260912/tools/walkthrough_pages.py
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "raw"
RAW.mkdir(parents=True, exist_ok=True)


def dump(page, name, full=False):
    txt = page.evaluate("() => document.body.innerText")
    (RAW / f"page-{name}.text.txt").write_text(txt, encoding="utf-8")
    (RAW / f"page-{name}.url.txt").write_text(page.url, encoding="utf-8")
    (RAW / f"page-{name}.title.txt").write_text(page.title(), encoding="utf-8")
    page.screenshot(path=str(RAW / f"page-{name}.png"), full_page=full)
    print(f"=== [{name}] url={page.url} title=<{page.title()}> chars={len(txt)}")
    print(txt[:2500])
    print("=== end ---")
    return txt


def login(page, base, user, pwd, btn=".login-btn"):
    page.goto(base + "/login", wait_until="networkidle", timeout=30000)
    page.fill("#username", user)
    page.fill("#password", pwd)
    page.click(btn)
    page.wait_for_timeout(2500)
    try:
        page.wait_for_load_state("networkidle", timeout=15000)
    except Exception:  # noqa: BLE001
        pass


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000}, locale="zh-CN")

    # ---------- 8091 分析平台 ----------
    page = ctx.new_page()
    login(page, "http://127.0.0.1:8091", "admin", "admin123")
    dump(page, "8091-overview")
    for route, name in [("/pipeline", "8091-pipeline"), ("/ops", "8091-ops"),
                        ("/behavior", "8091-behavior"), ("/sales", "8091-sales")]:
        try:
            page.goto("http://127.0.0.1:8091" + route, wait_until="networkidle", timeout=30000)
            page.wait_for_timeout(2000)
            dump(page, name, full=True)
        except Exception as e:  # noqa: BLE001
            print(f"!!! {name} 异常: {e}")
    page.close()

    # ---------- 8090 模拟商城 ----------
    page2 = ctx.new_page()
    login(page2, "http://127.0.0.1:8090", "admin", "admin123")
    dump(page2, "8090-mall")
    try:
        page2.goto("http://127.0.0.1:8090/admin-products", wait_until="networkidle", timeout=30000)
        page2.wait_for_timeout(1500)
        dump(page2, "8090-admin-products")
    except Exception as e:  # noqa: BLE001
        print(f"!!! 8090-admin-products 异常: {e}")
    page2.close()

    browser.close()
print("WALKTHROUGH_DONE")
