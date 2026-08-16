import { defineStore } from 'pinia'
import client from '../api/client'

export const MANAGEMENT_ROLES = Object.freeze({
  dashboard: Object.freeze(['ADMIN', 'AUDITOR']),
  knowledge: Object.freeze(['ADMIN', 'KNOWLEDGE_EDITOR', 'REVIEWER', 'DOCTOR']),
  monitor: Object.freeze(['ADMIN', 'AUDITOR']),
  users: Object.freeze(['ADMIN']),
  audit: Object.freeze(['ADMIN', 'AUDITOR']),
})

export function roleHome(role) {
  if (role === 'ADMIN' || role === 'AUDITOR') return '/dashboard'
  if (role === 'KNOWLEDGE_EDITOR' || role === 'REVIEWER' || role === 'DOCTOR') return '/knowledge'
  return '/consult'
}

export function roleHomeName(role) {
  if (role === 'ADMIN' || role === 'AUDITOR') return 'dashboard'
  if (role === 'KNOWLEDGE_EDITOR' || role === 'REVIEWER' || role === 'DOCTOR') return 'knowledge'
  return 'consult'
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    role: '',
    username: '',
    initialized: false,
  }),
  getters: {
    isAuthenticated: (state) => !!state.username && !!state.role,
    isAdmin: (state) => state.role === 'ADMIN',
    canAccess: (state) => (roles = []) => state.role === 'ADMIN' || roles.includes(state.role),
    hasManagementAccess: (state) => state.role === 'ADMIN'
      || Object.values(MANAGEMENT_ROLES).some((roles) => roles.includes(state.role)),
    homePath: (state) => roleHome(state.role),
  },
  actions: {
    setSession(profile) {
      this.username = typeof profile?.username === 'string' ? profile.username : ''
      this.role = typeof profile?.role === 'string' ? profile.role : ''
      this.initialized = true
    },
    clearSession() {
      this.username = ''
      this.role = ''
      this.initialized = true
    },
    async restoreSession() {
      if (this.initialized) return this.isAuthenticated

      try {
        const { data } = await client.get('/auth/me')
        if (!data.success || !data.data?.username || !data.data?.role) {
          throw new Error(data.error || 'Invalid session profile')
        }
        this.setSession(data.data)
        return true
      } catch {
        this.clearSession()
        return false
      }
    },
    async login(username, password) {
      const { data } = await client.post('/auth/login', { username, password })
      if (!data.success) throw new Error(data.error || 'Login failed')
      if (data.data?.username && data.data?.role) {
        this.setSession(data.data)
        return
      }

      this.initialized = false
      if (!await this.restoreSession()) throw new Error('Unable to restore the authenticated session')
    },
    async logout() {
      await client.post('/auth/logout')
      this.clearSession()
    },
  },
})
