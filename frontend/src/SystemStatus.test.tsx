import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SystemStatus } from './SystemStatus.tsx'

function mockFetch(response: Response | Error) {
  const fn = vi.fn(() => (response instanceof Error ? Promise.reject(response) : Promise.resolve(response)))
  vi.stubGlobal('fetch', fn)
  return fn
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('SystemStatus', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('shows backend and database status from /api/health', async () => {
    const fetch = mockFetch(json({ status: 'UP', database: 'UP' }))

    render(<SystemStatus />)

    expect(screen.getByRole('status').textContent).toBe('Checking backend…')
    expect(await screen.findByText('Backend UP, database UP')).toBeTruthy()
    expect(fetch).toHaveBeenCalledWith('/api/health', expect.anything())
  })

  it('shows a degraded state when the backend reports the database down', async () => {
    mockFetch(json({ status: 'DOWN', database: 'DOWN' }, 503))

    render(<SystemStatus />)

    expect(await screen.findByText('Backend DOWN, database DOWN')).toBeTruthy()
  })

  it('shows an error when the backend cannot be reached', async () => {
    mockFetch(new TypeError('Failed to fetch'))

    render(<SystemStatus />)

    expect(await screen.findByText('Backend unreachable')).toBeTruthy()
  })

  it('shows an error on unexpected HTTP failures', async () => {
    mockFetch(new Response('Bad gateway', { status: 502 }))

    render(<SystemStatus />)

    expect(await screen.findByText('Backend unreachable')).toBeTruthy()
  })
})
