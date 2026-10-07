import importlib
import json
import logging
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.response_scan.rules import CodePointSpan
from app.response_scan.service import ResponseScanService
from app.schemas.response_scan import ResponseScanCategory

main_module = importlib.import_module("app.main")
client = TestClient(app)
TEST_CREDENTIAL = "test-internal-credential"
INTERNAL_HEADERS = {"X-FinGuard-Service-Credential": TEST_CREDENTIAL}

# contracts/response-scan/fixtures/cases.json, shared verbatim with the Gateway's JUnit
# suite (SPEC §19.2). Do not edit; read it and assert against it.
_FIXTURE_PATH = (
    Path(__file__).resolve().parents[2] / "contracts" / "response-scan" / "fixtures" / "cases.json"
)
_FIXTURE_CASES = json.loads(_FIXTURE_PATH.read_text(encoding="utf-8"))["cases"]


@pytest.fixture(autouse=True)
def configure_internal_credential(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FINGUARD_INTERNAL_CREDENTIAL", TEST_CREDENTIAL)


def _payload(text: str, target_consumer_id: str = "CUST-1001", request_id: str = "REQ-001") -> dict[str, str]:
    return {
        "requestId": request_id,
        "tool": "LOAN_APPLICATION_READ",
        "targetConsumerId": target_consumer_id,
        "text": text,
    }


def _slice_utf16(text: str, start: int, end: int) -> str:
    """Slice `text` by UTF-16 code unit offsets, the unit §19.2 mandates for findings."""
    utf16_bytes = text.encode("utf-16-le")
    return utf16_bytes[start * 2 : end * 2].decode("utf-16-le")


def _post(text: str, target_consumer_id: str = "CUST-1001", request_id: str = "REQ-001"):
    return client.post(
        "/internal/v1/risk/response-scan",
        json=_payload(text, target_consumer_id, request_id),
        headers=INTERNAL_HEADERS,
    )


# ---------------------------------------------------------------------------
# Shared cross-language fixture cases
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("case", _FIXTURE_CASES, ids=[case["name"] for case in _FIXTURE_CASES])
def test_fixture_case_findings_counts_and_values_match(case: dict) -> None:
    response = _post(case["text"], case["targetConsumerId"], request_id=f"REQ-{case['name']}")

    assert response.status_code == 200
    payload = response.json()
    assert payload["detectorVersion"] == "response-scan-1"
    assert payload["counts"] == case["counts"]
    assert len(payload["findings"]) == len(case["findings"])
    for actual, expected in zip(payload["findings"], case["findings"], strict=True):
        assert actual["category"] == expected["category"]
        assert actual["start"] == expected["start"]
        assert actual["end"] == expected["end"]
        assert _slice_utf16(case["text"], actual["start"], actual["end"]) == expected["value"]


def test_fixture_cases_never_leak_text_or_matched_values_into_logs(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="app.response_scan")

    for case in _FIXTURE_CASES:
        response = _post(case["text"], case["targetConsumerId"], request_id=f"REQ-leak-{case['name']}")
        assert response.status_code == 200
        assert case["text"] not in response.text or case["text"] == ""
        for finding in case["findings"]:
            assert finding["value"] not in response.text

    for case in _FIXTURE_CASES:
        if case["text"]:
            assert case["text"] not in caplog.text
        for finding in case["findings"]:
            assert finding["value"] not in caplog.text


# ---------------------------------------------------------------------------
# Extra rule coverage beyond the shared fixture
# ---------------------------------------------------------------------------


def test_landline_with_hyphen_is_detected() -> None:
    response = _post("연락처 02-123-4567 확인")

    assert response.status_code == 200
    findings = response.json()["findings"]
    assert len(findings) == 1
    assert findings[0]["category"] == "PHONE_NUMBER"
    text = "연락처 02-123-4567 확인"
    assert _slice_utf16(text, findings[0]["start"], findings[0]["end"]) == "02-123-4567"


def test_dotted_mobile_phone_is_detected() -> None:
    text = "연락처 010.1234.5678 확인"
    response = _post(text)

    assert response.status_code == 200
    findings = response.json()["findings"]
    assert len(findings) == 1
    assert findings[0]["category"] == "PHONE_NUMBER"
    assert _slice_utf16(text, findings[0]["start"], findings[0]["end"]) == "010.1234.5678"


def test_rrn_with_single_space_separator_is_detected() -> None:
    text = "번호 900101 1234567 확인"
    response = _post(text)

    assert response.status_code == 200
    findings = response.json()["findings"]
    assert len(findings) == 1
    assert findings[0]["category"] == "RRN"
    assert _slice_utf16(text, findings[0]["start"], findings[0]["end"]) == "900101 1234567"


def test_date_shaped_four_group_account_is_excluded() -> None:
    # 2026-10-07-12 has 4 groups and 10 total digits (within the account range) but is
    # excluded because it starts with a yyyy-mm-dd date shape (SPEC §19.2).
    response = _post("접수 2026-10-07-12 처리")

    assert response.status_code == 200
    assert response.json()["findings"] == []
    assert response.json()["counts"] == {
        "RRN": 0,
        "ACCOUNT_NUMBER": 0,
        "PHONE_NUMBER": 0,
        "OTHER_CUSTOMER": 0,
    }


def test_rrn_rejects_february_29_in_a_non_leap_year() -> None:
    # 1999 (century digit '2' -> 1900s) was not a leap year, so Feb 29 is not a valid date.
    response = _post("번호 990229-2123456 확인")

    assert response.status_code == 200
    assert response.json()["findings"] == []


def test_rrn_accepts_february_29_in_a_leap_year() -> None:
    # 2000 (century digit '4' -> 2000s) was a leap year, so Feb 29 is a valid date.
    text = "번호 000229-4123456 확인"
    response = _post(text)

    assert response.status_code == 200
    findings = response.json()["findings"]
    assert len(findings) == 1
    assert findings[0]["category"] == "RRN"
    assert _slice_utf16(text, findings[0]["start"], findings[0]["end"]) == "000229-4123456"


def test_full_width_digits_are_not_detected() -> None:
    # SPEC §19.2's known limitations explicitly list full-width digits as missed.
    response = _post("번호 ９００１０１－１２３４５６７ 확인")

    assert response.status_code == 200
    assert response.json()["findings"] == []


def test_letter_immediately_after_match_breaks_boundary() -> None:
    response = _post("안내 900101-1234567a 확인")

    assert response.status_code == 200
    assert response.json()["findings"] == []


def test_letter_immediately_before_match_breaks_boundary() -> None:
    response = _post("안내 a900101-1234567 확인")

    assert response.status_code == 200
    assert response.json()["findings"] == []


def test_own_target_customer_id_is_not_flagged() -> None:
    response = _post("대상 고객 CUST-1001 신청서", target_consumer_id="CUST-1001")

    assert response.status_code == 200
    assert response.json()["findings"] == []


def test_empty_text_is_a_normal_request_with_zero_findings() -> None:
    response = _post("")

    assert response.status_code == 200
    payload = response.json()
    assert payload["findings"] == []
    assert payload["counts"] == {
        "RRN": 0,
        "ACCOUNT_NUMBER": 0,
        "PHONE_NUMBER": 0,
        "OTHER_CUSTOMER": 0,
    }


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------


def test_response_scan_requires_internal_credential() -> None:
    missing = client.post("/internal/v1/risk/response-scan", json=_payload("정상 입력"))
    invalid = client.post(
        "/internal/v1/risk/response-scan",
        json=_payload("정상 입력"),
        headers={"X-FinGuard-Service-Credential": "invalid"},
    )

    assert missing.status_code == 401
    assert missing.json()["detail"] == "INTERNAL_CREDENTIAL_INVALID"
    assert invalid.status_code == 401
    assert invalid.json()["detail"] == "INTERNAL_CREDENTIAL_INVALID"


def test_response_scan_fails_closed_when_server_credential_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("FINGUARD_INTERNAL_CREDENTIAL")

    response = _post("정상 입력")

    assert response.status_code == 503
    assert response.json()["detail"] == "RESPONSE_SCAN_UNAVAILABLE"


# ---------------------------------------------------------------------------
# 413 (too large)
# ---------------------------------------------------------------------------


def test_response_scan_rejects_text_over_16kib_utf8(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO, logger="app.response_scan")
    oversized_text = "a" * (16 * 1024 + 1)

    response = _post(oversized_text, request_id="REQ-oversized")

    assert response.status_code == 413
    assert response.json()["detail"] == "RESPONSE_TOO_LARGE"
    assert oversized_text not in response.text
    assert oversized_text not in caplog.text
    assert "REQ-oversized" in caplog.text


def test_response_scan_accepts_text_at_exactly_16kib_utf8() -> None:
    boundary_text = "a" * (16 * 1024)

    response = _post(boundary_text)

    assert response.status_code == 200


# ---------------------------------------------------------------------------
# 422 (bad shape, non-string text, lone surrogate)
# ---------------------------------------------------------------------------


def test_response_scan_rejects_unknown_field() -> None:
    payload = _payload("정상 입력")
    payload["unexpectedField"] = "nope"

    response = client.post(
        "/internal/v1/risk/response-scan", json=payload, headers=INTERNAL_HEADERS
    )

    assert response.status_code == 422
    assert response.json() == {"detail": "REQUEST_VALIDATION_FAILED"}


def test_response_scan_rejects_non_string_text() -> None:
    payload = _payload("정상 입력")
    payload["text"] = 12345

    response = client.post(
        "/internal/v1/risk/response-scan", json=payload, headers=INTERNAL_HEADERS
    )

    assert response.status_code == 422
    assert response.json() == {"detail": "REQUEST_VALIDATION_FAILED"}


def _post_raw_json(payload: dict[str, object]) -> object:
    # httpx's own JSON encoder tries to encode the body as UTF-8 client-side, which
    # raises before the request is even sent if `text` holds a lone surrogate. Build the
    # ASCII-safe (`\udXXX`-escaped) JSON ourselves so the invalid character only exists
    # once the server decodes it, matching what a real lone-surrogate payload looks like
    # on the wire.
    raw_body = json.dumps(payload, ensure_ascii=True).encode("ascii")
    return client.post(
        "/internal/v1/risk/response-scan",
        content=raw_body,
        headers={**INTERNAL_HEADERS, "Content-Type": "application/json"},
    )


def test_response_scan_rejects_lone_surrogate_in_text(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.INFO, logger="app.response_scan")
    sensitive_text = "비밀 900101-1234567 \ud800 끝"

    response = _post_raw_json(_payload(sensitive_text, request_id="REQ-surrogate"))

    assert response.status_code == 422
    assert response.json() == {"detail": "REQUEST_VALIDATION_FAILED"}
    assert "900101-1234567" not in response.text
    assert "900101-1234567" not in caplog.text
    assert "REQ-surrogate" in caplog.text


def test_response_scan_rejects_surrogate_at_a_matched_endpoint(caplog: pytest.LogCaptureFixture) -> None:
    # A lone surrogate right next to what would otherwise be an RRN match still fails
    # closed: the text as a whole cannot be measured in UTF-8, regardless of where the
    # surrogate sits.
    caplog.set_level(logging.INFO, logger="app.response_scan")
    text = "900101-1234567\ud800"

    response = _post_raw_json(_payload(text, request_id="REQ-surrogate-2"))

    assert response.status_code == 422
    assert "900101-1234567" not in caplog.text


# ---------------------------------------------------------------------------
# 503 (internal failure, fail-closed)
# ---------------------------------------------------------------------------


def test_response_scan_fails_closed_on_detector_error(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.INFO, logger="app.response_scan")

    def _raise(_text: str, _target_consumer_id: str) -> list[CodePointSpan]:
        raise RuntimeError("synthetic detector failure")

    monkeypatch.setattr(
        main_module, "response_scan_service", ResponseScanService(find_merged_spans_fn=_raise)
    )
    sensitive_text = "비밀 900101-1234567 번호"

    response = _post(sensitive_text, request_id="REQ-detector-fail")

    assert response.status_code == 503
    assert response.json()["detail"] == "RESPONSE_SCAN_UNAVAILABLE"
    assert "900101-1234567" not in response.text
    assert "900101-1234567" not in caplog.text
    assert "REQ-detector-fail" in caplog.text


def test_response_scan_fails_closed_when_findings_exceed_the_cap(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def _too_many(_text: str, _target_consumer_id: str) -> list[CodePointSpan]:
        return [
            CodePointSpan(index, index + 1, ResponseScanCategory.RRN) for index in range(0, 600, 2)
        ]

    monkeypatch.setattr(
        main_module, "response_scan_service", ResponseScanService(find_merged_spans_fn=_too_many)
    )

    response = _post("placeholder text", request_id="REQ-too-many")

    assert response.status_code == 503
    assert response.json()["detail"] == "RESPONSE_SCAN_UNAVAILABLE"


# ---------------------------------------------------------------------------
# Overlap/priority and own-identifier regression (duplicated here deliberately:
# these are exactly the behaviors the fixture's "overlap" and "other-customer"
# cases exercise, kept close to the rule module for fast local debugging)
# ---------------------------------------------------------------------------


def test_other_customer_outranks_phone_number_in_a_merge() -> None:
    text = "CUST-1001 연락처 010-1234-5678 CUST-1001"
    response = _post(text, target_consumer_id="CUST-1002", request_id="REQ-priority")

    assert response.status_code == 200
    categories = {finding["category"] for finding in response.json()["findings"]}
    assert "OTHER_CUSTOMER" in categories
