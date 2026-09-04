import { describe, expect, it } from 'vitest'

import { formatAverageDuration } from './monitorMetrics'

describe('monitor dashboard metrics', () => {
  it('formats completed trace averages without inventing data', () => {
    expect(formatAverageDuration(null)).toEqual({ value: '--', suffix: '' })
    expect(formatAverageDuration(842.6)).toEqual({ value: 843, suffix: 'ms' })
    expect(formatAverageDuration(1534)).toEqual({ value: '1.5', suffix: '秒' })
  })
})
