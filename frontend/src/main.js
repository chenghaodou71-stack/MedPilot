import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import router from './router'
import './style.css'
import App from './App.vue'
import { AUTH_SESSION_EXPIRED_EVENT } from './lib/authSession'
import { useAuthStore } from './stores/auth'
import { resolveWorkspaceAppearance } from './lib/workspaceAppearance'

const SETTINGS_KEY = 'medpilot-user-settings'

function getInitialTheme() {
  try {
    const stored = JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null')
    return stored?.appearance?.theme === 'medical-dark' ? 'medical-dark' : 'medical-light'
  } catch {
    return 'medical-light'
  }
}

const initialTheme = getInitialTheme()

function resolveInitialWorkspace(pathname) {
  if (/^\/(dashboard|knowledge|monitor)(?:\/|$)/.test(pathname)) return 'admin'
  if (pathname === '/login' || pathname === '/') return 'login'
  return 'patient'
}

const initialWorkspace = resolveInitialWorkspace(window.location.pathname)
const initialAppearance = resolveWorkspaceAppearance(initialWorkspace, initialTheme)
document.documentElement.dataset.workspace = initialWorkspace
document.documentElement.dataset.theme = initialAppearance.theme
document.documentElement.classList.toggle('dark', initialAppearance.dark)
document.documentElement.style.colorScheme = initialAppearance.dark ? 'dark' : 'light'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
const auth = useAuthStore(pinia)
window.addEventListener(AUTH_SESSION_EXPIRED_EVENT, () => auth.clearSession())

app
  .use(router)
  .use(ElementPlus)
  .mount('#app')
