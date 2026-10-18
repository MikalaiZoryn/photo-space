import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PhotoAsset } from '../api/assets.ts'
import type { ContentDraft, Plan, Post, PostDetail, StudioSettings } from '../api/content.ts'
import { bodiesOf, clearCookie, json, setCookie } from '../test/mockApi.ts'
import { page, renderWorkspace, session, WS } from '../test/workspace.tsx'
import { addDays, addMonths, cadenceByWeek, monthRange, todayIn, weekStart } from './calendar.ts'
import { centredCrop, moveCrop, zoomCrop } from './crop.ts'
import { PLAN_POLL_MS } from './usePolling.ts'

const CONTENT = `${WS}/content`

function photo(id: string, overrides: Partial<PhotoAsset> = {}): PhotoAsset {
  return {
    id,
    sessionId: 's-1',
    status: 'READY',
    originalFilename: `${id}.jpg`,
    contentType: 'image/jpeg',
    byteSize: 1234,
    width: 1500,
    height: 1000,
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

function post(overrides: Partial<Post> = {}): Post {
  return {
    id: 'p-1',
    planId: 'pl-1',
    sessionId: 's-1',
    sessionTitle: 'Family session',
    draftId: 'd-1',
    stage: 'SNEAK_PEEK',
    format: 'SINGLE',
    status: 'PROPOSED',
    idea: null,
    offsetDays: 1,
    plannedOn: '2026-10-20',
    postedOn: null,
    postUrl: null,
    regenerating: false,
    failureReason: null,
    version: 0,
    draftStatus: 'DRAFT',
    draftVersion: 1,
    caption: 'A first peek at this family session.',
    callToAction: 'Send this to a friend.',
    hashtags: ['family'],
    photoCount: 1,
    coverThumbnailUrl: 'http://storage.test/thumb-a-1.jpg',
    updatedAt: '2026-10-17T10:00:00Z',
    ...overrides,
  }
}

function plan(overrides: Partial<Plan> = {}): Plan {
  return {
    id: 'pl-1',
    sessionId: 's-1',
    sessionTitle: 'Family session',
    origin: 'AUTOMATIC',
    batchId: 'b-1',
    status: 'SUCCEEDED',
    failureReason: null,
    warnings: [],
    provider: 'mock',
    model: 'mock-captions-1',
    publishedOn: null,
    postCount: 2,
    proposedCount: 2,
    approvedCount: 0,
    postedCount: 0,
    createdAt: '2026-10-17T10:00:00Z',
    updatedAt: '2026-10-17T10:00:00Z',
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
    caption: 'A first peek at this family session.',
    callToAction: 'Send this to a friend.',
    hashtags: ['family'],
    source: 'GENERATED',
    approvedVersion: null,
    approvedAt: null,
    updatedAt: '2026-10-17T10:00:00Z',
    checks: [],
    rules: [{ code: 'LOCATION', severity: 'WARNING', message: 'Mention Kraków so people nearby find the post.' }],
    ...overrides,
  }
}

function settings(overrides: Partial<StudioSettings> = {}): StudioSettings {
  return {
    weeklyGoal: 4,
    autoPlans: true,
    sneakPeekDays: 1,
    dripPosts: 6,
    dripSpacingDays: 7,
    studioArea: null,
    version: 0,
    updatedAt: null,
    ...overrides,
  }
}

function postDetail(overrides: Partial<Post> = {}, photos = [photo('a-1')]): PostDetail {
  return {
    post: post(overrides),
    photos: photos.map((p) => ({ photo: p, crop: centredCrop(p.width ?? 4, p.height ?? 5), customCrop: false })),
    draft: draft(),
  }
}

describe('Calendar and crop helpers', () => {
  it('counts days and weeks in UTC, from Monday', () => {
    expect(todayIn('Pacific/Auckland', new Date('2026-10-17T12:00:00Z'))).toBe('2026-10-18')
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01')
    expect(weekStart('2026-10-18')).toBe('2026-10-12')
    expect(weekStart('2026-10-12')).toBe('2026-10-12')
    expect(monthRange('2026-10-17')).toEqual({ from: '2026-09-28', to: '2026-11-01' })
    expect(addMonths('2026-01-31', 1)).toBe('2026-02-01')
  })

  it('counts approved and posted feed posts toward the goal, Stories and proposals apart', () => {
    const posts = [
      post({ id: '1', status: 'POSTED', postedOn: '2026-10-13' }),
      post({ id: '2', status: 'SCHEDULED', plannedOn: '2026-10-14' }),
      post({ id: '3', status: 'PROPOSED', plannedOn: '2026-10-15' }),
      post({ id: '4', status: 'SCHEDULED', format: 'STORY', plannedOn: '2026-10-15' }),
      post({ id: '5', status: 'SCHEDULED', plannedOn: '2026-10-20' }),
    ]
    expect(cadenceByWeek(posts, ['2026-10-12', '2026-10-19'])).toEqual([
      { start: '2026-10-12', posted: 1, ready: 2, proposed: 1 },
      { start: '2026-10-19', posted: 0, ready: 1, proposed: 0 },
    ])
  })

  it('keeps a crop 4:5 and inside the photo when it moves or zooms', () => {
    const crop = centredCrop(1500, 1000)
    expect(crop.height).toBe(1)
    expect((crop.width * 1500) / (crop.height * 1000)).toBeCloseTo(0.8)
    expect(moveCrop(crop, 1, 0).x).toBeCloseTo(1 - crop.width)
    const zoomed = zoomCrop(crop, 0.5, 1500, 1000)
    expect((zoomed.width * 1500) / (zoomed.height * 1000)).toBeCloseTo(0.8)
    expect(zoomed.height).toBeCloseTo(0.5)
    expect(zoomed.y).toBeCloseTo(0.25)
  })
})

describe('Content Studio', () => {
  beforeEach(() => {
    setCookie('XSRF-TOKEN=csrf-token')
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-10-17T10:00:00Z'))
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    clearCookie('XSRF-TOKEN')
    window.history.replaceState(null, '', '/')
  })

  it('shows the week with its goal, warns about an empty week, and plans an approved post into it', async () => {
    const fetch = renderWorkspace('#/content', {
      [`GET ${CONTENT}/studio-settings`]: () => json(settings({ weeklyGoal: 3 })),
      [`GET ${CONTENT}/posts`]: (_, url) =>
        url.searchParams.get('unscheduled') === 'true'
          ? page([post({ id: 'p-9', status: 'APPROVED', plannedOn: null, stage: 'DRIP', version: 2 })])
          : url.searchParams.get('from') === '2026-10-12'
            ? page([
                post({ id: 'p-1', status: 'SCHEDULED', plannedOn: '2026-10-13' }),
                post({ id: 'p-2', status: 'POSTED', format: 'CAROUSEL', postedOn: '2026-10-14' }),
                post({ id: 'p-3', format: 'STORY', plannedOn: '2026-10-14' }),
              ])
            : page([]),
      [`PUT ${CONTENT}/posts/p-9/schedule`]: () => json(post({ id: 'p-9', plannedOn: '2026-10-19' })),
    })

    expect(await screen.findByRole('heading', { name: /Week of Oct 12, 2026/ })).toBeTruthy()
    const meter = await screen.findByRole('progressbar', { name: 'Week of Oct 12, 2026' })
    expect(meter.getAttribute('aria-valuenow')).toBe('2')
    expect(screen.getByText('2 of 3 posts')).toBeTruthy()
    const tuesday = screen.getByRole('region', { name: 'Oct 13, 2026' })
    expect(within(tuesday).getByRole('link', { name: 'Sneak peek · Photo · Family session' }).getAttribute('href')).toBe(
      '#/content/posts/p-1',
    )
    expect(screen.queryByText(/Nothing planned/)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Nothing planned for the week of Oct 19, 2026.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Plan for Oct 19, 2026' }))
    await waitFor(() =>
      expect(bodiesOf(fetch, 'PUT', `${CONTENT}/posts/p-9/schedule`)).toEqual([{ version: 2, plannedOn: '2026-10-19' }]),
    )
  })

  it('lists plans to review', async () => {
    renderWorkspace('#/content?view=review', {
      [`GET ${CONTENT}/plans`]: () => page([plan(), plan({ id: 'pl-2', sessionId: 's-2', sessionTitle: 'Wedding', status: 'FAILED' })]),
    })

    const list = await screen.findByRole('list', { name: 'Plans to review' })
    expect(within(list).getByRole('link', { name: 'Family session' }).getAttribute('href')).toBe('#/content/plans/s-1')
    expect(within(list).getByText(/2 to review · 0 approved/)).toBeTruthy()
    expect(within(list).getByText('Failed')).toBeTruthy()
  })

  it('waits for a plan being written, then shows its posts by stage, and refreshes it', async () => {
    let calls = 0
    const fetch = renderWorkspace('#/content/plans/s-1', {
      [`GET ${CONTENT}/sessions/s-1/plan`]: () => {
        calls++
        return calls === 1
          ? json({ plan: plan({ status: 'PENDING' }), posts: [] })
          : json({
              plan: plan({ warnings: ['No studio area given.'] }),
              posts: [post(), post({ id: 'p-2', stage: 'DELIVERY', format: 'CAROUSEL', plannedOn: null })],
            })
      },
      [`POST ${CONTENT}/sessions/s-1/plan`]: () => json({ plan: plan({ status: 'PENDING' }), posts: [] }),
    })

    expect(await screen.findByText(/Planning posts/)).toBeTruthy()
    await act(() => vi.advanceTimersByTimeAsync(PLAN_POLL_MS))
    const delivery = await screen.findByRole('list', { name: 'Delivery' })
    expect(within(delivery).getByText('Day set when the gallery is published')).toBeTruthy()
    expect(within(screen.getByRole('list', { name: 'Sneak peek' })).getByRole('link').getAttribute('href')).toBe(
      '#/content/posts/p-1',
    )
    expect(screen.getByText('No studio area given.')).toBeTruthy()
    expect(screen.getByText(/get their days when the session’s gallery is published/)).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh plan' }))
    expect(await screen.findByText(/Planning posts/)).toBeTruthy()
    expect(fetch.mock.calls.filter(([input, init]) => input === `${CONTENT}/sessions/s-1/plan` && init?.method === 'POST')).toHaveLength(1)
  })

  it('offers to plan posts on a session without a plan', async () => {
    renderWorkspace('#/sessions/s-1', {
      [`GET ${WS}/sessions/s-1`]: () => json(session()),
      [`GET ${WS}/sessions/s-1/assets`]: () => page([photo('a-1')]),
      [`GET ${WS}/galleries`]: () => page([]),
      [`GET ${CONTENT}/batches`]: () => page([]),
      [`POST ${CONTENT}/sessions/s-1/plan`]: () => json({ plan: plan({ status: 'PENDING' }), posts: [] }),
    })

    const panel = await screen.findByRole('region', { name: 'Content plan' })
    expect(await within(panel).findByText(/No plan yet/)).toBeTruthy()
    fireEvent.click(within(panel).getByRole('button', { name: 'Plan posts' }))
    await waitFor(() => expect(window.location.hash).toBe('#/content/plans/s-1'))
  })

  it('reorders and crops a post’s photos, which sends it back to review', async () => {
    const fetch = renderWorkspace('#/content/posts/p-1', {
      [`GET ${CONTENT}/posts/p-1`]: () =>
        json(postDetail({ status: 'SCHEDULED', draftStatus: 'APPROVED' }, [photo('a-1'), photo('a-2')])),
      [`PUT ${CONTENT}/posts/p-1/photos`]: () => json(postDetail({ status: 'PROPOSED', version: 1 }, [photo('a-2'), photo('a-1')])),
    })

    const photos = await screen.findByRole('list', { name: 'Post photos' })
    expect(screen.getByRole('link', { name: 'Open post kit' }).getAttribute('href')).toBe('#/content/posts/p-1/kit')
    expect(screen.getByText('Mention Kraków so people nearby find the post.')).toBeTruthy()
    expect(screen.getByText(/Changing photos or crops needs a new approval/)).toBeTruthy()
    fireEvent.click(within(photos).getByRole('button', { name: 'Move a-2.jpg up' }))
    fireEvent.click(within(photos).getByRole('button', { name: 'Crop a-2.jpg' }))
    const frame = within(photos).getByRole('slider', { name: 'Crop position for a-2.jpg' })
    fireEvent.keyDown(frame, { key: 'ArrowLeft' })
    fireEvent.click(screen.getByRole('button', { name: 'Save photos' }))

    await waitFor(() => expect(bodiesOf(fetch, 'PUT', `${CONTENT}/posts/p-1/photos`)).toHaveLength(1))
    const [body] = bodiesOf(fetch, 'PUT', `${CONTENT}/posts/p-1/photos`) as {
      version: number
      photos: { assetId: string; crop: { x: number } | null }[]
    }[]
    expect(body.version).toBe(0)
    expect(body.photos.map((p) => p.assetId)).toEqual(['a-2', 'a-1'])
    expect(body.photos[0].crop?.x).toBeCloseTo(centredCrop(1500, 1000).x - 0.01)
    expect(body.photos[1].crop).toBeNull()
    expect(await screen.findByText('To review')).toBeTruthy()
  })

  it('marks an approved post as posted with the day and link', async () => {
    const fetch = renderWorkspace('#/content/posts/p-1', {
      [`GET ${CONTENT}/posts/p-1`]: () => json(postDetail({ status: 'SCHEDULED', draftStatus: 'APPROVED', version: 1 })),
      [`POST ${CONTENT}/posts/p-1/posted`]: () => json(post({ status: 'POSTED' })),
    })

    const form = await screen.findByRole('form', { name: 'Mark as posted' })
    expect((within(form).getByLabelText('Posted on') as HTMLInputElement).value).toBe('2026-10-17')
    fireEvent.change(within(form).getByLabelText('Link to the post (optional)'), {
      target: { value: 'https://www.instagram.com/p/abc' },
    })
    fireEvent.click(within(form).getByRole('button', { name: 'Mark as posted' }))
    await waitFor(() =>
      expect(bodiesOf(fetch, 'POST', `${CONTENT}/posts/p-1/posted`)).toEqual([
        { version: 1, posted: true, postedOn: '2026-10-17', postUrl: 'https://www.instagram.com/p/abc' },
      ]),
    )
  })

  it('rewrites one post’s caption and waits for it', async () => {
    let calls = 0
    const fetch = renderWorkspace('#/content/posts/p-1', {
      [`GET ${CONTENT}/posts/p-1`]: () => {
        calls++
        if (calls === 1) return json(postDetail())
        if (calls === 2) return json(postDetail({ regenerating: true, version: 1 }))
        return json({ ...postDetail({ version: 2, draftVersion: 2 }), draft: draft({ version: 2, caption: 'A new caption.' }) })
      },
      [`POST ${CONTENT}/posts/p-1/regenerate`]: () => json(post({ regenerating: true, version: 1 })),
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Write a new caption' }))
    expect(await screen.findByText(/Writing a new caption/)).toBeTruthy()
    expect(bodiesOf(fetch, 'POST', `${CONTENT}/posts/p-1/regenerate`)).toEqual([{ version: 0 }])
    await act(() => vi.advanceTimersByTimeAsync(PLAN_POLL_MS))
    await waitFor(() => expect((screen.getByLabelText('Caption') as HTMLTextAreaElement).value).toBe('A new caption.'))
  })

  it('blocks saving a caption with more than five hashtags', async () => {
    renderWorkspace('#/content/posts/p-1', { [`GET ${CONTENT}/posts/p-1`]: () => json(postDetail()) })

    const hashtags = (await screen.findByLabelText('Hashtags')) as HTMLInputElement
    fireEvent.change(hashtags, { target: { value: '#a #b #c #d #e #f' } })
    expect(screen.getByText('Instagram allows at most 5 hashtags.')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Save changes' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('lays out the post kit with the photos in order and the text to copy', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    renderWorkspace('#/content/posts/p-1/kit', {
      [`GET ${CONTENT}/posts/p-1/kit`]: () =>
        json({
          post: post({ status: 'SCHEDULED', idea: null }),
          photos: [
            { assetId: 'a-2', filename: 'a-2.jpg', thumbnailUrl: 't2', exportUrl: 'http://storage.test/e2', originalUrl: 'http://storage.test/o2' },
            { assetId: 'a-1', filename: 'a-1.jpg', thumbnailUrl: 't1', exportUrl: 'http://storage.test/e1', originalUrl: null },
          ],
        }),
    })

    const photos = await screen.findByRole('list', { name: 'Photos in posting order' })
    expect(within(photos).getAllByRole('img').map((img) => img.getAttribute('alt'))).toEqual(['1. a-2.jpg', '2. a-1.jpg'])
    expect(within(photos).getAllByRole('link', { name: 'Save' }).map((a) => a.getAttribute('href'))).toEqual([
      'http://storage.test/e2',
      'http://storage.test/e1',
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Copy caption and hashtags' }))
    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith('A first peek at this family session.\n\nSend this to a friend.\n\n#family'),
    )
  })

  it('saves the Content Studio settings with their version', async () => {
    const fetch = renderWorkspace('#/settings/content-studio', {
      [`GET ${CONTENT}/studio-settings`]: () => json(settings()),
      [`PUT ${CONTENT}/studio-settings`]: () => json(settings({ weeklyGoal: 5, studioArea: 'Kraków', version: 1 })),
      [`GET ${CONTENT}/brand-profile`]: () =>
        json({ voice: null, audience: null, preferences: null, avoid: null, version: 0, updatedAt: null }),
    })

    const form = await screen.findByRole('form', { name: 'Plans and posting' })
    fireEvent.change(within(form).getByLabelText('Feed posts per week'), { target: { value: '5' } })
    fireEvent.change(within(form).getByLabelText('Studio area'), { target: { value: ' Kraków ' } })
    fireEvent.click(within(form).getByRole('checkbox', { name: "Propose a plan when a session's photos are ready" }))
    fireEvent.click(within(form).getByRole('button', { name: 'Save settings' }))

    await waitFor(() =>
      expect(bodiesOf(fetch, 'PUT', `${CONTENT}/studio-settings`)).toEqual([
        {
          version: 0,
          settings: {
            weeklyGoal: 5,
            autoPlans: false,
            sneakPeekDays: 1,
            dripPosts: 6,
            dripSpacingDays: 7,
            studioArea: 'Kraków',
          },
        },
      ]),
    )
  })
})
