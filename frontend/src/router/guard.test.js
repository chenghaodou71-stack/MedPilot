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
  it('restores an uninitialized session before evaluating a public route', async () => {
    const auth = createAuth({ initialized: false })

    await expect(guardRoute({
      name: 'faq',
      fullPath: '/faq',
      meta: { public: true },
    }, auth)).resolves.toBe(true)
    expect(auth.restoreSession).toHaveBeenCalledTimes(1)
  })

  it('redirects an authenticated user away from login to the role home', async () => {
    const auth = createAuth({ isAuthenticated: true, role: 'AUDITOR' })

    await expect(guardRoute({
      name: 'login',
      fullPath: '/login',
      meta: { public: true },
    }, auth)).resolves.toEqual({ name: 'dashboard' })
  })

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

  it.each(['DOCTOR', 'REVIEWER'])('allows %s to enter the clinical review route', async (role) => {
    const auth = createAuth({ isAuthenticated: true, role })

    await expect(guardRoute({
      name: 'clinical-reviews',
      fullPath: '/clinical-reviews',
      meta: { roles: ['REVIEWER', 'DOCTOR'] },
    }, auth)).resolves.toBe(true)
  })

  it('keeps administrators out of the clinical review route', async () => {
    const auth = createAuth({ isAuthenticated: true, role: 'ADMIN' })

    await expect(guardRoute({
      name: 'clinical-reviews',
      fullPath: '/clinical-reviews',
      meta: { roles: ['REVIEWER', 'DOCTOR'] },
    }, auth)).resolves.toEqual({ name: 'dashboard' })
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

  it('supports the legacy admin metadata and ordinary authenticated routes', async () => {
    const user = createAuth({ isAuthenticated: true, role: 'USER' })
    await expect(guardRoute({
      name: 'users',
      fullPath: '/users',
      meta: { admin: true },
    }, user)).resolves.toEqual({ name: 'consult' })
    await expect(guardRoute({
      name: 'consult',
      fullPath: '/consult',
      meta: {},
    }, user)).resolves.toBe(true)
  })
})
