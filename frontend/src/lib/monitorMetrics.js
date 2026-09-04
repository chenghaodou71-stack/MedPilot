export function formatAverageDuration(value) {
  if (value == null || value === '') return { value: '--', suffix: '' }
  const duration = Number(value)
  if (!Number.isFinite(duration) || duration < 0) return { value: '--', suffix: '' }
  if (duration >= 1000) return { value: (duration / 1000).toFixed(1), suffix: '秒' }
  return { value: Math.round(duration), suffix: 'ms' }
}
