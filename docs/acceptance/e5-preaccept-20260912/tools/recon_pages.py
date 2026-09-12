# E5 预验收 - 页面侦察（只读；不修改任何被测系统状态）
# 用法: python docs/acceptance/e5-preaccept-20260912/tools/recon_pages.py
import json
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright

RAW = Path(__file__).resolve().parent.parent / "raw"
RAW.mkdir(parents=True, exist_ok=True)


def dump(page, name):
    txt = page.evaluate("() => document.body.innerText")
    html = page.content()
    (RAW / f"page-{name}.text.txt").write_text(txt, encoding="utf-8")
    (RAW / f"page-{name}.title.txt").write_text(page.title(), encoding="utf-8")
    print(f"--- [{name}] url={page.url}")
    print(f"    title=<{page.title()}>  bodyInnerText chars={len(txt)}  html chars={len(html)}")
    print("    first 400 chars of innerText:")
    print("    " + txt[:400].replace("\n", "\n    "))
    return txt


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    ctx = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN")
    page = ctx.new_page()
    errors = []
    page.on("console", lambda m: errors.append(f"{m.type}: {m.text}") if m.type == "error" else None)

    for name, url in [("8090-mall-root", "http://127.0.0.1:8090/"),
                      ("8091-platform-root", "http://127.0.0.1:8091/"),
                      ("8092-generator-root", "http://127.0.0.1:8092/")]:
        try:
            resp = page.goto(url, wait_until="networkidle", timeout=30000)
            print(f"[{name}] status={resp.status if resp else None}")
        except Exception as e:  # noqa: BLE001
            print(f"[{name}] goto exception: {e}")
        page.wait_for_timeout(1500)
        dump(page, name)
        page.screenshot(path=str(RAW / f"page-{name}.png"), full_page=False)

    (RAW / "page-console-errors.txt").write_text("\n".join(errors), encoding="utf-8")
    browser.close()
print("RECON_DONE")
