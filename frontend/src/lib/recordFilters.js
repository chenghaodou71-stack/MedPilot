function trimmed(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function isoTime(value) {
  if (!value) return ''
  const date = value instanceof Date
    ? value
    : new Date(typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value)
  return Number.isNaN(date.getTime()) ? '' : date.toISOString()
}

export function buildRecordQuery({
  recordId,
  sessionId,
  symptoms,
  department,
  keyword,
  dateRange,
  page = 0,
  size = 20,
}) {
  const params = { page, size }
  const normalizedId = trimmed(String(recordId ?? ''))
  if (normalizedId) params.id = normalizedId
  for (const [key, value] of Object.entries({ sessionId, symptoms, department, keyword })) {
    const normalized = trimmed(value)
    if (normalized) params[key] = normalized
  }
  if (Array.isArray(dateRange)) {
    const startTime = isoTime(dateRange[0])
    const endTime = isoTime(dateRange[1])
    if (startTime) params.startTime = startTime
    if (endTime) params.endTime = endTime
  }
  return params
}

export function emptyRecordFilters() {
  return {
    recordId: '',
    sessionId: '',
    symptoms: '',
    department: '',
    keyword: '',
    dateRange: [],
    page: 0,
  }
}

export function presetDateRange(days, now = new Date()) {
  const end = new Date(now)
  const start = new Date(end)
  start.setDate(start.getDate() - days)
  return [start, end]
}
