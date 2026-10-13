#!/usr/bin/env python3
"""Verify the real authenticated RuntimeProfile read-only browser path.

The admin password is read only from GRADUATION_TEST_ADMIN_PASSWORD in this
process. The browser session, token, screenshots, traces, and response bodies
are never persisted or printed. This script performs no profile mutations.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from urllib.parse import urlparse


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5173")
    return parser.parse_args()


def validate_base_url(value: str) -> str:
    parsed = urlparse(value)
    if (
        parsed.scheme not in {"http", "https"}
        or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("BaseUrl must be loopback HTTP(S), without credentials, query, or fragment.")
    return value.rstrip("/")


def record_read_api_response(response, api_responses: dict) -> None:
    parsed = urlparse(response.url)
    if parsed.path not in {"/api/v1/runtime-profiles", "/api/v1/sources"}:
        return
    key = parsed.path.rsplit("/", 1)[-1]
    check = {"http_ok": 200 <= response.status < 300, "envelope_ok": False, "data_is_array": False}
    try:
        body = response.json()
        check["envelope_ok"] = isinstance(body, dict) and body.get("code") == "OK"
        check["data_is_array"] = isinstance(body, dict) and isinstance(body.get("data"), list)
    except Exception:
        pass
    api_responses[key] = check


def record_login_response(response, login_response: dict) -> None:
    """Keep only bounded login diagnostics; never retain response text or tokens."""
    if urlparse(response.url).path != "/api/v1/auth/login":
        return
    login_response["http_status"] = response.status
    login_response["business_code"] = "OTHER"
    try:
        body = response.json()
        code = body.get("code") if isinstance(body, dict) else None
        if code in {"OK", "BAD_CREDENTIALS", "USER_DISABLED"}:
            login_response["business_code"] = code
    except Exception:
        pass


def read_api_contracts_ok(api_responses: dict) -> bool:
    expected = {"runtime-profiles", "sources"}
    return set(api_responses) == expected and all(
        check["http_ok"] and check["envelope_ok"] and check["data_is_array"]
        for check in api_responses.values()
    )


def main() -> int:
    args = parse_args()
    try:
        base_url = validate_base_url(args.base_url)
    except ValueError as exc:
        print(json.dumps({"result": "FAIL", "reason": str(exc)}, ensure_ascii=False))
        return 2

    password = os.environ.get("GRADUATION_TEST_ADMIN_PASSWORD")
    if not password:
        print(json.dumps({
            "result": "NOT_RUN",
            "reason": "GRADUATION_TEST_ADMIN_PASSWORD is unavailable; no browser or request was started.",
        }, ensure_ascii=False))
        return 3

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print(json.dumps({"result": "FAIL", "reason": "Playwright is not installed."}, ensure_ascii=False))
        return 4

    page_errors = 0
    api_requests = 0
    api_responses = {}
    login_response = {"http_status": None, "business_code": None}
    profile_count = 0
    active_count = 0
    login_succeeded = False
    runtime_route_opened = False
    logout_succeeded = False
    failure = None
    browser = None
    context = None

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context()
            page = context.new_page()

            def count_api_request(request) -> None:
                nonlocal api_requests
                if "/api/v1/" in request.url:
                    api_requests += 1

            def count_page_error(_error) -> None:
                nonlocal page_errors
                page_errors += 1

            page.on("request", count_api_request)
            page.on("response", lambda response: record_read_api_response(response, api_responses))
            page.on("response", lambda response: record_login_response(response, login_response))
            page.on("pageerror", count_page_error)
            page.goto(f"{base_url}/login", wait_until="domcontentloaded", timeout=15000)
            page.locator("#username").fill("admin")
            page.locator("#password").fill(password)
            page.locator("button.login-btn").click()
            try:
                page.wait_for_url("**/overview", timeout=15000)
            except Exception as exc:  # Keep login response/body out of output.
                raise RuntimeError("Admin login did not reach the authenticated overview.") from exc
            login_succeeded = True

            page.goto(f"{base_url}/runtime-profiles", wait_until="domcontentloaded", timeout=15000)
            page.get_by_role("heading", name="运行环境").wait_for(timeout=15000)
            page.locator(".runtime-profiles-page .card-title").wait_for(timeout=15000)
            page.wait_for_function(
                "() => !document.querySelector('.runtime-profiles-page .card-head .muted')?.textContent?.includes('正在读取')",
                timeout=15000,
            )
            # Vue renders after Axios resolves; wait for both expected read responses
            # without exposing or persisting their bodies.
            if set(api_responses) != {"runtime-profiles", "sources"}:
                raise RuntimeError("RuntimeProfile and source list API responses were not both observed.")
            if any(not check["http_ok"] or not check["envelope_ok"] or not check["data_is_array"]
                   for check in api_responses.values()):
                raise RuntimeError("A RuntimeProfile or source list API response violated the success contract.")
            if page.locator(".runtime-profiles-page [role='alert']").count():
                raise RuntimeError("RuntimeProfile page displayed an API or permission error.")
            profile_count = page.locator(".runtime-profiles-page table.data-table tbody tr").count()
            active_count = page.locator(".runtime-profiles-page table.data-table tbody tr.active-row").count()
            if profile_count == 0 and page.locator(".runtime-profiles-page .empty-state").count() == 0:
                raise RuntimeError("RuntimeProfile page showed neither rows nor an explicit empty state.")
            runtime_route_opened = True

            page.get_by_role("button", name="退出登录").click()
            page.wait_for_url("**/login", timeout=10000)
            token_cleared = page.evaluate("() => localStorage.getItem('analytics_token') === null")
            if not token_cleared:
                raise RuntimeError("Logout did not clear the platform token from browser storage.")
            logout_succeeded = True
    except Exception as exc:
        # Do not print exception strings: browser/network exceptions can contain URLs or response data.
        failure = type(exc).__name__
    finally:
        password = None
        if context is not None:
            try:
                context.close()
            except Exception:
                pass
        if browser is not None:
            try:
                browser.close()
            except Exception:
                pass

    read_apis_valid = read_api_contracts_ok(api_responses)
    result = "PASS" if failure is None and login_succeeded and runtime_route_opened and logout_succeeded and page_errors == 0 and read_apis_valid else "FAIL"
    print(json.dumps({
        "result": result,
        "login_succeeded": login_succeeded,
        "runtime_profile_route_opened": runtime_route_opened,
        "profile_rows": profile_count,
        "active_rows": active_count,
        "api_request_count": api_requests,
        "login_http_status": login_response["http_status"],
        "login_business_code": login_response["business_code"],
        "validated_read_endpoints": sorted(api_responses),
        "read_api_contracts_ok": read_apis_valid,
        "logout_succeeded": logout_succeeded,
        "page_error_count": page_errors,
        "failure_type": failure,
        "writes_performed": False,
        "secret_output": False,
        "evidence_scope": "real loopback admin browser session and read-only RuntimeProfile/source API; no DB direct access, connectivity test, profile mutation, or profile switch",
    }, ensure_ascii=False))
    return 0 if result == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
