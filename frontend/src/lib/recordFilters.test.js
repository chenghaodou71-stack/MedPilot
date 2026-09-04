import { describe, expect, it } from 'vitest'

import { buildRecordQuery, emptyRecordFilters, presetDateRange } from './recordFilters'

describe('record query parameters', () => {
  it('sends server-side identifiers, fields and arbitrary times', () => {
    expect(buildRecordQuery({
      recordId: '42',
      sessionId: 'session-1',
      symptoms: '咳嗽',
      department: '呼吸内科',
      dateRange: [new Date('2026-08-01T00:00:00Z'), new Date('2026-08-17T23:59:59Z')],
      page: 1,
      size: 20,
    })).toEqual({
      id: '42',
      sessionId: 'session-1',
      symptoms: '咳嗽',
      department: '呼吸内科',
      startTime: '2026-08-01T00:00:00.000Z',
      endTime: '2026-08-17T23:59:59.000Z',
      page: 1,
      size: 20,
    })
  })

  it('omits blank values and exposes explicit reset/preset helpers', () => {
    expect(buildRecordQuery({ ...emptyRecordFilters(), page: 0, size: 20 }))
      .toEqual({ page: 0, size: 20 })
    const [start, end] = presetDateRange(7, new Date('2026-08-17T12:00:00Z'))
    expect(end.toISOString()).toBe('2026-08-17T12:00:00.000Z')
    expect(start.toISOString()).toBe('2026-08-10T12:00:00.000Z')
  })
})
