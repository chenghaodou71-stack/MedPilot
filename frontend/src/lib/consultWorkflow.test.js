import { describe, expect, it } from 'vitest'

import {
  EMERGENCY_WORKFLOW_AGENT_KEYS,
  FOLLOWUP_WORKFLOW_AGENT_KEYS,
  STANDARD_WORKFLOW_AGENT_KEYS,
  resolveWorkflowAgentKeys,
  summarizeWorkflowProgress,
} from './consultWorkflow'

describe('resolveWorkflowAgentKeys', () => {
  it('keeps the complete workflow for a standard consultation', () => {
    expect(resolveWorkflowAgentKeys({ intent: 'medical_consult', emergency: null }))
      .toEqual(STANDARD_WORKFLOW_AGENT_KEYS)
  })

  it('selects only the real safety fast-path nodes', () => {
    expect(resolveWorkflowAgentKeys({ intent: 'emergency', emergency: null }))
      .toEqual(EMERGENCY_WORKFLOW_AGENT_KEYS)
    expect(resolveWorkflowAgentKeys({ intent: 'medical_consult', emergency: { matchedTerms: ['胸痛'] } }))
      .toEqual(EMERGENCY_WORKFLOW_AGENT_KEYS)
  })

  it('selects only nodes that really ran when the workflow asks a follow-up', () => {
    expect(resolveWorkflowAgentKeys({
      intent: 'medical_consult',
      emergency: null,
      nodes: {
        safety_screen: { status: 'done' },
        extract: { status: 'done' },
        ask_followup: { status: 'running' },
      },
    })).toEqual(FOLLOWUP_WORKFLOW_AGENT_KEYS)
  })
})

describe('summarizeWorkflowProgress', () => {
  it('reports a completed emergency workflow as three of three', () => {
    const summary = summarizeWorkflowProgress(EMERGENCY_WORKFLOW_AGENT_KEYS, {
      safety_screen: 'done',
      extract: 'waiting',
      retrieve: 'waiting',
      classify: 'done',
      compose: 'done',
    }, true)

    expect(summary).toEqual({ completed: 3, total: 3, percentage: 100, activeStep: 3 })
  })

  it('does not count skipped standard nodes in emergency progress', () => {
    const summary = summarizeWorkflowProgress(EMERGENCY_WORKFLOW_AGENT_KEYS, {
      safety_screen: 'done',
      extract: 'waiting',
      retrieve: 'waiting',
      classify: 'running',
      compose: 'waiting',
    })

    expect(summary).toEqual({ completed: 1, total: 3, percentage: 47, activeStep: 1 })
  })
})
