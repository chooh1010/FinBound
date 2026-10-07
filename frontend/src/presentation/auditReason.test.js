import { describe, expect, it } from 'vitest'

import { describeAuditReason } from './auditReason'

describe('audit reason presentation', () => {
  it('does not describe an unknown reason code as a normal result', () => {
    expect(describeAuditReason({
      auditStatus: 'ERROR',
      decision: 'ALLOW',
      reasonCodes: ['UNREGISTERED_DOWNSTREAM_FAILURE'],
    })).toBe('처리 사유 설명이 제공되지 않았습니다.')
  })

  it('describes a known downstream error as an error', () => {
    expect(describeAuditReason({
      auditStatus: 'ERROR',
      decision: 'ALLOW',
      reasonCodes: ['DOWNSTREAM_ERROR'],
    })).toContain('오류')
  })

  it('describes an approval as held for a person, not as a block', () => {
    const description = describeAuditReason({
      auditStatus: 'COMPLETED',
      decision: 'APPROVAL',
      reasonCodes: ['BEHAVIOR_ANOMALY'],
    })

    expect(description).toContain('담당자 확인')
    expect(description).not.toContain('차단')
  })

  it('uses the normal message only for an explicit completed allow without a reason code', () => {
    expect(describeAuditReason({
      auditStatus: 'COMPLETED',
      decision: 'ALLOW',
      reasonCodes: [],
    })).toBe('요청한 업무 범위 안에서 정상 처리했습니다.')
  })

  it.each([
    ['RRN_MASKED', '주민등록번호'],
    ['ACCOUNT_NUMBER_MASKED', '계좌번호'],
    ['PHONE_NUMBER_MASKED', '전화번호'],
    ['OTHER_CUSTOMER_DATA_IN_RESPONSE', '다른 고객'],
    ['RESPONSE_SCAN_UNAVAILABLE', '검사'],
    ['RESPONSE_SCAN_DISABLED', '검사'],
    ['RESPONSE_TOO_LARGE', '크기'],
    ['MASKING_FAILED', '가리는 처리'],
  ])('describes the response-scan reason code %s in Korean', (reasonCode, expectedFragment) => {
    const description = describeAuditReason({
      auditStatus: 'COMPLETED',
      decision: reasonCode === 'OTHER_CUSTOMER_DATA_IN_RESPONSE' ? 'BLOCK' : 'MASK',
      reasonCodes: [reasonCode],
    })

    expect(description).toContain(expectedFragment)
  })

  it('describes a masked response without claiming it was blocked', () => {
    const description = describeAuditReason({
      auditStatus: 'COMPLETED',
      decision: 'MASK',
      decisionStage: 'RESPONSE',
      reasonCodes: ['RRN_MASKED'],
    })

    expect(description).not.toContain('차단')
  })
})
