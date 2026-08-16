const INTENTS = new Set(['medical_consult', 'emergency'])
const PHASES = new Set([
  'screening',
  'collecting',
  'summarizing',
  'retrieving',
  'triaging',
  'composing',
  'awaiting_followup',
  'completed',
  'escalated',
  'failed',
])
const HISTORY_MODES = new Set(['full', 'summary'])

export class ConsultTraceProtocolError extends Error {
  constructor(message) {
    super(message)
    this.name = 'ConsultTraceProtocolError'
  }
}

export function createConsultTraceState() {
  return {
    protocolVersion: null,
    traceId: null,
    sessionId: null,
    expectedSequence: 1,
    status: 'idle',
    intent: null,
    phase: null,
    turnCount: 0,
    historyMode: 'full',
    nodes: {},
    events: [],
    emergency: null,
    symptoms: null,
    evidence: [],
    triage: null,
    answer: null,
    citations: [],
    followup: null,
    awaitingFollowup: false,
    error: '',
  }
}

function requiredString(value, field) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new ConsultTraceProtocolError(`${field} 缺失或无效。`)
  }
  return value
}

function validateEnvelope(current, event) {
  if (!event || typeof event !== 'object' || Array.isArray(event)) {
    throw new ConsultTraceProtocolError('事件必须是 JSON 对象。')
  }
  if (event.protocol_version !== '1.0') {
    throw new ConsultTraceProtocolError('protocol_version 不受支持。')
  }
  const traceId = requiredString(event.trace_id, 'trace_id')
  const sessionId = requiredString(event.session_id, 'session_id')
  if (current.traceId && current.traceId !== traceId) {
    throw new ConsultTraceProtocolError('同一响应流中的 trace_id 不一致。')
  }
  if (current.sessionId && current.sessionId !== sessionId) {
    throw new ConsultTraceProtocolError('同一响应流中的 session_id 不一致。')
  }
  if (!Number.isInteger(event.sequence) || event.sequence !== current.expectedSequence) {
    throw new ConsultTraceProtocolError(`事件 sequence 应为 ${current.expectedSequence}。`)
  }
  if (!Number.isFinite(event.elapsed_ms) || event.elapsed_ms < 0) {
    throw new ConsultTraceProtocolError('elapsed_ms 必须是非负数。')
  }
  const state = event.state
  if (!state || typeof state !== 'object') {
    throw new ConsultTraceProtocolError('事件 state 缺失。')
  }
  if (!INTENTS.has(state.intent)) throw new ConsultTraceProtocolError('state.intent 无效。')
  if (!PHASES.has(state.phase)) throw new ConsultTraceProtocolError('state.phase 无效。')
  if (!HISTORY_MODES.has(state.history_mode)) {
    throw new ConsultTraceProtocolError('state.history_mode 无效。')
  }
  if (!Number.isInteger(state.turn_count) || state.turn_count < 1) {
    throw new ConsultTraceProtocolError('state.turn_count 无效。')
  }
  if (!event.data || typeof event.data !== 'object' || Array.isArray(event.data)) {
    throw new ConsultTraceProtocolError('事件 data 必须是对象。')
  }
  if (current.status === 'done') {
    throw new ConsultTraceProtocolError('done 之后不能再接收事件。')
  }
}

function updateCompletedData(next, node, data) {
  if (node === 'safety_screen') {
    const safety = data.safety
    if (safety?.matched) {
      next.emergency = {
        matchedTerms: Array.isArray(safety.matched_terms) ? [...safety.matched_terms] : [],
        department: safety.department || '',
        riskLevel: safety.risk_level || '高',
        urgency: safety.urgency || '建议立即就医或呼叫急救',
        ruleId: safety.rule_id || '',
      }
    }
    return
  }
  if (node === 'extract') {
    if (data.symptoms) next.symptoms = data.symptoms
    return
  }
  if (node === 'retrieve') {
    next.evidence = Array.isArray(data.evidence) ? [...data.evidence] : []
    return
  }
  if (node === 'classify') {
    next.triage = data.triage || null
    return
  }
  if (node === 'compose') {
    next.answer = data.answer || null
    next.citations = resolveCitations(next.evidence, data.answer?.citations)
    return
  }
  if (node === 'ask_followup') {
    next.followup = data.followup || null
  }
}

function resolveCitations(evidence, answerCitations) {
  if (!Array.isArray(answerCitations)) return []
  const byId = new Map(
    evidence
      .filter((item) => item?.citation_id)
      .map((item) => [item.citation_id, item]),
  )
  return answerCitations.map((citation) => {
    if (typeof citation === 'string') {
      return byId.get(citation) || { citation_id: citation, source: citation }
    }
    const fromEvidence = byId.get(citation?.citation_id)
    return fromEvidence ? { ...fromEvidence, ...citation } : { ...citation }
  })
}

function reduceNode(next, event) {
  const node = requiredString(event.node, 'node')
  const previous = next.nodes[node]
  if (event.status === 'started') {
    if (previous) throw new ConsultTraceProtocolError(`${node} 重复 started。`)
    next.nodes[node] = { status: 'running', elapsedMs: null, label: event.label || node }
    next.status = 'running'
    return
  }
  if (!['completed', 'error'].includes(event.status)) {
    throw new ConsultTraceProtocolError(`${node} 的 status 无效。`)
  }
  if (previous?.status !== 'running') {
    throw new ConsultTraceProtocolError(`${node} 未 started 就结束。`)
  }
  next.nodes[node] = {
    ...previous,
    status: event.status === 'completed' ? 'done' : 'error',
    elapsedMs: event.elapsed_ms,
  }
  if (event.status === 'error') {
    next.status = 'error'
    next.error = event.data.detail || '智能体执行失败。'
    return
  }
  updateCompletedData(next, node, event.data)
}

/** Pure reducer for one trace. Throws when the event protocol is inconsistent. */
export function reduceConsultTraceEvent(current, event) {
  validateEnvelope(current, event)
  const next = {
    ...current,
    protocolVersion: event.protocol_version,
    traceId: event.trace_id,
    sessionId: event.session_id,
    expectedSequence: current.expectedSequence + 1,
    intent: event.state.intent,
    phase: event.state.phase,
    turnCount: event.state.turn_count,
    historyMode: event.state.history_mode,
    nodes: { ...current.nodes },
    events: [...current.events, event],
    evidence: [...current.evidence],
    citations: [...current.citations],
  }

  if (event.type === 'node') {
    reduceNode(next, event)
    return next
  }
  if (event.type === 'error') {
    if (event.status !== 'error') throw new ConsultTraceProtocolError('error 事件状态无效。')
    next.status = 'error'
    next.error = event.data.detail || event.detail || '问诊服务执行失败。'
    return next
  }
  if (event.type === 'done') {
    if (event.status !== 'completed' || !['completed', 'escalated'].includes(event.state.phase)) {
      throw new ConsultTraceProtocolError('done 事件终态无效。')
    }
    if (Object.values(next.nodes).some((node) => node.status === 'running')) {
      throw new ConsultTraceProtocolError('存在未结束节点时不能 done。')
    }
    next.status = 'done'
    next.awaitingFollowup = Boolean(next.followup) && !next.answer
    return next
  }
  throw new ConsultTraceProtocolError(`不支持的事件类型：${event.type}`)
}
