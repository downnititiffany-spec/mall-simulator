"""R7-4 商城侧（8090）前后端边界与四页可用性实测。

边界依据：指导书 V2.0 §18.4 / §31 第 6 条——分析前端只属 8091，商城前端只属 8090。
断言：
  1) 8090 商城 SPA 登录页可用（账号来自商城库 mall_simulator）
  2) 登录后 /mall、/admin-products、/generator 三页渲染出各自页面标题
  3) 越界检查：8090 不提供分析接口（/api/v1/dashboards/overview 非 200）、
     商城页面不出现分析看板标记（快照/口径版本/GMV 卡片），商城 SPA 路由不含分析页面
  4) 全程 0 页面错误 / 0 console 错误
"""
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright

MALL = "http://127.0.0.1:8090"
OUT = Path(".verify/r7-4-mall-dom")
OUT.mkdir(parents=True, exist_ok=True)

results = []
page_errors = []
console_errors = []


def check(name, ok, detail=""):
    results.append({"name": name, "ok": bool(ok), "detail": str(detail)[:400]})
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {str(detail)[:200]}")


with sync_playwright() as p:
    browser = p.chromium.launch()
    ctx = browser.new_context(viewport={"width": 1440, "height": 900})
    page = ctx.new_page()
    page.on("pageerror", lambda e: page_errors.append(str(e)))
    page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)

    # 1) 未登录 → 跳登录页
    page.goto(MALL + "/", wait_until="networkidle")
    check("8090 根路径进入商城登录页", "/login" in page.url, page.url)
    check("登录页标题为「模拟商城」", "模拟商城" in page.inner_text("body"),
          page.inner_text(".login-title") if page.query_selector(".login-title") else "")
    check("登录页提示账号库为商城库 mall_simulator", "mall_simulator" in page.inner_text("body"))
    page.screenshot(path=str(OUT / "01-mall-login.png"), full_page=True)

    # 2) 登录（商城库账号 admin/admin123）
    page.fill("#username", "admin")
    page.fill("#password", "admin123")
    page.click(".login-btn")
    # 注意：不能按"URL 以 /mall 结尾"判断 —— 未登录时的 /login?redirect=/mall 也满足，
    # 必须比对 urlparse().path（且等 localStorage 真正写入令牌）。
    page.wait_for_function(
        "() => location.pathname === '/mall' && !!localStorage.getItem('mall_token')",
        timeout=15000)
    check("登录后进入商城演示页 /mall", urlparse(page.url).path == "/mall", page.url)
    check("登录令牌写入 localStorage(mall_token)",
          bool(page.evaluate("() => localStorage.getItem('mall_token')")))
    body = page.inner_text("body")
    check("/mall 渲染「商城演示」", "商城演示" in body)
    page.screenshot(path=str(OUT / "02-mall.png"), full_page=True)

    # 3) 商品后台 / 生成器
    page.goto(MALL + "/admin-products", wait_until="networkidle")
    body = page.inner_text("body")
    check("/admin-products 渲染商品后台（含新建商品表单）", page.query_selector("input[placeholder='商品名称']") is not None,
          body[:120].replace("\n", " "))
    page.screenshot(path=str(OUT / "03-admin-products.png"), full_page=True)

    page.goto(MALL + "/generator", wait_until="networkidle")
    body = page.inner_text("body")
    check("/generator 渲染数据生成器", "数据生成器" in body)
    check("生成器页面注明生成入口属商城侧（§18.4）", "不再提供生成入口" in body)
    page.screenshot(path=str(OUT / "04-generator.png"), full_page=True)

    # 4) 越界检查 A：商城页面不得出现分析看板标记
    markers = ["快照 S", "口径版本", "质量 PASS", "分析看板", "AI 问数", "决策任务"]
    hits = [m for m in markers if m in page.inner_text("body")]
    check("商城页面不含分析看板标记", not hits, f"命中: {hits}")

    # 5) 越界检查 B：越界路由回落商城首页而非分析页面
    page.goto(MALL + "/ops", wait_until="networkidle")
    check("访问分析路由 /ops 不渲染分析页面（回落商城登录/首页）",
          "运营看板" not in page.inner_text("body") and "快照" not in page.inner_text("body"),
          page.url)

    # 6) 越界检查 C：商城进程不提供分析 API
    r = ctx.request.get(MALL + "/api/v1/dashboards/overview")
    check("8090 不提供分析接口 /api/v1/dashboards/overview", r.status != 200, f"HTTP {r.status}")
    r2 = ctx.request.get(MALL + "/api/v1/metrics/health")
    check("8090 不提供指标库健康接口 /api/v1/metrics/health", r2.status != 200, f"HTTP {r2.status}")

    # 7) 商城自身接口可用（登录拿 token + 商品列表）
    r3 = ctx.request.post(MALL + "/api/v1/auth/login",
                          data=json.dumps({"username": "admin", "password": "admin123"}),
                          headers={"Content-Type": "application/json"})
    tok = ""
    try:
        tok = (r3.json().get("data") or {}).get("token") or ""
    except Exception:
        pass
    check("商城自身登录接口可用（返回 token）", r3.status == 200 and bool(tok), f"HTTP {r3.status}")
    r4 = ctx.request.get(MALL + "/api/v1/mall/products",
                         headers={"Authorization": f"Bearer {tok}"} if tok else {})
    check("商城自身商品接口可用 /api/v1/mall/products", r4.status == 200, f"HTTP {r4.status}")

    check("0 页面错误", not page_errors, page_errors[:3])
    check("0 console 错误", not console_errors, console_errors[:3])
    browser.close()

report = {
    "target": MALL,
    "assertions": len(results),
    "failed": sum(1 for r in results if not r["ok"]),
    "results": results,
    "pageErrors": page_errors,
    "consoleErrors": console_errors,
}
Path(".verify/r7-4-mall-dom-report.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"[r7-4-mall-dom] assertions={report['assertions']} failed={report['failed']} "
      f"pageErrors={len(page_errors)} consoleErrors={len(console_errors)}")
sys.exit(1 if report["failed"] else 0)
