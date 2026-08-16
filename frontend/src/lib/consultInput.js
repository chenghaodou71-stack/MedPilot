export function normalizeQuickConsultText(value) {
  return String(value ?? '').trim()
}

export function isQuickConsultReady(value) {
  return normalizeQuickConsultText(value).length >= 2
}

export function buildInitialConsultText({ form = {}, selectedSymptoms = [] }) {
  const parts = []
  const name = String(form.name || '').trim()
  const gender = String(form.gender || '').trim()
  const description = String(form.description || '').trim()

  if (name) parts.push(`称呼：${name}`)
  if (gender) parts.push(`性别：${gender}`)
  if (form.age) parts.push(`年龄：${form.age}岁`)
  parts.push(`主要症状：${selectedSymptoms.join('、')}`)
  parts.push(`症状描述：${description}`)
  if (form.duration) parts.push(`持续时间：${form.duration}`)
  if (form.severity) parts.push(`严重程度：${form.severity}`)
  return parts.join('；')
}
