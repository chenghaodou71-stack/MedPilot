import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import client, {
  apiFetch,
  buildCookieRequestInit,
  ensureCsrfToken,
  readCsrfToken,
} from './client'

function requestInterceptor() {
  return client.interceptors.request.handlers.at(-1).fulfilled
}

function responseInterceptors() {
  const handler = client.interceptors.response.handlers.at(-1)
  return { fulfilled: handler.fulfilled, rejected: handler.rejected }
}

describe('cookie client behavior', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('handles absent and malformed CSRF cookies safely', () => {
    expect(readCsrfToken('theme=dark')).toBe('')
    expect(readCsrfToken('XSRF-TOKEN=%E0%A4%A')).toBe('')
    expect(readCsrfToken(null)).toBe('')
  })

  it('returns immediately outside a browser or with an existing token', async () => {
    vi.stubGlobal('document', undefined)
    await expect(ensureCsrfToken()).resolves.toBe('')

    vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=ready' })
    await expect(ensureCsrfToken()).resolves.toBe('ready')
  })

  it('coalesces concurrent CSRF bootstrap requests', async () => {
    const browserDocument = { cookie: '' }
    vi.stubGlobal('document', browserDocument)
    const get = vi.spyOn(client, 'get').mockImplementation(async () => {
      browserDocument.cookie = 'XSRF-TOKEN=bootstrapped'
      return { data: { success: true } }
    })

    await expect(Promise.all([ensureCsrfToken(), ensureCsrfToken()]))
      .resolves.toEqual(['bootstrapped', 'bootstrapped'])
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/auth/csrf', { skipCsrfBootstrap: true })
  })

  it('applies CSRF only to state-changing axios requests', async () => {
    vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=request-token' })
    const set = vi.fn()
    const safe = { method: 'get', headers: { set } }
    const bootstrap = { method: 'post', skipCsrfBootstrap: true, headers: { set } }
    const mutation = { method: 'patch', headers: { set } }

    await expect(requestInterceptor()(safe)).resolves.toBe(safe)
    await expect(requestInterceptor()(bootstrap)).resolves.toBe(bootstrap)
    await expect(requestInterceptor()(mutation)).resolves.toBe(mutation)
    expect(set).toHaveBeenCalledOnce()
    expect(set).toHaveBeenCalledWith('X-XSRF-TOKEN', 'request-token')
  })

  it('keeps successful responses and expires unauthorized browser sessions', async () => {
    const assign = vi.fn()
    const dispatchEvent = vi.fn()
    vi.stubGlobal('window', {
      dispatchEvent,
      location: { pathname: '/records', search: '?page=2', hash: '', assign },
    })
    const { fulfilled, rejected } = responseInterceptors()
    const response = { status: 200 }

    expect(fulfilled(response)).toBe(response)
    await expect(rejected({ response: { status: 500 }, config: { url: '/records' } }))
      .rejects.toMatchObject({ response: { status: 500 } })
    expect(dispatchEvent).not.toHaveBeenCalled()

    const unauthorized = { response: { status: 401 }, config: { url: '/records' } }
    await expect(rejected(unauthorized)).rejects.toBe(unauthorized)
    expect(dispatchEvent).toHaveBeenCalledOnce()
    expect(assign).toHaveBeenCalledWith('/login?redirect=%2Frecords%3Fpage%3D2')
  })

  it.each(['/auth/login', '/auth/me'])('does not redirect a failed %s request', async (url) => {
    const assign = vi.fn()
    vi.stubGlobal('window', {
      dispatchEvent: vi.fn(),
      location: { pathname: '/records', search: '', hash: '', assign },
    })
    const { rejected } = responseInterceptors()
    const unauthorized = { response: { status: 401 }, config: { url } }

    await expect(rejected(unauthorized)).rejects.toBe(unauthorized)
    expect(assign).not.toHaveBeenCalled()
  })

  it('builds default request init values', () => {
    const request = buildCookieRequestInit()

    expect(request.credentials).toBe('same-origin')
    expect([...request.headers]).toEqual([])
  })

  it('uses fetch with cookies and expires a rejected API session', async () => {
    const assign = vi.fn()
    const dispatchEvent = vi.fn()
    vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=fetch-token' })
    vi.stubGlobal('window', {
      dispatchEvent,
      location: { pathname: '/consult', search: '', hash: '', assign },
    })
    const response = { status: 401 }
    const fetchMock = vi.fn().mockResolvedValue(response)
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiFetch('/api/consult', { method: 'POST', body: '{}' })).resolves.toBe(response)
    const [, request] = fetchMock.mock.calls[0]
    expect(request.credentials).toBe('same-origin')
    expect(request.headers.get('X-XSRF-TOKEN')).toBe('fetch-token')
    expect(dispatchEvent).toHaveBeenCalledOnce()
    expect(assign).toHaveBeenCalledWith('/login?redirect=%2Fconsult')
  })

  it('does not expire a successful raw request', async () => {
    vi.stubGlobal('document', { cookie: '' })
    const dispatchEvent = vi.fn()
    vi.stubGlobal('window', { dispatchEvent, location: { pathname: '/consult' } })
    const response = { status: 204 }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

    await expect(apiFetch('/api/health')).resolves.toBe(response)
    expect(dispatchEvent).not.toHaveBeenCalled()
  })
})
