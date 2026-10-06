<script setup>
import { onMounted, ref } from 'vue'

import { finboundApi } from '../services/finboundApi'

// Core가 받는 사유 목록(docs/04 §15.1). 자유 메모는 받지 않는다 — 감사 기록은 고칠 수 없어 붙여 넣은 민감정보가 남는다.
const DECISION_REASONS = [
  { value: '', label: '사유 선택 안 함' },
  { value: 'CONFIRMED_BUSINESS_NEED', label: '업무상 필요 확인' },
  { value: 'CUSTOMER_VERIFIED', label: '고객 확인 완료' },
  { value: 'SUSPICIOUS_ACTIVITY', label: '의심 활동' },
  { value: 'OUTSIDE_TASK_SCOPE', label: '업무 범위 밖' },
  { value: 'OTHER', label: '기타' },
]
const TOOL_LABELS = {
  CREDIT_SCORE_READ: '신용정보 확인',
  INCOME_READ: '소득자료 확인',
  DEBT_READ: '부채자료 확인',
}
const DECISION_ERRORS = {
  APPROVAL_SELF_DECISION: '본인이 요청한 건은 승인하거나 거절할 수 없습니다.',
  APPROVAL_NOT_PENDING: '이미 처리됐거나 처리 기한이 지난 요청입니다. 목록을 새로 불러왔습니다.',
}

const approvals = ref([])
const reasons = ref({})
const loading = ref(false)
// 건별로 잠근다. 하나의 값으로 두면 두 건을 이어 누를 때 앞 건의 잠금이 풀린다.
const busyIds = ref(new Set())
// 처리한 건. 처리 중에 시작된 새로고침이 늦게 도착해도 목록에 되살리지 않는다.
const decidedIds = new Set()
const loadError = ref('')
const notice = ref('')
const noticeIsError = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const items = (await finboundApi.listApprovals()).items
    approvals.value = items.filter((item) => !decidedIds.has(item.approvalRequestId))
  } catch {
    loadError.value = '승인 요청을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
  } finally {
    loading.value = false
  }
}

async function decide(approval, decision) {
  const id = approval.approvalRequestId
  if (busyIds.value.has(id)) return
  busyIds.value = new Set([...busyIds.value, id])
  notice.value = ''
  noticeIsError.value = false
  const reason = reasons.value[approval.approvalRequestId] || undefined
  try {
    if (decision === 'approve') {
      await finboundApi.approve(approval.approvalRequestId, reason)
      notice.value = `${approval.approvalRequestId}을 승인했습니다. 요청한 직원이 사용 기한 안에 같은 업무를 다시 실행하면 이 승인을 한 번 쓸 수 있습니다.`
    } else {
      await finboundApi.reject(approval.approvalRequestId, reason)
      notice.value = `${approval.approvalRequestId}을 거절했습니다.`
    }
    decidedIds.add(id)
    approvals.value = approvals.value.filter((item) => item.approvalRequestId !== id)
  } catch (error) {
    noticeIsError.value = true
    notice.value = DECISION_ERRORS[error?.code] ?? '처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    if (error?.code === 'APPROVAL_NOT_PENDING') await load()
  } finally {
    const remaining = new Set(busyIds.value)
    remaining.delete(id)
    busyIds.value = remaining
  }
}

const toolLabel = (tool) => TOOL_LABELS[tool] ?? tool
const timeLabel = (value) => (value ? new Date(value).toLocaleString('ko-KR') : '미제공')
</script>

<template>
  <section class="panel approvals-panel" aria-labelledby="approvals-heading">
    <div class="approvals-heading">
      <div>
        <p class="section-kicker">사람의 확인</p>
        <h2 id="approvals-heading">승인을 기다리는 조회</h2>
        <p>정책이 행동 위험 때문에 멈춘 조회입니다. 승인하면 요청한 직원이 사용 기한 안에 같은 업무를 다시 실행할 때 이 승인을 한 번 쓸 수 있습니다.</p>
      </div>
      <button class="session-end" type="button" :disabled="loading" @click="load">새로 불러오기</button>
    </div>

    <p v-if="notice" :class="['approvals-notice', { error: noticeIsError }]" role="status">{{ notice }}</p>
    <p v-if="loadError" class="approvals-notice error" role="alert">{{ loadError }}</p>
    <p v-else-if="!loading && approvals.length === 0" class="approvals-empty">승인을 기다리는 조회가 없습니다.</p>

    <ul class="approval-list">
      <li v-for="approval in approvals" :key="approval.approvalRequestId" class="approval-item" :data-approval="approval.approvalRequestId">
        <dl>
          <div><dt>요청</dt><dd>{{ approval.approvalRequestId }}</dd></div>
          <div><dt>요청 직원</dt><dd>{{ approval.requesterEmployeeId ?? '미제공' }}</dd></div>
          <div><dt>고객</dt><dd>{{ approval.targetConsumerId ?? '미제공' }}</dd></div>
          <div><dt>조회</dt><dd>{{ toolLabel(approval.requestedTool) }} · {{ (approval.requestedData ?? []).join(' · ') || '자료 미제공' }}</dd></div>
          <div><dt>사유</dt><dd>{{ (approval.reasonCodes ?? []).join(' · ') || '미제공' }}</dd></div>
          <div><dt>처리 기한</dt><dd>{{ timeLabel(approval.expiresAt) }}</dd></div>
        </dl>
        <div class="approval-actions">
          <label :for="`reason-${approval.approvalRequestId}`">판단 사유</label>
          <select :id="`reason-${approval.approvalRequestId}`" v-model="reasons[approval.approvalRequestId]">
            <option v-for="option in DECISION_REASONS" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
          <button class="primary-button" type="button" data-action="approve" :disabled="busyIds.has(approval.approvalRequestId)" @click="decide(approval, 'approve')">승인</button>
          <button class="session-end" type="button" data-action="reject" :disabled="busyIds.has(approval.approvalRequestId)" @click="decide(approval, 'reject')">거절</button>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.approvals-panel { padding: 24px; display: grid; gap: 16px; }
.approvals-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.approvals-heading h2 { margin: 4px 0; }
.approvals-notice { margin: 0; padding: 10px 12px; border-radius: 8px; background: #eef5ff; }
.approvals-notice.error { background: #fff1f0; color: #a8071a; }
.approvals-empty { margin: 0; color: #607084; }
.approval-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; }
.approval-item { border: 1px solid var(--line); border-radius: 10px; padding: 16px; display: grid; gap: 12px; }
.approval-item dl { margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px 16px; }
.approval-item dt { font-size: 12px; color: #607084; }
.approval-item dd { margin: 2px 0 0; word-break: break-all; }
.approval-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.approval-actions select { padding: 8px; border-radius: 8px; border: 1px solid var(--line); }
</style>
