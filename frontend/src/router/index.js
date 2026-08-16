import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { guardRoute } from './guard'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true, workspace: 'login' } },
  { path: '/dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: 'Dashboard', roles: ['ADMIN', 'AUDITOR'], workspace: 'admin' } },
  { path: '/consult', name: 'consult', component: () => import('../views/ConsultView.vue'), meta: { title: 'Consultation', workspace: 'patient' } },
  { path: '/records', name: 'records', component: () => import('../views/RecordsView.vue'), meta: { title: 'Records', workspace: 'patient' } },
  { path: '/records/:id', name: 'record-detail', component: () => import('../views/RecordDetailView.vue'), meta: { title: 'Record details', workspace: 'patient' } },
  { path: '/profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: 'Health profile', workspace: 'patient' } },
  { path: '/health', name: 'health', component: () => import('../views/HealthView.vue'), meta: { title: 'Health discovery', workspace: 'patient' } },
  { path: '/faq', name: 'faq', component: () => import('../views/FaqView.vue'), meta: { title: 'FAQ', workspace: 'patient' } },
  { path: '/settings', name: 'settings', component: () => import('../views/SettingsView.vue'), meta: { title: 'Settings', workspace: 'patient' } },
  { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue'), meta: { title: 'Knowledge base', roles: ['ADMIN', 'KNOWLEDGE_EDITOR', 'REVIEWER', 'DOCTOR'], workspace: 'admin' } },
  { path: '/monitor', name: 'monitor', component: () => import('../views/MonitorView.vue'), meta: { title: 'Agent monitor', roles: ['ADMIN', 'AUDITOR'], workspace: 'admin' } },
  { path: '/users', name: 'users', component: () => import('../views/AdminUsersView.vue'), meta: { title: 'User access', roles: ['ADMIN'], workspace: 'admin' } },
  { path: '/audit', name: 'audit', component: () => import('../views/AuditView.vue'), meta: { title: 'Audit log', roles: ['ADMIN', 'AUDITOR'], workspace: 'admin' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  return guardRoute(to, useAuthStore())
})

router.afterEach((to) => {
  document.title = `${to.meta.title || 'Medical AI workspace'} | MedPilot`
})

export default router
