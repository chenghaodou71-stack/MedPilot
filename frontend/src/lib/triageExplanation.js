export function formatTriageSupportScore(score) {
  if (score === null || score === undefined || score === '') return ''
  const value = Number(score)
  if (!Number.isFinite(value) || value < 0) return ''
  const percent = value <= 1 ? value * 100 : value
  return `${Math.max(0, Math.min(100, Math.round(percent)))}%`
}

export function normalizeTriageFactors(factors) {
  if (typeof factors === 'string') {
    try {
      factors = JSON.parse(factors)
    } catch {
      return []
    }
  }
  if (!Array.isArray(factors)) return []
  return factors
    .filter((factor) => factor && (factor.kind === 'rule' || factor.kind === 'evidence'))
    .map((factor) => ({
      kind: factor.kind,
      label: String(factor.label || '').trim(),
      reference: String(factor.reference || '').trim(),
      support: formatTriageSupportScore(factor.support),
    }))
    .filter((factor) => factor.label)
}
