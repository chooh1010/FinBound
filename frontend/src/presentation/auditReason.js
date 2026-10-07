const reasonDescriptions = {
  CASE_SCOPE_VIOLATION: '현재 심사 건과 관련 없는 고객 자료가 포함되어 조회 전에 차단했습니다.',
  MANDATE_SCOPE_VIOLATION: '현재 고객 동의 범위에 포함되지 않은 자료라 조회 전에 차단했습니다.',
  PROMPT_INJECTION: 'AI 입력에서 업무 지시를 바꾸려는 위험 신호를 확인해 실행 전에 차단했습니다.',
  BEHAVIOR_ANOMALY: '평소 업무 흐름과 다른 AI 행동을 확인해 실행 전에 차단했습니다.',
  DOWNSTREAM_TIMEOUT: '금융시스템 응답이 지연되어 결과를 직원에게 제공하지 못했습니다.',
  DOWNSTREAM_ERROR: '금융시스템 처리 중 오류가 발생해 결과를 직원에게 제공하지 못했습니다.',
  // 응답 검사(docs/04 §19) 사유. 조회는 완료됐고, 결과를 가리거나 제공하지 않은 사유만 다르다.
  RRN_MASKED: '응답에서 주민등록번호로 보이는 부분을 가리고 제공했습니다.',
  ACCOUNT_NUMBER_MASKED: '응답에서 계좌번호로 보이는 부분을 가리고 제공했습니다.',
  PHONE_NUMBER_MASKED: '응답에서 전화번호로 보이는 부분을 가리고 제공했습니다.',
  OTHER_CUSTOMER_DATA_IN_RESPONSE: '응답에 다른 고객의 정보가 포함되어 결과를 제공하지 않았습니다.',
  RESPONSE_SCAN_UNAVAILABLE: '응답 내용 검사를 완료하지 못해 결과를 제공하지 않았습니다.',
  RESPONSE_SCAN_DISABLED: '응답 내용 검사 기능이 꺼져 있어 요청을 실행하지 않았습니다.',
  RESPONSE_TOO_LARGE: '응답 내용이 검사할 수 있는 크기를 넘어 결과를 제공하지 않았습니다.',
  MASKING_FAILED: '응답 내용을 가리는 처리에 실패해 결과를 제공하지 않았습니다.',
}

// 같은 사유라도 판정에 따라 일어난 일이 다르다. APPROVAL은 차단이 아니라 실행 보류다.
const approvalDescriptions = {
  BEHAVIOR_ANOMALY: '평소 업무 흐름과 다른 AI 행동이 확인되어 실행하지 않고 담당자 확인을 기다립니다.',
}

export function describeAuditReason(event) {
  const reasonCode = event?.reasonCodes?.[0]
  if (event?.decision === 'APPROVAL') {
    return approvalDescriptions[reasonCode] ?? '담당자 확인이 필요해 실행하지 않았습니다.'
  }
  if (reasonCode && reasonDescriptions[reasonCode]) return reasonDescriptions[reasonCode]
  if (reasonCode) return '처리 사유 설명이 제공되지 않았습니다.'
  if (event?.auditStatus === 'ERROR' || event?.decision === 'BLOCK') return '처리 사유를 확인할 수 없습니다.'
  if (event?.decision === 'ALLOW' && event?.auditStatus === 'COMPLETED') {
    return '요청한 업무 범위 안에서 정상 처리했습니다.'
  }
  return '처리 상태와 사유를 확인할 수 없습니다.'
}
