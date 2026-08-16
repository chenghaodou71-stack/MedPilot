<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { init, use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import {
  Aim,
  ChatDotRound,
  CircleCheckFilled,
  Clock,
  Collection,
  Cpu,
  DataLine,
  DocumentChecked,
  Filter,
  Grid,
  Loading,
  Monitor,
  QuestionFilled,
  Refresh,
  Search,
  WarningFilled,
} from '@element-plus/icons-vue'
import { apiFetch } from '../api/client'
import { createConsultTraceState, reduceConsultTraceEvent } from '../lib/consultTrace'

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const health = ref(null)
const healthLoading = ref(false)
const healthError = ref('')
const lastUpdatedAt = ref(null)
const healthSamples = ref([])
const chartMetric = ref('sessions')
const chartElement = ref(null)

const traceId = ref('')
const traceLoading = ref(false)
const traceState = ref(createConsultTraceState())
const traceError = ref('')

// Trace inventory is deliberately kept separate from the selected trace detail so
// refreshing a page of records never clears an operator's current investigation.
const traceRows = ref([])
const traceListLoading = ref(false)
const traceListError = ref('')
const traceListPage = ref(0)
const traceListSize = ref(10)
const traceListTotal = ref(0)
const traceListMeta = ref({ page: 0, size: 10, total: 0, pages: 0 })
const traceStats = ref(null)
const traceStatsLoading = ref(false)
const traceStatsError = ref('')
const traceFilterDraft = ref({ status: '', timeout: false, range: [] })
const traceFilters = ref({ status: '', timeout: false, range: [] })

let chart = null
let chartResizeObserver = null
let healthPollTimer = null
let traceListRequestId = 0
let traceStatsRequestId = 0

const defaultNodes = ['safety_screen', 'extract', 'retrieve', 'classify', 'compose', 'ask_followup']
const nodeMeta = {
  safety_screen: {
    label: '医疗安全筛查',
    shortLabel: '安全筛查',
    description: '在模型调用前识别高风险危险信号',
    icon: WarningFilled,
    tone: 'red',
  },
  extract: {
    label: '症状采集智能体',
    shortLabel: '症状采集',
    description: '提取症状、持续时间与危险信号',
    icon: ChatDotRound,
    tone: 'blue',
  },
  retrieve: {
    label: '知识检索智能体',
    shortLabel: '知识检索',
    description: '召回本地医学知识与证据',
    icon: Search,
    tone: 'green',
  },
  classify: {
    label: '辅助分诊智能体',
    shortLabel: '辅助分诊',
    description: '评估科室、风险与就诊时效',
    icon: Aim,
    tone: 'purple',
  },
  compose: {
    label: '回答编排智能体',
    shortLabel: '回答编排',
    description: '汇总建议、依据与安全边界',
    icon: DocumentChecked,
    tone: 'orange',
  },
  ask_followup: {
    label: '主动追问智能体',
    shortLabel: '主动追问',
    description: '信息不足时生成必要追问',
    icon: QuestionFilled,
    tone: 'red',
  },
}

const traceEvents = computed(() => traceState.value.events)
const traceCompleted = computed(() => traceState.value.status === 'done')

const displayNodes = computed(() => {
  const observed = []
  traceEvents.value.forEach((event) => {
    if (event.type === 'node' && event.node && !observed.includes(event.node)) {
      observed.push(event.node)
    }
  })
  return observed.length ? observed : defaultNodes
})

const statusCards = computed(() => [
  {
    label: '模型服务',
    value: !health.value ? '--' : health.value.ollama?.ok ? '在线' : '离线',
    detail: health.value?.ollama?.model
      || (Array.isArray(health.value?.ollama?.models) ? health.value.ollama.models.join(' · ') : '')
      || health.value?.ollama?.error
      || 'Ollama 状态未获取',
    icon: Cpu,
    tone: !health.value ? 'blue' : health.value.ollama?.ok ? 'green' : 'red',
    state: !health.value ? '待连接' : health.value.ollama?.ok ? '正常' : '异常',
  },
  {
    label: '活跃会话',
    value: health.value?.sessions?.active ?? '--',
    unit: '个',
    detail: '当前内存会话数量',
    icon: Monitor,
    tone: 'blue',
    state: health.value ? '实时' : '待连接',
  },
  {
    label: '知识文档',
    value: health.value?.knowledge?.docs ?? '--',
    unit: '篇',
    detail: '本地知识库收录量',
    icon: Collection,
    tone: 'purple',
    state: health.value ? '已同步' : '待连接',
  },
  {
    label: '向量索引',
    value: !health.value ? '--' : health.value.knowledge?.index_loaded ? '已加载' : '未加载',
    detail: '检索器内存加载状态',
    icon: Grid,
    tone: !health.value ? 'blue' : health.value.knowledge?.index_loaded ? 'orange' : 'red',
    state: !health.value ? '待连接' : health.value.knowledge?.index_loaded ? '就绪' : '未就绪',
  },
])

const executionProgress = computed(() => {
  const nodes = Object.values(traceState.value.nodes)
  if (!nodes.length) return 0
  const completed = nodes.filter((node) => ['done', 'error'].includes(node.status)).length
  return Math.round((completed / nodes.length) * 100)
})

const lastUpdatedLabel = computed(() => {
  if (!lastUpdatedAt.value) return '尚未完成采样'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(lastUpdatedAt.value)
})

const sparseChartSummary = computed(() => {
  const metricLabel = chartMetric.value === 'sessions' ? '活跃会话' : '知识文档'
  if (!healthSamples.value.length) return `等待首次${metricLabel}采样`
  const latest = healthSamples.value.at(-1)
  return `首个真实样本：${metricLabel} ${latest?.[chartMetric.value] ?? 0}`
})

const monitorLoading = computed(() => (
  healthLoading.value || traceListLoading.value || traceStatsLoading.value
))

const traceStatsCards = computed(() => {
  const stats = traceStats.value
  const total = Number(stats?.totalTraces)
  const failed = Number(stats?.failedTraces)
  const timeout = Number(stats?.timeoutTraces)
  const failureRate = Number.isFinite(total) && total > 0 && Number.isFinite(failed)
    ? `${Math.round((failed / total) * 1000) / 10}%`
    : '--'
  return [
    {
      label: '已记录链路',
      value: Number.isFinite(total) ? total : '--',
      detail: '当前时间范围内的持久化 Trace',
      tone: 'blue',
      icon: DataLine,
    },
    {
      label: '失败链路',
      value: Number.isFinite(failed) ? failed : '--',
      detail: `失败率 ${failureRate}`,
      tone: failed > 0 ? 'red' : 'green',
      icon: WarningFilled,
    },
    {
      label: '超时链路',
      value: Number.isFinite(timeout) ? timeout : '--',
      detail: '错误码中包含 timeout 的记录',
      tone: timeout > 0 ? 'orange' : 'green',
      icon: Clock,
    },
    {
      label: '已完成链路',
      value: Number.isFinite(Number(stats?.completedTraces)) ? Number(stats.completedTraces) : '--',
      detail: '正常结束且未触发失败的记录',
      tone: 'green',
      icon: CircleCheckFilled,
    },
  ]
})

const topFailureCodes = computed(() => {
  const codes = traceStats.value?.errorCodes
  if (!codes || typeof codes !== 'object') return []
  return Object.entries(codes)
    .map(([code, count]) => ({ code, count: Number(count) || 0 }))
    .sort((left, right) => right.count - left.count)
    .slice(0, 5)
})

function stateLabel(state) {
  return {
    pending: '待命',
    running: '执行中',
    done: '已完成',
    error: '异常',
  }[state] || '待命'
}

function stateTagType(state) {
  return {
    running: 'primary',
    done: 'success',
    error: 'danger',
    pending: 'info',
  }[state] || 'info'
}

function traceStatusLabel(status) {
  return status === 'failed' ? '执行失败' : status === 'completed' ? '已完成' : '未知状态'
}

function traceStatusType(status) {
  return status === 'failed' ? 'danger' : status === 'completed' ? 'success' : 'info'
}

function tracePhaseLabel(phase) {
  return {
    screening: '安全筛查',
    collecting: '信息采集',
    summarizing: '症状摘要',
    retrieving: '知识检索',
    triaging: '辅助分诊',
    composing: '回答编排',
    awaiting_followup: '等待追问',
    completed: '已完成',
    escalated: '已升级',
    failed: '已失败',
  }[phase] || phase || '--'
}

function traceFailureLabel(code) {
  if (!code) return '—'
  return String(code).replaceAll('_', ' ')
}

function isTraceTimeout(row) {
  return String(row?.failureCode || '').toLowerCase().includes('timeout')
}

function compactTraceId(value, length = 12) {
  const text = String(value || '')
  if (!text) return '--'
  if (text.length <= length) return text
  const head = Math.max(4, Math.floor((length - 1) / 2))
  return `${text.slice(0, head)}…${text.slice(-Math.max(4, length - head - 1))}`
}

function formatTraceTime(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '--'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

function formatTraceDuration(value) {
  const duration = Number(value)
  if (!Number.isFinite(duration) || duration < 0) return '--'
  if (duration < 1000) return `${Math.round(duration)} ms`
  return `${(duration / 1000).toFixed(duration >= 10000 ? 1 : 2)} s`
}

function toInstant(value, endOfRange = false) {
  if (!value) return ''
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ''
  if (endOfRange) date.setMilliseconds(999)
  return date.toISOString()
}

function buildTraceQuery({ includePaging = true } = {}) {
  const filters = traceFilters.value || {}
  const params = new URLSearchParams()
  if (filters.status) params.set('status', filters.status)
  if (filters.timeout) params.set('timeout', 'true')
  const range = Array.isArray(filters.range) ? filters.range : []
  if (range[0]) params.set('startTime', toInstant(range[0]))
  if (range[1]) params.set('endTime', toInstant(range[1], true))
  if (includePaging) {
    params.set('page', String(Math.max(0, traceListPage.value)))
    params.set('size', String(traceListSize.value))
  }
  return params
}

async function fetchTraceList() {
  const requestId = ++traceListRequestId
  traceListLoading.value = true
  traceListError.value = ''
  try {
    const query = buildTraceQuery()
    const response = await apiFetch(`/api/monitor/traces?${query.toString()}`)
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || payload.success === false) {
      throw new Error(payload.error || `HTTP ${response.status}`)
    }
    if (requestId !== traceListRequestId) return
    traceRows.value = Array.isArray(payload.data) ? payload.data : []
    const meta = payload.meta && typeof payload.meta === 'object' ? payload.meta : {}
    traceListMeta.value = {
      page: Number.isFinite(Number(meta.page)) ? Number(meta.page) : traceListPage.value,
      size: Number.isFinite(Number(meta.size)) ? Number(meta.size) : traceListSize.value,
      total: Number.isFinite(Number(meta.total)) ? Number(meta.total) : traceRows.value.length,
      pages: Number.isFinite(Number(meta.pages)) ? Number(meta.pages) : 0,
    }
    traceListTotal.value = traceListMeta.value.total
  } catch (error) {
    if (requestId === traceListRequestId) {
      traceRows.value = []
      traceListTotal.value = 0
      traceListError.value = error.message || '追踪列表暂时无法获取。'
    }
  } finally {
    if (requestId === traceListRequestId) traceListLoading.value = false
  }
}

async function fetchTraceStats() {
  const requestId = ++traceStatsRequestId
  traceStatsLoading.value = true
  traceStatsError.value = ''
  try {
    const query = buildTraceQuery({ includePaging: false })
    const response = await apiFetch(`/api/monitor/stats?${query.toString()}`)
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || payload.success === false) {
      throw new Error(payload.error || `HTTP ${response.status}`)
    }
    if (requestId !== traceStatsRequestId) return
    traceStats.value = payload.data && typeof payload.data === 'object' ? payload.data : null
  } catch (error) {
    if (requestId === traceStatsRequestId) {
      traceStats.value = null
      traceStatsError.value = error.message || '追踪统计暂时无法获取。'
    }
  } finally {
    if (requestId === traceStatsRequestId) traceStatsLoading.value = false
  }
}

async function refreshMonitor() {
  await Promise.allSettled([fetchHealth(), fetchTraceList(), fetchTraceStats()])
}

async function applyTraceFilters() {
  const draft = traceFilterDraft.value || {}
  traceFilters.value = {
    status: draft.status || '',
    timeout: Boolean(draft.timeout),
    range: Array.isArray(draft.range) ? [...draft.range] : [],
  }
  traceListPage.value = 0
  await Promise.all([fetchTraceList(), fetchTraceStats()])
}

async function resetTraceFilters() {
  traceFilterDraft.value = { status: '', timeout: false, range: [] }
  await applyTraceFilters()
}

async function changeTracePage(page) {
  traceListPage.value = Math.max(0, Number(page) - 1)
  await fetchTraceList()
}

async function changeTraceSize(size) {
  traceListSize.value = Math.max(1, Number(size) || 10)
  traceListPage.value = 0
  await fetchTraceList()
}

async function openTrace(row) {
  const selected = String(row?.traceId || '').trim()
  if (!selected) return
  traceId.value = selected
  await loadTrace()
}

function resetTrace() {
  traceState.value = createConsultTraceState()
  traceError.value = ''
}

function nodeState(nodeId) {
  return traceState.value.nodes[nodeId]?.status || 'pending'
}

function nodeInfo(nodeId) {
  return nodeMeta[nodeId] || {
    label: traceState.value.nodes[nodeId]?.label || nodeId,
    shortLabel: traceState.value.nodes[nodeId]?.label || nodeId,
    description: '协议返回的扩展执行节点',
    icon: DataLine,
    tone: 'blue',
  }
}

function appendHealthSample(data) {
  const now = new Date()
  const label = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(now)

  healthSamples.value = [
    ...healthSamples.value,
    {
      label,
      sessions: Number(data?.sessions?.active) || 0,
      docs: Number(data?.knowledge?.docs) || 0,
    },
  ].slice(-12)
  updateChart()
}

async function fetchHealth() {
  if (healthLoading.value) return
  healthLoading.value = true
  healthError.value = ''

  try {
    const response = await apiFetch('/api/monitor/health')
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`)

    health.value = payload.data || null
    if (!health.value) throw new Error('接口未返回健康状态数据')
    lastUpdatedAt.value = new Date()
    appendHealthSample(health.value)
  } catch (error) {
    healthError.value = error.message || '健康状态暂时无法获取，请检查后端服务。'
  } finally {
    healthLoading.value = false
  }
}

function initChart() {
  if (!chartElement.value || chart) return
  chart = init(chartElement.value)
  chartResizeObserver = new ResizeObserver(() => chart?.resize())
  chartResizeObserver.observe(chartElement.value)
  updateChart()
}

function themeValue(name, fallback) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
}

function updateChart() {
  if (!chart) return

  const isSessions = chartMetric.value === 'sessions'
  const seriesName = isSessions ? '活跃会话' : '知识文档'
  const color = isSessions
    ? themeValue('--primary', '#0f65d8')
    : themeValue('--accent-violet', '#7047b8')
  const borderColor = themeValue('--border-default', '#dfe4ea')
  const subtleBorderColor = themeValue('--border-subtle', '#edf0f4')
  const surfaceColor = themeValue('--surface-elevated', '#ffffff')
  const textColor = themeValue('--text-primary', '#182230')
  const mutedColor = themeValue('--text-muted', '#667085')
  const areaColor = isSessions
    ? themeValue('--primary-soft', 'rgba(88, 186, 255, 0.13)')
    : themeValue('--accent-violet-soft', 'rgba(189, 145, 243, 0.15)')
  const values = healthSamples.value.map((sample) => sample[chartMetric.value])

  chart.setOption({
    animationDuration: 420,
    color: [color],
    grid: { left: 40, right: 16, top: 26, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      borderWidth: 0,
      borderColor,
      backgroundColor: surfaceColor,
      textStyle: { color: textColor, fontSize: 12 },
      extraCssText: 'box-shadow: 0 14px 36px rgba(0, 3, 14, 0.34); backdrop-filter: blur(18px); border-radius: 6px;',
      formatter(params) {
        const item = params[0]
        return `${item.axisValue}<br/>${seriesName}：<strong>${item.value}</strong>`
      },
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: healthSamples.value.map((sample) => sample.label),
      axisLine: { lineStyle: { color: borderColor } },
      axisTick: { show: false },
      axisLabel: { color: mutedColor, fontSize: 12, hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      min: 0,
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: mutedColor, fontSize: 12 },
      splitLine: { lineStyle: { color: subtleBorderColor, type: 'dashed' } },
    },
    series: [
      {
        name: seriesName,
        type: 'line',
        data: values,
        smooth: 0.42,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: values.length <= 6,
        lineStyle: { width: 2.5, color, shadowBlur: 11, shadowColor: color },
        itemStyle: { color, borderColor: surfaceColor, borderWidth: 2 },
        areaStyle: { color: areaColor },
      },
    ],
  }, true)
}

async function loadTrace() {
  const requestedTraceId = traceId.value.trim()
  if (!requestedTraceId || traceLoading.value) return

  resetTrace()
  traceLoading.value = true

  try {
    const response = await apiFetch(`/api/monitor/trace/${encodeURIComponent(requestedTraceId)}`)
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || payload.success === false) {
      throw new Error(payload.error || `HTTP ${response.status}`)
    }

    const events = payload.data?.events
    if (!Array.isArray(events)) throw new Error('接口未返回有效的事件列表')
    if (!events.length) throw new Error('该追踪记录没有保存事件')

    events.forEach((event) => {
      traceState.value = reduceConsultTraceEvent(traceState.value, event)
    })
    if (traceState.value.status === 'error') {
      traceError.value = traceState.value.error || '该问诊链路执行失败'
    }
  } catch (error) {
    traceError.value = `读取失败：${error.message || '无法获取该追踪记录'}`
  } finally {
    traceLoading.value = false
  }
}

function eventLabel(event) {
  if (event.type === 'done') return '流程完成'
  if (event.type === 'error') return '执行异常'
  return nodeMeta[event.node]?.shortLabel || event.label || event.node
}

function eventSummary(event) {
  if (event.type === 'done') return '本次智能体链路已结束'
  if (event.type === 'error') return event.data?.detail || event.detail || '未提供错误详情'
  if (event.status === 'started') return '节点开始执行'
  if (event.status === 'error') return event.data?.detail || '节点执行异常'

  const data = event.data || {}
  if (event.node === 'safety_screen') {
    const safety = data.safety || {}
    return safety.matched
      ? `命中危险信号：${(safety.matched_terms || []).join('、') || '高风险症状'}`
      : '未命中已配置的危险信号'
  }
  if (event.node === 'extract') {
    const symptoms = data.symptoms?.symptoms
    return Array.isArray(symptoms) && symptoms.length ? symptoms.join('、') : '已完成症状结构化'
  }
  if (event.node === 'retrieve') {
    return `${Array.isArray(data.evidence) ? data.evidence.length : 0} 条医学证据`
  }
  if (event.node === 'classify') {
    const triage = data.triage || {}
    return [triage.department, triage.risk_level ? `${triage.risk_level}风险` : '']
      .filter(Boolean)
      .join(' · ') || '已完成辅助分诊'
  }
  if (event.node === 'compose') {
    return shorten(data.answer?.text) || '已完成回答编排'
  }
  if (event.node === 'ask_followup') {
    return shorten(data.followup?.question) || '已生成主动追问'
  }
  return '节点执行完成'
}

function shorten(value) {
  const text = typeof value === 'string' ? value.trim() : ''
  return text.length > 54 ? `${text.slice(0, 54)}...` : text
}

function elapsedLabel(event) {
  const elapsed = Number(event.elapsed_ms)
  return Number.isFinite(elapsed) ? `${elapsed} ms` : '--'
}

watch(chartMetric, updateChart)

onMounted(async () => {
  resetTrace()
  await refreshMonitor()
  await nextTick()
  initChart()
  healthPollTimer = window.setInterval(refreshMonitor, 30000)
  window.addEventListener('medpilot-settings-changed', updateChart)
})

onBeforeUnmount(() => {
  if (healthPollTimer) window.clearInterval(healthPollTimer)
  chartResizeObserver?.disconnect()
  window.removeEventListener('medpilot-settings-changed', updateChart)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="mon-page">
    <header class="mon-header">
      <div class="mon-heading">
        <span>AGENT ORCHESTRATION</span>
        <h1>智能体运行监控</h1>
        <p>查看模型服务状态与真实问诊保存的智能体执行链路</p>
      </div>

      <div class="mon-header-actions">
        <div class="mon-sample-time">
          <span :class="['mon-live-dot', { 'mon-live-dot-offline': healthError }]" />
          <div>
            <small>最近采样</small>
            <strong>{{ lastUpdatedLabel }}</strong>
          </div>
        </div>
        <el-tooltip content="立即刷新" placement="bottom">
          <el-button circle plain aria-label="刷新运行状态" :loading="monitorLoading" @click="refreshMonitor">
            <el-icon v-if="!monitorLoading"><Refresh /></el-icon>
          </el-button>
        </el-tooltip>
      </div>
    </header>

    <el-alert
      v-if="healthError"
      class="mon-alert"
      type="warning"
      :closable="false"
      show-icon
      :title="healthError"
    >
      <template #default>
        <el-button text type="primary" @click="fetchHealth">重新连接</el-button>
      </template>
    </el-alert>

    <section class="mon-status-grid" aria-label="服务运行状态">
      <article v-for="card in statusCards" :key="card.label" class="mon-status-card">
        <div class="mon-status-topline">
          <span :class="['mon-status-icon', `mon-tone-${card.tone}`]">
            <el-icon :size="20"><component :is="card.icon" /></el-icon>
          </span>
          <el-tag
            size="small"
            effect="plain"
            :type="card.state === '异常' || card.state === '未就绪' ? 'danger' : card.state === '待连接' ? 'info' : 'success'"
          >
            {{ card.state }}
          </el-tag>
        </div>
        <span class="mon-status-label">{{ card.label }}</span>
        <strong class="mon-status-value">
          {{ card.value }}
          <small v-if="card.unit">{{ card.unit }}</small>
        </strong>
        <p :title="card.detail">{{ card.detail }}</p>
      </article>
    </section>

    <section class="mon-trace-inventory" aria-labelledby="trace-inventory-title">
      <div class="mon-panel-heading mon-inventory-heading">
        <div>
          <span>TRACE INVENTORY</span>
          <h2 id="trace-inventory-title">问诊链路清单</h2>
          <p>从持久化 Trace 中定位失败、超时和异常节点</p>
        </div>
        <el-tag size="small" effect="plain" type="info">
          {{ traceListTotal }} 条记录
        </el-tag>
      </div>

      <div class="mon-trace-stat-grid" aria-label="链路失败统计">
        <article v-for="card in traceStatsCards" :key="card.label" class="mon-trace-stat-card">
          <span :class="['mon-trace-stat-icon', `mon-tone-${card.tone}`]">
            <el-icon :size="18"><component :is="card.icon" /></el-icon>
          </span>
          <div>
            <small>{{ card.label }}</small>
            <strong>{{ card.value }}</strong>
            <p>{{ card.detail }}</p>
          </div>
        </article>
      </div>

      <div v-if="topFailureCodes.length" class="mon-failure-codes" aria-label="主要失败码">
        <span>主要失败码</span>
        <el-tag
          v-for="item in topFailureCodes"
          :key="item.code"
          size="small"
          effect="plain"
          type="danger"
        >
          {{ traceFailureLabel(item.code) }} · {{ item.count }}
        </el-tag>
      </div>

      <div class="mon-trace-filters" role="search" aria-label="链路筛选">
        <el-select
          v-model="traceFilterDraft.status"
          class="mon-trace-filter-control"
          clearable
          placeholder="全部状态"
          aria-label="按链路状态筛选"
        >
          <el-option label="全部状态" value="" />
          <el-option label="已完成" value="completed" />
          <el-option label="执行失败" value="failed" />
        </el-select>
        <el-date-picker
          v-model="traceFilterDraft.range"
          class="mon-trace-filter-date"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          :teleported="false"
          aria-label="按时间范围筛选"
        />
        <el-checkbox v-model="traceFilterDraft.timeout" class="mon-timeout-filter">
          仅看超时
        </el-checkbox>
        <div class="mon-filter-actions">
          <el-button type="primary" :loading="traceListLoading || traceStatsLoading" @click="applyTraceFilters">
            <el-icon v-if="!traceListLoading && !traceStatsLoading"><Filter /></el-icon>
            应用筛选
          </el-button>
          <el-button text :disabled="traceListLoading || traceStatsLoading" @click="resetTraceFilters">
            重置
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="traceStatsError"
        class="mon-inventory-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="traceStatsError"
      />
      <el-alert
        v-if="traceListError"
        class="mon-inventory-alert"
        type="error"
        :closable="false"
        show-icon
        :title="traceListError"
      />

      <div class="mon-trace-table-wrap">
        <el-table
          v-if="traceRows.length || traceListLoading"
          v-loading="traceListLoading"
          :data="traceRows"
          row-key="traceId"
          class="mon-trace-table"
          aria-label="问诊链路记录"
          @row-click="openTrace"
        >
          <el-table-column label="创建时间" min-width="146">
            <template #default="{ row }">{{ formatTraceTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="Trace ID" min-width="164">
            <template #default="{ row }">
              <code class="mon-trace-id" :title="row.traceId">{{ compactTraceId(row.traceId, 20) }}</code>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="108">
            <template #default="{ row }">
              <el-tag size="small" effect="light" :type="traceStatusType(row.status)">
                {{ traceStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="终态阶段" min-width="116">
            <template #default="{ row }">{{ tracePhaseLabel(row.terminalPhase) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="92">
            <template #default="{ row }">{{ formatTraceDuration(row.totalDurationMs) }}</template>
          </el-table-column>
          <el-table-column label="失败码" min-width="138">
            <template #default="{ row }">
              <span :class="['mon-failure-cell', { 'mon-failure-cell-muted': !row.failureCode }]">
                {{ traceFailureLabel(row.failureCode) }}
              </span>
              <el-tag v-if="isTraceTimeout(row)" size="small" type="warning" effect="plain">超时</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openTrace(row)">
                <el-icon><Search /></el-icon>
                查看
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无符合筛选条件的链路记录" :image-size="64" />
      </div>

      <div class="mon-trace-pagination">
        <small>
          已展示 {{ traceRows.length }} 条<span v-if="traceListTotal">，共 {{ traceListTotal }} 条</span>
        </small>
        <el-pagination
          v-if="traceListTotal"
          background
          layout="sizes, prev, pager, next"
          :page-sizes="[10, 20, 50]"
          :page-size="traceListSize"
          :current-page="traceListPage + 1"
          :total="traceListTotal"
          @current-change="changeTracePage"
          @size-change="changeTraceSize"
        />
      </div>
    </section>

    <div class="mon-workspace">
      <section class="mon-chart-panel">
        <div class="mon-panel-heading">
          <div>
            <span>LIVE SAMPLES</span>
            <h2>服务采样趋势</h2>
          </div>
          <el-radio-group v-model="chartMetric" size="small" aria-label="趋势指标">
            <el-radio-button value="sessions">活跃会话</el-radio-button>
            <el-radio-button value="docs">知识文档</el-radio-button>
          </el-radio-group>
        </div>
        <div class="mon-chart-stage">
          <div ref="chartElement" class="mon-chart" role="img" aria-label="服务健康采样趋势折线图" />
          <div v-if="healthSamples.length < 2" class="mon-chart-sparse" aria-live="polite">
            <span><el-icon><DataLine /></el-icon></span>
            <div>
              <strong>{{ sparseChartSummary }}</strong>
              <p>累积至少两个采样点后显示变化趋势</p>
            </div>
          </div>
        </div>
        <div class="mon-chart-foot">
          <span><el-icon><Clock /></el-icon>每 30 秒采样一次</span>
          <span>保留最近 {{ Math.max(healthSamples.length, 0) }} / 12 个真实采样点</span>
        </div>
      </section>

      <section class="mon-node-panel">
        <div class="mon-panel-heading mon-node-heading">
          <div>
            <span>EXECUTION NODES</span>
            <h2>智能体节点</h2>
          </div>
          <strong>{{ executionProgress }}%</strong>
        </div>
        <el-progress
          class="mon-node-progress"
          :percentage="executionProgress"
          :show-text="false"
          :stroke-width="5"
        />

        <div class="mon-node-list">
          <div v-for="nodeId in displayNodes" :key="nodeId" class="mon-node-row">
            <span :class="['mon-node-icon', `mon-tone-${nodeInfo(nodeId).tone}`]">
              <el-icon><component :is="nodeInfo(nodeId).icon" /></el-icon>
            </span>
            <div class="mon-node-copy">
              <strong>{{ nodeInfo(nodeId).label }}</strong>
              <small>{{ nodeInfo(nodeId).description }}</small>
            </div>
            <el-tag :type="stateTagType(nodeState(nodeId))" size="small" effect="light">
              <el-icon v-if="nodeState(nodeId) === 'running'" class="mon-spinning"><Loading /></el-icon>
              {{ stateLabel(nodeState(nodeId)) }}
            </el-tag>
          </div>
        </div>
      </section>
    </div>

    <section class="mon-trace-panel">
      <div class="mon-panel-heading mon-trace-heading">
        <div>
          <span>PIPELINE TRACE</span>
          <h2>问诊链路追踪</h2>
        </div>
        <el-tag v-if="traceCompleted" type="success" effect="light">
          <el-icon><CircleCheckFilled /></el-icon>
          追踪记录完整
        </el-tag>
      </div>

      <div class="mon-trace-form">
        <el-input
          v-model="traceId"
          clearable
          :disabled="traceLoading"
          maxlength="64"
          placeholder="输入真实问诊的 trace_id"
          aria-label="问诊追踪编号"
          @keyup.enter="loadTrace"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" :disabled="!traceId.trim()" :loading="traceLoading" @click="loadTrace">
          <el-icon v-if="!traceLoading"><Search /></el-icon>
          {{ traceLoading ? '正在读取' : '读取追踪' }}
        </el-button>
      </div>

      <div v-if="traceState.traceId" class="mon-trace-context">
        <div>
          <small>trace_id</small>
          <code>{{ traceState.traceId }}</code>
        </div>
        <div>
          <small>session_id</small>
          <code>{{ traceState.sessionId }}</code>
        </div>
        <div>
          <small>阶段 / 轮次</small>
          <strong>{{ traceState.phase }} · 第 {{ traceState.turnCount }} 轮</strong>
        </div>
      </div>

      <el-alert
        v-if="traceState.emergency"
        class="mon-emergency-alert"
        type="error"
        :closable="false"
        show-icon
        title="该问诊命中高风险安全规则"
      >
        <p>{{ traceState.emergency.urgency }}</p>
      </el-alert>

      <el-alert
        v-if="traceError"
        class="mon-trace-alert"
        type="error"
        :closable="false"
        show-icon
        :title="traceError"
      />

      <div v-if="!traceEvents.length" class="mon-trace-empty">
        <span><el-icon :size="20"><DataLine /></el-icon></span>
        <div>
          <strong>等待读取追踪记录</strong>
          <p>输入用户问诊产生的 trace_id，查看已保存的真实节点事件与耗时。</p>
        </div>
      </div>
      <div v-else class="mon-event-log" aria-live="polite">
        <div v-for="event in traceEvents" :key="`${event.trace_id}-${event.sequence}`" class="mon-event-row">
          <span
            :class="[
              'mon-event-index',
              {
                'mon-event-index-error': event.type === 'error' || event.status === 'error',
                'mon-event-index-done': event.type === 'done',
              },
            ]"
          >
            <el-icon v-if="event.type === 'error' || event.status === 'error'"><WarningFilled /></el-icon>
            <el-icon v-else-if="event.type === 'done'"><CircleCheckFilled /></el-icon>
            <span v-else>{{ event.sequence }}</span>
          </span>
          <strong>{{ eventLabel(event) }}</strong>
          <code>{{ elapsedLabel(event) }}</code>
          <p>{{ eventSummary(event) }}</p>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.mon-page {
  width: min(100%, 1180px);
  min-width: 0;
  margin: 0 auto;
  display: grid;
  gap: 14px;
  color: #1d2129;
}

.mon-header,
.mon-header-actions,
.mon-sample-time,
.mon-status-topline,
.mon-panel-heading,
.mon-chart-foot,
.mon-node-row,
.mon-trace-form,
.mon-trace-empty,
.mon-event-row {
  display: flex;
  align-items: center;
}

.mon-header {
  justify-content: space-between;
  gap: 24px;
  padding: 4px 2px 8px;
}

.mon-heading > span,
.mon-panel-heading > div > span {
  color: #1677ff;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
}

.mon-heading h1 {
  margin: 5px 0 4px;
  font-size: 24px;
  line-height: 1.3;
  letter-spacing: 0;
}

.mon-heading p {
  margin: 0;
  color: #86909c;
  font-size: 12px;
}

.mon-header-actions {
  flex: 0 0 auto;
  gap: 12px;
}

.mon-sample-time {
  gap: 8px;
}

.mon-live-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #00a870;
  box-shadow: 0 0 0 4px #e8fff7;
}

.mon-live-dot-offline {
  background: #f53f3f;
  box-shadow: 0 0 0 4px #fff0f0;
}

.mon-sample-time small,
.mon-sample-time strong {
  display: block;
  letter-spacing: 0;
}

.mon-sample-time small {
  color: #9aa3ae;
  font-size: 9px;
}

.mon-sample-time strong {
  margin-top: 1px;
  color: #4e5969;
  font-size: 10px;
}

.mon-alert,
.mon-trace-alert,
.mon-emergency-alert {
  border-radius: 8px;
}

.mon-status-grid {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.mon-status-card,
.mon-chart-panel,
.mon-node-panel,
.mon-trace-panel {
  min-width: 0;
  border: 1px solid #e5eaf0;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 4px 14px rgba(31, 45, 61, 0.04);
}

.mon-status-card {
  padding: 15px 16px;
}

.mon-status-topline {
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 11px;
}

.mon-status-icon,
.mon-node-icon,
.mon-trace-empty > span {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 7px;
}

.mon-status-icon {
  width: 36px;
  height: 36px;
}

.mon-tone-blue {
  color: #1677ff;
  background: #e8f3ff;
}

.mon-tone-green {
  color: #00a870;
  background: #e8fff7;
}

.mon-tone-purple {
  color: #7a5af8;
  background: #f1edff;
}

.mon-tone-orange {
  color: #e86f00;
  background: #fff3e8;
}

.mon-tone-red {
  color: #e5484d;
  background: #fff0f0;
}

.mon-status-label {
  color: #86909c;
  font-size: 10px;
}

.mon-status-value {
  display: block;
  margin-top: 3px;
  overflow: hidden;
  font-size: 21px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mon-status-value small {
  margin-left: 3px;
  color: #86909c;
  font-size: 9px;
  font-weight: 500;
}

.mon-status-card > p {
  margin: 3px 0 0;
  overflow: hidden;
  color: #9aa3ae;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mon-workspace {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(320px, 0.8fr);
  gap: 14px;
}

.mon-chart-panel,
.mon-node-panel,
.mon-trace-panel {
  padding: 18px 20px;
}

.mon-panel-heading {
  justify-content: space-between;
  gap: 16px;
}

.mon-panel-heading h2 {
  margin: 3px 0 0;
  font-size: 15px;
  line-height: 1.3;
  letter-spacing: 0;
}

.mon-chart-panel :deep(.el-radio-button__inner) {
  padding: 7px 11px;
  font-size: 10px;
}

.mon-chart {
  width: 100%;
  height: 218px;
  margin-top: 8px;
}

.mon-chart-foot {
  justify-content: space-between;
  gap: 12px;
  padding-top: 7px;
  border-top: 1px solid #edf0f3;
  color: #9aa3ae;
  font-size: 9px;
}

.mon-chart-foot > span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.mon-node-heading > strong {
  color: #1677ff;
  font-size: 13px;
}

.mon-node-progress {
  margin: 12px 0 8px;
}

.mon-node-list {
  display: grid;
}

.mon-node-row {
  min-width: 0;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid #edf0f3;
}

.mon-node-row:last-child {
  border-bottom: 0;
}

.mon-node-icon {
  width: 30px;
  height: 30px;
}

.mon-node-copy {
  min-width: 0;
  flex: 1;
}

.mon-node-copy strong,
.mon-node-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mon-node-copy strong {
  color: #4e5969;
  font-size: 10px;
}

.mon-node-copy small {
  margin-top: 2px;
  color: #9aa3ae;
  font-size: 8px;
}

.mon-node-row :deep(.el-tag) {
  flex: 0 0 auto;
}

.mon-spinning {
  margin-right: 2px;
  animation: mon-spin 0.8s linear infinite;
}

@keyframes mon-spin {
  to {
    transform: rotate(360deg);
  }
}

.mon-trace-heading {
  margin-bottom: 13px;
}

.mon-trace-form {
  gap: 9px;
}

.mon-trace-form > .el-input {
  min-width: 0;
  flex: 1;
}

.mon-trace-form > .el-button {
  min-width: 112px;
  margin: 0;
}

.mon-trace-context {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(150px, 0.7fr);
  gap: 1px;
  margin-top: 12px;
  overflow: hidden;
  border: 1px solid #e5eaf0;
  border-radius: 7px;
  background: #e5eaf0;
}

.mon-trace-context > div {
  min-width: 0;
  padding: 10px 12px;
  background: #fafbfd;
}

.mon-trace-context small,
.mon-trace-context code,
.mon-trace-context strong {
  display: block;
}

.mon-trace-context small {
  color: #9aa3ae;
  font-size: 8px;
}

.mon-trace-context code,
.mon-trace-context strong {
  margin-top: 4px;
  overflow-wrap: anywhere;
  color: #4e5969;
  font-size: 9px;
  line-height: 1.45;
}

.mon-trace-alert,
.mon-emergency-alert {
  margin-top: 11px;
}

.mon-emergency-alert p {
  margin: 4px 0 0;
  font-size: 10px;
}

.mon-trace-empty {
  gap: 12px;
  min-height: 76px;
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px dashed #dce3eb;
  border-radius: 7px;
  background: #fafbfd;
}

.mon-trace-empty > span {
  width: 36px;
  height: 36px;
  color: #1677ff;
  background: #e8f3ff;
}

.mon-trace-empty strong {
  color: #4e5969;
  font-size: 11px;
}

.mon-trace-empty p {
  margin: 3px 0 0;
  color: #9aa3ae;
  font-size: 9px;
}

.mon-event-log {
  margin-top: 12px;
  border-top: 1px solid #edf0f3;
}

.mon-event-row {
  min-width: 0;
  display: grid;
  grid-template-columns: 26px 88px 70px minmax(0, 1fr);
  gap: 10px;
  min-height: 45px;
  border-bottom: 1px solid #edf0f3;
}

.mon-event-row:last-child {
  border-bottom: 0;
}

.mon-event-index {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #1677ff;
  background: #e8f3ff;
  font-size: 9px;
  font-weight: 700;
}

.mon-event-index-done {
  color: #00a870;
  background: #e8fff7;
}

.mon-event-index-error {
  color: #e5484d;
  background: #fff0f0;
}

.mon-event-row > strong {
  overflow: hidden;
  color: #4e5969;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mon-event-row > code {
  color: #7a5af8;
  font-size: 9px;
}

.mon-event-row > p {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: #86909c;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1080px) {
  .mon-status-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mon-workspace {
    grid-template-columns: 1fr;
  }

  .mon-node-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: 18px;
  }
}

@media (max-width: 700px) {
  .mon-header {
    align-items: flex-start;
  }

  .mon-sample-time {
    display: none;
  }

  .mon-status-grid,
  .mon-node-list {
    grid-template-columns: 1fr;
  }

  .mon-panel-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .mon-node-heading,
  .mon-trace-heading {
    flex-direction: row;
  }

  .mon-chart {
    height: 210px;
  }

  .mon-trace-form {
    align-items: stretch;
    flex-direction: column;
  }

  .mon-trace-context {
    grid-template-columns: 1fr;
  }

  .mon-event-row {
    grid-template-columns: 26px 76px 58px minmax(0, 1fr);
    gap: 6px;
  }
}

@media (max-width: 480px) {
  .mon-header {
    flex-direction: column;
  }

  .mon-event-row {
    grid-template-columns: 26px minmax(0, 1fr) 58px;
  }

  .mon-event-row > p {
    grid-column: 2 / -1;
    padding-bottom: 9px;
  }
}

/* Monitoring surfaces share the workspace theme and readable type scale. */
.mon-page {
  gap: 16px;
  color: var(--text-primary, #182230);
}

.mon-heading > span,
.mon-panel-heading > div > span,
.mon-node-heading > strong {
  color: var(--primary, #0f65d8);
  font-size: 12px;
}

.mon-heading p,
.mon-status-label,
.mon-status-card > p,
.mon-chart-foot,
.mon-node-copy small,
.mon-trace-context small,
.mon-trace-empty p,
.mon-event-row > p {
  color: var(--text-muted, #667085);
  font-size: 12px;
}

.mon-sample-time small,
.mon-sample-time strong,
.mon-status-value small,
.mon-chart-panel :deep(.el-radio-button__inner),
.mon-node-copy strong,
.mon-trace-context code,
.mon-trace-context strong,
.mon-emergency-alert p,
.mon-event-index,
.mon-event-row > strong,
.mon-event-row > code {
  font-size: 12px;
}

.mon-sample-time strong,
.mon-node-copy strong,
.mon-trace-context code,
.mon-trace-context strong,
.mon-trace-empty strong,
.mon-event-row > strong {
  color: var(--text-secondary, #475467);
}

.mon-status-card,
.mon-chart-panel,
.mon-node-panel,
.mon-trace-panel {
  border-color: var(--border-default, #dfe4ea);
  background: var(--surface-elevated, #ffffff);
  box-shadow: var(--shadow-card, 0 3px 12px rgba(24, 34, 48, 0.05));
}

.mon-status-card {
  min-height: 158px;
  padding: 18px;
}

.mon-status-icon {
  width: 40px;
  height: 40px;
}

.mon-status-label {
  display: block;
  margin-top: 2px;
}

.mon-status-value {
  margin-top: 6px;
  color: var(--text-primary, #182230);
  font-size: 24px;
}

.mon-status-card > p {
  margin-top: 5px;
}

.mon-tone-blue,
.mon-trace-empty > span,
.mon-event-index {
  color: var(--primary, #0f65d8);
  background: var(--primary-soft, #eaf3ff);
}

.mon-tone-green,
.mon-event-index-done {
  color: var(--success, #087f5b);
  background: var(--success-soft, #eafaf3);
}

.mon-tone-purple {
  color: var(--accent-violet, #7047b8);
  background: var(--accent-violet-soft, #f3edff);
}

.mon-tone-orange {
  color: var(--warning, #b45309);
  background: var(--warning-soft, #fff7e8);
}

.mon-tone-red,
.mon-event-index-error {
  color: var(--danger, #d92d3a);
  background: var(--danger-soft, #fff0f1);
}

.mon-chart-stage {
  position: relative;
  min-width: 0;
}

.mon-chart-sparse {
  position: absolute;
  right: 14px;
  bottom: 22px;
  left: 48px;
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 54px;
  padding: 10px 12px;
  border: 1px solid var(--border-default, #dfe4ea);
  border-radius: var(--radius-md, 6px);
  background: color-mix(in srgb, var(--surface-elevated, #ffffff) 94%, transparent);
  box-shadow: var(--shadow-sm, 0 1px 3px rgba(24, 34, 48, 0.08));
  backdrop-filter: blur(6px);
}

.mon-chart-sparse > span {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: var(--radius-md, 6px);
  background: var(--primary-soft, #eaf3ff);
  color: var(--primary, #0f65d8);
}

.mon-chart-sparse strong,
.mon-chart-sparse p {
  display: block;
  margin: 0;
}

.mon-chart-sparse strong {
  color: var(--text-primary, #182230);
  font-size: 13px;
}

.mon-chart-sparse p {
  margin-top: 2px;
  color: var(--text-muted, #667085);
  font-size: 12px;
}

.mon-chart-foot,
.mon-node-row,
.mon-event-log,
.mon-event-row {
  border-color: var(--border-subtle, #edf0f4);
}

.mon-node-row {
  min-height: 62px;
}

.mon-node-icon {
  width: 36px;
  height: 36px;
}

.mon-node-copy strong {
  font-size: 13px;
}

.mon-node-copy small {
  margin-top: 3px;
}

.mon-trace-context {
  border-color: var(--border-default, #dfe4ea);
  background: var(--border-default, #dfe4ea);
}

.mon-trace-context > div,
.mon-trace-empty {
  background: var(--surface-muted, #f7f9fc);
}

.mon-trace-empty {
  border-color: var(--border-default, #dfe4ea);
}

.mon-event-log {
  position: relative;
  padding-top: 4px;
}

.mon-event-row {
  position: relative;
  grid-template-columns: 34px 116px 74px minmax(0, 1fr);
  gap: 12px;
  min-height: 58px;
  padding: 9px 0;
}

.mon-event-index {
  position: relative;
  z-index: 1;
  width: 26px;
  height: 26px;
}

.mon-event-row:not(:last-child) .mon-event-index::after {
  position: absolute;
  z-index: -1;
  top: 26px;
  bottom: -42px;
  left: 50%;
  width: 1px;
  content: '';
  background: var(--border-default, #dfe4ea);
}

.mon-event-row > strong,
.mon-event-row > p {
  white-space: normal;
}

.mon-event-row > p {
  line-height: 1.55;
}

.mon-event-row > code {
  color: var(--accent-violet, #7047b8);
}

@media (max-width: 700px) {
  .mon-status-card {
    min-height: 0;
  }

  .mon-event-row {
    grid-template-columns: 32px minmax(0, 1fr) 64px;
    gap: 8px;
  }

  .mon-event-row > p {
    grid-column: 2 / -1;
  }
}

@media (max-width: 480px) {
  .mon-chart-sparse {
    right: 8px;
    left: 40px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .mon-spinning,
  .mon-live-dot {
    animation: none;
  }
}

/* Unified deep-space monitoring workspace. */
.mon-page {
  gap: 16px;
  color: var(--text-primary);
}

.mon-live-dot {
  background: var(--success);
  box-shadow: 0 0 0 3px var(--success-soft), 0 0 12px color-mix(in srgb, var(--success) 58%, transparent);
  animation: mon-live-pulse 2.2s ease-in-out infinite;
}

.mon-live-dot-offline {
  background: var(--danger);
  box-shadow: 0 0 0 3px var(--danger-soft), 0 0 12px color-mix(in srgb, var(--danger) 58%, transparent);
}

.mon-status-grid {
  position: relative;
  isolation: isolate;
  gap: 0;
  padding: 7px;
  overflow: hidden;
  border-radius: var(--radius-lg);
  background:
    linear-gradient(108deg, rgba(88, 190, 255, 0.075), transparent 44%, rgba(187, 124, 255, 0.055)),
    var(--glass-surface, rgba(12, 28, 55, 0.66));
  box-shadow: inset 0 1px 0 rgba(218, 243, 255, 0.09), 0 13px 32px rgba(0, 3, 14, 0.17);
  backdrop-filter: blur(18px) saturate(132%);
}

.mon-status-grid::before,
.mon-chart-panel::before,
.mon-node-panel::before,
.mon-trace-panel::before {
  position: absolute;
  top: 0;
  right: 4%;
  left: 4%;
  z-index: 0;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(94, 210, 255, 0.43), rgba(189, 127, 255, 0.33), transparent);
  pointer-events: none;
}

.mon-status-card {
  position: relative;
  z-index: 1;
  min-height: 130px;
  padding: 14px 16px;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  box-shadow: none;
  transition: background 0.18s ease, transform 0.18s ease;
}

.mon-status-card:hover {
  background: rgba(100, 183, 243, 0.07);
  transform: translateY(-1px);
}

.mon-chart-panel,
.mon-node-panel,
.mon-trace-panel {
  position: relative;
  isolation: isolate;
  border: 0;
  background:
    linear-gradient(128deg, rgba(86, 187, 250, 0.065), transparent 42%, rgba(184, 120, 255, 0.05)),
    var(--glass-surface, rgba(12, 28, 55, 0.66));
  box-shadow: inset 0 1px 0 rgba(217, 243, 255, 0.085), 0 14px 34px rgba(0, 3, 14, 0.18);
  backdrop-filter: blur(18px) saturate(132%);
}

.mon-status-icon,
.mon-node-icon,
.mon-trace-empty > span,
.mon-chart-sparse > span {
  box-shadow: inset 0 1px 0 rgba(225, 247, 255, 0.15), 0 0 15px rgba(91, 191, 252, 0.08);
}

.mon-chart-foot,
.mon-node-row,
.mon-event-log,
.mon-event-row {
  border: 0;
}

.mon-chart-foot {
  box-shadow: inset 0 1px 0 var(--border-subtle);
}

.mon-node-row {
  position: relative;
}

.mon-node-row:not(:last-child)::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 46px;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, var(--border-subtle), rgba(184, 124, 255, 0.09), transparent);
}

.mon-trace-context {
  gap: 8px;
  overflow: visible;
  border: 0;
  background: transparent;
}

.mon-trace-context > div {
  border-radius: var(--radius-md);
  background: rgba(91, 156, 216, 0.07);
  box-shadow: inset 0 1px 0 rgba(215, 242, 255, 0.07);
}

.mon-trace-empty,
.mon-chart-sparse {
  border: 0;
  background:
    linear-gradient(110deg, rgba(88, 188, 251, 0.09), rgba(185, 122, 255, 0.055)),
    rgba(5, 16, 35, 0.48);
  box-shadow: inset 0 1px 0 rgba(217, 243, 255, 0.09), 0 10px 26px rgba(0, 3, 14, 0.16);
}

.mon-event-log {
  position: relative;
  padding-top: 7px;
}

.mon-event-row {
  border-radius: var(--radius-sm);
  transition: background 0.16s ease;
}

.mon-event-row:hover {
  background: rgba(99, 180, 241, 0.055);
}

.mon-event-row:not(:last-child) .mon-event-index::after {
  background: linear-gradient(var(--primary-subtle), var(--border-subtle));
}

.mon-node-progress :deep(.el-progress-bar__outer) {
  background: rgba(104, 167, 225, 0.1);
}

.mon-page :deep(.el-tag) {
  background: color-mix(in srgb, currentColor 11%, transparent);
  border-color: color-mix(in srgb, currentColor 30%, transparent);
}

.mon-alert,
.mon-trace-alert,
.mon-emergency-alert {
  border: 0;
}

@keyframes mon-live-pulse {
  0%,
  100% {
    opacity: 0.72;
    transform: scale(0.92);
  }

  50% {
    opacity: 1;
    transform: scale(1);
  }
}

@media (max-width: 700px) {
  .mon-status-grid {
    gap: 2px;
  }

  .mon-status-card {
    min-height: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .mon-live-dot,
  .mon-status-card,
  .mon-event-row {
    animation: none;
    transition: none;
  }
}

@supports not ((backdrop-filter: blur(1px))) {
  .mon-status-grid,
  .mon-chart-panel,
  .mon-node-panel,
  .mon-trace-panel,
  .mon-trace-inventory {
    background: var(--surface-base);
  }
}

/* Trace inventory keeps operational filtering close to the persisted evidence. */
.mon-trace-inventory {
  position: relative;
  isolation: isolate;
  min-width: 0;
  padding: 18px 20px;
  overflow: hidden;
  border: 0;
  border-radius: var(--radius-lg);
  background:
    linear-gradient(128deg, rgba(86, 187, 250, 0.065), transparent 42%, rgba(184, 120, 255, 0.05)),
    var(--glass-surface, rgba(12, 28, 55, 0.66));
  box-shadow: inset 0 1px 0 rgba(217, 243, 255, 0.085), 0 14px 34px rgba(0, 3, 14, 0.18);
  backdrop-filter: blur(18px) saturate(132%);
}

.mon-trace-inventory::before {
  position: absolute;
  top: 0;
  right: 4%;
  left: 4%;
  z-index: 0;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(94, 210, 255, 0.43), rgba(189, 127, 255, 0.33), transparent);
  pointer-events: none;
}

.mon-inventory-heading {
  position: relative;
  z-index: 1;
}

.mon-inventory-heading p {
  margin: 5px 0 0;
  color: var(--text-muted, #667085);
  font-size: 12px;
}

.mon-trace-stat-grid {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-top: 16px;
}

.mon-trace-stat-card {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  border: 1px solid rgba(122, 182, 231, 0.13);
  border-radius: var(--radius-md, 6px);
  background: rgba(91, 156, 216, 0.065);
}

.mon-trace-stat-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: var(--radius-md, 6px);
}

.mon-trace-stat-card > div {
  min-width: 0;
}

.mon-trace-stat-card small,
.mon-trace-stat-card strong,
.mon-trace-stat-card p {
  display: block;
}

.mon-trace-stat-card small {
  color: var(--text-muted, #667085);
  font-size: 11px;
}

.mon-trace-stat-card strong {
  margin-top: 3px;
  color: var(--text-primary, #182230);
  font-size: 22px;
  line-height: 1.1;
}

.mon-trace-stat-card p {
  margin: 5px 0 0;
  overflow: hidden;
  color: var(--text-muted, #667085);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mon-failure-codes {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 13px;
}

.mon-failure-codes > span {
  margin-right: 3px;
  color: var(--text-muted, #667085);
  font-size: 12px;
}

.mon-trace-filters {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(150px, 0.65fr) minmax(260px, 1.25fr) auto minmax(190px, auto);
  align-items: center;
  gap: 9px;
  margin-top: 16px;
  padding: 10px;
  border: 1px solid rgba(122, 182, 231, 0.14);
  border-radius: var(--radius-md, 6px);
  background: rgba(4, 16, 36, 0.28);
}

.mon-trace-filter-control,
.mon-trace-filter-date {
  width: 100%;
  min-width: 0;
}

.mon-timeout-filter {
  margin: 0 5px;
  color: var(--text-secondary, #475467);
  white-space: nowrap;
}

.mon-filter-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  white-space: nowrap;
}

.mon-filter-actions :deep(.el-button) {
  margin: 0;
}

.mon-inventory-alert {
  position: relative;
  z-index: 1;
  margin-top: 10px;
}

.mon-trace-table-wrap {
  position: relative;
  z-index: 1;
  min-width: 0;
  margin-top: 13px;
  overflow-x: auto;
  border: 1px solid rgba(122, 182, 231, 0.14);
  border-radius: var(--radius-md, 6px);
}

.mon-trace-table {
  min-width: 850px;
  --el-table-border-color: rgba(122, 182, 231, 0.13);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: rgba(91, 156, 216, 0.065);
  --el-table-row-hover-bg-color: rgba(99, 180, 241, 0.08);
  --el-table-text-color: var(--text-secondary, #475467);
  --el-table-header-text-color: var(--text-muted, #667085);
}

.mon-trace-table :deep(.el-table__inner-wrapper::before) {
  background-color: rgba(122, 182, 231, 0.13);
}

.mon-trace-table :deep(.el-table__header-wrapper th.el-table__cell),
.mon-trace-table :deep(.el-table__body-wrapper td.el-table__cell) {
  background: transparent;
  border-bottom-color: rgba(122, 182, 231, 0.13);
}

.mon-trace-table :deep(.el-table__header-wrapper th.el-table__cell) {
  height: 42px;
  font-size: 11px;
  font-weight: 600;
}

.mon-trace-table :deep(.el-table__body-wrapper td.el-table__cell) {
  height: 52px;
  font-size: 12px;
}

.mon-trace-table :deep(.el-table__row) {
  cursor: pointer;
  transition: background 0.16s ease;
}

.mon-trace-table :deep(.el-table__empty-text) {
  color: var(--text-muted, #667085);
}

.mon-trace-id {
  color: var(--accent-violet, #7047b8);
  font-size: 11px;
}

.mon-failure-cell {
  display: inline-block;
  max-width: 150px;
  overflow: hidden;
  margin-right: 5px;
  color: var(--danger, #d92d3a);
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
}

.mon-failure-cell-muted {
  color: var(--text-muted, #667085);
}

.mon-trace-pagination {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 13px;
}

.mon-trace-pagination > small {
  color: var(--text-muted, #667085);
  font-size: 11px;
}

.mon-trace-pagination :deep(.el-pagination) {
  margin-left: auto;
}

@media (max-width: 1040px) {
  .mon-trace-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mon-trace-filters {
    grid-template-columns: minmax(150px, 0.7fr) minmax(260px, 1.3fr) auto;
  }

  .mon-filter-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 700px) {
  .mon-trace-inventory {
    padding: 16px 14px;
  }

  .mon-trace-stat-grid,
  .mon-trace-filters {
    grid-template-columns: 1fr;
  }

  .mon-timeout-filter {
    margin: 2px 0;
  }

  .mon-filter-actions {
    justify-content: flex-start;
  }

  .mon-trace-pagination {
    align-items: flex-start;
    flex-direction: column;
  }

  .mon-trace-pagination :deep(.el-pagination) {
    margin-left: 0;
  }
}
</style>
