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

# Mobile: 01[016789], each separator position independently '-', '.', a single space,
# or (mobile only) no separator at all. The two gaps are independent character classes,
# so mixed pairings like "010-1234.5678" or "010 1234-5678" match (SPEC §19.2 lists
# permitted separators without requiring both gaps to match).
_MOBILE_PATTERN = re.compile(
    rf"{_NOT_BEFORE_ALNUM}01[016789]"
    rf"(?:[-. ]\d{{3,4}}[-. ]\d{{4}}|\d{{7,8}})"
    rf"{_NOT_AFTER_ALNUM}",
    re.ASCII,
)

# Landline: 0[2-6][0-9]?, a separator is required at each gap (no bare-digit variant),
# but the two gaps may differ.
_LANDLINE_PATTERN = re.compile(
    rf"{_NOT_BEFORE_ALNUM}0[2-6][0-9]?[-. ]\d{{3,4}}[-. ]\d{{4}}{_NOT_AFTER_ALNUM}",
    re.ASCII,
)

# Account: maximal runs of dash-joined ASCII digit groups (SPEC §19.2: groups of any
# length, no per-group digit limit). `finditer` on this pattern cannot backtrack across
# a rejected candidate the way a fixed 3-4 group pattern could, because there is no
# alternative here to reject: every maximal run is found exactly once, and the
# multi-group windows inside it are then checked in Python (see _account_run_span).
# Digit and '-' are disjoint character classes, so this match is linear, not subject to
# catastrophic backtracking.
_DIGIT_DASH_RUN_PATTERN = re.compile(r"[0-9]+(?:-[0-9]+)*", re.ASCII)

_ASCII_ALNUM = frozenset(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
)

_ACCOUNT_WINDOW_SIZES = (3, 4)

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


def _has_alnum_run_boundary(text: str, start: int, end: int) -> bool:
    """True if the character immediately before `start` or at `end` is an ASCII
    letter or digit (SPEC §19.2 "경계"), mirroring the lookaround the other rules
    apply inline in their compiled patterns."""
    if start > 0 and text[start - 1] in _ASCII_ALNUM:
        return True
    if end < len(text) and text[end] in _ASCII_ALNUM:
        return True
    return False


def _account_run_qualifies(run_text: str) -> bool:
    """True if any window of 3-4 consecutive dash-joined groups in this run has a
    total digit count in [10, 14], and the run does not start with the date shape
    (SPEC §19.2). A qualifying run is masked in full (see module docstring for why
    this cannot be narrowed to the window without leaving the rest of the run, which
    is still a dash-joined digit chain, visible)."""
    if _DATE_SHAPE_PREFIX.match(run_text):
        return False
    group_lengths = [len(group) for group in run_text.split("-")]
    group_count = len(group_lengths)
    for window_size in _ACCOUNT_WINDOW_SIZES:
        if window_size > group_count:
            continue
        for window_start in range(group_count - window_size + 1):
            window_end = window_start + window_size
            total_digits = sum(group_lengths[window_start:window_end])
            if _ACCOUNT_MIN_DIGITS <= total_digits <= _ACCOUNT_MAX_DIGITS:
                return True
    return False


def _find_account_spans(text: str) -> list[tuple[int, int, ResponseScanCategory]]:
    spans: list[tuple[int, int, ResponseScanCategory]] = []
    for match in _DIGIT_DASH_RUN_PATTERN.finditer(text):
        start, end = match.start(), match.end()
        if _has_alnum_run_boundary(text, start, end):
            continue
        if _account_run_qualifies(match.group()):
            spans.append((start, end, ResponseScanCategory.ACCOUNT_NUMBER))
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
