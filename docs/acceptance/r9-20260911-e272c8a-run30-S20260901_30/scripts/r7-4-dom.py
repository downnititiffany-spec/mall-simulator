"""R7-4 看板 DOM 验收 v2（§18.5 第 4 处一致性证据 + 8 页四态/交互实测）

前提：分析平台已在 127.0.0.1:8091 运行（jar 内含 web/dist 静态资源）。
用法：python .verify/r7-4-dom.py
产物：.verify/r7-4-dom/*.png 截图 + .verify/r7-4-dom-report.json

覆盖：
  A. §18.5 四路一致性：页面 DOM 黄金值 == MySQL ACTIVE == REST == Hive ADS（前两路由本脚本/REST 证明）
  B. 8 页渲染（overview/behavior/products/sales/rfm/pipeline/ops/ai-assistant/decisions）
  C. 交互：Ops 快照行「查看指标」按钮、AiAssistant 真实问数（输入→发送→结果表）
  D. 四态：ready（默认）/ empty（显式不存在的快照号）/ error（拦截接口制造失败）
  E. 0 页面错误 / 0 console 错误
"""
import json
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:8091"
OUT = Path(".verify/r7-4-dom")
OUT.mkdir(parents=True, exist_ok=True)

GOLDEN = {
    "销售额(GMV)": "2,042.00元",
    "支付订单数": "5单",
    "浏览量(PV)": "7次",
    "退款率": "60.00%",
}

report = {"base": BASE, "pages": {}, "cards": {}, "golden": GOLDEN, "errors": [], "console": [],
          "consoleIsolated": [], "assertions": []}


def card_texts(page):
    cards = {}
    for el in page.query_selector_all(".metric-card"):
        label = el.query_selector(".label")
        value = el.query_selector(".value")
        if label and value:
            cards[label.inner_text().strip()] = value.inner_text().strip()
    return cards


def body_text(page):
    return page.inner_text("body")


def iso_page():
    """独立 context 的页面：用于「故意失败」的用例（业务守卫 400、未知快照四态、拦截制造 error），
    这些用例必然在 console 留下 Failed to load resource，不能污染主流程的 0 console 错误断言。
    复用主 context 的登录态（storage_state），因此不需要重新登录。"""
    ctx = browser.new_context(viewport={"width": 1440, "height": 900}, storage_state=auth_state)
    pg = ctx.new_page()
    pg.on("console", lambda m: report["consoleIsolated"].append(f"{m.type}: {m.text}") if m.type == "error" else None)
    pg.on("pageerror", lambda e: report["errors"].append(f"[isolated] pageerror: {e}"))
    return pg


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    page.on("console", lambda m: report["console"].append(f"{m.type}: {m.text}") if m.type == "error" else None)
    page.on("pageerror", lambda e: report["errors"].append(f"pageerror: {e}"))
    page.on("requestfailed", lambda r: report["errors"].append(f"requestfailed: {r.url} {r.failure}"))
    bad_responses = []
    page.on("response", lambda r: bad_responses.append(f"{r.status} {r.url}") if r.status >= 400 else None)
    external_requests = []
    page.on("request", lambda r: external_requests.append(r.url))
    auth_state = None

    # 1) 登录
    page.goto(f"{BASE}/login", wait_until="networkidle")
    page.fill("#username", "admin")
    page.fill("#password", "admin123")
    page.click(".login-btn")
    page.wait_for_url(re.compile(r".*/overview"), timeout=20000)
    page.wait_for_load_state("networkidle")
    keys = page.evaluate("() => Object.keys(localStorage)")
    auth_state = page.context.storage_state()
    report["assertions"].append({"label": "登录态键名为分析平台自有键（§18.4 边界）",
                                 "expected": "含 analytics_token，且不含 mall_token",
                                 "dom": keys,
                                 "pass": ("analytics_token" in keys) and ("mall_token" not in keys)})

    # 2) 运营大盘：指标卡 + 快照元信息
    page.wait_for_selector(".metric-card", timeout=20000)
    page.wait_for_timeout(800)
    page.screenshot(path=str(OUT / "overview.png"), full_page=True)
    cards = card_texts(page)
    report["cards"] = cards
    ctx_text = (page.query_selector(".analysis-context").inner_text().replace("\n", " | ")
                if page.query_selector(".analysis-context") else None)
    report["pages"]["overview"] = {
        "url": page.url,
        "title": (page.query_selector(".page-title").inner_text().strip() if page.query_selector(".page-title") else None),
        "metricCardCount": len(cards),
        "contextText": ctx_text,
        "chartCount": len(page.query_selector_all("canvas")),
    }
    for label, expect in GOLDEN.items():
        got = cards.get(label)
        ok = got == expect
        report.setdefault("assertions", []).append(
            {"label": f"overview/{label}", "expected": expect, "dom": got, "pass": ok})
        if not ok:
            report["errors"].append(f"黄金值不一致 {label}: DOM={got!r} 期望={expect!r}")
    # ACTIVE 快照号不写死（发布新快照后 ID 会变），从页面真实上下文里取出，供下面「不得回退 ACTIVE」复用
    m_ctx = re.search(r"S\d{8}_\d+", ctx_text or "")
    active_snapshot = m_ctx.group(0) if m_ctx else None
    ctx_ok = bool(ctx_text) and active_snapshot is not None and "2026-09-01" in ctx_text
    report["assertions"].append({"label": f"overview/快照上下文({active_snapshot}+业务时间)",
                                 "expected": "含快照号与业务时间", "dom": ctx_text, "pass": ctx_ok})
    if not ctx_ok:
        report["errors"].append(f"概览页快照上下文缺失: {ctx_text!r}")

    # 3) 8 页渲染（其余页；路由表见 web/src/router.js：智能分析助手是 /ai）
    for name, path in [("behavior", "/behavior"), ("products", "/products"), ("sales", "/sales"),
                       ("rfm", "/rfm"), ("pipeline", "/pipeline"), ("ops", "/ops"),
                       ("ai-assistant", "/ai"), ("decisions", "/decisions")]:
        page.goto(f"{BASE}{path}", wait_until="networkidle")
        page.wait_for_timeout(1200)
        page.screenshot(path=str(OUT / f"{name}.png"), full_page=True)
        body = body_text(page)
        info = {
            "url": page.url,
            "title": (page.query_selector(".page-title").inner_text().strip() if page.query_selector(".page-title") else None),
            "chartCount": len(page.query_selector_all("canvas")),
            "tableRows": len(page.query_selector_all("tbody tr")),
            "hasContext": page.query_selector(".analysis-context") is not None,
            "hasEmpty": "暂无" in body,
            "hasError": "数据加载失败" in body,
            "firstLines": [ln for ln in body.splitlines() if ln.strip()][:14],
        }
        report["pages"][name] = info
        report["assertions"].append({
            "label": f"{name}/渲染无错误态",
            "expected": "无「数据加载失败」且页面有标题",
            "dom": f"title={info['title']} error={info['hasError']}",
            "pass": (not info["hasError"]) and bool(info["title"]),
        })
        if info["hasError"] or not info["title"]:
            report["errors"].append(f"{path} 渲染异常: {info}")

    # 4) 交互 A：Ops 快照行「查看指标」按钮（真点击，且必须点在快照表而不是用户表）
    page.goto(f"{BASE}/ops", wait_until="networkidle")
    page.wait_for_timeout(1200)
    snap_btn = None
    for tbl in page.query_selector_all("table"):
        head = tbl.query_selector("thead")
        if head and "快照" in head.inner_text():
            snap_btn = tbl.query_selector("tbody tr button")
            if snap_btn:
                break
    selected_before = None
    ctx_el = page.query_selector(".analysis-context")
    if ctx_el:
        selected_before = ctx_el.inner_text().replace("\n", " ")
    clicked = False
    if snap_btn:
        snap_btn.click()
        clicked = True
        page.wait_for_timeout(2000)
    after = body_text(page)
    ctx_after = page.query_selector(".analysis-context")
    metric_rows = [ln for ln in after.splitlines() if re.search(r"(avg_order_value|paid_order_cnt|refund_rate|^\s*pv\b)", ln, re.I)]
    report["opsInteraction"] = {"clickedSnapshotActionButton": clicked,
                                "contextBefore": selected_before,
                                "contextAfter": ctx_after.inner_text().replace("\n", " ") if ctx_after else None,
                                "rowsAfterClick": len(page.query_selector_all("tbody tr")),
                                "metricLines": metric_rows[:6]}
    report["assertions"].append({"label": "ops/点击快照行「查看指标」后出现指标行",
                                 "expected": "≥1 行含 avg_order_value/pv/refund_rate 等列",
                                 "dom": f"clicked={clicked} lines={len(metric_rows)}",
                                 "pass": clicked and len(metric_rows) >= 1})
    if not (clicked and metric_rows):
        report["errors"].append("Ops 页快照行「查看指标」点击后未出现指标行")

    # 4b) 业务守卫的错误必须落到页面提示（真实调用：禁用当前登录账号 → 后端 400 FORBIDDEN_OPERATION）。
    #     该类用例必然产生「故意的失败请求」，因此放到独立 context 里跑，不污染主流程的 console 断言。
    iso = iso_page()
    if iso:
        iso.on("dialog", lambda d: d.dismiss())  # 重置密码用 prompt；禁用/启用不用，先兜住
        dis = None
        for tbl in iso.query_selector_all("table"):
            head = tbl.query_selector("thead")
            if head and "角色" in head.inner_text():
                for b in tbl.query_selector_all("tbody tr button"):
                    if b.inner_text().strip() == "禁用":
                        dis = b
                        break
        if dis:
            dis.click()
            iso.wait_for_timeout(1500)
            guard_body = body_text(iso)
            iso.screenshot(path=str(OUT / "ops-guard-error.png"), full_page=True)
            guard_ok = ("不能停用当前登录账号" in guard_body) or ("FORBIDDEN_OPERATION" in guard_body)
            report["assertions"].append({"label": "ops/后端业务守卫错误在页面可见",
                                         "expected": "页面出现「不能停用当前登录账号」提示",
                                         "dom": [ln for ln in guard_body.splitlines() if "停用" in ln or "失败" in ln][:4],
                                         "pass": guard_ok})
            if not guard_ok:
                report["errors"].append("Ops 页未把后端 400 业务守卫错误显示出来")
        iso.context.close()
    else:
        report["errors"].append("无法创建独立验收上下文（业务守卫用例未执行）")

    # 5) 交互 B：AiAssistant 真实问数（输入 → 发送 → 结果表）
    #    注意：问数输入框是没有 type 属性的 <input>，选择器必须覆盖 input:not([type])
    page.goto(f"{BASE}/ai", wait_until="networkidle")
    page.wait_for_timeout(1200)
    box = page.query_selector("input:not([type]), input[type=text], textarea")
    send = None
    for b in page.query_selector_all("button"):
        if b.inner_text().strip() in ("发送", "提问", "查询", "问数"):
            send = b
            break
    if box and send:
        box.fill("最新一期的 GMV 和退款率是多少？")
        send.click()
        for _ in range(24):
            page.wait_for_timeout(500)
            if "数据依据" in body_text(page) or "请求失败" in body_text(page):
                break
        page.wait_for_timeout(800)
    ai_body = body_text(page)
    page.screenshot(path=str(OUT / "ai-assistant-after-query.png"), full_page=True)
    has_exec = ("EXECUTED" in ai_body) or ("成功" in ai_body)
    has_sql = "SELECT" in ai_body.upper()
    ai_rows = len(page.query_selector_all("tbody tr"))
    report["aiInteraction"] = {"inputFound": bool(box), "sendFound": bool(send),
                               "hasExecuted": has_exec, "hasSql": has_sql, "tableRows": ai_rows,
                               "lines": [ln for ln in ai_body.splitlines() if ln.strip()][:25]}
    report["assertions"].append({"label": "ai-assistant/真实问数返回结果",
                                 "expected": "页面出现 SQL 与结果行",
                                 "dom": f"sql={has_sql} rows={ai_rows}",
                                 "pass": has_sql and ai_rows >= 1})
    if not has_sql or ai_rows < 1:
        report["errors"].append(f"AI 问数页面未见 SQL/结果行: rows={ai_rows}")

    # 6) 四态 C：empty —— 用「真实后端对未知快照号的响应体」逐字回放（非编造），
    #    断言页面显示空态 + UNKNOWN_SNAPSHOT 警告，且不回退 ACTIVE 快照的黄金值。
    #    真实响应采集：GET /api/v1/dashboards/overview?snapshotId=S-NOT-EXIST
    #    → {"snapshotId":null,...,"qualityStatus":"UNKNOWN","data":{},"warnings":["UNKNOWN_SNAPSHOT"]}
    unknown_body = {
        "code": "OK",
        "message": "success",
        "data": {
            "snapshotId": None, "businessTime": None, "dataUpdatedAt": None,
            "definitionVersion": None, "qualityStatus": "UNKNOWN",
            "filters": {"snapshotId": "S-NOT-EXIST"}, "data": {}, "warnings": ["UNKNOWN_SNAPSHOT"],
        },
        "traceId": "dom-empty-state-replay",
    }
    iso2 = iso_page()
    iso2.route("**/api/v1/dashboards/overview*",
               lambda route: route.fulfill(status=200, content_type="application/json",
                                           body=json.dumps(unknown_body, ensure_ascii=False)))
    iso2.goto(f"{BASE}/products?snapshotId=S-NOT-EXIST", wait_until="networkidle")
    iso2.wait_for_timeout(500)
    iso2.goto(f"{BASE}/overview?snapshotId=S-NOT-EXIST", wait_until="networkidle")
    iso2.wait_for_timeout(1500)
    empty_body = body_text(iso2)
    empty_cards = card_texts(iso2)
    iso2.screenshot(path=str(OUT / "overview-unknown-snapshot.png"), full_page=True)
    unknown_ok = ("UNKNOWN_SNAPSHOT" in empty_body) or ("不存在" in empty_body) or ("无该快照" in empty_body)
    no_active_fallback = ((active_snapshot is None or active_snapshot not in empty_body)
                          and "2,042.00" not in empty_body)
    report["emptyState"] = {"cards": empty_cards, "lines": [ln for ln in empty_body.splitlines() if ln.strip()][:14]}
    report["assertions"].append({"label": "overview/未知快照 → 空态且不回退 ACTIVE",
                                 "expected": "提示未知快照且不出现 ACTIVE 快照号/黄金值",
                                 "dom": f"unknown={unknown_ok} noFallback={no_active_fallback} cards={empty_cards}",
                                 "pass": unknown_ok and no_active_fallback})
    if not (unknown_ok and no_active_fallback):
        report["errors"].append(f"未知快照未按空态处理或回退了 ACTIVE: {empty_body[:200]!r}")
    iso2.context.close()

    # 7) 四态 D：error（拦截概览接口制造失败 → 页面必须显示失败态而不是空白；同样用独立 context）
    iso3 = iso_page()
    iso3.route("**/api/v1/dashboards/overview*", lambda route: route.abort())
    iso3.goto(f"{BASE}/overview", wait_until="networkidle")
    iso3.wait_for_timeout(1500)
    err_body = body_text(iso3)
    iso3.screenshot(path=str(OUT / "overview-error-state.png"), full_page=True)
    has_err = "数据加载失败" in err_body
    report["assertions"].append({"label": "overview/接口失败 → 显示失败态",
                                 "expected": "出现「数据加载失败」",
                                 "dom": [ln for ln in err_body.splitlines() if ln.strip()][:8],
                                 "pass": has_err})
    if not has_err:
        report["errors"].append("拦截接口后概览页未显示失败态")
    iso3.context.close()

    # 8) 离线可运行：全流程不得发起任何非 127.0.0.1 的网络请求（字体/CDN 已移除）
    external = [u for u in external_requests if not u.startswith(("http://127.0.0.1", "http://localhost"))]
    report["assertions"].append({"label": "离线可运行（无非本机外网请求）",
                                 "expected": "0 个外部请求",
                                 "dom": external[:5],
                                 "pass": not external})
    if external:
        report["errors"].append(f"页面发起了外部网络请求: {external[:5]}")

    # 9) 主流程页面错误与 console 错误必须为 0（故意失败的用例已隔离到独立 context）
    check_console = list(report["console"])
    check_ext = [e for e in report["errors"] if not e.startswith("[isolated]")]
    report["assertions"].append({"label": "0 页面错误（主流程）",
                                 "expected": "无 pageerror",
                                 "dom": check_ext[:3],
                                 "pass": not check_ext})
    report["assertions"].append({"label": "0 console 错误（主流程）",
                                 "expected": "无 console error",
                                 "dom": check_console[:3],
                                 "pass": not check_console})
    report["assertions"].append({"label": "隔离用例（故意失败）的 console 仅记录不判失败",
                                 "expected": "记录条目数 ≥0",
                                 "dom": len(report["consoleIsolated"]),
                                 "pass": True})

    browser.close()

report_path = Path(".verify/r7-4-dom-report.json")
report["badResponses"] = bad_responses
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
failed = [a for a in report.get("assertions", []) if not a["pass"]]
print(f"[r7-4-dom] assertions={len(report.get('assertions', []))} failed={len(failed)} "
      f"pageErrors={len(report['errors'])} consoleErrors={len(report['console'])} report={report_path}")
for a in report.get("assertions", []):
    print(f"  {'PASS' if a['pass'] else 'FAIL'} {a['label']}: dom={str(a['dom'])[:120]!r} expected={a['expected']!r}")
for e in report["errors"]:
    print(f"  ERROR {e.encode('unicode_escape').decode('ascii')}")
sys.exit(0 if not failed and not report["errors"] else 2)
