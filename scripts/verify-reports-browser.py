#!/usr/bin/env python3
"""Exercise the reports page with loopback-only intercepted synthetic APIs.

This verifies client workflow and presentation only. It does not authenticate
against the platform or provide HTTP, database, migration, or owner-isolation
evidence.
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


def envelope(data):
    return {"code": "OK", "data": data, "traceId": "mock-report-trace"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5173")
    args = parser.parse_args()
    base_url = require_loopback_url(args.base_url)

    snapshot = {
        "snapshotId": "S-MOCK-20261010",
        "sourceId": 1,
        "businessTime": "2026-10-10T23:59:59",
        "definitionVersion": "d3",
        "status": "ACTIVE",
    }
    source = {"sourceId": 1, "sourceCode": "mock-source", "displayName": "模拟来源"}
    reports = {}
    create_payloads = []
    requests = []
    unexpected_requests = []
    page_errors = []
    state = {"decision_calls": 0}

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context()
        # Client-only identity. Every API request is intercepted below.
        context.add_init_script(
            "localStorage.setItem('analytics_token','mock-report-token');"
            "localStorage.setItem('analytics_user',"
            "JSON.stringify({id:88,username:'mock-analyst',role:'analyst'}));"
        )
        page = context.new_page()
        page.set_default_timeout(8000)
        page.on("pageerror", lambda error: page_errors.append(str(error)))

        def mock_api(route):
            request = route.request
            parsed = urlsplit(request.url)
            path = parsed.path.split("/api/v1", 1)[-1]
            requests.append({"method": request.method, "path": path})
            data = None

            if path == "/metrics/snapshots" and request.method == "GET":
                data = [snapshot]
            elif path == "/analytics/sources" and request.method == "GET":
                data = [source]
            elif path == "/reports/page" and request.method == "GET":
                items = sorted(reports.values(), key=lambda report: report["id"], reverse=True)
                data = {
                    "items": items,
                    "page": 1,
                    "size": 10,
                    "total": len(items),
                    "totalPages": 1 if items else 0,
                    "sort": "createdAt,desc",
                }
            elif path == "/reports" and request.method == "POST":
                payload = request.post_data_json
                create_payloads.append(payload)
                report_id = 80 + len(create_payloads)
                report = {
                    "id": report_id,
                    "templateCode": payload["templateCode"],
                    "templateVersion": "1",
                    "sourceId": 1,
                    "snapshotId": None if payload["templateCode"] == "DECISION_EFFECT" else snapshot["snapshotId"],
                    "definitionVersion": "d3",
                    "fromDate": payload.get("fromDate", payload.get("businessDate", "2026-10-10")),
                    "toDate": payload.get("toDate", payload.get("businessDate", "2026-10-10")),
                    "creatorId": "mock-analyst",
                    "status": "READY",
                    "artifactChecksum": "a" * 64,
                    "createdAt": "2026-10-10T12:00:00",
                }
                reports[report_id] = report
                data = report
            elif path.startswith("/reports/") and path.endswith("/artifact") and request.method == "GET":
                report_id = int(path.split("/")[2])
                if report_id not in reports:
                    unexpected_requests.append({"method": request.method, "path": path})
                    route.fulfill(status=404, content_type="application/json", body=json.dumps(envelope(None)))
                    return
                data = {
                    "templateCode": reports[report_id]["templateCode"],
                    "templateVersion": "1",
                    "evidence": {
                        "snapshotId": snapshot["snapshotId"],
                        "facts": [{
                            "metricCode": "gmv",
                            "metricName": "成交额",
                            "value": "120.00",
                            "unit": "元",
                            "period": "2026-10-04..2026-10-10",
                            "evidenceRef": "metric@S-MOCK-20261010",
                        }],
                        "warnings": [],
                    },
                    "templateContent": {
                        "totalOrders": None,
                        "totalSaleAmount": None,
                        "warnings": ["WARN_WEEKLY_SALES_DAYS_MISSING"],
                        "limitations": ["合成浏览器夹具，只演示报告页面呈现。"],
                    },
                }
            elif path.startswith("/reports/") and request.method == "GET":
                report_id = int(path.split("/")[2])
                report = reports.get(report_id)
                if not report:
                    unexpected_requests.append({"method": request.method, "path": path})
                    route.fulfill(status=404, content_type="application/json", body=json.dumps(envelope(None)))
                    return
                data = {
                    "report": report,
                    "summary": {
                        "snapshotId": report["snapshotId"],
                        "factCount": 1,
                        "comparisonCount": 0,
                        "warningCount": 1,
                        "dataQuality": {"gateStatus": "PASS"},
                        "templateSummary": {"totalOrders": None, "totalSaleAmount": None},
                    },
                }
            elif path == "/decisions/page" and request.method == "GET":
                state["decision_calls"] += 1
                data = {
                    "items": [{
                        "id": 77,
                        "decisionNo": "D-MOCK-77",
                        "title": "模拟已完成决策",
                        "status": "EFFECTIVE",
                        "completedAt": "2026-10-09T12:00:00",
                    }],
                    "page": 1,
                    "size": 20,
                    "total": 1,
                    "totalPages": 1,
                    "sort": "id,desc",
                }
            elif path == "/decisions/77/evaluations" and request.method == "GET":
                data = [{"result": "EFFECTIVE"}]
            else:
                unexpected_requests.append({"method": request.method, "path": path})
                route.fulfill(
                    status=404,
                    content_type="application/json",
                    body=json.dumps({"code": "MOCK_ROUTE_UNEXPECTED", "message": "Unexpected mock route"}),
                )
                return

            route.fulfill(status=200, content_type="application/json", body=json.dumps(envelope(data)))

        page.route("**/api/v1/**", mock_api)
        try:
            page.goto(f"{base_url}/reports", wait_until="networkidle", timeout=30000)
            assert page.locator(".reports-page > .page-title").is_visible()
            assert page.get_by_label("报告模板").locator("option").count() == 6
            assert page.locator(".template-card").count() == 6
            assert page.locator(".template-card").filter(has_text="经营日报").count() == 1
            assert page.locator(".template-card").filter(has_text="经营周报").count() == 1
            assert state["decision_calls"] == 0, "Decision candidates should load only after selecting that template"

            page.get_by_label("报告模板").select_option("OPS_WEEKLY")
            assert page.get_by_label("周报起始日").input_value() == "2026-10-04"
            assert page.get_by_label("周报结束日").input_value() == "2026-10-10"
            page.get_by_role("button", name="生成经营周报").click()
            page.get_by_text("经营周报 #81 已生成并绑定快照 S-MOCK-20261010。", exact=True).wait_for()
            assert create_payloads[0]["fromDate"] == "2026-10-04"
            assert create_payloads[0]["toDate"] == "2026-10-10"
            assert create_payloads[0]["snapshotId"] == snapshot["snapshotId"]
            assert create_payloads[0]["businessDate"] == "2026-10-10"
            page.get_by_text("未提供", exact=True).first.wait_for()
            assert page.locator(".report-table-scroll").count() == 0

            page.get_by_label("报告模板").select_option("DECISION_EFFECT")
            page.get_by_label("已评价决策").select_option("77")
            assert state["decision_calls"] == 1
            assert page.get_by_label("数据快照").count() == 0
            assert page.get_by_label("业务日").count() == 0
            page.get_by_role("button", name="生成决策效果").click()
            page.get_by_text("决策效果 #82 已生成并绑定决策 77。", exact=True).wait_for()
            assert create_payloads[1]["decisionId"] == 77
            assert "snapshotId" not in create_payloads[1]
            assert "businessDate" not in create_payloads[1]
            assert not unexpected_requests
            assert not page_errors

            print(json.dumps({
                "result": "PASS",
                "workflows": ["OPS_WEEKLY snapshot/window -> create -> list -> summary detail",
                              "DECISION_EFFECT lazy candidate load -> decisionId create"],
                "template_option_count": 6,
                "mocked_api_request_count": len(requests),
                "create_payload_count": len(create_payloads),
                "unexpected_api_requests": unexpected_requests,
                "page_errors": page_errors,
                "artifact_read": "not exercised by this browser check",
                "evidence_scope": "loopback UI with intercepted synthetic APIs and client-only identity; no platform auth/HTTP/DB",
            }, ensure_ascii=True))
        finally:
            browser.close()


if __name__ == "__main__":
    main()
