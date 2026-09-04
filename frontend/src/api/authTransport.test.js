import { readFile } from 'node:fs/promises'
import { afterEach, describe, expect, it, vi } from 'vitest'

import client, {
  apiFetch,
  buildCookieRequestInit,
  ensureCsrfToken,
  readCsrfToken,
} from './client'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('cookie authentication transport', () => {
  it('uses credentials without a default Authorization header', () => {
    expect(client.defaults.withCredentials).toBe(true)
    expect(client.defaults.headers.common.Authorization).toBeUndefined()
  })

  it("decodes Spring Security's CSRF cookie", () => {
    expect(readCsrfToken('theme=dark; XSRF-TOKEN=abc%2B123; other=value')).toBe('abc+123')
    expect(readCsrfToken('theme=dark')).toBe('')
    expect(readCsrfToken('XSRF-TOKEN=%E0%A4%A')).toBe('')
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

  it('bootstraps a missing CSRF cookie once and reuses an existing cookie', async () => {
    const browserDocument = { cookie: '' }
    vi.stubGlobal('document', browserDocument)
    const get = vi.spyOn(client, 'get').mockImplementation(async () => {
      browserDocument.cookie = 'XSRF-TOKEN=bootstrapped-token'
      return { data: { success: true } }
    })

    await expect(ensureCsrfToken()).resolves.toBe('bootstrapped-token')
    await expect(ensureCsrfToken()).resolves.toBe('bootstrapped-token')

    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/auth/csrf', { skipCsrfBootstrap: true })
  })

  it('uses the shared transport for raw requests and expires a rejected session', async () => {
    vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=csrf-value' })
    const assign = vi.fn()
    const dispatchEvent = vi.fn()
    vi.stubGlobal('window', {
      dispatchEvent,
      location: { pathname: '/records', search: '?page=2', hash: '', assign },
    })
    const fetchMock = vi.fn().mockResolvedValue({ status: 401 })
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiFetch('/api/records', { method: 'POST' })).resolves.toEqual({ status: 401 })

    const init = fetchMock.mock.calls[0][1]
    expect(init.credentials).toBe('same-origin')
    expect(init.headers.get('X-XSRF-TOKEN')).toBe('csrf-value')
    expect(dispatchEvent).toHaveBeenCalledTimes(1)
    expect(assign).toHaveBeenCalledWith('/login?redirect=%2Frecords%3Fpage%3D2')
  })

  it('redirects unauthorized axios responses except for session probes', async () => {
    const rejected = client.interceptors.response.handlers[0].rejected
    const assign = vi.fn()
    const dispatchEvent = vi.fn()
    vi.stubGlobal('window', {
      dispatchEvent,
      location: { pathname: '/consult', search: '', hash: '', assign },
    })

    const protectedError = { response: { status: 401 }, config: { url: '/records' } }
    await expect(rejected(protectedError)).rejects.toBe(protectedError)
    expect(assign).toHaveBeenCalledTimes(1)

    assign.mockClear()
    const probeError = { response: { status: 401 }, config: { url: '/auth/me' } }
    await expect(rejected(probeError)).rejects.toBe(probeError)
    expect(assign).not.toHaveBeenCalled()

    const serverError = { response: { status: 500 }, config: { url: '/records' } }
    await expect(rejected(serverError)).rejects.toBe(serverError)
    expect(dispatchEvent).toHaveBeenCalledTimes(2)
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
