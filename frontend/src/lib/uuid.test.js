import { describe, expect, it } from 'vitest'

import { createCanonicalUuid } from './uuid'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('createCanonicalUuid', () => {
  it('uses a native canonical UUID when available', () => {
    const value = '2c293933-6590-4bfc-b0e8-507d3063c90b'
    expect(createCanonicalUuid({ randomUUID: () => value })).toBe(value)
  })

  it('creates a canonical v4 UUID from random bytes as a fallback', () => {
    const cryptoApi = {
      getRandomValues(bytes) {
        bytes.fill(0x11)
        return bytes
      },
    }

    const value = createCanonicalUuid(cryptoApi)

    expect(value).toMatch(UUID_V4)
    expect(value).toBe('11111111-1111-4111-9111-111111111111')
  })
})
