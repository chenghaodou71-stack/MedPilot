import { readFile } from 'node:fs/promises'
import { describe, expect, it } from 'vitest'

import client, { buildCookieRequestInit, readCsrfToken } from './client'

describe('cookie authentication transport', () => {
  it('uses credentials without a default Authorization header', () => {
    expect(client.defaults.withCredentials).toBe(true)
    expect(client.defaults.headers.common.Authorization).toBeUndefined()
  })

  it("decodes Spring Security's CSRF cookie", () => {
    expect(readCsrfToken('theme=dark; XSRF-TOKEN=abc%2B123; other=value')).toBe('abc+123')
  })

  it('adds credentials and CSRF only to a state-changing raw request', () => {
    const request = buildCookieRequestInit(
      { method: 'POST', headers: { 'Content-Type': 'application/json' } },
      'csrf-token',
    )
    const safeRequest = buildCookieRequestInit({ method: 'GET' }, 'csrf-token')

    expect(request.credentials).toBe('same-origin')
    expect(request.headers.get('X-XSRF-TOKEN')).toBe('csrf-token')
    expect(safeRequest.credentials).toBe('same-origin')
    expect(safeRequest.headers.has('X-XSRF-TOKEN')).toBe(false)
  })

  it('keeps authentication tokens out of browser storage and view fetch calls', async () => {
    const sources = await Promise.all([
      readFile(new URL('../stores/auth.js', import.meta.url), 'utf8'),
      readFile(new URL('./client.js', import.meta.url), 'utf8'),
      readFile(new URL('../views/ConsultView.vue', import.meta.url), 'utf8'),
      readFile(new URL('../views/MonitorView.vue', import.meta.url), 'utf8'),
    ])
    const authSource = sources.join('\n')

    expect(authSource).not.toMatch(/localStorage\.(?:getItem|setItem|removeItem)\(['"](?:token|role|username)['"]\)/)
    expect(authSource).not.toMatch(/Authorization\s*[:=].*Bearer/)
    expect(sources[2]).not.toMatch(/\bfetch\s*\(/)
    expect(sources[3]).not.toMatch(/\bfetch\s*\(/)
  })
})
