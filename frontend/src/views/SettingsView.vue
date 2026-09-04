<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bell,
  Brush,
  Delete,
  InfoFilled,
  Lock,
  Monitor,
  Refresh,
  Setting,
  SwitchButton,
  User,
  WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import {
  DEFAULT_PRIVACY_SETTINGS,
  SETTINGS_KEY,
  clearHealthHistory as removeHealthHistory,
  healthHistoryKey,
  normalizePrivacySettings,
} from '../lib/privacy'

const router = useRouter()
const auth = useAuthStore()

const defaultSettings = {
  notifications: {
    consultationComplete: true,
    safetyAlerts: true,
  },
  privacy: { ...DEFAULT_PRIVACY_SETTINGS },
  appearance: {
    density: 'standard',
  },
}

const settings = reactive(structuredClone(defaultSettings))
const savedSnapshot = ref('')
const savedAt = ref('')
const healthHistoryCount = ref(0)

const isDirty = computed(() => JSON.stringify(settings) !== savedSnapshot.value)
const displayName = computed(() => auth.username || '当前用户')
const roleName = computed(() => (auth.isAdmin ? '系统管理员' : '普通用户'))
const currentHealthHistoryKey = computed(() => healthHistoryKey(auth.username))
const densityDescription = computed(() => ({
  compact: '更紧凑的信息间距，适合高频工作场景',
  standard: '平衡信息密度与阅读舒适度',
  relaxed: '更宽松的内容间距，适合长时间阅读',
}[settings.appearance.density]))

function normalizeSettings(stored) {
  return {
    notifications: {
      consultationComplete: stored?.notifications?.consultationComplete !== false,
      safetyAlerts: true,
    },
    privacy: normalizePrivacySettings(stored?.privacy),
    appearance: {
      density: ['compact', 'standard', 'relaxed'].includes(stored?.appearance?.density)
        ? stored.appearance.density
        : 'standard',
    },
  }
}

function refreshLocalDataCount() {
  try {
    const stored = JSON.parse(localStorage.getItem(currentHealthHistoryKey.value) || '[]')
    healthHistoryCount.value = Array.isArray(stored) ? stored.length : 0
  } catch {
    healthHistoryCount.value = 0
  }
}

function loadSettings() {
  let stored = null
  try {
    stored = JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null')
  } catch {
    stored = null
  }

  Object.assign(settings, normalizeSettings(stored))
  savedSnapshot.value = JSON.stringify(settings)
  refreshLocalDataCount()
}

function saveSettings() {
  settings.notifications.safetyAlerts = true
  const serializedSettings = JSON.stringify(settings)
  localStorage.setItem(SETTINGS_KEY, serializedSettings)
  window.dispatchEvent(new CustomEvent('medpilot-settings-changed', {
    detail: JSON.parse(serializedSettings),
  }))

  if (!settings.privacy.saveHealthHistory) {
    removeHealthHistory(auth.username)
    refreshLocalDataCount()
  }

  savedSnapshot.value = serializedSettings
  savedAt.value = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date())
  ElMessage.success('设置已保存到当前浏览器')
}

async function resetSettings() {
  try {
    await ElMessageBox.confirm('恢复默认设置后，当前未保存的偏好将被覆盖。', '恢复默认设置', {
      type: 'warning',
      confirmButtonText: '恢复默认',
      cancelButtonText: '取消',
    })
    Object.assign(settings, structuredClone(defaultSettings))
    saveSettings()
  } catch {
    // The user cancelled the confirmation.
  }
}

async function clearHealthHistory() {
  if (!healthHistoryCount.value) {
    ElMessage.info('当前没有健康检索记录')
    return
  }

  try {
    await ElMessageBox.confirm(`确认清除当前浏览器中的 ${healthHistoryCount.value} 条健康检索记录？`, '清除本地数据', {
      type: 'warning',
      confirmButtonText: '确认清除',
      cancelButtonText: '取消',
    })
    removeHealthHistory(auth.username)
    refreshLocalDataCount()
    ElMessage.success('健康检索记录已清除')
  } catch {
    // The user cancelled the confirmation.
  }
}

async function logout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能继续使用工作台。', '退出当前账号', {
      type: 'warning',
      confirmButtonText: '退出登录',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }

  const username = auth.username
  try {
    await auth.logout()
  } catch {
    ElMessage.error('退出登录失败，当前会话仍然有效，请检查网络后重试。')
    return
  }

  if (settings.privacy.clearHistoryOnLogout) {
    removeHealthHistory(username)
  }
  router.replace('/login')
}

onMounted(loadSettings)
</script>

<template>
  <div class="settings-page" :class="`settings-page--${settings.appearance.density}`">
    <header class="settings-page__header">
      <div>
        <p class="settings-page__eyebrow">WORKSPACE PREFERENCES</p>
        <h1>系统设置</h1>
        <p>管理当前浏览器上的通知、隐私和界面偏好。</p>
      </div>
      <div class="settings-page__actions">
        <span v-if="savedAt" class="settings-page__saved">已于 {{ savedAt }} 保存</span>
        <el-button :icon="Refresh" @click="resetSettings">恢复默认</el-button>
        <el-button type="primary" :icon="Setting" :disabled="!isDirty" @click="saveSettings">保存设置</el-button>
      </div>
    </header>

    <div class="settings-layout">
      <div class="settings-layout__primary">
        <section class="settings-panel" aria-labelledby="notification-title">
          <header class="settings-panel__header">
            <div class="settings-panel__icon settings-panel__icon--blue"><el-icon><Bell /></el-icon></div>
            <div>
              <h2 id="notification-title">通知偏好</h2>
              <p>选择需要在工作台中重点关注的消息</p>
            </div>
          </header>

          <div class="settings-rows">
            <div class="settings-row">
              <div>
                <strong>问诊完成通知</strong>
                <span>智能体完成分析并生成分诊建议时提示</span>
              </div>
              <el-switch v-model="settings.notifications.consultationComplete" aria-label="问诊完成通知" />
            </div>
            <div class="settings-row">
              <div>
                <strong>安全与急症提示</strong>
                <span>高风险信息始终展示，不能关闭</span>
              </div>
              <div class="settings-row__control">
                <el-tag size="small" type="danger" effect="light">安全必需</el-tag>
                <el-switch v-model="settings.notifications.safetyAlerts" disabled aria-label="安全与急症提示" />
              </div>
            </div>
          </div>
        </section>

        <section class="settings-panel" aria-labelledby="privacy-title">
          <header class="settings-panel__header">
            <div class="settings-panel__icon settings-panel__icon--green"><el-icon><Lock /></el-icon></div>
            <div>
              <h2 id="privacy-title">隐私与本地数据</h2>
              <p>控制仅保存在当前浏览器中的非敏感偏好数据</p>
            </div>
          </header>

          <div class="settings-rows">
            <div class="settings-row">
              <div>
                <strong>保存健康检索历史</strong>
                <span>便于重复查看已搜索的健康主题</span>
              </div>
              <el-switch v-model="settings.privacy.saveHealthHistory" aria-label="保存健康检索历史" />
            </div>
            <div class="settings-row">
              <div>
                <strong>退出时清除检索历史</strong>
                <span>退出当前账号时自动删除本地健康检索记录</span>
              </div>
              <el-switch
                v-model="settings.privacy.clearHistoryOnLogout"
                :disabled="!settings.privacy.saveHealthHistory"
                aria-label="退出时清除检索历史"
              />
            </div>
            <div class="settings-row settings-row--data">
              <div>
                <strong>健康检索记录</strong>
                <span>当前浏览器共保存 {{ healthHistoryCount }} 条，不包含诊断或病历数据</span>
              </div>
              <el-button plain type="danger" :icon="Delete" @click="clearHealthHistory">清除记录</el-button>
            </div>
          </div>

          <div class="settings-privacy-note">
            <el-icon><InfoFilled /></el-icon>
            <span>本页设置不采集身份证号、联系方式、详细病史等敏感信息。</span>
          </div>
        </section>
      </div>

      <aside class="settings-layout__secondary">
        <section class="settings-panel" aria-labelledby="appearance-title">
          <header class="settings-panel__header">
            <div class="settings-panel__icon settings-panel__icon--violet"><el-icon><Brush /></el-icon></div>
            <div>
              <h2 id="appearance-title">界面偏好</h2>
              <p>调整工作台的信息呈现方式</p>
            </div>
          </header>

          <div class="settings-fields">
            <label>
              <span>内容密度</span>
              <el-select v-model="settings.appearance.density" aria-label="内容密度">
                <el-option label="紧凑" value="compact" />
                <el-option label="标准" value="standard" />
                <el-option label="宽松" value="relaxed" />
              </el-select>
              <small>{{ densityDescription }}</small>
            </label>

          </div>
        </section>

        <section class="settings-panel" aria-labelledby="account-title">
          <header class="settings-panel__header">
            <div class="settings-panel__icon settings-panel__icon--orange"><el-icon><User /></el-icon></div>
            <div>
              <h2 id="account-title">账号与会话</h2>
              <p>查看当前登录状态</p>
            </div>
          </header>

          <div class="settings-account">
            <el-avatar :size="44">{{ displayName.charAt(0).toUpperCase() }}</el-avatar>
            <div>
              <strong>{{ displayName }}</strong>
              <span>{{ roleName }}</span>
            </div>
            <el-tag type="success" effect="light">会话有效</el-tag>
          </div>

          <div class="settings-session">
            <div>
              <el-icon><Monitor /></el-icon>
              <span><strong>当前浏览器</strong><small>本机 Web 会话</small></span>
            </div>
            <el-tag size="small" effect="plain">当前设备</el-tag>
          </div>

          <div class="settings-logout">
            <div>
              <el-icon><WarningFilled /></el-icon>
              <span>退出后将清除当前登录令牌</span>
            </div>
            <el-button type="danger" plain :icon="SwitchButton" @click="logout">退出登录</el-button>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  width: min(100%, 1180px);
  margin: 0 auto;
  color: var(--text-primary);
}

.settings-page__header,
.settings-page__actions,
.settings-panel__header,
.settings-row,
.settings-row__control,
.settings-privacy-note,
.settings-account,
.settings-session > div,
.settings-logout,
.settings-logout > div {
  display: flex;
  align-items: center;
}

.settings-page__header {
  min-height: 72px;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

.settings-page__eyebrow {
  margin: 0 0 5px;
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
}

.settings-page h1 {
  margin: 0;
  font-size: 24px;
  line-height: 1.3;
  letter-spacing: 0;
}

.settings-page__header > div > p:last-child {
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 14px;
}

.settings-page__actions {
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.settings-page__saved {
  color: var(--text-muted);
  font-size: 12px;
}

.settings-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.16fr) minmax(340px, 0.84fr);
  align-items: start;
  gap: 16px;
}

.settings-layout__primary,
.settings-layout__secondary {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.settings-panel {
  overflow: hidden;
  border: 1px solid var(--border-default);
  border-radius: 8px;
  background: var(--surface-elevated);
  box-shadow: var(--shadow-card);
}

.settings-panel__header {
  gap: 12px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--border-subtle);
}

.settings-panel__icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 8px;
  font-size: 17px;
}

.settings-panel__icon--blue {
  color: var(--primary);
  background: var(--primary-soft);
}

.settings-panel__icon--green {
  color: var(--success);
  background: var(--success-soft);
}

.settings-panel__icon--violet {
  color: var(--accent-violet);
  background: var(--accent-violet-soft);
}

.settings-panel__icon--orange {
  color: var(--warning);
  background: var(--warning-soft);
}

.settings-panel__header h2 {
  margin: 0;
  font-size: 15px;
  line-height: 1.45;
  letter-spacing: 0;
}

.settings-panel__header p {
  margin: 3px 0 0;
  color: var(--text-muted);
  font-size: 12px;
}

.settings-rows {
  padding: 0 20px;
}

.settings-row {
  min-height: 70px;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--border-subtle);
}

.settings-row:last-child {
  border-bottom: 0;
}

.settings-row > div:first-child {
  min-width: 0;
}

.settings-row strong,
.settings-fields label > span,
.settings-account strong,
.settings-session strong {
  display: block;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
}

.settings-row > div:first-child > span,
.settings-account span,
.settings-session small {
  display: block;
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.settings-row__control {
  gap: 10px;
  flex: 0 0 auto;
}

.settings-privacy-note {
  gap: 8px;
  margin: 0 20px 18px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--primary-soft);
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.55;
}

.settings-privacy-note .el-icon {
  flex: 0 0 auto;
  color: var(--primary);
}

.settings-fields {
  display: grid;
  gap: 18px;
  padding: 20px;
}

.settings-fields label {
  display: grid;
  gap: 8px;
}

.settings-fields small {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.settings-fields :deep(.el-select) {
  width: 100%;
}

.settings-account {
  gap: 12px;
  padding: 20px;
}

.settings-account .el-avatar {
  flex: 0 0 auto;
  background: var(--primary);
  color: var(--text-inverse);
  font-size: 15px;
  font-weight: 700;
}

.settings-account > div {
  min-width: 0;
  flex: 1;
}

.settings-session {
  margin: 0 20px;
  padding: 13px 14px;
  border: 1px solid var(--border-default);
  border-radius: 7px;
  background: var(--surface-muted);
}

.settings-session,
.settings-logout {
  display: flex;
  justify-content: space-between;
  gap: 14px;
}

.settings-session > div {
  gap: 10px;
  min-width: 0;
}

.settings-session .el-icon {
  flex: 0 0 auto;
  color: var(--primary);
  font-size: 17px;
}

.settings-logout {
  align-items: center;
  margin-top: 18px;
  padding: 16px 20px;
  border-top: 1px solid var(--border-subtle);
}

.settings-logout > div {
  gap: 7px;
  color: var(--text-muted);
  font-size: 12px;
}

.settings-logout > div .el-icon {
  color: var(--danger);
}

.settings-page--compact .settings-panel__header {
  padding-top: 14px;
  padding-bottom: 14px;
}

.settings-page--compact .settings-row {
  min-height: 60px;
}

.settings-page--compact .settings-fields,
.settings-page--compact .settings-account {
  padding-top: 16px;
  padding-bottom: 16px;
}

.settings-page--relaxed .settings-panel__header {
  padding-top: 22px;
  padding-bottom: 22px;
}

.settings-page--relaxed .settings-row {
  min-height: 82px;
}

.settings-page--relaxed .settings-fields,
.settings-page--relaxed .settings-account {
  padding-top: 24px;
  padding-bottom: 24px;
}

@media (max-width: 1050px) {
  .settings-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 700px) {
  .settings-page__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .settings-page__actions {
    justify-content: flex-start;
  }

  .settings-row--data,
  .settings-logout {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (max-width: 480px) {
  .settings-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
    padding: 14px 0;
  }
}
</style>
