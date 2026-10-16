#!/usr/bin/env python3
"""Exercise analysis page error/empty states with a fully intercepted loopback API."""

import argparse
import ipaddress
import json
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright


def require_loopback_url(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("Base URL must be a loopback HTTP(S) URL")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Base URL cannot include credentials, query, or fragment")
    try:
        loopback = ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        loopback = parsed.hostname.lower() == "localhost"
    if not loopback:
        raise ValueError("Only loopback URLs are allowed; all API requests are mocked")
    return value.rstrip("/")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5189")
    args = parser.parse_args()
    base_url = require_loopback_url(args.base_url)
    requests = []
    unexpected_requests = []
    page_errors = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        try:
            for mode in ("malformed", "valid-empty"):
                context = browser.new_context()
                context.add_init_script(
                    "localStorage.setItem('analytics_token','mock-only-token');"
                    "localStorage.setItem('analytics_user',"
                    "JSON.stringify({username:'mock-analyst',role:'analyst'}));"
                )
                page = context.new_page()
                page.on("pageerror", lambda error: page_errors.append(str(error)))

                def mock_api(route):
                    request = route.request
                    parsed = urlsplit(request.url)
                    path = parsed.path.split("/api/v1", 1)[-1]
                    requests.append({"mode": mode, "method": request.method, "path": path})
                    if request.method != "GET":
                        unexpected_requests.append({"mode": mode, "method": request.method, "path": path})
                        route.fulfill(status=404, content_type="application/json",
                                      body=json.dumps({"code": "MOCK_UNEXPECTED", "message": "Unexpected mock route"}))
                        return
                    if path == "/metrics/snapshots":
                        data = [{
                            "snapshotId": "mock-snapshot",
                            "sourceId": 1,
                            "businessTime": "2026-10-10T00:00:00",
                            "status": "ACTIVE",
                            "definitionVersion": "mock-v1",
                        }]
                    elif path == "/analytics/sources":
                        data = []
                    elif path == "/analysis/sales" and mode == "malformed":
                        payload = {
                            "snapshotId": "mock-snapshot",
                            "warnings": [],
                            "data": {"trend": "not-an-array", "byCategory": [], "byRegion": [], "quality": {}},
                        }
                        data = payload
                    elif path == "/analysis/sales":
                        payload = {
                            "snapshotId": "mock-snapshot",
                            "warnings": [],
                            "data": {
                                "trend": [], "byCategory": [], "byRegion": [], "quality": {},
                                "gmv": None, "netSale": None, "refundRate": None, "fullRefundRate": None,
                            },
                        }
                        data = payload
                    else:
                        unexpected_requests.append({"mode": mode, "method": request.method, "path": path})
                        route.fulfill(status=404, content_type="application/json",
                                      body=json.dumps({"code": "MOCK_UNEXPECTED", "message": "Unexpected mock route"}))
                        return
                    route.fulfill(status=200, content_type="application/json",
                                  body=json.dumps({"code": "OK", "data": data, "traceId": "mock-trace"}))

                page.route("**/api/v1/**", mock_api)
                page.goto(f"{base_url}/sales", wait_until="networkidle", timeout=30000)
                if mode == "malformed":
                    error_states = page.locator(".state-error").filter(has_text="销售分析数据结构无效")
                    error_states.first.wait_for(timeout=10000)
                    assert error_states.count() == 3
                    assert page.locator(".state-empty").count() == 0
                else:
                    empty_state = page.locator(".state-empty")
                    empty_state.first.wait_for(timeout=10000)
                    assert page.locator(".state-error").count() == 0
                context.close()

            assert len(requests) == 6, f"Expected snapshot/options/sales requests for both modes, got {len(requests)}"
            assert not unexpected_requests, f"Unexpected requests: {unexpected_requests}"
            assert not page_errors, f"Browser page errors: {page_errors}"
            print(json.dumps({
                "result": "PASS",
                "malformed_success_response": "shown as error, not empty state",
                "valid_empty_active_snapshot": "shown as empty state without error",
                "mocked_api_request_count": len(requests),
                "unexpected_api_requests": unexpected_requests,
                "page_error_count": len(page_errors),
                "evidence_scope": "headless Chromium with synthetic client identity and fully intercepted loopback API; no platform authentication or database",
            }, ensure_ascii=False))
        finally:
            browser.close()


if __name__ == "__main__":
    main()
