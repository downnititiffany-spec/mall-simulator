"""Local UI checks only: synthetic identity/API; no backend authentication."""
import json
import subprocess
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[2]
evidence = Path(__file__).resolve().parent
base = "http://127.0.0.1:5187"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    try:
        context = browser.new_context()
        page = context.new_page()
        errors = []
        api_calls = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.route("**/api/v1/**", lambda route: (
            api_calls.append(route.request.url), route.abort()))
        routes = ["/overview", "/sales", "/behavior", "/products", "/rfm", "/ai",
                  "/decisions", "/reports", "/pipeline", "/ops", "/sources/wizard",
                  "/runtime-profiles"]
        for route in routes:
            page.goto(base + route, wait_until="networkidle")
            assert page.url == base + "/login", route
            assert page.locator('input[type="password"]').is_visible()
        assert not api_calls, "anonymous guards should redirect before platform API calls"
        assert not errors
        page.screenshot(path=str(evidence / "anonymous-login.png"), full_page=True)
        print(json.dumps({"result": "PASS", "anonymous_route_count": len(routes),
                          "api_request_count": len(api_calls), "page_error_count": len(errors),
                          "scope": "client-only anonymous guards; no platform auth/DB"}))
    finally:
        browser.close()

source = (root / "scripts/verify-reports-browser.py").read_text(encoding="utf-8")
source = source.replace("            print(json.dumps({", "            page.screenshot(path="
                        + repr(str(evidence / "reports-mock-ui.png"))
                        + ", full_page=True)\n            print(json.dumps({", 1)
capture = evidence / "verify-reports-capture.py"
capture.write_text(source, encoding="utf-8")
for script in [capture, root / "scripts/verify-runtime-profile-browser.py"]:
    subprocess.run([sys.executable, str(script), "--base-url", base], check=True)
