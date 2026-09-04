import { describe, expect, it } from 'vitest'

import { adminUserErrorText, validAdminPassword, validAdminUsername } from './adminUsers'

describe('admin user helpers', () => {
  it('renders deterministic messages for protected backend operations', () => {
    expect(adminUserErrorText({
      response: { data: { error: 'cannot delete your own account' } },
    }, 'fallback')).toBe('不能删除当前登录账号。')
    expect(adminUserErrorText({
      response: { data: { error: 'user owns consultation records; disable the account instead' } },
    }, 'fallback')).toContain('关联问诊记录')
    expect(adminUserErrorText({
      response: { data: { error: 'at least one active administrator is required' } },
    }, 'fallback')).toContain('至少一名')
  })

  it('supports nested proxy errors without hiding unknown server messages', () => {
    expect(adminUserErrorText({
      response: { data: { error: '{"detail":"username already exists"}' } },
    }, 'fallback')).toBe('该账号名已存在。')
    expect(adminUserErrorText({
      response: { data: { error: 'custom error' } },
    }, 'fallback')).toBe('custom error')
  })

  it('matches backend account validation before submitting', () => {
    expect(validAdminUsername('reviewer-01')).toBe(true)
    expect(validAdminUsername('A Reviewer')).toBe(false)
    expect(validAdminPassword('0123456789')).toBe(true)
    expect(validAdminPassword('short')).toBe(false)
  })

  it('falls back for empty or non-string errors and maps all protected operations', () => {
    expect(adminUserErrorText(null, 'fallback')).toBe('fallback')
    expect(adminUserErrorText({ response: { data: { detail: '  ' } } }, 'fallback')).toBe('fallback')
    expect(adminUserErrorText({ response: { data: { message: 42 } } }, 'fallback')).toBe('fallback')
    expect(adminUserErrorText({
      response: { data: { detail: 'cannot disable or change your own administrator access' } },
    }, 'fallback')).toContain('当前账号')
    expect(adminUserErrorText({
      response: { data: { message: 'USER NOT FOUND' } },
    }, 'fallback')).toContain('不存在')
    expect(adminUserErrorText({
      response: { data: { error: '{"message":"username format is invalid"}' } },
    }, 'fallback')).toContain('3-64')
  })

  it('enforces username and password boundaries for non-string values', () => {
    expect(validAdminUsername('ab')).toBe(false)
    expect(validAdminUsername('a'.repeat(64))).toBe(true)
    expect(validAdminUsername('a'.repeat(65))).toBe(false)
    expect(validAdminUsername(null)).toBe(false)
    expect(validAdminPassword('x'.repeat(128))).toBe(true)
    expect(validAdminPassword('x'.repeat(129))).toBe(false)
    expect(validAdminPassword(1234567890)).toBe(false)
  })
})
