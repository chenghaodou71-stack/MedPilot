import { describe, expect, it } from 'vitest'
import {
  DEFAULT_PRIVACY_SETTINGS,
  healthHistoryKey,
  normalizePrivacySettings,
} from './privacy'

describe('healthHistoryKey', () => {
  it('isolates history by username', () => {
    expect(healthHistoryKey('alice')).not.toBe(healthHistoryKey('bob'))
  })

  it('encodes user-controlled key content', () => {
    expect(healthHistoryKey('a/b c')).toBe('medpilot-health-search-history:a%2Fb%20c')
  })
})

describe('DEFAULT_PRIVACY_SETTINGS', () => {
  it('clears health history on logout by default', () => {
    expect(DEFAULT_PRIVACY_SETTINGS.clearHistoryOnLogout).toBe(true)
  })

  it('keeps an explicit opt-out while defaulting missing values safely', () => {
    expect(normalizePrivacySettings(null).clearHistoryOnLogout).toBe(true)
    expect(normalizePrivacySettings({ clearHistoryOnLogout: false }).clearHistoryOnLogout).toBe(false)
  })
})
