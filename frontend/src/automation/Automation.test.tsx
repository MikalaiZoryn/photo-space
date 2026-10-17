import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Workspaces } from '../Workspaces.tsx'
import { bodiesOf, clearCookie, json, mockApi, setCookie } from '../test/mockApi.ts'
import { page, renderWorkspace, workspace, WS } from '../test/workspace.tsx'

function rule(overrides: Record<string, unknown> = {}) {
  return {
    id: 'r-1',
    triggerStatus: 'COMPLETED',
    taskTitle: 'Send the gallery',
    dueInDays: 3,
    enabled: true,
    version: 0,
    createdAt: '2026-10-15T10:00:00Z',
    updatedAt: '2026-10-15T10:00:00Z',
    ...overrides,
  }
}

function run(overrides: Record<string, unknown> = {}) {
  return {
    id: 'run-1',
    ruleId: 'r-1',
    triggerStatus: 'COMPLETED',
    taskTitle: 'Send the gallery',
    dueDate: '2026-11-10',
    session: { id: 's-1', title: 'Family session', sessionType: 'Family', archived: false },
    status: 'SUCCEEDED',
    runCount: 1,
    failureReason: null,
    progress: null,
    taskId: 't-1',
    triggeredAt: '2026-11-07T10:00:00Z',
    finishedAt: '2026-11-07T10:00:02Z',
    ...overrides,
  }
}

describe('Automation', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('lets an owner add a rule and disable one', async () => {
    const fetch = renderWorkspace('#/automation', {
      [`GET ${WS}/automation/rules`]: () => json([rule()]),
      [`GET ${WS}/automation/runs`]: () => page([]),
      [`GET ${WS}/jobs`]: () => page([]),
      [`POST ${WS}/automation/rules`]: () =>
        json(rule({ id: 'r-2', triggerStatus: 'BOOKED', taskTitle: 'Send the contract', dueInDays: 0 }), 201),
      [`PUT ${WS}/automation/rules/r-1`]: () => json(rule({ enabled: false, version: 1 })),
    })

    const existing = (await screen.findByText('Send the gallery')).closest('li')!
    expect(within(existing).getByText('When a session is marked completed · due 3 days later')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Add rule' }))
    const form = screen.getByRole('form', { name: 'New rule' })
    fireEvent.change(within(form).getByLabelText('When a session is marked'), { target: { value: 'BOOKED' } })
    fireEvent.change(within(form).getByLabelText('Due after (days)'), { target: { value: '0' } })
    fireEvent.change(within(form).getByLabelText('Create a task titled'), { target: { value: 'Send the contract' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Create rule' }))
    expect(await screen.findByText('When a session is marked booked · due the same day')).toBeTruthy()

    fireEvent.click(within(existing).getByRole('button', { name: 'Disable' }))
    expect(await within(existing).findByText('Disabled')).toBeTruthy()
    expect(bodiesOf(fetch, 'POST', `${WS}/automation/rules`)).toEqual([
      { triggerStatus: 'BOOKED', taskTitle: 'Send the contract', dueInDays: 0, enabled: true },
    ])
    expect(bodiesOf(fetch, 'PUT', `${WS}/automation/rules/r-1`)).toEqual([
      { version: 0, rule: { triggerStatus: 'COMPLETED', taskTitle: 'Send the gallery', dueInDays: 3, enabled: false } },
    ])
  })

  it('shows run outcomes, retries a failed run, and lists failed jobs', async () => {
    const fetch = renderWorkspace('#/automation', {
      [`GET ${WS}/automation/rules`]: () => json([rule()]),
      [`GET ${WS}/automation/runs`]: () =>
        page([
          run(),
          run({
            id: 'run-2',
            taskTitle: 'Ask for a review',
            status: 'FAILED',
            failureReason: 'Creating the task failed repeatedly. Retry the run.',
            taskId: null,
          }),
        ]),
      [`POST ${WS}/automation/runs/run-2/retry`]: () =>
        json(run({ id: 'run-2', taskTitle: 'Ask for a review', status: 'PENDING', runCount: 2, taskId: null, finishedAt: null })),
      [`GET ${WS}/jobs`]: () =>
        page([
          {
            id: 'j-1',
            type: 'run-automation',
            subjectId: 'run-2',
            status: 'FAILED',
            attempts: 5,
            maxAttempts: 5,
            lastError: 'DataAccessResourceFailureException',
            createdAt: '2026-11-07T10:00:00Z',
            updatedAt: '2026-11-07T10:20:00Z',
            finishedAt: '2026-11-07T10:20:00Z',
          },
        ]),
    })

    const runs = (await screen.findByRole('heading', { name: 'Recent runs' })).closest('section')!
    const succeeded = (await within(runs).findByText('Send the gallery')).closest('li')!
    expect(within(succeeded).getByText('Task created')).toBeTruthy()
    expect(within(succeeded).getByRole('link', { name: 'Open task' }).getAttribute('href')).toBe('#/tasks/t-1/edit')
    const failed = within(runs).getByText('Ask for a review').closest('li')!
    expect(within(failed).getByText('Creating the task failed repeatedly. Retry the run.')).toBeTruthy()

    fireEvent.click(within(failed).getByRole('button', { name: 'Retry' }))
    expect(await within(failed).findByText('Pending')).toBeTruthy()
    expect(within(failed).getByText(/run 2/)).toBeTruthy()
    const retries = fetch.mock.calls.filter(([url, init]) => url === `${WS}/automation/runs/run-2/retry` && init?.method === 'POST')
    expect(retries).toHaveLength(1)

    const jobs = screen.getByRole('heading', { name: 'Failed background jobs' }).closest('section')!
    expect(await within(jobs).findByText('Automation run')).toBeTruthy()
    expect(within(jobs).getByText(/DataAccessResourceFailureException · 5 of 5 attempts/)).toBeTruthy()
  })

  it('shows members the rules read-only, without failed jobs or the activity log', async () => {
    window.location.hash = '#/automation'
    const fetch = mockApi({
      'GET /api/workspaces': () => json([{ ...workspace, role: 'MEMBER' }]),
      [`GET ${WS}/automation/rules`]: () => json([rule()]),
      [`GET ${WS}/automation/runs`]: () => page([]),
    })
    render(<Workspaces userId="u-2" userName="Bob" onSignOut={() => {}} />)

    expect(await screen.findByText('Send the gallery')).toBeTruthy()
    expect(screen.getByText('Only workspace owners can change rules.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Add rule' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Disable' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Failed background jobs' })).toBeNull()
    expect(screen.queryAllByRole('link', { name: 'Activity' })).toHaveLength(0)
    expect(fetch.mock.calls.some(([url]) => String(url).includes('/jobs'))).toBe(false)
  })

  it('lists activity with the actor, or Automation for the system', async () => {
    renderWorkspace('#/activity', {
      [`GET ${WS}/audit-events`]: () =>
        page([
          {
            id: 'e-1',
            action: 'TASK_CREATED',
            targetType: 'TASK',
            targetId: 't-1',
            detail: 'due 2026-11-10',
            actor: null,
            correlationId: 'job-1',
            createdAt: '2026-11-07T10:00:02Z',
          },
          {
            id: 'e-2',
            action: 'SESSION_STATUS_CHANGED',
            targetType: 'SESSION',
            targetId: 's-1',
            detail: 'BOOKED to COMPLETED',
            actor: { userId: 'u-1', displayName: 'Alice' },
            correlationId: 'abc-12345',
            createdAt: '2026-11-07T10:00:00Z',
          },
        ]),
    })

    const created = (await screen.findByRole('link', { name: 'Created a task' })).closest('li')!
    expect(created.querySelector('a')!.getAttribute('href')).toBe('#/tasks/t-1/edit')
    expect(within(created).getByText(/Automation · .* · due 2026-11-10/)).toBeTruthy()
    const changed = screen.getByRole('link', { name: 'Changed a session’s status' }).closest('li')!
    expect(within(changed).getByText(/Alice · .* · BOOKED to COMPLETED/)).toBeTruthy()
  })
})
