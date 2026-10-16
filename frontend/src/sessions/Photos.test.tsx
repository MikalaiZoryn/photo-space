import { act, cleanup, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { bodiesOf, clearCookie, json, setCookie } from '../test/mockApi.ts'
import { page, renderWorkspace, session, WS } from '../test/workspace.tsx'
import { POLL_MS } from './SessionPhotos.tsx'

const ASSETS = `${WS}/sessions/s-1/assets`
const STORAGE_URL = 'http://storage.test/photo-space/workspaces/ws-1/originals/a-9?X-Amz-Signature=secret'

function asset(overrides: Record<string, unknown> = {}) {
  return {
    id: 'a-1',
    sessionId: 's-1',
    status: 'READY',
    originalFilename: 'IMG_0001.jpg',
    contentType: 'image/jpeg',
    byteSize: 1234,
    width: 800,
    height: 1200,
    checksumSha256: 'ab'.repeat(32),
    failureReason: null,
    thumbnailUrl: 'http://storage.test/thumb-1.jpg',
    previewUrl: 'http://storage.test/preview-1.jpg',
    uploadedAt: '2026-10-15T10:00:00Z',
    processedAt: '2026-10-15T10:00:05Z',
    createdAt: '2026-10-15T10:00:00Z',
    ...overrides,
  }
}

function jpeg(name = 'new.jpg', size = 3) {
  return new File([new Uint8Array(size)], name, { type: 'image/jpeg' })
}

function sessionRoutes(assets: () => Response) {
  return {
    [`GET ${WS}/sessions/s-1`]: () => json(session()),
    [`GET ${ASSETS}`]: assets,
  }
}

describe('Session photos', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('shows thumbnails of ready photos and the status of the others', async () => {
    renderWorkspace(
      '#/sessions/s-1',
      sessionRoutes(() =>
        page([
          asset(),
          asset({ id: 'a-2', status: 'PROCESSING', originalFilename: 'IMG_0002.jpg', thumbnailUrl: null, previewUrl: null }),
          asset({
            id: 'a-3',
            status: 'REJECTED',
            originalFilename: 'notes.jpg',
            failureReason: 'This file is not a JPEG or PNG image.',
            thumbnailUrl: null,
            previewUrl: null,
          }),
        ]),
      ),
    )

    const grid = await screen.findByRole('list', { name: 'Session photos' })
    const image = within(grid).getByRole('img', { name: 'IMG_0001.jpg' })
    expect(image.getAttribute('src')).toBe('http://storage.test/thumb-1.jpg')
    expect(image.closest('a')?.getAttribute('href')).toBe('http://storage.test/preview-1.jpg')
    expect(image.closest('a')?.getAttribute('rel')).toBe('noreferrer')
    expect(within(grid).getByText('Processing…')).toBeTruthy()
    expect(within(grid).getByText('Rejected')).toBeTruthy()
    expect(within(grid).getByText('This file is not a JPEG or PNG image.')).toBeTruthy()
  })

  it('shows an empty state with the accepted formats', async () => {
    renderWorkspace('#/sessions/s-1', sessionRoutes(() => page([])))

    expect(await screen.findByText(/No photos yet. JPEG or PNG, up to 100 MB each./)).toBeTruthy()
  })

  it('polls while photos are processing and stops once they are done', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let calls = 0
    const fetch = renderWorkspace(
      '#/sessions/s-1',
      sessionRoutes(() => {
        calls++
        return page([calls < 2 ? asset({ status: 'PROCESSING', thumbnailUrl: null }) : asset()])
      }),
    )

    expect(await screen.findByText('Processing…')).toBeTruthy()
    await act(() => vi.advanceTimersByTimeAsync(POLL_MS))
    expect(await screen.findByRole('img', { name: 'IMG_0001.jpg' })).toBeTruthy()

    await act(() => vi.advanceTimersByTimeAsync(POLL_MS * 3))
    const listCalls = fetch.mock.calls.filter(([input]) => String(input).startsWith(ASSETS))
    expect(listCalls).toHaveLength(2)
  })

  it('uploads a photo straight to storage, then reports it complete', async () => {
    let uploaded = false
    const fetch = renderWorkspace('#/sessions/s-1', {
      ...sessionRoutes(() => page(uploaded ? [asset({ id: 'a-9', status: 'PROCESSING', thumbnailUrl: null })] : [])),
      [`POST ${ASSETS}`]: () =>
        json(
          {
            asset: asset({ id: 'a-9', status: 'PENDING_UPLOAD', thumbnailUrl: null }),
            upload: { url: STORAGE_URL, method: 'PUT', headers: { 'content-type': 'image/jpeg' }, expiresAt: 'x' },
          },
          201,
        ),
      'PUT /photo-space/workspaces/ws-1/originals/a-9': () => {
        uploaded = true
        return new Response(null, { status: 200 })
      },
      [`POST ${WS}/assets/a-9/complete`]: () => json(asset({ id: 'a-9', status: 'PROCESSING' })),
    })

    const input = await screen.findByLabelText('Upload photos')
    fireEvent.change(input, { target: { files: [jpeg('Family 1.jpg', 3)] } })

    expect(await screen.findByText('Processing…')).toBeTruthy()
    expect(bodiesOf(fetch, 'POST', ASSETS)).toEqual([{ filename: 'Family 1.jpg', contentType: 'image/jpeg', byteSize: 3 }])
    const put = fetch.mock.calls.find(([url]) => url === STORAGE_URL)!
    expect(put[1]?.method).toBe('PUT')
    expect(put[1]?.headers).toEqual({ 'content-type': 'image/jpeg' })
    expect(put[1]?.body).toBeInstanceOf(File)
    const complete = fetch.mock.calls.find(([url]) => url === `${WS}/assets/a-9/complete`)!
    const completeHeaders = complete[1]!.headers as Record<string, string>
    expect(completeHeaders['X-XSRF-TOKEN']).toBe('csrf-token')
    expect(screen.queryByRole('list', { name: 'Uploads' })).toBeNull()
  })

  it('refuses unsupported or oversized files without calling the server', async () => {
    const fetch = renderWorkspace('#/sessions/s-1', sessionRoutes(() => page([])))

    const input = await screen.findByLabelText('Upload photos')
    const gif = new File([new Uint8Array(3)], 'anim.gif', { type: 'image/gif' })
    const huge = jpeg('huge.jpg')
    Object.defineProperty(huge, 'size', { value: 100 * 1024 * 1024 + 1 })
    fireEvent.change(input, { target: { files: [gif, huge] } })

    const uploads = await screen.findByRole('list', { name: 'Uploads' })
    expect(await within(uploads).findByText('Only JPEG and PNG photos can be uploaded.')).toBeTruthy()
    expect(await within(uploads).findByText('The file is larger than 100 MB.')).toBeTruthy()
    expect(bodiesOf(fetch, 'POST', ASSETS)).toEqual([])

    fireEvent.click(within(uploads).getAllByRole('button', { name: 'Dismiss' })[0])
    expect(within(uploads).queryByText('Only JPEG and PNG photos can be uploaded.')).toBeNull()
  })

  it("shows the server's reason when an upload is refused", async () => {
    renderWorkspace('#/sessions/s-1', {
      ...sessionRoutes(() => page([])),
      [`POST ${ASSETS}`]: () =>
        json({ status: 409, detail: "This upload would exceed the workspace's storage limit of 102400 MB." }, 409),
    })

    fireEvent.change(await screen.findByLabelText('Upload photos'), { target: { files: [jpeg()] } })

    expect(await screen.findByText(/would exceed the workspace's storage limit/)).toBeTruthy()
  })

  it('reports a failed upload to storage', async () => {
    renderWorkspace('#/sessions/s-1', {
      ...sessionRoutes(() => page([])),
      [`POST ${ASSETS}`]: () =>
        json(
          {
            asset: asset({ id: 'a-9', status: 'PENDING_UPLOAD' }),
            upload: { url: STORAGE_URL, method: 'PUT', headers: {}, expiresAt: 'x' },
          },
          201,
        ),
      'PUT /photo-space/workspaces/ws-1/originals/a-9': () => new Response(null, { status: 403 }),
    })

    fireEvent.change(await screen.findByLabelText('Upload photos'), { target: { files: [jpeg()] } })

    expect(await screen.findByText('The upload to storage failed. Try again.')).toBeTruthy()
  })

  it('offers no upload on an archived session', async () => {
    renderWorkspace('#/sessions/s-1', {
      [`GET ${WS}/sessions/s-1`]: () =>
        json(session({ archivedAt: '2026-10-15T12:00:00Z', allowedStatuses: [] })),
      [`GET ${ASSETS}`]: () => page([asset()]),
    })

    expect(await screen.findByText('Restore the session to upload photos.')).toBeTruthy()
    expect(screen.queryByLabelText('Upload photos')).toBeNull()
    expect(await screen.findByRole('img', { name: 'IMG_0001.jpg' })).toBeTruthy()
  })
})
