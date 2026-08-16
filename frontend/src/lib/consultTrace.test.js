import { describe, expect, it } from 'vitest'

import {
  ConsultTraceProtocolError,
  createConsultTraceState,
  reduceConsultTraceEvent,
} from './consultTrace'

const TRACE_ID = '2c293933-6590-4bfc-b0e8-507d3063c90b'
const SESSION_ID = '1779673a-c983-47e4-9715-f2d9548f469a'

function event(sequence, overrides = {}) {
  return {
    protocol_version: '1.0',
    trace_id: TRACE_ID,
    session_id: SESSION_ID,
    sequence,
    type: 'node',
    node: 'extract',
    status: 'started',
    elapsed_ms: 0,
    state: {
      intent: 'medical_consult',
      phase: 'collecting',
      turn_count: 1,
      history_mode: 'full',
    },
    data: {},
    ...overrides,
  }
}

describe('reduceConsultTraceEvent', () => {
  it('starts and completes only the current node', () => {
    const started = reduceConsultTraceEvent(createConsultTraceState(), event(1))
    const completed = reduceConsultTraceEvent(started, event(2, {
      status: 'completed',
      elapsed_ms: 18,
      data: { symptoms: { symptoms: ['咳嗽'], raw_text: '咳嗽三天' } },
    }))

    expect(started.nodes.extract.status).toBe('running')
    expect(started.nodes.retrieve).toBeUndefined()
    expect(completed.nodes.extract).toMatchObject({ status: 'done', elapsedMs: 18 })
    expect(completed.symptoms.symptoms).toEqual(['咳嗽'])
  })

  it('keeps unknown nodes in the event log', () => {
    const state = reduceConsultTraceEvent(createConsultTraceState(), event(1, {
      node: 'future_agent',
      state: { intent: 'medical_consult', phase: 'summarizing', turn_count: 6, history_mode: 'summary' },
    }))

    expect(state.nodes.future_agent.status).toBe('running')
    expect(state.events).toHaveLength(1)
    expect(state.historyMode).toBe('summary')
  })

  it('rejects a sequence gap and trace mismatch', () => {
    const state = reduceConsultTraceEvent(createConsultTraceState(), event(1))

    expect(() => reduceConsultTraceEvent(state, event(3))).toThrow(ConsultTraceProtocolError)
    expect(() => reduceConsultTraceEvent(state, event(2, { trace_id: crypto.randomUUID() })))
      .toThrow(ConsultTraceProtocolError)
  })

  it('makes emergency action persistent even when a later event errors', () => {
    const started = reduceConsultTraceEvent(createConsultTraceState(), event(1, {
      node: 'safety_screen',
      state: { intent: 'medical_consult', phase: 'screening', turn_count: 1, history_mode: 'full' },
    }))
    const screened = reduceConsultTraceEvent(started, event(2, {
      node: 'safety_screen',
      status: 'completed',
      state: { intent: 'emergency', phase: 'screening', turn_count: 1, history_mode: 'full' },
      data: {
        safety: {
          matched: true,
          matched_terms: ['胸痛'],
          risk_level: '高',
          urgency: '建议立即就医或呼叫急救',
        },
      },
    }))
    const failed = reduceConsultTraceEvent(screened, event(3, {
      type: 'error',
      status: 'error',
      node: undefined,
      state: { intent: 'emergency', phase: 'failed', turn_count: 1, history_mode: 'full' },
      data: { detail: 'upstream unavailable' },
    }))

    expect(screened.emergency.matchedTerms).toEqual(['胸痛'])
    expect(failed.status).toBe('error')
    expect(failed.emergency.urgency).toContain('立即就医')
  })

  it('finishes a followup turn on done without requiring an answer', () => {
    let state = createConsultTraceState()
    state = reduceConsultTraceEvent(state, event(1, {
      node: 'ask_followup',
      state: { intent: 'medical_consult', phase: 'awaiting_followup', turn_count: 1, history_mode: 'full' },
    }))
    state = reduceConsultTraceEvent(state, event(2, {
      node: 'ask_followup',
      status: 'completed',
      state: { intent: 'medical_consult', phase: 'awaiting_followup', turn_count: 1, history_mode: 'full' },
      data: { followup: { question: '持续多久了？' } },
    }))
    state = reduceConsultTraceEvent(state, event(3, {
      type: 'done',
      status: 'completed',
      node: undefined,
      state: { intent: 'medical_consult', phase: 'completed', turn_count: 1, history_mode: 'full' },
    }))

    expect(state.status).toBe('done')
    expect(state.awaitingFollowup).toBe(true)
    expect(state.answer).toBeNull()
  })

  it('links structured answer citations to evidence by citation_id', () => {
    const citation = {
      citation_id: 'resp-1#0',
      doc_id: 'resp-1',
      chunk_id: 'resp-1#0',
      source: '呼吸指南',
      department: '呼吸内科',
      quote: '咳嗽伴发热应评估。',
      score: 0.86,
      index_version: 'v1',
    }
    let state = createConsultTraceState()
    state = reduceConsultTraceEvent(state, event(1, { node: 'retrieve', state: { intent: 'medical_consult', phase: 'retrieving', turn_count: 1, history_mode: 'full' } }))
    state = reduceConsultTraceEvent(state, event(2, {
      node: 'retrieve',
      status: 'completed',
      state: { intent: 'medical_consult', phase: 'retrieving', turn_count: 1, history_mode: 'full' },
      data: { evidence: [citation] },
    }))
    state = reduceConsultTraceEvent(state, event(3, { node: 'compose', state: { intent: 'medical_consult', phase: 'composing', turn_count: 1, history_mode: 'full' } }))
    state = reduceConsultTraceEvent(state, event(4, {
      node: 'compose',
      status: 'completed',
      state: { intent: 'medical_consult', phase: 'composing', turn_count: 1, history_mode: 'full' },
      data: { answer: { text: '建议就诊。', citations: [{ citation_id: citation.citation_id }] } },
    }))

    expect(state.citations).toEqual([citation])
    expect(state.citations[0].quote).toBe('咳嗽伴发热应评估。')
  })
})
