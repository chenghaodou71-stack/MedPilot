import { safeRedirectTarget } from './navigation'

export const AUTH_SESSION_EXPIRED_EVENT = 'medpilot-auth-session-expired'

export function buildLoginRedirect(locationLike) {
  const current = safeRedirectTarget(
    `${locationLike?.pathname || ''}${locationLike?.search || ''}${locationLike?.hash || ''}`,
  )
  if (!current || current === '/login' || current.startsWith('/login?')) return '/login'
  return `/login?redirect=${encodeURIComponent(current)}`
}

export function expireBrowserSession(browserLike, { redirect = true } = {}) {
  if (!browserLike) return

  const event = typeof Event === 'function'
    ? new Event(AUTH_SESSION_EXPIRED_EVENT)
    : { type: AUTH_SESSION_EXPIRED_EVENT }
  browserLike.dispatchEvent?.(event)

  if (!redirect || browserLike.location?.pathname === '/login') return
  browserLike.location?.assign?.(buildLoginRedirect(browserLike.location))
}
