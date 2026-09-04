import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('../api/client', () => ({ default: api }))

import { roleHome, roleHomeName, useAuthStore } from './auth'

describe('auth store edge behavior', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
    setActivePinia(createPinia())
  })

  it.each([
    ['ADMIN', '/dashboard', 'dashboard'],
    ['AUDITOR', '/dashboard', 'dashboard'],
    ['KNOWLEDGE_EDITOR', '/knowledge', 'knowledge'],
    ['REVIEWER', '/knowledge', 'knowledge'],
    ['DOCTOR', '/knowledge', 'knowledge'],
    ['USER', '/consult', 'consult'],
    ['', '/consult', 'consult'],
  ])('maps %s to its role home', (role, path, name) => {
    expect(roleHome(role)).toBe(path)
    expect(roleHomeName(role)).toBe(name)
  })

  it('normalizes malformed profiles and exposes conservative getters', () => {
    const auth = useAuthStore()

    auth.setSession({ username: 42, role: null })

    expect(auth.username).toBe('')
    expect(auth.role).toBe('')
    expect(auth.isAdmin).toBe(false)
    expect(auth.hasManagementAccess).toBe(false)
    expect(auth.homePath).toBe('/consult')
  })

  it.each(['ADMIN', 'AUDITOR', 'KNOWLEDGE_EDITOR', 'REVIEWER', 'DOCTOR'])(
    'recognizes %s as a management role',
    (role) => {
      const auth = useAuthStore()
      auth.setSession({ username: 'operator', role })

      expect(auth.hasManagementAccess).toBe(true)
      expect(auth.isAdmin).toBe(role === 'ADMIN')
    },
  )

  it('does not repeat an initialized session probe', async () => {
    const auth = useAuthStore()
    auth.setSession({ username: 'alice', role: 'USER' })

    await expect(auth.restoreSession()).resolves.toBe(true)
    expect(api.get).not.toHaveBeenCalled()
  })

  it('rejects an invalid session response', async () => {
    api.get.mockResolvedValue({ data: { success: false, error: 'expired' } })
    const auth = useAuthStore()

    await expect(auth.restoreSession()).resolves.toBe(false)
    expect(auth.isAuthenticated).toBe(false)
  })

  it('restores the profile when login omits it', async () => {
    api.post.mockResolvedValue({ data: { success: true } })
    api.get.mockResolvedValue({
      data: { success: true, data: { username: 'reviewer', role: 'REVIEWER' } },
    })
    const auth = useAuthStore()

    await auth.login('reviewer', 'correct horse battery staple')

    expect(api.get).toHaveBeenCalledWith('/auth/me')
    expect(auth.username).toBe('reviewer')
    expect(auth.role).toBe('REVIEWER')
  })

  it('rejects a failed login envelope', async () => {
    api.post.mockResolvedValue({ data: { success: false, error: 'bad credentials' } })
    const auth = useAuthStore()

    await expect(auth.login('alice', 'wrong password')).rejects.toThrow('bad credentials')
  })

  it('rejects login when the authenticated profile cannot be restored', async () => {
    api.post.mockResolvedValue({ data: { success: true } })
    api.get.mockRejectedValue(new Error('offline'))
    const auth = useAuthStore()

    await expect(auth.login('alice', 'correct horse battery staple'))
      .rejects.toThrow('Unable to restore the authenticated session')
  })
})
