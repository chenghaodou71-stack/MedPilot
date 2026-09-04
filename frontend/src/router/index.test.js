import { beforeAll, describe, expect, it, vi } from 'vitest'

const harness = vi.hoisted(() => {
  const hooks = {}
  const router = {
    beforeEach: vi.fn((callback) => { hooks.beforeEach = callback }),
    afterEach: vi.fn((callback) => { hooks.afterEach = callback }),
  }
  return {
    auth: { username: 'admin', role: 'ADMIN' },
    guardRoute: vi.fn().mockResolvedValue(true),
    history: { kind: 'web' },
    hooks,
    options: null,
    router,
  }
})

vi.mock('vue-router', () => ({
  createWebHistory: vi.fn(() => harness.history),
  createRouter: vi.fn((options) => {
    harness.options = options
    return harness.router
  }),
}))

vi.mock('../stores/auth', () => ({
  useAuthStore: vi.fn(() => harness.auth),
}))

vi.mock('./guard', () => ({
  guardRoute: harness.guardRoute,
}))

vi.mock('../views/LoginView.vue', () => ({ default: { name: 'LoginView' } }))
vi.mock('../views/DashboardView.vue', () => ({ default: { name: 'DashboardView' } }))
vi.mock('../views/ConsultView.vue', () => ({ default: { name: 'ConsultView' } }))
vi.mock('../views/RecordsView.vue', () => ({ default: { name: 'RecordsView' } }))
vi.mock('../views/RecordDetailView.vue', () => ({ default: { name: 'RecordDetailView' } }))
vi.mock('../views/ProfileView.vue', () => ({ default: { name: 'ProfileView' } }))
vi.mock('../views/HealthView.vue', () => ({ default: { name: 'HealthView' } }))
vi.mock('../views/FaqView.vue', () => ({ default: { name: 'FaqView' } }))
vi.mock('../views/SettingsView.vue', () => ({ default: { name: 'SettingsView' } }))
vi.mock('../views/KnowledgeView.vue', () => ({ default: { name: 'KnowledgeView' } }))
vi.mock('../views/ClinicalReviewView.vue', () => ({ default: { name: 'ClinicalReviewView' } }))
vi.mock('../views/MonitorView.vue', () => ({ default: { name: 'MonitorView' } }))
vi.mock('../views/AdminUsersView.vue', () => ({ default: { name: 'AdminUsersView' } }))
vi.mock('../views/AuditView.vue', () => ({ default: { name: 'AuditView' } }))
vi.mock('../views/NotFoundView.vue', () => ({ default: { name: 'NotFoundView' } }))

let router

beforeAll(async () => {
  router = (await import('./index')).default
})

describe('application router registration', () => {
  it('registers all product routes and the public catch-all page', () => {
    expect(router).toBe(harness.router)
    expect(harness.options.history).toBe(harness.history)
    expect(harness.options.routes.map((route) => route.path)).toEqual([
      '/', '/login', '/dashboard', '/consult', '/records', '/records/:id', '/profile',
      '/health', '/faq', '/settings', '/knowledge', '/clinical-reviews', '/monitor', '/users', '/audit',
      '/:pathMatch(.*)*',
    ])
    expect(harness.options.routes.at(-1)).toMatchObject({
      name: 'not-found',
      meta: { public: true, workspace: 'login' },
    })
  })

  it('loads every lazy route component', async () => {
    const lazyRoutes = harness.options.routes.filter((route) => typeof route.component === 'function')

    const modules = await Promise.all(lazyRoutes.map((route) => route.component()))

    expect(modules).toHaveLength(15)
    expect(modules.every((module) => module.default)).toBe(true)
  })

  it('delegates navigation decisions to the auth guard', async () => {
    const target = { name: 'dashboard', meta: { roles: ['ADMIN'] } }

    await expect(harness.hooks.beforeEach(target)).resolves.toBe(true)
    expect(harness.guardRoute).toHaveBeenCalledWith(target, harness.auth)
  })

  it('sets a deterministic document title after navigation', () => {
    vi.stubGlobal('document', { title: '' })

    harness.hooks.afterEach({ meta: { title: '问诊记录' } })
    expect(document.title).toBe('问诊记录 | MedPilot')

    harness.hooks.afterEach({ meta: {} })
    expect(document.title).toBe('Medical AI workspace | MedPilot')
    vi.unstubAllGlobals()
  })
})
