from collections.abc import Callable

from app.response_scan.rules import CodePointSpan, find_merged_spans
from app.schemas.response_scan import (
    MAX_FINDINGS,
    ResponseScanCounts,
    ResponseScanFinding,
    ResponseScanRequest,
    ResponseScanResponse,
)

DETECTOR_VERSION = "response-scan-1"

MAX_TEXT_BYTES = 16 * 1024


class ResponseScanError(Exception):
    """Base error for the response-scan endpoint; never carries the scanned text."""


class ResponseScanValidationError(ResponseScanError):
    """The text is not well-formed (e.g. a lone UTF-16 surrogate). Maps to 422."""


class ResponseScanTooLargeError(ResponseScanError):
    """The text exceeds the 16 KiB UTF-8 limit. Maps to 413."""


class ResponseScanUnavailableError(ResponseScanError):
    """Detection could not produce a trustworthy result. Maps to 503 (fail-closed)."""


def _utf16_offset(text: str, code_point_index: int) -> int:
    """Convert a Python string (code point) index to a UTF-16 code unit offset.

    Exactly the formula required by SPEC §19.2: ``len(text[:i].encode("utf-16-le")) // 2``.
    """
    return len(text[:code_point_index].encode("utf-16-le")) // 2


def _reject_malformed_text(text: str) -> None:
    # A lone (unpaired) surrogate cannot be encoded as UTF-8 or UTF-16; this is the
    # cheapest way to detect it and it also doubles as the UTF-8 size measurement below.
    try:
        text.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ResponseScanValidationError("RESPONSE_SCAN_TEXT_MALFORMED") from error


def _reject_oversized_text(text: str) -> None:
    if len(text.encode("utf-8")) > MAX_TEXT_BYTES:
        raise ResponseScanTooLargeError("RESPONSE_TOO_LARGE")


class ResponseScanService:
    def __init__(
        self,
        find_merged_spans_fn: Callable[[str, str], list[CodePointSpan]] | None = None,
    ) -> None:
        self._find_merged_spans = find_merged_spans_fn or find_merged_spans

    def evaluate(self, request: ResponseScanRequest) -> ResponseScanResponse:
        text = request.text
        _reject_malformed_text(text)
        _reject_oversized_text(text)

        try:
            merged_spans = self._find_merged_spans(text, request.target_consumer_id)
        except ResponseScanError:
            raise
        except Exception as error:
            raise ResponseScanUnavailableError("RESPONSE_SCAN_UNAVAILABLE") from error

        if len(merged_spans) > MAX_FINDINGS:
            raise ResponseScanUnavailableError("RESPONSE_SCAN_UNAVAILABLE")

        findings = [
            ResponseScanFinding(
                category=span.category,
                start=_utf16_offset(text, span.start),
                end=_utf16_offset(text, span.end),
            )
            for span in merged_spans
        ]
        counts = ResponseScanCounts(
            RRN=sum(1 for finding in findings if finding.category == "RRN"),
            ACCOUNT_NUMBER=sum(1 for finding in findings if finding.category == "ACCOUNT_NUMBER"),
            PHONE_NUMBER=sum(1 for finding in findings if finding.category == "PHONE_NUMBER"),
            OTHER_CUSTOMER=sum(1 for finding in findings if finding.category == "OTHER_CUSTOMER"),
        )
        return ResponseScanResponse(
            detector_version=DETECTOR_VERSION,
            findings=findings,
            counts=counts,
        )


__all__ = [
    "DETECTOR_VERSION",
    "MAX_TEXT_BYTES",
    "ResponseScanError",
    "ResponseScanService",
    "ResponseScanTooLargeError",
    "ResponseScanUnavailableError",
    "ResponseScanValidationError",
]
