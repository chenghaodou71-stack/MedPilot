const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

const SUPPORTED_EXTENSIONS = Object.freeze([
  '.txt',
  '.pdf',
  '.jpg',
  '.jpeg',
  '.png',
  '.webp',
  '.mp3',
  '.wav',
  '.m4a',
])

const SUPPORTED_EXTENSION_SET = new Set(SUPPORTED_EXTENSIONS)

// Keep this list in sync with the server-side attachment policy.  Extensions
// are intentional here because browser supplied MIME values are not reliable.
export const ATTACHMENT_ACCEPT = SUPPORTED_EXTENSIONS.join(',')

function asText(value) {
  return value === null || value === undefined ? '' : String(value)
}

function normalizeNonNegativeNumber(value, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : fallback
}

/**
 * Normalize an attachment payload returned by the API for safe rendering.
 *
 * Multimodal bytes are never passed to the diagnosis pipeline.  The explicit
 * false value below also protects the client when an older or compromised
 * server sends a truthy flag.
 */
export function normalizeAttachment(payload) {
  const source = payload && typeof payload === 'object' && !Array.isArray(payload)
    ? payload
    : {}
  const status = source.status === 'CONFIRMED' ? 'CONFIRMED' : 'AWAITING_CONFIRMATION'
  const draftText = asText(source.draftText ?? source.draft_text)
  const confirmedText = status === 'CONFIRMED'
    ? asText(source.confirmedText || source.confirmed_text || draftText)
    : ''

  return {
    ...source,
    id: asText(source.id),
    sessionId: asText(source.sessionId || source.session_id),
    originalFilename: asText(source.originalFilename || source.original_filename),
    mediaType: asText(source.mediaType || source.media_type),
    sizeBytes: normalizeNonNegativeNumber(source.sizeBytes ?? source.size_bytes),
    kind: asText(source.kind).toUpperCase(),
    status,
    extractedText: asText(source.extractedText || source.extracted_text),
    draftText,
    confirmedText,
    confirmationRequired: status !== 'CONFIRMED',
    // Never permit automatic image/audio interpretation in the browser.
    automaticAnalysisAllowed: false,
  }
}

/**
 * Append only user-confirmed attachment text to the consultation prompt.
 * Pending drafts remain local UI state until the user confirms them.
 */
export function appendConfirmedAttachments(baseText, attachments) {
  const base = asText(baseText).trim()
  const confirmed = Array.isArray(attachments)
    ? attachments
      .filter((attachment) => attachment?.status === 'CONFIRMED')
      .map((attachment) => asText(attachment.confirmedText || attachment.draftText).trim())
      .filter(Boolean)
    : []

  if (!confirmed.length) return base

  const attachmentText = [
    '用户确认的附件补充：',
    ...confirmed.map((text) => `- ${text}`),
  ].join('\n')
  return base ? `${base}\n\n${attachmentText}` : attachmentText
}

/**
 * Validate a browser File (or a File-like object) before sending it upstream.
 * Returns an empty string when valid, otherwise a user-facing error message.
 */
export function validateAttachmentCandidate(file) {
  if (!file || typeof file !== 'object') {
    return '请选择要上传的附件'
  }

  const filename = asText(file.name).trim()
  const extensionMatch = /\.[^./\\]+$/.exec(filename)
  const extension = extensionMatch ? extensionMatch[0].toLowerCase() : ''
  if (!SUPPORTED_EXTENSION_SET.has(extension)) {
    return '附件类型不支持，请选择 TXT、PDF、JPG、JPEG、PNG、WEBP、MP3、WAV 或 M4A 文件'
  }

  const size = Number(file.size)
  if (!Number.isFinite(size) || size < 0) {
    return '附件大小无效，请重新选择文件'
  }
  if (size === 0) {
    return '附件不能为空，请重新选择文件'
  }
  if (size > MAX_ATTACHMENT_BYTES) {
    return '附件大小不能超过 10 MB'
  }

  return ''
}

export function formatAttachmentSize(sizeBytes) {
  const size = normalizeNonNegativeNumber(sizeBytes)
  if (size < 1024) return `${Math.round(size)} B`

  const units = ['KB', 'MB', 'GB']
  let value = size
  let unitIndex = -1
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }

  const rounded = value >= 10 ? value.toFixed(0) : value.toFixed(1)
  return `${Number(rounded)} ${units[unitIndex]}`
}
