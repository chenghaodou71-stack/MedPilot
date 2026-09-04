export const MAX_KNOWLEDGE_FILE_BYTES = 10 * 1024 * 1024

const SUPPORTED_KNOWLEDGE_EXTENSIONS = new Set(['txt', 'md', 'pdf'])

export function knowledgePermissions(role) {
  const normalized = typeof role === 'string' ? role.toUpperCase() : ''
  const isAdmin = normalized === 'ADMIN'
  return {
    canEdit: isAdmin || normalized === 'KNOWLEDGE_EDITOR',
    canReview: isAdmin || normalized === 'REVIEWER' || normalized === 'DOCTOR',
  }
}

export function knowledgeFileExtension(fileName) {
  const name = typeof fileName === 'string' ? fileName.trim().toLowerCase() : ''
  const separator = name.lastIndexOf('.')
  return separator >= 0 ? name.slice(separator + 1) : ''
}

export function validateKnowledgeFile(file) {
  if (!file) return '请选择要上传的知识文件'
  if (!SUPPORTED_KNOWLEDGE_EXTENSIONS.has(knowledgeFileExtension(file.name))) {
    return '仅支持 TXT、MD 和文本型 PDF 文件'
  }
  if (!Number.isFinite(Number(file.size)) || Number(file.size) <= 0) {
    return '文件内容为空，请重新选择'
  }
  if (Number(file.size) > MAX_KNOWLEDGE_FILE_BYTES) {
    return '文件不能超过 10 MB'
  }
  return ''
}

export function buildKnowledgeUploadFormData(form, file, FormDataClass = globalThis.FormData) {
  const validationError = validateKnowledgeFile(file)
  if (validationError) throw new Error(validationError)
  if (typeof FormDataClass !== 'function') throw new Error('当前环境不支持文件上传')

  const payload = new FormDataClass()
  payload.append('file', file)
  const fields = [
    'doc_id',
    'department',
    'source',
    'institution',
    'title',
    'url',
    'published_date',
    'version',
    'license',
    'expires_at',
    'change_reason',
  ]
  fields.forEach((field) => payload.append(field, String(form?.[field] ?? '').trim()))
  return payload
}

export function buildKnowledgeReviewPayload(action, changeReason) {
  return {
    action: action === 'approve' ? 'approve' : 'reject',
    change_reason: typeof changeReason === 'string' ? changeReason.trim() : '',
  }
}

export function knowledgeFailureSummary(document) {
  const value = document?.error_summary
    ?? document?.failure_summary
    ?? document?.processing_error
  return typeof value === 'string' ? value.trim() : ''
}

export function formatKnowledgeHitRate(value) {
  if (value === null || value === undefined || value === '') return '--'
  const rate = Number(value)
  if (!Number.isFinite(rate) || rate < 0) return '--'
  const percentage = Math.min(rate, 1) * 100
  return `${percentage % 1 === 0 ? percentage : percentage.toFixed(1)}%`
}
