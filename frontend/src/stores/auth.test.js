import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('../api/client', () => ({ default: api }))

import { roleHome, roleHomeName, useAuthStore } from './auth'

describe('auth store', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
    setActivePinia(createPinia())
  })

  it('restores the in-memory session through /auth/me', async () => {
    api.get.mockResolvedValue({
      data: { success: true, data: { username: 'alice', role: 'USER' } },
    })
    const auth = useAuthStore()

    await auth.restoreSession()

    expect(api.get).toHaveBeenCalledWith('/auth/me')
    expect(auth.username).toBe('alice')
    expect(auth.role).toBe('USER')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.initialized).toBe(true)
  })

  it('exposes role-scoped management permissions', () => {
    const auth = useAuthStore()

    auth.setSession({ username: 'editor', role: 'KNOWLEDGE_EDITOR' })
    expect(auth.canAccess(['ADMIN', 'KNOWLEDGE_EDITOR', 'REVIEWER'])).toBe(true)
    expect(auth.canAccess(['ADMIN', 'AUDITOR'])).toBe(false)

    auth.setSession({ username: 'auditor', role: 'AUDITOR' })
    expect(auth.canAccess(['ADMIN', 'AUDITOR'])).toBe(true)
    expect(auth.canAccess(['ADMIN', 'KNOWLEDGE_EDITOR', 'REVIEWER'])).toBe(false)
  })

  it('maps every role to a deterministic home and exposes derived access flags', () => {
    expect(roleHome('ADMIN')).toBe('/dashboard')
    expect(roleHomeName('AUDITOR')).toBe('dashboard')
    expect(roleHome('DOCTOR')).toBe('/knowledge')
    expect(roleHomeName('REVIEWER')).toBe('knowledge')
    expect(roleHome('USER')).toBe('/consult')
    expect(roleHomeName('unknown')).toBe('consult')

    const auth = useAuthStore()
    auth.setSession({ username: 'admin', role: 'ADMIN' })
    expect(auth.isAdmin).toBe(true)
    expect(auth.hasManagementAccess).toBe(true)
    expect(auth.homePath).toBe('/dashboard')
    auth.setSession(null)
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.hasManagementAccess).toBe(false)
  })

  it('uses the cookie login profile and ignores any legacy token field', async () => {
    api.post.mockResolvedValue({
      data: {
        success: true,
        data: { username: 'admin', role: 'ADMIN', token: 'must-not-be-used' },
      },
    })
    const auth = useAuthStore()

    await auth.login('admin', 'secret')

    expect(api.post).toHaveBeenCalledWith('/auth/login', {
      username: 'admin',
      password: 'secret',
    })
    expect(auth.username).toBe('admin')
    expect(auth.role).toBe('ADMIN')
    expect(auth.token).toBeUndefined()
  })

  it('keeps the in-memory session when server logout fails', async () => {
    api.post.mockRejectedValue(new Error('offline'))
    const auth = useAuthStore()
    auth.setSession({ username: 'alice', role: 'USER' })

    await expect(auth.logout()).rejects.toThrow('offline')

    expect(api.post).toHaveBeenCalledWith('/auth/logout')
    expect(auth.username).toBe('alice')
    expect(auth.role).toBe('USER')
    expect(auth.isAuthenticated).toBe(true)
  })

  it('clears the in-memory session after server logout succeeds', async () => {
    api.post.mockResolvedValue({ data: { success: true } })
    const auth = useAuthStore()
    auth.setSession({ username: 'alice', role: 'USER' })

    await auth.logout()

    expect(api.post).toHaveBeenCalledWith('/auth/logout')
    expect(auth.username).toBe('')
    expect(auth.role).toBe('')
    expect(auth.isAuthenticated).toBe(false)
  })

  it('treats an unauthorized session probe as a signed-out session', async () => {
    api.get.mockRejectedValue({ response: { status: 401 } })
    const auth = useAuthStore()

    await expect(auth.restoreSession()).resolves.toBe(false)

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.initialized).toBe(true)
  })

  it('does not repeat a session probe after initialization', async () => {
    const auth = useAuthStore()
    auth.setSession({ username: 'alice', role: 'USER' })

    await expect(auth.restoreSession()).resolves.toBe(true)
    expect(api.get).not.toHaveBeenCalled()
  })

  it('rejects invalid login envelopes and restores a profile when login omits it', async () => {
    const auth = useAuthStore()
    api.post.mockResolvedValueOnce({ data: { success: false, error: 'denied' } })
    await expect(auth.login('alice', 'wrong')).rejects.toThrow('denied')

    api.post.mockResolvedValueOnce({ data: { success: true, data: {} } })
    api.get.mockResolvedValueOnce({
      data: { success: true, data: { username: 'alice', role: 'USER' } },
    })
    await expect(auth.login('alice', 'correct-password')).resolves.toBeUndefined()
    expect(auth.username).toBe('alice')
    expect(auth.role).toBe('USER')
  })

  it('fails closed when login succeeds but the follow-up session probe is invalid', async () => {
    const auth = useAuthStore()
    api.post.mockResolvedValue({ data: { success: true, data: {} } })
    api.get.mockResolvedValue({ data: { success: true, data: {} } })

    await expect(auth.login('alice', 'correct-password'))
      .rejects.toThrow('Unable to restore the authenticated session')
    expect(auth.isAuthenticated).toBe(false)
  })
})
