const LIGHT_THEME = 'medical-light'
const DARK_THEME = 'medical-dark'

export function resolveWorkspaceAppearance(mode, preference = LIGHT_THEME) {
  if (mode === 'admin') return { theme: LIGHT_THEME, dark: false }
  if (mode === 'patient') return { theme: LIGHT_THEME, dark: false }

  const theme = preference === DARK_THEME ? DARK_THEME : LIGHT_THEME
  return { theme, dark: theme === DARK_THEME }
}
