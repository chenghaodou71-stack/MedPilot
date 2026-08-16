import { describe, expect, it } from 'vitest'
import {
  appendConfirmedAttachments,
  normalizeAttachment,
  validateAttachmentCandidate,
} from './consultAttachments'

describe('consult attachment helpers', () => {
  it('includes only user-confirmed drafts in consultation text', () => {
    const text = appendConfirmedAttachments('base symptoms', [
      { status: 'AWAITING_CONFIRMATION', confirmedText: '', draftText: 'pending private note' },
      { status: 'CONFIRMED', confirmedText: 'fever for three days' },
      { status: 'CONFIRMED', confirmedText: '   ' },
    ])

    expect(text).toContain('base symptoms')
    expect(text).toContain('fever for three days')
    expect(text).not.toContain('pending private note')
  })

  it('normalizes server payload without enabling automatic analysis', () => {
    expect(normalizeAttachment({
      id: 'a1',
      originalFilename: 'rash.png',
      kind: 'IMAGE',
      status: 'AWAITING_CONFIRMATION',
      draftText: 'please add context',
      automaticAnalysisAllowed: true,
    })).toMatchObject({
      id: 'a1',
      draftText: 'please add context',
      automaticAnalysisAllowed: false,
      confirmationRequired: true,
    })
  })

  it('rejects unsupported and oversized browser files before upload', () => {
    expect(validateAttachmentCandidate({ name: 'scan.exe', size: 20 })).toContain('不支持')
    expect(validateAttachmentCandidate({ name: 'note.txt', size: 10 * 1024 * 1024 + 1 })).toContain('10 MB')
    expect(validateAttachmentCandidate({ name: 'note.txt', size: 20 })).toBe('')
  })
})
