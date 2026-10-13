#!/usr/bin/env python3
"""Verify the authenticated decision page renders only the isolated synthetic drafts.

The admin password is read from GRADUATION_TEST_ADMIN_PASSWORD in this process.
This verifier performs login, read-only navigation, and logout; it never clicks
decision actions or persists browser artifacts, response bodies, or credentials.
"""

from __future__ import annotations

import json
import os
import sys
from urllib.parse import urlparse


def main() -> int:
    base_url = "http://127.0.0.1:5173"
    password = os.environ.get("GRADUATION_TEST_ADMIN_PASSWORD")
    if not password:
        print(json.dumps({"result": "NOT_RUN", "reason": "admin credential unavailable; no request sent"}))
        return 3
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print(json.dumps({"result": "FAIL", "reason": "Playwright is not installed"}))
        return 4

    page_errors = 0
    api_responses: dict[str, dict] = {}
    login_succeeded = False
    decision_page_opened = False
    logout_succeeded = False
    synthetic_rows = 0
    draft_rows = 0
    page_summary_visible = False
    failure = None
    browser = None
    context = None

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context()
            page = context.new_page()

            def on_response(response) -> None:
                nonlocal api_responses
                path = urlparse(response.url).path
                if path not in {"/api/v1/auth/login", "/api/v1/decisions/page", "/api/v1/auth/logout"}:
                    return
                info = {"http_ok": 200 <= response.status < 300, "code_ok": False}
                try:
                    body = response.json()
                    info["code_ok"] = isinstance(body, dict) and body.get("code") == "OK"
                    if path == "/api/v1/decisions/page" and info["code_ok"]:
                        data = body.get("data")
                        items = data.get("items") if isinstance(data, dict) else None
                        info["total"] = data.get("total") if isinstance(data, dict) else None
                        info["items_count"] = len(items) if isinstance(items, list) else None
                        info["synthetic_count"] = sum(
                            1 for item in items
                            if isinstance(item, dict) and "PAGINATION-SYNTH" in str(item.get("title", ""))
                        ) if isinstance(items, list) else 0
                        info["draft_count"] = sum(
                            1 for item in items
                            if isinstance(item, dict) and item.get("status") == "DRAFT"
                        ) if isinstance(items, list) else 0
                except Exception:
                    pass
                api_responses[path] = info

            def on_page_error(_error) -> None:
                nonlocal page_errors
                page_errors += 1

            page.on("response", on_response)
            page.on("pageerror", on_page_error)
            page.goto(f"{base_url}/login", wait_until="domcontentloaded", timeout=15000)
            page.locator("#username").fill("admin")
            page.locator("#password").fill(password)
            page.locator("button.login-btn").click()
            page.wait_for_url("**/overview", timeout=15000)
            login_succeeded = True

            page.get_by_role("link", name="决策中心", exact=True).click()
            page.wait_for_url("**/decisions", timeout=15000)
            page.locator(".page-title").filter(has_text="决策中心").wait_for(timeout=15000)
            page.locator(".data-table tbody tr").first.wait_for(timeout=15000)
            row_texts = page.locator(".data-table tbody tr").all_text_contents()
            synthetic_rows = sum("PAGINATION-SYNTH" in row for row in row_texts)
            draft_rows = sum("DRAFT" in row for row in row_texts)
            page_summary_visible = page.get_by_text("共 2 条 · 第 1 / 1 页", exact=True).count() == 1
            if synthetic_rows != 2 or draft_rows != 2 or not page_summary_visible:
                raise RuntimeError("The decision page did not render the expected synthetic DRAFT page.")
            decision_page_opened = True

            page.get_by_role("button", name="退出登录").click()
            page.wait_for_url("**/login", timeout=10000)
            if page.evaluate("() => localStorage.getItem('analytics_token') !== null"):
                raise RuntimeError("Logout left a platform token in browser storage.")
            logout_succeeded = True
    except Exception as exc:
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

    decision_response = api_responses.get("/api/v1/decisions/page", {})
    result = (
        "PASS" if failure is None and login_succeeded and decision_page_opened and logout_succeeded
        and page_errors == 0 and decision_response.get("http_ok") and decision_response.get("code_ok")
        and decision_response.get("total") == 2 and decision_response.get("items_count") == 2
        and decision_response.get("synthetic_count") == 2 and decision_response.get("draft_count") == 2
        else "FAIL"
    )
    print(json.dumps({
        "result": result,
        "login_succeeded": login_succeeded,
        "decision_page_opened": decision_page_opened,
        "page_summary_visible": page_summary_visible,
        "synthetic_rows": synthetic_rows,
        "draft_rows": draft_rows,
        "decision_api_total": decision_response.get("total"),
        "decision_api_items": decision_response.get("items_count"),
        "logout_succeeded": logout_succeeded,
        "page_error_count": page_errors,
        "failure_type": failure,
        "writes_performed": False,
        "secret_output": False,
        "evidence_scope": "real loopback admin browser and read-only decision list; no decision actions or DB direct access",
    }, ensure_ascii=False))
    return 0 if result == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
