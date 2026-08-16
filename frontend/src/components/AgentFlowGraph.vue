<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { Handle, Position, VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { buildAgentFlowModel } from '../lib/triageVisualization'

const props = defineProps({
  agents: {
    type: Array,
    default: () => [],
  },
  statusByKey: {
    type: Object,
    default: () => ({}),
  },
  traceState: {
    type: Object,
    default: () => ({}),
  },
})

const selectedNodeId = ref('')
const prefersReducedMotion = ref(false)
const flowInstance = shallowRef(null)
let motionMediaQuery = null

const flowModel = computed(() => buildAgentFlowModel(
  props.agents,
  props.statusByKey,
  props.traceState,
))

const graphNodes = computed(() => flowModel.value.nodes || [])
const graphEdges = computed(() => (flowModel.value.edges || []).map((edge) => ({
  ...edge,
  animated: Boolean(edge.animated) && !prefersReducedMotion.value,
})))

const selectedNode = computed(() => {
  const nodes = graphNodes.value
  return nodes.find((node) => node.id === selectedNodeId.value)
    || nodes.find((node) => node.data?.status === 'running')
    || nodes[0]
    || null
})

const graphContentWidth = computed(() => `${Math.max(680, graphNodes.value.length * 228)}px`)

function selectNode(payload) {
  const node = payload?.node || payload
  if (node?.id) selectedNodeId.value = node.id
}

function nodeAriaLabel(node) {
  const data = node?.data || {}
  return [data.title || data.label, data.statusLabel, data.detail]
    .filter(Boolean)
    .join('，')
}

function fitGraph() {
  return flowInstance.value?.fitView?.({
    padding: 0.08,
    minZoom: 0.35,
    maxZoom: 1,
    duration: prefersReducedMotion.value ? 0 : 180,
  })
}

function handleFlowInit(instance) {
  flowInstance.value = instance
  fitGraph()
}

function handleMotionPreferenceChange(event) {
  prefersReducedMotion.value = event.matches
}

watch(
  () => graphNodes.value.map((node) => node.id).join('|'),
  async () => {
    await nextTick()
    fitGraph()
  },
)

onMounted(() => {
  motionMediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  prefersReducedMotion.value = motionMediaQuery.matches
  motionMediaQuery.addEventListener('change', handleMotionPreferenceChange)
})

onBeforeUnmount(() => {
  motionMediaQuery?.removeEventListener('change', handleMotionPreferenceChange)
  flowInstance.value = null
})
</script>

<template>
  <section
    class="agent-flow-graph"
    data-testid="agent-flow-graph"
    aria-labelledby="agent-flow-title"
  >
    <header class="agent-flow-header">
      <div>
        <span>实时协同路径</span>
        <h3 id="agent-flow-title">智能体执行拓扑</h3>
      </div>
      <strong aria-live="polite">
        {{ graphNodes.filter((node) => node.data?.status === 'done').length }}/{{ graphNodes.length }} 已完成
      </strong>
    </header>

    <div v-if="graphNodes.length" class="agent-flow-canvas-scroll">
      <VueFlow
        class="agent-flow-canvas"
        data-testid="agent-flow-canvas"
        :style="{ '--agent-flow-content-width': graphContentWidth }"
        :nodes="graphNodes"
        :edges="graphEdges"
        :nodes-draggable="false"
        :nodes-connectable="false"
        :elements-selectable="true"
        :zoom-on-scroll="false"
        :zoom-on-double-click="false"
        :pan-on-drag="false"
        :pan-on-scroll="false"
        :prevent-scrolling="false"
        :min-zoom="0.35"
        :max-zoom="1"
        :fit-view-on-init="true"
        aria-label="问诊智能体实时执行拓扑图"
        @init="handleFlowInit"
        @node-click="selectNode"
      >
        <template #node-agent="{ id, data }">
          <button
            type="button"
            class="agent-flow-node"
            :class="[`is-${data.status}`, { 'is-selected': selectedNode?.id === id }]"
            :data-node-id="id"
            :aria-label="nodeAriaLabel({ id, data })"
            :aria-pressed="selectedNode?.id === id"
            @click.stop="selectNode({ id, data })"
          >
            <Handle type="target" :position="Position.Left" :connectable="false" />
            <span class="agent-flow-node-state">
              <i aria-hidden="true" />
              {{ data.statusLabel }}
            </span>
            <strong>{{ data.label }}</strong>
            <small>{{ data.detail }}</small>
            <Handle type="source" :position="Position.Right" :connectable="false" />
          </button>
        </template>
      </VueFlow>
    </div>

    <p v-else class="agent-flow-empty" role="status">等待工作流节点</p>

    <ol v-if="graphNodes.length" class="agent-flow-equivalent" aria-label="智能体执行状态文本">
      <li v-for="node in graphNodes" :key="node.id">
        <button
          type="button"
          :class="{ 'is-selected': selectedNode?.id === node.id }"
          :aria-pressed="selectedNode?.id === node.id"
          :aria-label="nodeAriaLabel(node)"
          @click="selectNode(node)"
        >
          <span :class="`is-${node.data.status}`" aria-hidden="true" />
          <strong>{{ node.data.label }}</strong>
          <small>{{ node.data.statusLabel }}</small>
        </button>
      </li>
    </ol>

    <div
      v-if="selectedNode"
      class="agent-flow-detail"
      data-testid="agent-flow-detail"
      aria-live="polite"
    >
      <div>
        <span>当前节点</span>
        <strong>{{ selectedNode.data.title }}</strong>
      </div>
      <span class="agent-flow-detail-status" :class="`is-${selectedNode.data.status}`">
        {{ selectedNode.data.statusLabel }}
      </span>
      <p>{{ selectedNode.data.description || selectedNode.data.detail }}</p>
      <small v-if="selectedNode.data.description">{{ selectedNode.data.detail }}</small>
    </div>
  </section>
</template>

<style scoped>
.agent-flow-graph {
  min-width: 0;
  color: var(--text-primary);
}

.agent-flow-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.agent-flow-header span,
.agent-flow-detail > div > span {
  display: block;
  margin-bottom: 3px;
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
}

.agent-flow-header h3 {
  margin: 0;
  font-size: 16px;
  letter-spacing: 0;
}

.agent-flow-header > strong {
  color: var(--text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.agent-flow-canvas-scroll {
  width: 100%;
  height: 286px;
  overflow-x: auto;
  overflow-y: hidden;
  border: 1px solid var(--border-default);
  border-radius: 8px;
  background: var(--surface-muted);
  overscroll-behavior-x: contain;
}

.agent-flow-canvas {
  width: 100%;
  min-width: 0;
  height: 284px;
  background-image:
    linear-gradient(var(--border-subtle) 1px, transparent 1px),
    linear-gradient(90deg, var(--border-subtle) 1px, transparent 1px);
  background-size: 28px 28px;
}

.agent-flow-canvas :deep(.vue-flow__pane) {
  cursor: default;
}

.agent-flow-canvas :deep(.vue-flow__edge-path) {
  stroke: var(--border-strong);
  stroke-width: 2;
}

.agent-flow-canvas :deep(.vue-flow__edge.animated .vue-flow__edge-path) {
  stroke: var(--primary);
}

.agent-flow-canvas :deep(.vue-flow__node-agent) {
  width: 192px;
  cursor: pointer;
}

.agent-flow-canvas :deep(.vue-flow__node-agent:focus-visible) {
  outline: 3px solid var(--focus-ring);
  outline-offset: 3px;
}

.agent-flow-node {
  position: relative;
  display: grid;
  width: 192px;
  min-height: 106px;
  align-content: start;
  gap: 7px;
  padding: 13px 14px;
  overflow: hidden;
  border: 1px solid var(--border-default);
  border-radius: 8px;
  background: var(--surface-elevated);
  color: var(--text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.agent-flow-node:hover,
.agent-flow-node.is-selected {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.agent-flow-node:focus-visible {
  outline: 3px solid var(--focus-ring);
  outline-offset: 2px;
}

.agent-flow-node-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 12px;
}

.agent-flow-node-state i,
.agent-flow-equivalent button > span {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--text-subtle);
}

.agent-flow-node > strong {
  font-size: 14px;
  line-height: 1.35;
}

.agent-flow-node > small {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.agent-flow-node.is-running {
  border-color: var(--primary);
  background: color-mix(in srgb, var(--primary-soft) 72%, var(--surface-elevated));
}

.agent-flow-node.is-done {
  border-color: color-mix(in srgb, var(--success) 42%, var(--border-default));
}

.agent-flow-node.is-error {
  border-color: var(--danger);
  background: color-mix(in srgb, var(--danger-soft) 68%, var(--surface-elevated));
}

.is-running .agent-flow-node-state,
.agent-flow-detail-status.is-running {
  color: var(--primary);
}

.is-running .agent-flow-node-state i,
.agent-flow-equivalent button > span.is-running {
  background: var(--primary);
  animation: agent-flow-pulse 1.2s ease-in-out infinite;
}

.is-done .agent-flow-node-state,
.agent-flow-detail-status.is-done {
  color: var(--success);
}

.is-done .agent-flow-node-state i,
.agent-flow-equivalent button > span.is-done {
  background: var(--success);
}

.is-error .agent-flow-node-state,
.agent-flow-detail-status.is-error {
  color: var(--danger);
}

.is-error .agent-flow-node-state i,
.agent-flow-equivalent button > span.is-error {
  background: var(--danger);
}

.agent-flow-node :deep(.vue-flow__handle) {
  width: 7px;
  height: 7px;
  border-color: var(--surface-elevated);
  background: var(--primary);
  pointer-events: none;
}

.agent-flow-equivalent {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
  gap: 6px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.agent-flow-equivalent button {
  display: grid;
  width: 100%;
  min-height: 54px;
  grid-template-columns: 9px minmax(0, 1fr);
  align-items: center;
  gap: 3px 7px;
  padding: 8px 9px;
  border: 1px solid var(--border-subtle);
  border-radius: 6px;
  background: var(--surface-elevated);
  color: var(--text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.agent-flow-equivalent button:hover,
.agent-flow-equivalent button.is-selected {
  border-color: var(--primary);
  background: var(--primary-soft);
}

.agent-flow-equivalent button:focus-visible {
  outline: 3px solid var(--focus-ring);
  outline-offset: 1px;
}

.agent-flow-equivalent strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 12px;
}

.agent-flow-equivalent small {
  grid-column: 2;
  color: var(--text-muted);
  font-size: 12px;
}

.agent-flow-detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px 18px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-default);
}

.agent-flow-detail > div > strong {
  display: block;
  font-size: 14px;
}

.agent-flow-detail-status {
  align-self: center;
  font-size: 12px;
  font-weight: 700;
}

.agent-flow-detail p,
.agent-flow-detail > small {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.55;
}

.agent-flow-detail > small {
  color: var(--text-muted);
}

.agent-flow-empty {
  display: grid;
  min-height: 150px;
  margin: 0;
  place-items: center;
  border: 1px dashed var(--border-default);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 13px;
}

@keyframes agent-flow-pulse {
  50% {
    opacity: 0.4;
    transform: scale(0.72);
  }
}

@media (max-width: 640px) {
  .agent-flow-header {
    align-items: flex-start;
  }

  .agent-flow-canvas {
    width: var(--agent-flow-content-width);
    min-width: var(--agent-flow-content-width);
  }

  .agent-flow-equivalent {
    grid-template-columns: 1fr;
  }

  .agent-flow-detail {
    grid-template-columns: minmax(0, 1fr);
  }

  .agent-flow-detail-status {
    justify-self: start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .is-running .agent-flow-node-state i,
  .agent-flow-equivalent button > span.is-running {
    animation: none;
  }

  .agent-flow-canvas :deep(.vue-flow__edge.animated .vue-flow__edge-path) {
    animation: none;
  }
}
</style>
