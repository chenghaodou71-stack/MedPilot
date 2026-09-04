<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElNotification } from 'element-plus'
import {
  Bell,
  BookOpenText,
  BriefcaseMedical,
  ChartNoAxesCombined,
  ChevronDown,
  Database,
  FileClock,
  ClipboardCheck,
  LogOut,
  Menu,
  MessageCircleMore,
  MonitorCog,
  PanelLeftClose,
  PanelLeftOpen,
  SearchCheck,
  Settings2,
  Stethoscope,
  TriangleAlert,
  UserRound,
  UsersRound,
  ShieldCheck,
  HeartPulse,
  X,
} from 'lucide-vue-next'
import { MANAGEMENT_ROLES, useAuthStore } from './stores/auth'
import {
  SETTINGS_KEY,
  clearHealthHistory,
  normalizePrivacySettings,
} from './lib/privacy'
import { resolveWorkspaceAppearance } from './lib/workspaceAppearance'
import client from './api/client'
import { collectUnseenDueFollowUps, normalizeDueFollowUps } from './lib/followUpNotifications'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const mobileNavOpen = ref(false)
const mobileMenuButton = ref(null)
const adminSidebarCollapsed = ref(false)
const workspaceDensity = ref('standard')
const workspaceTheme = ref(
  document.documentElement.dataset.theme === 'medical-dark' ? 'medical-dark' : 'medical-light',
)
const dueFollowUps = ref([])
const notificationsOpen = ref(false)
const notificationsLoading = ref(false)
const seenReminderIds = new Set()
let reminderPollTimer = null

const isLoginPage = computed(() => route.name === 'login')
const workspaceMode = computed(() => route.meta.workspace || 'patient')
const isAdminWorkspace = computed(() => workspaceMode.value === 'admin')
const isPatientWorkspace = computed(() => workspaceMode.value === 'patient')
const showGlobalEmergency = computed(() => isPatientWorkspace.value)

const allNavItems = [
  { page: 'dashboard', label: '数据看板', icon: ChartNoAxesCombined, admin: true },
  { page: 'consult', label: '智能问诊', icon: MessageCircleMore, admin: false },
  { page: 'records', label: '问诊记录', icon: FileClock, admin: false },
  { page: 'profile', label: '健康档案', icon: HeartPulse, admin: false },
  { page: 'health', label: '健康检索', icon: SearchCheck, admin: false },
  { page: 'knowledge', label: '医学知识库', icon: Database, admin: true },
  { page: 'clinical-reviews', permission: 'reviews', label: '医生复核', icon: ClipboardCheck, admin: true },
  { page: 'monitor', label: '智能体监控', icon: MonitorCog, admin: true },
  { page: 'users', label: '用户权限', icon: UsersRound, admin: true },
  { page: 'audit', label: '审计日志', icon: ShieldCheck, admin: true },
  { page: 'faq', label: '常见问题', icon: BookOpenText, admin: false },
  { page: 'settings', label: '系统设置', icon: Settings2, admin: false },
]

const navItems = computed(() => allNavItems.filter((item) => (
  !item.admin || auth.canAccess(MANAGEMENT_ROLES[item.permission || item.page])
)))
const patientNavItems = computed(() => allNavItems.filter((item) => (
  !item.admin && item.page !== 'settings'
)))
const adminNavItems = computed(() => allNavItems.filter((item) => (
  item.admin && auth.canAccess(MANAGEMENT_ROLES[item.permission || item.page])
)))
const brandTarget = computed(() => (isAdminWorkspace.value ? auth.homePath : '/consult'))

const displayName = computed(() => {
  if (!auth.username) return '用户'
  return auth.username === 'admin' ? '管理员' : auth.username
})

function closeMobileNav() {
  mobileNavOpen.value = false
}

function openMobileNav() {
  mobileNavOpen.value = true
}

function readStoredSettings() {
  try {
    return JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null') || {}
  } catch {
    return {}
  }
}

function syncWorkspaceSettings(event) {
  const appearance = event?.detail?.appearance || readStoredSettings()?.appearance || {}
  const density = appearance.density
  const theme = resolveWorkspaceAppearance(workspaceMode.value, appearance.theme).theme
  workspaceDensity.value = ['compact', 'standard', 'relaxed'].includes(density) ? density : 'standard'
  workspaceTheme.value = theme
  syncDocumentAppearance()
}

function syncDocumentAppearance() {
  const mode = workspaceMode.value
  const appearance = resolveWorkspaceAppearance(mode, workspaceTheme.value)
  workspaceTheme.value = appearance.theme
  document.documentElement.dataset.workspace = mode
  document.documentElement.dataset.theme = appearance.theme
  document.documentElement.classList.toggle('dark', appearance.dark)
  document.documentElement.style.colorScheme = appearance.dark ? 'dark' : 'light'
}

function focusMobileNavigation() {
  window.setTimeout(() => {
    const target = document.querySelector('#mobile-navigation .side-link.active, #mobile-navigation .side-link')
    target?.focus({ preventScroll: true })
  }, 0)
}

async function restoreMobileMenuFocus() {
  await nextTick()
  mobileMenuButton.value?.focus()
}

function handleViewportResize() {
  if (window.innerWidth > 900 && mobileNavOpen.value) closeMobileNav()
}

function reminderPayload(response) {
  return normalizeDueFollowUps(response)
}

function announceDueFollowUps(tasks) {
  const unseen = collectUnseenDueFollowUps(tasks, seenReminderIds)
  for (const task of unseen) {
    ElNotification({
      title: '复诊提醒已到期',
      message: task.title || '请查看健康档案中的复诊计划',
      type: 'warning',
      duration: 8000,
      position: 'top-right',
      onClick: () => router.push('/profile'),
    })
  }
}

async function loadDueFollowUps({ announce = true } = {}) {
  if (!isPatientWorkspace.value || !auth.username) {
    dueFollowUps.value = []
    return
  }
  notificationsLoading.value = true
  try {
    const response = await client.get('/profile/follow-ups/due')
    const tasks = reminderPayload(response.data)
    dueFollowUps.value = tasks
    if (announce) announceDueFollowUps(tasks)
  } catch {
    // Reminder polling is best-effort and must never interrupt an active consult.
  } finally {
    notificationsLoading.value = false
  }
}

function startReminderPolling() {
  if (reminderPollTimer) window.clearInterval(reminderPollTimer)
  loadDueFollowUps()
  reminderPollTimer = window.setInterval(() => loadDueFollowUps(), 60_000)
}

function stopReminderPolling() {
  if (reminderPollTimer) window.clearInterval(reminderPollTimer)
  reminderPollTimer = null
  dueFollowUps.value = []
  notificationsOpen.value = false
  seenReminderIds.clear()
}

function openReminder(task) {
  notificationsOpen.value = false
  router.push({ path: '/profile', query: { reminder: String(task.id) } })
}

function formatReminderTime(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

async function handleUserCommand(command) {
  if (command === 'settings') {
    router.push('/settings')
    return
  }
  if (command !== 'logout') return
  const username = auth.username

  try {
    await auth.logout()
  } catch {
    ElMessage.error('退出登录失败，当前会话仍然有效，请检查网络后重试。')
    return
  }

  if (normalizePrivacySettings(readStoredSettings()?.privacy).clearHistoryOnLogout) {
    clearHealthHistory(username)
  }
  router.push('/login')
}

function toggleAdminSidebar() {
  adminSidebarCollapsed.value = !adminSidebarCollapsed.value
}

watch(workspaceMode, syncDocumentAppearance, { immediate: true })
watch(
  [workspaceMode, () => auth.username],
  ([mode, username], [previousMode, previousUsername] = []) => {
    if (mode === 'patient' && username) {
      if (mode !== previousMode || username !== previousUsername) seenReminderIds.clear()
      startReminderPolling()
    } else {
      stopReminderPolling()
    }
  },
  { immediate: true },
)

onMounted(() => {
  syncWorkspaceSettings()
  window.addEventListener('medpilot-settings-changed', syncWorkspaceSettings)
  window.addEventListener('resize', handleViewportResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('medpilot-settings-changed', syncWorkspaceSettings)
  window.removeEventListener('resize', handleViewportResize)
  stopReminderPolling()
})
</script>

<template>
  <router-view v-if="isLoginPage" />

  <div
    v-else
    class="med-app"
    :class="[
      `density-${workspaceDensity}`,
      `theme-${workspaceTheme}`,
      `workspace-${workspaceMode}`,
      { 'admin-sidebar-collapsed': isAdminWorkspace && adminSidebarCollapsed },
    ]"
  >
    <header class="med-header">
      <div class="brand-group">
        <button
          ref="mobileMenuButton"
          class="mobile-menu"
          type="button"
          aria-label="打开功能导航"
          aria-controls="mobile-navigation"
          aria-haspopup="dialog"
          :aria-expanded="mobileNavOpen"
          @click="openMobileNav"
        >
          <Menu :size="20" :stroke-width="1.8" aria-hidden="true" />
        </button>
        <router-link :to="brandTarget" class="brand-link">
          <span class="brand-symbol"><BriefcaseMedical :size="23" :stroke-width="1.8" aria-hidden="true" /></span>
          <span class="brand-copy">
            <strong>MedPilot</strong>
            <small>医疗多智能体辅助分诊系统</small>
          </span>
        </router-link>
      </div>

      <nav v-if="isPatientWorkspace" class="patient-nav" aria-label="患者服务导航">
        <router-link
          v-for="item in patientNavItems"
          :key="item.page"
          :to="`/${item.page}`"
          class="patient-nav-link"
          active-class="active"
          :aria-label="item.label"
          :title="item.label"
        >
          <component :is="item.icon" :size="16" :stroke-width="1.8" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="header-actions">
        <a
          v-if="showGlobalEmergency"
          class="header-emergency"
          href="tel:120"
          aria-label="紧急情况拨打 120"
        >
          <TriangleAlert :size="16" :stroke-width="1.9" aria-hidden="true" />
          <span>急救 120</span>
        </a>
        <el-button v-if="auth.hasManagementAccess && isPatientWorkspace" text class="header-action" @click="router.push(auth.homePath)">
          <MonitorCog :size="17" :stroke-width="1.8" aria-hidden="true" />
          <span>管理控制台</span>
        </el-button>
        <el-button v-else-if="isAdminWorkspace" text class="header-action" @click="router.push('/consult')">
          <MessageCircleMore :size="17" :stroke-width="1.8" aria-hidden="true" />
          <span>患者服务</span>
        </el-button>
        <el-popover
          v-if="isPatientWorkspace"
          v-model:visible="notificationsOpen"
          placement="bottom-end"
          :width="348"
          trigger="click"
          popper-class="reminder-popover"
        >
          <template #reference>
            <el-badge :value="dueFollowUps.length" :hidden="!dueFollowUps.length" :max="9" class="header-notification-badge">
              <el-tooltip content="到期提醒" placement="bottom">
                <el-button text circle aria-label="到期提醒">
                  <Bell :size="17" :stroke-width="1.8" aria-hidden="true" />
                </el-button>
              </el-tooltip>
            </el-badge>
          </template>
          <section class="reminder-panel" aria-labelledby="reminder-panel-title">
            <header class="reminder-panel__header">
              <div>
                <span>FOLLOW-UP ALERTS</span>
                <strong id="reminder-panel-title">到期提醒</strong>
              </div>
              <el-button text size="small" @click="router.push('/profile'); notificationsOpen = false">健康档案</el-button>
            </header>
            <div v-if="notificationsLoading" class="reminder-panel__loading">正在同步提醒…</div>
            <div v-else-if="dueFollowUps.length" class="reminder-panel__list">
              <button
                v-for="task in dueFollowUps"
                :key="task.id"
                type="button"
                class="reminder-panel__item"
                @click="openReminder(task)"
              >
                <span class="reminder-panel__dot" />
                <span class="reminder-panel__copy">
                  <strong>{{ task.title || '复诊事项' }}</strong>
                  <small>到期时间 {{ formatReminderTime(task.dueAt) }}</small>
                </span>
                <ChevronDown :size="15" class="reminder-panel__arrow" aria-hidden="true" />
              </button>
            </div>
            <div v-else class="reminder-panel__empty">
              <Bell :size="22" aria-hidden="true" />
              <strong>目前没有到期提醒</strong>
              <span>新的复诊计划会在到期后出现在这里</span>
            </div>
          </section>
        </el-popover>
        <el-tooltip v-else content="通知" placement="bottom">
          <el-button text circle aria-label="通知" @click="ElMessage.info('当前没有新的系统通知')">
            <Bell :size="17" :stroke-width="1.8" aria-hidden="true" />
          </el-button>
        </el-tooltip>
        <el-dropdown trigger="click" @command="handleUserCommand">
          <button type="button" class="user-trigger">
            <el-avatar :size="32" class="user-avatar">
              {{ auth.username?.charAt(0).toUpperCase() || 'U' }}
            </el-avatar>
            <span>{{ displayName }}</span>
            <ChevronDown class="user-caret" :size="14" :stroke-width="1.8" aria-hidden="true" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                <UserRound :size="15" :stroke-width="1.8" aria-hidden="true" />{{ auth.role || 'USER' }}
              </el-dropdown-item>
              <el-dropdown-item command="settings">
                <Settings2 :size="15" :stroke-width="1.8" aria-hidden="true" />系统设置
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <LogOut :size="15" :stroke-width="1.8" aria-hidden="true" />退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="med-body">
      <aside v-if="isAdminWorkspace" class="med-sidebar admin-sidebar" aria-label="管理员功能导航">
        <div class="admin-sidebar-heading">
          <span v-if="!adminSidebarCollapsed">管理控制台</span>
          <el-tooltip :content="adminSidebarCollapsed ? '展开侧栏' : '收起侧栏'" placement="right">
            <button
              type="button"
              class="sidebar-collapse"
              :aria-label="adminSidebarCollapsed ? '展开管理员侧栏' : '收起管理员侧栏'"
              :aria-expanded="!adminSidebarCollapsed"
              @click="toggleAdminSidebar"
            >
              <PanelLeftOpen v-if="adminSidebarCollapsed" :size="18" :stroke-width="1.8" aria-hidden="true" />
              <PanelLeftClose v-else :size="18" :stroke-width="1.8" aria-hidden="true" />
            </button>
          </el-tooltip>
        </div>
        <nav class="side-nav" aria-label="主导航">
          <el-tooltip
            v-for="item in adminNavItems"
            :key="item.page"
            :content="item.label"
            :disabled="!adminSidebarCollapsed"
            placement="right"
          >
            <router-link
              :to="`/${item.page}`"
              class="side-link"
              active-class="active"
              :aria-label="adminSidebarCollapsed ? item.label : undefined"
            >
              <span class="side-icon-shell">
                <component :is="item.icon" :size="18" :stroke-width="1.8" aria-hidden="true" />
              </span>
              <span class="side-link-label">{{ item.label }}</span>
            </router-link>
          </el-tooltip>
        </nav>
      </aside>

      <main class="med-main">
        <router-view />
      </main>
    </div>

    <el-drawer
      v-model="mobileNavOpen"
      class="mobile-nav-drawer"
      direction="ltr"
      size="284px"
      title="功能导航"
      append-to-body
      lock-scroll
      close-on-click-modal
      close-on-press-escape
      @opened="focusMobileNavigation"
      @closed="restoreMobileMenuFocus"
    >
      <template #header="{ close, titleId, titleClass }">
        <div class="mobile-drawer-header">
          <span class="mobile-drawer-symbol"><Stethoscope :size="20" :stroke-width="1.8" aria-hidden="true" /></span>
          <div>
            <strong :id="titleId" :class="titleClass">功能导航</strong>
            <small>{{ isAdminWorkspace ? '管理控制台' : '患者服务' }}</small>
          </div>
          <el-button text circle aria-label="关闭功能导航" @click="close">
            <X :size="18" :stroke-width="1.8" aria-hidden="true" />
          </el-button>
        </div>
      </template>

      <div id="mobile-navigation" class="mobile-drawer-content">
        <nav class="side-nav" aria-label="移动端主导航">
          <router-link
            v-for="item in navItems"
            :key="item.page"
            :to="`/${item.page}`"
            class="side-link"
            active-class="active"
            @click="closeMobileNav"
          >
            <span class="side-icon-shell">
              <component :is="item.icon" :size="18" :stroke-width="1.8" aria-hidden="true" />
            </span>
            <span class="side-link-label">{{ item.label }}</span>
          </router-link>
        </nav>

        <div v-if="showGlobalEmergency" class="emergency-note">
          <div class="emergency-title">
            <TriangleAlert :size="17" :stroke-width="1.8" aria-hidden="true" />
            <strong>紧急情况请拨打 120</strong>
          </div>
          <p>本系统仅提供辅助建议，不能替代医生诊断与治疗。</p>
          <span class="emergency-number">120</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.med-app {
  min-height: 100vh;
  background: var(--surface-page);
  color: var(--text-primary);
}

.med-header {
  position: sticky;
  top: 0;
  z-index: 30;
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 0 28px;
  overflow: hidden;
  background: var(--surface-elevated);
  border-bottom: 1px solid var(--border-subtle);
  box-shadow: var(--shadow-sm);
}

.med-header::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  width: 26%;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, var(--primary), var(--success), transparent);
  opacity: 0.72;
  animation: shell-scan-line 7s ease-in-out infinite;
}

.brand-group,
.brand-link,
.header-actions,
.user-trigger,
.emergency-title {
  display: flex;
  align-items: center;
}

.brand-link {
  gap: 12px;
  color: var(--text-primary);
  text-decoration: none;
}

.brand-symbol {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--primary-solid), var(--primary), var(--success));
  background-size: 180% 180%;
  border-radius: 8px;
  box-shadow: 0 6px 16px var(--focus-ring);
  animation: shell-brand-shift 7s ease infinite;
}

.brand-copy strong,
.brand-copy small {
  display: block;
  letter-spacing: 0;
}

.brand-copy strong {
  font-size: 18px;
  line-height: 1.15;
}

.brand-copy small {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.3;
}

.header-actions {
  gap: 6px;
}

.header-action {
  color: var(--text-secondary);
}

.header-action :deep(.lucide) {
  margin-right: 5px;
}

:global(.el-dropdown-menu__item > .lucide) {
  margin-right: 7px;
}

.user-trigger {
  gap: 8px;
  min-height: 40px;
  margin-left: 4px;
  padding: 4px 8px;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.user-trigger:hover {
  background: var(--surface-muted);
}

.user-avatar {
  background: var(--primary);
  color: var(--text-inverse);
  font-size: 13px;
  font-weight: 700;
}

.user-caret {
  color: var(--text-muted);
}

.med-body {
  display: flex;
  min-height: calc(100vh - 72px);
}

.med-sidebar {
  position: sticky;
  top: 72px;
  width: 216px;
  height: calc(100vh - 72px);
  display: flex;
  flex: 0 0 216px;
  flex-direction: column;
  padding: 20px 14px 18px;
  overflow-y: auto;
  background: var(--surface-elevated);
  border-right: 1px solid var(--border-subtle);
}

.side-nav {
  display: grid;
  gap: 5px;
}

.side-link {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 42px;
  padding: 0 13px;
  border-radius: 7px;
  color: var(--text-secondary);
  font-size: 14px;
  text-decoration: none;
  transition: color 0.16s ease, background 0.16s ease;
}

.side-link::before {
  position: absolute;
  inset: 8px auto 8px 0;
  width: 2px;
  content: '';
  border-radius: 2px;
  background: linear-gradient(var(--primary), var(--success));
  opacity: 0;
  transform: scaleY(0.35);
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.side-link:hover {
  color: var(--primary);
  background: var(--primary-soft);
}

.side-link.active {
  color: var(--primary);
  background: var(--primary-soft);
  font-weight: 600;
}


.side-link.active::before {
  opacity: 1;
  transform: scaleY(1);
}

@keyframes shell-scan-line {
  0%,
  100% {
    transform: translateX(110%);
  }
  50% {
    transform: translateX(-290%);
  }
}

@keyframes shell-brand-shift {
  0%,
  100% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
}

.emergency-note {
  position: relative;
  margin-top: auto;
  padding: 14px 13px 42px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--danger) 36%, transparent);
  border-radius: 8px;
  background: var(--danger-soft);
}

.emergency-title {
  gap: 6px;
  color: var(--danger);
  font-size: 12px;
}

.emergency-note p {
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.7;
}

.emergency-number {
  position: absolute;
  right: 10px;
  bottom: 9px;
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 50%;
  background: var(--danger);
  color: var(--text-inverse);
  font-size: 12px;
  font-weight: 700;
}

.med-main {
  min-width: 0;
  flex: 1;
  padding: 26px 30px 44px;
  overflow: hidden;
}

@media (min-width: 901px) {
  .density-compact .med-main {
    padding: 18px 22px 32px;
  }

  .density-relaxed .med-main {
    padding: 34px 38px 52px;
  }
}

.mobile-menu {
  display: none;
}

.mobile-drawer-header,
.mobile-drawer-symbol {
  display: flex;
  align-items: center;
}

.mobile-drawer-header {
  width: 100%;
  gap: 10px;
}

.mobile-drawer-header > div {
  min-width: 0;
  flex: 1;
}

.mobile-drawer-header strong,
.mobile-drawer-header small {
  display: block;
  letter-spacing: 0;
}

.mobile-drawer-header strong {
  color: var(--text-primary);
  font-size: 15px;
}

.mobile-drawer-header small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
}

.mobile-drawer-symbol {
  width: 34px;
  height: 34px;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 7px;
  background: var(--primary);
  color: var(--text-inverse);
}

.mobile-drawer-content {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
}

:global(.mobile-nav-drawer) {
  max-width: 88vw;
  background: var(--surface-elevated);
}

:global(.mobile-nav-drawer .el-drawer__header) {
  margin: 0;
  padding: 16px;
  border-bottom: 1px solid var(--border-subtle);
}

:global(.mobile-nav-drawer .el-drawer__body) {
  padding: 16px 14px 18px;
}

@media (max-width: 900px) {
  .med-header {
    height: 64px;
    gap: 12px;
    padding: 0 16px;
  }

  .brand-group {
    min-width: 0;
  }

  .brand-symbol {
    width: 38px;
    height: 38px;
  }

  .mobile-menu {
    display: grid;
    width: 38px;
    height: 38px;
    margin-right: 8px;
    place-items: center;
    border: 0;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
  }

  .med-sidebar {
    display: none;
  }

  .med-body {
    min-height: calc(100vh - 64px);
  }

  .med-main {
    padding: 20px 18px 36px;
  }
}

@media (max-width: 640px) {
  .brand-copy small,
  .header-action span,
  .header-actions > .el-button:nth-child(2),
  .header-actions > .el-button:nth-child(3),
  .user-trigger > span:not(.el-avatar) {
    display: none;
  }

  .brand-copy strong {
    font-size: 16px;
    line-height: 1.2;
  }

  .header-actions {
    gap: 0;
  }

  .med-main {
    padding: 14px 12px 28px;
  }
}

@media (max-width: 420px) {
  .med-header {
    gap: 8px;
    padding: 0 10px;
  }

  .brand-link {
    gap: 9px;
  }

  .header-actions > .el-button {
    display: none;
  }

  .user-trigger {
    margin-left: 0;
    padding-right: 4px;
    padding-left: 4px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .med-header::after,
  .brand-symbol {
    animation: none;
  }
}

/* Shared medical workspace shell. Both patient and admin workspaces use the same light foundation. */
.med-app {
  --shell-header-height: 68px;
  position: relative;
  isolation: isolate;
  min-height: 100vh;
  overflow-x: clip;
  background:
    radial-gradient(ellipse at 8% -12%, rgba(23, 111, 137, 0.1), transparent 34%),
    radial-gradient(ellipse at 92% 6%, rgba(109, 98, 160, 0.08), transparent 30%),
    linear-gradient(180deg, #f9fcfd 0%, var(--surface-page) 48%, #edf5f6 100%);
}

.med-app::before,
.med-app::after {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  content: '';
}

.med-app::before {
  background-image:
    radial-gradient(circle, rgba(23, 111, 137, 0.16) 0 1px, transparent 1.35px),
    radial-gradient(circle, rgba(109, 98, 160, 0.12) 0 0.8px, transparent 1.2px),
    radial-gradient(circle, rgba(168, 101, 24, 0.1) 0 0.7px, transparent 1.15px);
  background-position: 12px 18px, 38px 46px, 74px 9px;
  background-size: 74px 74px, 109px 109px, 137px 137px;
  opacity: 0.7;
}

.med-app::after {
  background-image:
    repeating-radial-gradient(ellipse at 86% 14%, transparent 0 104px, rgba(23, 111, 137, 0.045) 105px, transparent 107px 156px),
    linear-gradient(118deg, transparent 0 18%, rgba(23, 111, 137, 0.045) 18.08%, transparent 18.28% 57%, rgba(109, 98, 160, 0.04) 57.08%, transparent 57.28%),
    linear-gradient(63deg, transparent 0 37%, rgba(24, 125, 109, 0.04) 37.08%, transparent 37.25% 72%, rgba(168, 101, 24, 0.035) 72.08%, transparent 72.25%);
  mask-image: linear-gradient(to bottom, transparent, #000 8%, #000 92%, transparent);
  opacity: 0.9;
}

.med-header,
.med-body {
  position: relative;
  z-index: 1;
}

.med-header {
  height: var(--shell-header-height);
  padding: 0 clamp(18px, 2.5vw, 34px);
  overflow: visible;
  background: color-mix(in srgb, var(--surface-elevated) 94%, transparent);
  border-bottom: 1px solid var(--border-subtle);
  box-shadow: 0 8px 24px rgba(31, 62, 70, 0.06);
  backdrop-filter: blur(18px) saturate(112%);
}

.med-header::before {
  position: absolute;
  right: 10%;
  bottom: 0;
  left: 32%;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(23, 111, 137, 0.2), rgba(109, 98, 160, 0.16), transparent);
}

.med-header::after {
  right: 6%;
  bottom: 0;
  width: 18%;
  background: linear-gradient(90deg, transparent, var(--primary), var(--accent-violet), transparent);
  filter: drop-shadow(0 0 5px rgba(23, 111, 137, 0.24));
}

.brand-link {
  gap: 11px;
}

.brand-symbol {
  width: 40px;
  height: 40px;
  color: var(--text-inverse);
  background: linear-gradient(145deg, var(--primary-solid), var(--primary), var(--success));
  border: 0;
  border-radius: 8px;
  box-shadow: 0 7px 18px var(--focus-ring);
}

.brand-copy strong {
  color: var(--text-primary);
  font-size: 17px;
  text-shadow: none;
}

.brand-copy small {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
}

.header-actions {
  gap: 4px;
}

.header-action,
.med-header :deep(.el-button.is-text) {
  color: var(--text-secondary);
}

.med-header :deep(.el-button.is-text:hover),
.header-action:hover,
.user-trigger:hover {
  color: var(--text-primary);
  background: var(--surface-muted);
}

.user-trigger {
  min-height: 38px;
  border-radius: 6px;
}

.user-avatar {
  background: linear-gradient(145deg, #4a9fe8, #5b6fc6 62%, #8e6bc4);
  color: #f7fbff;
  box-shadow: 0 0 14px rgba(78, 160, 244, 0.2);
}

.med-body {
  display: block;
  min-height: calc(100vh - var(--shell-header-height));
}

.med-main {
  min-height: calc(100vh - var(--shell-header-height));
  padding: 24px clamp(22px, 3vw, 42px) 44px;
  overflow: visible;
}

.side-icon-shell {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 7px;
  background: var(--surface-muted);
  color: inherit;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.med-sidebar .side-link.active .side-icon-shell {
  color: var(--primary);
  background: linear-gradient(145deg, var(--primary-light), var(--accent-violet-soft));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.84), 0 5px 14px rgba(23, 111, 137, 0.08);
}

.side-link-label {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Patient services use a quiet top navigation; admin routes use a real sidebar. */
.patient-nav {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: 4px;
  margin: 0 24px;
}

.patient-nav-link {
  position: relative;
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 11px;
  border-radius: 6px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  white-space: nowrap;
}

.patient-nav-link::after {
  position: absolute;
  right: 12px;
  bottom: 2px;
  left: 12px;
  height: 2px;
  content: '';
  border-radius: 2px;
  background: var(--primary);
  opacity: 0;
  transform: scaleX(0.4);
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.patient-nav-link:hover,
.patient-nav-link.active {
  background: var(--primary-soft);
  color: var(--primary-solid);
}

.patient-nav-link.active::after {
  opacity: 1;
  transform: scaleX(1);
}

.header-emergency {
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid color-mix(in srgb, var(--danger) 32%, transparent);
  border-radius: 6px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
  white-space: nowrap;
}

.header-emergency:hover {
  border-color: var(--danger);
  background: var(--danger-light);
}

.workspace-patient {
  background: var(--surface-page);
}

.workspace-patient::before,
.workspace-patient::after,
.workspace-patient .med-header::before,
.workspace-patient .med-header::after {
  display: none;
}

.workspace-patient .med-header {
  background: color-mix(in srgb, var(--surface-elevated) 95%, transparent);
  border-bottom: 1px solid var(--border-subtle);
  box-shadow: 0 8px 24px rgba(32, 66, 76, 0.06);
  backdrop-filter: blur(14px) saturate(112%);
}

.workspace-patient .brand-symbol {
  color: var(--text-inverse);
  background: var(--primary-solid);
  box-shadow: 0 7px 18px var(--focus-ring);
}

.workspace-patient .brand-copy strong {
  text-shadow: none;
}

.workspace-patient .med-body {
  display: block;
}

.workspace-patient .med-main,
.workspace-patient.density-compact .med-main,
.workspace-patient.density-relaxed .med-main {
  min-height: calc(100vh - var(--shell-header-height));
  padding: 26px clamp(20px, 3vw, 42px) 48px;
  background: var(--surface-page);
}

.workspace-admin {
  --admin-sidebar-width: 216px;
  background: var(--surface-page);
}

.workspace-admin.admin-sidebar-collapsed {
  --admin-sidebar-width: 72px;
}

.workspace-admin .med-body {
  display: flex;
  align-items: stretch;
}

.workspace-admin .med-sidebar {
  position: sticky;
  top: var(--shell-header-height);
  bottom: auto;
  left: auto;
  z-index: 12;
  width: var(--admin-sidebar-width);
  max-width: none;
  height: calc(100vh - var(--shell-header-height));
  display: flex;
  align-items: stretch;
  flex: 0 0 var(--admin-sidebar-width);
  flex-direction: column;
  gap: 12px;
  padding: 14px 12px 18px;
  overflow-x: hidden;
  overflow-y: auto;
  background: color-mix(in srgb, var(--surface-elevated) 90%, transparent);
  border-right: 1px solid var(--border-subtle);
  transform: none;
  transition: width 0.18s ease, flex-basis 0.18s ease;
  backdrop-filter: blur(18px) saturate(125%);
}

.admin-sidebar-heading {
  min-height: 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 4px 10px 8px;
  border-bottom: 1px solid var(--border-subtle);
}

.admin-sidebar-heading > span {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
}

.sidebar-collapse {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  flex: 0 0 32px;
  border: 1px solid var(--border-default);
  border-radius: 6px;
  background: var(--surface-muted);
  color: var(--text-secondary);
  cursor: pointer;
}

.sidebar-collapse:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.workspace-admin .med-sidebar .side-nav {
  position: static;
  display: grid;
  gap: 5px;
  padding: 0;
  overflow: visible;
  background: transparent;
  border-radius: 0;
  box-shadow: none;
  backdrop-filter: none;
}

.workspace-admin .med-sidebar .side-nav::before {
  display: none;
}

.workspace-admin .med-sidebar .side-link {
  width: 100%;
  min-height: 44px;
  align-items: center;
  flex-direction: row;
  justify-content: flex-start;
  gap: 10px;
  padding: 5px 9px;
  border-radius: 6px;
  color: var(--text-muted);
  font-size: 13px;
}

.workspace-admin .med-sidebar .side-link::before {
  inset: 9px auto 9px 0;
  width: 2px;
  height: auto;
  background: var(--primary);
  transform: scaleY(0.35);
}

.workspace-admin .med-sidebar .side-link.active::before {
  transform: scaleY(1);
  filter: none;
}

.workspace-admin .med-sidebar .side-link:hover,
.workspace-admin .med-sidebar .side-link.active {
  background: var(--primary-soft);
  color: var(--text-primary);
}

.workspace-admin .med-sidebar .side-icon-shell {
  width: 32px;
  height: 32px;
}

.workspace-admin.admin-sidebar-collapsed .med-sidebar {
  padding-inline: 9px;
}

.workspace-admin.admin-sidebar-collapsed .admin-sidebar-heading {
  justify-content: center;
  padding-inline: 0;
}

.workspace-admin.admin-sidebar-collapsed .med-sidebar .side-link {
  justify-content: center;
  padding-inline: 4px;
}

.workspace-admin.admin-sidebar-collapsed .side-link-label {
  display: none;
}

.workspace-admin .med-main {
  min-width: 0;
  flex: 1;
  padding: 24px clamp(22px, 3vw, 42px) 48px;
  background: var(--surface-page);
}

@media (max-width: 1180px) and (min-width: 901px) {
  .med-header {
    gap: 10px;
    padding-inline: 16px;
  }

  .brand-copy small,
  .header-emergency span,
  .header-action span,
  .user-trigger > span:not(.el-avatar) {
    display: none;
  }

  .patient-nav {
    gap: 2px;
    margin-inline: 0;
  }

  .patient-nav-link {
    gap: 5px;
    padding-inline: 7px;
    font-size: 12px;
  }

  .patient-nav-link::after {
    right: 8px;
    left: 8px;
  }
}

@media (max-width: 900px) {
  .med-app {
    --shell-header-height: 64px;
  }

  .med-app::after {
    opacity: 0.54;
  }

  .med-header {
    height: var(--shell-header-height);
    padding: 0 16px;
  }

  .med-sidebar {
    display: none;
  }

  /* The desktop admin selector is more specific than the shared mobile rule. */
  .workspace-admin .med-sidebar {
    display: none;
  }

  .patient-nav {
    display: none;
  }

  .med-body,
  .med-main {
    min-height: calc(100vh - var(--shell-header-height));
  }

  .med-main {
    padding: 20px 18px calc(38px + env(safe-area-inset-bottom));
  }

  .workspace-patient .med-main,
  .workspace-patient.density-compact .med-main,
  .workspace-patient.density-relaxed .med-main,
  .workspace-admin .med-main {
    padding: 20px 18px calc(38px + env(safe-area-inset-bottom));
  }

  .mobile-drawer-content .side-nav {
    display: grid;
    gap: 5px;
  }

  .mobile-drawer-content .side-link {
    width: 100%;
  }

  .mobile-drawer-content .side-icon-shell {
    width: 32px;
    height: 32px;
  }
}

@media (max-width: 640px) {
  .med-main {
    padding: 14px 12px calc(30px + env(safe-area-inset-bottom));
  }

  .workspace-patient .med-main,
  .workspace-patient.density-compact .med-main,
  .workspace-patient.density-relaxed .med-main,
  .workspace-admin .med-main {
    padding: 14px 12px calc(30px + env(safe-area-inset-bottom));
  }

  .header-emergency span {
    display: none;
  }
}

.header-notification-badge {
  display: inline-flex;
  align-items: center;
}

.header-notification-badge :deep(.el-badge__content) {
  top: 5px;
  right: 4px;
  border: 2px solid var(--surface-elevated);
  box-shadow: none;
}

.reminder-panel {
  color: var(--text-primary);
}

.reminder-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-subtle);
}

.reminder-panel__header span,
.reminder-panel__header strong {
  display: block;
}

.reminder-panel__header span {
  color: var(--primary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .04em;
}

.reminder-panel__header strong {
  margin-top: 3px;
  font-size: 14px;
}

.reminder-panel__list {
  display: grid;
  gap: 6px;
  padding-top: 8px;
}

.reminder-panel__item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 52px;
  padding: 8px 4px;
  border: 0;
  border-bottom: 1px solid var(--border-subtle);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.reminder-panel__item:last-child {
  border-bottom: 0;
}

.reminder-panel__item:hover,
.reminder-panel__item:focus-visible {
  outline: none;
  color: var(--primary-solid);
}

.reminder-panel__dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--warning);
  box-shadow: 0 0 0 4px var(--warning-soft);
}

.reminder-panel__copy {
  min-width: 0;
  flex: 1;
}

.reminder-panel__copy strong,
.reminder-panel__copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reminder-panel__copy strong {
  font-size: 12px;
}

.reminder-panel__copy small {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 11px;
}

.reminder-panel__arrow {
  flex: 0 0 auto;
  color: var(--text-muted);
  transform: rotate(-90deg);
}

.reminder-panel__empty,
.reminder-panel__loading {
  display: grid;
  place-items: center;
  gap: 6px;
  min-height: 130px;
  padding: 16px;
  color: var(--text-muted);
  text-align: center;
}

.reminder-panel__empty strong {
  color: var(--text-secondary);
  font-size: 12px;
}

.reminder-panel__empty span,
.reminder-panel__loading {
  font-size: 11px;
}

</style>
