import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('../api/client', () => ({ default: api }))

import { useAuthStore } from './auth'

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
})
