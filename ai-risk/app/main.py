import logging
import time
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.behavior.service import BehaviorModelError, BehaviorRiskService
from app.prompt.service import PromptInputError, PromptModelError, PromptRiskService
from app.response_scan.service import (
    ResponseScanService,
    ResponseScanTooLargeError,
    ResponseScanUnavailableError,
    ResponseScanValidationError,
)
from app.schemas.behavior import BehaviorRiskRequest, BehaviorRiskResponse
from app.schemas.prompt import PromptRiskRequest, PromptRiskResponse
from app.schemas.response_scan import ResponseScanCounts, ResponseScanRequest, ResponseScanResponse
from app.security import (
    internal_credential_is_configured,
    verify_internal_credential,
    verify_prompt_internal_credential,
    verify_response_scan_internal_credential,
)

logger = logging.getLogger("app.response_scan")

app = FastAPI(
    title="FinBound AI Risk Engine",
    version="0.1.0",
    description="Returns risk signals only; authorization decisions belong to OPA.",
)
behavior_service = BehaviorRiskService()
prompt_service = PromptRiskService()
response_scan_service = ResponseScanService()


@app.exception_handler(RequestValidationError)
def request_validation_exception_handler(
    _request: Request,
    _error: RequestValidationError,
) -> JSONResponse:
    # Pydantic validation errors include the rejected input by default. Prompt and
    # financial request bodies must not be reflected into responses that callers may log.
    return JSONResponse(status_code=422, content={"detail": "REQUEST_VALIDATION_FAILED"})


@app.get("/health", tags=["operations"])
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/ready", tags=["operations"])
def ready() -> dict[str, str]:
    try:
        if not internal_credential_is_configured():
            raise BehaviorModelError("Internal credential is not configured")
        behavior_service.check_ready()
        prompt_service.check_ready()
    except BehaviorModelError as error:
        raise HTTPException(status_code=503, detail="BEHAVIOR_RISK_UNAVAILABLE") from error
    except PromptModelError as error:
        raise HTTPException(status_code=503, detail="PROMPT_RISK_UNAVAILABLE") from error
    return {"status": "READY"}


@app.post(
    "/internal/v1/risk/behavior",
    response_model=BehaviorRiskResponse,
    response_model_by_alias=True,
    tags=["risk"],
)
def evaluate_behavior(
    request: BehaviorRiskRequest,
    _internal_credential: Annotated[None, Depends(verify_internal_credential)],
) -> BehaviorRiskResponse:
    try:
        return behavior_service.evaluate(request)
    except BehaviorModelError as error:
        raise HTTPException(status_code=503, detail="BEHAVIOR_RISK_UNAVAILABLE") from error


@app.post(
    "/internal/v1/risk/prompt",
    response_model=PromptRiskResponse,
    response_model_by_alias=True,
    tags=["risk"],
)
def evaluate_prompt(
    request: PromptRiskRequest,
    _internal_credential: Annotated[None, Depends(verify_prompt_internal_credential)],
) -> PromptRiskResponse:
    try:
        return prompt_service.evaluate(request)
    except PromptInputError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    except PromptModelError as error:
        raise HTTPException(status_code=503, detail="PROMPT_RISK_UNAVAILABLE") from error


def _log_response_scan_success(
    request_id: str, counts: ResponseScanCounts, elapsed_ms: float
) -> None:
    # SPEC §19.2: only requestId, per-category counts, and elapsed time may be logged.
    # The scanned text, matched values, and positions never appear here.
    logger.info(
        "response_scan_completed request_id=%s rrn=%d account_number=%d "
        "phone_number=%d other_customer=%d elapsed_ms=%.3f",
        request_id,
        counts.RRN,
        counts.ACCOUNT_NUMBER,
        counts.PHONE_NUMBER,
        counts.OTHER_CUSTOMER,
        elapsed_ms,
    )


def _log_response_scan_failure(request_id: str, reason: str, elapsed_ms: float) -> None:
    # No counts are logged here: a failed scan has no trustworthy counts to report.
    logger.warning(
        "response_scan_failed request_id=%s reason=%s elapsed_ms=%.3f",
        request_id,
        reason,
        elapsed_ms,
    )


@app.post(
    "/internal/v1/risk/response-scan",
    response_model=ResponseScanResponse,
    response_model_by_alias=True,
    tags=["risk"],
)
def evaluate_response_scan(
    request: ResponseScanRequest,
    _internal_credential: Annotated[None, Depends(verify_response_scan_internal_credential)],
) -> ResponseScanResponse:
    started_at = time.monotonic()
    try:
        result = response_scan_service.evaluate(request)
    except ResponseScanValidationError as error:
        elapsed_ms = (time.monotonic() - started_at) * 1000
        _log_response_scan_failure(request.request_id, "RESPONSE_SCAN_TEXT_MALFORMED", elapsed_ms)
        raise HTTPException(status_code=422, detail="REQUEST_VALIDATION_FAILED") from error
    except ResponseScanTooLargeError as error:
        elapsed_ms = (time.monotonic() - started_at) * 1000
        _log_response_scan_failure(request.request_id, "RESPONSE_TOO_LARGE", elapsed_ms)
        raise HTTPException(status_code=413, detail="RESPONSE_TOO_LARGE") from error
    except ResponseScanUnavailableError as error:
        elapsed_ms = (time.monotonic() - started_at) * 1000
        _log_response_scan_failure(request.request_id, "RESPONSE_SCAN_UNAVAILABLE", elapsed_ms)
        raise HTTPException(status_code=503, detail="RESPONSE_SCAN_UNAVAILABLE") from error

    elapsed_ms = (time.monotonic() - started_at) * 1000
    _log_response_scan_success(request.request_id, result.counts, elapsed_ms)
    return result
