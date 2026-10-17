import { useEffect, useState } from 'react'

// Only remembers the color theme picked in this browser. public/theme.js reads the same key before first paint.
const THEME_KEY = 'photospace.theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

export type ThemeChoice = 'system' | 'light' | 'dark'

export function readTheme(): ThemeChoice {
  try {
    const value = window.localStorage.getItem(THEME_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

function writeTheme(choice: ThemeChoice) {
  try {
    if (choice === 'system') window.localStorage.removeItem(THEME_KEY)
    else window.localStorage.setItem(THEME_KEY, choice)
  } catch {
    // Storage can be unavailable (private mode, blocked site data); the choice then lasts for this page only.
  }
}

function systemPrefersDark(): boolean {
  return window.matchMedia?.(DARK_QUERY).matches ?? false
}

/** Sets `data-theme` on <html>, which the CSS tokens and the `dark:` variant key on, and the browser UI color. */
function applyTheme(choice: ThemeChoice) {
  const root = document.documentElement
  root.dataset.theme = choice === 'dark' || (choice === 'system' && systemPrefersDark()) ? 'dark' : 'light'
  const background = getComputedStyle(root).getPropertyValue('--background').trim()
  if (!background) return
  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach((meta) => {
    meta.content = background
  })
}

/** The theme picked in this browser and a setter. While on `system`, the page follows the OS setting live. */
export function useTheme(): [ThemeChoice, (choice: ThemeChoice) => void] {
  const [choice, setChoice] = useState(readTheme)

  useEffect(() => {
    applyTheme(choice)
    if (choice !== 'system' || !window.matchMedia) return
    const media = window.matchMedia(DARK_QUERY)
    const follow = () => applyTheme('system')
    media.addEventListener('change', follow)
    return () => media.removeEventListener('change', follow)
  }, [choice])

  const change = (next: ThemeChoice) => {
    writeTheme(next)
    setChoice(next)
  }
  return [choice, change]
}
