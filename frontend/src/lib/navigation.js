export function safeRedirectTarget(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) {
    return null
  }
  return value
}

export function resolvePostLoginTarget(value, roleHomeOrIsAdmin) {
  if (safeRedirectTarget(value)) return value
  if (typeof roleHomeOrIsAdmin === 'string' && roleHomeOrIsAdmin.startsWith('/')) {
    return roleHomeOrIsAdmin
  }
  return roleHomeOrIsAdmin ? '/dashboard' : '/consult'
}
