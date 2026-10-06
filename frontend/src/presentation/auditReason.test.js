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
})
