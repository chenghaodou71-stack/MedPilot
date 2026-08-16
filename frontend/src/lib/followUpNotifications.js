/** Normalize the due-reminder response without coupling the shell to Axios. */
export function normalizeDueFollowUps(response) {
  const payload = response?.data?.data ?? response?.data ?? response ?? []
  if (!Array.isArray(payload)) return []

  return payload
    .filter((task) => task && task.id != null && task.status === 'OPEN')
    .filter((task) => task.due === true || isDue(task.dueAt))
    .sort((left, right) => dateValue(left.dueAt) - dateValue(right.dueAt))
}

/** Return unseen reminders and record their stable ids for this browser session. */
export function collectUnseenDueFollowUps(tasks, seenIds) {
  const unseen = []
  for (const task of Array.isArray(tasks) ? tasks : []) {
    const id = String(task.id)
    if (seenIds.has(id)) continue
    seenIds.add(id)
    unseen.push(task)
  }
  return unseen
}

function isDue(value) {
  return dateValue(value) <= Date.now()
}

function dateValue(value) {
  const parsed = Date.parse(value || '')
  return Number.isFinite(parsed) ? parsed : Number.POSITIVE_INFINITY
}
