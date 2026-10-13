#!/usr/bin/env python3
"""Verify the RuntimeProfile browser workflow against an intercepted mock API.

This proves only client behavior. It never sends RuntimeProfile requests to the
platform API and must not be described as authenticated service/DB evidence.
"""

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
    parser.add_argument("--base-url", default="http://127.0.0.1:5173")
    args = parser.parse_args()
    base_url = require_loopback_url(args.base_url)

    active = {
        "id": 10,
        "profileCode": "local-dev",
        "profileName": "本地默认环境",
        "type": "LOCAL",
        "sourceId": 1,
        "status": "ACTIVE",
        "version": 2,
        "updatedAt": "2026-10-10T08:00:00",
    }
    state = {"candidate": None}
    requests = []
    payloads = []
    unexpected_requests = []
    page_errors = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context()
        # This mock identity only opens the client route. Every API call is
        # intercepted below, so it cannot authenticate against the platform.
        context.add_init_script(
            "localStorage.setItem('analytics_token','mock-admin-token');"
            "localStorage.setItem('analytics_user',"
            "JSON.stringify({username:'mock-admin',role:'admin'}));"
            "window.confirm=(message)=>{window.__confirmMessage=String(message);return true;};"
        )
        page = context.new_page()
        page.on("pageerror", lambda error: page_errors.append(str(error)))

        def mock_api(route):
            request = route.request
            parsed = urlsplit(request.url)
            path = parsed.path.split("/api/v1", 1)[-1]
            requests.append({"method": request.method, "path": path})
            candidate = state["candidate"]

            if path == "/runtime-profiles" and request.method == "GET":
                data = [active] + ([candidate] if candidate else [])
            elif path == "/sources" and request.method == "GET":
                data = [{"id": 1, "sourceCode": "mock-mall", "sourceName": "模拟商城"}]
            elif path == "/runtime-profiles" and request.method == "POST":
                payload = request.post_data_json
                payloads.append(payload)
                candidate = {
                    **payload,
                    "id": 12,
                    "status": "DRAFT",
                    "version": 1,
                    "updatedAt": "2026-10-10T09:00:00",
                }
                state["candidate"] = candidate
                data = candidate
            elif path == "/runtime-profiles/12/test" and request.method == "POST" and candidate:
                candidate["status"] = "TESTING"
                data = {
                    "profileId": 12,
                    "allPassed": True,
                    "summary": "适用项全部通过（mock）",
                    "checks": [
                        {
                            "name": "HiveServer2",
                            "applicable": True,
                            "passed": True,
                            "detail": "mock check",
                        }
                    ],
                }
            elif path == "/runtime-profiles/12/activate" and request.method == "POST" and candidate:
                active["status"] = "DISABLED"
                candidate["status"] = "ACTIVE"
                candidate["version"] = 2
                data = candidate
            else:
                unexpected_requests.append({"method": request.method, "path": path})
                route.fulfill(
                    status=404,
                    content_type="application/json",
                    body=json.dumps({"code": "MOCK_ROUTE_UNEXPECTED", "message": "Unexpected mock route"}),
                )
                return

            route.fulfill(
                status=200,
                content_type="application/json",
                body=json.dumps({"code": "OK", "data": data, "traceId": "mock-trace"}),
            )

        page.route("**/api/v1/**", mock_api)
        try:
            page.goto(f"{base_url}/runtime-profiles", wait_until="networkidle", timeout=30000)
            assert page.get_by_role("heading", name="运行环境").is_visible()
            assert page.get_by_text("本地默认环境").is_visible()

            page.get_by_role("button", name="新建环境").click()
            page.get_by_label("环境编码").fill("hive-candidate")
            page.get_by_label("环境名称").fill("隔离 Hive 候选环境")
            page.get_by_label("环境类型").select_option("SINGLE_NODE")
            page.get_by_label("绑定来源").select_option("1")
            page.get_by_label("HDFS URI").fill("hdfs://127.0.0.1:19000")
            page.get_by_label("HiveServer2 JDBC URL").fill("jdbc:hive2://127.0.0.1:10015/default")
            page.get_by_label("凭据引用").fill("secure-store/hive-readonly")
            assert page.locator('input[type="password"]').count() == 0
            page.get_by_role("button", name="保存草稿配置").click()

            row = page.get_by_role("row", name="隔离 Hive 候选环境")
            row.wait_for()
            assert len(payloads) == 1
            payload = payloads[0]
            assert payload.get("credentialRef") == "secure-store/hive-readonly"
            assert not any(
                any(marker in key.lower() for marker in ("password", "token", "secret"))
                for key in payload
            )

            activate_button = row.get_by_role("button", name="激活")
            assert activate_button.is_disabled(), "A profile must not activate before a passing test"
            row.get_by_role("button", name="测试").click()
            page.get_by_text("适用项全部通过（mock）").wait_for()
            assert row.get_by_role("button", name="激活").is_enabled()
            row.get_by_role("button", name="激活").click()
            page.get_by_role("status").get_by_text("已激活").wait_for()
            assert page.get_by_role("row", name="隔离 Hive 候选环境").get_by_text("生效中").is_visible()
            confirmation = page.evaluate("window.__confirmMessage || ''")
            assert "隔离 Hive 候选环境" in confirmation and "是否继续" in confirmation
            assert not unexpected_requests
            assert not page_errors

            print(
                json.dumps(
                    {
                        "result": "PASS",
                        "workflow": "create -> test -> confirm -> activate",
                        "credential_reference_only": True,
                        "password_input_count": 0,
                        "mocked_api_request_count": len(requests),
                        "unexpected_api_requests": unexpected_requests,
                        "page_errors": page_errors,
                        "evidence_scope": "mock API and synthetic client identity; no platform authentication or DB",
                    },
                    ensure_ascii=True,
                )
            )
        finally:
            browser.close()


if __name__ == "__main__":
    main()
