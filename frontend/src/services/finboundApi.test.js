import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  configureFinboundApi,
  finboundApi,
  FinboundApiError,
  mapAuditEvent,
  resetFinboundApi,
} from './finboundApi'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  }
}

afterEach(() => {
  resetFinboundApi()
  vi.restoreAllMocks()
})

describe('real Core API adapter', () => {
  it('creates an AgentRun and reads its result through the public execution API', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-REAL-1',
        agentId: 'LOAN-AGENT-01',
        employeeId: 'EMP-101',
        caseId: 'CASE-REAL-1',
        passportId: 'PASS-REAL-1',
        inputRefs: ['INPUT-REAL-1'],
        status: 'RUNNING',
        startedAt: '2026-09-01T12:00:00+09:00',
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-REAL-1',
        agentEffectivePermission: {
          allowedTools: ['CREDIT_SCORE_READ'],
          allowedData: ['CREDIT_SCORE'],
        },
        withheldTools: ['INCOME_READ'],
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-REAL-1',
        status: 'COMPLETED',
        attempts: [{
          requestId: 'REQ-REAL-1',
          requestedTool: 'CREDIT_SCORE_READ',
          targetConsumerId: 'CUST-1001',
          requestedData: ['CREDIT_SCORE'],
          decision: 'ALLOW',
          systemOutcome: 'COMPLETED',
          reasonCodes: [],
          downstreamReached: true,
          responseReleased: true,
          scopeStatus: { customerScope: 'OK' },
        }],
      }))

    configureFinboundApi({
      mode: 'real',
      baseUrl: 'http://localhost:8080/',
      credential: 'operator-test-credential',
      fetchImpl,
    })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(fetchImpl).toHaveBeenCalledTimes(3)
    expect(fetchImpl.mock.calls[0][0]).toBe('http://localhost:8080/api/v1/agent-runs')
    expect(fetchImpl.mock.calls[0][1].headers.Authorization).toBe('Bearer operator-test-credential')
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({
      employeeId: 'EMP-101',
      consumerId: 'CUST-1001',
      taskType: 'LOAN_REVIEW',
      inputText: '현재 고객의 신규 대출 심사자료 확인',
    })
    expect(fetchImpl.mock.calls[1][0]).toContain('/api/v1/agent-runs/RUN-REAL-1/permission-comparison')
    expect(fetchImpl.mock.calls[2][0]).toContain('/api/v1/agent-runs/RUN-REAL-1/execution')
    expect(fetchImpl.mock.calls.every(([url]) => !url.includes('/internal/'))).toBe(true)
    expect(result.status).toBe('COMPLETED')
    expect(result.attempts[0]).toMatchObject({
      tool: 'CREDIT_SCORE_READ',
      decision: 'ALLOW',
      systemOutcome: 'COMPLETED',
      downstreamReached: true,
      responseReleased: true,
    })
    expect(result.resultItems).toContain('정상 확인 1건')
    expect(result.agentRun).toMatchObject({
      agentRunId: 'RUN-REAL-1',
      passportId: 'PASS-REAL-1',
    })
    expect(result.permission).toMatchObject({
      agentEffectivePermission: {
        allowedTools: ['CREDIT_SCORE_READ'],
        allowedData: ['CREDIT_SCORE'],
      },
      withheldTools: ['INCOME_READ'],
    })
  })

  it('polls the public execution resource until the Agent execution completes', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-POLL-1', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-POLL-1', status: 'RUNNING', attempts: [] }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-POLL-1',
        status: 'COMPLETED',
        attempts: [{
          requestId: 'REQ-POLL-1',
          tool: 'CREDIT_SCORE_READ',
          targetConsumerId: 'CUST-1001',
          requestedData: ['CREDIT_SCORE'],
          decision: 'BLOCK',
          systemOutcome: 'COMPLETED',
          reasonCodes: ['CASE_SCOPE_VIOLATION'],
          downstreamReached: false,
          responseReleased: false,
        }],
      }))
    const sleepImpl = vi.fn().mockResolvedValue(undefined)
    configureFinboundApi({
      mode: 'real',
      credential: 'operator',
      fetchImpl,
      executionPollAttempts: 2,
      sleepImpl,
    })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(sleepImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls.filter(([url]) => url.includes('/execution'))).toHaveLength(2)
    expect(fetchImpl.mock.calls.every(([url]) => !url.includes('/internal/'))).toBe(true)
    expect(result.attempts[0]).toMatchObject({
      decision: 'BLOCK',
      systemOutcome: 'COMPLETED',
      downstreamReached: false,
      responseReleased: false,
    })
  })

  it('shows a terminal Agent failure even when no Gateway audit attempt exists', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-PRE-AUDIT-FAILURE',
        passportId: 'PASS-PRE-AUDIT-FAILURE',
        status: 'RUNNING',
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-PRE-AUDIT-FAILURE',
        status: 'FAILED',
        reasonCodes: [],
        attempts: [],
      }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.status).toBe('ERROR')
    expect(result.attempts).toEqual([])
    expect(result.resultItems).toContain('처리 오류 1건')
  })

  it('preserves verified AgentRun and permission context when execution lookup fails', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-PARTIAL-1',
        passportId: 'PASS-PARTIAL-1',
        status: 'RUNNING',
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: {
          allowedTools: ['CREDIT_SCORE_READ'],
          allowedData: ['CREDIT_SCORE'],
        },
        withheldTools: ['INCOME_READ'],
      }))
      .mockRejectedValueOnce(new Error('execution endpoint unavailable'))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    await expect(finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })).rejects.toMatchObject({
      code: 'CORE_API_UNAVAILABLE',
      executionContext: {
        agentRun: {
          agentRunId: 'RUN-PARTIAL-1',
          passportId: 'PASS-PARTIAL-1',
        },
        permission: {
          agentEffectivePermission: {
            allowedTools: ['CREDIT_SCORE_READ'],
            allowedData: ['CREDIT_SCORE'],
          },
          withheldTools: ['INCOME_READ'],
        },
      },
    })
  })

  it('maps official audit fields and sends supported server filters', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({
      items: [{
        auditEventId: 'AUD-REAL-1',
        status: 'ERROR',
        systemOutcome: 'ERROR',
        promptRiskEvaluationStatus: 'EVALUATED',
        promptRiskLevel: 'ALERT',
        severity: 'HIGH',
        riskFlagged: true,
        behaviorFeatureVersion: 'behavior-features-2',
        reasonCodes: ['DOWNSTREAM_TIMEOUT'],
      }],
      page: 2,
      pageSize: 5,
      totalItems: 8,
      totalPages: 2,
    }))
    configureFinboundApi({ mode: 'real', credential: 'viewer', fetchImpl })

    const result = await finboundApi.getAuditEvents({
      filters: {
        period: '24H',
        outcome: 'ERROR',
        severity: 'HIGH',
        riskOnly: true,
      },
      page: 2,
      pageSize: 5,
    })

    const url = new URL(fetchImpl.mock.calls[0][0], 'http://frontend.local')
    expect(url.searchParams.get('period')).toBe('24H')
    expect(url.searchParams.get('outcome')).toBe('ERROR')
    expect(url.searchParams.get('page')).toBe('2')
    expect(url.searchParams.get('severity')).toBe('HIGH')
    expect(url.searchParams.get('riskOnly')).toBe('true')
    expect(result.items[0]).toMatchObject({
      auditStatus: 'ERROR',
      promptEvaluationStatus: 'EVALUATED',
      promptRiskLevel: 'ALERT',
      severity: 'HIGH',
      riskFlagged: true,
      featureVersion: 'behavior-features-2',
    })
  })

  it('does not call Core without a credential', async () => {
    const fetchImpl = vi.fn()
    configureFinboundApi({ mode: 'real', fetchImpl })

    await expect(finboundApi.getDashboardSummary()).rejects.toMatchObject({
      name: 'FinboundApiError',
      code: 'CORE_API_CREDENTIAL_REQUIRED',
    })
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('preserves application/problem+json reasonCode and detail', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({
      type: 'about:blank',
      title: 'Forbidden',
      status: 403,
      detail: 'Viewer cannot create AgentRun',
      reasonCode: 'CORE_API_ROLE_FORBIDDEN',
    }, 403))
    configureFinboundApi({ mode: 'real', credential: 'viewer', fetchImpl })

    await expect(finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })).rejects.toEqual(
      expect.objectContaining({
        name: 'FinboundApiError',
        code: 'CORE_API_ROLE_FORBIDDEN',
        status: 403,
        message: 'Viewer cannot create AgentRun',
      }),
    )
  })

  it('preserves a credential-filter reasonCode even when detail is absent', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({
      reasonCode: 'CORE_API_CREDENTIAL_INVALID',
    }, 401))
    configureFinboundApi({ mode: 'real', credential: 'invalid', fetchImpl })

    await expect(finboundApi.getDashboardSummary()).rejects.toMatchObject({
      code: 'CORE_API_CREDENTIAL_INVALID',
      status: 401,
    })
  })

  it('preserves a validation problem reasonCode and detail', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({
      status: 422,
      detail: 'The request contains invalid fields',
      reasonCode: 'CORE_API_VALIDATION_FAILED',
      invalidFields: ['employeeId'],
    }, 422))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    await expect(finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })).rejects.toMatchObject({
      code: 'CORE_API_VALIDATION_FAILED',
      status: 422,
      message: 'The request contains invalid fields',
    })
  })

  it('does not convert a non-JSON Core error into an unavailable-network error', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      text: async () => '<html>bad gateway</html>',
    })
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    await expect(finboundApi.getDashboardSummary()).rejects.toMatchObject({
      code: 'CORE_API_HTTP_502',
      status: 502,
    })
  })

  it('fails closed when a completed execution omits its result attempts', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-BROKEN', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-BROKEN', status: 'COMPLETED', attempts: [] }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    await expect(finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })).rejects.toMatchObject({
      code: 'CORE_API_INVALID_RESPONSE',
    })
  })

  it('shows a completed run without attempts as outcome unknown when Core says so', async () => {
    // 결과 기록이 도착하지 않은 시도는 attempts에 없고 실행 사유로만 온다(docs/04 §3).
    // 이때 0건을 "응답 형식 오류"로 버리면 원인이 화면에서 사라진다.
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-UNKNOWN', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-UNKNOWN',
        status: 'COMPLETED',
        reasonCodes: ['AUDIT_OUTCOME_UNKNOWN'],
        attempts: [],
      }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.outcomeUnknown).toBe(true)
    expect(result.title).toBe('AI 업무 결과 기록을 확인할 수 없습니다')
    expect(result.title).not.toBe('AI 업무 처리가 완료되었습니다')
    expect(result.resultItems).toContain('결과 미확인 시도 있음')
  })

  it('shows an approval attempt as waiting, not as a completed or blocked lookup', async () => {
    // APPROVAL은 실행하지 않은 판정이다. Core는 COMPLETED 시도와 실행 사유 AUDIT_APPROVAL_PENDING으로 보낸다(docs/04 §3).
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-APPROVAL', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-APPROVAL',
        status: 'COMPLETED',
        reasonCodes: ['AUDIT_APPROVAL_PENDING', 'BEHAVIOR_ANOMALY'],
        attempts: [{
          requestId: 'REQ-APPROVAL',
          requestedTool: 'CREDIT_SCORE_READ',
          targetConsumerId: 'CUST-1001',
          requestedData: ['CREDIT_SCORE'],
          decision: 'APPROVAL',
          systemOutcome: 'COMPLETED',
          reasonCodes: ['BEHAVIOR_ANOMALY'],
          downstreamReached: false,
          responseReleased: false,
          requestedAt: '2026-10-06T12:00:00Z',
          completedAt: '2026-10-06T12:00:01Z',
        }],
      }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.approvalPending).toBe(true)
    expect(result.title).toBe('담당자 확인을 기다리는 조회가 있습니다')
    expect(result.resultItems).toContain('승인 필요 판정 1건')
    expect(result.resultItems).toContain('안전 차단 0건')
    expect(result.resultItems).toContain('정상 확인 0건')
    expect(result.attempts[0].description).toContain('담당자 확인')
    expect(result.attempts[0].description).not.toContain('차단')
  })

  it.each([
    ['attempts are missing', { reasonCodes: ['AUDIT_OUTCOME_UNKNOWN'] }],
    ['a reason code is not a string', { reasonCodes: ['AUDIT_OUTCOME_UNKNOWN', null], attempts: [] }],
  ])('still rejects a completed run without attempts when %s', async (_label, shape) => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-MALFORMED', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-MALFORMED', status: 'COMPLETED', ...shape }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    await expect(finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })).rejects.toMatchObject({
      code: 'CORE_API_INVALID_RESPONSE',
    })
  })

  it('keeps a fail-closed attempt that carries no policy decision', async () => {
    // 정책 판정에 닿기 전 시스템 장애로 차단된 실행은 decision 을 생략한다.
    // contracts/audit/fixtures/execution-outcome.fail-closed.valid.json 이 그 모양이고,
    // execution-outcome.schema.json 은 systemOutcome=COMPLETED 일 때만 decision 을 요구한다.
    // 여기서 거부하면 Core·AI 장애로 막힌 실행이 화면에서 통째로 사라진다.
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-FAILCLOSED', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-FAILCLOSED',
        status: 'FAILED',
        reasonCodes: ['CONTEXT_SERVICE_UNAVAILABLE'],
        attempts: [{
          requestId: '00000000-0000-4000-8000-00000000f001',
          requestedTool: 'CREDIT_SCORE_READ',
          systemOutcome: 'ERROR',
          reasonCodes: ['CONTEXT_SERVICE_UNAVAILABLE'],
          downstreamReached: false,
          responseReleased: false,
          success: false,
          errorLocation: 'CORE',
        }],
      }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const execution = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(execution.attempts).toHaveLength(1)
    expect(execution.attempts[0].systemOutcome).toBe('ERROR')
    expect(execution.attempts[0].decision).toBeUndefined()
    expect(execution.attempts[0].reasonCodes).toContain('CONTEXT_SERVICE_UNAVAILABLE')
  })

  it('rejects a BLOCK attempt that claims a system error', async () => {
    // BLOCK + ERROR 는 execution-outcome.schema.json 이 표현할 수 없는 상태다. ERROR 절은
    // success 를 필수로 요구하고 BLOCK 절은 success 의 존재 자체를 금지한다. 서버가 이 조합을
    // 보냈다면 계약을 어긴 것이고, 조용히 받으면 mapAgentExecution 이 차단과 오류로 이중 계수한다.
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-BLOCKERROR', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-BLOCKERROR',
        status: 'FAILED',
        attempts: [{
          requestId: '00000000-0000-4000-8000-00000000f003',
          requestedTool: 'CREDIT_SCORE_READ',
          decision: 'BLOCK',
          systemOutcome: 'ERROR',
          reasonCodes: ['POLICY_ENGINE_UNAVAILABLE'],
        }],
      }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    await expect(finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })).rejects.toMatchObject({
      code: 'CORE_API_INVALID_RESPONSE',
    })
  })

  it('still rejects a completed attempt that omits its policy decision', async () => {
    // 부재를 허용하는 것은 ERROR 뿐이다. COMPLETED 는 판정이 끝났다는 뜻이므로
    // decision 이 없으면 계약 위반이다.
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-NODECISION', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-NODECISION',
        status: 'COMPLETED',
        attempts: [{
          requestId: '00000000-0000-4000-8000-00000000f002',
          requestedTool: 'CREDIT_SCORE_READ',
          systemOutcome: 'COMPLETED',
        }],
      }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    await expect(finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })).rejects.toMatchObject({
      code: 'CORE_API_INVALID_RESPONSE',
    })
  })

  it('keeps unavailable reachability facts unknown', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-UNKNOWN', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({
        agentRunId: 'RUN-UNKNOWN',
        status: 'COMPLETED',
        attempts: [{
          requestId: 'REQ-UNKNOWN',
          decision: 'ALLOW',
          systemOutcome: 'COMPLETED',
        }],
      }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.attempts[0].downstreamReached).toBeNull()
    expect(result.attempts[0].responseReleased).toBeNull()
  })

  it('never derives unavailable backend risk fields as safe values', () => {
    const mapped = mapAuditEvent({ auditEventId: 'AUD-1', status: 'PROCESSING' })

    expect(mapped.severity).toBe('UNKNOWN')
    expect(mapped.behaviorRiskLevel).toBe('UNKNOWN')
    expect(mapped.riskFlagged).toBeNull()
    expect(mapped.promptInjectionDetected).toBeNull()
    expect(mapped.systemOutcome).toBeNull()
  })

  it('never derives a system outcome for an outcome-unknown audit', () => {
    const mapped = mapAuditEvent({
      auditEventId: 'AUD-2',
      status: 'OUTCOME_UNKNOWN',
      outcomeUnknownDetectedAt: '2026-10-05T12:01:05Z',
    })

    expect(mapped.auditStatus).toBe('OUTCOME_UNKNOWN')
    expect(mapped.systemOutcome).toBeNull()
    expect(mapped.outcomeUnknownDetectedAt).toBe('2026-10-05T12:01:05Z')
  })

  it('uses a dedicated typed error for adapter failures', () => {
    expect(new FinboundApiError('failed')).toBeInstanceOf(Error)
  })
})

describe('approval flow', () => {
  const approvalAttempt = {
    requestId: 'REQ-APPROVAL',
    requestedTool: 'CREDIT_SCORE_READ',
    targetConsumerId: 'CUST-1001',
    requestedData: ['CREDIT_SCORE'],
    decision: 'APPROVAL',
    systemOutcome: 'COMPLETED',
    reasonCodes: ['BEHAVIOR_ANOMALY'],
    downstreamReached: false,
    responseReleased: false,
  }

  function runReturning(execution) {
    return vi.fn()
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-A', status: 'RUNNING' }))
      .mockResolvedValueOnce(jsonResponse({
        agentEffectivePermission: { allowedTools: [], allowedData: [] },
        withheldTools: [],
      }))
      .mockResolvedValueOnce(jsonResponse({ agentRunId: 'RUN-A', status: 'COMPLETED', ...execution }))
  }

  it('offers a rerun for an approved request that is still valid', async () => {
    const fetchImpl = runReturning({
      reasonCodes: ['BEHAVIOR_ANOMALY'],
      attempts: [approvalAttempt],
      approvals: [{
        approvalRequestId: 'APR-1',
        requestId: 'REQ-APPROVAL',
        status: 'APPROVED',
        validUntil: '2999-01-01T00:00:00Z',
      }],
    })
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.title).toBe('승인된 조회가 있습니다')
    expect(result.rerunApproval).toMatchObject({ approvalRequestId: 'APR-1', rerunnable: true })
    expect(result.attempts[0].description).not.toContain('아직 제공되지 않습니다')
  })

  it('does not offer a rerun for an approval whose validity has passed', async () => {
    const fetchImpl = runReturning({
      reasonCodes: ['BEHAVIOR_ANOMALY'],
      attempts: [approvalAttempt],
      approvals: [{ approvalRequestId: 'APR-1', status: 'APPROVED', validUntil: '2000-01-01T00:00:00Z' }],
    })
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.rerunApproval).toBeNull()
    // 만료 배치가 돌기 전이라 Core 사유가 없어도 완료로 보이지 않는다.
    expect(result.title).toBe('승인 기한이 지난 조회가 있습니다')
  })

  it.each([
    ['AUDIT_APPROVAL_REJECTED', 'REJECTED', '승인되지 않은 조회가 있습니다'],
    ['AUDIT_APPROVAL_EXPIRED', 'EXPIRED', '승인 기한이 지난 조회가 있습니다'],
  ])('does not show %s as a completed run', async (reason, status, title) => {
    const fetchImpl = runReturning({
      reasonCodes: [reason, 'BEHAVIOR_ANOMALY'],
      attempts: [approvalAttempt],
      approvals: [{ approvalRequestId: 'APR-1', requestId: 'REQ-APPROVAL', status }],
    })
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.title).toBe(title)
    expect(result.approvals).toEqual([
      { approvalRequestId: 'APR-1', requestId: 'REQ-APPROVAL', status, validUntil: null, rerunnable: false },
    ])
  })

  it('reruns with the approval id and shows the attempt that used it', async () => {
    const fetchImpl = runReturning({
      reasonCodes: [],
      attempts: [{
        ...approvalAttempt,
        decision: 'ALLOW',
        reasonCodes: [],
        downstreamReached: true,
        responseReleased: true,
        approvalRequestId: 'APR-1',
      }],
      approvals: [],
    })
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN', approvalRequestId: 'APR-1' })

    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({
      employeeId: 'EMP-101',
      consumerId: 'CUST-1001',
      taskType: 'LOAN_REVIEW',
      inputText: '현재 고객의 신규 대출 심사자료 확인',
      approvalRequestId: 'APR-1',
    })
    expect(result.attempts[0].approvalRequestId).toBe('APR-1')
    expect(result.title).toBe('AI 업무 처리가 완료되었습니다')
  })

  it('surfaces a refused rerun with its reason code', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(jsonResponse({
      reasonCode: 'APPROVAL_NOT_APPLICABLE',
      detail: '이 실행에 쓸 수 없는 승인입니다.',
    }, 409))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    await expect(finboundApi.executeAgentTask({ workId: 'NEW_LOAN', approvalRequestId: 'APR-OLD' }))
      .rejects.toMatchObject({ code: 'APPROVAL_NOT_APPLICABLE', status: 409 })
  })

  it('reads the role and lists, approves and rejects approval requests', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ role: 'APPROVER', employeeId: 'EMP-201' }))
      .mockResolvedValueOnce(jsonResponse({ items: [{ approvalRequestId: 'APR-1', status: 'PENDING' }] }))
      .mockResolvedValueOnce(jsonResponse({ approvalRequestId: 'APR-1', status: 'APPROVED' }))
      .mockResolvedValueOnce(jsonResponse({ approvalRequestId: 'APR-2', status: 'REJECTED' }))
    configureFinboundApi({ mode: 'real', baseUrl: 'http://core', credential: 'approver', fetchImpl })

    expect(await finboundApi.getMe()).toEqual({ role: 'APPROVER', employeeId: 'EMP-201' })
    expect((await finboundApi.listApprovals()).items).toHaveLength(1)
    await finboundApi.approve('APR-1', 'CONFIRMED_BUSINESS_NEED')
    await finboundApi.reject('APR-2')

    expect(fetchImpl.mock.calls[0][0]).toBe('http://core/api/v1/me')
    expect(fetchImpl.mock.calls[1][0]).toBe('http://core/api/v1/approval-requests?status=PENDING')
    expect(fetchImpl.mock.calls[2][0]).toBe('http://core/api/v1/approval-requests/APR-1/approve')
    expect(fetchImpl.mock.calls[2][1].method).toBe('POST')
    expect(JSON.parse(fetchImpl.mock.calls[2][1].body)).toEqual({ reason: 'CONFIRMED_BUSINESS_NEED' })
    expect(fetchImpl.mock.calls[3][0]).toBe('http://core/api/v1/approval-requests/APR-2/reject')
    expect(JSON.parse(fetchImpl.mock.calls[3][1].body)).toEqual({})
  })

  it('does not show the original run as completed after a rerun used its approval', async () => {
    const fetchImpl = runReturning({
      reasonCodes: ['BEHAVIOR_ANOMALY'],
      attempts: [approvalAttempt],
      approvals: [{ approvalRequestId: 'APR-1', status: 'CONSUMED', validUntil: '2999-01-01T00:00:00Z' }],
    })
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.title).toBe('승인된 조회는 다시 실행에서 진행했습니다')
    expect(result.rerunApproval).toBeNull()
  })

  it('keeps a failure ahead of an approved request and still exposes the rerun', async () => {
    const fetchImpl = runReturning({
      reasonCodes: ['BEHAVIOR_ANOMALY'],
      attempts: [approvalAttempt, { ...approvalAttempt, requestId: 'REQ-ERR', decision: undefined, systemOutcome: 'ERROR' }],
      approvals: [{ approvalRequestId: 'APR-1', status: 'APPROVED', validUntil: '2999-01-01T00:00:00Z' }],
    })
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

    expect(result.title).toBe('AI 업무 처리 중 오류가 발생했습니다')
    expect(result.rerunApproval?.approvalRequestId).toBe('APR-1')
  })

  it('picks the first approval still valid at the current time', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-07T12:00:00Z'))
    try {
      const fetchImpl = runReturning({
        reasonCodes: ['BEHAVIOR_ANOMALY'],
        attempts: [approvalAttempt],
        approvals: [
          // 기한이 지금과 같으면 지난 것이다. Core도 valid_until > now일 때만 받는다.
          { approvalRequestId: 'APR-EDGE', status: 'APPROVED', validUntil: '2026-10-07T12:00:00Z' },
          { approvalRequestId: 'APR-NEXT', status: 'APPROVED', validUntil: '2026-10-07T12:00:01Z' },
          { approvalRequestId: 'APR-LATER', status: 'APPROVED', validUntil: '2026-10-07T13:00:00Z' },
        ],
      })
      configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl, sleepImpl: async () => {} })

      const result = await finboundApi.executeAgentTask({ workId: 'NEW_LOAN' })

      expect(result.approvals.map((approval) => approval.rerunnable)).toEqual([false, true, true])
      expect(result.rerunApproval.approvalRequestId).toBe('APR-NEXT')
    } finally {
      vi.useRealTimers()
    }
  })

  it.each([
    [403, 'APPROVAL_SELF_DECISION'],
    [409, 'APPROVAL_NOT_PENDING'],
  ])('surfaces a %s %s decision refusal with its reason code', async (status, reasonCode) => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(jsonResponse({ reasonCode, detail: 'refused' }, status))
    configureFinboundApi({ mode: 'real', credential: 'approver', fetchImpl })

    await expect(finboundApi.approve('APR-1')).rejects.toMatchObject({ code: reasonCode, status })
  })

  it('keeps mock mode on the operator screens', async () => {
    expect(await finboundApi.getMe()).toEqual({ role: 'OPERATOR', employeeId: 'EMP-101' })
    await expect(finboundApi.approve('APR-1')).rejects.toMatchObject({ code: 'APPROVAL_REQUIRES_CORE_API' })
  })
})

describe('notification inbox API', () => {
  it('reads the unread count and the inbox and marks an item read', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ unread: 3 }))
      .mockResolvedValueOnce(jsonResponse({ items: [{ notificationId: 1 }] }))
      .mockResolvedValueOnce({ ok: true, status: 204, text: async () => '' })
    configureFinboundApi({ mode: 'real', baseUrl: 'http://core', credential: 'operator', fetchImpl })

    expect(await finboundApi.getUnreadNotificationCount()).toBe(3)
    expect((await finboundApi.listNotifications()).items).toHaveLength(1)
    await finboundApi.markNotificationRead(1)

    expect(fetchImpl.mock.calls[0][0]).toBe('http://core/api/v1/notifications/unread-count')
    expect(fetchImpl.mock.calls[1][0]).toBe('http://core/api/v1/notifications?unreadOnly=false')
    expect(fetchImpl.mock.calls[2][0]).toBe('http://core/api/v1/notifications/1/read')
    expect(fetchImpl.mock.calls[2][1].method).toBe('POST')
  })

  it('treats a malformed count as zero', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(jsonResponse({ unread: 'many' }))
    configureFinboundApi({ mode: 'real', credential: 'operator', fetchImpl })

    expect(await finboundApi.getUnreadNotificationCount()).toBe(0)
  })
})
