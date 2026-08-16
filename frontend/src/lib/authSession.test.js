import { describe, expect, it } from 'vitest'

import {
  AUTH_SESSION_EXPIRED_EVENT,
  buildLoginRedirect,
  expireBrowserSession,
} from './authSession'

describe('expireBrowserSession', () => {
  it('notifies the in-memory session before redirecting to login', () => {
    const actions = []
    const browser = {
      dispatchEvent: (event) => actions.push(event.type),
      location: {
        pathname: '/records/7',
        search: '?tab=trace',
        hash: '',
        assign: (target) => actions.push(target),
      },
    }

    expireBrowserSession(browser)

    expect(actions).toEqual([
      AUTH_SESSION_EXPIRED_EVENT,
      '/login?redirect=%2Frecords%2F7%3Ftab%3Dtrace',
    ])
  })

  it('can clear the session without redirecting during a session probe', () => {
    const actions = []
    const browser = {
      dispatchEvent: (event) => actions.push(event.type),
      location: { pathname: '/consult', assign: (target) => actions.push(target) },
    }

    expireBrowserSession(browser, { redirect: false })

    expect(actions).toEqual([AUTH_SESSION_EXPIRED_EVENT])
  })
})

describe('buildLoginRedirect', () => {
  it('preserves the current internal route', () => {
    expect(buildLoginRedirect({ pathname: '/records/7', search: '?tab=trace', hash: '' }))
      .toBe('/login?redirect=%2Frecords%2F7%3Ftab%3Dtrace')
  })

  it('does not recursively redirect a login page', () => {
    expect(buildLoginRedirect({ pathname: '/login', search: '', hash: '' })).toBe('/login')
  })
})
