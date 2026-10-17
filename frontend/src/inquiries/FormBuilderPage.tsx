import { useState, type ReactNode } from 'react'
import { ArrowDown, ArrowUp, Check, Copy, Plus, Trash2 } from 'lucide-react'
import { ApiError } from '../api/client.ts'
import { getBranding, publicView, type Branding } from '../api/branding.ts'
import {
  createInquiryForm,
  embedSnippet,
  getInquiryForm,
  hostedUrl,
  publishForm,
  QUESTION_TYPES,
  REQUIRED_QUESTIONS,
  RESERVED_QUESTIONS,
  saveDraft,
  unpublishForm,
  type FormConfig,
  type InquiryForm,
  type Question,
  type QuestionType,
} from '../api/inquiryForm.ts'
import { paths } from '../routing.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { EmptyState, ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { CheckboxField, Field } from '../ui/Field.tsx'
import { reference } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { BrandedForm } from './BrandedForm.tsx'
import { formStatusLabels, formStatusTones, questionTypeLabels } from './labels.ts'

type Props = { workspaceId: string }

/**
 * The General Inquiry form builder: edit the draft with a live, branded preview, save it, and publish it.
 * Visitors see only the published version; draft edits reach them on the next publish.
 */
export function FormBuilderPage({ workspaceId }: Props) {
  const loaded = useLoad(
    async (signal) => {
      const branding = await getBranding(workspaceId, signal)
      try {
        return { branding, form: await getInquiryForm(workspaceId, signal) }
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return { branding, form: null }
        throw e
      }
    },
    [workspaceId],
  )
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)

  async function create(branding: Branding) {
    setCreating(true)
    setCreateError(null)
    try {
      loaded.replace({ branding, form: await createInquiryForm(workspaceId) })
    } catch (e) {
      setCreateError(`Could not create the form${reference(e)}.`)
    } finally {
      setCreating(false)
    }
  }

  const header = (actions?: ReactNode, badges?: ReactNode) => (
    <PageHeader
      id="inquiry-form-heading"
      title="Inquiry form"
      badges={badges}
      description={
        <>
          The form visitors fill in on your hosted page or your own site. Its look comes from your{' '}
          <a className="font-medium text-accent-foreground hover:underline" href={paths.branding()}>
            branding
          </a>
          .
        </>
      }
      actions={actions}
    />
  )

  if (loaded.state.kind === 'loading') {
    return (
      <section aria-labelledby="inquiry-form-heading">
        {header()}
        <Loading>Loading the form…</Loading>
      </section>
    )
  }
  if (loaded.state.kind === 'error') {
    return (
      <section aria-labelledby="inquiry-form-heading">
        {header()}
        <LoadError error={loaded.state.error} what="inquiry form" onRetry={loaded.reload} />
      </section>
    )
  }
  const { branding, form } = loaded.state.value
  if (form === null) {
    return (
      <section aria-labelledby="inquiry-form-heading">
        {header()}
        {createError && (
          <div className="mb-4">
            <ErrorAlert>{createError}</ErrorAlert>
          </div>
        )}
        <EmptyState
          action={
            <Button type="button" disabled={creating} onClick={() => create(branding)}>
              Start from the default template
            </Button>
          }
        >
          You have no inquiry form yet. Start from a template with name, email, phone, preferred date, and a message.
        </EmptyState>
      </section>
    )
  }
  return (
    <Builder
      key={form.id}
      workspaceId={workspaceId}
      branding={branding}
      initial={form}
      header={header}
    />
  )
}

function Builder({
  workspaceId,
  branding,
  initial,
  header,
}: {
  workspaceId: string
  branding: Branding
  initial: InquiryForm
  header: (actions?: ReactNode, badges?: ReactNode) => ReactNode
}) {
  const [form, setForm] = useState(initial)
  const [draft, setDraft] = useState<FormConfig>(initial.draft)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState<string[]>([])
  const [notice, setNotice] = useState<string | null>(null)

  function edit(change: (draft: FormConfig) => FormConfig) {
    setDraft((current) => change(current))
    setDirty(true)
    setNotice(null)
  }

  const editQuestion = (index: number, change: Partial<Question>) =>
    edit((d) => ({ ...d, questions: d.questions.map((q, i) => (i === index ? { ...q, ...change } : q)) }))

  function move(index: number, by: -1 | 1) {
    edit((d) => {
      const questions = [...d.questions]
      const [moved] = questions.splice(index, 1)
      questions.splice(index + by, 0, moved)
      return { ...d, questions }
    })
  }

  async function run(action: () => Promise<InquiryForm>, done: string) {
    setBusy(true)
    setErrors([])
    setNotice(null)
    try {
      const saved = await action()
      setForm(saved)
      setDraft(saved.draft)
      setDirty(false)
      setNotice(done)
    } catch (e) {
      setErrors(messages(e))
    } finally {
      setBusy(false)
    }
  }

  const save = () => run(() => saveDraft(workspaceId, form.version, cleaned(draft)), 'Draft saved.')
  const publish = () =>
    run(async () => {
      const saved = dirty ? await saveDraft(workspaceId, form.version, cleaned(draft)) : form
      return publishForm(workspaceId, saved.version)
    }, 'Published. Visitors now see this version.')
  const unpublish = () => run(() => unpublishForm(workspaceId), 'Unpublished. The form no longer accepts inquiries.')

  const live = form.status === 'PUBLISHED'
  const pending = dirty || form.unpublishedChanges

  return (
    <section aria-labelledby="inquiry-form-heading">
      {header(
        <>
          <Button type="button" variant="outline" disabled={busy || !dirty} onClick={save}>
            Save draft
          </Button>
          <Button type="button" disabled={busy || (live && !pending)} onClick={publish}>
            {form.published ? 'Publish changes' : 'Publish'}
          </Button>
          {live && (
            <Button type="button" variant="outline" disabled={busy} onClick={unpublish}>
              Unpublish
            </Button>
          )}
        </>,
        <>
          <StatusBadge tone={formStatusTones[form.status]}>{formStatusLabels[form.status]}</StatusBadge>
          {form.published && <StatusBadge tone="neutral">Version {form.published.number}</StatusBadge>}
          {pending && form.published && <StatusBadge tone="warning">Unpublished changes</StatusBadge>}
        </>,
      )}
      <div className="mb-6 space-y-3">
        {errors.length > 0 && (
          <ErrorAlert>
            <ul className="list-inside list-disc">
              {errors.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          </ErrorAlert>
        )}
        {notice && (
          <p role="status" className="rounded-lg border border-tone-success/30 bg-tone-success-bg px-4 py-3 text-sm text-tone-success">
            {notice}
          </p>
        )}
      </div>
      <SharePanel form={form} />
      <div className="mt-6 grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div className="space-y-6">
          <Panel id="form-texts-heading" title="Texts">
            <div className="grid gap-4">
              <Field label="Title">
                <Input value={draft.title} maxLength={200} onChange={(e) => edit((d) => ({ ...d, title: e.target.value }))} />
              </Field>
              <Field label="Introduction">
                <Textarea
                  rows={3}
                  maxLength={2000}
                  value={draft.intro ?? ''}
                  onChange={(e) => edit((d) => ({ ...d, intro: e.target.value }))}
                />
              </Field>
              <Field label="Message after sending">
                <Textarea
                  rows={2}
                  maxLength={1000}
                  value={draft.confirmation}
                  onChange={(e) => edit((d) => ({ ...d, confirmation: e.target.value }))}
                />
              </Field>
            </div>
          </Panel>
          <Panel id="form-service-heading" title="Service">
            <CheckboxField
              label="Ask which service the visitor is interested in"
              checked={draft.service !== null}
              onChange={(checked) =>
                edit((d) => ({
                  ...d,
                  service: checked ? { label: 'Type of session', required: false, choices: ['Family', 'Portrait'] } : null,
                }))
              }
            />
            {draft.service && (
              <div className="grid gap-4">
                <Field label="Service question">
                  <Input
                    value={draft.service.label}
                    maxLength={200}
                    onChange={(e) => edit((d) => ({ ...d, service: { ...d.service!, label: e.target.value } }))}
                  />
                </Field>
                <ChoicesField
                  label="Services, one per line (the chosen one becomes the session type)"
                  choices={draft.service.choices}
                  onChange={(choices) => edit((d) => ({ ...d, service: { ...d.service!, choices } }))}
                />
                <CheckboxField
                  label="Required"
                  checked={draft.service.required}
                  onChange={(required) => edit((d) => ({ ...d, service: { ...d.service!, required } }))}
                />
              </div>
            )}
          </Panel>
          <Panel
            id="form-questions-heading"
            title="Questions"
            actions={
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={draft.questions.length >= 30}
                onClick={() => edit((d) => ({ ...d, questions: [...d.questions, newQuestion()] }))}
              >
                <Plus aria-hidden="true" />
                Add question
              </Button>
            }
          >
            <ol className="space-y-4" aria-label="Questions">
              {draft.questions.map((question, index) => (
                <QuestionEditor
                  key={question.id}
                  question={question}
                  position={index + 1}
                  first={index === 0}
                  last={index === draft.questions.length - 1}
                  onChange={(change) => editQuestion(index, change)}
                  onMove={(by) => move(index, by)}
                  onRemove={() => edit((d) => ({ ...d, questions: d.questions.filter((_, i) => i !== index) }))}
                />
              ))}
            </ol>
          </Panel>
        </div>
        <div>
          <section aria-labelledby="form-preview-heading" className="space-y-3 xl:sticky xl:top-6">
            <h4 id="form-preview-heading" className="text-base font-semibold">
              Preview
            </h4>
            <div className="rounded-2xl border border-dashed bg-muted/40 p-3 sm:p-5">
              <BrandedForm form={draft} branding={publicView(branding)} />
            </div>
          </section>
        </div>
      </div>
    </section>
  )
}

function QuestionEditor({
  question,
  position,
  first,
  last,
  onChange,
  onMove,
  onRemove,
}: {
  question: Question
  position: number
  first: boolean
  last: boolean
  onChange: (change: Partial<Question>) => void
  onMove: (by: -1 | 1) => void
  onRemove: () => void
}) {
  const reserved = question.id in RESERVED_QUESTIONS
  const alwaysRequired = REQUIRED_QUESTIONS.includes(question.id)
  const name = question.label || `Question ${position}`
  return (
    <li className="space-y-3 rounded-lg border bg-background p-3 sm:p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium">
          {position}. {name}
          {reserved && <span className="ml-2 text-xs font-normal text-muted-foreground">(used for matching and new clients)</span>}
        </p>
        <div className="flex gap-1">
          <Button type="button" size="icon" variant="ghost" aria-label={`Move ${name} up`} disabled={first} onClick={() => onMove(-1)}>
            <ArrowUp aria-hidden="true" />
          </Button>
          <Button type="button" size="icon" variant="ghost" aria-label={`Move ${name} down`} disabled={last} onClick={() => onMove(1)}>
            <ArrowDown aria-hidden="true" />
          </Button>
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-label={`Remove ${name}`}
            disabled={alwaysRequired}
            onClick={onRemove}
          >
            <Trash2 aria-hidden="true" />
          </Button>
        </div>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Label">
          <Input value={question.label} maxLength={200} onChange={(e) => onChange({ label: e.target.value })} />
        </Field>
        <Field label="Type">
          <NativeSelect
            className="w-full"
            value={question.type}
            disabled={reserved}
            onChange={(e) => {
              const type = e.target.value as QuestionType
              onChange({ type, choices: type === 'SINGLE_CHOICE' && question.choices.length === 0 ? ['Option 1', 'Option 2'] : question.choices })
            }}
          >
            {QUESTION_TYPES.map((type) => (
              <NativeSelectOption key={type} value={type}>
                {questionTypeLabels[type]}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <Field label="Help text" className="sm:col-span-2">
          <Input value={question.help ?? ''} maxLength={500} onChange={(e) => onChange({ help: e.target.value })} />
        </Field>
        {question.type === 'SINGLE_CHOICE' && (
          <div className="sm:col-span-2">
            <ChoicesField label="Choices, one per line" choices={question.choices} onChange={(choices) => onChange({ choices })} />
          </div>
        )}
      </div>
      <CheckboxField
        label={alwaysRequired ? 'Required (always)' : 'Required'}
        checked={question.required}
        onChange={(required) => !alwaysRequired && onChange({ required })}
      />
    </li>
  )
}

function ChoicesField({ label, choices, onChange }: { label: string; choices: string[]; onChange: (choices: string[]) => void }) {
  return (
    <Field label={label}>
      <Textarea rows={Math.max(3, choices.length)} value={choices.join('\n')} onChange={(e) => onChange(e.target.value.split('\n'))} />
    </Field>
  )
}

/** Hosted URL and embed snippet, once the form has been published at least once. */
function SharePanel({ form }: { form: InquiryForm }) {
  const [copied, setCopied] = useState<string | null>(null)
  const url = hostedUrl(form.slug)
  const snippet = embedSnippet(form.slug)

  async function copy(what: string, text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(what)
    } catch {
      // Clipboard access can be refused; the text stays selectable.
    }
  }

  const copyButton = (what: string, text: string) => (
    <Button type="button" variant="outline" onClick={() => copy(what, text)} aria-label={`Copy ${what}`}>
      {copied === what ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
      {copied === what ? 'Copied' : 'Copy'}
    </Button>
  )

  return (
    <Panel id="form-share-heading" title="Share">
      {form.status !== 'PUBLISHED' && (
        <p className="text-sm text-muted-foreground">
          {form.status === 'DRAFT'
            ? 'Publish the form to start accepting inquiries. Until then these links show “not available”.'
            : 'The form is unpublished: these links show “not available” until you publish it again.'}
        </p>
      )}
      <div className="grid gap-4">
        <Field label="Hosted page">
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input className="flex-1 font-mono text-xs" value={url} readOnly onFocus={(e) => e.target.select()} />
            {copyButton('hosted URL', url)}
          </div>
        </Field>
        <Field label="Embed on your site (paste into your page’s HTML)">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
            <Textarea className="flex-1 font-mono text-xs" rows={3} value={snippet} readOnly onFocus={(e) => e.target.select()} />
            {copyButton('embed code', snippet)}
          </div>
        </Field>
        <p className="text-xs text-muted-foreground">
          The embedded form has a fixed height of 1100 px; change the iframe’s height if your form is shorter or longer.
        </p>
      </div>
    </Panel>
  )
}

function newQuestion(): Question {
  return {
    id: `q${Math.random().toString(36).slice(2, 10)}`,
    type: 'SHORT_TEXT',
    label: 'New question',
    help: null,
    required: false,
    choices: [],
  }
}

/** Choices without blank lines, as the server expects them. */
function cleaned(draft: FormConfig): FormConfig {
  const choices = (list: string[]) => list.map((c) => c.trim()).filter(Boolean)
  return {
    ...draft,
    questions: draft.questions.map((q) => ({ ...q, choices: q.type === 'SINGLE_CHOICE' ? choices(q.choices) : [] })),
    service: draft.service && { ...draft.service, choices: choices(draft.service.choices) },
  }
}

/** The server's field errors as readable sentences, e.g. "Question 2 label is required". */
function messages(error: unknown): string[] {
  if (error instanceof ApiError && error.status === 400 && error.problem.errors) {
    return Object.entries(error.problem.errors).map(([key, message]) => {
      const field = key.replace(/^draft\./, '')
      const question = /^questions\[(\d+)\]\.(\w+)$/.exec(field)
      if (question) return `Question ${Number(question[1]) + 1} ${question[2]} ${message}`
      return `${field.replace('.', ' ')} ${message}`.replace(/^\w/, (c) => c.toUpperCase())
    })
  }
  if (error instanceof ApiError && error.status === 409 && error.problem.detail) return [error.problem.detail]
  return [`Could not save the form${reference(error)}.`]
}
