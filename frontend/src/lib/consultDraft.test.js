import { describe, expect, it } from 'vitest'

import {
  CONSULT_DRAFT_TTL_MS,
  clearConsultDraft,
  createConsultDraft,
  loadConsultDraft,
  saveConsultDraft,
} from './consultDraft'

function createStorage() {
  const values = new Map()
  return {
    getItem: (key) => values.get(key) || null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  }
}

describe('consult draft persistence', () => {
  it('round-trips only the fields needed to resume an intake', () => {
    const storage = createStorage()
    const draft = createConsultDraft({
      stage: 'intake',
      sessionId: 'session-1',
      quickInput: '咳嗽三天',
      selectedSymptoms: ['咳嗽'],
      form: { description: '伴有低热', age: 28 },
      savedAt: 1000,
    })

    expect(saveConsultDraft('patient/a', draft, storage)).toBe(true)
    expect(loadConsultDraft('patient/a', storage, 1001)).toMatchObject({
      sessionId: 'session-1',
      stage: 'intake',
      quickInput: '咳嗽三天',
      selectedSymptoms: ['咳嗽'],
      form: { description: '伴有低热', age: 28 },
    })
  })

  it('expires stale drafts and removes them from storage', () => {
    const storage = createStorage()
    saveConsultDraft('patient', createConsultDraft({ savedAt: 1000 }), storage)

    expect(loadConsultDraft('patient', storage, 1000 + CONSULT_DRAFT_TTL_MS + 1)).toBeNull()
    expect(loadConsultDraft('patient', storage, 1000 + CONSULT_DRAFT_TTL_MS + 1)).toBeNull()
  })

  it('isolates drafts by user and supports explicit clearing', () => {
    const storage = createStorage()
    saveConsultDraft('alice', createConsultDraft({ savedAt: 1000 }), storage)

    expect(loadConsultDraft('bob', storage, 1001)).toBeNull()
    expect(clearConsultDraft('alice', storage)).toBe(true)
    expect(loadConsultDraft('alice', storage, 1001)).toBeNull()
  })
})
