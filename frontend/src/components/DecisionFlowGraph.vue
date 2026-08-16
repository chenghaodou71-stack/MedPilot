<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Handle, Position, VueFlow } from '@vue-flow/core'
import {
  Activity,
  BookOpenCheck,
  ClipboardCheck,
  FileQuestion,
  ShieldCheck,
} from 'lucide-vue-next'
import { buildDecisionFlowModel } from '../lib/triageVisualization'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

const props = defineProps({
  symptoms: {
    type: Object,
    default: () => ({}),
  },
  triage: {
    type: Object,
    default: () => ({}),
  },
  evidence: {
    type: Array,
    default: () => [],
  },
})

const detailId = 'decision-flow-selected-detail'
const canvasElement = ref(null)
const canvasWidth = ref(900)
const selectedNodeId = ref('')
let resizeObserver = null
let flowApi = null

const model = computed(() => buildDecisionFlowModel({
  symptoms: props.symptoms,
  triage: props.triage,
  evidence: props.evidence,
}))

const isCompact = computed(() => canvasWidth.value < 560)

function compactPositions(nodes) {
  const x = Math.max(16, (canvasWidth.value - 220) / 2)
  const categories = ['symptom', 'rule-or-evidence', 'outcome']
  const byCategory = {
    symptom: nodes.filter((node) => node.data.category === 'symptom'),
    'rule-or-evidence': nodes.filter((node) => (
      ['rule', 'evidence', 'retrieval', 'empty'].includes(node.data.category)
    )),
    outcome: nodes.filter((node) => node.data.category === 'outcome'),
  }
  const positioned = new Map()
  let y = 20

  categories.forEach((category) => {
    byCategory[category].forEach((node) => {
      positioned.set(node.id, { x, y })
      y += 106
    })
    y += 28
  })

  return nodes.map((node) => ({
    ...node,
    position: positioned.get(node.id) || node.position,
    sourcePosition: Position.Bottom,
    targetPosition: Position.Top,
  }))
}

const flowNodes = computed(() => {
  const nodes = isCompact.value ? compactPositions(model.value.nodes) : model.value.nodes
  return nodes.map((node) => ({
    ...node,
    selectable: false,
    focusable: false,
    connectable: false,
    width: 220,
    class: selectedNodeId.value === node.id ? 'decision-flow-node-selected' : '',
    ariaLabel: nodeAriaLabel(node),
  }))
})

const flowEdges = computed(() => model.value.edges.map((edge) => ({
  ...edge,
  animated: false,
  selectable: false,
  focusable: false,
  style: {
    stroke: 'var(--border-strong)',
    strokeWidth: 1.6,
    strokeDasharray: '6 5',
  },
})))

const selectedNode = computed(() => (
  model.value.nodes.find((node) => node.id === selectedNodeId.value) || model.value.nodes[0] || null
))

const canvasHeight = computed(() => {
  const maxY = Math.max(0, ...flowNodes.value.map((node) => Number(node.position?.y) || 0))
  const desired = Math.max(isCompact.value ? 500 : 390, maxY + 132)
  return Math.min(isCompact.value ? 720 : 620, desired)
})

const hasStructuredSymptoms = computed(() => (
  Array.isArray(props.symptoms?.symptoms)
  && props.symptoms.symptoms.some((value) => typeof value === 'string' && value.trim())
))

const hasTriageResult = computed(() => (
  ['department', 'risk_level', 'urgency'].some((key) => (
    typeof props.triage?.[key] === 'string' && props.triage[key].trim()
  ))
))

const emptyMessage = computed(() => {
  if (!hasStructuredSymptoms.value && !hasTriageResult.value && !model.value.hasDecisionBasis) {
    return '暂无可展示的结构化判断数据。图中仅保留待确认占位，不推断缺失信息。'
  }
  if (!model.value.hasDecisionBasis && model.value.hasRetrievalMaterials) {
    return '本次仅展示回答采用的检索资料；后端未返回结构化判断依据，因此资料不会连接到分诊结论。'
  }
  if (!model.value.hasDecisionBasis) {
    return '本次没有返回真实规则或检索依据，关系线保持为空，不补造判断依据。'
  }
  return ''
})

const nodeById = computed(() => new Map(model.value.nodes.map((node) => [node.id, node])))

const relationshipText = computed(() => model.value.edges.map((edge) => {
  const source = nodeById.value.get(edge.source)
  const target = nodeById.value.get(edge.target)
  return {
    id: edge.id,
    text: `${nodeText(source)}，与 ${nodeText(target)} 关联`,
  }
}))

const detailRows = computed(() => {
  const data = selectedNode.value?.data
  if (!data) return []
  return [
    data.value ? ['结果', data.value] : null,
    data.supportLabel ? ['指标', data.supportLabel] : null,
    data.detail ? ['说明', data.detail] : null,
    data.source ? ['来源', data.source] : null,
    data.reference ? ['引用编号', data.reference] : null,
  ].filter(Boolean)
})

function nodeIcon(category) {
  if (category === 'symptom') return Activity
  if (category === 'rule') return ShieldCheck
  if (category === 'evidence' || category === 'retrieval') return BookOpenCheck
  if (category === 'outcome') return ClipboardCheck
  return FileQuestion
}

function nodeText(node) {
  if (!node?.data) return '未知节点'
  const label = node.data.label || '待确认'
  const value = node.data.value ? `，${node.data.value}` : ''
  return `${node.data.categoryLabel || '节点'}：${label}${value}`
}

function nodeAriaLabel(node) {
  const data = node?.data || {}
  return [data.categoryLabel, data.label, data.value, data.supportLabel, data.detail]
    .filter(Boolean)
    .join('，')
}

function selectNode(id) {
  if (model.value.nodes.some((node) => node.id === id)) selectedNodeId.value = id
}

function handleFlowInit(api) {
  flowApi = api
  fitGraph()
}

async function fitGraph() {
  if (!flowApi) return
  await nextTick()
  flowApi.fitView({ padding: isCompact.value ? 0.08 : 0.12, minZoom: 0.48, maxZoom: 1, duration: 0 })
}

watch(
  model,
  () => {
    if (!model.value.nodes.some((node) => node.id === selectedNodeId.value)) {
      selectedNodeId.value = model.value.nodes[0]?.id || ''
    }
    fitGraph()
  },
  { immediate: true },
)

watch(isCompact, fitGraph)

onMounted(() => {
  if (typeof ResizeObserver === 'undefined' || !canvasElement.value) return
  resizeObserver = new ResizeObserver(([entry]) => {
    const width = Math.round(entry?.contentRect?.width || 0)
    if (width > 0 && width !== canvasWidth.value) {
      canvasWidth.value = width
      fitGraph()
    }
  })
  resizeObserver.observe(canvasElement.value)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  flowApi = null
})
</script>

<template>
  <section
    class="decision-flow-graph"
    data-testid="decision-flow-graph"
    aria-labelledby="decision-flow-title"
  >
    <header class="decision-flow-heading">
      <div>
        <p class="decision-flow-kicker">DECISION TRACE</p>
        <h3 id="decision-flow-title">判断依据关系</h3>
      </div>
      <span class="decision-flow-count">{{ model.nodes.length }} 个节点</span>
    </header>

    <p v-if="emptyMessage" class="decision-flow-empty" role="status">
      <FileQuestion :size="18" aria-hidden="true" />
      <span>{{ emptyMessage }}</span>
    </p>

    <div class="decision-flow-layout">
      <div ref="canvasElement" class="decision-flow-canvas-shell">
        <VueFlow
          class="decision-flow-canvas"
          data-testid="decision-flow-canvas"
          :style="{ height: `${canvasHeight}px` }"
          :nodes="flowNodes"
          :edges="flowEdges"
          :fit-view-on-init="true"
          :min-zoom="0.4"
          :max-zoom="1.2"
          :nodes-draggable="false"
          :nodes-connectable="false"
          :edges-updatable="false"
          :elements-selectable="false"
          :nodes-focusable="false"
          :edges-focusable="false"
          :zoom-on-scroll="false"
          :zoom-on-pinch="true"
          :zoom-on-double-click="false"
          :pan-on-scroll="false"
          :pan-on-drag="true"
          :prevent-scrolling="false"
          @init="handleFlowInit"
          @node-click="({ node }) => selectNode(node.id)"
        >
          <template #node-decision="{ id, data, sourcePosition, targetPosition }">
            <button
              type="button"
              class="decision-node"
              :class="[`decision-node-${data.category}`, { 'decision-node-active': selectedNodeId === id }]"
              :data-node-id="id"
              :aria-label="nodeAriaLabel({ data })"
              :aria-pressed="selectedNodeId === id"
              :aria-controls="detailId"
              @click.stop="selectNode(id)"
              @keydown.enter.prevent.stop="selectNode(id)"
              @keydown.space.prevent.stop="selectNode(id)"
            >
              <Handle
                type="target"
                :position="targetPosition || Position.Left"
                :connectable="false"
                tabindex="-1"
                aria-hidden="true"
              />
              <span class="decision-node-topline">
                <span class="decision-node-icon" aria-hidden="true">
                  <component :is="nodeIcon(data.category)" :size="16" :stroke-width="1.9" />
                </span>
                <span>{{ data.categoryLabel }}</span>
              </span>
              <strong>{{ data.label }}</strong>
              <span v-if="data.value" class="decision-node-value">{{ data.value }}</span>
              <small v-if="data.supportLabel">{{ data.supportLabel }}</small>
              <Handle
                type="source"
                :position="sourcePosition || Position.Right"
                :connectable="false"
                tabindex="-1"
                aria-hidden="true"
              />
            </button>
          </template>
        </VueFlow>
      </div>

      <aside
        :id="detailId"
        class="decision-flow-detail"
        data-testid="decision-flow-detail"
        aria-live="polite"
        aria-atomic="true"
      >
        <template v-if="selectedNode">
          <span class="decision-flow-detail-label">{{ selectedNode.data.categoryLabel }}</span>
          <h4>{{ selectedNode.data.label }}</h4>
          <dl v-if="detailRows.length">
            <div v-for="([label, value]) in detailRows" :key="label">
              <dt>{{ label }}</dt>
              <dd>{{ value }}</dd>
            </div>
          </dl>
          <p v-else>该节点没有更多结构化信息。</p>
        </template>
      </aside>
    </div>

    <p class="decision-flow-note">{{ model.relationshipNote }}</p>

    <details class="decision-flow-text">
      <summary>文本版判断路径</summary>
      <ol>
        <li v-for="node in model.nodes" :key="node.id">
          <strong>{{ nodeText(node) }}</strong>
          <span v-if="node.data.supportLabel">，{{ node.data.supportLabel }}</span>
          <span v-if="node.data.detail">。{{ node.data.detail }}</span>
        </li>
      </ol>
      <div class="decision-flow-relations">
        <strong>关系</strong>
        <p>{{ model.relationshipNote }}</p>
        <ul v-if="relationshipText.length">
          <li v-for="relation in relationshipText" :key="relation.id">{{ relation.text }}</li>
        </ul>
        <p v-else>当前没有真实依据关系可展示。</p>
      </div>
    </details>
  </section>
</template>

<style scoped>
.decision-flow-graph {
  width: 100%;
  min-width: 0;
  container: decision-flow / inline-size;
  color: var(--text-primary);
}

.decision-flow-heading,
.decision-flow-layout,
.decision-flow-node-topline,
.decision-flow-empty {
  display: flex;
  align-items: center;
}

.decision-flow-heading {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.decision-flow-kicker {
  margin: 0 0 3px;
  color: var(--primary);
  font-size: 12px;
  font-weight: 750;
  line-height: 1.2;
  letter-spacing: 0;
}

.decision-flow-heading h3 {
  margin: 0;
  font-size: 18px;
  line-height: 1.3;
  letter-spacing: 0;
}

.decision-flow-count {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-size: 12px;
}

.decision-flow-empty {
  gap: 9px;
  margin: 0 0 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.55;
}

.decision-flow-empty svg {
  flex: 0 0 auto;
  color: var(--warning);
}

.decision-flow-layout {
  align-items: stretch;
  gap: 14px;
}

.decision-flow-canvas-shell {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-muted);
}

.decision-flow-canvas {
  width: 100%;
  min-height: 390px;
}

.decision-flow-canvas :deep(.vue-flow__pane) {
  cursor: grab;
}

.decision-flow-canvas :deep(.vue-flow__pane:active) {
  cursor: grabbing;
}

.decision-flow-canvas :deep(.vue-flow__node) {
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.decision-flow-canvas :deep(.vue-flow__node-decision) {
  width: 220px;
}

.decision-flow-canvas :deep(.vue-flow__edge-path) {
  stroke-linecap: round;
}

.decision-node {
  position: relative;
  width: 220px;
  min-height: 84px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 5px;
  padding: 12px 14px;
  overflow: visible;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  background: var(--surface-elevated);
  box-shadow: var(--shadow-sm);
  color: var(--text-primary);
  text-align: left;
  font: inherit;
  cursor: pointer;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.decision-node:hover {
  border-color: var(--primary);
  box-shadow: var(--shadow-card);
  transform: translateY(-1px);
}

.decision-node:focus-visible {
  outline: 3px solid var(--primary-subtle);
  outline-offset: 3px;
}

.decision-node-active {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-soft), var(--shadow-card);
}

.decision-node-topline {
  gap: 6px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
}

.decision-node-icon {
  width: 23px;
  height: 23px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  color: var(--primary);
}

.decision-node strong,
.decision-node-value,
.decision-node small {
  width: 100%;
  overflow-wrap: anywhere;
  letter-spacing: 0;
}

.decision-node strong {
  font-size: 14px;
  line-height: 1.35;
}

.decision-node-value {
  color: var(--primary);
  font-size: 15px;
  font-weight: 750;
  line-height: 1.35;
}

.decision-node small {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.35;
}

.decision-node-rule .decision-node-icon {
  background: var(--danger-soft);
  color: var(--danger);
}

.decision-node-evidence .decision-node-icon,
.decision-node-retrieval .decision-node-icon {
  background: var(--success-soft);
  color: var(--success);
}

.decision-node-outcome .decision-node-icon {
  background: var(--warning-soft);
  color: var(--warning);
}

.decision-node-empty {
  border-style: dashed;
  box-shadow: none;
}

.decision-node-empty .decision-node-icon {
  background: var(--warning-soft);
  color: var(--warning);
}

.decision-node :deep(.vue-flow__handle) {
  width: 8px;
  height: 8px;
  border: 2px solid var(--surface-elevated);
  background: var(--text-muted);
  pointer-events: none;
}

.decision-flow-detail {
  width: min(29%, 290px);
  min-width: 230px;
  padding: 16px;
  border-left: 3px solid var(--primary);
  border-radius: 0 var(--radius-md) var(--radius-md) 0;
  background: var(--surface-muted);
}

.decision-flow-detail-label {
  color: var(--primary);
  font-size: 12px;
  font-weight: 750;
}

.decision-flow-detail h4 {
  margin: 5px 0 14px;
  overflow-wrap: anywhere;
  font-size: 16px;
  line-height: 1.4;
  letter-spacing: 0;
}

.decision-flow-detail dl,
.decision-flow-detail p {
  margin: 0;
}

.decision-flow-detail dl > div {
  padding: 10px 0;
  border-top: 1px solid var(--border-subtle);
}

.decision-flow-detail dt {
  margin-bottom: 4px;
  color: var(--text-muted);
  font-size: 12px;
}

.decision-flow-detail dd,
.decision-flow-detail p {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.55;
}

.decision-flow-note {
  margin: 10px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.decision-flow-text {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: 13px;
}

.decision-flow-text summary {
  width: fit-content;
  color: var(--primary);
  font-weight: 700;
  cursor: pointer;
}

.decision-flow-text summary:focus-visible {
  outline: 3px solid var(--primary-subtle);
  outline-offset: 3px;
}

.decision-flow-text ol,
.decision-flow-text ul {
  margin: 10px 0 0;
  padding-left: 22px;
}

.decision-flow-text li {
  margin: 7px 0;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.decision-flow-relations {
  margin-top: 14px;
}

.decision-flow-relations > p {
  margin: 7px 0 0;
}

@container decision-flow (max-width: 900px) {
  .decision-flow-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }

  .decision-flow-detail {
    width: auto;
    min-width: 0;
    border-top: 3px solid var(--primary);
    border-left: 0;
    border-radius: 0 0 var(--radius-md) var(--radius-md);
  }
}

@container decision-flow (max-width: 520px) {
  .decision-flow-heading {
    align-items: flex-start;
  }

  .decision-flow-count {
    padding-top: 4px;
  }

  .decision-flow-canvas {
    min-height: 500px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .decision-node {
    transition: none;
  }

  .decision-node:hover {
    transform: none;
  }

  .decision-flow-canvas :deep(*) {
    animation: none !important;
    transition: none !important;
  }
}
</style>
