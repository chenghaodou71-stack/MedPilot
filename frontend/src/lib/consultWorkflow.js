export const STANDARD_WORKFLOW_AGENT_KEYS = Object.freeze([
  'safety_screen',
  'extract',
  'retrieve',
  'classify',
  'compose',
])

export const EMERGENCY_WORKFLOW_AGENT_KEYS = Object.freeze([
  'safety_screen',
  'classify',
  'compose',
])

export const FOLLOWUP_WORKFLOW_AGENT_KEYS = Object.freeze([
  'safety_screen',
  'extract',
  'ask_followup',
])

export function isEmergencyWorkflow(traceState) {
  return Boolean(traceState?.emergency) || traceState?.intent === 'emergency'
}

export function resolveWorkflowAgentKeys(traceState) {
  if (isEmergencyWorkflow(traceState)) return EMERGENCY_WORKFLOW_AGENT_KEYS
  if (traceState?.nodes?.ask_followup) return FOLLOWUP_WORKFLOW_AGENT_KEYS
  return STANDARD_WORKFLOW_AGENT_KEYS
}

export function summarizeWorkflowProgress(agentKeys, statusByKey, terminal = false) {
  const total = agentKeys.length
  const completed = agentKeys.filter((key) => statusByKey[key] === 'done').length
  const hasRunning = agentKeys.some((key) => statusByKey[key] === 'running')

  if (!total) return { completed: 0, total: 0, percentage: 0, activeStep: 0 }
  if (terminal) return { completed, total, percentage: 100, activeStep: total }

  return {
    completed,
    total,
    percentage: Math.min(95, Math.round(((completed + (hasRunning ? 0.4 : 0)) / total) * 100)),
    activeStep: Math.min(completed, total - 1),
  }
}
