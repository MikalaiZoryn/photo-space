import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PhotoAsset } from '../api/assets.ts'
import type { BatchDetail, ContentBatch, ContentDraft } from '../api/content.ts'
import { parseHashtags, postText } from '../api/content.ts'
import { parseRoute, paths } from '../routing.ts'
import { bodiesOf, clearCookie, json, setCookie } from '../test/mockApi.ts'
import { page, renderWorkspace, session, WS } from '../test/workspace.tsx'
import { BATCH_POLL_MS } from './BatchPage.tsx'

const CONTENT = `${WS}/content`
const BATCH = `${CONTENT}/batches/b-1`

function photo(id: string, overrides: Partial<PhotoAsset> = {}): PhotoAsset {
  return {
    id,
    sessionId: 's-1',
    status: 'READY',
    originalFilename: `${id}.jpg`,
    contentType: 'image/jpeg',
    byteSize: 1234,
    width: 800,
    height: 600,
    checksumSha256: null,
    failureReason: null,
    thumbnailUrl: `http://storage.test/thumb-${id}.jpg`,
    previewUrl: `http://storage.test/preview-${id}.jpg`,
    uploadedAt: '2026-10-15T10:00:00Z',
    processedAt: '2026-10-15T10:00:05Z',
    createdAt: '2026-10-15T10:00:00Z',
    ...overrides,
  }
}

function batch(overrides: Partial<ContentBatch> = {}): ContentBatch {
  return {
    id: 'b-1',
    sessionId: 's-1',
    sessionTitle: 'Family session',
    status: 'SUCCEEDED',
    sessionType: 'Family',
    notes: 'Picnic by the lake.',
    captionCount: 2,
    includeHashtags: true,
    includeCallToAction: true,
    run: 1,
    failureReason: null,
    provider: 'mock',
    model: 'mock-captions-1',
    promptVersion: 'instagram-drafts-v1',
    carouselRationale: 'The wide shot first.',
    storyIdeas: ['Behind the scenes'],
    warnings: ['No location given.'],
    photoCount: 2,
    draftCount: 2,
    approvedCount: 0,
    createdAt: '2026-10-16T10:00:00Z',
    completedAt: '2026-10-16T10:00:10Z',
    ...overrides,
  }
}

function draft(overrides: Partial<ContentDraft> = {}): ContentDraft {
  return {
    id: 'd-1',
    batchId: 'b-1',
    position: 0,
    status: 'DRAFT',
    version: 1,
    caption: 'Golden hour by the lake.',
    callToAction: 'Book your session.',
    hashtags: ['family', 'goldenhour'],
    source: 'GENERATED',
    approvedVersion: null,
    approvedAt: null,
    updatedAt: '2026-10-16T10:00:10Z',
    checks: [],
    ...overrides,
  }
}

function detail(overrides: Partial<ContentBatch> = {}, drafts = [draft()]): BatchDetail {
  return { batch: batch(overrides), photos: [photo('a-1'), photo('a-2')], carousel: ['a-2', 'a-1'], drafts }
}

const settings = { provider: 'mock', model: 'mock-captions-1', maxPhotos: 2, maxCaptions: 5, promptVersion: 'v1' }

describe('Content helpers', () => {
  it('reads content routes from the hash', () => {
    expect(parseRoute('#/content')).toEqual({ page: 'content' })
    expect(parseRoute(paths.contentBatch('b 1'))).toEqual({ page: 'contentBatch', id: 'b 1' })
    expect(parseRoute('#/content/batches')).toEqual({ page: 'content' })
  })

  it('builds the post text and reads typed hashtags', () => {
    expect(postText(draft())).toBe('Golden hour by the lake.\n\nBook your session.\n\n#family #goldenhour')
    expect(postText(draft({ callToAction: null, hashtags: [] }))).toBe('Golden hour by the lake.')
    expect(parseHashtags(' #family, goldenhour  ##kids_2026 ')).toEqual(['family', 'goldenhour', 'kids_2026'])
  })
})

describe('Content studio', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('requests drafts for photos picked on a session, with the notes to send', async () => {
    const fetch = renderWorkspace('#/sessions/s-1', {
      [`GET ${WS}/sessions/s-1`]: () => json(session({ notes: 'Picnic by the lake.' })),
      [`GET ${WS}/sessions/s-1/assets`]: () =>
        page([photo('a-1'), photo('a-2'), photo('a-3'), photo('a-4', { status: 'PROCESSING', thumbnailUrl: null })]),
      [`GET ${WS}/galleries`]: () => page([]),
      [`GET ${CONTENT}/batches`]: () => page([]),
      [`GET ${CONTENT}/settings`]: () => json(settings),
      [`POST ${CONTENT}/batches`]: () => json(detail({ status: 'PENDING' }, []), 201),
    })

    expect(await screen.findByText('No drafts for this session yet.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Create drafts' }))
    const form = await screen.findByRole('form', { name: 'New drafts' })
    expect(within(form).getByText(/nothing is sent to an AI service/)).toBeTruthy()
    const picker = within(form).getByRole('list', { name: 'Photos to write about' })
    expect(within(picker).getAllByRole('button')).toHaveLength(3)
    const write = within(form).getByRole('button', { name: 'Write drafts' }) as HTMLButtonElement
    expect(write.disabled).toBe(true)

    fireEvent.click(within(picker).getByRole('button', { name: 'a-2.jpg' }))
    fireEvent.click(within(picker).getByRole('button', { name: 'a-1.jpg' }))
    expect(within(picker).getByRole('button', { name: 'a-2.jpg, selected 1' }).getAttribute('aria-pressed')).toBe('true')
    expect((within(picker).getByRole('button', { name: 'a-3.jpg' }) as HTMLButtonElement).disabled).toBe(true)
    const notes = within(form).getByLabelText('Notes for the AI') as HTMLTextAreaElement
    expect(notes.value).toBe('Picnic by the lake.')
    fireEvent.change(notes, { target: { value: 'Picnic by the lake. Kites!' } })
    fireEvent.change(within(form).getByLabelText('Caption options'), { target: { value: '2' } })
    fireEvent.click(within(form).getByRole('checkbox', { name: 'Call to action' }))
    fireEvent.click(write)

    await waitFor(() => expect(window.location.hash).toBe('#/content/batches/b-1'))
    expect(bodiesOf(fetch, 'POST', `${CONTENT}/batches`)).toEqual([
      {
        sessionId: 's-1',
        assetIds: ['a-2', 'a-1'],
        notes: 'Picnic by the lake. Kites!',
        captionCount: 2,
        includeHashtags: true,
        includeCallToAction: false,
      },
    ])
  })

  it('says what is sent when a real AI provider is configured', async () => {
    renderWorkspace('#/sessions/s-1', {
      [`GET ${WS}/sessions/s-1`]: () => json(session()),
      [`GET ${WS}/sessions/s-1/assets`]: () => page([photo('a-1')]),
      [`GET ${WS}/galleries`]: () => page([]),
      [`GET ${CONTENT}/batches`]: () => page([]),
      [`GET ${CONTENT}/settings`]: () => json({ ...settings, provider: 'anthropic', model: 'claude-opus-5-5' }),
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Create drafts' }))
    const form = await screen.findByRole('form', { name: 'New drafts' })
    expect(within(form).getByText(/Sent to the AI provider \(claude-opus-5-5\)/).textContent).toContain(
      'Client names and contact details are not sent',
    )
  })

  it('waits for a pending batch, then shows its drafts and suggestions', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let calls = 0
    renderWorkspace('#/content/batches/b-1', {
      [`GET ${BATCH}`]: () => {
        calls++
        if (calls === 1) return json(detail({ status: 'PENDING', completedAt: null }, []))
        if (calls === 2) return json(detail({ status: 'PENDING', failureReason: 'The AI provider is busy right now.' }, []))
        return json(detail())
      },
    })

    expect(await screen.findByText(/Writing drafts/)).toBeTruthy()
    expect(screen.getByRole('list', { name: 'Selected photos' })).toBeTruthy()
    await act(() => vi.advanceTimersByTimeAsync(BATCH_POLL_MS))
    expect(await screen.findByText(/Retrying automatically after a problem: The AI provider is busy/)).toBeTruthy()
    await act(() => vi.advanceTimersByTimeAsync(BATCH_POLL_MS))

    const form = await screen.findByRole('form', { name: 'Draft 1' })
    expect((within(form).getByLabelText('Caption') as HTMLTextAreaElement).value).toBe('Golden hour by the lake.')
    expect((within(form).getByLabelText('Hashtags') as HTMLInputElement).value).toBe('#family #goldenhour')
    expect(within(screen.getByRole('list', { name: 'Warnings' })).getByText('No location given.')).toBeTruthy()
    const carousel = screen.getByRole('list', { name: 'Carousel order' })
    expect(within(carousel).getAllByRole('img').map((img) => img.getAttribute('alt'))).toEqual(['a-2.jpg', 'a-1.jpg'])
    expect(screen.getByText('Behind the scenes')).toBeTruthy()
    expect(screen.getByText(/Nothing is posted automatically/)).toBeTruthy()
    expect(screen.queryByText(/Writing drafts/)).toBeNull()
    await act(() => vi.advanceTimersByTimeAsync(BATCH_POLL_MS * 3))
    expect(calls).toBe(3)
  })

  it('reports a failed batch and retries it', async () => {
    const fetch = renderWorkspace('#/content/batches/b-1', {
      [`GET ${BATCH}`]: () =>
        json(detail({ status: 'FAILED', failureReason: 'The AI provider rejected the request.', provider: null }, [])),
      [`POST ${BATCH}/retry`]: () => json(detail({ status: 'PENDING', run: 2 }, [])),
    })

    const alert = await screen.findByText(/Generation failed\. The AI provider rejected the request\./)
    expect(screen.queryByRole('button', { name: 'Regenerate' })).toBeTruthy()
    fireEvent.click(within(alert.closest('[role="alert"]') as HTMLElement).getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText(/Writing drafts/)).toBeTruthy()
    expect(fetch.mock.calls.filter(([url, init]) => url === `${BATCH}/retry` && init?.method === 'POST')).toHaveLength(1)
  })

  it('saves an edit as a new version, then approves that version and copies it', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    const fetch = renderWorkspace('#/content/batches/b-1', {
      [`GET ${BATCH}`]: () => json(detail()),
      [`PUT ${CONTENT}/drafts/d-1`]: () =>
        json(
          draft({
            version: 2,
            source: 'EDITED',
            caption: 'Kites over the lake.',
            callToAction: null,
            hashtags: ['lake', 'kids'],
            checks: ['Mentions the account “@anna”, which is not in your notes or brand profile. Check it before approving.'],
          }),
        ),
      [`POST ${CONTENT}/drafts/d-1/status`]: () =>
        json(
          draft({
            version: 2,
            source: 'EDITED',
            caption: 'Kites over the lake.',
            callToAction: null,
            hashtags: ['lake', 'kids'],
            status: 'APPROVED',
            approvedVersion: 2,
            approvedAt: '2026-10-16T11:00:00Z',
          }),
        ),
    })

    let form = await screen.findByRole('form', { name: 'Draft 1' })
    expect(within(form).queryByRole('button', { name: 'Copy for Instagram' })).toBeNull()
    fireEvent.change(within(form).getByLabelText('Caption'), { target: { value: 'Kites over the lake.' } })
    fireEvent.change(within(form).getByLabelText('Call to action'), { target: { value: '' } })
    fireEvent.change(within(form).getByLabelText('Hashtags'), { target: { value: '#lake, kids' } })
    expect((within(form).getByRole('button', { name: 'Approve' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(within(form).getByRole('button', { name: 'Save changes' }))

    await screen.findByText(/Version 2 · edited/)
    form = screen.getByRole('form', { name: 'Draft 1' })
    expect(within(within(form).getByRole('list', { name: 'Review hints' })).getByText(/@anna/)).toBeTruthy()
    fireEvent.click(within(form).getByRole('button', { name: 'Approve' }))

    const copy = await screen.findByRole('button', { name: 'Copy for Instagram' })
    expect(screen.getByText(/Version 2 approved on/)).toBeTruthy()
    fireEvent.click(copy)
    await screen.findByRole('button', { name: 'Copied' })
    expect(writeText).toHaveBeenCalledWith('Kites over the lake.\n\n#lake #kids')
    expect(bodiesOf(fetch, 'PUT', `${CONTENT}/drafts/d-1`)).toEqual([
      { version: 1, draft: { caption: 'Kites over the lake.', callToAction: null, hashtags: ['lake', 'kids'] } },
    ])
    expect(bodiesOf(fetch, 'POST', `${CONTENT}/drafts/d-1/status`)).toEqual([{ version: 2, status: 'APPROVED' }])
  })

  it('shows a stale edit conflict and the version history', async () => {
    renderWorkspace('#/content/batches/b-1', {
      [`GET ${BATCH}`]: () => json(detail({}, [draft({ version: 2, source: 'EDITED', caption: 'Mine' })])),
      [`PUT ${CONTENT}/drafts/d-1`]: () =>
        json({ status: 409, detail: 'This draft was changed by someone else. Reload it and try again.' }, 409),
      [`GET ${CONTENT}/drafts/d-1`]: () =>
        json({
          draft: draft({ version: 2 }),
          versions: [
            { version: 2, caption: 'Mine', callToAction: null, hashtags: [], source: 'EDITED', createdAt: '2026-10-16T11:00:00Z' },
            { version: 1, caption: 'Original', callToAction: null, hashtags: ['family'], source: 'GENERATED', createdAt: '2026-10-16T10:00:00Z' },
          ],
        }),
    })

    const form = await screen.findByRole('form', { name: 'Draft 1' })
    fireEvent.change(within(form).getByLabelText('Caption'), { target: { value: 'Mine, again' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save changes' }))
    expect(await within(form).findByText('This draft was changed by someone else. Reload it and try again.')).toBeTruthy()

    fireEvent.click(within(form).getByRole('button', { name: 'History' }))
    const versions = await within(form).findByRole('list', { name: 'Versions' })
    expect(within(versions).getByText('Original #family')).toBeTruthy()
    fireEvent.click(within(versions).getByRole('button', { name: 'Use this text' }))
    expect((within(form).getByLabelText('Caption') as HTMLTextAreaElement).value).toBe('Original')
  })

  it('lists batches and links to the brand profile in Content Studio', async () => {
    renderWorkspace('#/content', {
      [`GET ${CONTENT}/batches`]: () => page([batch({ approvedCount: 1 })]),
    })

    const list = await screen.findByRole('list', { name: 'Content batches' })
    expect(within(list).getByRole('link', { name: 'Family session' }).getAttribute('href')).toBe('#/content/batches/b-1')
    expect(within(list).getByText(/2 drafts, 1 approved/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Content' }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('link', { name: 'Content Studio' }).getAttribute('href')).toBe('#/settings/content-studio')
    expect(screen.queryByRole('form', { name: 'Brand profile' })).toBeNull()
  })

  it('saves the brand profile with its version in Content Studio', async () => {
    const fetch = renderWorkspace('#/settings/content-studio', {
      [`GET ${CONTENT}/brand-profile`]: () =>
        json({ voice: 'Warm', audience: null, preferences: null, avoid: null, version: 1, updatedAt: '2026-10-16T09:00:00Z' }),
      [`PUT ${CONTENT}/brand-profile`]: () =>
        json({ voice: 'Warm', audience: null, preferences: null, avoid: 'Clichés', version: 2, updatedAt: '2026-10-16T10:00:00Z' }),
    })

    expect(await screen.findByRole('heading', { name: 'Content Studio' })).toBeTruthy()
    const nav = screen.getByRole('navigation', { name: 'Workspace sections' })
    expect(within(nav).getByRole('link', { name: 'Settings' }).getAttribute('aria-current')).toBe('page')

    const form = await screen.findByRole('form', { name: 'Brand profile' })
    expect((within(form).getByLabelText('Voice') as HTMLTextAreaElement).value).toBe('Warm')
    fireEvent.change(within(form).getByLabelText('Avoid'), { target: { value: 'Clichés' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save profile' }))

    await waitFor(() =>
      expect(bodiesOf(fetch, 'PUT', `${CONTENT}/brand-profile`)).toEqual([
        { version: 1, profile: { voice: 'Warm', audience: null, preferences: null, avoid: 'Clichés' } },
      ]),
    )
    expect(await within(screen.getByRole('form', { name: 'Brand profile' })).findByText('Saved')).toBeTruthy()
  })

  it('shows not found for a batch of another workspace', async () => {
    renderWorkspace('#/content/batches/b-9', {})
    expect(await screen.findByText(/was not found/)).toBeTruthy()
  })
})
