"""Current-jar employee/admin route and usability smoke for verification batch N31-03.

The platform must already be running against an isolated database. Credentials and the
unique evidence directory are supplied through process environment variables; this
script never prints credentials and performs no mutating business actions.
"""

import json
import os
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright


BASE_URL = os.getenv("N31_PLATFORM_URL", "http://127.0.0.1:8091").rstrip("/")
OUT_DIR = Path(os.environ["N31_EVIDENCE_DIR"])
ANALYST_USER = os.getenv("N31_ANALYST_USERNAME", "analyst")
ADMIN_USER = os.getenv("N31_ADMIN_USERNAME", "admin")
ANALYST_PASSWORD = os.environ["N31_ANALYST_PASSWORD"]
ADMIN_PASSWORD = os.environ["N31_ADMIN_PASSWORD"]

EMPLOYEE_PAGES = [
    ("销售分析", "/sales", "sales"),
    ("用户行为分析", "/behavior", "behavior"),
    ("商品分析", "/products", "products"),
    ("用户分层", "/rfm", "rfm"),
    ("智能分析助手", "/ai", "ai"),
    ("决策中心", "/decisions", "decisions"),
]
ADMIN_ROUTES = ["/pipeline", "/ops", "/sources/wizard"]
SNAPSHOT_RE = re.compile(r"S\d{8}_\d+")


def login(page, username: str, password: str, expected_role: str) -> dict:
    page.goto(f"{BASE_URL}/login", wait_until="networkidle")
    page.get_by_label("用户名").fill(username)
    page.get_by_label("密码").fill(password)
    with page.expect_response(lambda r: r.url.endswith("/api/v1/auth/login") and r.status == 200):
        page.get_by_label("密码").press("Enter")
    page.wait_for_url(re.compile(r".*/overview$"), timeout=15000)
    page.wait_for_load_state("networkidle")
    user = page.evaluate("JSON.parse(localStorage.getItem('analytics_user') || 'null')")
    if not user or user.get("role") != expected_role:
        raise AssertionError(f"登录角色不匹配：期望 {expected_role}，实际 {user}")
    return {"username": username, "role": user.get("role"), "displayName": user.get("realName")}


def page_summary(page, route: str, api_events: list) -> dict:
    text = page.locator("body").inner_text()
    heading = page.locator(".page-title, .page-head h1, h1").first
    return {
        "route": route,
        "heading": heading.inner_text() if heading.count() else None,
        "snapshotIds": sorted(set(SNAPSHOT_RE.findall(text))),
        "honestEmptyMarkers": [
            marker for marker in ("暂无", "没有可用", "未发布", "UNKNOWN_DIMENSION_TABLE", "不编造")
            if marker in text
        ],
        "api": list(api_events),
    }


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    result = {
        "scenario": "N31-03 current packaged platform; employee and admin UI route checks",
        "baseUrl": BASE_URL,
        "businessWrites": False,
        "employeePages": [],
        "admin": {},
        "runtime": {"pageErrors": [], "consoleErrors": [], "requestFailures": []},
    }
    exit_code = 0

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        try:
            analyst_context = browser.new_context(viewport={"width": 1440, "height": 1000})
            analyst = analyst_context.new_page()
            api_events = []
            analyst.on("pageerror", lambda exc: result["runtime"]["pageErrors"].append(str(exc)))
            analyst.on(
                "console",
                lambda msg: result["runtime"]["consoleErrors"].append(msg.text) if msg.type == "error" else None,
            )
            analyst.on(
                "requestfailed",
                lambda req: result["runtime"]["requestFailures"].append(
                    {"url": req.url, "failure": req.failure}
                ),
            )

            def record_analyst_response(response):
                if "/api/v1/" not in response.url:
                    return
                event = {"path": re.sub(r"\?.*$", "", response.url),
                         "method": response.request.method, "status": response.status}
                try:
                    payload = response.json()
                    if isinstance(payload, dict):
                        event["code"] = payload.get("code")
                        data = payload.get("data")
                        if isinstance(data, dict):
                            event["snapshotId"] = data.get("snapshotId")
                except Exception:
                    pass
                api_events.append(event)

            analyst.on("response", record_analyst_response)
            result["analyst"] = login(analyst, ANALYST_USER, ANALYST_PASSWORD, "analyst")
            result["overview"] = page_summary(analyst, "/overview", api_events)
            overview_text = analyst.locator("main").inner_text()
            result["overviewControls"] = {
                "dateInputs": analyst.locator("main input[type=date]").count(),
                "selectControls": analyst.locator("main select").count(),
                "sourceSelectionTextPresent": "数据源" in overview_text or "来源" in overview_text,
            }
            dates = analyst.locator("main input[type=date]")
            if dates.count() >= 2:
                dates.nth(0).fill("2026-09-18")
                dates.nth(1).fill("2026-09-18")
                with analyst.expect_response(
                    lambda r: "/api/v1/dashboards/overview" in r.url and r.status == 200
                ) as date_response:
                    analyst.get_by_role("button", name="加载", exact=True).click(timeout=10000)
                analyst.wait_for_load_state("networkidle")
                result["overviewDateFilter"] = {
                    "from": dates.nth(0).input_value(),
                    "to": dates.nth(1).input_value(),
                    "apiStatus": date_response.value.status,
                }
            else:
                raise AssertionError("运营大盘日期范围控件不完整")
            analyst.screenshot(path=str(OUT_DIR / "analyst-overview.png"))

            for title, route, name in EMPLOYEE_PAGES:
                start = len(api_events)
                link = analyst.get_by_role("link", name=title, exact=True)
                if not link.is_visible():
                    raise AssertionError(f"普通分析员看不到业务导航：{title}")
                with analyst.expect_response(
                    lambda r: "/api/v1/" in r.url and r.request.method == "GET"
                ) as page_response:
                    link.click(timeout=10000)
                analyst.wait_for_url(re.compile(r".*/" + re.escape(route.lstrip("/")) + r"$"), timeout=10000)
                analyst.wait_for_load_state("networkidle")
                page_result = page_summary(analyst, route, api_events[start:])
                page_result["navigation"] = "accessible link click"
                page_result["observedApiResponse"] = {
                    "path": re.sub(r"\?.*$", "", page_response.value.url),
                    "status": page_response.value.status,
                }
                if page_response.value.status != 200:
                    raise AssertionError(f"{route} 首个数据 API 返回 {page_response.value.status}")
                result["employeePages"].append(page_result)
                analyst.screenshot(path=str(OUT_DIR / f"analyst-{name}.png"))

            # Hard reload of a real user route validates both SPA fallback and session restoration.
            analyst.reload(wait_until="networkidle")
            if not analyst.url.endswith("/decisions") or analyst.get_by_role("button", name="退出登录").count() != 1:
                raise AssertionError("分析员刷新业务深链后未留在应用页面")
            result["employeeReload"] = {"route": "/decisions", "result": "PASS"}

            token = analyst.evaluate("localStorage.getItem('analytics_token')")
            headers = {"Authorization": f"Bearer {token}"} if token else {}
            runtime_response = analyst.request.get(f"{BASE_URL}/api/v1/runtime-profiles", headers=headers)
            result["analystRuntimeProfilesStatus"] = runtime_response.status
            if runtime_response.status != 403:
                raise AssertionError(f"分析员读取运行配置应为 403，实为 {runtime_response.status}")

            denied_routes = []
            for route in ADMIN_ROUTES:
                analyst.goto(BASE_URL + route, wait_until="networkidle")
                analyst.wait_for_url(re.compile(r".*/overview$"), timeout=10000)
                if analyst.get_by_role("link", name="接入向导", exact=True).count() != 0:
                    raise AssertionError(f"分析员访问 {route} 后仍显示管理员入口")
                denied_routes.append({"requested": route, "redirectedTo": "/overview", "result": "PASS"})
            result["analystAdminRoutes"] = denied_routes
            analyst.screenshot(path=str(OUT_DIR / "analyst-admin-route-denied.png"))

            analyst.get_by_role("button", name="退出登录", exact=True).click(timeout=10000)
            analyst.wait_for_url(re.compile(r".*/login$"), timeout=10000)
            if analyst.evaluate("localStorage.getItem('analytics_token')") is not None:
                raise AssertionError("登出后仍残留登录令牌")
            result["analystLogout"] = "PASS"
            analyst_context.close()

            admin_context = browser.new_context(viewport={"width": 1440, "height": 1000})
            admin = admin_context.new_page()
            admin.on("pageerror", lambda exc: result["runtime"]["pageErrors"].append(str(exc)))
            admin.on(
                "console",
                lambda msg: result["runtime"]["consoleErrors"].append(msg.text) if msg.type == "error" else None,
            )
            admin_api_events = []

            def record_admin_response(response):
                if "/api/v1/" not in response.url:
                    return
                admin_api_events.append({"path": re.sub(r"\?.*$", "", response.url),
                                         "method": response.request.method, "status": response.status})

            admin.on("response", record_admin_response)
            result["admin"]["identity"] = login(admin, ADMIN_USER, ADMIN_PASSWORD, "admin")
            wizard_link = admin.get_by_role("link", name="接入向导", exact=True)
            if not wizard_link.is_visible():
                raise AssertionError("管理员看不到接入向导入口")
            with admin.expect_response(lambda r: "/api/v1/sources" in r.url) as sources_response:
                wizard_link.click(timeout=10000)
            admin.wait_for_url(re.compile(r".*/sources/wizard$"), timeout=10000)
            admin.wait_for_load_state("networkidle")
            wizard_text = admin.locator("main").inner_text()
            if "第 1 步" not in wizard_text or "确认激活" not in wizard_text:
                raise AssertionError("管理员接入向导未呈现选源和激活步骤")
            if sources_response.value.status != 200:
                raise AssertionError(f"管理员读取来源列表失败：HTTP {sources_response.value.status}")
            admin.screenshot(path=str(OUT_DIR / "admin-source-wizard.png"))

            # The nested route must survive a hard reload, not merely a Vue-side click.
            with admin.expect_response(lambda r: "/api/v1/sources" in r.url) as reload_sources_response:
                admin.reload(wait_until="networkidle")
            if not admin.url.endswith("/sources/wizard"):
                raise AssertionError("管理员刷新接入向导后 URL 未保留")
            if "第 1 步" not in admin.locator("main").inner_text():
                raise AssertionError("管理员刷新接入向导后未重新渲染页面")
            if reload_sources_response.value.status != 200:
                raise AssertionError(f"管理员刷新后读取来源列表失败：HTTP {reload_sources_response.value.status}")
            result["admin"]["sourceWizardReload"] = "PASS"
            result["admin"]["api"] = admin_api_events
            admin.screenshot(path=str(OUT_DIR / "admin-source-wizard-reload.png"))

            profile_response = admin.request.get(
                f"{BASE_URL}/api/v1/runtime-profiles",
                headers={"Authorization": f"Bearer {admin.evaluate('localStorage.getItem(\"analytics_token\")')}"},
            )
            result["admin"]["runtimeProfilesStatus"] = profile_response.status
            if profile_response.status != 200:
                raise AssertionError(f"管理员运行配置读取应为 200，实为 {profile_response.status}")
            admin.get_by_role("button", name="退出登录", exact=True).click(timeout=10000)
            admin.wait_for_url(re.compile(r".*/login$"), timeout=10000)
            result["admin"]["logout"] = "PASS"
            admin_context.close()

            if result["runtime"]["pageErrors"]:
                raise AssertionError("浏览器出现未处理页面异常")
            result["outcome"] = "PASS"
        except Exception as exc:
            result["outcome"] = "FAIL"
            result["failure"] = f"{type(exc).__name__}: {exc}"
            exit_code = 1
        finally:
            result["runtime"]["apiEventsAnalyst"] = api_events if "api_events" in locals() else []
            OUT_DIR.mkdir(parents=True, exist_ok=True)
            (OUT_DIR / "n3103-employee-browser-result.json").write_text(
                json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            browser.close()

    print(json.dumps(result, ensure_ascii=True, indent=2))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
