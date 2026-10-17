import { act, cleanup, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { bodiesOf, json, queriesOf } from '../test/mockApi.ts'
import { page, renderWorkspace, session, workspace, WS } from '../test/workspace.tsx'

const LAYOUT = `${WS}/dashboard/layout`

function layout(widgets: [string, 'HALF' | 'FULL'][], overrides: Record<string, unknown> = {}) {
  return json({
    widgets: widgets.map(([type, width]) => ({ type, width })),
    customized: true,
    version: 3,
    updatedAt: '2026-10-17T10:00:00Z',
    ...overrides,
  })
}

const empty = () => page([])

/** Every list a widget reads, empty unless a test overrides it. */
const lists = {
  [`GET ${WS}/sessions`]: empty,
  [`GET ${WS}/tasks`]: empty,
  [`GET ${WS}/inquiries`]: empty,
  [`GET ${WS}/content/batches`]: empty,
  [`GET ${WS}/galleries`]: empty,
  [`GET ${WS}/audit-events`]: empty,
}

function widgetTitles() {
  const grid = screen.getByRole('list', { name: 'Widgets' })
  return within(grid)
    .getAllByRole('heading')
    .map((h) => h.textContent)
}

function nav() {
  return screen.getByRole('navigation', { name: 'Workspace sections' })
}

describe('Dashboard', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    window.location.hash = ''
  })

  it('is the default page and the first item in the sidebar', async () => {
    renderWorkspace('', { ...lists, [`GET ${LAYOUT}`]: () => layout([['UPCOMING_SESSIONS', 'HALF']]) })

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeTruthy()
    const first = within(nav()).getAllByRole('link')[0]
    expect(first.textContent).toBe('Dashboard')
    expect(first.getAttribute('aria-current')).toBe('page')
  })

  it('shows the saved widgets in order, each with its own data', async () => {
    const fetch = renderWorkspace('#/dashboard', {
      ...lists,
      [`GET ${LAYOUT}`]: () =>
        layout([
          ['AT_A_GLANCE', 'FULL'],
          ['UPCOMING_SESSIONS', 'HALF'],
          ['MY_TASKS', 'HALF'],
        ]),
      [`GET ${WS}/sessions`]: (_, url) =>
        url.searchParams.get('size') === '1' ? page([], { totalItems: 4 }) : page([session({ status: 'BOOKED' })]),
      [`GET ${WS}/tasks`]: (_, url) =>
        url.searchParams.has('assigneeId') ? page([]) : page([], { totalItems: url.searchParams.has('overdue') ? 2 : 7 }),
    })

    expect(await screen.findByRole('link', { name: /Family session/ })).toBeTruthy()
    expect(widgetTitles()).toEqual(['At a glance', 'Upcoming sessions', 'My tasks'])
    const counts = await screen.findByRole('list', { name: 'Counts' })
    expect(within(counts).getByRole('link', { name: '4 Upcoming sessions' })).toBeTruthy()
    expect(within(counts).getByRole('link', { name: '7 Open tasks' })).toBeTruthy()
    expect(within(counts).getByRole('link', { name: '2 Overdue tasks' })).toBeTruthy()
    expect(await screen.findByText('No open tasks assigned to you.')).toBeTruthy()

    expect(queriesOf(fetch, `${WS}/sessions`).sort()).toEqual(['size=1&upcoming=true', 'upcoming=true&size=5'].sort())
    expect(queriesOf(fetch, `${WS}/tasks`)).toContain('status=OPEN&assigneeId=u-1&size=5')
    expect(queriesOf(fetch, `${WS}/inquiries`)).toEqual(['size=1&open=true'])
    expect(queriesOf(fetch, `${WS}/content/batches`)).toEqual(['size=1&toReview=true'])
    expect(screen.getByRole('link', { name: 'View all: Upcoming sessions' }).getAttribute('href')).toBe('#/sessions')
  })

  it('keeps the other widgets working when one fails', async () => {
    renderWorkspace('#/dashboard', {
      ...lists,
      [`GET ${LAYOUT}`]: () => layout([['OPEN_INQUIRIES', 'HALF'], ['UPCOMING_SESSIONS', 'HALF']]),
      [`GET ${WS}/inquiries`]: () => json({ status: 500, title: 'Internal Server Error' }, 500),
    })

    expect(await screen.findByText(/Could not load open inquiries/)).toBeTruthy()
    expect(await screen.findByText('No upcoming sessions.')).toBeTruthy()
  })

  it('removes, resizes, and adds widgets, then saves the layout', async () => {
    const fetch = renderWorkspace('#/dashboard', {
      ...lists,
      [`GET ${LAYOUT}`]: () => layout([['UPCOMING_SESSIONS', 'HALF'], ['MY_TASKS', 'HALF'], ['GALLERIES', 'HALF']]),
      [`PUT ${LAYOUT}`]: (init) => {
        const body = JSON.parse(String(init?.body)) as { widgets: unknown[] }
        return json({ widgets: body.widgets, customized: true, version: 4, updatedAt: '2026-10-17T11:00:00Z' })
      },
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Customize' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove My tasks' }))
    fireEvent.click(screen.getByRole('button', { name: 'Make Upcoming sessions full width' }))
    const add = screen.getByRole('region', { name: 'Add widgets' })
    expect(within(add).getByRole('button', { name: 'My tasks' })).toBeTruthy()
    fireEvent.click(within(add).getByRole('button', { name: 'Quick actions' }))
    expect(widgetTitles()).toEqual(['Upcoming sessions', 'Recent galleries', 'Quick actions'])

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('button', { name: 'Customize' })).toBeTruthy()
    expect(bodiesOf(fetch, 'PUT', LAYOUT)).toEqual([
      {
        version: 3,
        widgets: [
          { type: 'UPCOMING_SESSIONS', width: 'FULL' },
          { type: 'GALLERIES', width: 'HALF' },
          { type: 'QUICK_ACTIONS', width: 'HALF' },
        ],
      },
    ])
    expect(screen.queryByRole('button', { name: 'Remove Quick actions' })).toBeNull()
  })

  it('moves a widget with the keyboard', async () => {
    // jsdom has no layout: give each widget a row of its own, in DOM order, so the keyboard sensor can find them.
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      const item = this.closest('li')
      const index = item?.parentElement ? Array.from(item.parentElement.children).indexOf(item) : 0
      return DOMRect.fromRect({ x: 0, y: index * 200, width: 600, height: 180 })
    })
    const fetch = renderWorkspace('#/dashboard', {
      ...lists,
      [`GET ${LAYOUT}`]: () => layout([['UPCOMING_SESSIONS', 'HALF'], ['MY_TASKS', 'HALF'], ['GALLERIES', 'HALF']]),
      [`PUT ${LAYOUT}`]: (init) => json({ ...JSON.parse(String(init?.body)), customized: true, version: 4, updatedAt: null }),
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Customize' }))
    const handle = screen.getByRole('button', { name: 'Move Upcoming sessions' })
    handle.focus()
    // The keyboard sensor starts listening for the next keys after a timeout.
    for (const code of ['Space', 'ArrowDown', 'Space']) {
      await act(async () => {
        fireEvent.keyDown(handle, { code })
        await new Promise((resolve) => setTimeout(resolve, 0))
      })
    }

    expect(widgetTitles()).toEqual(['My tasks', 'Upcoming sessions', 'Recent galleries'])
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByRole('button', { name: 'Customize' })
    expect(bodiesOf(fetch, 'PUT', LAYOUT)).toEqual([
      {
        version: 3,
        widgets: [
          { type: 'MY_TASKS', width: 'HALF' },
          { type: 'UPCOMING_SESSIONS', width: 'HALF' },
          { type: 'GALLERIES', width: 'HALF' },
        ],
      },
    ])
  })

  it('cancels unsaved changes and explains a conflicting save', async () => {
    renderWorkspace('#/dashboard', {
      ...lists,
      [`GET ${LAYOUT}`]: () => layout([['UPCOMING_SESSIONS', 'HALF'], ['MY_TASKS', 'HALF']]),
      [`PUT ${LAYOUT}`]: () =>
        json({ status: 409, title: 'Conflict', detail: 'Your dashboard was changed in another window.' }, 409),
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Customize' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove My tasks' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(widgetTitles()).toEqual(['Upcoming sessions', 'My tasks'])

    fireEvent.click(screen.getByRole('button', { name: 'Customize' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove My tasks' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Your dashboard was changed in another window.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Save' })).toBeTruthy()
    expect(widgetTitles()).toEqual(['Upcoming sessions'])
  })

  it('resets to the default layout', async () => {
    const fetch = renderWorkspace('#/dashboard', {
      ...lists,
      [`GET ${LAYOUT}`]: () => layout([['GALLERIES', 'FULL']]),
      [`DELETE ${LAYOUT}`]: () =>
        layout([['AT_A_GLANCE', 'FULL'], ['UPCOMING_SESSIONS', 'HALF']], { customized: false, version: 0, updatedAt: null }),
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Customize' }))
    fireEvent.click(screen.getByRole('button', { name: 'Reset to default' }))

    expect(await screen.findByRole('button', { name: 'Customize' })).toBeTruthy()
    expect(widgetTitles()).toEqual(['At a glance', 'Upcoming sessions'])
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1)
  })

  it('does not offer owner-only widgets to members', async () => {
    renderWorkspace('#/dashboard', {
      'GET /api/workspaces': () => json([{ ...workspace, role: 'MEMBER' }]),
      ...lists,
      [`GET ${LAYOUT}`]: () => layout([], { customized: false, version: 0, updatedAt: null }),
    })

    expect(await screen.findByText('Your dashboard has no widgets.')).toBeTruthy()
    fireEvent.click(screen.getAllByRole('button', { name: 'Customize' })[0])
    const add = screen.getByRole('region', { name: 'Add widgets' })
    expect(within(add).getByRole('button', { name: 'Quick actions' })).toBeTruthy()
    expect(within(add).queryByRole('button', { name: 'Recent activity' })).toBeNull()
  })
})
