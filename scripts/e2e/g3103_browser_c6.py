"""Real-browser checks for Stage-7 login/AI submit semantics and analyst RBAC.

Requires the platform and Vite dev server to already be running. Results are
written only to the caller-provided ignored attempt directory.
"""

import argparse
import json
import os
import re
from pathlib import Path

from playwright.sync_api import sync_playwright


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument("--web-url", default=os.getenv("GRADUATION_E2E_WEB_URL", "http://127.0.0.1:5176"))
    parser.add_argument("--api-url", default=os.getenv("GRADUATION_E2E_API_URL", "http://127.0.0.1:8091"))
    parser.add_argument("--username", default=os.getenv("GRADUATION_E2E_USERNAME", "analyst"))
    parser.add_argument("--password", default=os.getenv("GRADUATION_E2E_PASSWORD", "analyst123"))
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    responses = []
    requests = []
    page_errors = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on("pageerror", lambda exc: page_errors.append(str(exc)))
        page.on("request", lambda req: requests.append({"url": req.url, "method": req.method})
                 if "/api/v1/" in req.url else None)
        page.on("response", lambda res: responses.append({
            "url": re.sub(r"\?.*$", "", res.url),
            "status": res.status,
            "method": res.request.method,
        }) if "/api/v1/" in res.url else None)

        page.goto(args.web_url.rstrip("/") + "/login", wait_until="networkidle")
        page.get_by_label("用户名").fill(args.username)
        page.get_by_label("密码").fill(args.password)
        with page.expect_response(lambda res: res.url.endswith("/api/v1/auth/login") and res.status == 200):
            page.get_by_label("密码").press("Enter")
        page.wait_for_url(re.compile(r".*/overview$"), timeout=15000)

        page.goto(args.web_url.rstrip("/") + "/ai", wait_until="networkidle")
        question = page.locator(".chat-input-bar input")
        query_path = "/api/v1/ai/queries"

        def count_ai_posts() -> int:
            return sum(item["method"] == "POST" and query_path in item["url"] for item in requests)

        question.fill("最新一期的 GMV 和退款率是多少？")
        before = count_ai_posts()
        with page.expect_response(lambda res: res.url.endswith(query_path) and res.status == 200, timeout=30000) as enter_response:
            question.press("Enter")
        enter_payload = enter_response.value.json()
        page.get_by_text("数据依据（真实查询结果，未做前端重算）").wait_for(timeout=15000)
        enter_posts = count_ai_posts() - before

        question.fill("")
        before = count_ai_posts()
        chip = page.get_by_role("button", name="最近 7 天销售额变化趋势如何？", exact=True)
        chip.wait_for(timeout=10000)
        with page.expect_response(lambda res: res.url.endswith(query_path) and res.status == 200, timeout=30000) as chip_response:
            chip.click()
        chip_payload = chip_response.value.json()
        chip_posts = count_ai_posts() - before
        page.screenshot(path=str(args.out_dir / "browser-c6-ai-chip.png"))

        token = page.evaluate("localStorage.getItem('analytics_token')")
        rbac = page.request.get(
            args.api_url.rstrip("/") + "/api/v1/runtime-profiles",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert enter_payload.get("code") == "OK", "AI Enter response was not successful"
        assert chip_payload.get("code") == "OK", "AI recommendation response was not successful"
        assert enter_posts == 1, f"Enter triggered {enter_posts} AI POST requests"
        assert chip_posts == 1, f"recommendation chip triggered {chip_posts} AI POST requests"
        assert rbac.status == 403, f"analyst runtime-profile API returned {rbac.status}, expected 403"
        assert not page_errors, page_errors

        result = {
            "scenario": "real Chromium against live isolated platform",
            "loginEnter": "PASS",
            "aiEnter": {"httpStatus": enter_response.value.status, "postCount": enter_posts,
                        "queryStatus": enter_payload["data"]["query"]["status"]},
            "recommendationChip": {"httpStatus": chip_response.value.status, "postCount": chip_posts,
                                   "queryStatus": chip_payload["data"]["query"]["status"]},
            "analystRuntimeProfileApiStatus": rbac.status,
            "pageErrors": page_errors,
            "apiResponses": responses,
        }
        output = args.out_dir / "browser-c6-real-api.json"
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({**result, "evidenceFile": str(output)}, ensure_ascii=True, indent=2))
        browser.close()


if __name__ == "__main__":
    main()
