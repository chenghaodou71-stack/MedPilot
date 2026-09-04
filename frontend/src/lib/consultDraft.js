export const CONSULT_DRAFT_VERSION = 1
export const CONSULT_DRAFT_TTL_MS = 24 * 60 * 60 * 1000

const DRAFT_KEY_PREFIX = 'medpilot-consult-draft'

function resolveStorage(storage) {
  if (storage) return storage
  return typeof localStorage === 'undefined' ? null : localStorage
}

function asText(value, maxLength = 500) {
  return String(value ?? '').trim().slice(0, maxLength)
}

function normalizeAttachments(attachments) {
  if (!Array.isArray(attachments)) return []
  return attachments
    .filter((attachment) => attachment && typeof attachment === 'object' && attachment.id)
    .slice(0, 8)
    .map((attachment) => ({
      id: asText(attachment.id, 120),
      sessionId: asText(attachment.sessionId || attachment.session_id, 120),
      originalFilename: asText(attachment.originalFilename || attachment.original_filename, 180),
      mediaType: asText(attachment.mediaType || attachment.media_type, 120),
      sizeBytes: Number.isFinite(Number(attachment.sizeBytes || attachment.size_bytes))
        ? Number(attachment.sizeBytes || attachment.size_bytes)
        : 0,
      kind: asText(attachment.kind, 30).toUpperCase(),
      status: attachment.status === 'CONFIRMED' ? 'CONFIRMED' : 'AWAITING_CONFIRMATION',
      draftText: asText(attachment.draftText, 4000),
      confirmedText: asText(attachment.confirmedText, 4000),
    }))
}

export function consultDraftKey(username) {
  const identity = asText(username, 120) || 'anonymous'
  return `${DRAFT_KEY_PREFIX}:${encodeURIComponent(identity)}`
}

export function createConsultDraft({
  stage = 'landing',
  sessionId = '',
  quickInput = '',
  selectedSymptoms = [],
  form = {},
  attachments = [],
  savedAt = Date.now(),
} = {}) {
  return {
    version: CONSULT_DRAFT_VERSION,
    savedAt,
    stage: stage === 'intake' ? 'intake' : 'landing',
    sessionId: asText(sessionId, 120),
    quickInput: asText(quickInput, 500),
    selectedSymptoms: Array.isArray(selectedSymptoms)
      ? selectedSymptoms.map((item) => asText(item, 60)).filter(Boolean).slice(0, 20)
      : [],
    form: {
      name: asText(form.name, 40),
      gender: asText(form.gender, 20),
      age: Number.isInteger(Number(form.age)) ? Number(form.age) : null,
      duration: asText(form.duration, 40),
      severity: asText(form.severity, 40),
      description: asText(form.description, 500),
    },
    attachments: normalizeAttachments(attachments),
  }
}

export function saveConsultDraft(username, draft, storage) {
  const target = resolveStorage(storage)
  if (!target) return false
  try {
    target.setItem(consultDraftKey(username), JSON.stringify(draft))
    return true
  } catch {
    return false
  }
}

export function loadConsultDraft(username, storage, now = Date.now()) {
  const target = resolveStorage(storage)
  if (!target) return null
  const key = consultDraftKey(username)
  try {
    const raw = target.getItem(key)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    const savedAt = Number(parsed?.savedAt)
    if (parsed?.version !== CONSULT_DRAFT_VERSION || !Number.isFinite(savedAt)) {
      target.removeItem(key)
      return null
    }
    if (now - savedAt > CONSULT_DRAFT_TTL_MS || now < savedAt) {
      target.removeItem(key)
      return null
    }
    return createConsultDraft({ ...parsed, savedAt })
  } catch {
    return null
  }
}

export function clearConsultDraft(username, storage) {
  const target = resolveStorage(storage)
  if (!target) return false
  try {
    target.removeItem(consultDraftKey(username))
    return true
  } catch {
    return false
  }
}
