import { describe, expect, it } from 'vitest'

import { resolveWorkspaceAppearance } from './workspaceAppearance'

describe('resolveWorkspaceAppearance', () => {
  it.each(['medical-light', 'medical-dark'])(
    'keeps the patient workspace light when %s is stored',
    (preference) => {
      expect(resolveWorkspaceAppearance('patient', preference))
        .toEqual({ theme: 'medical-light', dark: false })
    },
  )

  it.each(['medical-light', 'medical-dark'])(
    'keeps the admin workspace light when %s is stored',
    (preference) => {
      expect(resolveWorkspaceAppearance('admin', preference))
        .toEqual({ theme: 'medical-light', dark: false })
    },
  )

  it('uses the light patient theme when no preference is stored', () => {
    expect(resolveWorkspaceAppearance('patient'))
      .toEqual({ theme: 'medical-light', dark: false })
  })

  it('respects the preference on the login workspace', () => {
    expect(resolveWorkspaceAppearance('login', 'medical-dark'))
      .toEqual({ theme: 'medical-dark', dark: true })
    expect(resolveWorkspaceAppearance('login', 'medical-light'))
      .toEqual({ theme: 'medical-light', dark: false })
  })
})
