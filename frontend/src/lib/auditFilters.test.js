import { describe, expect, it } from 'vitest'

import { buildAuditLogParams, emptyAuditFilters } from './auditFilters'

describe('audit filter parameters', () => {
  it('preserves entered conditions when applying filters', () => {
    expect(buildAuditLogParams({
      actor: ' admin ',
      statusGroup: 'client_error',
      page: 2,
      size: 30,
    })).toEqual({
      actor: 'admin',
      statusGroup: 'client_error',
      page: 2,
      size: 30,
    })
  })

  it('omits blank filters and resets only through an explicit reset state', () => {
    expect(buildAuditLogParams({ actor: ' ', statusGroup: '', page: 0, size: 30 }))
      .toEqual({ page: 0, size: 30 })
    expect(emptyAuditFilters()).toEqual({ actor: '', statusGroup: '', page: 0 })
  })
})
