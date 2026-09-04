<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  CircleCheckFilled,
  Clock,
  Document,
  FirstAidKit,
  InfoFilled,
  OfficeBuilding,
  Refresh,
  Search,
  WarningFilled,
} from '@element-plus/icons-vue'
import client from '../api/client'
import { buildRecordQuery, emptyRecordFilters, presetDateRange } from '../lib/recordFilters'

const router = useRouter()

const records = ref([])
const loading = ref(true)
const loadError = ref('')
const activeTab = ref('all')
const recordId = ref('')
const sessionId = ref('')
const symptoms = ref('')
const department = ref('')
const keyword = ref('')
const dateRange = ref([])
const page = ref(0)
const pageSize = ref(20)
const total = ref(0)
const pages = ref(0)

const tabOptions = [
  { value: 'all', label: '全部' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
]

const hasActiveFilters = computed(
  () => activeTab.value !== 'all'
    || Boolean(recordId.value.trim())
    || Boolean(sessionId.value.trim())
    || Boolean(symptoms.value.trim())
    || Boolean(department.value.trim())
    || Boolean(keyword.value.trim())
    || dateRange.value.length > 0,
)

async function fetchRecords() {
  loading.value = true
  loadError.value = ''

  try {
    const response = await client.get('/records', {
      params: buildRecordQuery({
        recordId: recordId.value,
        sessionId: sessionId.value,
        symptoms: symptoms.value,
        department: department.value,
        keyword: keyword.value,
        dateRange: dateRange.value,
        page: page.value,
        size: pageSize.value,
      }),
    })
    records.value = Array.isArray(response.data?.data) ? response.data.data : []
    total.value = Number(response.data?.meta?.total) || 0
    pages.value = Number(response.data?.meta?.pages) || 0
  } catch (error) {
    records.value = []
    loadError.value = error.response?.data?.error || '问诊记录暂时无法加载，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function normalizeText(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function toTimestamp(value) {
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : 0
}

function formatDate(value) {
  const timestamp = toTimestamp(value)
  if (!timestamp) return '时间未提供'

  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(timestamp)
}

function riskType(level) {
  return { 高: 'danger', 中: 'warning', 低: 'success' }[normalizeText(level)] || 'info'
}

function riskLabel(level) {
  return normalizeText(level) ? `${normalizeText(level)}风险` : '风险待评估'
}

function reviewStatusLabel(status) {
  return {
    PENDING_REVIEW: '待医生复核',
    IN_REVIEW: '医生复核中',
    EMERGENCY_ESCALATED: '急诊人工升级',
    CLINICIAN_CONFIRMED: '医生已确认',
    CLINICIAN_MODIFIED: '医生已调整',
    REJECTED: '医生已退回',
    SYSTEM_FALLBACK: '人工流程接管',
  }[status] || '复核状态待同步'
}

function reviewStatusType(status) {
  return {
    PENDING_REVIEW: 'warning',
    IN_REVIEW: 'warning',
    EMERGENCY_ESCALATED: 'danger',
    CLINICIAN_CONFIRMED: 'success',
    CLINICIAN_MODIFIED: 'success',
    REJECTED: 'info',
    SYSTEM_FALLBACK: 'info',
  }[status] || 'info'
}

function clearFilters() {
  const reset = emptyRecordFilters()
  activeTab.value = 'all'
  recordId.value = reset.recordId
  sessionId.value = reset.sessionId
  symptoms.value = reset.symptoms
  department.value = reset.department
  keyword.value = reset.keyword
  dateRange.value = reset.dateRange
  page.value = reset.page
  fetchRecords()
}

function applyFilters() {
  page.value = 0
  fetchRecords()
}

function chooseTab(value) {
  activeTab.value = value
  dateRange.value = value === 'all' ? [] : presetDateRange(value === '7d' ? 7 : 30)
  page.value = 0
  fetchRecords()
}

function changePage(nextPage) {
  page.value = nextPage - 1
  fetchRecords()
}

function viewDetail(id) {
  if (id === undefined || id === null) return
  router.push(`/records/${id}`)
}

onMounted(fetchRecords)
</script>

<template>
  <div class="records-page">
    <header class="records-header">
      <div>
        <span class="records-eyebrow">CONSULTATION HISTORY</span>
        <h1>问诊记录</h1>
        <p>按时间查看每次辅助分诊的症状信息与结果摘要</p>
      </div>

      <div class="records-header-actions">
        <el-tooltip content="刷新记录" placement="bottom">
          <el-button circle plain aria-label="刷新问诊记录" :loading="loading" @click="fetchRecords">
            <el-icon v-if="!loading"><Refresh /></el-icon>
          </el-button>
        </el-tooltip>
        <el-button type="primary" @click="router.push('/consult')">
          <el-icon><FirstAidKit /></el-icon>
          发起问诊
        </el-button>
      </div>
    </header>

    <section class="records-toolbar" aria-label="记录筛选">
      <div class="records-tabs" role="tablist" aria-label="记录状态">
        <button
          v-for="tab in tabOptions"
          :key="tab.value"
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.value"
          :class="['records-tab', { 'records-tab-active': activeTab === tab.value }]"
          @click="chooseTab(tab.value)"
        >
          {{ tab.label }}
        </button>
      </div>

      <div class="records-controls">
        <el-input
          v-model="recordId"
          class="records-id-input"
          clearable
          placeholder="记录 ID"
          aria-label="按记录 ID 筛选"
          @keyup.enter="applyFilters"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-input v-model="sessionId" class="records-session-input" clearable placeholder="会话 ID" aria-label="按会话 ID 筛选" @keyup.enter="applyFilters" />
        <el-input v-model="symptoms" class="records-field-input" clearable placeholder="症状" aria-label="按症状筛选" @keyup.enter="applyFilters" />
        <el-input v-model="department" class="records-field-input" clearable placeholder="科室" aria-label="按科室筛选" @keyup.enter="applyFilters" />
        <el-input v-model="keyword" class="records-search" clearable placeholder="关键词（ID/会话/症状/科室）" aria-label="按关键词筛选" @keyup.enter="applyFilters" />
        <el-date-picker
          v-model="dateRange"
          class="records-date-range"
          type="datetimerange"
          value-format="x"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          aria-label="按起止时间筛选"
          @change="applyFilters"
        />
        <el-button type="primary" @click="applyFilters">应用</el-button>
        <el-button v-if="hasActiveFilters" plain @click="clearFilters">重置</el-button>
      </div>
    </section>

    <el-alert
      v-if="loadError"
      class="records-error"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
    >
      <template #default>
        <el-button text type="primary" @click="fetchRecords">重新加载</el-button>
      </template>
    </el-alert>

    <section v-if="loading" class="records-loading" aria-label="正在加载问诊记录">
      <div v-for="index in 3" :key="index" class="records-skeleton-row">
        <el-skeleton :rows="2" animated />
      </div>
    </section>

    <section v-else-if="!loadError && !records.length" class="records-empty">
      <el-empty :description="hasActiveFilters ? '没有符合筛选条件的记录' : '暂无问诊记录'">
        <el-button v-if="hasActiveFilters" type="primary" plain @click="clearFilters">
          清除筛选
        </el-button>
        <el-button v-else type="primary" @click="router.push('/consult')">开始首次问诊</el-button>
      </el-empty>
    </section>

    <section v-else-if="!loadError" class="records-list" aria-live="polite">
      <div class="records-list-header">
        <div>
          <strong>问诊时间线</strong>
        <span>共 {{ total }} 条记录 · 第 {{ pages ? page + 1 : 0 }} / {{ pages }} 页</span>
        </div>
        <el-button v-if="hasActiveFilters" text type="primary" @click="clearFilters">
          清除筛选
        </el-button>
      </div>

      <div class="records-timeline">
        <article
          v-for="record in records"
          :key="record.id ?? record.sessionId"
          class="records-item"
          role="button"
          tabindex="0"
          :aria-label="`查看${normalizeText(record.symptoms) || '本次问诊'}详情`"
          @click="viewDetail(record.id)"
          @keydown.enter="viewDetail(record.id)"
          @keydown.space.prevent="viewDetail(record.id)"
        >
          <span
            :class="[
              'records-timeline-dot',
              { 'records-timeline-dot-pending': !normalizeText(record.riskLevel) },
            ]"
          >
            <el-icon v-if="normalizeText(record.riskLevel)"><CircleCheckFilled /></el-icon>
            <el-icon v-else><InfoFilled /></el-icon>
          </span>

          <div class="records-item-content">
            <div class="records-item-heading">
              <div>
                <span class="records-item-kicker">问诊症状</span>
                <h2>{{ normalizeText(record.symptoms) || '症状信息未提供' }}</h2>
              </div>
              <div class="records-item-tags">
                <el-tag :type="reviewStatusType(record.reviewStatus)" effect="plain">
                  {{ reviewStatusLabel(record.reviewStatus) }}
                </el-tag>
                <el-tag :type="normalizeText(record.riskLevel) ? 'success' : 'info'" effect="light">
                  {{ normalizeText(record.riskLevel) ? 'AI 分诊草案' : '结果字段未提供' }}
                </el-tag>
              </div>
            </div>

            <div class="records-meta">
              <div class="records-meta-item">
                <span class="records-meta-icon records-meta-icon-blue">
                  <el-icon><OfficeBuilding /></el-icon>
                </span>
                <div>
                  <small>推荐科室</small>
                  <strong>{{ normalizeText(record.department) || '未提供' }}</strong>
                </div>
              </div>

              <div class="records-meta-item">
                <span class="records-meta-icon records-meta-icon-orange">
                  <el-icon><WarningFilled /></el-icon>
                </span>
                <div>
                  <small>风险程度</small>
                  <el-tag :type="riskType(record.riskLevel)" size="small" effect="plain">
                    {{ riskLabel(record.riskLevel) }}
                  </el-tag>
                </div>
              </div>

              <div class="records-meta-item">
                <span class="records-meta-icon records-meta-icon-purple">
                  <el-icon><Clock /></el-icon>
                </span>
                <div>
                  <small>建议就医时效</small>
                  <strong :class="{ 'records-placeholder': !normalizeText(record.urgency) }">
                    {{ normalizeText(record.urgency) || '未提供' }}
                  </strong>
                </div>
              </div>
            </div>

            <div class="records-item-footer">
              <span>
                <el-icon><Clock /></el-icon>
                问诊时间：{{ formatDate(record.createdAt) }}
              </span>
              <el-button text type="primary" @click.stop="viewDetail(record.id)">
                查看详情
                <el-icon><ArrowRight /></el-icon>
              </el-button>
            </div>
          </div>
        </article>
      </div>
      <div class="records-pagination">
        <el-pagination
          v-if="total"
          background
          layout="prev, pager, next, sizes"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :current-page="page + 1"
          :total="total"
          @current-change="changePage"
          @size-change="(nextSize) => { pageSize = nextSize; page = 0; fetchRecords() }"
        />
      </div>
    </section>

    <p class="records-data-note">
      <el-icon><Document /></el-icon>
      列表仅展示接口返回的摘要字段，完整建议与依据请进入记录详情查看。
    </p>
  </div>
</template>

<style scoped>
.records-page {
  width: min(100%, 1180px);
  min-width: 0;
  margin: 0 auto;
  display: grid;
  gap: 16px;
  color: var(--text-primary);
}

.records-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 4px 2px 8px;
}

.records-eyebrow {
  color: var(--primary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
}

.records-header h1 {
  margin: 5px 0 4px;
  font-size: 24px;
  line-height: 1.3;
  letter-spacing: 0;
}

.records-header p {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.records-header-actions,
.records-item-tags,
.records-controls,
.records-list-header > div,
.records-item-footer,
.records-item-footer > span,
.records-data-note {
  display: flex;
  align-items: center;
}

.records-header-actions {
  gap: 10px;
  flex: 0 0 auto;
}

.records-item-tags {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.records-toolbar {
  display: flex;
  align-items: stretch;
  flex-direction: column;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  background: var(--glass-surface);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(125%);
  -webkit-backdrop-filter: blur(18px) saturate(125%);
}

.records-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: max-content;
}

.records-tab {
  min-height: 36px;
  padding: 0 12px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.records-tab:hover {
  color: var(--primary-hover);
  background: var(--primary-soft);
}

.records-tab span {
  margin-left: 5px;
  color: var(--text-subtle);
  font-size: 10px;
}

.records-tab-active {
  color: var(--primary);
  background: var(--primary-soft);
  font-weight: 600;
}

.records-tab-active span {
  color: var(--primary);
}

.records-controls {
  justify-content: flex-start;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.records-search {
  width: min(100%, 260px);
}

.records-id-input {
  width: 118px;
}

.records-session-input {
  width: 210px;
}

.records-field-input {
  width: 142px;
}

.records-date-range {
  width: 350px;
  flex: 0 0 auto;
}

.records-error {
  border-radius: 8px;
}

.records-loading,
.records-empty,
.records-list {
  overflow: hidden;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  background: var(--glass-surface);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(125%);
  -webkit-backdrop-filter: blur(18px) saturate(125%);
}

.records-skeleton-row {
  padding: 22px 24px;
  border-bottom: 1px solid var(--border-subtle);
}

.records-skeleton-row:last-child {
  border-bottom: 0;
}

.records-empty {
  min-height: 360px;
  display: grid;
  place-items: center;
}

.records-list-header {
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 22px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--surface-muted);
}

.records-list-header > div {
  gap: 10px;
}

.records-list-header strong {
  font-size: 13px;
}

.records-list-header span {
  color: var(--text-muted);
  font-size: 11px;
}

.records-timeline {
  position: relative;
}

.records-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px;
  border-top: 1px solid var(--border-subtle);
}

.records-item {
  position: relative;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 13px;
  padding: 15px 22px;
  border-bottom: 1px solid var(--border-subtle);
  outline: 0;
  cursor: pointer;
  transition: background 0.16s ease;
}

.records-item:last-child {
  border-bottom: 0;
}

.records-item:hover,
.records-item:focus-visible {
  background: var(--surface-muted);
}

.records-item::after {
  position: absolute;
  z-index: 0;
  top: 45px;
  bottom: -15px;
  left: 37px;
  border-left: 1px solid var(--primary-subtle);
  content: '';
}

.records-item:last-child::after {
  display: none;
}

.records-timeline-dot {
  position: relative;
  z-index: 1;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border: 5px solid var(--primary-soft);
  border-radius: 50%;
  background: var(--primary-solid);
  color: var(--text-inverse);
  font-size: 13px;
  box-shadow: 0 0 0 1px var(--primary-subtle), 0 0 16px var(--focus-ring);
}

.records-timeline-dot-pending {
  border-color: var(--surface-subtle);
  background: var(--text-subtle);
  box-shadow: 0 0 0 1px var(--border-default);
}

.records-item-content {
  min-width: 0;
}

.records-item-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.records-item-kicker {
  color: var(--text-muted);
  font-size: 10px;
}

.records-item-heading h2 {
  max-width: 720px;
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.55;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.records-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 10px 0;
}

.records-meta-item {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.records-meta-icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 7px;
}

.records-meta-icon-blue {
  color: var(--primary);
  background: var(--primary-soft);
}

.records-meta-icon-orange {
  color: var(--warning);
  background: var(--warning-soft);
}

.records-meta-icon-purple {
  color: var(--accent-violet);
  background: var(--accent-violet-soft);
}

.records-meta-item div {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.records-meta-item small {
  color: var(--text-muted);
  font-size: 10px;
}

.records-meta-item strong {
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.records-meta-item .records-placeholder {
  color: var(--text-subtle);
  font-weight: 500;
}

.records-item-footer {
  justify-content: space-between;
  gap: 14px;
  padding-top: 8px;
  border-top: 1px dashed var(--border-default);
}

.records-item-footer > span {
  gap: 6px;
  color: var(--text-muted);
  font-size: 11px;
}

.records-data-note {
  justify-content: center;
  gap: 6px;
  margin: 0;
  color: var(--text-subtle);
  font-size: 10px;
  text-align: center;
}

@media (max-width: 1000px) {
  .records-toolbar {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .records-controls {
    justify-content: flex-start;
  }

  .records-search,
  .records-session-input,
  .records-date-range {
    width: min(100%, 420px);
  }
}

@media (max-width: 720px) {
  .records-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }

  .records-header-actions {
    width: 100%;
    justify-content: flex-end;
  }

  .records-header-actions > .el-button:last-child {
    padding-inline: 10px;
  }

  .records-controls {
    align-items: stretch;
    flex-direction: column;
  }

  .records-search,
  .records-id-input,
  .records-session-input,
  .records-field-input,
  .records-date-range {
    width: 100%;
  }

  .records-pagination {
    overflow-x: auto;
    justify-content: flex-start;
  }

  .records-meta {
    grid-template-columns: 1fr;
  }

  .records-item {
    padding-inline: 16px;
  }

  .records-item-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .records-item-heading h2 {
    max-width: 100%;
    white-space: normal;
  }

  .records-item::after {
    left: 31px;
  }
}

@media (max-width: 420px) {
  .records-toolbar,
  .records-item {
    padding-inline: 12px;
  }

  .records-tabs {
    width: 100%;
  }

  .records-tab {
    min-width: 0;
    flex: 1;
    padding-inline: 7px;
  }

  .records-item {
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 10px;
  }

  .records-timeline-dot {
    width: 28px;
    height: 28px;
  }

  .records-item::after {
    left: 25px;
  }

  .records-item-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
}
</style>
