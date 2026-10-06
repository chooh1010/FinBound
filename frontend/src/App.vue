<script setup>
import { computed, onMounted, ref } from 'vue'

import brandLogoDark from './assets/finbound-logo-dark.png'
import brandWordmark from './assets/finbound-wordmark.png'
import AgentRunView from './views/AgentRunView.vue'
import ApprovalsView from './views/ApprovalsView.vue'
import DashboardView from './views/DashboardView.vue'
import NotificationBell from './components/NotificationBell.vue'
import { finboundApi } from './services/finboundApi'

const allScreens = [
  { id: 'run', labelParts: ['AI 업무 지원'], kicker: '01', subtitle: 'AI Agent가 안전하게 업무를 지원하도록 권한을 최소화합니다.' },
  { id: 'dashboard', labelParts: ['AI 업무', '안전 현황'], kicker: '02', subtitle: 'AI 업무 처리 기록과 보호 설정의 작동 결과를 한눈에 확인합니다.' },
  { id: 'approvals', labelParts: ['승인 요청'], kicker: '03', subtitle: '정책이 사람의 확인을 요구한 조회를 승인하거나 거절합니다.' },
]
const activeScreen = ref('run')
const realMode = finboundApi.isRealMode()
const credential = ref('')
const sessionReady = ref(!realMode || finboundApi.hasCredential())
// 메뉴를 고르는 데만 쓴다. 권한은 Core가 Endpoint마다 판단한다(docs/04 §15.1).
const role = ref(null)
const roleError = ref(false)
// 세션마다 번호를 올린다. 로그아웃 뒤 늦게 도착한 역할 응답이 새 세션의 메뉴를 덮지 않게.
let sessionGeneration = 0
// 알림함에 넘기는 같은 값의 반응형 사본. 바뀌면 알림함이 이전 세션의 늦은 응답을 버린다.
const sessionKey = ref(0)
// 알림은 직원 신원이 있는 역할에만 있다. VIEWER에게는 보이지 않는다.
const showNotifications = computed(() => sessionReady.value && ['OPERATOR', 'APPROVER'].includes(role.value))
const screens = computed(() => allScreens.filter((screen) => (
  role.value === 'APPROVER' ? screen.id !== 'run' : screen.id !== 'approvals'
)))
const activeComponent = computed(() => ({
  run: AgentRunView,
  dashboard: DashboardView,
  approvals: ApprovalsView,
})[activeScreen.value])
const activeScreenConfig = computed(() => allScreens.find((screen) => screen.id === activeScreen.value))
const screenLabel = (screen) => screen.labelParts.join(' ')

async function loadRole() {
  const generation = sessionGeneration
  roleError.value = false
  let loaded = null
  try {
    loaded = (await finboundApi.getMe())?.role ?? null
  } catch {
    // 역할을 모르면 기존 메뉴를 보인다. 승인 메뉴는 승인자로 확인된 때만 연다. 다시 시도할 수 있게 알린다.
    if (generation === sessionGeneration) roleError.value = true
  }
  if (generation !== sessionGeneration) return
  role.value = loaded
  if (role.value === 'APPROVER') activeScreen.value = 'approvals'
}

onMounted(() => {
  if (sessionReady.value) loadRole()
})

async function startSession() {
  if (!credential.value.trim()) return
  // 앞선 시작이 역할 응답을 기다리는 중이어도 이 시작이 이긴다.
  sessionGeneration += 1
  sessionKey.value = sessionGeneration
  finboundApi.setCredential(credential.value.trim())
  credential.value = ''
  await loadRole()
  sessionReady.value = true
}

function endSession() {
  sessionGeneration += 1
  sessionKey.value = sessionGeneration
  finboundApi.clearCredential()
  sessionReady.value = false
  role.value = null
  roleError.value = false
  activeScreen.value = 'run'
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#main-content" aria-label="FinBound 홈">
        <span class="brand-mark" aria-hidden="true"><img :src="brandLogoDark" alt="" /></span>
        <span class="brand-wordmark-frame"><img class="brand-wordmark" :src="brandWordmark" alt="FinBound" /></span>
      </a>
      <nav aria-label="업무 메뉴">
        <button v-for="screen in screens" :key="screen.id" :data-screen="screen.id" :class="['nav-item', { active: activeScreen === screen.id }]" :aria-current="activeScreen === screen.id ? 'page' : undefined" type="button" @click="activeScreen = screen.id">
          <span class="nav-icon" aria-hidden="true">
            <svg v-if="screen.id === 'run'" viewBox="0 0 24 24"><rect x="5" y="3.5" width="14" height="17" rx="2" /><circle cx="12" cy="9.5" r="2.25" /><path d="M8.5 17.5v-.8a3.5 3.5 0 0 1 7 0v.8" /></svg>
            <svg v-else viewBox="0 0 24 24"><path d="M12 3.2c2.1 1.2 4.35 2.08 6.75 2.65v5.3c0 4.25-2.57 7.55-6.75 9.65-4.18-2.1-6.75-5.4-6.75-9.65v-5.3C7.65 5.28 9.9 4.4 12 3.2Z" /><path d="m8.8 12.1 2.05 2.05 4.45-4.7" /></svg>
          </span>
          <span class="nav-label">
            <small>{{ screen.kicker }}</small>
            <template v-for="(labelPart, index) in screen.labelParts" :key="labelPart">
              {{ index < screen.labelParts.length - 1 ? `${labelPart} ` : labelPart }}<br v-if="index < screen.labelParts.length - 1" class="compact-nav-break" />
            </template>
          </span>
        </button>
      </nav>
      <div class="system-state">
        <span class="system-shield" aria-hidden="true"><svg class="soft-shield-icon" viewBox="0 0 24 24"><path class="shield-fill" d="M12 2.7c2.35 1.45 4.75 2.35 7.2 2.9v5.15c0 4.75-2.8 8.4-7.2 10.55-4.4-2.15-7.2-5.8-7.2-10.55V5.6c2.45-.55 4.85-1.45 7.2-2.9Z" /><path class="shield-symbol" d="M12 8v8M8 12h8" /></svg></span>
        <div><strong>AI 업무 보호 적용 중</strong><small>권한 범위 내에서만<br />안전하게 보호됩니다.</small></div>
      </div>
    </aside>
    <main id="main-content">
      <header class="topbar">
        <div><h1>{{ screenLabel(activeScreenConfig) }}</h1><p class="page-subtitle">{{ activeScreenConfig?.subtitle }}</p></div>
        <div class="runtime-actions">
          <div class="environment-badge"><span></span> {{ realMode ? 'Core API 연결 모드' : 'Mock 검증 모드' }}</div>
          <NotificationBell v-if="showNotifications" :session-key="sessionKey" />
          <button v-if="realMode && sessionReady" class="session-end" type="button" @click="endSession">연결 종료</button>
        </div>
      </header>
      <section v-if="realMode && !sessionReady" class="panel credential-panel" aria-labelledby="credential-heading">
        <p class="section-kicker">보호된 Core API 연결</p>
        <h2 id="credential-heading">업무 세션 Credential을 입력해 주세요</h2>
        <p>Credential은 현재 브라우저 메모리에서만 사용하며 Web Storage나 빌드 파일에 저장하지 않습니다.</p>
        <form @submit.prevent="startSession">
          <label for="core-credential">Operator, Viewer 또는 Approver Credential</label>
          <div>
            <input id="core-credential" v-model="credential" type="password" autocomplete="off" spellcheck="false" required />
            <button class="primary-button" type="submit">Core API 연결</button>
          </div>
        </form>
      </section>
      <template v-else>
        <p v-if="roleError" class="role-warning" role="alert">
          역할을 확인하지 못해 기본 메뉴를 보이고 있습니다. 승인자라면 다시 확인해 주세요.
          <button class="session-end" type="button" @click="loadRole">역할 다시 확인</button>
        </p>
        <component :is="activeComponent" />
      </template>
    </main>
  </div>
</template>
