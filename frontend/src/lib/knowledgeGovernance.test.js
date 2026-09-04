import { describe, expect, it } from 'vitest'

import {
  MAX_KNOWLEDGE_FILE_BYTES,
  buildKnowledgeReviewPayload,
  buildKnowledgeUploadFormData,
  formatKnowledgeHitRate,
  knowledgeFailureSummary,
  knowledgePermissions,
  validateKnowledgeFile,
} from './knowledgeGovernance'

class FormDataRecorder {
  constructor() {
    this.entries = []
  }

  append(name, value) {
    this.entries.push([name, value])
  }
}

describe('knowledge governance helpers', () => {
  it('separates edit actions from clinical approval actions', () => {
    expect(knowledgePermissions('KNOWLEDGE_EDITOR')).toEqual({ canEdit: true, canReview: false })
    expect(knowledgePermissions('REVIEWER')).toEqual({ canEdit: false, canReview: true })
    expect(knowledgePermissions('DOCTOR')).toEqual({ canEdit: false, canReview: true })
    expect(knowledgePermissions('ADMIN')).toEqual({ canEdit: true, canReview: true })
  })

  it('accepts only non-empty TXT, MD and PDF files up to 10 MB', () => {
    expect(validateKnowledgeFile({ name: 'guide.MD', size: 12 })).toBe('')
    expect(validateKnowledgeFile({ name: 'guide.pdf', size: MAX_KNOWLEDGE_FILE_BYTES })).toBe('')
    expect(validateKnowledgeFile({ name: 'guide.docx', size: 12 })).toContain('TXT')
    expect(validateKnowledgeFile({ name: 'guide.txt', size: 0 })).toContain('为空')
    expect(validateKnowledgeFile({ name: 'guide.txt', size: MAX_KNOWLEDGE_FILE_BYTES + 1 })).toContain('10 MB')
  })

  it('builds the exact multipart fields expected by the Spring upload endpoint', () => {
    const file = { name: 'guide.txt', size: 12 }
    const payload = buildKnowledgeUploadFormData({
      doc_id: ' guide-1 ',
      department: '呼吸内科',
      source: ' 临床指南 ',
      institution: '测试医院',
      title: '指南',
      url: 'https://example.test/guide',
      published_date: '2026-08-17',
      version: 'v1',
      license: 'CC BY 4.0',
      expires_at: '',
      change_reason: '初次录入',
    }, file, FormDataRecorder)

    expect(payload.entries[0]).toEqual(['file', file])
    expect(Object.fromEntries(payload.entries.slice(1))).toMatchObject({
      doc_id: 'guide-1',
      department: '呼吸内科',
      source: '临床指南',
      change_reason: '初次录入',
    })
    expect(payload.entries).toHaveLength(12)
  })

  it('never lets the browser select the reviewer identity', () => {
    expect(buildKnowledgeReviewPayload('approve', ' 来源与内容复核通过 ')).toEqual({
      action: 'approve',
      change_reason: '来源与内容复核通过',
    })
    expect(buildKnowledgeReviewPayload('reject', '资料过期')).not.toHaveProperty('reviewer')
  })

  it('normalizes processing failures and retrieval hit rates', () => {
    expect(knowledgeFailureSummary({ error_summary: ' parse failed ' })).toBe('parse failed')
    expect(knowledgeFailureSummary({ failure_summary: 'vector failed' })).toBe('vector failed')
    expect(knowledgeFailureSummary({ processing_error: 'legacy failure' })).toBe('legacy failure')
    expect(formatKnowledgeHitRate(0.75)).toBe('75%')
    expect(formatKnowledgeHitRate(0.333)).toBe('33.3%')
    expect(formatKnowledgeHitRate(undefined)).toBe('--')
    expect(formatKnowledgeHitRate(null)).toBe('--')
  })
})
