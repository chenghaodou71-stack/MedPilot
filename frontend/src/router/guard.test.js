import { describe, expect, it, vi } from 'vitest'

import { guardRoute } from './guard'

function createAuth(overrides = {}) {
  return {
    initialized: true,
    isAuthenticated: false,
    role: 'USER',
    isAdmin: false,
    restoreSession: vi.fn(),
    ...overrides,
  }
}

describe('guardRoute', () => {
  it('redirects a signed-out user to login and preserves a safe route', async () => {
    const auth = createAuth()

    await expect(guardRoute({
      name: 'record-detail',
      fullPath: '/records/7?tab=trace',
      meta: {},
    }, auth)).resolves.toEqual({
      name: 'login',
      query: { redirect: '/records/7?tab=trace' },
    })
  })

  it('does not preserve an unsafe redirect target', async () => {
    const auth = createAuth()

    await expect(guardRoute({
      name: 'records',
      fullPath: '//example.com/records',
      meta: {},
    }, auth)).resolves.toEqual({ name: 'login' })
  })

  it('redirects a regular user away from a management route', async () => {
    const auth = createAuth({ isAuthenticated: true, role: 'USER' })

    await expect(guardRoute({
      name: 'dashboard',
      fullPath: '/dashboard',
      meta: { roles: ['ADMIN', 'AUDITOR'] },
    }, auth)).resolves.toEqual({ name: 'consult' })
  })

  it('allows an admin user to enter any management route', async () => {
    const auth = createAuth({ isAuthenticated: true, role: 'ADMIN', isAdmin: true })

    await expect(guardRoute({
      name: 'dashboard',
      fullPath: '/dashboard',
      meta: { roles: ['ADMIN', 'AUDITOR'] },
    }, auth)).resolves.toBe(true)
  })

  it.each(['KNOWLEDGE_EDITOR', 'REVIEWER'])('allows %s to enter the knowledge route', async (role) => {
    const auth = createAuth({ isAuthenticated: true, role })

    await expect(guardRoute({
      name: 'knowledge',
      fullPath: '/knowledge',
      meta: { roles: ['ADMIN', 'KNOWLEDGE_EDITOR', 'REVIEWER'] },
    }, auth)).resolves.toBe(true)
  })

  it('allows doctors to enter the knowledge route', async () => {
    const auth = createAuth({ isAuthenticated: true, role: 'DOCTOR' })

    await expect(guardRoute({
      name: 'knowledge',
      fullPath: '/knowledge',
      meta: { roles: ['ADMIN', 'KNOWLEDGE_EDITOR', 'REVIEWER', 'DOCTOR'] },
    }, auth)).resolves.toBe(true)
  })

  it('keeps knowledge editors out of the dashboard', async () => {
    const auth = createAuth({ isAuthenticated: true, role: 'KNOWLEDGE_EDITOR' })

    await expect(guardRoute({
      name: 'dashboard',
      fullPath: '/dashboard',
      meta: { roles: ['ADMIN', 'AUDITOR'] },
    }, auth)).resolves.toEqual({ name: 'knowledge' })
  })

  it('allows auditors to enter the dashboard and monitor routes', async () => {
    const auth = createAuth({ isAuthenticated: true, role: 'AUDITOR' })

    await expect(guardRoute({
      name: 'dashboard',
      fullPath: '/dashboard',
      meta: { roles: ['ADMIN', 'AUDITOR'] },
    }, auth)).resolves.toBe(true)
    await expect(guardRoute({
      name: 'monitor',
      fullPath: '/monitor',
      meta: { roles: ['ADMIN', 'AUDITOR'] },
    }, auth)).resolves.toBe(true)
  })

  it('keeps doctors out of restricted monitoring routes', async () => {
    const auth = createAuth({ isAuthenticated: true, role: 'DOCTOR' })

    await expect(guardRoute({
      name: 'monitor',
      fullPath: '/monitor',
      meta: { roles: ['ADMIN', 'AUDITOR'] },
    }, auth)).resolves.toEqual({ name: 'knowledge' })
  })
})
