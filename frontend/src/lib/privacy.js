export const SETTINGS_KEY = 'medpilot-user-settings'
const HEALTH_HISTORY_PREFIX = 'medpilot-health-search-history'

export const DEFAULT_PRIVACY_SETTINGS = Object.freeze({
  saveHealthHistory: true,
  clearHistoryOnLogout: true,
})

export function normalizePrivacySettings(value) {
  return {
    saveHealthHistory: value?.saveHealthHistory !== false,
    clearHistoryOnLogout: value?.clearHistoryOnLogout !== false,
  }
}

export function healthHistoryKey(username) {
  const identity = typeof username === 'string' && username.trim() ? username.trim() : 'anonymous'
  return `${HEALTH_HISTORY_PREFIX}:${encodeURIComponent(identity)}`
}

export function clearHealthHistory(username) {
  localStorage.removeItem(healthHistoryKey(username))
}
