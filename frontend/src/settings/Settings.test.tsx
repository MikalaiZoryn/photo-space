import { cleanup, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { json } from '../test/mockApi.ts'
import { renderWorkspace, WS } from '../test/workspace.tsx'

function nav() {
  return screen.getByRole('navigation', { name: 'Workspace sections' })
}

describe('Settings', () => {
  afterEach(() => {
    cleanup()
    window.location.hash = ''
  })

  it('shows a card for each settings page', async () => {
    renderWorkspace('#/settings', {})

    const cards = await screen.findByRole('list', { name: 'Settings' })
    const hrefs = within(cards)
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'))
    expect(hrefs).toEqual(['#/automation', '#/members', '#/inquiry-form', '#/settings/content-studio'])
    expect(within(cards).getByRole('link', { name: 'Content Studio' })).toBeTruthy()
    expect(within(nav()).getByRole('link', { name: 'Settings' }).getAttribute('aria-current')).toBe('page')
  })

  it('keeps the moved pages out of the sidebar', async () => {
    renderWorkspace('#/settings', {})

    await screen.findByRole('list', { name: 'Settings' })
    for (const name of ['Automation', 'Members', 'Inquiry form', 'Content Studio']) {
      expect(within(nav()).queryByRole('link', { name })).toBeNull()
    }
  })

  it('highlights Settings on a settings page, with a link back', async () => {
    renderWorkspace('#/members', {
      [`GET ${WS}/members`]: () => json([]),
      [`GET ${WS}/invitations`]: () => json([]),
    })

    expect(await screen.findByRole('heading', { name: 'Members' })).toBeTruthy()
    expect(within(nav()).getByRole('link', { name: 'Settings' }).getAttribute('aria-current')).toBe('page')
    const main = screen.getByRole('main')
    expect(within(main).getByRole('link', { name: 'Settings' }).getAttribute('href')).toBe('#/settings')
  })
})
