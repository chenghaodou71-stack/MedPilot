<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import client from '../api/client'
import { use } from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import * as echarts from 'echarts/core'
import {
  ArrowRight,
  ChatDotRound,
  Clock,
  Collection,
  Document,
  FirstAidKit,
  RefreshRight,
  WarningFilled,
} from '@element-plus/icons-vue'
import { formatAverageDuration } from '../lib/monitorMetrics'

use([LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const router = useRouter()
const auth = useAuthStore()
const records = ref([])
const knowledgeStats = ref(null)
const knowledgeStatsUnavailable = ref(false)
const monitorStats = ref(null)
const monitorStatsUnavailable = ref(false)
const recordTotal = ref(0)
const loading = ref(true)
const loadError = ref('')
const trendChartElement = ref(null)
const departmentChartElement = ref(null)
const prefersReducedMotion = ref(false)

let trendChart = null
let departmentChart = null
let chartResizeObserver = null
let chartRenderFrame = null
let motionMediaQuery = null
let isViewMounted = false

const displayName = computed(() => (auth.username === 'admin' ? '管理员' : auth.username || '用户'))

const thisMonthCount = computed(() => {
  const now = new Date()
  return records.value.filter((record) => {
    const date = new Date(record.createdAt)
    return date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth()
  }).length
})

const highRiskCount = computed(
  () => records.value.filter((record) => record.riskLevel === '高').length,
)

const latestRecords = computed(() => records.value.slice(0, 5))

const lastSevenDays = computed(() => {
  const days = []
  const today = new Date()
  for (let offset = 6; offset >= 0; offset -= 1) {
    const date = new Date(today)
    date.setHours(0, 0, 0, 0)
    date.setDate(today.getDate() - offset)
    const nextDate = new Date(date)
    nextDate.setDate(date.getDate() + 1)
    days.push({
      label: `${date.getMonth() + 1}/${date.getDate()}`,
      count: records.value.filter((record) => {
        const createdAt = new Date(record.createdAt)
        return createdAt >= date && createdAt < nextDate
      }).length,
    })
  }
  return days
})

const departmentDistribution = computed(() => {
  const counts = new Map()
  records.value.forEach((record) => {
    const department = record.department?.trim()
    if (department) counts.set(department, (counts.get(department) || 0) + 1)
  })
  return [...counts.entries()]
    .map(([name, value]) => ({ name, value }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 6)
})

const departmentCount = computed(() => new Set(
  records.value
    .map((record) => record.department?.trim())
    .filter(Boolean),
).size)

const averageDuration = computed(() => formatAverageDuration(monitorStats.value?.averageDurationMs))

const metrics = computed(() => [
  {
    label: '问诊总量',
    value: recordTotal.value,
    suffix: '次',
    detail: `本月新增 ${thisMonthCount.value} 次`,
    icon: ChatDotRound,
    tone: 'blue',
  },
  {
    label: '知识库文档',
    value: knowledgeStatsUnavailable.value ? '--' : knowledgeStats.value?.total_docs ?? 0,
    suffix: '篇',
    detail: knowledgeStatsUnavailable.value
      ? '统计服务暂不可用'
      : `${knowledgeStats.value?.total_chunks ?? 0} 个向量分片`,
    icon: Collection,
    tone: 'green',
  },
  {
    label: '覆盖科室',
    value: departmentCount.value,
    suffix: '个',
    detail: departmentDistribution.value[0]?.name || '暂无问诊数据',
    icon: FirstAidKit,
    tone: 'purple',
  },
  {
    label: '平均响应时间',
    value: monitorStatsUnavailable.value ? '--' : averageDuration.value.value,
    suffix: monitorStatsUnavailable.value ? '' : averageDuration.value.suffix,
    detail: monitorStatsUnavailable.value
      ? '监控统计暂不可用'
      : monitorStats.value?.completedTraces
        ? `${monitorStats.value.completedTraces} 条完成链路`
        : '暂无已完成链路',
    icon: Clock,
    tone: 'gold',
  },
])

async function fetchDashboard() {
  loading.value = true
  loadError.value = ''
  try {
    const [recordResponse, knowledgeResponse, monitorResponse] = await Promise.all([
      client.get('/records', { params: { page: 0, size: 100 } }),
      client.get('/knowledge/stats').catch(() => null),
      client.get('/monitor/stats').catch(() => null),
    ])
    records.value = recordResponse.data?.data || []
    recordTotal.value = Number(recordResponse.data?.meta?.total) || records.value.length
    knowledgeStatsUnavailable.value = knowledgeResponse === null
    knowledgeStats.value = knowledgeResponse?.data?.data || null
    monitorStatsUnavailable.value = monitorResponse === null
    monitorStats.value = monitorResponse?.data?.data || null
  } catch (error) {
    records.value = []
    knowledgeStats.value = null
    knowledgeStatsUnavailable.value = true
    monitorStats.value = null
    monitorStatsUnavailable.value = true
    recordTotal.value = 0
    loadError.value = '暂时无法加载工作台数据，请稍后刷新。'
  } finally {
    loading.value = false
    await nextTick()
    if (isViewMounted) renderCharts()
  }
}

function getChartTheme() {
  const styles = getComputedStyle(document.documentElement)
  const token = (name) => styles.getPropertyValue(name).trim()

  return {
    primary: '#65c8ff',
    primarySoft: 'rgba(60, 154, 255, 0.28)',
    success: '#5eead4',
    warning: '#ffc56f',
    danger: '#ff667e',
    teal: '#4de1ef',
    violet: '#be8cff',
    surfaceElevated: 'rgba(7, 13, 34, 0.94)',
    surfaceMuted: 'rgba(17, 32, 65, 0.72)',
    surfaceSubtle: 'rgba(72, 104, 158, 0.24)',
    textPrimary: '#f2f7ff',
    textSecondary: '#c1cee6',
    textMuted: '#7f92b7',
    borderDefault: 'rgba(114, 190, 255, 0.34)',
    borderSubtle: 'rgba(91, 145, 208, 0.13)',
    shadow: '0 14px 38px rgba(0, 0, 0, 0.42)',
    fontFamily: token('--el-font-family'),
  }
}

function getTooltipOptions(theme) {
  return {
    backgroundColor: theme.surfaceElevated,
    borderColor: theme.borderDefault,
    borderWidth: 0,
    padding: [9, 12],
    textStyle: {
      color: theme.textPrimary,
      fontFamily: theme.fontFamily,
      fontSize: 12,
    },
    extraCssText: `box-shadow: ${theme.shadow}; border-radius: 8px; backdrop-filter: blur(16px);`,
  }
}

function renderCharts() {
  const theme = getChartTheme()
  const animationEnabled = !prefersReducedMotion.value

  if (trendChartElement.value) {
    trendChart ||= echarts.init(trendChartElement.value)
    trendChart.setOption({
      animation: animationEnabled,
      animationDuration: animationEnabled ? 420 : 0,
      color: [theme.primary],
      textStyle: { fontFamily: theme.fontFamily },
      tooltip: {
        ...getTooltipOptions(theme),
        trigger: 'axis',
        axisPointer: { lineStyle: { color: theme.borderDefault } },
        valueFormatter: (value) => `${value} 次`,
      },
      grid: { left: 8, right: 18, top: 22, bottom: 8, containLabel: true },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: lastSevenDays.value.map((item) => item.label),
        axisLine: { lineStyle: { color: theme.borderSubtle } },
        axisTick: { show: false },
        axisLabel: { color: theme.textMuted, fontSize: 12, margin: 12 },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: theme.borderSubtle } },
        axisLabel: { color: theme.textMuted, fontSize: 12 },
      },
      series: [
        {
          type: 'line',
          data: lastSevenDays.value.map((item) => item.count),
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: {
            width: 3.5,
            color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
              { offset: 0, color: theme.primary },
              { offset: 0.48, color: theme.violet },
              { offset: 1, color: theme.teal },
            ]),
            shadowBlur: 16,
            shadowColor: theme.primary,
          },
          itemStyle: {
            color: theme.primary,
            borderWidth: 2,
            borderColor: '#dff7ff',
          },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(85, 168, 255, 0.32)' },
                { offset: 0.55, color: 'rgba(139, 92, 246, 0.12)' },
                { offset: 1, color: 'rgba(6, 13, 34, 0)' },
              ],
            },
            opacity: 1,
          },
          emphasis: { scale: animationEnabled },
        },
      ],
    }, { notMerge: true })
  }

  if (departmentChartElement.value) {
    const hasDepartmentData = departmentDistribution.value.length > 0
    const compactLayout = departmentChartElement.value.clientWidth < 520
    const legend = compactLayout
      ? {
          show: hasDepartmentData,
          type: 'scroll',
          orient: 'horizontal',
          left: 'center',
          right: 'center',
          bottom: 0,
          width: '92%',
          itemWidth: 9,
          itemHeight: 9,
          itemGap: 14,
          pageIconColor: theme.primary,
          pageIconInactiveColor: theme.textMuted,
          pageTextStyle: { color: theme.textMuted, fontSize: 12 },
          textStyle: { color: theme.textSecondary, fontSize: 12 },
        }
      : {
          show: hasDepartmentData,
          orient: 'vertical',
          right: 4,
          top: 'middle',
          itemWidth: 9,
          itemHeight: 9,
          itemGap: 14,
          textStyle: { color: theme.textSecondary, fontSize: 12 },
        }

    departmentChart ||= echarts.init(departmentChartElement.value)
    departmentChart.setOption({
      animation: animationEnabled,
      animationDuration: animationEnabled ? 420 : 0,
      color: [
        theme.primary,
        theme.success,
        theme.violet,
        theme.warning,
        theme.danger,
        theme.teal,
      ],
      textStyle: { fontFamily: theme.fontFamily },
      tooltip: {
        ...getTooltipOptions(theme),
        show: hasDepartmentData,
        trigger: 'item',
        formatter: '{b}<br/>{c} 次 ({d}%)',
      },
      legend,
      series: [
        {
          type: 'pie',
          radius: compactLayout ? ['38%', '59%'] : ['49%', '72%'],
          center: compactLayout ? ['50%', '40%'] : ['34%', '50%'],
          avoidLabelOverlap: true,
          itemStyle: {
            borderColor: 'rgba(7, 14, 36, 0.86)',
            borderWidth: 3,
            shadowBlur: 13,
            shadowColor: 'rgba(96, 197, 255, 0.22)',
          },
          label: hasDepartmentData
            ? { show: false }
            : {
                show: true,
                position: 'center',
                formatter: '暂无数据',
                color: theme.textMuted,
                fontSize: 13,
              },
          emphasis: { scale: animationEnabled, scaleSize: animationEnabled ? 4 : 0 },
          data: hasDepartmentData
            ? departmentDistribution.value
            : [{ name: '暂无数据', value: 1, itemStyle: { color: theme.surfaceSubtle } }],
        },
      ],
    }, { notMerge: true })
  }
}

function resizeCharts() {
  trendChart?.resize()
  departmentChart?.resize()
}

function scheduleChartRender() {
  if (chartRenderFrame !== null) cancelAnimationFrame(chartRenderFrame)
  chartRenderFrame = requestAnimationFrame(() => {
    chartRenderFrame = null
    resizeCharts()
    renderCharts()
  })
}

function observeChartSizes() {
  if ('ResizeObserver' in window) {
    chartResizeObserver = new ResizeObserver(scheduleChartRender)
    if (trendChartElement.value) chartResizeObserver.observe(trendChartElement.value)
    if (departmentChartElement.value) chartResizeObserver.observe(departmentChartElement.value)
    return
  }

  window.addEventListener('resize', scheduleChartRender)
}

function handleWorkspaceSettingsChanged() {
  scheduleChartRender()
}

function handleMotionPreferenceChange(event) {
  prefersReducedMotion.value = event.matches
  scheduleChartRender()
}

function riskType(level) {
  return { 高: 'danger', 中: 'warning', 低: 'success' }[level] || 'info'
}

function formatDate(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

onMounted(() => {
  isViewMounted = true
  motionMediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  prefersReducedMotion.value = motionMediaQuery.matches
  motionMediaQuery.addEventListener('change', handleMotionPreferenceChange)
  window.addEventListener('medpilot-settings-changed', handleWorkspaceSettingsChanged)
  observeChartSizes()
  fetchDashboard()
})

onBeforeUnmount(() => {
  isViewMounted = false
  window.removeEventListener('medpilot-settings-changed', handleWorkspaceSettingsChanged)
  window.removeEventListener('resize', scheduleChartRender)
  motionMediaQuery?.removeEventListener('change', handleMotionPreferenceChange)
  chartResizeObserver?.disconnect()
  if (chartRenderFrame !== null) cancelAnimationFrame(chartRenderFrame)
  trendChart?.dispose()
  departmentChart?.dispose()
})
</script>

<template>
  <div class="dashboard-page">
    <div class="dashboard-atmosphere" aria-hidden="true">
      <svg viewBox="0 0 1440 980" preserveAspectRatio="none" focusable="false">
        <defs>
          <linearGradient id="dashboard-flow-cyan" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#5bc8ff" stop-opacity="0" />
            <stop offset="0.42" stop-color="#5bc8ff" stop-opacity=".72" />
            <stop offset=".74" stop-color="#a780ff" stop-opacity=".52" />
            <stop offset="1" stop-color="#5eead4" stop-opacity="0" />
          </linearGradient>
          <linearGradient id="dashboard-flow-gold" x1="0" y1="1" x2="1" y2="0">
            <stop offset="0" stop-color="#ffca73" stop-opacity="0" />
            <stop offset=".52" stop-color="#ffca73" stop-opacity=".46" />
            <stop offset="1" stop-color="#ff6f91" stop-opacity="0" />
          </linearGradient>
        </defs>
        <g class="atmosphere-filaments">
          <path d="M-90 188 C210 16 302 346 574 194 S958 6 1540 226" />
          <path d="M-60 706 C184 480 356 826 620 618 S1064 430 1504 724" />
          <path d="M246 -30 C402 184 230 364 468 516 S760 746 626 1030" />
          <path d="M1090 -34 C900 168 1136 300 920 508 S808 820 1170 1010" />
        </g>
        <g class="atmosphere-orbits">
          <path d="M-120 536 C224 174 506 218 770 476 S1178 810 1540 458" />
          <path d="M-80 608 C236 354 410 352 700 536 S1180 646 1510 320" />
          <path d="M232 924 C438 594 680 386 1054 216 S1328 66 1510 54" />
        </g>
        <g class="atmosphere-gold">
          <path d="M928 930 C1050 750 1138 732 1230 602 S1354 442 1512 496" />
        </g>
        <g class="atmosphere-nodes">
          <circle cx="112" cy="213" r="3" />
          <circle cx="276" cy="512" r="2" />
          <circle cx="424" cy="168" r="4" />
          <circle cx="632" cy="612" r="3" />
          <circle cx="788" cy="292" r="2" />
          <circle cx="966" cy="524" r="4" />
          <circle cx="1158" cy="164" r="3" />
          <circle cx="1262" cy="716" r="2" />
          <circle cx="1380" cy="382" r="3" />
        </g>
      </svg>
    </div>

    <header class="dashboard-heading">
      <div class="dashboard-heading-copy">
        <span class="dashboard-kicker"><i />智能中枢 / CLINICAL INTELLIGENCE</span>
        <h1>医疗智能工作台</h1>
        <p>您好，{{ displayName }}。这里汇总问诊、风险与知识库运行数据。</p>
      </div>
      <div class="dashboard-actions">
        <el-button :loading="loading" @click="fetchDashboard">
          <el-icon><RefreshRight /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" @click="router.push('/consult')">
          <el-icon><ChatDotRound /></el-icon>
          发起问诊
        </el-button>
      </div>
    </header>

    <el-alert v-if="loadError" :title="loadError" type="warning" :closable="false" show-icon />

    <section class="dashboard-metrics" aria-label="关键指标">
      <svg class="metric-data-stream" viewBox="0 0 1200 190" preserveAspectRatio="none" aria-hidden="true">
        <path class="metric-stream-glow" d="M-24 102 C108 44 190 36 292 101 S470 162 578 91 S770 30 886 94 S1070 158 1224 72" />
        <path class="metric-stream-core" d="M-24 102 C108 44 190 36 292 101 S470 162 578 91 S770 30 886 94 S1070 158 1224 72" />
        <path class="metric-stream-thread" d="M-12 124 C126 76 202 58 302 117 S466 142 582 75 S758 48 888 112 S1070 132 1216 54" />
      </svg>
      <article
        v-for="(metric, index) in metrics"
        :key="metric.label"
        class="dashboard-metric"
        :class="metric.tone"
      >
        <span class="dashboard-metric-index">0{{ index + 1 }}</span>
        <div>
          <span>{{ metric.label }}</span>
          <strong>{{ loading ? '--' : metric.value }}<small>{{ metric.suffix }}</small></strong>
          <p>{{ metric.detail }}</p>
        </div>
        <span class="dashboard-metric-icon" :class="metric.tone">
          <el-icon :size="21"><component :is="metric.icon" /></el-icon>
        </span>
      </article>
    </section>

    <aside
      class="dashboard-risk-status"
      role="status"
      :aria-label="`高风险提醒 ${highRiskCount} 次`"
    >
      <span class="risk-orb-core">
        <span class="risk-orb-highlight" />
        <el-icon :size="21"><WarningFilled /></el-icon>
        <strong>{{ loading ? '--' : highRiskCount }}</strong>
      </span>
      <span class="risk-orb-copy">
        <strong>风险状态</strong>
        <small>{{ highRiskCount ? '建议重点关注' : '当前运行平稳' }}</small>
      </span>
    </aside>

    <div class="dashboard-charts">
      <section class="dashboard-panel trend-panel">
        <div class="dashboard-panel-heading">
          <div>
            <span>近 7 天</span>
            <h2>问诊趋势</h2>
          </div>
          <el-tag effect="plain">共 {{ records.length }} 次</el-tag>
        </div>
        <div
          ref="trendChartElement"
          class="dashboard-chart"
          role="img"
          aria-label="近七天问诊趋势图"
        />
      </section>

      <section class="dashboard-panel department-panel">
        <div class="dashboard-panel-heading">
          <div>
            <span>科室统计</span>
            <h2>分诊分布</h2>
          </div>
        </div>
        <div
          ref="departmentChartElement"
          class="dashboard-chart"
          role="img"
          aria-label="分诊科室分布图"
        />
      </section>
    </div>

    <section class="dashboard-panel recent-dashboard-panel">
      <div class="dashboard-panel-heading">
        <div>
          <span>实时数据</span>
          <h2>最近问诊</h2>
        </div>
        <el-button text type="primary" @click="router.push('/records')">
          查看全部<el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>

      <el-skeleton v-if="loading" :rows="4" :animated="!prefersReducedMotion" />
      <el-empty v-else-if="!latestRecords.length" :image-size="72" description="暂无问诊记录" />
      <template v-else>
        <el-table
          :data="latestRecords"
          class="dashboard-table"
          @row-click="(row) => router.push(`/records/${row.id}`)"
        >
          <el-table-column label="症状摘要" min-width="220">
            <template #default="{ row }">
              <div class="symptom-cell">
                <span><el-icon><Document /></el-icon></span>
                <strong>{{ row.symptoms || '问诊记录' }}</strong>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="department" label="推荐科室" min-width="130">
            <template #default="{ row }">{{ row.department || '--' }}</template>
          </el-table-column>
          <el-table-column label="风险等级" width="110">
            <template #default="{ row }">
              <el-tag :type="riskType(row.riskLevel)" effect="light" size="small">
                {{ row.riskLevel || '未知' }}风险
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="问诊时间" width="130">
            <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column width="44">
            <template #default><el-icon class="row-arrow"><ArrowRight /></el-icon></template>
          </el-table-column>
        </el-table>

        <div class="dashboard-mobile-records" aria-label="最近问诊列表">
          <button
            v-for="record in latestRecords"
            :key="record.id"
            type="button"
            class="dashboard-mobile-record"
            @click="router.push(`/records/${record.id}`)"
          >
            <span class="dashboard-mobile-record-main">
              <span class="mobile-record-icon"><el-icon><Document /></el-icon></span>
              <span>
                <strong>{{ record.symptoms || '问诊记录' }}</strong>
                <small>{{ record.department || '暂未推荐科室' }}</small>
              </span>
            </span>
            <span class="dashboard-mobile-record-meta">
              <el-tag :type="riskType(record.riskLevel)" effect="light" size="small">
                {{ record.riskLevel || '未知' }}风险
              </el-tag>
              <time>{{ formatDate(record.createdAt) }}</time>
              <el-icon class="row-arrow"><ArrowRight /></el-icon>
            </span>
          </button>
        </div>
      </template>
    </section>

  </div>
</template>

<style scoped>
@property --dashboard-beam-angle {
  syntax: "<angle>";
  inherits: false;
  initial-value: 0deg;
}

.dashboard-page {
  width: min(100%, 1260px);
  min-width: 0;
  margin: 0 auto;
  color: var(--text-primary);
}

.dashboard-heading,
.dashboard-actions,
.dashboard-panel-heading,
.symptom-cell,
.dashboard-mobile-record-main,
.dashboard-mobile-record-meta {
  display: flex;
  align-items: center;
}

.dashboard-heading {
  justify-content: space-between;
  gap: 22px;
  margin-bottom: 18px;
}

.dashboard-heading > div:first-child > span,
.dashboard-panel-heading > div > span {
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
}

.dashboard-heading h1 {
  margin: 5px 0 4px;
  color: var(--text-primary);
  font-size: 24px;
  line-height: 1.3;
  letter-spacing: 0;
}

.dashboard-heading p {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.dashboard-actions {
  gap: 8px;
}

.dashboard-actions :deep(.el-button),
.dashboard-panel-heading :deep(.el-button) {
  min-height: 36px;
  border-radius: var(--radius-md);
  font-size: 13px;
}

.dashboard-page > .el-alert {
  margin-bottom: 16px;
  border-radius: var(--radius-lg);
}

.dashboard-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.dashboard-metric,
.dashboard-panel {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-elevated);
  box-shadow: var(--shadow-card);
}

.dashboard-metric {
  --metric-tone: var(--primary);
  --metric-soft: var(--primary-soft);

  position: relative;
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 18px 17px;
  overflow: hidden;
  border-top: 2px solid var(--metric-tone);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.dashboard-metric::after {
  position: absolute;
  top: -1px;
  left: -38%;
  width: 34%;
  height: 3px;
  background: linear-gradient(
    90deg,
    transparent,
    var(--metric-soft),
    var(--metric-tone),
    transparent
  );
  content: "";
  filter: drop-shadow(0 0 5px var(--metric-tone));
  pointer-events: none;
  animation: dashboard-metric-sheen 4.8s ease-in-out infinite;
}

.dashboard-metric:nth-child(2)::after {
  animation-delay: -1.2s;
}

.dashboard-metric:nth-child(3)::after {
  animation-delay: -2.4s;
}

.dashboard-metric:nth-child(4)::after {
  animation-delay: -3.6s;
}

.dashboard-metric.green {
  --metric-tone: var(--success);
  --metric-soft: var(--success-soft);
}

.dashboard-metric.purple {
  --metric-tone: var(--accent-violet);
  --metric-soft: var(--accent-violet-soft);
}

.dashboard-metric.gold {
  --metric-tone: var(--warning);
  --metric-soft: var(--warning-soft);
}

.dashboard-metric > div > span {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}

.dashboard-metric strong {
  display: block;
  margin-top: 8px;
  color: var(--text-primary);
  font-size: 28px;
  line-height: 1.1;
  letter-spacing: 0;
}

.dashboard-metric strong small {
  margin-left: 4px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}

.dashboard-metric p {
  margin: 8px 0 0;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-metric-icon,
.symptom-cell > span {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: var(--radius-md);
}

.dashboard-metric-icon {
  width: 40px;
  height: 40px;
  background: var(--metric-soft);
  color: var(--metric-tone);
}

.dashboard-metric.gold .dashboard-metric-icon {
  animation: none;
}

.dashboard-charts {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(320px, 0.75fr);
  gap: 14px;
  margin-bottom: 14px;
}

.dashboard-panel {
  min-width: 0;
  padding: 20px;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.trend-panel {
  position: relative;
}

.trend-panel::before {
  position: absolute;
  z-index: 1;
  inset: -1px;
  padding: 1px;
  border-radius: var(--radius-lg);
  background: conic-gradient(
    from var(--dashboard-beam-angle),
    transparent 0deg 270deg,
    var(--primary) 300deg,
    var(--teal) 326deg,
    transparent 350deg
  );
  content: "";
  pointer-events: none;
  -webkit-mask:
    linear-gradient(var(--surface-base) 0 0) content-box,
    linear-gradient(var(--surface-base) 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  animation: dashboard-border-beam 4.6s linear infinite;
}

.dashboard-panel-heading {
  min-width: 0;
  justify-content: space-between;
  gap: 14px;
}

.dashboard-panel-heading h2 {
  margin: 4px 0 0;
  color: var(--text-primary);
  font-size: 16px;
  line-height: 1.35;
  letter-spacing: 0;
}

.dashboard-panel-heading :deep(.el-tag),
.dashboard-page :deep(.el-tag) {
  border-radius: var(--radius-sm);
  font-size: 12px;
}

.dashboard-chart {
  width: 100%;
  height: 270px;
  margin-top: 12px;
}

.recent-dashboard-panel {
  padding-bottom: 8px;
}

.recent-dashboard-panel .dashboard-panel-heading {
  margin-bottom: 12px;
}

.dashboard-table :deep(.el-table__row) {
  cursor: pointer;
}

.dashboard-table {
  --el-table-bg-color: var(--surface-elevated);
  --el-table-tr-bg-color: var(--surface-elevated);
  --el-table-header-bg-color: var(--surface-muted);
  --el-table-row-hover-bg-color: var(--primary-soft);
  --el-table-border-color: var(--border-subtle);
  --el-table-text-color: var(--text-secondary);
  --el-table-header-text-color: var(--text-muted);
}

.dashboard-table :deep(.el-table__inner-wrapper::before) {
  background: var(--border-subtle);
}

.dashboard-table :deep(.el-table__cell) {
  padding: 11px 0;
  border-color: var(--border-subtle);
  font-size: 13px;
}

.dashboard-table :deep(th.el-table__cell) {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
}

.symptom-cell {
  min-width: 0;
  gap: 10px;
}

.symptom-cell > span {
  width: 32px;
  height: 32px;
  background: var(--primary-soft);
  color: var(--primary);
}

.symptom-cell strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-arrow {
  color: var(--text-subtle);
}

.dashboard-mobile-records {
  display: none;
}

.dashboard-mobile-record {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 14px 2px;
  border: 0;
  border-top: 1px solid var(--border-subtle);
  background: transparent;
  color: var(--text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease;
}

.dashboard-mobile-record:last-child {
  padding-bottom: 8px;
}

.dashboard-mobile-record-main {
  min-width: 0;
  gap: 10px;
}

.mobile-record-icon {
  width: 34px;
  height: 34px;
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--primary-soft);
  color: var(--primary);
}

.dashboard-mobile-record-main > span:last-child {
  min-width: 0;
}

.dashboard-mobile-record strong,
.dashboard-mobile-record small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-mobile-record strong {
  max-width: 100%;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.45;
}

.dashboard-mobile-record small {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.4;
}

.dashboard-mobile-record-meta {
  justify-content: flex-end;
  gap: 9px;
}

.dashboard-mobile-record time {
  color: var(--text-muted);
  font-size: 12px;
  white-space: nowrap;
}

@media (hover: hover) {
  .dashboard-metric:hover {
    border-color: var(--border-default);
    box-shadow: var(--shadow-md);
    transform: translateY(-1px);
  }

  .dashboard-panel:hover {
    border-color: var(--border-default);
  }

  .dashboard-mobile-record:hover {
    background: var(--surface-muted);
  }
}

@keyframes dashboard-border-beam {
  to {
    --dashboard-beam-angle: 360deg;
  }
}

@keyframes dashboard-metric-sheen {
  0%,
  18% {
    transform: translateX(0);
  }

  64%,
  100% {
    transform: translateX(510%);
  }
}

@keyframes dashboard-status-glow {
  0%,
  100% {
    box-shadow: 0 0 0 2px var(--metric-soft);
  }

  50% {
    box-shadow: 0 0 0 5px var(--metric-soft), 0 0 16px var(--metric-soft);
  }
}

@media (max-width: 1120px) {
  .dashboard-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dashboard-charts {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .dashboard-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
  }

  .dashboard-heading h1 {
    font-size: 22px;
  }

  .dashboard-actions {
    width: 100%;
  }

  .dashboard-actions .el-button {
    flex: 1;
    margin: 0;
  }

  .dashboard-metrics {
    grid-template-columns: 1fr;
  }

  .dashboard-panel {
    padding: 16px;
  }

  .trend-panel .dashboard-chart {
    height: 240px;
  }

  .department-panel .dashboard-chart {
    height: 280px;
  }

  .recent-dashboard-panel {
    padding-bottom: 8px;
  }

  .dashboard-table {
    display: none;
  }

  .dashboard-mobile-records {
    display: block;
  }

  .dashboard-mobile-record-meta time {
    display: none;
  }
}

@media (max-width: 420px) {
  .dashboard-metric {
    padding: 16px;
  }

  .dashboard-panel-heading {
    align-items: flex-start;
  }

  .dashboard-mobile-record {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
  }

  .dashboard-mobile-record-meta {
    gap: 6px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .dashboard-metric,
  .dashboard-panel,
  .dashboard-mobile-record {
    transition: none;
  }

  .dashboard-metric:hover {
    transform: none;
  }

  .trend-panel::before,
  .dashboard-metric::after,
  .dashboard-metric.gold .dashboard-metric-icon {
    animation: none;
  }

  .trend-panel::before {
    --dashboard-beam-angle: 315deg;
  }

  .dashboard-metric::after {
    opacity: 0.64;
    transform: translateX(390%);
  }
}

/* Deep-space data field: local tokens keep this workspace consistent in both app themes. */
.dashboard-page {
  --dashboard-ink: #f1f7ff;
  --dashboard-secondary: #bccae2;
  --dashboard-muted: #778bac;
  --dashboard-blue: #65c8ff;
  --dashboard-cyan: #52e2ed;
  --dashboard-violet: #b58aff;
  --dashboard-gold: #ffc56f;
  --dashboard-red: #ff5d79;

  width: min(100%, 1380px);
  position: relative;
  padding: 4px 6px 98px;
  isolation: isolate;
  color: var(--dashboard-ink);
}

.dashboard-page::before {
  position: absolute;
  z-index: 0;
  inset: -30px -36px -52px;
  background:
    radial-gradient(ellipse at 48% 30%, rgba(28, 73, 137, 0.24), transparent 35%),
    radial-gradient(ellipse at 84% 74%, rgba(91, 54, 145, 0.15), transparent 32%),
    linear-gradient(135deg, #030817 0%, #071126 47%, #090a22 75%, #030611 100%);
  content: "";
  pointer-events: none;
}

.dashboard-page > :not(.dashboard-atmosphere) {
  position: relative;
  z-index: 1;
}

.dashboard-atmosphere {
  position: fixed;
  z-index: 0;
  inset: 72px 0 0 216px;
  overflow: hidden;
  background:
    radial-gradient(ellipse at 49% 46%, rgba(34, 83, 151, 0.28), transparent 34%),
    radial-gradient(ellipse at 84% 76%, rgba(106, 64, 155, 0.18), transparent 31%),
    radial-gradient(ellipse at 15% 18%, rgba(16, 119, 164, 0.13), transparent 27%),
    linear-gradient(135deg, #030817 0%, #071126 45%, #090a22 74%, #030611 100%);
  pointer-events: none;
}

.dashboard-atmosphere::before,
.dashboard-atmosphere::after {
  position: absolute;
  inset: 0;
  content: "";
  pointer-events: none;
}

.dashboard-atmosphere::before {
  background-image:
    radial-gradient(circle, rgba(178, 224, 255, 0.72) 0 1px, transparent 1.5px),
    radial-gradient(circle, rgba(150, 121, 255, 0.45) 0 1px, transparent 1.4px);
  background-position: 0 0, 31px 19px;
  background-size: 67px 67px, 109px 109px;
  opacity: 0.46;
  -webkit-mask-image: linear-gradient(to bottom, black, rgba(0, 0, 0, 0.72) 70%, transparent);
  mask-image: linear-gradient(to bottom, black, rgba(0, 0, 0, 0.72) 70%, transparent);
}

.dashboard-atmosphere::after {
  background:
    repeating-linear-gradient(114deg, transparent 0 98px, rgba(86, 172, 238, 0.035) 99px 100px),
    linear-gradient(90deg, rgba(3, 7, 21, 0.32), transparent 21%, transparent 80%, rgba(3, 7, 21, 0.36));
}

.dashboard-atmosphere svg {
  position: absolute;
  width: 108%;
  height: 108%;
  inset: -4%;
  overflow: visible;
}

.atmosphere-filaments,
.atmosphere-orbits,
.atmosphere-gold {
  fill: none;
  vector-effect: non-scaling-stroke;
}

.atmosphere-filaments {
  stroke: rgba(85, 160, 225, 0.16);
  stroke-width: 1;
  stroke-dasharray: 2 8;
}

.atmosphere-orbits {
  stroke: url(#dashboard-flow-cyan);
  stroke-width: 1.25;
  stroke-dasharray: 12 17;
  animation: dashboard-filament-flow 18s linear infinite;
}

.atmosphere-gold {
  stroke: url(#dashboard-flow-gold);
  stroke-width: 1.4;
  stroke-dasharray: 18 16;
  animation: dashboard-filament-flow 23s linear infinite reverse;
}

.atmosphere-nodes {
  fill: #bfeaff;
  filter: drop-shadow(0 0 6px #64c8ff);
  animation: dashboard-node-field 4.8s ease-in-out infinite;
}

.dashboard-heading {
  align-items: flex-end;
  margin-bottom: 20px;
  padding: 6px 4px 12px;
}

.dashboard-heading-copy {
  min-width: 0;
}

.dashboard-heading > .dashboard-heading-copy > .dashboard-kicker,
.dashboard-panel-heading > div > span {
  color: var(--dashboard-blue);
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
}

.dashboard-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.dashboard-kicker i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--dashboard-cyan);
  box-shadow: 0 0 9px var(--dashboard-cyan), 0 0 18px rgba(82, 226, 237, 0.72);
}

.dashboard-heading h1 {
  margin: 7px 0 5px;
  color: var(--dashboard-ink);
  font-size: 25px;
  font-weight: 680;
  text-shadow: 0 0 28px rgba(101, 200, 255, 0.22);
}

.dashboard-heading p {
  color: var(--dashboard-muted);
}

.dashboard-actions :deep(.el-button),
.dashboard-panel-heading :deep(.el-button) {
  --el-button-bg-color: rgba(11, 25, 54, 0.52);
  --el-button-border-color: transparent;
  --el-button-text-color: var(--dashboard-secondary);
  --el-button-hover-bg-color: rgba(55, 124, 194, 0.22);
  --el-button-hover-border-color: transparent;
  --el-button-hover-text-color: #ffffff;
  min-height: 36px;
  border: 0;
  box-shadow: inset 0 1px 0 rgba(221, 245, 255, 0.1), 0 8px 24px rgba(0, 0, 0, 0.12);
  backdrop-filter: blur(12px);
}

.dashboard-actions :deep(.el-button--primary) {
  --el-button-bg-color: rgba(43, 130, 219, 0.74);
  --el-button-text-color: #ffffff;
  --el-button-hover-bg-color: rgba(61, 157, 243, 0.88);
  box-shadow: inset 0 1px 0 rgba(227, 248, 255, 0.28), 0 0 22px rgba(56, 159, 242, 0.2);
}

.dashboard-page > .el-alert {
  border: 0;
  background: rgba(129, 78, 25, 0.32);
  color: #ffe1a9;
  box-shadow: inset 0 1px 0 rgba(255, 224, 171, 0.13);
  backdrop-filter: blur(14px);
}

.dashboard-metrics {
  position: relative;
  min-height: 172px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  align-items: center;
  margin: 0 0 20px;
  padding: 7px 0 8px;
}

.metric-data-stream {
  position: absolute;
  z-index: 0;
  width: calc(100% + 38px);
  height: calc(100% + 24px);
  inset: -12px -19px;
  overflow: visible;
  pointer-events: none;
}

.metric-data-stream path {
  fill: none;
  vector-effect: non-scaling-stroke;
}

.metric-stream-glow {
  stroke: rgba(62, 159, 255, 0.26);
  stroke-width: 13;
  filter: blur(8px);
}

.metric-stream-core {
  stroke: url(#dashboard-flow-cyan);
  stroke-width: 1.8;
  stroke-dasharray: 15 10;
  filter: drop-shadow(0 0 6px rgba(79, 196, 255, 0.86));
  animation: dashboard-stream-flow 12s linear infinite;
}

.metric-stream-thread {
  stroke: rgba(186, 137, 255, 0.32);
  stroke-width: 1;
  stroke-dasharray: 3 8;
  animation: dashboard-stream-flow 17s linear infinite reverse;
}

.dashboard-metric,
.dashboard-panel {
  border: 0;
  background: transparent;
  box-shadow: none;
}

.dashboard-metric {
  --metric-tone: var(--dashboard-blue);
  --metric-haze: rgba(40, 131, 221, 0.24);

  z-index: 1;
  min-height: 130px;
  padding: 23px 18px 20px;
  overflow: visible;
  transform: translateY(-5px);
  transition: transform 0.2s ease, filter 0.2s ease;
}

.dashboard-metric::before {
  position: absolute;
  z-index: -1;
  inset: 10px -2px 7px;
  border-radius: 48% 52% 44% 56% / 34% 43% 57% 66%;
  background:
    radial-gradient(ellipse at 78% 22%, color-mix(in srgb, var(--metric-tone) 30%, transparent), transparent 35%),
    linear-gradient(115deg, rgba(10, 22, 49, 0.68), var(--metric-haze) 56%, rgba(9, 18, 41, 0.5));
  box-shadow:
    inset 0 1px 0 rgba(222, 244, 255, 0.12),
    inset 0 -18px 34px rgba(2, 7, 20, 0.18),
    0 17px 44px rgba(0, 0, 0, 0.14);
  content: "";
  backdrop-filter: blur(16px);
}

.dashboard-metric::after {
  top: auto;
  right: 21px;
  bottom: 12px;
  left: auto;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--metric-tone);
  filter: drop-shadow(0 0 7px var(--metric-tone));
  animation: dashboard-metric-node 3.4s ease-in-out infinite;
}

.dashboard-metric.green {
  --metric-tone: var(--dashboard-cyan);
  --metric-haze: rgba(22, 150, 164, 0.2);
  transform: translateY(13px);
}

.dashboard-metric.purple {
  --metric-tone: var(--dashboard-violet);
  --metric-haze: rgba(111, 69, 182, 0.24);
  transform: translateY(-4px);
}

.dashboard-metric.gold {
  --metric-tone: var(--dashboard-gold);
  --metric-haze: rgba(170, 107, 37, 0.2);
  transform: translateY(12px);
}

.dashboard-metric-index {
  position: absolute;
  top: 12px;
  left: 17px;
  color: color-mix(in srgb, var(--metric-tone) 66%, transparent);
  font: 700 9px/1 ui-monospace, "SFMono-Regular", Consolas, monospace;
}

.dashboard-metric > div,
.dashboard-metric-icon {
  position: relative;
  z-index: 1;
}

.dashboard-metric > div > span {
  color: var(--dashboard-secondary);
  font-size: 12px;
}

.dashboard-metric strong {
  margin-top: 9px;
  color: var(--dashboard-ink);
  font-size: 27px;
  text-shadow: 0 0 20px color-mix(in srgb, var(--metric-tone) 28%, transparent);
}

.dashboard-metric strong small,
.dashboard-metric p {
  color: var(--dashboard-muted);
}

.dashboard-metric-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: radial-gradient(circle at 32% 24%, rgba(255, 255, 255, 0.27), var(--metric-haze));
  color: var(--metric-tone);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.19),
    0 0 18px color-mix(in srgb, var(--metric-tone) 26%, transparent);
  backdrop-filter: blur(10px);
}

.dashboard-metric.gold .dashboard-metric-icon {
  animation: none;
}

.dashboard-charts {
  grid-template-columns: minmax(0, 1.55fr) minmax(310px, 0.72fr);
  gap: clamp(26px, 3vw, 48px);
  margin: 3px 0 22px;
}

.dashboard-panel {
  padding: 18px 8px 10px;
  transition: filter 0.2s ease;
}

.trend-panel::before {
  display: none;
}

.dashboard-panel-heading h2 {
  color: var(--dashboard-ink);
  font-size: 16px;
  font-weight: 650;
}

.dashboard-panel-heading :deep(.el-tag),
.dashboard-page :deep(.el-tag) {
  --el-tag-bg-color: rgba(24, 54, 97, 0.34);
  --el-tag-border-color: transparent;
  --el-tag-text-color: #bcdcff;
  border: 0;
  box-shadow: inset 0 1px 0 rgba(204, 235, 255, 0.09);
  backdrop-filter: blur(10px);
}

.dashboard-chart {
  height: 282px;
  margin-top: 8px;
}

.department-panel .dashboard-chart {
  filter: drop-shadow(0 0 22px rgba(71, 167, 230, 0.1));
}

.recent-dashboard-panel {
  position: relative;
  margin-top: 8px;
  padding: 24px 8px 10px;
}

.recent-dashboard-panel::before {
  position: absolute;
  top: 0;
  right: 5%;
  left: 0;
  height: 1px;
  background: linear-gradient(90deg, rgba(83, 204, 255, 0.48), rgba(176, 125, 255, 0.22) 56%, transparent);
  box-shadow: 0 0 10px rgba(73, 179, 255, 0.24);
  content: "";
}

.recent-dashboard-panel .dashboard-panel-heading {
  margin-bottom: 14px;
}

.dashboard-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(40, 105, 173, 0.16);
  --el-table-border-color: rgba(95, 147, 207, 0.13);
  --el-table-text-color: var(--dashboard-secondary);
  --el-table-header-text-color: var(--dashboard-muted);
  background: transparent;
}

.dashboard-table :deep(.el-table),
.dashboard-table :deep(.el-table__inner-wrapper),
.dashboard-table :deep(.el-table__header-wrapper),
.dashboard-table :deep(.el-table__body-wrapper),
.dashboard-table :deep(.el-table__row),
.dashboard-table :deep(.el-table__cell) {
  background: transparent;
}

.dashboard-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.dashboard-table :deep(.el-table__cell) {
  padding: 12px 0;
  border-color: rgba(95, 147, 207, 0.13);
}

.dashboard-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: linear-gradient(90deg, rgba(42, 112, 186, 0.17), rgba(93, 64, 149, 0.07), transparent);
}

.dashboard-table :deep(th.el-table__cell) {
  color: var(--dashboard-muted);
}

.symptom-cell > span,
.mobile-record-icon {
  border-radius: 8px;
  background: linear-gradient(145deg, rgba(85, 196, 255, 0.2), rgba(110, 85, 190, 0.17));
  color: var(--dashboard-blue);
  box-shadow: inset 0 1px 0 rgba(220, 246, 255, 0.12), 0 0 14px rgba(80, 183, 244, 0.12);
}

.symptom-cell strong,
.dashboard-mobile-record strong {
  color: var(--dashboard-ink);
}

.row-arrow {
  color: var(--dashboard-blue);
}

.dashboard-mobile-record {
  border-top-color: rgba(95, 147, 207, 0.14);
  color: var(--dashboard-ink);
}

.dashboard-mobile-record small,
.dashboard-mobile-record time {
  color: var(--dashboard-muted);
}

.dashboard-page > .dashboard-risk-status {
  position: relative;
  z-index: 1;
  right: auto;
  bottom: auto;
  left: auto;
  width: max-content;
  margin: -8px clamp(22px, 3vw, 52px) 14px auto;
  display: flex;
  align-items: center;
  gap: 12px;
  pointer-events: none;
}

.risk-orb-core {
  position: relative;
  width: 62px;
  height: 62px;
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: 1fr;
  place-items: center;
  border-radius: 50%;
  background:
    radial-gradient(circle at 34% 26%, #ffc0cb 0 5%, #ff6c84 18%, #ae183c 52%, #3c0619 77%, #0d0510 100%);
  color: #fff5f7;
  box-shadow:
    inset -8px -10px 18px rgba(15, 0, 9, 0.46),
    inset 5px 6px 12px rgba(255, 221, 226, 0.28),
    0 0 0 1px rgba(255, 138, 158, 0.28),
    0 0 20px rgba(255, 50, 89, 0.54),
    0 0 52px rgba(255, 37, 82, 0.26);
  animation: dashboard-risk-float 3.2s ease-in-out infinite;
}

.risk-orb-core::before,
.risk-orb-core::after {
  position: absolute;
  border: 1px solid rgba(255, 99, 126, 0.36);
  border-radius: 50%;
  content: "";
  pointer-events: none;
}

.risk-orb-core::before {
  inset: -8px;
  animation: dashboard-risk-ring 2.4s ease-out infinite;
}

.risk-orb-core::after {
  inset: -17px;
  border-color: rgba(255, 99, 126, 0.14);
  animation: dashboard-risk-ring 2.4s 0.7s ease-out infinite;
}

.risk-orb-core .el-icon {
  grid-area: 1 / 1;
  margin-top: -13px;
  filter: drop-shadow(0 0 5px rgba(255, 255, 255, 0.5));
}

.risk-orb-core strong {
  grid-area: 1 / 1;
  align-self: end;
  margin-bottom: 9px;
  font-size: 13px;
  line-height: 1;
}

.risk-orb-highlight {
  position: absolute;
  top: 9px;
  left: 13px;
  width: 13px;
  height: 7px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.6);
  filter: blur(1px);
  transform: rotate(-28deg);
}

.risk-orb-copy strong,
.risk-orb-copy small {
  display: block;
  text-shadow: 0 2px 10px #030611;
}

.risk-orb-copy {
  display: none;
}

.risk-orb-copy strong {
  color: #ffd7de;
  font-size: 11px;
}

.risk-orb-copy small {
  margin-top: 3px;
  color: #9e8290;
  font-size: 10px;
  white-space: nowrap;
}

@media (hover: hover) {
  .dashboard-metric:hover {
    border-color: transparent;
    box-shadow: none;
    filter: brightness(1.12);
    transform: translateY(-9px);
  }

  .dashboard-metric.green:hover {
    transform: translateY(9px);
  }

  .dashboard-metric.purple:hover {
    transform: translateY(-8px);
  }

.dashboard-metric.gold:hover {
  transform: translateY(8px);
}

  .dashboard-panel:hover {
    border-color: transparent;
    filter: brightness(1.04);
  }

  .dashboard-mobile-record:hover {
    background: linear-gradient(90deg, rgba(42, 112, 186, 0.14), transparent);
  }
}

@keyframes dashboard-filament-flow {
  to { stroke-dashoffset: -116; }
}

@keyframes dashboard-stream-flow {
  to { stroke-dashoffset: -150; }
}

@keyframes dashboard-node-field {
  0%, 100% { opacity: 0.42; }
  50% { opacity: 0.9; }
}

@keyframes dashboard-metric-node {
  0%, 100% { opacity: 0.48; transform: scale(0.82); }
  50% { opacity: 1; transform: scale(1.18); }
}

@keyframes dashboard-risk-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-4px); }
}

@keyframes dashboard-risk-ring {
  0% { opacity: 0.8; transform: scale(0.84); }
  72%, 100% { opacity: 0; transform: scale(1.24); }
}

@media (max-width: 1120px) {
  .dashboard-metrics {
    min-height: 292px;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 3px 20px;
  }

  .metric-data-stream {
    opacity: 0.62;
  }

  .dashboard-metric,
  .dashboard-metric.green,
  .dashboard-metric.purple,
  .dashboard-metric.gold {
    transform: none;
  }

  .dashboard-charts {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}

@media (max-width: 900px) {
  .dashboard-atmosphere {
    inset: 64px 0 0;
  }

  .dashboard-page::before {
    inset: -20px -18px -42px;
  }
}

@media (max-width: 640px) {
  .dashboard-page {
    padding: 3px 2px 90px;
  }

  .dashboard-heading {
    gap: 15px;
    padding-right: 2px;
    padding-left: 2px;
  }

  .dashboard-heading h1 {
    font-size: 22px;
  }

  .dashboard-heading p {
    max-width: 34ch;
  }

  .dashboard-metrics {
    min-height: 0;
    grid-template-columns: 1fr;
    gap: 7px;
    padding-left: 10px;
  }

  .dashboard-metrics::before {
    position: absolute;
    top: 24px;
    bottom: 24px;
    left: 19px;
    width: 1px;
    background: linear-gradient(var(--dashboard-blue), var(--dashboard-violet), var(--dashboard-gold));
    box-shadow: 0 0 8px rgba(101, 200, 255, 0.5);
    content: "";
  }

  .metric-data-stream {
    display: none;
  }

  .dashboard-metric,
  .dashboard-metric.green,
  .dashboard-metric.purple,
  .dashboard-metric.gold {
    min-height: 108px;
    padding: 18px 18px 17px 26px;
    transform: none;
  }

  .dashboard-metric::before {
    inset: 4px 0;
    border-radius: 8px 48% 48% 8px / 8px 42% 58% 8px;
  }

  .dashboard-metric-index {
    top: 12px;
    left: 8px;
  }

  .dashboard-panel {
    padding: 16px 2px 8px;
  }

  .trend-panel .dashboard-chart {
    height: 250px;
  }

  .department-panel .dashboard-chart {
    height: 292px;
  }

  .recent-dashboard-panel {
    padding-right: 2px;
    padding-left: 2px;
  }

  .risk-orb-core {
    width: 54px;
    height: 54px;
  }

  .risk-orb-copy {
    display: none;
  }
}

@media (max-width: 420px) {
  .dashboard-kicker {
    font-size: 10px;
  }

  .dashboard-metric,
  .dashboard-metric.green,
  .dashboard-metric.purple,
  .dashboard-metric.gold {
    padding: 17px 14px 16px 24px;
  }

  .dashboard-metric strong {
    font-size: 25px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .atmosphere-orbits,
  .atmosphere-gold,
  .atmosphere-nodes,
  .metric-stream-core,
  .metric-stream-thread,
  .dashboard-metric::after,
  .risk-orb-core,
  .risk-orb-core::before,
  .risk-orb-core::after {
    animation: none;
  }

  .dashboard-metric::after {
    opacity: 0.72;
    transform: none;
  }

  .dashboard-metric:hover,
  .dashboard-metric.green:hover,
  .dashboard-metric.purple:hover,
  .dashboard-metric.gold:hover {
    transform: none;
  }
}

@media (max-width: 900px) {
  .dashboard-page > .dashboard-risk-status {
    position: relative;
    top: auto;
    right: auto;
    bottom: auto;
    left: auto;
    width: 54px;
    margin: -4px 10px 10px auto;
  }
}

/* Light clinical data field: keeps the dashboard expressive without separating it from patient services. */
.dashboard-page {
  --dashboard-ink: var(--text-primary);
  --dashboard-secondary: var(--text-secondary);
  --dashboard-muted: var(--text-muted);
  --dashboard-blue: var(--primary);
  --dashboard-cyan: var(--success);
  --dashboard-violet: var(--accent-violet);
  --dashboard-gold: var(--warning);
  --dashboard-red: var(--danger);

  position: relative;
  width: min(100%, 1260px);
  padding: 8px 0 52px;
  isolation: isolate;
  overflow: hidden;
  background: transparent;
}

.dashboard-page::before {
  inset: 0;
  background:
    radial-gradient(ellipse at 14% 18%, rgba(23, 111, 137, 0.11), transparent 34%),
    radial-gradient(ellipse at 86% 80%, rgba(109, 98, 160, 0.08), transparent 32%),
    linear-gradient(145deg, #f8fcfd 0%, #f2f8f9 55%, #f7f8fc 100%);
}

.dashboard-page > :not(.dashboard-atmosphere) {
  position: relative;
  z-index: 1;
}

.dashboard-atmosphere {
  position: absolute;
  z-index: 0;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(ellipse at 50% 34%, rgba(23, 111, 137, 0.05), transparent 40%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.18), transparent 56%);
  pointer-events: none;
}

.dashboard-atmosphere::before {
  background-image:
    radial-gradient(circle, rgba(23, 111, 137, 0.28) 0 1px, transparent 1.45px),
    radial-gradient(circle, rgba(109, 98, 160, 0.2) 0 1px, transparent 1.4px);
  background-position: 0 0, 31px 19px;
  background-size: 67px 67px, 109px 109px;
  opacity: 0.42;
}

.dashboard-atmosphere::after {
  background:
    repeating-linear-gradient(114deg, transparent 0 98px, rgba(23, 111, 137, 0.045) 99px 100px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.28), transparent 21%, transparent 80%, rgba(255, 255, 255, 0.24));
  opacity: 0.62;
}

.dashboard-atmosphere svg {
  opacity: 0.38;
}

.atmosphere-filaments {
  stroke: rgba(23, 111, 137, 0.12);
}

.atmosphere-orbits {
  stroke: var(--primary);
  opacity: 0.26;
}

.atmosphere-gold {
  stroke: var(--accent-violet);
  opacity: 0.2;
}

.atmosphere-nodes {
  fill: var(--primary);
  filter: none;
}

.dashboard-heading h1,
.dashboard-panel-heading h2,
.dashboard-metric strong {
  color: var(--dashboard-ink);
  text-shadow: none;
}

.dashboard-heading p,
.dashboard-metric > div > span,
.dashboard-metric strong small,
.dashboard-metric p,
.dashboard-mobile-record small,
.dashboard-mobile-record time {
  color: var(--dashboard-muted);
}

.dashboard-heading > .dashboard-heading-copy > .dashboard-kicker,
.dashboard-panel-heading > div > span {
  color: var(--dashboard-blue);
}

.dashboard-actions :deep(.el-button),
.dashboard-panel-heading :deep(.el-button) {
  --el-button-bg-color: var(--surface-elevated);
  --el-button-border-color: var(--border-default);
  --el-button-text-color: var(--text-secondary);
  --el-button-hover-bg-color: var(--primary-soft);
  --el-button-hover-border-color: var(--primary);
  --el-button-hover-text-color: var(--primary-solid);
  box-shadow: 0 5px 14px rgba(31, 62, 70, 0.06);
  backdrop-filter: none;
}

.dashboard-actions :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary-solid);
  --el-button-border-color: var(--primary-solid);
  --el-button-text-color: var(--text-inverse);
  --el-button-hover-bg-color: var(--primary-solid-hover);
  --el-button-hover-border-color: var(--primary-solid-hover);
}

.dashboard-page > .el-alert {
  border: 1px solid color-mix(in srgb, var(--warning) 28%, transparent);
  background: var(--warning-soft);
  color: var(--text-primary);
  box-shadow: none;
  backdrop-filter: none;
}

.dashboard-metrics {
  gap: 14px;
}

.dashboard-metric,
.dashboard-metric.green,
.dashboard-metric.purple,
.dashboard-metric.gold {
  min-height: 132px;
  transform: none;
  border: 1px solid var(--border-subtle);
  border-top: 3px solid var(--metric-tone);
  border-radius: 8px;
  background: linear-gradient(145deg, var(--surface-elevated), color-mix(in srgb, var(--metric-soft) 46%, white));
  box-shadow: var(--shadow-card);
}

.dashboard-metric::before {
  display: none;
}

.dashboard-metric::after {
  background: linear-gradient(90deg, transparent, var(--metric-soft), var(--metric-tone), transparent);
  filter: none;
}

.dashboard-metric-icon {
  background: var(--metric-soft);
  color: var(--metric-tone);
  box-shadow: none;
  backdrop-filter: none;
}

.dashboard-panel {
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: var(--shadow-card);
}

.dashboard-panel::before {
  opacity: 0.38;
}

.dashboard-panel-heading :deep(.el-tag),
.dashboard-page :deep(.el-tag) {
  --el-tag-bg-color: var(--primary-soft);
  --el-tag-border-color: transparent;
  --el-tag-text-color: var(--primary-solid);
  box-shadow: none;
  backdrop-filter: none;
}

.dashboard-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: var(--surface-muted);
  --el-table-row-hover-bg-color: var(--primary-soft);
  --el-table-border-color: var(--border-subtle);
  --el-table-text-color: var(--text-secondary);
  --el-table-header-text-color: var(--text-muted);
}

.dashboard-table :deep(.el-table),
.dashboard-table :deep(.el-table__inner-wrapper),
.dashboard-table :deep(.el-table__header-wrapper),
.dashboard-table :deep(.el-table__body-wrapper),
.dashboard-table :deep(.el-table__row),
.dashboard-table :deep(.el-table__cell) {
  background: transparent;
}

.dashboard-table :deep(.el-table__row:hover > td.el-table__cell),
.dashboard-mobile-record:hover {
  background: var(--primary-soft);
}

.dashboard-risk-status {
  min-height: 58px;
  margin: 2px 0 14px auto;
  padding: 6px 12px 6px 7px;
  border: 1px solid color-mix(in srgb, var(--danger) 22%, var(--border-subtle));
  border-radius: 999px;
  background: color-mix(in srgb, var(--surface-elevated) 92%, var(--danger-soft));
  box-shadow: 0 7px 18px rgba(31, 62, 70, 0.07);
}

.risk-orb-core {
  width: 46px;
  height: 46px;
  background: var(--danger-soft);
  color: var(--danger);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--danger) 22%, transparent);
  animation: none;
}

.risk-orb-core::before,
.risk-orb-core::after {
  display: none;
}

.risk-orb-core .el-icon {
  margin-top: -9px;
  filter: none;
}

.risk-orb-core strong {
  margin-bottom: 6px;
  color: var(--danger);
  font-size: 12px;
}

.risk-orb-highlight {
  display: none;
}

.risk-orb-copy {
  display: block;
  margin-left: 2px;
}

.risk-orb-copy strong,
.risk-orb-copy small {
  text-shadow: none;
}

.risk-orb-copy strong {
  color: var(--text-primary);
  font-size: 12px;
}

.risk-orb-copy small {
  color: var(--text-muted);
}

@media (hover: hover) {
  .dashboard-metric:hover,
  .dashboard-metric.green:hover,
  .dashboard-metric.purple:hover,
  .dashboard-metric.gold:hover {
    transform: translateY(-2px);
    filter: none;
  }
}

@media (max-width: 640px) {
  .dashboard-page {
    padding: 4px 0 40px;
  }

  .dashboard-risk-status {
    width: max-content;
    margin: -2px 0 12px auto;
  }
}
</style>
