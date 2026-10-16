import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.tsx'
import type { ShareLink } from '../api/galleries.ts'
import { parseRoute, paths, publicGalleryHash, publicGalleryToken } from '../routing.ts'
import { bodiesOf, clearCookie, headersOf, json, mockApi, setCookie } from '../test/mockApi.ts'
import { page, renderWorkspace, session, WS } from '../test/workspace.tsx'
import { startDownload } from '../ui/download.ts'

vi.mock('../ui/download.ts', () => ({ startDownload: vi.fn() }))

const GALLERY = `${WS}/galleries/g-1`
const TOKEN = 'tok_abcdefghijklmnopqrstuvwxyz0123456789ABCDE'

function photo(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    sessionId: 's-1',
    status: 'READY',
    originalFilename: `${id}.jpg`,
    contentType: 'image/jpeg',
    byteSize: 1234,
    width: 800,
    height: 600,
    checksumSha256: 'ab'.repeat(32),
    failureReason: null,
    thumbnailUrl: `http://storage.test/thumb-${id}.jpg`,
    previewUrl: `http://storage.test/preview-${id}.jpg`,
    uploadedAt: '2026-10-15T10:00:00Z',
    processedAt: '2026-10-15T10:00:05Z',
    createdAt: '2026-10-15T10:00:00Z',
    ...overrides,
  }
}

function gallery(overrides: Record<string, unknown> = {}) {
  return {
    id: 'g-1',
    sessionId: 's-1',
    title: 'Anna & family',
    status: 'DRAFT',
    downloads: 'PREVIEWS',
    photoCount: 2,
    archivedAt: null,
    version: 3,
    createdAt: '2026-10-15T10:00:00Z',
    updatedAt: '2026-10-15T10:00:00Z',
    ...overrides,
  }
}

function detail(photos = [photo('a-1'), photo('a-2')], overrides: Record<string, unknown> = {}) {
  return { gallery: gallery({ photoCount: photos.length, ...overrides }), photos }
}

function link(overrides: Partial<ShareLink> = {}): ShareLink {
  return {
    id: 'l-1',
    state: 'ACTIVE',
    expiresAt: '2026-11-14T10:00:00Z',
    revokedAt: null,
    createdAt: '2026-10-15T10:00:00Z',
    views: 3,
    downloads: 1,
    lastAccessedAt: '2026-10-16T09:00:00Z',
    ...overrides,
  }
}

function galleryRoutes(overrides: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  return {
    [`GET ${GALLERY}`]: () => json(detail()),
    [`GET ${GALLERY}/links`]: () => page([link()]),
    [`GET ${WS}/sessions/s-1/assets`]: () =>
      page([photo('a-1'), photo('a-2'), photo('a-3'), photo('a-4', { status: 'PROCESSING', thumbnailUrl: null })]),
    ...overrides,
  }
}

describe('Gallery routes', () => {
  it('reads gallery pages and visitor links from the hash', () => {
    expect(parseRoute('#/galleries')).toEqual({ page: 'galleries' })
    expect(parseRoute(paths.gallery('g 1'))).toEqual({ page: 'gallery', id: 'g 1' })
    expect(publicGalleryToken(publicGalleryHash(TOKEN))).toBe(TOKEN)
    expect(publicGalleryToken('#/g/')).toBe('')
    expect(publicGalleryToken('#/galleries')).toBeNull()
    expect(publicGalleryToken('#/sessions')).toBeNull()
  })
})

describe('Gallery management', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('lists galleries with their status and photo count', async () => {
    const fetch = renderWorkspace('#/galleries', {
      [`GET ${WS}/galleries`]: () => page([gallery({ status: 'PUBLISHED' })]),
    })

    const list = await screen.findByRole('list', { name: 'Galleries' })
    expect(within(list).getByRole('link', { name: 'Anna & family' }).getAttribute('href')).toBe('#/galleries/g-1')
    expect(within(list).getByText('Published')).toBeTruthy()
    expect(within(list).getByText(/2 photos/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Galleries' }).getAttribute('aria-current')).toBe('page')

    fireEvent.click(screen.getByRole('checkbox', { name: 'Show archived' }))
    await waitFor(() =>
      expect(fetch.mock.calls.map(([url]) => String(url))).toContain(`${WS}/galleries?archived=true&page=0`),
    )
  })

  it('creates a gallery from a session and opens it', async () => {
    const fetch = renderWorkspace('#/sessions/s-1', {
      [`GET ${WS}/sessions/s-1`]: () => json(session()),
      [`GET ${WS}/sessions/s-1/assets`]: () => page([]),
      [`GET ${WS}/galleries`]: () => page([]),
      [`POST ${WS}/galleries`]: () => json(detail([]), 201),
    })

    expect(await screen.findByText('No galleries for this session yet.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'New gallery' }))
    const form = screen.getByRole('form', { name: 'New gallery' })
    fireEvent.change(within(form).getByLabelText('Title'), { target: { value: 'Proofs' } })
    fireEvent.change(within(form).getByLabelText('Downloads'), { target: { value: 'ORIGINALS' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Create gallery' }))

    await waitFor(() => expect(window.location.hash).toBe('#/galleries/g-1'))
    expect(bodiesOf(fetch, 'POST', `${WS}/galleries`)).toEqual([
      { sessionId: 's-1', gallery: { title: 'Proofs', downloads: 'ORIGINALS' } },
    ])
    expect(fetch.mock.calls.map(([url]) => String(url))).toContain(`${WS}/galleries?sessionId=s-1&page=0`)
  })

  it('reorders, removes, and adds photos with the current version', async () => {
    const fetch = renderWorkspace(
      '#/galleries/g-1',
      galleryRoutes({
        [`PUT ${GALLERY}/photos`]: (init) => {
          const { assetIds } = JSON.parse(String(init?.body)) as { assetIds: string[] }
          return json(detail(assetIds.map((id) => photo(id)), { version: 4 }))
        },
      }),
    )

    const inGallery = await screen.findByRole('list', { name: 'Gallery photos' })
    expect(within(inGallery).getAllByRole('img').map((img) => img.getAttribute('alt'))).toEqual(['a-1.jpg', 'a-2.jpg'])
    const picker = await screen.findByRole('list', { name: 'Session photos to add' })
    expect(within(picker).getAllByRole('img').map((img) => img.getAttribute('alt'))).toEqual(['a-3.jpg'])

    fireEvent.click(within(inGallery).getByRole('button', { name: 'Move a-1.jpg later' }))
    await waitFor(() =>
      expect(within(screen.getByRole('list', { name: 'Gallery photos' })).getAllByRole('img')[0].getAttribute('alt')).toBe(
        'a-2.jpg',
      ),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Add a-3.jpg' }))
    await screen.findByText('Every ready photo on this page of the session is in the gallery.')
    fireEvent.click(screen.getByRole('button', { name: 'Remove a-1.jpg' }))
    await screen.findByText('Photos in this gallery (2)')

    expect(bodiesOf(fetch, 'PUT', `${GALLERY}/photos`)).toEqual([
      { version: 3, assetIds: ['a-2', 'a-1'] },
      { version: 4, assetIds: ['a-2', 'a-1', 'a-3'] },
      { version: 4, assetIds: ['a-2', 'a-3'] },
    ])
  })

  it('publishes the gallery and shows the server explanation when it cannot', async () => {
    let attempts = 0
    renderWorkspace(
      '#/galleries/g-1',
      galleryRoutes({
        [`POST ${GALLERY}/status`]: () =>
          ++attempts === 1
            ? json({ status: 409, detail: 'Add photos before publishing the gallery.' }, 409)
            : json(detail(undefined, { status: 'PUBLISHED', version: 4 })),
      }),
    )

    expect(await screen.findByText(/links do not open this gallery until you publish it/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect((await screen.findByRole('alert')).textContent).toBe('Add photos before publishing the gallery.')
    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect(await screen.findByRole('button', { name: 'Unpublish' })).toBeTruthy()
    expect(screen.getByText('Anyone with an active link can view this gallery.')).toBeTruthy()
  })

  it('saves settings with the current version', async () => {
    const fetch = renderWorkspace(
      '#/galleries/g-1',
      galleryRoutes({ [`PUT ${GALLERY}`]: () => json(detail(undefined, { downloads: 'ORIGINALS', version: 4 })) }),
    )

    const form = await screen.findByRole('form', { name: 'Gallery settings' })
    const save = within(form).getByRole('button', { name: 'Save settings' }) as HTMLButtonElement
    expect(save.disabled).toBe(true)
    fireEvent.change(within(form).getByLabelText('Downloads'), { target: { value: 'ORIGINALS' } })
    fireEvent.click(save)

    await waitFor(() =>
      expect(bodiesOf(fetch, 'PUT', GALLERY)).toEqual([
        { version: 3, gallery: { title: 'Anna & family', downloads: 'ORIGINALS' } },
      ]),
    )
  })

  it('shows a new link once, lists link usage, and revokes links', async () => {
    let links = [link(), link({ id: 'l-2', state: 'REVOKED', revokedAt: '2026-10-16T08:00:00Z', views: 1, downloads: 0 })]
    const fetch = renderWorkspace(
      '#/galleries/g-1',
      galleryRoutes({
        [`GET ${GALLERY}/links`]: () => page(links),
        [`POST ${GALLERY}/links`]: () => json({ link: link({ id: 'l-3' }), token: TOKEN }, 201),
        [`POST ${GALLERY}/links/l-1/revoke`]: () => {
          links = links.map((l) => (l.id === 'l-1' ? { ...l, state: 'REVOKED', revokedAt: '2026-10-16T10:00:00Z' } : l))
          return json(links[0])
        },
      }),
    )

    const list = await screen.findByRole('list', { name: 'Share links' })
    expect(within(list).getByText('Active')).toBeTruthy()
    expect(within(list).getByText(/3 views · 1 download/)).toBeTruthy()
    expect(within(list).getAllByRole('button', { name: 'Revoke' })).toHaveLength(1)

    fireEvent.change(screen.getByLabelText('Valid for'), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create link' }))
    const field = (await screen.findByRole('textbox', { name: 'New share link' })) as HTMLInputElement
    expect(field.value).toBe(`${window.location.origin}/#/g/${TOKEN}`)
    expect(bodiesOf(fetch, 'POST', `${GALLERY}/links`)).toEqual([{ validDays: 7 }])
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByRole('textbox', { name: 'New share link' })).toBeNull()

    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }))
    await waitFor(() =>
      expect(within(screen.getByRole('list', { name: 'Share links' })).getAllByText('Revoked')).toHaveLength(2),
    )
    expect(screen.queryByRole('button', { name: 'Revoke' })).toBeNull()
  })

  it('offers no editing for an archived gallery', async () => {
    renderWorkspace(
      '#/galleries/g-1',
      galleryRoutes({ [`GET ${GALLERY}`]: () => json(detail(undefined, { archivedAt: '2026-10-16T10:00:00Z' })) }),
    )

    expect(await screen.findByRole('button', { name: 'Restore' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull()
    expect(screen.queryByRole('form', { name: 'Gallery settings' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Remove/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Create link' })).toBeNull()
  })
})

describe('Public gallery', () => {
  const visitorGallery = (downloads = 'PREVIEWS') =>
    json({
      title: 'Anna & family',
      studioName: 'Alice Studio',
      downloads,
      linkExpiresAt: '2026-11-14T10:00:00Z',
      photos: [
        { id: 'a-1', width: 800, height: 600, thumbnailUrl: 'http://storage.test/t1', previewUrl: 'http://storage.test/p1' },
        { id: 'a-2', width: 800, height: 600, thumbnailUrl: 'http://storage.test/t2', previewUrl: 'http://storage.test/p2' },
      ],
    })

  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    vi.mocked(startDownload).mockReset()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('opens the gallery with the token in a header and without signing in', async () => {
    window.location.hash = publicGalleryHash(TOKEN)
    const fetch = mockApi({
      'GET /api/public/gallery': () => visitorGallery(),
      'POST /api/public/gallery/photos/a-2/download': () =>
        json({ url: 'http://storage.test/download?sig', expiresAt: '2026-10-16T10:05:00Z' }),
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Anna & family' })).toBeTruthy()
    expect(screen.getByText(/By Alice Studio/)).toBeTruthy()
    expect(headersOf(fetch, 'GET', '/api/public/gallery')['X-Gallery-Token']).toBe(TOKEN)
    const urls = fetch.mock.calls.map(([url]) => String(url))
    expect(urls.some((url) => url.includes(TOKEN))).toBe(false)
    expect(urls).not.toContain('/api/me')
    const image = screen.getByRole('img', { name: 'Photo 1' })
    expect(image.getAttribute('src')).toBe('http://storage.test/t1')
    expect(image.closest('a')?.getAttribute('href')).toBe('http://storage.test/p1')

    fireEvent.click(screen.getByRole('button', { name: 'Download: photo 2' }))
    await waitFor(() => expect(startDownload).toHaveBeenCalledWith('http://storage.test/download?sig'))
    expect(headersOf(fetch, 'POST', '/api/public/gallery/photos/a-2/download')['X-Gallery-Token']).toBe(TOKEN)
  })

  it('offers no downloads when the gallery does not allow them', async () => {
    window.location.hash = publicGalleryHash(TOKEN)
    mockApi({ 'GET /api/public/gallery': () => visitorGallery('NONE') })

    render(<App />)

    expect(await screen.findByRole('img', { name: 'Photo 2' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Download/ })).toBeNull()
  })

  it('explains a link that does not work', async () => {
    window.location.hash = publicGalleryHash('expired')
    mockApi({ 'GET /api/public/gallery': () => json({ status: 404, title: 'Not Found' }, 404) })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'This gallery link does not work' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: /Sign in/ })).toBeNull()
  })
})
