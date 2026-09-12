# 第2项：在模拟商城页面产生「少量行为」（1 次加购）→ 观测 landing/events 落盘
# 只点「+ 加购」一次；不注册新用户、不下单、不支付。
# 用法: python docs/acceptance/e5-preaccept-20260912/tools/item2_mall_behavior.py <existingUserId>
import hashlib
import json
import sys
import time
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "raw"
EVENTS = Path(r"D:\Develop_code\GraduationProject\landing\events")
USER = sys.argv[1] if len(sys.argv) > 1 else "2098607950599319554"
lines = []


def land_snapshot():
    out = {}
    for p in sorted(EVENTS.glob("*.jsonl")):
        st = p.stat()
        out[p.name] = (st.st_size, st.st_mtime)
    return out


def log(s):
    print(s)
    lines.append(s)


before = land_snapshot()
log(f"BEFORE landing/events 文件数 = {len(before)}")
log(f"BEFORE 最新 3 个 = {list(before.items())[-3:]}")
for name, (size, mtime) in before.items():
    if name.startswith("202609122"):
        log(f"BEFORE 本小时文件已存在: {name} size={size}")

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000}, locale="zh-CN")
    page = ctx.new_page()
    page.goto("http://127.0.0.1:8090/login", wait_until="networkidle", timeout=30000)
    page.fill("#username", "admin")
    page.fill("#password", "admin123")
    page.click(".login-btn")
    page.wait_for_timeout(2500)
    # 以「已存在的历史用户」身份操作：直接写 localStorage 里的演示用户键，避免注册新用户写入新业务行
    page.evaluate("(u) => localStorage.setItem('mall_demo_user', u)", USER)
    page.goto("http://127.0.0.1:8090/mall", wait_until="networkidle", timeout=30000)
    page.wait_for_timeout(2500)
    log(f"PAGE url={page.url} title=<{page.title()}>")
    log(f"PAGE 当前用户行: {page.locator('text=当前用户').first.inner_text() if page.locator('text=当前用户').count() else '(未见)'}")
    btns = page.locator("button", has_text="加购")
    log(f"PAGE 「+ 加购」按钮数 = {btns.count()}")
    first_row = page.locator("tbody tr").first.inner_text().replace("\n", " | ")
    log(f"PAGE 首个商品行 = {first_row}")
    (RAW / "page-8090-mall-before-addcart.png").write_bytes(b"")
    page.screenshot(path=str(RAW / "page-8090-mall-before-addcart.png"))
    btns.first.click()
    page.wait_for_timeout(2500)
    try:
        toast = page.locator("div", has_text="已加入购物车").last.inner_text()
    except Exception as e:  # noqa: BLE001
        toast = f"(未捕获 toast: {e})"
    log(f"ACTION 点击「+ 加购」→ 页面提示: {toast}")
    body = page.evaluate("() => document.body.innerText")
    (RAW / "page-8090-mall-after-addcart.text.txt").write_text(body, encoding="utf-8")
    page.screenshot(path=str(RAW / "page-8090-mall-after-addcart.png"), full_page=True)
    log("PAGE 购物车区域文本 = " + (" / ".join(l for l in body.splitlines() if "购物车" in l or "×" in l)[:300]))
    browser.close()

# 等 outbox 发布器（3 s 间隔）把事件写入 landing
new_or_changed = []
for _ in range(20):
    time.sleep(1.5)
    after = land_snapshot()
    new_or_changed = [n for n, v in after.items() if n not in before or v != before[n]]
    if new_or_changed:
        break
after = land_snapshot()
log(f"AFTER  landing/events 文件数 = {len(after)}")
log(f"AFTER  新增或变化的文件 = {new_or_changed}")
for n in new_or_changed:
    f = EVENTS / n
    data = f.read_bytes()
    log(f"FILE {n} size={len(data)} sha256={hashlib.sha256(data).hexdigest()}")
    txt = data.decode("utf-8", "replace")
    tail = [l for l in txt.splitlines() if l.strip()][-3:]
    log("LAST_LINES " + json.dumps(tail, ensure_ascii=False)[:1200])
    (RAW / f"landing-newfile-{n}.txt").write_text(txt[-4000:], encoding="utf-8")
(RAW / "item2-mall-behavior.log").write_text("\n".join(lines), encoding="utf-8")
print("ITEM2_DONE")
