<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  AlertTriangle,
  Check,
  FileText,
  RefreshCw,
  Send,
  ShieldCheck,
  UserRoundCheck,
  X,
} from 'lucide-vue-next'
import client from '../api/client'

const reviews = ref([])
const selected = ref(null)
const loading = ref(true)
const submitting = ref(false)
const loadError = ref('')
const statusFilter = ref('')
const decisionForm = ref({
  decision: 'MODIFY',
  finalDepartment: '',
  finalRiskLevel: '',
  finalUrgency: '',
  reason: '',
})

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'PENDING_REVIEW', label: '待复核' },
  { value: 'IN_REVIEW', label: '复核中' },
  { value: 'EMERGENCY_ESCALATED', label: '急诊升级' },
  { value: 'CLINICIAN_CONFIRMED', label: '医生确认' },
  { value: 'CLINICIAN_MODIFIED', label: '医生修改' },
  { value: 'REJECTED', label: '已退回' },
]

const pendingCount = computed(() => reviews.value.filter((item) => (
  ['PENDING_REVIEW', 'EMERGENCY_ESCALATED'].includes(item.status)
)).length)

function statusLabel(status) {
  return statusOptions.find((item) => item.value === status)?.label || '状态未知'
}

function statusType(status) {
  return {
    PENDING_REVIEW: 'warning',
    IN_REVIEW: 'primary',
    EMERGENCY_ESCALATED: 'danger',
    CLINICIAN_CONFIRMED: 'success',
    CLINICIAN_MODIFIED: 'success',
    REJECTED: 'info',
  }[status] || 'info'
}

function maskMpi(value) {
  const text = String(value || '')
  if (text.length < 5) return text ? '已关联患者' : '未关联 MPI'
  return `${text.slice(0, 2)}${'•'.repeat(Math.min(6, text.length - 4))}${text.slice(-2)}`
}

function canAct(review) {
  return review && !['CLINICIAN_CONFIRMED', 'CLINICIAN_MODIFIED', 'REJECTED', 'SYSTEM_FALLBACK'].includes(review.status)
}

async function fetchReviews() {
  loading.value = true
  loadError.value = ''
  try {
    const response = await client.get('/clinical-reviews', {
      params: statusFilter.value ? { status: statusFilter.value } : {},
    })
    reviews.value = Array.isArray(response.data?.data) ? response.data.data : []
    if (selected.value) {
      selected.value = reviews.value.find((item) => item.id === selected.value.id) || null
    }
  } catch (error) {
    reviews.value = []
    loadError.value = error.response?.data?.error || '复核队列暂时无法加载。'
  } finally {
    loading.value = false
  }
}

function selectReview(review) {
  selected.value = review
  decisionForm.value = {
    decision: 'MODIFY',
    finalDepartment: review.finalDepartment || review.originalDepartment || '',
    finalRiskLevel: review.finalRiskLevel || review.originalRiskLevel || '',
    finalUrgency: review.finalUrgency || review.originalUrgency || '',
    reason: '',
  }
}

async function claimReview(review) {
  if (!review?.id || submitting.value) return
  submitting.value = true
  try {
    const response = await client.post(`/clinical-reviews/${review.id}/claim`)
    const updated = response.data?.data
    if (updated) selectReview(updated)
    await fetchReviews()
    ElMessage.success('已领取复核任务')
  } catch (error) {
    ElMessage.error(error.response?.data?.error || '领取失败，请刷新后重试。')
  } finally {
    submitting.value = false
  }
}

async function submitDecision() {
  const review = selected.value
  if (!review?.id || submitting.value || !canAct(review)) return
  if (!decisionForm.value.reason.trim()) {
    ElMessage.warning('请填写复核依据，便于审计追踪。')
    return
  }
  submitting.value = true
  try {
    const response = await client.post(`/clinical-reviews/${review.id}/decision`, {
      decision: decisionForm.value.decision,
      finalDepartment: decisionForm.value.finalDepartment,
      finalRiskLevel: decisionForm.value.finalRiskLevel,
      finalUrgency: decisionForm.value.finalUrgency,
      reason: decisionForm.value.reason,
    })
    const updated = response.data?.data
    if (updated) selected.value = updated
    await fetchReviews()
    ElMessage.success('复核决定已保存，AI 原始结果保持不变。')
  } catch (error) {
    ElMessage.error(error.response?.data?.error || '保存复核决定失败。')
  } finally {
    submitting.value = false
  }
}

onMounted(fetchReviews)
</script>

<template>
  <div class="clinical-review-page">
    <header class="clinical-review-header">
      <div>
        <span class="clinical-review-eyebrow">CLINICAL SAFETY GATE</span>
        <h1>医生复核队列</h1>
        <p>AI 结果仅作为待复核草案，最终分诊决定由具备医疗关系的医生或复核员提交。</p>
      </div>
      <div class="clinical-review-actions">
        <el-tag :type="pendingCount ? 'warning' : 'success'" effect="plain">
          {{ pendingCount }} 项待处理
        </el-tag>
        <el-tooltip content="刷新复核队列" placement="bottom">
          <el-button circle plain :loading="loading" aria-label="刷新复核队列" @click="fetchReviews">
            <RefreshCw :size="16" aria-hidden="true" />
          </el-button>
        </el-tooltip>
      </div>
    </header>

    <section class="clinical-review-toolbar" aria-label="复核筛选">
      <el-select v-model="statusFilter" aria-label="按复核状态筛选" @change="fetchReviews">
        <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <span class="clinical-review-safety"><ShieldCheck :size="15" /> 访问范围由医疗关系、院区和 MFA 共同决定</span>
    </section>

    <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="loadError">
      <template #default><el-button text type="primary" @click="fetchReviews">重新加载</el-button></template>
    </el-alert>

    <div class="clinical-review-layout">
      <section class="clinical-review-list" aria-label="待复核记录">
        <el-skeleton v-if="loading" :rows="8" animated />
        <el-empty v-else-if="!reviews.length" description="当前没有可访问的复核任务" />
        <template v-else>
          <button
            v-for="review in reviews"
            :key="review.id"
            type="button"
            :class="['clinical-review-item', { selected: selected?.id === review.id }]"
            @click="selectReview(review)"
          >
            <div class="clinical-review-item-top">
              <span class="clinical-review-item-id">#{{ review.consultationRecordId || review.recordId }}</span>
              <el-tag :type="statusType(review.status)" size="small" effect="plain">{{ statusLabel(review.status) }}</el-tag>
            </div>
            <strong>{{ review.originalDepartment || '未提供科室' }}</strong>
            <span>{{ maskMpi(review.patientMpiId) }} · {{ review.originalRiskLevel || '风险待评估' }}</span>
            <small>{{ review.createdAt ? new Date(review.createdAt).toLocaleString('zh-CN') : '--' }}</small>
          </button>
        </template>
      </section>

      <section v-if="selected" class="clinical-review-detail" aria-label="复核详情">
        <div class="clinical-review-detail-heading">
          <div>
            <span>记录 #{{ selected.consultationRecordId || selected.recordId }}</span>
            <h2>复核决定</h2>
          </div>
          <el-tag :type="statusType(selected.status)" effect="dark">{{ statusLabel(selected.status) }}</el-tag>
        </div>

        <el-alert v-if="selected.status === 'EMERGENCY_ESCALATED'" type="error" :closable="false" show-icon>
          <template #title>系统已标记急诊或高风险信号</template>
          <p>请先按院内急诊流程处置，再提交复核决定。</p>
        </el-alert>

        <dl class="clinical-review-comparison">
          <div><dt>AI 推荐科室</dt><dd>{{ selected.originalDepartment || '--' }}</dd></div>
          <div><dt>AI 风险等级</dt><dd>{{ selected.originalRiskLevel || '--' }}</dd></div>
          <div><dt>AI 就医时效</dt><dd>{{ selected.originalUrgency || '--' }}</dd></div>
          <div><dt>患者标识</dt><dd>{{ maskMpi(selected.patientMpiId) }}</dd></div>
        </dl>

        <div v-if="canAct(selected)" class="clinical-review-form">
          <el-button
            v-if="selected.status === 'PENDING_REVIEW' || selected.status === 'EMERGENCY_ESCALATED'"
            type="primary"
            plain
            :loading="submitting"
            @click="claimReview(selected)"
          >
            <UserRoundCheck :size="15" /> 领取复核
          </el-button>
          <el-form v-if="selected.status === 'IN_REVIEW'" label-position="top" @submit.prevent="submitDecision">
            <el-form-item label="最终决定">
              <el-radio-group v-model="decisionForm.decision">
                <el-radio-button value="CONFIRM"><Check :size="14" />确认 AI 结果</el-radio-button>
                <el-radio-button value="MODIFY"><FileText :size="14" />修改分诊</el-radio-button>
                <el-radio-button value="REJECT"><X :size="14" />退回</el-radio-button>
                <el-radio-button value="ESCALATE"><AlertTriangle :size="14" />急诊升级</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <div class="clinical-review-fields">
              <el-form-item label="最终科室"><el-input v-model="decisionForm.finalDepartment" maxlength="128" /></el-form-item>
              <el-form-item label="最终风险等级"><el-input v-model="decisionForm.finalRiskLevel" maxlength="32" /></el-form-item>
              <el-form-item label="最终就医时效"><el-input v-model="decisionForm.finalUrgency" maxlength="512" /></el-form-item>
            </div>
            <el-form-item label="复核依据（必填）"><el-input v-model="decisionForm.reason" type="textarea" :rows="4" maxlength="2000" show-word-limit /></el-form-item>
            <el-button type="primary" native-type="submit" :loading="submitting">
              <Send :size="15" /> 提交复核决定
            </el-button>
          </el-form>
        </div>
        <el-alert v-else type="success" :closable="false" show-icon title="该复核已完成，最终决定已留存审计记录。" />
      </section>

      <el-empty v-else class="clinical-review-placeholder" description="选择左侧任务查看 AI 原始结果与复核表单" />
    </div>
  </div>
</template>

<style scoped>
.clinical-review-page { width: min(100%, 1180px); margin: 0 auto; display: grid; gap: 16px; color: var(--text-primary); }
.clinical-review-header, .clinical-review-actions, .clinical-review-toolbar, .clinical-review-item-top, .clinical-review-detail-heading { display: flex; align-items: center; }
.clinical-review-header { justify-content: space-between; gap: 20px; }
.clinical-review-eyebrow { color: var(--primary); font-size: 10px; font-weight: 700; }
.clinical-review-header h1 { margin: 5px 0 4px; font-size: 24px; }
.clinical-review-header p { margin: 0; color: var(--text-muted); font-size: 12px; }
.clinical-review-actions { gap: 10px; }
.clinical-review-toolbar { justify-content: space-between; gap: 12px; padding: 12px 14px; border: 1px solid var(--border-default); border-radius: 8px; background: var(--glass-surface); }
.clinical-review-safety { display: inline-flex; align-items: center; gap: 6px; color: var(--text-muted); font-size: 11px; }
.clinical-review-layout { display: grid; grid-template-columns: minmax(280px, .7fr) minmax(0, 1.3fr); gap: 16px; align-items: start; }
.clinical-review-list, .clinical-review-detail, .clinical-review-placeholder { min-width: 0; padding: 16px; border: 1px solid var(--border-default); border-radius: 8px; background: var(--glass-surface); }
.clinical-review-list { display: grid; gap: 8px; }
.clinical-review-item { display: grid; gap: 6px; width: 100%; padding: 12px; border: 1px solid var(--border-subtle); border-radius: 6px; color: var(--text-primary); background: transparent; text-align: left; cursor: pointer; }
.clinical-review-item:hover, .clinical-review-item.selected { border-color: var(--primary); background: var(--primary-soft); }
.clinical-review-item-top { justify-content: space-between; gap: 8px; }
.clinical-review-item-id, .clinical-review-item small { color: var(--text-muted); font-size: 10px; }
.clinical-review-item > span { color: var(--text-secondary); font-size: 11px; }
.clinical-review-detail { display: grid; gap: 16px; }
.clinical-review-detail-heading { justify-content: space-between; gap: 12px; border-bottom: 1px solid var(--border-subtle); padding-bottom: 14px; }
.clinical-review-detail-heading span { color: var(--text-muted); font-size: 11px; }
.clinical-review-detail-heading h2 { margin: 4px 0 0; font-size: 18px; }
.clinical-review-detail p { margin: 4px 0 0; font-size: 11px; }
.clinical-review-comparison { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 0; }
.clinical-review-comparison div { padding: 10px; border: 1px solid var(--border-subtle); border-radius: 6px; background: var(--surface-muted); }
.clinical-review-comparison dt { color: var(--text-muted); font-size: 10px; }
.clinical-review-comparison dd { margin: 5px 0 0; color: var(--text-primary); font-size: 12px; overflow-wrap: anywhere; }
.clinical-review-form { display: grid; gap: 14px; padding-top: 2px; }
.clinical-review-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.clinical-review-form :deep(.el-radio-button__inner) { display: inline-flex; align-items: center; gap: 5px; }
@media (max-width: 800px) { .clinical-review-layout { grid-template-columns: 1fr; } .clinical-review-fields, .clinical-review-comparison { grid-template-columns: 1fr; } .clinical-review-header, .clinical-review-toolbar { align-items: flex-start; flex-direction: column; } }
</style>
