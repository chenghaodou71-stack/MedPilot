import { describe, expect, it, vi } from 'vitest'

import { guardRoute } from './guard'

describe('route guard edge behavior', () => {
  it('restores an uninitialized session before evaluating a public page', async () => {
    const auth = {
      initialized: false,
      isAuthenticated: false,
      role: '',
      restoreSession: vi.fn(async function restore() {
        this.initialized = true
      }),
    }

    await expect(guardRoute({ name: 'faq', fullPath: '/faq', meta: { public: true } }, auth))
      .resolves.toBe(true)
    expect(auth.restoreSession).toHaveBeenCalledOnce()
  })

  it('moves an authenticated user away from login', async () => {
    const auth = {
      initialized: true,
      isAuthenticated: true,
      role: 'AUDITOR',
      restoreSession: vi.fn(),
    }

    await expect(guardRoute({ name: 'login', fullPath: '/login', meta: { public: true } }, auth))
      .resolves.toEqual({ name: 'dashboard' })
  })

  it('supports the legacy admin metadata contract', async () => {
    const auth = {
      initialized: true,
      isAuthenticated: true,
      role: 'USER',
      restoreSession: vi.fn(),
    }

    await expect(guardRoute({ name: 'legacy', fullPath: '/legacy', meta: { admin: true } }, auth))
      .resolves.toEqual({ name: 'consult' })
  })
})
