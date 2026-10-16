import { cleanup, fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { bodiesOf, clearCookie, json, queriesOf, setCookie } from '../test/mockApi.ts'
import { client, page, renderWorkspace, WS } from '../test/workspace.tsx'

describe('Clients', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('shows an empty state and creates the first client', async () => {
    let created = false
    const fetch = renderWorkspace('#/clients', {
      [`GET ${WS}/clients`]: () => page(created ? [client()] : []),
      [`POST ${WS}/clients`]: () => {
        created = true
        return json(client(), 201)
      },
      [`GET ${WS}/clients/c-1`]: () => json(client()),
      [`GET ${WS}/sessions`]: () => page([]),
    })

    expect(await screen.findByText(/No clients yet/)).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: 'New client' }))
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: '  Anna Nowak ' } })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'anna@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    expect(await screen.findByRole('heading', { name: 'Anna Nowak' })).toBeTruthy()
    expect(window.location.hash).toBe('#/clients/c-1')
    expect(bodiesOf(fetch, 'POST', `${WS}/clients`)).toEqual([
      { name: 'Anna Nowak', email: 'anna@example.com', phone: null, notes: null },
    ])
    expect(await screen.findByText('No sessions with this client yet.')).toBeTruthy()
    expect(queriesOf(fetch, `${WS}/sessions`)).toEqual(['clientId=c-1&page=0'])
  })

  it('shows server validation errors next to the field', async () => {
    renderWorkspace('#/clients/new', {
      [`POST ${WS}/clients`]: () =>
        json({ status: 400, title: 'Bad Request', errors: { email: 'must be a well-formed email address' } }, 400),
    })

    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Anna' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    expect(await screen.findByText('Email must be a well-formed email address')).toBeTruthy()
  })

  it('searches and pages through clients', async () => {
    const many = Array.from({ length: 25 }, (_, i) => client({ id: `c-${i}`, name: `Client ${i}` }))
    const fetch = renderWorkspace('#/clients', {
      [`GET ${WS}/clients`]: (_, url) =>
        page(url.searchParams.get('page') === '1' ? [client({ id: 'c-last', name: 'Last client' })] : many, {
          page: Number(url.searchParams.get('page')),
          totalItems: 26,
        }),
    })

    expect(await screen.findByText('1–25 of 26')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('link', { name: 'Last client' })).toBeTruthy()
    expect(screen.getByText('26–26 of 26')).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Search'), { target: { value: ' ann ' } })
    await screen.findByText('1–25 of 26')
    expect(queriesOf(fetch, `${WS}/clients`)).toEqual([
      'archived=false&page=0',
      'archived=false&page=1',
      'q=ann&archived=false&page=0',
    ])
  })

  it('saves edits with the loaded version and explains a conflict', async () => {
    const fetch = renderWorkspace('#/clients/c-1/edit', {
      [`GET ${WS}/clients/c-1`]: () => json(client({ version: 3 })),
      [`PUT ${WS}/clients/c-1`]: () =>
        json(
          {
            status: 409,
            title: 'Conflict',
            detail: 'This client was changed by someone else. Reload it and try again.',
          },
          409,
        ),
    })

    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Anna Kowalska' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save client' }))

    expect(await screen.findByText('This client was changed by someone else. Reload it and try again.')).toBeTruthy()
    expect(bodiesOf(fetch, 'PUT', `${WS}/clients/c-1`)).toEqual([
      { version: 3, client: { name: 'Anna Kowalska', email: 'anna@example.com', phone: null, notes: null } },
    ])
  })

  it('archives and restores a client', async () => {
    renderWorkspace('#/clients/c-1', {
      [`GET ${WS}/clients/c-1`]: () => json(client()),
      [`GET ${WS}/sessions`]: () => page([]),
      [`POST ${WS}/clients/c-1/archive`]: () => json(client({ archivedAt: '2026-10-15T12:00:00Z', version: 1 })),
      [`POST ${WS}/clients/c-1/restore`]: () => json(client({ version: 2 })),
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Archive' }))

    expect(await screen.findByRole('button', { name: 'Restore' })).toBeTruthy()
    expect(screen.getByText(/Archived on 15 Oct 2026|Archived on Oct 15, 2026/)).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Edit' })).toBeNull()
    expect(screen.queryByRole('link', { name: /New session for/ })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    expect(await screen.findByRole('link', { name: 'Edit' })).toBeTruthy()
  })

  it('says when a client does not exist', async () => {
    renderWorkspace('#/clients/c-404', {})

    expect(await screen.findByText(/This client was not found/)).toBeTruthy()
  })

  it('offers a retry when loading fails', async () => {
    let fail = true
    renderWorkspace('#/clients', {
      [`GET ${WS}/clients`]: () =>
        fail ? json({ status: 500, title: 'Internal Server Error', correlationId: 'ref-1' }, 500) : page([]),
    })

    expect(await screen.findByText(/Could not load clients \(ref ref-1\)/)).toBeTruthy()
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText(/No clients yet/)).toBeTruthy()
  })
})
