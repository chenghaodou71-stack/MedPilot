const RISK_STEPS = Object.freeze([
  { key: 'low', value: '低', label: '低风险' },
  { key: 'medium', value: '中', label: '中风险' },
  { key: 'high', value: '高', label: '高风险' },
])

const URGENCY_STEPS = Object.freeze([
  { key: 'immediate', label: '立即处置', detail: '立即就医或呼叫急救' },
  { key: 'soon', label: '尽快就医', detail: '尽快前往合适医疗机构' },
  { key: 'outpatient', label: '门诊就医', detail: '尽早安排门诊就诊' },
])

const STATUS_LABELS = Object.freeze({
  waiting: '等待中',
  running: '处理中',
  done: '已完成',
  error: '异常',
})

const RUNNING_DETAILS = Object.freeze({
  safety_screen: '正在筛查危险信号',
  extract: '正在识别症状信息',
  retrieve: '正在检索医学依据',
  classify: '正在评估风险与科室',
  compose: '正在生成行动建议',
  ask_followup: '正在整理补充问题',
})

function toText(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function toPercent(value) {
  if (value === '' || value === null || value === undefined) return ''
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) return ''
  const percent = number <= 1 ? number * 100 : number
  return `${Math.round(Math.min(100, percent))}%`
}

function nodeElapsed(traceState, key) {
  const value = Number(traceState?.nodes?.[key]?.elapsedMs)
  return Number.isFinite(value) && value >= 0 ? value : null
}

function completedDetail(agent, traceState) {
  const elapsed = nodeElapsed(traceState, agent.key)
  return `${agent.shortTitle || agent.title || agent.key}完成${elapsed === null ? '' : ` · ${elapsed} ms`}`
}

function agentDetail(agent, status, traceState) {
  if (status === 'running') return RUNNING_DETAILS[agent.key] || `正在执行${agent.shortTitle || agent.title || '当前节点'}`
  if (status === 'done') return completedDetail(agent, traceState)
  if (status === 'error') return `${agent.shortTitle || agent.title || agent.key}执行异常`
  return `等待执行${agent.shortTitle || agent.title || '当前节点'}`
}

export function buildRiskScale(riskLevel) {
  const normalized = toText(riskLevel).replace(/风险$/u, '')
  const activeIndex = RISK_STEPS.findIndex((step) => step.value === normalized)
  return {
    activeIndex,
    label: activeIndex === -1 ? '风险待确认' : RISK_STEPS[activeIndex].label,
    steps: RISK_STEPS.map((step, index) => ({
      ...step,
      active: index === activeIndex,
    })),
  }
}

export function buildUrgencyScale(urgency) {
  const value = toText(urgency)
  let activeIndex = -1
  if (/立即|急救/u.test(value)) activeIndex = 0
  else if (/尽快/u.test(value)) activeIndex = 1
  else if (/尽早|门诊/u.test(value)) activeIndex = 2

  return {
    activeIndex,
    label: activeIndex === -1 ? '时效待确认' : URGENCY_STEPS[activeIndex].label,
    value,
    steps: URGENCY_STEPS.map((step, index) => ({
      ...step,
      active: index === activeIndex,
    })),
  }
}

export function buildAgentFlowModel(agents, statusByKey = {}, traceState = {}) {
  const safeAgents = Array.isArray(agents) ? agents.filter((agent) => agent?.key) : []
  const nodes = safeAgents.map((agent, index) => {
    const status = STATUS_LABELS[statusByKey[agent.key]] ? statusByKey[agent.key] : 'waiting'
    return {
      id: agent.key,
      type: 'agent',
      position: { x: index * 228, y: 24 },
      sourcePosition: 'right',
      targetPosition: 'left',
      selectable: true,
      draggable: false,
      data: {
        label: agent.shortTitle || agent.title || agent.key,
        title: agent.title || agent.shortTitle || agent.key,
        description: toText(agent.description),
        status,
        statusLabel: STATUS_LABELS[status],
        detail: agentDetail(agent, status, traceState),
        elapsedMs: nodeElapsed(traceState, agent.key),
      },
    }
  })

  const edges = nodes.slice(1).map((node, index) => ({
    id: `agent-edge-${index}`,
    source: nodes[index].id,
    target: node.id,
    type: 'smoothstep',
    animated: node.data.status === 'running',
    data: { status: node.data.status },
  }))

  return { nodes, edges }
}

function normalizeSymptoms(symptoms) {
  const values = Array.isArray(symptoms?.symptoms) ? symptoms.symptoms : []
  return values.map(toText).filter(Boolean)
}

function normalizeDecisionFactors(triage, evidence) {
  if (triage?.abstained === true) return []
  const byCitation = new Map(
    (Array.isArray(evidence) ? evidence : [])
      .filter((item) => item?.citation_id)
      .map((item) => [item.citation_id, item]),
  )
  const factors = Array.isArray(triage?.factors) ? triage.factors : []
  const normalized = factors
    .filter((factor) => factor && ['rule', 'evidence'].includes(factor.kind) && toText(factor.label))
    .map((factor) => {
      const source = byCitation.get(factor.reference)
      return {
        kind: factor.kind,
        label: toText(factor.label),
        reference: toText(factor.reference),
        supportLabel: toPercent(factor.support)
          ? `${factor.kind === 'rule' ? '规则支持分' : '检索支持度'} ${toPercent(factor.support)}`
          : '',
        detail: toText(factor.detail) || toText(source?.quote) || toText(source?.source),
        source: toText(source?.source),
      }
    })

  if (normalized.length) return normalized
  const matchedRule = toText(triage?.matched_rule)
  if (matchedRule) {
    return [{
      kind: 'rule',
      label: matchedRule,
      reference: matchedRule,
      supportLabel: toPercent(triage?.support_score)
        ? `规则支持分 ${toPercent(triage.support_score)}`
        : '',
      detail: toText(triage?.explanation),
      source: '',
    }]
  }

  return []
}

function normalizeRetrievalMaterials(evidence) {
  return (Array.isArray(evidence) ? evidence : [])
    .filter((item) => item && (toText(item.source) || toText(item.citation_id)))
    .map((item) => ({
      kind: 'retrieval',
      label: toText(item.source) || toText(item.citation_id),
      reference: toText(item.citation_id),
      supportLabel: toPercent(item.score) ? `检索相似度 ${toPercent(item.score)}` : '',
      detail: toText(item.quote) || toText(item.text),
      source: toText(item.source),
    }))
}

function columnPositions(count, x, spacing, centerY = 120) {
  const height = Math.max(0, (count - 1) * spacing)
  const start = Math.max(20, centerY - height / 2)
  return Array.from({ length: count }, (_, index) => ({ x, y: start + index * spacing }))
}

export function buildDecisionFlowModel(input = {}) {
  const symptoms = normalizeSymptoms(input.symptoms)
  const triage = input.triage && typeof input.triage === 'object' ? input.triage : {}
  const factors = normalizeDecisionFactors(triage, input.evidence)
  const retrievalMaterials = factors.length ? [] : normalizeRetrievalMaterials(input.evidence)
  const hasDecisionBasis = factors.length > 0
  const hasRetrievalMaterials = retrievalMaterials.length > 0
  const symptomItems = symptoms.length ? symptoms : ['未提取到结构化症状']
  const middleItems = hasDecisionBasis
    ? factors
    : hasRetrievalMaterials
      ? retrievalMaterials
      : [{ kind: 'empty', label: '暂无可展示依据' }]
  const outcomeItems = [
    { key: 'department', label: '推荐科室', value: toText(triage.department) || '待确认' },
    {
      key: 'risk',
      label: '风险等级',
      value: triage.abstained ? '待确认' : (toText(triage.risk_level) || '待确认'),
    },
    { key: 'urgency', label: '就医时效', value: toText(triage.urgency) || '待确认' },
  ]
  const symptomPositions = columnPositions(symptomItems.length, 20, 96, 150)
  const middlePositions = columnPositions(middleItems.length, 330, 112, 150)
  const outcomePositions = columnPositions(outcomeItems.length, 660, 104, 150)

  const symptomNodes = symptomItems.map((label, index) => ({
    id: symptoms.length ? `symptom-${index}` : 'symptom-empty',
    type: 'decision',
    position: symptomPositions[index],
    sourcePosition: 'right',
    targetPosition: 'left',
    draggable: false,
    data: {
      category: 'symptom',
      categoryLabel: '症状',
      label,
      detail: symptoms.length
        ? [toText(input.symptoms?.duration), toText(input.symptoms?.severity)].filter(Boolean).join(' · ')
        : '系统没有返回可用于关系图的结构化症状。',
    },
  }))

  const middleNodes = middleItems.map((factor, index) => ({
    id: hasDecisionBasis
      ? `factor-${index}`
      : hasRetrievalMaterials
        ? `material-${index}`
        : 'factor-empty',
    type: 'decision',
    position: middlePositions[index],
    sourcePosition: 'right',
    targetPosition: 'left',
    draggable: false,
    data: {
      category: factor.kind === 'rule'
        ? 'rule'
        : factor.kind === 'empty'
          ? 'empty'
          : factor.kind === 'retrieval'
            ? 'retrieval'
            : 'evidence',
      categoryLabel: factor.kind === 'rule'
        ? '安全规则'
        : factor.kind === 'empty'
          ? '依据状态'
          : factor.kind === 'retrieval'
            ? '检索资料'
            : '检索依据',
      label: factor.label,
      supportLabel: factor.supportLabel || '',
      detail: factor.detail || (factor.kind === 'empty'
        ? triage.abstained
          ? '本次因证据不足暂缓判断，没有结构化分诊依据可供展示。'
          : '本次没有返回可验证的结构化判断依据。'
        : ''),
      reference: factor.reference || '',
      source: factor.source || '',
    },
  }))

  const outcomeNodes = outcomeItems.map((outcome, index) => ({
    id: `outcome-${outcome.key}`,
    type: 'decision',
    position: outcomePositions[index],
    sourcePosition: 'right',
    targetPosition: 'left',
    draggable: false,
    data: {
      category: 'outcome',
      categoryLabel: '分诊结论',
      label: outcome.label,
      value: outcome.value,
      detail: triage.abstained ? '证据不足，系统已暂缓确定科室结论。' : toText(triage.explanation),
    },
  }))

  const edges = []
  if (hasDecisionBasis) {
    symptomNodes.forEach((symptomNode) => {
      middleNodes.forEach((factorNode) => {
        edges.push({
          id: `${symptomNode.id}-${factorNode.id}`,
          source: symptomNode.id,
          target: factorNode.id,
          type: 'smoothstep',
        })
      })
    })
    middleNodes.forEach((factorNode) => {
      outcomeNodes.forEach((outcomeNode) => {
        edges.push({
          id: `${factorNode.id}-${outcomeNode.id}`,
          source: factorNode.id,
          target: outcomeNode.id,
          type: 'smoothstep',
        })
      })
    })
  }

  return {
    hasDecisionBasis,
    hasRetrievalMaterials,
    hasEvidence: hasDecisionBasis,
    relationshipNote: '连线仅表示本次返回数据的结构化关联，不代表单项因果或疾病诊断。',
    nodes: [...symptomNodes, ...middleNodes, ...outcomeNodes],
    edges,
  }
}
