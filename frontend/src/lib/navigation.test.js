import { describe, expect, it } from 'vitest'
import { resolvePostLoginTarget, safeRedirectTarget } from './navigation'

describe('safeRedirectTarget', () => {
  it('keeps an internal application path', () => {
    expect(safeRedirectTarget('/records/42?tab=trace')).toBe('/records/42?tab=trace')
  })

  it.each([
    ['https://example.com', null],
    ['//example.com/path', null],
    ['javascript:alert(1)', null],
    ['', null],
    [null, null],
  ])('rejects an unsafe redirect target', (value, expected) => {
    expect(safeRedirectTarget(value)).toBe(expected)
  })
})

describe('resolvePostLoginTarget', () => {
  it('returns a safe originally requested route', () => {
    expect(resolvePostLoginTarget('/records/42?tab=trace', false)).toBe('/records/42?tab=trace')
  })

  it('uses the role home when the redirect is missing or unsafe', () => {
    expect(resolvePostLoginTarget('https://example.com', false)).toBe('/consult')
    expect(resolvePostLoginTarget(null, true)).toBe('/dashboard')
    expect(resolvePostLoginTarget(null, '/knowledge')).toBe('/knowledge')
  })
})
