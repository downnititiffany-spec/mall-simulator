"""Offline contract tests for the RuntimeProfile read-only browser verifier."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


VERIFIER_PATH = Path(__file__).with_name("verify-runtime-profile-authenticated-readonly.py")
SPEC = importlib.util.spec_from_file_location("runtime_profile_verifier", VERIFIER_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class FakeResponse:
    def __init__(self, url: str, status: int, body: object):
        self.url = url
        self.status = status
        self.body = body

    def json(self):
        if isinstance(self.body, Exception):
            raise self.body
        return self.body


class ReadApiResponseContractTest(unittest.TestCase):
    def test_successful_empty_list_is_a_valid_response(self):
        checks = {}
        response = FakeResponse(
            "http://127.0.0.1:5173/api/v1/runtime-profiles",
            200,
            {"code": "OK", "data": []},
        )

        VERIFIER.record_read_api_response(response, checks)

        self.assertEqual(
            checks["runtime-profiles"],
            {"http_ok": True, "envelope_ok": True, "data_is_array": True},
        )

    def test_non_success_status_and_business_code_are_not_accepted(self):
        checks = {}
        response = FakeResponse(
            "http://127.0.0.1:5173/api/v1/sources",
            503,
            {"code": "ERROR", "data": []},
        )

        VERIFIER.record_read_api_response(response, checks)

        self.assertEqual(
            checks["sources"],
            {"http_ok": False, "envelope_ok": False, "data_is_array": True},
        )

    def test_invalid_json_and_non_array_data_fail_contract(self):
        checks = {}
        invalid_json = FakeResponse(
            "http://127.0.0.1:5173/api/v1/sources", 200, ValueError("private body")
        )
        VERIFIER.record_read_api_response(invalid_json, checks)
        self.assertEqual(
            checks["sources"],
            {"http_ok": True, "envelope_ok": False, "data_is_array": False},
        )

        non_array = FakeResponse(
            "http://127.0.0.1:5173/api/v1/sources",
            200,
            {"code": "OK", "data": {"items": []}},
        )
        VERIFIER.record_read_api_response(non_array, checks)
        self.assertEqual(
            checks["sources"],
            {"http_ok": True, "envelope_ok": True, "data_is_array": False},
        )

    def test_non_list_endpoint_is_ignored(self):
        checks = {}
        logout = FakeResponse(
            "http://127.0.0.1:5173/api/v1/auth/logout", 200, {"code": "OK"}
        )

        VERIFIER.record_read_api_response(logout, checks)

        self.assertEqual(checks, {})

    def test_both_successful_endpoints_are_required_for_contract_pass(self):
        valid = {
            "runtime-profiles": {"http_ok": True, "envelope_ok": True, "data_is_array": True},
            "sources": {"http_ok": True, "envelope_ok": True, "data_is_array": True},
        }

        self.assertTrue(VERIFIER.read_api_contracts_ok(valid))
        self.assertFalse(VERIFIER.read_api_contracts_ok({"runtime-profiles": valid["runtime-profiles"]}))

        invalid_source = {**valid, "sources": {**valid["sources"], "envelope_ok": False}}
        self.assertFalse(VERIFIER.read_api_contracts_ok(invalid_source))


class LoginDiagnosticTest(unittest.TestCase):
    def test_login_diagnostic_keeps_only_allowlisted_code_and_status(self):
        result = {}
        response = FakeResponse(
            "http://127.0.0.1:5173/api/v1/auth/login",
            401,
            {"code": "BAD_CREDENTIALS", "data": {"token": "must-not-be-retained"}},
        )

        VERIFIER.record_login_response(response, result)

        self.assertEqual(result, {"http_status": 401, "business_code": "BAD_CREDENTIALS"})

    def test_unknown_login_code_is_reduced_to_other(self):
        result = {}
        response = FakeResponse(
            "http://127.0.0.1:5173/api/v1/auth/login",
            500,
            {"code": "private diagnostic text", "message": "private"},
        )

        VERIFIER.record_login_response(response, result)

        self.assertEqual(result, {"http_status": 500, "business_code": "OTHER"})

    def test_non_login_response_is_ignored(self):
        result = {}
        VERIFIER.record_login_response(
            FakeResponse("http://127.0.0.1:5173/api/v1/auth/logout", 200, {"code": "OK"}),
            result,
        )
        self.assertEqual(result, {})


if __name__ == "__main__":
    unittest.main()
