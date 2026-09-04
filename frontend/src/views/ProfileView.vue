<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Calendar,
  Check,
  ClipboardPlus,
  Clock3,
  HeartPulse,
  Plus,
  RefreshCw,
  ShieldCheck,
  Trash2,
  X,
} from 'lucide-vue-next'
import client from '../api/client'

const loading = ref(false)
const saving = ref(false)
const profile = reactive({
  allergies: '',
  conditions: '',
  medications: '',
  notes: '',
  consentGranted: false,
})
const timeline = ref([])
const followUps = ref([])
const followUpVisible = ref(false)
const followUpSaving = ref(false)
const followUpForm = reactive({ title: '', dueAt: '', notes: '' })
const updatingTask = ref(null)
const highlightedReminderId = ref('')
const route = useRoute()

const openTasks = computed(() => followUps.value.filter((task) => task.status === 'OPEN'))
const dueTasks = computed(() => openTasks.value.filter((task) => (
  task.due === true || (task.dueAt && new Date(task.dueAt).getTime() <= Date.now())
)))
const highRiskCount = computed(() => timeline.value.filter((item) => item.riskLevel === '高').length)
const latestVisit = computed(() => timeline.value[0]?.createdAt || '')

function payload(data) {
  return data?.data || data || {}
}

function formatDate(value, withTime = true) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return new Intl.DateTimeFormat('zh-CN', withTime
    ? { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }
    : { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}

function riskType(level) {
  return { 高: 'danger', 中: 'warning', 低: 'success' }[level] || 'info'
}

function resetFollowUp() {
  Object.assign(followUpForm, { title: '', dueAt: '', notes: '' })
}

async function loadAll() {
  loading.value = true
  try {
    const [profileResponse, timelineResponse, followUpResponse] = await Promise.all([
      client.get('/profile'),
      client.get('/profile/timeline'),
      client.get('/profile/follow-ups'),
    ])
    Object.assign(profile, payload(profileResponse.data))
    timeline.value = Array.isArray(payload(timelineResponse.data)) ? payload(timelineResponse.data) : []
    followUps.value = Array.isArray(payload(followUpResponse.data)) ? payload(followUpResponse.data) : []
    await focusReminderFromQuery()
  } catch {
    ElMessage.error('健康档案暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function focusReminderFromQuery() {
  const reminderId = String(route.query.reminder || '')
  if (!reminderId) return
  highlightedReminderId.value = reminderId
  await nextTick()
  const target = [...document.querySelectorAll('[data-reminder-id]')]
    .find((element) => element.dataset.reminderId === reminderId)
  target?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  window.setTimeout(() => {
    if (highlightedReminderId.value === reminderId) highlightedReminderId.value = ''
  }, 2600)
}

async function saveProfile() {
  if (saving.value) return
  saving.value = true
  try {
    const response = await client.put('/profile', { ...profile })
    Object.assign(profile, payload(response.data))
    ElMessage.success(profile.consentGranted ? '健康档案已加密保存' : '已撤回授权并清空档案内容')
  } catch {
    ElMessage.error('健康档案保存失败，请稍后重试。')
  } finally {
    saving.value = false
  }
}

async function createFollowUp() {
  if (followUpSaving.value || !followUpForm.title.trim() || !followUpForm.dueAt) {
    ElMessage.warning('请填写复诊事项和提醒时间')
    return
  }
  followUpSaving.value = true
  try {
    const response = await client.post('/profile/follow-ups', {
      title: followUpForm.title.trim(),
      dueAt: new Date(followUpForm.dueAt).toISOString(),
      notes: followUpForm.notes.trim(),
    })
    followUps.value = [...followUps.value, payload(response.data)].sort(
      (a, b) => new Date(a.dueAt) - new Date(b.dueAt),
    )
    followUpVisible.value = false
    resetFollowUp()
    ElMessage.success('复诊提醒已创建')
  } catch {
    ElMessage.error('复诊提醒创建失败，请稍后重试。')
  } finally {
    followUpSaving.value = false
  }
}

async function updateTask(task, status) {
  if (!task?.id || updatingTask.value) return
  updatingTask.value = task.id
  try {
    const response = await client.patch(`/profile/follow-ups/${task.id}`, { status })
    const updated = payload(response.data)
    const index = followUps.value.findIndex((item) => item.id === task.id)
    if (index >= 0) followUps.value[index] = updated
    ElMessage.success(status === 'COMPLETED' ? '复诊提醒已完成' : '复诊提醒已取消')
  } catch {
    ElMessage.error('复诊提醒更新失败，请稍后重试。')
  } finally {
    updatingTask.value = null
  }
}

async function removeProfile() {
  try {
    await ElMessageBox.confirm(
      '撤回授权会删除长期档案字段，但不会删除已形成的问诊记录。是否继续？',
      '撤回健康档案授权',
      { type: 'warning', confirmButtonText: '确认清空', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  Object.assign(profile, {
    allergies: '', conditions: '', medications: '', notes: '', consentGranted: false,
  })
  await saveProfile()
}

onMounted(loadAll)
watch(() => route.query.reminder, focusReminderFromQuery)
</script>

<template>
  <div class="profile-page">
    <header class="profile-header">
      <div>
        <span class="profile-kicker">LONGITUDINAL HEALTH</span>
        <h1>健康档案</h1>
        <p>把每次问诊串成可回顾的健康时间线，档案字段默认加密保存。</p>
      </div>
      <el-button :loading="loading" plain @click="loadAll">
        <RefreshCw :size="16" :class="{ 'profile-spin': loading }" />刷新档案
      </el-button>
    </header>

    <el-alert
      class="profile-safety-alert"
      type="info"
      :closable="false"
      show-icon
      title="档案只用于辅助分诊上下文，不代表诊断结论；急性危险信号仍以安全筛查和线下急救为先。"
    />

    <section class="profile-overview" aria-label="档案概览">
      <article><span class="profile-overview-icon blue"><HeartPulse :size="19" /></span><div><small>已记录问诊</small><strong>{{ timeline.length }} <i>次</i></strong></div></article>
      <article><span class="profile-overview-icon red"><ShieldCheck :size="19" /></span><div><small>高风险记录</small><strong>{{ highRiskCount }} <i>次</i></strong></div></article>
      <article><span class="profile-overview-icon orange"><Clock3 :size="19" /></span><div><small>待完成复诊</small><strong>{{ openTasks.length }} <i>项</i></strong></div></article>
      <article><span class="profile-overview-icon green"><Calendar :size="19" /></span><div><small>最近问诊</small><strong>{{ formatDate(latestVisit, false) }}</strong></div></article>
    </section>

    <div class="profile-layout">
      <main class="profile-main">
        <section class="profile-panel profile-form-panel">
          <header class="profile-panel-heading">
            <div><span>PRIVATE PROFILE</span><h2>长期健康信息</h2></div>
            <el-tag :type="profile.consentGranted ? 'success' : 'info'" effect="plain">
              {{ profile.consentGranted ? '已授权使用' : '未授权保存' }}
            </el-tag>
          </header>
          <el-form label-position="top" @submit.prevent="saveProfile">
            <div class="profile-form-grid">
              <el-form-item label="过敏史"><el-input v-model="profile.allergies" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="例如：青霉素过敏；不清楚可填写暂不确定" /></el-form-item>
              <el-form-item label="既往史 / 慢病"><el-input v-model="profile.conditions" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="例如：哮喘、高血压或近期手术史" /></el-form-item>
              <el-form-item label="当前用药记录"><el-input v-model="profile.medications" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="只记录已知信息，不填写自行用药建议" /></el-form-item>
              <el-form-item label="补充说明"><el-input v-model="profile.notes" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="可记录希望在问诊时提醒自己的信息" /></el-form-item>
            </div>
            <div class="profile-consent-row">
              <el-checkbox v-model="profile.consentGranted">我同意将以上信息加密保存，并用于后续辅助分诊上下文</el-checkbox>
              <div>
                <el-button text type="danger" @click="removeProfile"><Trash2 :size="15" />撤回并清空</el-button>
                <el-button type="primary" :loading="saving" @click="saveProfile"><ShieldCheck :size="15" />保存档案</el-button>
              </div>
            </div>
          </el-form>
        </section>

        <section class="profile-panel timeline-panel">
          <header class="profile-panel-heading">
            <div><span>CONSULTATION TIMELINE</span><h2>问诊时间线</h2></div>
            <small>{{ timeline.length }} 条记录</small>
          </header>
          <el-empty v-if="!timeline.length && !loading" description="完成一次问诊后，这里会出现可回顾的时间线" />
          <div v-else class="timeline-list">
            <article v-for="item in timeline" :key="item.id" class="timeline-item">
              <span class="timeline-dot" />
              <div class="timeline-copy">
                <div class="timeline-topline"><strong>{{ item.symptoms || '未填写症状' }}</strong><time>{{ formatDate(item.createdAt) }}</time></div>
                <p>{{ item.department || '线下分诊台' }} · {{ item.urgency || '建议结合症状变化就医' }}</p>
              </div>
              <el-tag :type="riskType(item.riskLevel)" size="small" effect="plain">{{ item.riskLevel || '待评估' }}风险</el-tag>
            </article>
          </div>
        </section>
      </main>

      <aside class="profile-side">
        <section class="profile-panel followup-panel">
          <header class="profile-panel-heading">
            <div><span>FOLLOW-UP PLAN</span><h2>复诊提醒</h2></div>
            <div class="followup-heading-actions">
              <el-tag v-if="dueTasks.length" type="warning" effect="plain">{{ dueTasks.length }} 项已到期</el-tag>
              <el-button circle type="primary" aria-label="新增复诊提醒" @click="followUpVisible = true"><Plus :size="17" /></el-button>
            </div>
          </header>
          <el-empty v-if="!followUps.length" :image-size="58" description="还没有复诊计划" />
          <div v-else class="followup-list">
            <article v-for="task in followUps" :key="task.id" :data-reminder-id="task.id" :class="['followup-item', `followup-${task.status.toLowerCase()}`, { 'followup-highlighted': highlightedReminderId === String(task.id) }]">
              <span class="followup-icon"><ClipboardPlus :size="17" /></span>
              <div><strong>{{ task.title }}</strong><small>{{ formatDate(task.dueAt) }} <em v-if="task.status === 'OPEN' && task.due">· 已到期</em></small><p v-if="task.notes">{{ task.notes }}</p></div>
              <div class="followup-actions" v-if="task.status === 'OPEN'">
                <el-tooltip content="标记完成"><button type="button" :disabled="updatingTask === task.id" @click="updateTask(task, 'COMPLETED')"><Check :size="15" /></button></el-tooltip>
                <el-tooltip content="取消提醒"><button type="button" :disabled="updatingTask === task.id" @click="updateTask(task, 'CANCELLED')"><X :size="15" /></button></el-tooltip>
              </div>
            </article>
          </div>
        </section>
      </aside>
    </div>

    <el-dialog v-model="followUpVisible" title="新增复诊提醒" width="460px" @closed="resetFollowUp">
      <el-form label-position="top" @submit.prevent="createFollowUp">
        <el-form-item label="提醒事项" required><el-input v-model="followUpForm.title" maxlength="256" placeholder="例如：复查咳嗽变化" /></el-form-item>
        <el-form-item label="提醒时间" required><el-date-picker v-model="followUpForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择复诊或观察时间" class="profile-dialog-control" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="followUpForm.notes" type="textarea" :rows="3" maxlength="4000" placeholder="例如：若症状加重，不要等待提醒，直接线下就医" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="followUpVisible = false">取消</el-button><el-button type="primary" :loading="followUpSaving" @click="createFollowUp"><Calendar :size="15" />创建提醒</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.profile-page { width: min(100%, 1180px); margin: 0 auto; display: grid; gap: 16px; color: var(--text-primary); }
.profile-header, .profile-panel-heading, .profile-consent-row, .timeline-topline, .followup-item { display: flex; align-items: center; }
.profile-header { justify-content: space-between; gap: 18px; padding: 4px 2px 6px; }
.profile-kicker, .profile-panel-heading > div > span { color: var(--primary); font-size: 12px; font-weight: 700; letter-spacing: .02em; }
.profile-header h1 { margin: 5px 0 4px; font-size: 25px; line-height: 1.25; }
.profile-header p { margin: 0; color: var(--text-muted); font-size: 13px; }
.profile-safety-alert, .profile-panel { border: 1px solid var(--border-default); border-radius: var(--radius-lg); background: var(--glass-surface); box-shadow: var(--shadow-card); backdrop-filter: blur(18px) saturate(125%); }
.profile-overview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; padding: 7px; border-radius: var(--radius-lg); background: var(--glass-surface); box-shadow: var(--shadow-card); }
.profile-overview article { display: flex; align-items: center; gap: 10px; min-width: 0; padding: 12px; border-radius: var(--radius-md); }
.profile-overview-icon { display: grid; width: 38px; height: 38px; flex: 0 0 auto; place-items: center; border-radius: 8px; }
.profile-overview-icon.blue { color: var(--primary); background: var(--primary-soft); } .profile-overview-icon.red { color: var(--danger); background: var(--danger-soft); } .profile-overview-icon.orange { color: var(--warning); background: var(--warning-soft); } .profile-overview-icon.green { color: var(--success); background: var(--success-soft); }
.profile-overview small, .profile-overview strong { display: block; } .profile-overview small { color: var(--text-muted); font-size: 12px; } .profile-overview strong { margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 18px; } .profile-overview strong i { color: var(--text-muted); font-size: 11px; font-style: normal; font-weight: 500; }
.profile-layout { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(300px, .75fr); gap: 16px; align-items: start; }
.profile-main, .profile-side { display: grid; gap: 16px; min-width: 0; }
.profile-panel { padding: 20px; }
.profile-panel-heading { justify-content: space-between; gap: 14px; margin-bottom: 17px; } .profile-panel-heading h2 { margin: 4px 0 0; font-size: 16px; } .profile-panel-heading > small { color: var(--text-muted); font-size: 12px; }
.followup-heading-actions { display: flex; align-items: center; gap: 8px; }
.profile-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; } .profile-form-grid :deep(.el-form-item) { margin-bottom: 15px; }
.profile-consent-row { justify-content: space-between; gap: 12px; padding-top: 14px; border-top: 1px solid var(--border-subtle); } .profile-consent-row > div { display: flex; gap: 6px; flex: 0 0 auto; } .profile-consent-row .el-button { margin: 0; }
.profile-consent-row :deep(.el-checkbox__label) { color: var(--text-secondary); font-size: 12px; white-space: normal; }
.timeline-list { position: relative; display: grid; gap: 0; padding-left: 9px; } .timeline-list::before { position: absolute; top: 9px; bottom: 9px; left: 4px; width: 1px; content: ''; background: var(--border-default); }
.timeline-item { position: relative; display: flex; align-items: flex-start; gap: 12px; min-width: 0; padding: 13px 0 13px 11px; border-bottom: 1px solid var(--border-subtle); } .timeline-item:last-child { border-bottom: 0; }
.timeline-dot { width: 9px; height: 9px; flex: 0 0 auto; margin-top: 5px; margin-left: -20px; border: 2px solid var(--surface-elevated); border-radius: 50%; background: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
.timeline-copy { min-width: 0; flex: 1; } .timeline-topline { justify-content: space-between; gap: 12px; } .timeline-topline strong { min-width: 0; overflow-wrap: anywhere; font-size: 13px; } .timeline-topline time { flex: 0 0 auto; color: var(--text-muted); font-size: 11px; } .timeline-copy p { margin: 4px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.55; }
.followup-list { display: grid; gap: 9px; } .followup-item { align-items: flex-start; gap: 10px; min-width: 0; padding: 12px; border: 1px solid var(--border-subtle); border-radius: var(--radius-md); background: var(--surface-muted); transition: border-color .18s ease, box-shadow .18s ease, background .18s ease; } .followup-item > div:nth-child(2) { min-width: 0; flex: 1; } .followup-icon { display: grid; width: 32px; height: 32px; flex: 0 0 auto; place-items: center; border-radius: 7px; color: var(--primary); background: var(--primary-soft); } .followup-item strong, .followup-item small, .followup-item p { display: block; } .followup-item strong { overflow-wrap: anywhere; font-size: 12px; } .followup-item small { margin-top: 3px; color: var(--text-muted); font-size: 11px; } .followup-item small em { color: var(--warning); font-style: normal; font-weight: 700; } .followup-item p { margin: 5px 0 0; color: var(--text-secondary); font-size: 11px; line-height: 1.5; } .followup-item.followup-highlighted { border-color: var(--warning); background: var(--warning-soft); box-shadow: 0 0 0 3px color-mix(in srgb, var(--warning) 18%, transparent); } .followup-completed { opacity: .65; } .followup-completed .followup-icon { color: var(--success); background: var(--success-soft); } .followup-cancelled { opacity: .5; }
.followup-actions { display: flex; gap: 3px; } .followup-actions button { display: grid; width: 28px; height: 28px; padding: 0; place-items: center; border: 1px solid var(--border-default); border-radius: 6px; color: var(--text-muted); background: var(--surface-elevated); cursor: pointer; } .followup-actions button:hover { color: var(--primary); border-color: var(--primary); } .followup-actions button:last-child:hover { color: var(--danger); border-color: var(--danger); }
.profile-dialog-control { width: 100%; } .profile-spin { animation: profile-spin 1s linear infinite; } @keyframes profile-spin { to { transform: rotate(360deg); } }
@media (max-width: 900px) { .profile-layout { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .profile-header { align-items: flex-start; flex-direction: column; } .profile-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); } .profile-form-grid { grid-template-columns: 1fr; } .profile-consent-row { align-items: stretch; flex-direction: column; } .profile-consent-row > div { justify-content: flex-end; } .timeline-topline { align-items: flex-start; flex-direction: column; gap: 3px; } }
@media (prefers-reduced-motion: reduce) { .profile-spin { animation: none; } }
</style>
