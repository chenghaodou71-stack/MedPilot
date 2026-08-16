import { describe, expect, it } from 'vitest'

import {
  buildInitialConsultText,
  isQuickConsultReady,
  normalizeQuickConsultText,
} from './consultInput'

describe('quick consultation input', () => {
  it('normalizes blank quick text without inventing a form prefill', () => {
    expect(isQuickConsultReady('')).toBe(false)
    expect(normalizeQuickConsultText('  ')).toBe('')
  })

  it('accepts a trimmed two-character symptom description', () => {
    expect(normalizeQuickConsultText('  咳嗽三天  ')).toBe('咳嗽三天')
    expect(isQuickConsultReady('  咳嗽三天  ')).toBe(true)
  })
})

describe('buildInitialConsultText', () => {
  it('does not invent a gender when the optional field is blank', () => {
    const text = buildInitialConsultText({
      form: {
        name: '',
        gender: '',
        age: null,
        duration: '3天',
        severity: '',
        description: '持续咳嗽并伴有发热',
      },
      selectedSymptoms: ['咳嗽', '发热'],
    })

    expect(text).not.toContain('性别：')
    expect(text).toContain('主要症状：咳嗽、发热')
  })

  it('includes gender only after the user explicitly provides it', () => {
    const text = buildInitialConsultText({
      form: { gender: '女', description: '头痛', name: '', age: null, duration: '', severity: '' },
      selectedSymptoms: ['头痛'],
    })

    expect(text).toContain('性别：女')
  })
})
