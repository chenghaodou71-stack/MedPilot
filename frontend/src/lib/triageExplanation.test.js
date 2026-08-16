import { describe, expect, it } from 'vitest'
import { formatTriageSupportScore, normalizeTriageFactors } from './triageExplanation'

describe('triage explanation helpers', () => {
  it('formats support scores as percentages without treating them as accuracy', () => {
    expect(formatTriageSupportScore(0.82)).toBe('82%')
    expect(formatTriageSupportScore(82)).toBe('82%')
    expect(formatTriageSupportScore(null)).toBe('')
  })

  it('normalizes malformed factors without rendering unsafe values', () => {
    expect(normalizeTriageFactors([
      { kind: 'evidence', label: '指南', reference: 'doc#0', support: 0.8 },
      { kind: 'unknown', label: '<script>' },
    ])).toEqual([
      { kind: 'evidence', label: '指南', reference: 'doc#0', support: '80%' },
    ])
  })
})
