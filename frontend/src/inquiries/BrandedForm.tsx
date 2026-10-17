import { useState, type FormEvent } from 'react'
import type { PublicBranding } from '../api/branding.ts'
import type { FormConfig, Question } from '../api/inquiryForm.ts'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { ErrorAlert } from '../ui/Feedback.tsx'
import { Field } from '../ui/Field.tsx'
import { brandStyle } from './brand.ts'
import { BrandHeader } from './BrandHeader.tsx'

export type FormValues = { answers: Record<string, string>; service: string; website: string }

type Props = {
  form: FormConfig
  branding: PublicBranding
  /** Minimal chrome, for the embed. */
  compact?: boolean
  /** Absent in the builder's preview: the inputs work, but there is no submit button. */
  onSubmit?: (values: FormValues) => void
  submitting?: boolean
  /** Server messages by field: `answers.<question id>` or `service`. */
  fieldErrors?: Record<string, string>
  error?: string | null
}

/**
 * The inquiry form as visitors see it, in the workspace's branding. Checks are the server's: it validates every
 * answer against the published version, so this form only shows what it says.
 */
export function BrandedForm({ form, branding, compact, onSubmit, submitting, fieldErrors = {}, error }: Props) {
  const [values, setValues] = useState<FormValues>({ answers: {}, service: '', website: '' })
  const setAnswer = (id: string, value: string) =>
    setValues((current) => ({ ...current, answers: { ...current.answers, [id]: value } }))

  function submit(e: FormEvent) {
    e.preventDefault()
    onSubmit?.(values)
  }

  return (
    <div style={brandStyle(branding)} className="space-y-6">
      <BrandHeader branding={branding} compact={compact} />
      <form
        noValidate
        onSubmit={submit}
        aria-labelledby="inquiry-form-title"
        className="space-y-5 rounded-2xl border border-t-4 border-t-[var(--brand-primary)] bg-card p-5 text-card-foreground shadow-sm sm:p-8"
      >
        <div className="space-y-2">
          <h1 id="inquiry-form-title" className="text-2xl font-semibold sm:text-3xl">
            {form.title}
          </h1>
          {form.intro && <p className="text-sm whitespace-pre-line text-muted-foreground">{form.intro}</p>}
        </div>
        {error && <ErrorAlert>{error}</ErrorAlert>}
        {form.service && (
          <Field label={label(form.service.label, form.service.required)} error={fieldErrors.service}>
            <NativeSelect
              className="w-full"
              value={values.service}
              aria-invalid={fieldErrors.service ? true : undefined}
              onChange={(e) => setValues((current) => ({ ...current, service: e.target.value }))}
            >
              <NativeSelectOption value="">Choose…</NativeSelectOption>
              {form.service.choices.map((choice) => (
                <NativeSelectOption key={choice} value={choice}>
                  {choice}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        )}
        {form.questions.map((question) => (
          <QuestionField
            key={question.id}
            question={question}
            value={values.answers[question.id] ?? ''}
            error={fieldErrors[`answers.${question.id}`]}
            onChange={(value) => setAnswer(question.id, value)}
          />
        ))}
        {/* The honeypot: hidden from people and screen readers, filled in only by scripts. */}
        <div aria-hidden="true" className="absolute -left-[10000px] h-px w-px overflow-hidden">
          <label>
            Leave this field empty
            <input
              type="text"
              name="website"
              tabIndex={-1}
              autoComplete="off"
              value={values.website}
              onChange={(e) => setValues((current) => ({ ...current, website: e.target.value }))}
            />
          </label>
        </div>
        {onSubmit && (
          <button
            type="submit"
            disabled={submitting}
            className="inline-flex h-11 w-full items-center justify-center rounded-lg bg-[var(--brand-primary)] px-6 text-sm font-semibold text-[var(--brand-on-primary)] shadow-xs transition-opacity hover:opacity-90 focus-visible:ring-3 focus-visible:ring-[var(--brand-accent)]/50 focus-visible:outline-none disabled:opacity-60 sm:w-auto"
          >
            {submitting ? 'Sending…' : 'Send inquiry'}
          </button>
        )}
      </form>
    </div>
  )
}

function label(text: string, required: boolean) {
  return (
    <span>
      {text}
      {required ? (
        <span aria-hidden="true" className="text-[var(--brand-accent)]">
          {' '}
          *
        </span>
      ) : (
        <span className="font-normal text-muted-foreground"> (optional)</span>
      )}
    </span>
  )
}

function QuestionField({
  question,
  value,
  error,
  onChange,
}: {
  question: Question
  value: string
  error?: string
  onChange: (value: string) => void
}) {
  const common = {
    value,
    required: question.required,
    'aria-invalid': error ? true : undefined,
    'aria-required': question.required || undefined,
  }
  let control
  switch (question.type) {
    case 'LONG_TEXT':
      control = <Textarea rows={5} maxLength={5000} {...common} onChange={(e) => onChange(e.target.value)} />
      break
    case 'SINGLE_CHOICE':
      control = (
        <NativeSelect className="w-full" {...common} onChange={(e) => onChange(e.target.value)}>
          <NativeSelectOption value="">Choose…</NativeSelectOption>
          {question.choices.map((choice) => (
            <NativeSelectOption key={choice} value={choice}>
              {choice}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      )
      break
    default: {
      const types = { SHORT_TEXT: 'text', EMAIL: 'email', PHONE: 'tel', DATE: 'date' } as const
      const autoComplete = { name: 'name', email: 'email', phone: 'tel' }[question.id]
      control = (
        <Input
          type={types[question.type]}
          maxLength={question.type === 'SHORT_TEXT' ? 200 : undefined}
          autoComplete={autoComplete}
          {...common}
          onChange={(e) => onChange(e.target.value)}
        />
      )
    }
  }
  return (
    <Field label={label(question.label, question.required)} error={error}>
      {question.help && <span className="text-xs text-muted-foreground">{question.help}</span>}
      {control}
    </Field>
  )
}
