import { describe, expect, it } from 'vitest'

import { resolveWorkspaceAppearance } from './workspaceAppearance'

describe('resolveWorkspaceAppearance', () => {
  it('keeps the patient workspace light even when a dark preference is stored', () => {
    expect(resolveWorkspaceAppearance('patient', 'medical-dark'))
      .toEqual({ theme: 'medical-light', dark: false })
  })

  it('keeps the admin workspace in the dark console theme', () => {
    expect(resolveWorkspaceAppearance('admin', 'medical-light'))
      .toEqual({ theme: 'medical-dark', dark: true })
  })

  it('respects the preference on the login workspace', () => {
    expect(resolveWorkspaceAppearance('login', 'medical-dark'))
      .toEqual({ theme: 'medical-dark', dark: true })
    expect(resolveWorkspaceAppearance('login', 'medical-light'))
      .toEqual({ theme: 'medical-light', dark: false })
  })
})
