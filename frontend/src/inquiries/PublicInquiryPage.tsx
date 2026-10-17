import { useState, type ReactNode } from 'react'
import { CircleCheck, FileQuestion } from 'lucide-react'
import { ApiError } from '../api/client.ts'
import { fetchPublicForm, submitInquiry, type PublicForm, type Submission } from '../api/inquiryForm.ts'
import { Button } from '@/components/ui/button'
import { Brand } from '../layout/Brand.tsx'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { reference } from '../ui/formError.ts'
import { useLoad } from '../useLoad.ts'
import { BrandedForm, type FormValues } from './BrandedForm.tsx'
import { brandStyle } from './brand.ts'
import { BrandHeader } from './BrandHeader.tsx'

const UTM = ['source', 'medium', 'campaign', 'term', 'content'] as const

/**
 * The public inquiry form, at `/inquiry/<slug>` (hosted) or `/embed/inquiry/<slug>` (inside an iframe on the
 * photographer's site, with minimal chrome). It calls only the public inquiry API: no sign-in, no session
 * cookie, no CSRF token, and no workspace API.
 */
export function PublicInquiryPage({ slug, embed }: { slug: string; embed: boolean }) {
  const form = useLoad((signal) => fetchPublicForm(slug, signal), [slug])

  let content
  if (form.state.kind === 'loading') content = <Loading>Loading the form…</Loading>
  else if (form.state.kind === 'error') {
    const error = form.state.error
    content =
      error instanceof ApiError && error.status === 404 ? (
        <Message icon={<FileQuestion className="size-5" aria-hidden="true" />} title="This form is not available">
          It may have been taken down, or the link is incomplete. Please contact the business directly.
        </Message>
      ) : (
        <ErrorAlert>
          <span className="flex flex-wrap items-center gap-3">
            Could not load the form{reference(error)}.{' '}
            <Button type="button" variant="outline" size="sm" onClick={form.reload}>
              Retry
            </Button>
          </span>
        </ErrorAlert>
      )
  } else content = <Submit slug={slug} embed={embed} form={form.state.value} />

  if (embed) return <main className="mx-auto w-full max-w-2xl p-3 sm:p-4">{content}</main>
  return (
    <div className="flex min-h-dvh flex-col">
      <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-8 sm:px-6 sm:py-12">{content}</main>
      <footer className="flex justify-center px-4 py-6 text-xs text-muted-foreground opacity-80">
        <span className="flex items-center gap-2">
          Powered by <Brand />
        </span>
      </footer>
    </div>
  )
}

function Submit({ slug, embed, form }: { slug: string; embed: boolean; form: PublicForm }) {
  // One key per form fill: a retried or double submit is stored once.
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const [shownAt] = useState(() => Date.now())
  const [submitting, setSubmitting] = useState(false)
  const [sent, setSent] = useState(false)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)

  async function submit(values: FormValues) {
    setSubmitting(true)
    setError(null)
    setFieldErrors({})
    const query = new URLSearchParams(window.location.search)
    const utmValue = (name: string) => query.get(`utm_${name}`)
    const submission: Submission = {
      idempotencyKey,
      answers: values.answers,
      service: values.service || null,
      source: embed ? 'EMBED' : 'HOSTED',
      referrer: document.referrer || null,
      utm: Object.fromEntries(UTM.map((name) => [name, utmValue(name)])) as Submission['utm'],
      website: values.website,
      elapsedMillis: Date.now() - shownAt,
    }
    try {
      await submitInquiry(slug, submission)
      setSent(true)
    } catch (e) {
      if (e instanceof ApiError && e.status === 400 && e.problem.errors) {
        setFieldErrors(e.problem.errors)
        setError('Please check the highlighted answers.')
      } else if (e instanceof ApiError && e.status === 404) {
        setError('This form is no longer accepting inquiries.')
      } else {
        setError(`Your inquiry could not be sent${reference(e)}. Please try again.`)
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (sent) {
    return (
      <div style={brandStyle(form.branding)} className="space-y-6">
        <BrandHeader branding={form.branding} compact={embed} />
        <Message icon={<CircleCheck className="size-5" aria-hidden="true" />} title="Inquiry sent">
          <span className="whitespace-pre-line">{form.form.confirmation}</span>
        </Message>
      </div>
    )
  }
  return (
    <BrandedForm
      form={form.form}
      branding={form.branding}
      compact={embed}
      onSubmit={submit}
      submitting={submitting}
      fieldErrors={fieldErrors}
      error={error}
    />
  )
}

function Message({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <section
      role="status"
      aria-labelledby="inquiry-message-heading"
      className="mx-auto max-w-md space-y-3 rounded-2xl border bg-card p-8 text-center shadow-sm"
    >
      <span className="mx-auto flex size-12 items-center justify-center rounded-full bg-accent text-[var(--brand-accent,currentColor)]">
        {icon}
      </span>
      <h1 id="inquiry-message-heading" className="text-xl font-semibold">
        {title}
      </h1>
      <p className="text-sm text-muted-foreground">{children}</p>
    </section>
  )
}
