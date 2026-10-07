from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.behavior import ContractModel, FinancialTool

MAX_FINDINGS = 256


class ResponseScanCategory(StrEnum):
    RRN = "RRN"
    ACCOUNT_NUMBER = "ACCOUNT_NUMBER"
    PHONE_NUMBER = "PHONE_NUMBER"
    OTHER_CUSTOMER = "OTHER_CUSTOMER"


class ResponseScanRequest(ContractModel):
    request_id: str = Field(min_length=1, max_length=128)
    tool: FinancialTool
    target_consumer_id: str = Field(min_length=1, max_length=128)
    # Size (16 KiB UTF-8) is checked after parsing so the failure maps to 413, not the
    # generic 422 that a pydantic max_length violation would produce.
    text: str


class ResponseScanFinding(ContractModel):
    category: ResponseScanCategory
    start: int = Field(ge=0)
    end: int = Field(gt=0)


class ResponseScanCounts(BaseModel):
    # Deliberately not a ContractModel: the keys are the category names themselves
    # (RRN, ACCOUNT_NUMBER, ...), not snake_case fields that need a camelCase alias.
    model_config = ConfigDict(extra="forbid")

    RRN: int = Field(ge=0, le=MAX_FINDINGS)
    ACCOUNT_NUMBER: int = Field(ge=0, le=MAX_FINDINGS)
    PHONE_NUMBER: int = Field(ge=0, le=MAX_FINDINGS)
    OTHER_CUSTOMER: int = Field(ge=0, le=MAX_FINDINGS)


class ResponseScanResponse(ContractModel):
    detector_version: str = Field(pattern=r"^response-scan-[0-9]{1,4}$")
    findings: list[ResponseScanFinding] = Field(max_length=MAX_FINDINGS)
    counts: ResponseScanCounts
