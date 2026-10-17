import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { json, mockApi } from '../test/mockApi.ts'
import { AppShell } from './AppShell.tsx'

function prefersDark(matches: boolean) {
  const listeners = new Set<() => void>()
  const media = {
    get matches() {
      return matches
    },
    addEventListener: (_: string, fn: () => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: () => void) => listeners.delete(fn),
  }
  vi.stubGlobal('matchMedia', () => media)
  return (next: boolean) => {
    matches = next
    listeners.forEach((fn) => fn())
  }
}

function renderShell() {
  render(
    <AppShell userName="Alice" onSignOut={() => {}}>
      <p>Content</p>
    </AppShell>,
  )
}

describe('AppShell theme toggle', () => {
  beforeEach(() => {
    mockApi({ 'GET /api/health': () => json({ status: 'UP', database: 'UP' }) })
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    window.localStorage.clear()
    delete document.documentElement.dataset.theme
  })

  it('follows the system setting until the user picks a theme', () => {
    const setSystemDark = prefersDark(true)
    renderShell()
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(screen.getByRole('button', { name: 'Theme: System. Switch to light' })).toBeTruthy()

    setSystemDark(false)
    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('cycles light, dark, system and remembers the choice', () => {
    prefersDark(true)
    renderShell()

    fireEvent.click(screen.getByRole('button', { name: 'Theme: System. Switch to light' }))
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(window.localStorage.getItem('photospace.theme')).toBe('light')

    fireEvent.click(screen.getByRole('button', { name: 'Theme: Light. Switch to dark' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(window.localStorage.getItem('photospace.theme')).toBe('dark')

    fireEvent.click(screen.getByRole('button', { name: 'Theme: Dark. Switch to system' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(window.localStorage.getItem('photospace.theme')).toBeNull()
  })

  it('starts from the saved choice', () => {
    prefersDark(true)
    window.localStorage.setItem('photospace.theme', 'light')
    renderShell()
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(screen.getByRole('button', { name: 'Theme: Light. Switch to dark' })).toBeTruthy()
  })
})
