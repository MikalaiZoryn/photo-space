import { cleanup, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { bodiesOf, clearCookie, json, noContent, queriesOf, setCookie } from '../test/mockApi.ts'
import { client, page, renderWorkspace, session, WS } from '../test/workspace.tsx'

function task(overrides: Record<string, unknown> = {}) {
  return {
    id: 't-1',
    title: 'Send the contract',
    description: null,
    dueDate: '2026-11-07',
    overdue: false,
    status: 'OPEN',
    client: { id: 'c-1', name: 'Anna Nowak', archived: false },
    session: { id: 's-1', title: 'Family session', sessionType: 'Family', archived: false },
    assignee: { userId: 'u-1', displayName: 'Alice' },
    source: 'MANUAL',
    completedAt: null,
    version: 0,
    createdAt: '2026-10-15T10:00:00Z',
    updatedAt: '2026-10-15T10:00:00Z',
    ...overrides,
  }
}

const members = () => json([{ userId: 'u-1', displayName: 'Alice', role: 'OWNER', joinedAt: '2026-10-15T10:00:00Z' }])

describe('Tasks', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('lists open tasks with their due date, links, and badges, and completes one', async () => {
    const fetch = renderWorkspace('#/tasks', {
      [`GET ${WS}/members`]: members,
      [`GET ${WS}/tasks`]: () =>
        page([task(), task({ id: 't-2', title: 'Send the gallery', overdue: true, source: 'AUTOMATION', assignee: null })]),
      [`POST ${WS}/tasks/t-1/status`]: () => json(task({ status: 'DONE', completedAt: '2026-10-16T10:00:00Z', version: 1 })),
    })

    const first = (await screen.findByRole('link', { name: 'Send the contract' })).closest('li')!
    expect(within(first).getByText(/Due Nov 7, 2026 · Family session · Anna Nowak · Assigned to Alice/)).toBeTruthy()
    const second = screen.getByRole('link', { name: 'Send the gallery' }).closest('li')!
    expect(within(second).getByText('Overdue')).toBeTruthy()
    expect(within(second).getByText('Automated')).toBeTruthy()
    expect(queriesOf(fetch, `${WS}/tasks`)).toEqual(['status=OPEN&page=0'])

    fireEvent.click(screen.getByRole('checkbox', { name: 'Complete Send the contract' }))
    expect(await within(first).findByText('Done')).toBeTruthy()
    expect((screen.getByRole('checkbox', { name: 'Reopen Send the contract' }) as HTMLInputElement).checked).toBe(true)
    expect(bodiesOf(fetch, 'POST', `${WS}/tasks/t-1/status`)).toEqual([{ status: 'DONE' }])
  })

  it('filters by status, assignee, and overdue', async () => {
    const fetch = renderWorkspace('#/tasks', {
      [`GET ${WS}/members`]: members,
      [`GET ${WS}/tasks`]: () => page([]),
    })

    expect(await screen.findByText(/No open tasks/)).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Show'), { target: { value: '' } })
    await screen.findByText('No tasks match these filters.')
    await screen.findByRole('option', { name: 'Alice' })
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'u-1' } })
    await screen.findByText('No tasks match these filters.')
    fireEvent.click(screen.getByLabelText('Overdue only'))
    await screen.findByText('No tasks match these filters.')

    expect(queriesOf(fetch, `${WS}/tasks`)).toEqual([
      'status=OPEN&page=0',
      'page=0',
      'assigneeId=u-1&page=0',
      'assigneeId=u-1&overdue=true&page=0',
    ])
  })

  it('adds a task from a session page, linked to the session and its client', async () => {
    const fetch = renderWorkspace('#/tasks/new?session=s-1', {
      [`GET ${WS}/sessions/s-1`]: () => json(session()),
      [`GET ${WS}/members`]: members,
      [`GET ${WS}/clients`]: () => page([client()]),
      [`POST ${WS}/tasks`]: () => json(task(), 201),
    })

    expect(await screen.findByRole('link', { name: 'Family session' })).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: ' Send the contract ' } })
    fireEvent.change(screen.getByLabelText('Due date'), { target: { value: '2026-11-07' } })
    await screen.findByRole('option', { name: 'Alice' })
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'u-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create task' }))

    await vi.waitFor(() => expect(window.location.hash).toBe('#/sessions/s-1'))
    expect(bodiesOf(fetch, 'POST', `${WS}/tasks`)).toEqual([
      {
        title: 'Send the contract',
        description: null,
        dueDate: '2026-11-07',
        clientId: 'c-1',
        sessionId: 's-1',
        assigneeId: 'u-1',
      },
    ])
  })

  it('edits a task with its version, shows field errors, and deletes it after confirming', async () => {
    let failed = false
    const fetch = renderWorkspace('#/tasks/t-1/edit', {
      [`GET ${WS}/tasks/t-1`]: () => json(task({ version: 3, session: null })),
      [`GET ${WS}/members`]: members,
      [`GET ${WS}/clients`]: () => page([client()]),
      [`PUT ${WS}/tasks/t-1`]: () => {
        if (failed) return json(task({ version: 4 }))
        failed = true
        return json({ status: 400, title: 'Bad Request', errors: { 'task.assigneeId': 'is not a member of this workspace' } }, 400)
      },
      [`DELETE ${WS}/tasks/t-1`]: () => noContent(),
    })

    const title = (await screen.findByLabelText('Title')) as HTMLInputElement
    expect(title.value).toBe('Send the contract')
    fireEvent.click(screen.getByRole('button', { name: 'Save task' }))
    expect(await screen.findByText('Assignee is not a member of this workspace')).toBeTruthy()
    expect((bodiesOf(fetch, 'PUT', `${WS}/tasks/t-1`)[0] as { version: number }).version).toBe(3)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(bodiesOf(fetch, 'DELETE', `${WS}/tasks/t-1`)).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete' }))
    await vi.waitFor(() => expect(window.location.hash).toBe('#/tasks'))
  })

  it('shows the session’s tasks on its page', async () => {
    const fetch = renderWorkspace('#/sessions/s-1', {
      [`GET ${WS}/sessions/s-1`]: () => json(session()),
      [`GET ${WS}/tasks`]: () => page([task({ source: 'AUTOMATION' })]),
    })

    const panel = (await screen.findByRole('heading', { name: 'Tasks' })).closest('section')!
    const row = (await within(panel).findByRole('link', { name: 'Send the contract' })).closest('li')!
    expect(within(row).queryByText(/Family session/)).toBeNull()
    expect(within(panel).getByRole('link', { name: 'Add task' }).getAttribute('href')).toBe('#/tasks/new?session=s-1')
    expect(queriesOf(fetch, `${WS}/tasks`)).toEqual(['sessionId=s-1&page=0'])
  })

  it('reloads the session’s tasks after a status change, and again once rules have had time to run', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      let ruleRan = false
      const fetch = renderWorkspace('#/sessions/s-1', {
        [`GET ${WS}/sessions/s-1`]: () => json(session()),
        [`POST ${WS}/sessions/s-1/status`]: () =>
          json(session({ status: 'BOOKED', allowedStatuses: ['COMPLETED', 'CANCELLED'], version: 1 })),
        [`GET ${WS}/tasks`]: () => page(ruleRan ? [task({ source: 'AUTOMATION' })] : []),
      })

      fireEvent.click(await screen.findByRole('button', { name: 'Mark booked' }))
      await vi.waitFor(() => expect(queriesOf(fetch, `${WS}/tasks`)).toHaveLength(2))
      expect(await screen.findByText('No tasks for this session yet.')).toBeTruthy()

      ruleRan = true
      await vi.advanceTimersByTimeAsync(4000)
      expect(await screen.findByRole('link', { name: 'Send the contract' })).toBeTruthy()
      expect(queriesOf(fetch, `${WS}/tasks`)).toHaveLength(3)
    } finally {
      vi.useRealTimers()
    }
  })
})
