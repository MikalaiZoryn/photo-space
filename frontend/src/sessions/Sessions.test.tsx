import { cleanup, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { bodiesOf, clearCookie, json, queriesOf, setCookie } from '../test/mockApi.ts'
import { client, page, renderWorkspace, session, WS } from '../test/workspace.tsx'

describe('Sessions', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('lists sessions with times in the workspace zone, status, and client', async () => {
    renderWorkspace('#/sessions', {
      [`GET ${WS}/sessions`]: () => page([session(), session({ id: 's-2', title: 'Wedding', startsAt: null, client: null })]),
    })

    const first = (await screen.findByRole('link', { name: 'Family session' })).closest('li')!
    // 08:00 UTC is 10:00 in Warsaw, whatever zone the test machine is in.
    expect(within(first).getByText(/10:00/)).toBeTruthy()
    expect(within(first).getByText(/Anna Nowak/)).toBeTruthy()
    expect(within(first).getByText('Inquiry')).toBeTruthy()
    const second = screen.getByRole('link', { name: 'Wedding' }).closest('li')!
    expect(within(second).getByText('Not scheduled')).toBeTruthy()
    expect(screen.getByText('Times in Europe/Warsaw')).toBeTruthy()
  })

  it('shows an empty state and filters by status', async () => {
    const fetch = renderWorkspace('#/sessions', { [`GET ${WS}/sessions`]: () => page([]) })

    expect(await screen.findByText(/No sessions yet/)).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'BOOKED' } })

    expect(await screen.findByText('No sessions match these filters.')).toBeTruthy()
    expect(queriesOf(fetch, `${WS}/sessions`)).toEqual(['archived=false&page=0', 'status=BOOKED&archived=false&page=0'])
  })

  it('creates a session from wall-clock times and a client chosen in the picker', async () => {
    const fetch = renderWorkspace('#/sessions/new', {
      [`GET ${WS}/clients`]: () => page([client(), client({ id: 'c-2', name: 'Piotr' })]),
      [`POST ${WS}/sessions`]: () => json(session(), 201),
      [`GET ${WS}/sessions/s-1`]: () => json(session()),
    })

    expect(await screen.findByText('When (times in Europe/Warsaw)')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Family session' } })
    await screen.findByRole('option', { name: 'Anna Nowak' })
    fireEvent.change(screen.getByLabelText('Linked client'), { target: { value: 'c-1' } })
    fireEvent.change(screen.getByLabelText('Start'), { target: { value: '2026-07-04T10:00' } })
    fireEvent.change(screen.getByLabelText('End'), { target: { value: '2026-07-04T12:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create session' }))

    expect(await screen.findByRole('heading', { name: 'Family session' })).toBeTruthy()
    expect(bodiesOf(fetch, 'POST', `${WS}/sessions`)).toEqual([
      {
        title: 'Family session',
        sessionType: null,
        clientId: 'c-1',
        startLocal: '2026-07-04T10:00',
        endLocal: '2026-07-04T12:30',
        location: null,
        notes: null,
      },
    ])
  })

  it('preselects the client when started from a client page', async () => {
    renderWorkspace('#/sessions/new?client=c-9', {
      [`GET ${WS}/clients/c-9`]: () => json(client({ id: 'c-9', name: 'Zofia' })),
      [`GET ${WS}/clients`]: () => page([client()]),
    })

    await screen.findByRole('option', { name: 'Anna Nowak' })
    const picker = screen.getByLabelText('Linked client') as HTMLSelectElement
    expect(picker.value).toBe('c-9')
    expect(within(picker).getByRole('option', { name: 'Zofia' })).toBeTruthy()
  })

  it('offers only the allowed status changes and shows why one is refused', async () => {
    let booked = false
    const fetch = renderWorkspace('#/sessions/s-1', {
      [`GET ${WS}/sessions/s-1`]: () => json(session()),
      [`POST ${WS}/sessions/s-1/status`]: () =>
        booked
          ? json(session({ status: 'BOOKED', allowedStatuses: ['COMPLETED', 'CANCELLED'], version: 1 }))
          : json({ status: 409, title: 'Conflict', detail: 'Set a start time before changing the session to BOOKED.' }, 409),
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Mark booked' }))
    expect(screen.getByRole('button', { name: 'Cancel session' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Mark delivered' })).toBeNull()
    expect(await screen.findByText('Set a start time before changing the session to BOOKED.')).toBeTruthy()

    booked = true
    fireEvent.click(screen.getByRole('button', { name: 'Mark booked' }))
    expect(await screen.findByRole('button', { name: 'Mark completed' })).toBeTruthy()
    expect(screen.getByText('Booked')).toBeTruthy()
    expect(bodiesOf(fetch, 'POST', `${WS}/sessions/s-1/status`)).toEqual([{ status: 'BOOKED' }, { status: 'BOOKED' }])
  })

  it('edits a session with its wall-clock times and version, and shows nested field errors', async () => {
    const fetch = renderWorkspace('#/sessions/s-1/edit', {
      [`GET ${WS}/sessions/s-1`]: () => json(session({ version: 2 })),
      [`GET ${WS}/clients`]: () => page([]),
      [`PUT ${WS}/sessions/s-1`]: () =>
        json(
          {
            status: 400,
            title: 'Bad Request',
            errors: { 'session.startLocal': 'does not exist in Europe/Warsaw because the clocks change at that time' },
          },
          400,
        ),
    })

    const start = (await screen.findByLabelText('Start')) as HTMLInputElement
    expect(start.value).toBe('2026-07-04T10:00')
    expect((screen.getByLabelText('Linked client') as HTMLSelectElement).value).toBe('c-1')
    fireEvent.change(start, { target: { value: '2027-03-28T02:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save session' }))

    expect(
      await screen.findByText('Start does not exist in Europe/Warsaw because the clocks change at that time'),
    ).toBeTruthy()
    expect(bodiesOf(fetch, 'PUT', `${WS}/sessions/s-1`)).toEqual([
      {
        version: 2,
        session: {
          title: 'Family session',
          sessionType: 'Family',
          clientId: 'c-1',
          startLocal: '2027-03-28T02:30',
          endLocal: null,
          location: 'Łazienki Park',
          notes: null,
        },
      },
    ])
  })

  it('hides editing and status changes for an archived session', async () => {
    renderWorkspace('#/sessions/s-1', {
      [`GET ${WS}/sessions/s-1`]: () =>
        json(session({ archivedAt: '2026-10-15T12:00:00Z', allowedStatuses: [] })),
    })

    expect(await screen.findByRole('button', { name: 'Restore' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Edit' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Mark booked' })).toBeNull()
  })

  it('says when a session does not exist', async () => {
    renderWorkspace('#/sessions/s-404', {})

    expect(await screen.findByText(/This session was not found/)).toBeTruthy()
  })
})
