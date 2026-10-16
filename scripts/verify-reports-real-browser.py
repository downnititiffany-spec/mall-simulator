#!/usr/bin/env python3
"""Verify the real authenticated Reports page against a loopback platform."""

from __future__ import annotations

import json
import os
import sys

from playwright.sync_api import sync_playwright


BASE_URL = os.environ.get("GRADUATION_REPORTS_WEB_URL", "http://127.0.0.1:5173")
EXPECTED_SNAPSHOT_ID = os.environ.get("GRADUATION_REPORT_SNAPSHOT_ID", "S20261010_1")
EXPECTED_REPORTS = {}
for item in os.environ.get("GRADUATION_REPORTS_EXPECTED", "1:OPS_DAILY").split(","):
    report_id, template = item.split(":", 1)
    EXPECTED_REPORTS[report_id.strip()] = template.strip()


def main() -> int:
    password = os.environ.get("GRADUATION_TEST_ADMIN_PASSWORD")
    if not password:
        print(json.dumps({"result": "FAIL", "failure_type": "MissingCredentialEnvironment"}))
        return 2

    page_errors: list[str] = []
    api_responses: list[tuple[str, int]] = []
    checks: dict[str, bool] = {}
    stage = "launch"

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page()
        page.on("pageerror", lambda error: page_errors.append(type(error).__name__))
        page.on(
            "response",
            lambda response: api_responses.append((response.url, response.status))
            if "/api/v1/" in response.url
            else None,
        )
        try:
            stage = "login_page"
            page.goto(f"{BASE_URL}/login", wait_until="domcontentloaded", timeout=15000)
            page.locator("#username").fill("admin")
            page.locator("#password").fill(password)
            page.get_by_role("button", name="登 录").click()
            page.wait_for_function("localStorage.getItem('analytics_token') !== null", timeout=15000)
            stage = "reports_page"
            page.goto(f"{BASE_URL}/reports", wait_until="domcontentloaded", timeout=15000)
            page.wait_for_load_state("networkidle", timeout=20000)
            page.locator(".reports-page .page-title").wait_for(timeout=10000)

            rows = page.locator(".reports-page tbody tr")
            rows.first.wait_for(timeout=10000)
            visible_rows = {
                row.locator("td").nth(0).inner_text().strip(): row for row in rows.all()
            }
            for expected_id, expected_template in EXPECTED_REPORTS.items():
                checks[f"report_{expected_id}_visible"] = expected_id in visible_rows
                target_row = visible_rows.get(expected_id)
                if target_row is None:
                    continue
                checks[f"report_{expected_id}_snapshot_visible"] = EXPECTED_SNAPSHOT_ID in target_row.inner_text()

                stage = f"open_detail_{expected_id}"
                target_row.get_by_role("button", name="查看证据").click()
                detail = page.locator('[aria-label="报告证据详情"]')
                detail.wait_for(timeout=10000)
                page.wait_for_function(
                    "id => { const title = document.querySelector('[aria-label=\"报告证据详情\"] .chart-title'); "
                    "return title && title.textContent.includes(`报告 #${id}`); }",
                    arg=expected_id,
                    timeout=10000,
                )
                detail_text = detail.inner_text()
                checks[f"report_{expected_id}_detail_title_visible"] = f"报告 #{expected_id}" in detail_text
                checks[f"report_{expected_id}_detail_snapshot_visible"] = EXPECTED_SNAPSHOT_ID in detail_text

                stage = f"read_artifact_{expected_id}"
                detail.get_by_role("button", name="读取完整制品").click()
                raw = detail.locator("details.report-raw pre.report-artifact")
                detail.locator("details.report-raw summary").click()
                raw.wait_for(timeout=10000)
                artifact = json.loads(raw.inner_text())
                evidence = artifact.get("evidence", {})
                checks[f"report_{expected_id}_artifact_valid"] = (
                    artifact.get("templateCode") == expected_template
                    and evidence.get("snapshotId") == EXPECTED_SNAPSHOT_ID
                )
            checks["api_requests_successful"] = bool(api_responses) and all(
                status < 400 for _, status in api_responses
            )

            stage = "logout"
            page.get_by_role("button", name="退出登录").click()
            page.wait_for_url("**/login", timeout=10000)
            checks["logout_cleared_token"] = page.evaluate(
                "localStorage.getItem('analytics_token') === null"
            )
            checks["no_page_errors"] = not page_errors
            failed = [name for name, passed in checks.items() if not passed]
            print(
                json.dumps(
                    {
                        "result": "PASS" if not failed else "FAIL",
                        "report_ids": list(EXPECTED_REPORTS),
                        "snapshot_id": EXPECTED_SNAPSHOT_ID,
                        "checks_passed": len(checks) - len(failed),
                        "checks_total": len(checks),
                        "failed_checks": failed,
                        "api_request_count": len(api_responses),
                        "page_error_count": len(page_errors),
                        "writes_performed": False,
                        "evidence_scope": "real loopback admin browser; report page, owner list, detail, full artifact, logout",
                    },
                    ensure_ascii=False,
                )
            )
            return 0 if not failed else 1
        except Exception as error:  # do not print exception text or request payloads
            print(
                json.dumps(
                    {
                        "result": "FAIL",
                        "failure_type": type(error).__name__,
                        "stage": stage,
                        "api_request_count": len(api_responses),
                        "page_error_count": len(page_errors),
                        "writes_performed": False,
                    },
                    ensure_ascii=False,
                )
            )
            return 1
        finally:
            browser.close()


if __name__ == "__main__":
    sys.exit(main())
