import { describe, expect, it } from 'vitest'
import {
  appendConfirmedAttachments,
  formatAttachmentSize,
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

  it('normalizes snake-case confirmed payloads and malformed payloads', () => {
    expect(normalizeAttachment({
      session_id: 'session-1',
      original_filename: 'note.TXT',
      media_type: 'text/plain',
      size_bytes: '42',
      kind: 'text',
      status: 'CONFIRMED',
      draft_text: 'draft',
    })).toMatchObject({
      sessionId: 'session-1',
      originalFilename: 'note.TXT',
      mediaType: 'text/plain',
      sizeBytes: 42,
      kind: 'TEXT',
      confirmedText: 'draft',
      confirmationRequired: false,
    })
    expect(normalizeAttachment(null)).toMatchObject({
      id: '',
      sizeBytes: 0,
      status: 'AWAITING_CONFIRMATION',
    })
  })

  it('returns only confirmed text even when no base text is present', () => {
    expect(appendConfirmedAttachments('', [
      { status: 'CONFIRMED', draftText: '补充说明' },
    ])).toBe('用户确认的附件补充：\n- 补充说明')
    expect(appendConfirmedAttachments('症状', null)).toBe('症状')
  })

  it('rejects unsupported and oversized browser files before upload', () => {
    expect(validateAttachmentCandidate({ name: 'scan.exe', size: 20 })).toContain('不支持')
    expect(validateAttachmentCandidate({ name: 'note.txt', size: 10 * 1024 * 1024 + 1 })).toContain('10 MB')
    expect(validateAttachmentCandidate({ name: 'note.txt', size: 20 })).toBe('')
  })

  it('rejects missing names and every invalid size shape', () => {
    expect(validateAttachmentCandidate(null)).toContain('请选择')
    expect(validateAttachmentCandidate({ name: 'README', size: 1 })).toContain('不支持')
    expect(validateAttachmentCandidate({ name: 'note.txt', size: -1 })).toContain('无效')
    expect(validateAttachmentCandidate({ name: 'note.txt', size: Number.NaN })).toContain('无效')
    expect(validateAttachmentCandidate({ name: 'note.txt', size: 0 })).toContain('不能为空')
    expect(validateAttachmentCandidate({ name: 'PHOTO.JPEG', size: 1 })).toBe('')
  })

  it('formats attachment sizes across byte units', () => {
    expect(formatAttachmentSize(-1)).toBe('0 B')
    expect(formatAttachmentSize(1023)).toBe('1023 B')
    expect(formatAttachmentSize(1536)).toBe('1.5 KB')
    expect(formatAttachmentSize(12 * 1024)).toBe('12 KB')
    expect(formatAttachmentSize(2 * 1024 * 1024)).toBe('2 MB')
  })
})
