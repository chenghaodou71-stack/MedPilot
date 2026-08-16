import axios from 'axios'
import { expireBrowserSession } from '../lib/authSession'

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])
const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

const client = axios.create({
  baseURL: '/api',
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: CSRF_COOKIE_NAME,
  xsrfHeaderName: CSRF_HEADER_NAME,
})

let csrfRequest = null

function isStateChanging(method) {
  return !SAFE_METHODS.has(String(method || 'GET').toUpperCase())
}

export function readCsrfToken(cookieString = '') {
  const cookie = String(cookieString)
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${CSRF_COOKIE_NAME}=`))
  if (!cookie) return ''

  const encodedValue = cookie.slice(CSRF_COOKIE_NAME.length + 1)
  try {
    return decodeURIComponent(encodedValue)
  } catch {
    return ''
  }
}

function browserCsrfToken() {
  return typeof document === 'undefined' ? '' : readCsrfToken(document.cookie)
}

export async function ensureCsrfToken() {
  const existingToken = browserCsrfToken()
  if (existingToken || typeof document === 'undefined') return existingToken

  if (!csrfRequest) {
    csrfRequest = client.get('/auth/csrf', { skipCsrfBootstrap: true })
      .finally(() => {
        csrfRequest = null
      })
  }
  await csrfRequest
  return browserCsrfToken()
}

client.interceptors.request.use(async (config) => {
  if (!isStateChanging(config.method) || config.skipCsrfBootstrap) return config

  const token = await ensureCsrfToken()
  if (token) config.headers.set(CSRF_HEADER_NAME, token)
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const isUnauthorized = error.response?.status === 401
    const requestUrl = String(error.config?.url || '')
    const shouldRedirect = !requestUrl.includes('/auth/login') && !requestUrl.includes('/auth/me')
    if (isUnauthorized && typeof window !== 'undefined') {
      expireBrowserSession(window, { redirect: shouldRedirect })
    }
    return Promise.reject(error)
  },
)

export function buildCookieRequestInit(init = {}, csrfToken = '') {
  const headers = new Headers(init.headers || {})
  if (isStateChanging(init.method) && csrfToken) {
    headers.set(CSRF_HEADER_NAME, csrfToken)
  }
  return {
    ...init,
    credentials: 'same-origin',
    headers,
  }
}

export async function apiFetch(input, init = {}) {
  const csrfToken = isStateChanging(init.method) ? await ensureCsrfToken() : browserCsrfToken()
  const response = await fetch(input, buildCookieRequestInit(init, csrfToken))
  if (response.status === 401 && typeof window !== 'undefined') {
    expireBrowserSession(window)
  }
  return response
}

export default client
