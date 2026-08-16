import { safeRedirectTarget } from '../lib/navigation'
import { roleHomeName } from '../stores/auth'

export async function guardRoute(to, auth) {
  if (!auth.initialized) await auth.restoreSession()
  if (to.meta.public) {
    if (to.name === 'login' && auth.isAuthenticated) {
      return { name: roleHomeName(auth.role) }
    }
    return true
  }
  if (!auth.isAuthenticated) {
    const redirect = safeRedirectTarget(to.fullPath)
    return redirect
      ? { name: 'login', query: { redirect } }
      : { name: 'login' }
  }
  const requiredRoles = Array.isArray(to.meta.roles)
    ? to.meta.roles
    : to.meta.admin
      ? ['ADMIN']
      : []
  if (requiredRoles.length && !requiredRoles.includes(auth.role)) {
    return { name: roleHomeName(auth.role) }
  }
  return true
}
