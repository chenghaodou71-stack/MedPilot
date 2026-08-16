<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart } from 'echarts/charts'
import { GridComponent } from 'echarts/components'
import { init, use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { buildRiskScale, buildUrgencyScale } from '../lib/triageVisualization'

use([BarChart, GridComponent, CanvasRenderer])

const props = defineProps({
  riskLevel: { type: String, default: '' },
  urgency: { type: String, default: '' },
  supportScore: { type: [String, Number], default: '' },
  supportLabel: { type: String, default: '' },
  abstained: { type: Boolean, default: false },
})

const riskChartElement = ref(null)
const urgencyChartElement = ref(null)
const supportChartElement = ref(null)
const prefersReducedMotion = ref(false)

let riskChart = null
let urgencyChart = null
let supportChart = null
let resizeObserver = null
let motionMediaQuery = null
let renderFrame = null
let mounted = false

const riskScale = computed(() => buildRiskScale(props.riskLevel))
const urgencyScale = computed(() => buildUrgencyScale(props.urgency))
const activeRiskIndex = computed(() => (props.abstained ? -1 : riskScale.value.activeIndex))
const safeSupportLabel = computed(() => {
  const value = props.supportLabel.trim()
  return !value || /准确率|概率/u.test(value) ? '依据支持度' : value
})
const supportPercent = computed(() => normalizeSupport(props.supportScore))
const riskSummary = computed(() => (
  props.abstained
    ? '风险等级：待确认'
    : `风险等级：${riskScale.value.label}`
))
const urgencySummary = computed(() => (
  urgencyScale.value.activeIndex === -1
    ? '就医时效：待确认'
    : `就医时效：${urgencyScale.value.label}，${urgencyScale.value.value}`
))
const supportSummary = computed(() => (
  supportPercent.value === null
    ? `${safeSupportLabel.value}：暂无可展示数值`
    : `${safeSupportLabel.value}：${formatPercent(supportPercent.value)}`
))

function normalizeSupport(value) {
  if (value === '' || value === null || value === undefined) return null
  const text = String(value).trim()
  const isPercent = text.endsWith('%')
  const number = Number(isPercent ? text.slice(0, -1).trim() : text)
  if (!Number.isFinite(number) || number < 0) return null
  const percent = isPercent ? number : number <= 1 ? number * 100 : number
  return Math.min(100, percent)
}

function formatPercent(value) {
  const rounded = Math.round(value * 10) / 10
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)}%`
}

function cssColor(name, fallback) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
}

function chartPalette() {
  return {
    text: cssColor('--text-secondary', '#425f68'),
    muted: cssColor('--text-muted', '#6b8189'),
    border: cssColor('--border-default', '#d3dfe2'),
    surface: cssColor('--surface-muted', '#edf3f4'),
    primary: cssColor('--primary', '#176f89'),
    success: cssColor('--success', '#187d6d'),
    warning: cssColor('--warning', '#a86518'),
    danger: cssColor('--danger', '#bf3e4d'),
  }
}

function animationOptions() {
  const disabled = prefersReducedMotion.value
  return {
    animation: !disabled,
    animationDuration: disabled ? 0 : 260,
    animationDurationUpdate: disabled ? 0 : 180,
  }
}

function segmentedScaleOption(model, activeIndex, activeColors) {
  const palette = chartPalette()
  return {
    ...animationOptions(),
    grid: { left: 8, right: 8, top: 28, bottom: 34 },
    xAxis: {
      type: 'category',
      data: model.steps.map((step) => step.label),
      axisLine: { lineStyle: { color: palette.border } },
      axisTick: { alignWithLabel: true, lineStyle: { color: palette.border } },
      axisLabel: { color: palette.text, fontSize: 12, interval: 0 },
    },
    yAxis: { type: 'value', min: 0, max: 1, show: false },
    series: [{
      type: 'bar',
      silent: true,
      barWidth: 20,
      label: {
        show: activeIndex >= 0,
        position: 'top',
        color: palette.text,
        fontSize: 11,
        fontWeight: 600,
        formatter: ({ dataIndex }) => (dataIndex === activeIndex ? '当前位置' : ''),
      },
      data: model.steps.map((step, index) => ({
        value: 1,
        itemStyle: {
          color: index === activeIndex ? activeColors[index] : palette.surface,
          borderColor: index === activeIndex ? activeColors[index] : palette.border,
          borderWidth: 1,
          borderRadius: 3,
        },
      })),
    }],
  }
}

function supportOption() {
  const palette = chartPalette()
  const value = supportPercent.value
  return {
    ...animationOptions(),
    grid: { left: 8, right: 12, top: 30, bottom: 34 },
    xAxis: {
      type: 'value',
      min: 0,
      max: 100,
      splitNumber: 4,
      axisLine: { show: true, lineStyle: { color: palette.border } },
      axisTick: { show: false },
      splitLine: { show: false },
      axisLabel: { color: palette.muted, fontSize: 11, formatter: '{value}%' },
    },
    yAxis: { type: 'category', data: [''], show: false },
    series: [{
      type: 'bar',
      silent: true,
      barWidth: 20,
      showBackground: true,
      backgroundStyle: { color: palette.surface, borderColor: palette.border, borderWidth: 1 },
      label: {
        show: value !== null,
        position: value !== null && value >= 18 ? 'insideRight' : 'right',
        color: value !== null && value >= 18 ? '#ffffff' : palette.text,
        fontSize: 12,
        fontWeight: 700,
        formatter: value === null ? '' : formatPercent(value),
      },
      data: [{
        value: value ?? 0,
        itemStyle: {
          color: value === null ? palette.surface : palette.primary,
          borderRadius: 3,
        },
      }],
    }],
  }
}

function renderCharts() {
  if (!mounted) return
  const palette = chartPalette()
  riskChart?.setOption(
    segmentedScaleOption(riskScale.value, activeRiskIndex.value, [
      palette.success,
      palette.warning,
      palette.danger,
    ]),
    true,
  )
  urgencyChart?.setOption(
    segmentedScaleOption(urgencyScale.value, urgencyScale.value.activeIndex, [
      palette.danger,
      palette.warning,
      palette.primary,
    ]),
    true,
  )
  supportChart?.setOption(supportOption(), true)
}

function resizeCharts() {
  riskChart?.resize()
  urgencyChart?.resize()
  supportChart?.resize()
}

function scheduleChartRender() {
  if (!mounted) return
  if (renderFrame !== null) cancelAnimationFrame(renderFrame)
  renderFrame = requestAnimationFrame(() => {
    renderFrame = null
    resizeCharts()
    renderCharts()
  })
}

function observeChartSizes() {
  const elements = [riskChartElement.value, urgencyChartElement.value, supportChartElement.value]
  if ('ResizeObserver' in window) {
    resizeObserver = new ResizeObserver(() => {
      if (renderFrame !== null) cancelAnimationFrame(renderFrame)
      renderFrame = requestAnimationFrame(() => {
        renderFrame = null
        resizeCharts()
      })
    })
    elements.filter(Boolean).forEach((element) => resizeObserver.observe(element))
    return
  }
  window.addEventListener('resize', scheduleChartRender)
}

function handleMotionPreferenceChange(event) {
  prefersReducedMotion.value = event.matches
  scheduleChartRender()
}

watch(
  () => [props.riskLevel, props.urgency, props.supportScore, props.supportLabel, props.abstained],
  scheduleChartRender,
  { flush: 'post' },
)

onMounted(async () => {
  mounted = true
  motionMediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  prefersReducedMotion.value = motionMediaQuery.matches
  motionMediaQuery.addEventListener('change', handleMotionPreferenceChange)
  await nextTick()
  riskChart = init(riskChartElement.value)
  urgencyChart = init(urgencyChartElement.value)
  supportChart = init(supportChartElement.value)
  observeChartSizes()
  window.addEventListener('medpilot-settings-changed', scheduleChartRender)
  renderCharts()
})

onBeforeUnmount(() => {
  mounted = false
  window.removeEventListener('resize', scheduleChartRender)
  window.removeEventListener('medpilot-settings-changed', scheduleChartRender)
  motionMediaQuery?.removeEventListener('change', handleMotionPreferenceChange)
  resizeObserver?.disconnect()
  if (renderFrame !== null) cancelAnimationFrame(renderFrame)
  riskChart?.dispose()
  urgencyChart?.dispose()
  supportChart?.dispose()
  riskChart = null
  urgencyChart = null
  supportChart = null
})
</script>

<template>
  <section
    class="triage-scale-panel"
    data-testid="triage-scale-panel"
    aria-labelledby="triage-scale-title"
  >
    <header class="triage-scale-heading">
      <div>
        <span>核心可视化</span>
        <h3 id="triage-scale-title">风险与就医时效</h3>
      </div>
      <div v-if="abstained" class="abstention-status" role="status" aria-live="polite">
        <strong>风险等级待确认</strong>
        <span>当前依据不足，系统暂缓给出确定的风险等级。</span>
      </div>
    </header>

    <div class="triage-scale-grid">
      <figure class="scale-figure">
        <figcaption>
          <span>风险分级尺</span>
          <strong>{{ abstained ? '待确认' : riskScale.label }}</strong>
        </figcaption>
        <div
          ref="riskChartElement"
          class="scale-chart"
          data-testid="risk-scale-chart"
          role="img"
          :aria-label="`${riskSummary}。分级从低风险、中风险到高风险。`"
        />
        <p class="scale-summary">{{ riskSummary }}</p>
        <ol class="scale-text-list" aria-label="风险分级文字等价信息">
          <li
            v-for="(step, index) in riskScale.steps"
            :key="step.key"
            :class="{ 'is-current': index === activeRiskIndex }"
            :aria-current="index === activeRiskIndex ? 'step' : undefined"
          >
            <span>{{ step.label }}</span>
            <strong v-if="index === activeRiskIndex">当前</strong>
          </li>
        </ol>
      </figure>

      <figure class="scale-figure">
        <figcaption>
          <span>就医时效轴</span>
          <strong>{{ urgencyScale.label }}</strong>
        </figcaption>
        <div
          ref="urgencyChartElement"
          class="scale-chart"
          data-testid="urgency-scale-chart"
          role="img"
          :aria-label="urgencySummary"
        />
        <p class="scale-summary">{{ urgencySummary }}</p>
        <ol class="scale-text-list" aria-label="就医时效文字等价信息">
          <li
            v-for="(step, index) in urgencyScale.steps"
            :key="step.key"
            :class="{ 'is-current': index === urgencyScale.activeIndex }"
            :aria-current="index === urgencyScale.activeIndex ? 'step' : undefined"
          >
            <span>{{ step.label }}</span>
            <strong v-if="index === urgencyScale.activeIndex">当前</strong>
          </li>
        </ol>
      </figure>

      <figure class="scale-figure">
        <figcaption>
          <span>{{ safeSupportLabel }}</span>
          <strong>{{ supportPercent === null ? '暂无数据' : formatPercent(supportPercent) }}</strong>
        </figcaption>
        <div
          ref="supportChartElement"
          class="scale-chart"
          data-testid="support-scale-chart"
          role="img"
          :aria-label="supportSummary"
        />
        <p class="scale-summary">{{ supportSummary }}</p>
        <p class="support-boundary">仅表示当前规则或检索依据的支持程度，不代表临床诊断结论。</p>
      </figure>
    </div>
  </section>
</template>

<style scoped>
.triage-scale-panel {
  min-width: 0;
  container: triage-scale-panel / inline-size;
  color: var(--text-primary, #17343d);
}

.triage-scale-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.triage-scale-heading > div:first-child > span,
.scale-figure figcaption span {
  color: var(--text-muted, #6b8189);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
}

.triage-scale-heading h3 {
  margin: 3px 0 0;
  font-size: 18px;
  line-height: 1.35;
  letter-spacing: 0;
}

.abstention-status {
  display: grid;
  max-width: 380px;
  gap: 2px;
  padding: 9px 11px;
  border: 1px solid var(--warning, #a86518);
  border-radius: 6px;
  background: var(--warning-soft, rgba(168, 101, 24, 0.1));
}

.abstention-status strong {
  color: var(--warning, #a86518);
  font-size: 13px;
}

.abstention-status span {
  color: var(--text-secondary, #425f68);
  font-size: 12px;
  line-height: 1.45;
}

.triage-scale-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  border-block: 1px solid var(--border-default, #d3dfe2);
}

.scale-figure {
  min-width: 0;
  margin: 0;
  padding: 15px 16px 14px;
}

.scale-figure + .scale-figure {
  border-left: 1px solid var(--border-default, #d3dfe2);
}

.scale-figure figcaption {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  min-height: 24px;
  gap: 10px;
}

.scale-figure figcaption strong {
  min-width: 0;
  color: var(--text-primary, #17343d);
  font-size: 14px;
  overflow-wrap: anywhere;
  text-align: right;
}

.scale-chart {
  width: 100%;
  height: 148px;
  min-height: 148px;
}

.scale-summary {
  min-height: 36px;
  margin: 2px 0 8px;
  color: var(--text-secondary, #425f68);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.scale-text-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 5px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.scale-text-list li {
  display: grid;
  min-width: 0;
  min-height: 43px;
  align-content: center;
  gap: 1px;
  padding: 5px 4px;
  border: 1px solid var(--border-default, #d3dfe2);
  border-radius: 4px;
  color: var(--text-muted, #6b8189);
  font-size: 12px;
  line-height: 1.25;
  text-align: center;
}

.scale-text-list li.is-current {
  border-color: var(--primary, #176f89);
  background: var(--primary-soft, rgba(23, 111, 137, 0.09));
  color: var(--text-primary, #17343d);
}

.scale-text-list strong {
  color: var(--primary, #176f89);
  font-size: 12px;
}

.support-boundary {
  min-height: 43px;
  margin: 0;
  padding: 7px 9px;
  border-left: 3px solid var(--primary, #176f89);
  background: var(--primary-soft, rgba(23, 111, 137, 0.09));
  color: var(--text-secondary, #425f68);
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

@container triage-scale-panel (max-width: 720px) {
  .triage-scale-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .abstention-status {
    max-width: none;
  }

  .triage-scale-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .scale-figure + .scale-figure {
    border-top: 1px solid var(--border-default, #d3dfe2);
    border-left: 0;
  }

  .scale-chart {
    height: 132px;
    min-height: 132px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .triage-scale-panel *,
  .triage-scale-panel *::before,
  .triage-scale-panel *::after {
    scroll-behavior: auto !important;
    animation-duration: 0s !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0s !important;
  }
}
</style>
