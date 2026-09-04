export function buildAuditLogParams({ actor, statusGroup, page, size }) {
  const params = { page, size }
  const normalizedActor = typeof actor === 'string' ? actor.trim() : ''
  if (normalizedActor) params.actor = normalizedActor
  if (statusGroup) params.statusGroup = statusGroup
  return params
}

export function emptyAuditFilters() {
  return { actor: '', statusGroup: '', page: 0 }
}
