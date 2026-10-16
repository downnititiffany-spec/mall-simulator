#!/usr/bin/env python3
"""Verify explicit archived snapshot selection using a fully intercepted loopback API."""

import argparse
import ipaddress
import json
from urllib.parse import parse_qs, urlsplit

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


def envelope(data):
    return {"code": "OK", "data": data, "traceId": "mock-trace"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5191")
    args = parser.parse_args()
    base_url = require_loopback_url(args.base_url)
    requests = []
    unexpected_requests = []
    page_errors = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
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
            query = parse_qs(parsed.query)
            requests.append({"method": request.method, "path": path, "query": query})
            if request.method != "GET":
                unexpected_requests.append({"method": request.method, "path": path})
                route.fulfill(status=404, content_type="application/json",
                              body=json.dumps({"code": "MOCK_UNEXPECTED", "message": "Unexpected route"}))
                return
            if path == "/metrics/snapshots":
                data = [{
                    "snapshotId": "S-CURRENT-ACTIVE", "sourceId": 7,
                    "businessTime": "2026-10-10T00:00:00", "status": "ACTIVE",
                    "definitionVersion": "mock-v1",
                }]
            elif path == "/analytics/sources":
                data = []
            elif path == "/analysis/sales":
                requested = query.get("snapshotId", [None])[0]
                if requested == "S-UNKNOWN":
                    route.fulfill(status=404, content_type="application/json", body=json.dumps({
                        "code": "SNAPSHOT_NOT_FOUND", "message": "指定快照不可用", "data": None,
                    }))
                    return
                if requested not in {"S-CURRENT-ACTIVE", "S-ARCHIVED-OTHER-PROFILE"}:
                    unexpected_requests.append({"method": request.method, "path": path, "snapshotId": requested})
                    route.fulfill(status=400, content_type="application/json",
                                  body=json.dumps({"code": "MOCK_UNEXPECTED", "message": "Unexpected snapshot ID"}))
                    return
                data = {
                    "snapshotId": requested,
                    "warnings": [],
                    "data": {
                        "trend": [{"date": "2026-10-10", "orderCount": 1, "saleAmount": 9.9}],
                        "byCategory": [], "byRegion": [], "quality": {},
                        "gmv": 9.9, "netSale": 9.9, "refundRate": 0, "fullRefundRate": 0,
                    },
                }
            else:
                unexpected_requests.append({"method": request.method, "path": path})
                route.fulfill(status=404, content_type="application/json",
                              body=json.dumps({"code": "MOCK_UNEXPECTED", "message": "Unexpected route"}))
                return
            route.fulfill(status=200, content_type="application/json", body=json.dumps(envelope(data)))

        page.route("**/api/v1/**", mock_api)
        try:
            page.goto(f"{base_url}/sales", wait_until="networkidle", timeout=30000)
            page.get_by_text("本页数据固定使用 S-CURRENT-ACTIVE", exact=False).wait_for(timeout=10000)
            active_select = page.locator("#analysis-snapshot")
            assert active_select.input_value() == "S-CURRENT-ACTIVE"

            snapshot_input = page.get_by_label("按 ID 查看归档快照")
            snapshot_input.fill("S-ARCHIVED-OTHER-PROFILE")
            with page.expect_response(lambda response: "/analysis/sales?" in response.url and
                                     "snapshotId=S-ARCHIVED-OTHER-PROFILE" in response.url):
                page.get_by_role("button", name="查看").click()
            page.get_by_text("本页正按指定 ID S-ARCHIVED-OTHER-PROFILE 读取", exact=False).wait_for()
            assert active_select.input_value() == "S-CURRENT-ACTIVE", "Historical selection must not rewrite current profile option"
            assert page.locator(".state-error").count() == 0

            snapshot_input.fill("S-UNKNOWN")
            with page.expect_response(lambda response: "/analysis/sales?" in response.url and
                                     "snapshotId=S-UNKNOWN" in response.url):
                page.get_by_role("button", name="查看").click()
            page.locator(".state-error").first.wait_for(timeout=10000)
            assert page.locator(".state-error").count() == 3
            assert not any(r["path"] == "/analysis/sales" and
                           r["query"].get("snapshotId") == ["S-CURRENT-ACTIVE"]
                           for r in requests[3:]), "Unknown ID must not fall back to current ACTIVE snapshot"

            page.get_by_role("button", name="返回当前档案快照").click()
            page.get_by_text("本页数据固定使用 S-CURRENT-ACTIVE", exact=False).wait_for(timeout=10000)
            assert active_select.input_value() == "S-CURRENT-ACTIVE"
            assert page.locator(".state-error").count() == 0

            assert not unexpected_requests, f"Unexpected API requests: {unexpected_requests}"
            assert not page_errors, f"Browser page errors: {page_errors}"
            print(json.dumps({
                "result": "PASS",
                "workflow": "active -> explicit archived cross-profile ID -> unknown ID fails closed -> return to active",
                "mocked_api_request_count": len(requests),
                "unexpected_api_requests": unexpected_requests,
                "page_error_count": len(page_errors),
                "evidence_scope": "headless Chromium with synthetic analyst identity and fully intercepted loopback API; no platform authentication, database, or real snapshot",
            }, ensure_ascii=False))
        finally:
            context.close()
            browser.close()


if __name__ == "__main__":
    main()
