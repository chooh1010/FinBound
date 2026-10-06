<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { finboundApi } from '../services/finboundApi'

const props = defineProps({
  // 세션이 바뀔 때마다 달라지는 값. 바뀌면 이전 세션의 늦은 응답을 버린다.
  sessionKey: { type: Number, required: true },
})

const REFRESH_MS = 30_000
const KIND_LABELS = {
  APPROVAL_REQUESTED: '새 승인 요청',
  APPROVAL_APPROVED: '승인됨 · 다시 실행할 수 있습니다',
  APPROVAL_REJECTED: '승인되지 않음',
  APPROVAL_EXPIRED: '승인 기한 지남',
}

const unread = ref(0)
const open = ref(false)
const items = ref([])
const loadError = ref('')
const pendingReads = ref(new Set())
let timer = null
// 응답마다 순번을 붙여 가장 최근 요청의 답만 받는다. 세션이 바뀌거나 읽음 처리 뒤에 늦게 온 답이 새 상태를 덮지 않게.
let countSeq = 0
let listSeq = 0

async function refreshCount() {
  const mine = ++countSeq
  try {
    const count = await finboundApi.getUnreadNotificationCount()
    if (mine === countSeq) unread.value = count
  } catch {
    // 숫자가 잠깐 오래돼도 업무를 막지 않는다. 다음 주기에 다시 읽는다.
  }
}

// 안 읽은 알림만 받는다. 전체 최신 50건을 받으면 오래된 안 읽은 알림이 그 밖으로 밀려 영영 읽음 처리할 수 없다.
async function refreshList() {
  const mine = ++listSeq
  loadError.value = ''
  try {
    const list = await finboundApi.listNotifications({ unreadOnly: true })
    if (mine === listSeq) items.value = list.items
  } catch {
    if (mine === listSeq) loadError.value = '알림을 불러오지 못했습니다.'
  }
}

async function toggle() {
  open.value = !open.value
  if (!open.value) return
  await Promise.all([refreshList(), refreshCount()])
}

async function markRead(item) {
  const id = item.notificationId
  if (pendingReads.value.has(id)) return
  pendingReads.value = new Set([...pendingReads.value, id])
  // 이 시점 이전에 보낸 조회 답은 읽기 전 상태다. 버린다.
  listSeq += 1
  countSeq += 1
  try {
    await finboundApi.markNotificationRead(id)
    loadError.value = ''
    items.value = items.value.filter((each) => each.notificationId !== id)
  } catch {
    loadError.value = '읽음으로 표시하지 못했습니다.'
  } finally {
    const remaining = new Set(pendingReads.value)
    remaining.delete(id)
    pendingReads.value = remaining
    // 숫자는 서버에서 다시 읽는다. 204만으로는 이 요청이 상태를 바꿨는지 알 수 없다.
    await refreshCount()
  }
}

function restart() {
  countSeq += 1
  listSeq += 1
  unread.value = 0
  items.value = []
  open.value = false
  loadError.value = ''
  refreshCount()
}

watch(() => props.sessionKey, restart)

onMounted(() => {
  refreshCount()
  timer = setInterval(refreshCount, REFRESH_MS)
})

onBeforeUnmount(() => {
  countSeq += 1
  listSeq += 1
  clearInterval(timer)
})

const kindLabel = (kind) => KIND_LABELS[kind] ?? kind
const timeLabel = (value) => (value ? new Date(value).toLocaleString('ko-KR') : '')
</script>

<template>
  <div class="notification-bell">
    <button class="notification-toggle" type="button" :aria-expanded="open" aria-controls="notification-panel" @click="toggle">
      알림<span v-if="unread > 0" class="notification-count" aria-hidden="true">{{ unread }}</span>
    </button>
    <span class="sr-only" aria-live="polite">안 읽은 알림 {{ unread }}건</span>
    <div v-if="open" id="notification-panel" class="notification-panel" role="region" aria-label="안 읽은 알림">
      <p v-if="loadError" class="notification-error" role="alert">{{ loadError }}</p>
      <p v-if="!loadError && items.length === 0" class="notification-empty">안 읽은 알림이 없습니다.</p>
      <ul>
        <li v-for="item in items" :key="item.notificationId" :data-notification="item.notificationId" class="unread">
          <div>
            <strong>{{ kindLabel(item.kind) }}</strong>
            <small>{{ item.approvalRequestId }} · {{ timeLabel(item.createdAt) }}</small>
          </div>
          <button type="button" class="notification-read" :disabled="pendingReads.has(item.notificationId)" @click="markRead(item)">읽음</button>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.notification-bell { position: relative; }
.notification-toggle { border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; background: var(--surface); font-weight: 700; cursor: pointer; }
.notification-count { margin-left: 6px; padding: 1px 7px; border-radius: 10px; background: #d4380d; color: #fff; font-size: 12px; }
.notification-panel { position: absolute; right: 0; top: calc(100% + 6px); z-index: 20; width: min(360px, 90vw); max-height: 60vh; overflow: auto; padding: 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); box-shadow: 0 8px 24px rgba(37, 57, 88, .12); }
.notification-panel ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.notification-panel li { display: flex; justify-content: space-between; gap: 8px; align-items: center; padding: 8px; border-radius: 8px; }
.notification-panel li.unread { background: #eef5ff; }
.notification-panel li div { display: grid; gap: 2px; }
.notification-panel small { color: #607084; word-break: break-all; }
.notification-empty, .notification-error { margin: 0; color: #607084; }
.notification-error { color: #a8071a; }
.notification-read { border: 1px solid var(--line); border-radius: 6px; background: transparent; padding: 4px 8px; cursor: pointer; }
.notification-read:disabled { opacity: .5; cursor: wait; }
</style>
