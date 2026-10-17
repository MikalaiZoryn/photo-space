import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.tsx'
import { embedSnippet } from '../api/inquiryForm.ts'
import { bodiesOf, clearCookie, headersOf, json, mockApi, setCookie } from '../test/mockApi.ts'
import { page, renderWorkspace, WS } from '../test/workspace.tsx'

const SLUG = 'AbCdEfGhIjKlMnOpQrStUv'
const PUBLIC = `/api/public/inquiry/${SLUG}`

function config(overrides: Record<string, unknown> = {}) {
  return {
    title: 'Tell us about your session',
    intro: 'We reply within two days.',
    confirmation: 'Thank you! We will be in touch.',
    questions: [
      { id: 'name', type: 'SHORT_TEXT', label: 'Your name', help: null, required: true, choices: [] },
      { id: 'email', type: 'EMAIL', label: 'Email', help: null, required: true, choices: [] },
      { id: 'message', type: 'LONG_TEXT', label: 'Message', help: null, required: false, choices: [] },
    ],
    service: { label: 'Type of session', required: false, choices: ['Family', 'Wedding'] },
    ...overrides,
  }
}

const publicBranding = {
  displayName: 'Alice Photo',
  logoUrl: 'https://photos.example/logo?sig=1',
  primaryColor: '#1a2b3c',
  accentColor: '#ff0066',
  font: 'SERIF',
  email: 'hello@alice.example',
  phone: null,
  description: 'Family and wedding photography in Warsaw.',
}

const branding = {
  ...publicBranding,
  displayName: null,
  workspaceName: 'Alice Studio',
  logo: null,
  logoUrl: undefined,
  version: 0,
  updatedAt: null,
}

function form(overrides: Record<string, unknown> = {}) {
  return {
    id: 'f-1',
    status: 'DRAFT',
    slug: SLUG,
    draft: config(),
    published: null,
    unpublishedChanges: true,
    version: 0,
    createdAt: '2026-10-17T10:00:00Z',
    updatedAt: '2026-10-17T10:00:00Z',
    ...overrides,
  }
}

function inquiry(overrides: Record<string, unknown> = {}) {
  return {
    id: 'i-1',
    status: 'NEEDS_REVIEW',
    name: 'Anna Nowak',
    email: 'anna@example.com',
    phone: null,
    service: 'Family',
    preferredDate: '2026-11-07',
    answers: [
      { questionId: 'name', label: 'Your name', type: 'SHORT_TEXT', value: 'Anna Nowak' },
      { questionId: 'message', label: 'Message', type: 'LONG_TEXT', value: 'Photos in the park\nwith our dog' },
    ],
    formVersion: 1,
    source: 'EMBED',
    referrer: 'https://alice.example/contact',
    utm: { source: 'instagram', medium: null, campaign: null, term: null, content: null },
    matches: [{ id: 'c-1', name: 'Anna Nowak', email: 'anna@example.com', phone: '+48 600' }],
    client: null,
    clientCreated: false,
    session: null,
    reviewTaskId: 't-1',
    followUpTaskId: null,
    associatedAt: null,
    dismissedAt: null,
    version: 1,
    submittedAt: '2026-10-17T10:00:00Z',
    updatedAt: '2026-10-17T10:00:00Z',
    ...overrides,
  }
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  clearCookie('XSRF-TOKEN')
  window.history.replaceState(null, '', '/')
})

describe('public inquiry page', () => {
  it('shows the branded form and submits the answers without a CSRF token', async () => {
    window.history.replaceState(null, '', `/inquiry/${SLUG}?utm_source=newsletter&utm_campaign=autumn`)
    const fetch = mockApi({
      [`GET ${PUBLIC}`]: () => json({ form: config(), branding: publicBranding }),
      [`POST ${PUBLIC}`]: () => json({ received: true }, 202),
    })
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Tell us about your session' })).toBeTruthy()
    expect(screen.getByText('Alice Photo')).toBeTruthy()
    expect(screen.getByRole('img', { name: 'Alice Photo logo' }).getAttribute('src')).toBe(publicBranding.logoUrl)
    expect(screen.getByRole('link', { name: 'hello@alice.example' })).toBeTruthy()
    expect(fetch.mock.calls.map(([url]) => String(url))).toEqual([PUBLIC])

    fireEvent.change(screen.getByLabelText(/Your name/), { target: { value: 'Anna Nowak' } })
    fireEvent.change(screen.getByLabelText(/^Email/), { target: { value: 'anna@example.com' } })
    fireEvent.change(screen.getByLabelText(/Type of session/), { target: { value: 'Family' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send inquiry' }))

    expect(await screen.findByText('Thank you! We will be in touch.')).toBeTruthy()
    const [body] = bodiesOf(fetch, 'POST', PUBLIC) as Record<string, unknown>[]
    expect(body).toMatchObject({
      answers: { name: 'Anna Nowak', email: 'anna@example.com' },
      service: 'Family',
      source: 'HOSTED',
      website: '',
      utm: { source: 'newsletter', campaign: 'autumn', medium: null },
    })
    expect(body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(typeof body.elapsedMillis).toBe('number')
    expect(headersOf(fetch, 'POST', PUBLIC)['X-XSRF-TOKEN']).toBeUndefined()
    expect(fetch.mock.calls.some(([url]) => String(url).startsWith('/api/auth'))).toBe(false)
  })

  it('shows the server’s field errors and keeps the same idempotency key on retry', async () => {
    window.history.replaceState(null, '', `/embed/inquiry/${SLUG}`)
    let attempts = 0
    const fetch = mockApi({
      [`GET ${PUBLIC}`]: () => json({ form: config(), branding: publicBranding }),
      [`POST ${PUBLIC}`]: () =>
        ++attempts === 1
          ? json({ status: 400, errors: { 'answers.email': 'must be an email address' } }, 400)
          : json({ received: true }, 202),
    })
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Send inquiry' }))
    expect(await screen.findByText('must be an email address')).toBeTruthy()
    expect(screen.getByText('Please check the highlighted answers.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Send inquiry' }))
    expect(await screen.findByText('Inquiry sent')).toBeTruthy()

    const bodies = bodiesOf(fetch, 'POST', PUBLIC) as { idempotencyKey: string; source: string }[]
    expect(bodies).toHaveLength(2)
    expect(bodies[1].idempotencyKey).toBe(bodies[0].idempotencyKey)
    expect(bodies[0].source).toBe('EMBED')
  })

  it('says the form is not available for unknown or unpublished slugs', async () => {
    window.history.replaceState(null, '', `/inquiry/${SLUG}`)
    mockApi({})
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'This form is not available' })).toBeTruthy()
  })
})

describe('inquiry form builder', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  it('starts from the default template', async () => {
    const fetch = renderWorkspace('#/inquiry-form', {
      [`GET ${WS}/branding`]: () => json(branding),
      [`POST ${WS}/inquiry-form`]: () => json(form(), 201),
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Start from the default template' }))
    expect(await screen.findByRole('button', { name: 'Publish' })).toBeTruthy()
    expect(fetch.mock.calls.filter(([url, init]) => url === `${WS}/inquiry-form` && init?.method === 'POST')).toHaveLength(1)
  })

  it('edits, reorders, and publishes the draft with a live preview', async () => {
    const fetch = renderWorkspace('#/inquiry-form', {
      [`GET ${WS}/branding`]: () => json(branding),
      [`GET ${WS}/inquiry-form`]: () => json(form()),
      [`PUT ${WS}/inquiry-form`]: (init) => {
        const { draft } = JSON.parse(String(init?.body))
        return json(form({ draft, version: 1 }))
      },
      [`POST ${WS}/inquiry-form/publish`]: () =>
        json(form({ status: 'PUBLISHED', version: 2, unpublishedChanges: false, published: { id: 'v-1', number: 1, config: config(), publishedAt: '2026-10-17T11:00:00Z' } })),
    })

    const questions = await screen.findByRole('list', { name: 'Questions' })
    expect(screen.getByRole('button', { name: 'Remove Your name' })).toHaveProperty('disabled', true)
    fireEvent.click(screen.getByRole('button', { name: 'Add question' }))
    const added = within(questions).getAllByRole('listitem')[3]
    fireEvent.change(within(added).getByLabelText('Label'), { target: { value: 'Budget' } })
    fireEvent.change(within(added).getByLabelText('Type'), { target: { value: 'SINGLE_CHOICE' } })
    fireEvent.change(within(added).getByLabelText(/Choices/), { target: { value: 'Under 500\n\n500 or more\n' } })
    fireEvent.click(within(added).getByRole('button', { name: 'Move Budget up' }))
    // The preview follows the draft.
    const preview = screen.getByRole('region', { name: 'Preview' })
    expect(within(preview).getByRole('combobox', { name: /Budget/ })).toBeTruthy()
    expect(within(preview).getByRole('option', { name: 'Under 500' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Publish' }))
    expect(await screen.findByText('Published. Visitors now see this version.')).toBeTruthy()

    const [{ version, draft }] = bodiesOf(fetch, 'PUT', `${WS}/inquiry-form`) as { version: number; draft: ReturnType<typeof config> }[]
    expect(version).toBe(0)
    expect(draft.questions.map((q) => q.label)).toEqual(['Your name', 'Email', 'Budget', 'Message'])
    expect(draft.questions[2]).toMatchObject({ type: 'SINGLE_CHOICE', choices: ['Under 500', '500 or more'], required: false })
    expect(draft.questions[2].id).toMatch(/^q[a-z0-9]+$/)
    expect(bodiesOf(fetch, 'POST', `${WS}/inquiry-form/publish`)).toEqual([{ version: 1 }])
    expect(screen.getByRole('button', { name: 'Unpublish' })).toBeTruthy()
    expect((screen.getByRole('textbox', { name: /Embed on your site/ }) as HTMLTextAreaElement).value).toBe(embedSnippet(SLUG))
  })

  it('shows the server’s validation messages per question', async () => {
    renderWorkspace('#/inquiry-form', {
      [`GET ${WS}/branding`]: () => json(branding),
      [`GET ${WS}/inquiry-form`]: () => json(form()),
      [`PUT ${WS}/inquiry-form`]: () =>
        json({ status: 400, errors: { 'draft.questions[2].label': 'is required', 'draft.title': 'is required' } }, 400),
    })
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }))
    expect(await screen.findByText('Question 3 label is required')).toBeTruthy()
    expect(screen.getByText('Title is required')).toBeTruthy()
  })
})

describe('inquiries', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  it('lists inquiries and hides dismissed ones unless asked', async () => {
    const fetch = renderWorkspace('#/inquiries', {
      [`GET ${WS}/inquiries`]: (_, url) =>
        url.searchParams.get('status') === 'DISMISSED' ? page([]) : page([inquiry({ status: 'NEW' })]),
    })
    const row = (await screen.findByRole('link', { name: 'Anna Nowak' })).closest('li')!
    expect(within(row).getByText('New')).toBeTruthy()
    expect(within(row).getByText(/anna@example.com · Family · prefers Nov 7, 2026/)).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'DISMISSED' } })
    expect(await screen.findByText('No inquiries with this status.')).toBeTruthy()
    expect(fetch.mock.calls.map(([url]) => String(url)).filter((url) => url.includes('/inquiries'))).toEqual([
      `${WS}/inquiries?page=0`,
      `${WS}/inquiries?status=DISMISSED&page=0`,
    ])
  })

  it('links a matching client', async () => {
    const fetch = renderWorkspace('#/inquiries/i-1', {
      [`GET ${WS}/inquiries/i-1`]: () => json(inquiry()),
      [`POST ${WS}/inquiries/i-1/associate`]: () =>
        json(inquiry({ status: 'ASSOCIATED', matches: [], client: { id: 'c-1', name: 'Anna Nowak', archived: false }, session: { id: 's-9', title: 'Inquiry: Anna Nowak', sessionType: 'Family', archived: false }, followUpTaskId: 't-2', version: 2 })),
    })

    expect(await screen.findByText('Photos in the park with our dog', { normalizer: (t) => t.replace(/\s+/g, ' ') })).toBeTruthy()
    expect(screen.getByText('Nov 7, 2026 (not booked)')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Link to Anna Nowak' }))

    expect(await screen.findByRole('link', { name: 'Inquiry: Anna Nowak' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Follow-up task' }).getAttribute('href')).toBe('#/tasks/t-2/edit')
    expect(bodiesOf(fetch, 'POST', `${WS}/inquiries/i-1/associate`)).toEqual([{ version: 1, clientId: 'c-1' }])
  })

  it('creates a client from the answers, or dismisses and reopens', async () => {
    const fetch = renderWorkspace('#/inquiries/i-1', {
      [`GET ${WS}/inquiries/i-1`]: () => json(inquiry({ status: 'NEW', matches: [] })),
      [`POST ${WS}/inquiries/i-1/dismiss`]: () => json(inquiry({ status: 'DISMISSED', matches: [], dismissedAt: '2026-10-17T12:00:00Z', version: 2 })),
      [`POST ${WS}/inquiries/i-1/reopen`]: () => json(inquiry({ status: 'NEW', matches: [], version: 3 })),
      [`POST ${WS}/inquiries/i-1/associate`]: () => json({ status: 409, detail: 'This inquiry was changed by someone else. Reload it and try again.' }, 409),
    })

    expect(await screen.findByText('No client has this email yet.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Create a new client from these answers' }))
    expect(await screen.findByText('This inquiry was changed by someone else. Reload it and try again.')).toBeTruthy()
    expect(bodiesOf(fetch, 'POST', `${WS}/inquiries/i-1/associate`)).toEqual([{ version: 1, createClient: true }])

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Reopen' }))
    expect(await screen.findByRole('button', { name: 'Dismiss' })).toBeTruthy()
    expect(bodiesOf(fetch, 'POST', `${WS}/inquiries/i-1/dismiss`)).toEqual([{ version: 1 }])
    expect(bodiesOf(fetch, 'POST', `${WS}/inquiries/i-1/reopen`)).toEqual([{ version: 2 }])
  })
})

describe('branding', () => {
  beforeEach(() => setCookie('XSRF-TOKEN=csrf-token'))

  it('saves colors, font, and contact details with a live preview', async () => {
    const fetch = renderWorkspace('#/branding', {
      [`GET ${WS}/branding`]: () => json(branding),
      [`PUT ${WS}/branding`]: (init) => {
        const { branding: saved } = JSON.parse(String(init?.body))
        return json({ ...branding, ...saved, version: 1 })
      },
    })

    const name = await screen.findByLabelText('Business name')
    expect(name.getAttribute('placeholder')).toBe('Alice Studio')
    fireEvent.change(name, { target: { value: ' Alice Photo ' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Primary color (hex)' }), { target: { value: '#ffffff' } })
    fireEvent.change(screen.getByLabelText('Font'), { target: { value: 'ROUNDED' } })
    expect(screen.getByText('Alice Photo', { selector: 'p' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Save branding' }))
    expect(await screen.findByText('Branding saved.')).toBeTruthy()
    expect(bodiesOf(fetch, 'PUT', `${WS}/branding`)).toEqual([
      {
        version: 0,
        branding: {
          displayName: 'Alice Photo',
          logoAssetId: null,
          primaryColor: '#ffffff',
          accentColor: '#ff0066',
          font: 'ROUNDED',
          email: 'hello@alice.example',
          phone: null,
          description: 'Family and wedding photography in Warsaw.',
        },
      },
    ])
  })
})
