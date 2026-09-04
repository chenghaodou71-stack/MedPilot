const ADMIN_ERROR_MESSAGES = Object.freeze({
  'cannot delete your own account': '不能删除当前登录账号。',
  'cannot disable or change your own administrator access': '不能禁用当前账号或变更自己的管理员职责。',
  'at least one active administrator is required': '系统必须保留至少一名已启用的管理员。',
  'user owns consultation records; disable the account instead': '该用户仍有关联问诊记录，不能删除；请改为禁用账号。',
  'username already exists': '该账号名已存在。',
  'username format is invalid': '账号需为 3-64 位小写字母、数字、点、下划线或连字符，并以字母或数字开头。',
  'password must contain 10 to 128 characters': '密码长度必须为 10-128 个字符。',
  'user not found': '用户不存在或已被删除。',
})

function extractErrorValue(errorValue) {
  const payload = errorValue?.response?.data
  let raw = payload?.error ?? payload?.detail ?? payload?.message
  if (typeof raw !== 'string') return ''
  raw = raw.trim()
  if (!raw) return ''
  try {
    const nested = JSON.parse(raw)
    return String(nested?.error ?? nested?.detail ?? nested?.message ?? raw).trim()
  } catch {
    return raw
  }
}

export function adminUserErrorText(errorValue, fallback) {
  const raw = extractErrorValue(errorValue)
  if (!raw) return fallback
  return ADMIN_ERROR_MESSAGES[raw.toLowerCase()] || raw
}

export function validAdminUsername(value) {
  return /^[a-z0-9][a-z0-9._-]{2,63}$/.test(String(value || '').trim())
}

export function validAdminPassword(value) {
  const length = typeof value === 'string' ? value.length : 0
  return length >= 10 && length <= 128
}
