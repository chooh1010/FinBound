"""Regex-based detection rules for the response-scan detector (SPEC §19.2).

All matching happens in Python ``str`` (Unicode code point) index space, because
that is what ``re`` operates on. Callers convert the resulting spans to UTF-16
code unit offsets (see :mod:`app.response_scan.service`) only after this module's
overlap-merge logic has run, since merging is invariant under that monotonic
conversion: two spans that touch or overlap at a code point index touch or
overlap at the corresponding UTF-16 index too.
"""

import re
from dataclasses import dataclass

from app.schemas.response_scan import ResponseScanCategory

# A match is rejected if the character immediately before or after it is an
# ASCII letter or digit (SPEC §19.2 "경계"). Lookaround keeps this local to each
# rule's pattern instead of a separate post-processing pass.
_NOT_BEFORE_ALNUM = r"(?<![A-Za-z0-9])"
_NOT_AFTER_ALNUM = r"(?![A-Za-z0-9])"

# re.ASCII keeps \d limited to '0'-'9'. Without it, Python's \d also matches other
# Unicode decimal-digit scripts (e.g. full-width digits), which SPEC §19.2's "known
# limitations" explicitly lists as a case this detector misses, not one it catches.
_RRN_PATTERN = re.compile(
    rf"{_NOT_BEFORE_ALNUM}(\d{{6}})([- ]?)(\d{{7}}){_NOT_AFTER_ALNUM}", re.ASCII
)

# Mobile: 01[016789], groups joined by '-', '.', a single space, or no separator at all.
# The same separator is used for both gaps; spec gives no evidence that mixing them
# (e.g. "010-1234.5678") is an intended format, and no real phone number is written
# that way.
_MOBILE_PATTERN = re.compile(
    rf"{_NOT_BEFORE_ALNUM}01[016789]"
    rf"(?:-\d{{3,4}}-\d{{4}}|\.\d{{3,4}}\.\d{{4}}| \d{{3,4}} \d{{4}}|\d{{3,4}}\d{{4}})"
    rf"{_NOT_AFTER_ALNUM}",
    re.ASCII,
)

# Landline: 0[2-6][0-9]?, a separator is required (no bare-digit variant).
_LANDLINE_PATTERN = re.compile(
    rf"{_NOT_BEFORE_ALNUM}0[2-6][0-9]?"
    rf"(?:-\d{{3,4}}-\d{{4}}|\.\d{{3,4}}\.\d{{4}}| \d{{3,4}} \d{{4}})"
    rf"{_NOT_AFTER_ALNUM}",
    re.ASCII,
)

# Account: 3-4 digit groups (2-6 digits each) joined by '-'. Range and the
# date-shape exclusion are applied after the match (see _is_valid_account).
# Known limitation (documented in SPEC §19.2 and docs/04 §19.2): because `finditer`
# does not backtrack across a failed candidate, a long chain of 5+ dash-joined groups
# (e.g. "1234-5678-90-123456") can be rejected as a whole even though a 3-4 group
# window inside it would individually satisfy the digit-count rule. This matches the
# spec's framing of the account rule as conservative and not a precise identifier,
# and is not covered by the shared fixtures.
_ACCOUNT_PATTERN = re.compile(
    rf"{_NOT_BEFORE_ALNUM}\d{{2,6}}-\d{{2,6}}-\d{{2,6}}(?:-\d{{2,6}})?{_NOT_AFTER_ALNUM}",
    re.ASCII,
)

_OTHER_CUSTOMER_PATTERN = re.compile(
    rf"{_NOT_BEFORE_ALNUM}CUST-\d{{4}}{_NOT_AFTER_ALNUM}", re.ASCII
)

_DATE_SHAPE_PREFIX = re.compile(r"^(?:19|20)\d{2}-\d{2}-\d{2}", re.ASCII)

_DAYS_IN_MONTH = {
    1: 31,
    2: 28,
    3: 31,
    4: 30,
    5: 31,
    6: 30,
    7: 31,
    8: 31,
    9: 30,
    10: 31,
    11: 30,
    12: 31,
}

# RRN 7th digit -> century, per the convention the spec's own "7번째 1~8" rule is
# built on (1,2=1900s domestic; 3,4=2000s domestic; 5,6=1900s foreign; 7,8=2000s
# foreign). Used only to resolve the YY -> full year for a correct leap-year check;
# no checksum digit is used (SPEC §19.2: "체크섬은 보지 않는다").
_RRN_CENTURY_BASE_YEAR = {
    "1": 1900,
    "2": 1900,
    "3": 2000,
    "4": 2000,
    "5": 1900,
    "6": 1900,
    "7": 2000,
    "8": 2000,
}

_ACCOUNT_MIN_DIGITS = 10
_ACCOUNT_MAX_DIGITS = 14


def _is_leap_year(year: int) -> bool:
    return year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)

# Highest priority first, matching SPEC §19.2's merge rule.
_CATEGORY_PRIORITY = {
    ResponseScanCategory.OTHER_CUSTOMER: 4,
    ResponseScanCategory.RRN: 3,
    ResponseScanCategory.PHONE_NUMBER: 2,
    ResponseScanCategory.ACCOUNT_NUMBER: 1,
}


@dataclass(frozen=True)
class CodePointSpan:
    """A merged, category-resolved match, in Python string (code point) indices."""

    start: int
    end: int
    category: ResponseScanCategory


def _is_valid_rrn_date(first_six_digits: str, century_digit: str) -> bool:
    year_in_century = int(first_six_digits[0:2])
    month = int(first_six_digits[2:4])
    day = int(first_six_digits[4:6])
    if month not in _DAYS_IN_MONTH:
        return False
    max_day = _DAYS_IN_MONTH[month]
    if month == 2 and _is_leap_year(_RRN_CENTURY_BASE_YEAR[century_digit] + year_in_century):
        max_day = 29
    return 1 <= day <= max_day


def _find_rrn_spans(text: str) -> list[tuple[int, int, ResponseScanCategory]]:
    spans: list[tuple[int, int, ResponseScanCategory]] = []
    for match in _RRN_PATTERN.finditer(text):
        first_six, _separator, last_seven = match.groups()
        century_digit = last_seven[0]
        if century_digit not in _RRN_CENTURY_BASE_YEAR:
            continue
        if not _is_valid_rrn_date(first_six, century_digit):
            continue
        spans.append((match.start(), match.end(), ResponseScanCategory.RRN))
    return spans


def _find_phone_spans(text: str) -> list[tuple[int, int, ResponseScanCategory]]:
    spans: list[tuple[int, int, ResponseScanCategory]] = []
    for pattern in (_MOBILE_PATTERN, _LANDLINE_PATTERN):
        for match in pattern.finditer(text):
            spans.append((match.start(), match.end(), ResponseScanCategory.PHONE_NUMBER))
    return spans


def _is_valid_account(matched_text: str) -> bool:
    if _DATE_SHAPE_PREFIX.match(matched_text):
        return False
    digit_count = sum(character.isdigit() for character in matched_text)
    return _ACCOUNT_MIN_DIGITS <= digit_count <= _ACCOUNT_MAX_DIGITS


def _find_account_spans(text: str) -> list[tuple[int, int, ResponseScanCategory]]:
    spans: list[tuple[int, int, ResponseScanCategory]] = []
    for match in _ACCOUNT_PATTERN.finditer(text):
        if _is_valid_account(match.group()):
            spans.append((match.start(), match.end(), ResponseScanCategory.ACCOUNT_NUMBER))
    return spans


def _find_other_customer_spans(
    text: str, target_consumer_id: str
) -> list[tuple[int, int, ResponseScanCategory]]:
    spans: list[tuple[int, int, ResponseScanCategory]] = []
    for match in _OTHER_CUSTOMER_PATTERN.finditer(text):
        if match.group() == target_consumer_id:
            continue
        spans.append((match.start(), match.end(), ResponseScanCategory.OTHER_CUSTOMER))
    return spans


def _highest_priority_category(
    categories: set[ResponseScanCategory],
) -> ResponseScanCategory:
    return max(categories, key=lambda category: _CATEGORY_PRIORITY[category])


def _merge_spans(
    raw_spans: list[tuple[int, int, ResponseScanCategory]],
) -> list[CodePointSpan]:
    if not raw_spans:
        return []
    ordered = sorted(raw_spans, key=lambda span: span[0])
    merged: list[CodePointSpan] = []
    current_start, current_end, current_category = ordered[0]
    current_categories = {current_category}
    for start, end, category in ordered[1:]:
        if start <= current_end:
            current_end = max(current_end, end)
            current_categories.add(category)
            continue
        merged.append(
            CodePointSpan(current_start, current_end, _highest_priority_category(current_categories))
        )
        current_start, current_end, current_categories = start, end, {category}
    merged.append(
        CodePointSpan(current_start, current_end, _highest_priority_category(current_categories))
    )
    return merged


def find_merged_spans(text: str, target_consumer_id: str) -> list[CodePointSpan]:
    """Detect every rule match and merge overlapping/touching ones (SPEC §19.2).

    Spans are in Python string (code point) index space. The caller is
    responsible for converting to UTF-16 code unit offsets.
    """
    raw_spans: list[tuple[int, int, ResponseScanCategory]] = []
    raw_spans.extend(_find_other_customer_spans(text, target_consumer_id))
    raw_spans.extend(_find_rrn_spans(text))
    raw_spans.extend(_find_phone_spans(text))
    raw_spans.extend(_find_account_spans(text))
    return _merge_spans(raw_spans)
