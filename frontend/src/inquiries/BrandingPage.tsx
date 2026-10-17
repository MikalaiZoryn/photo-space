import { useState, type FormEvent } from 'react'
import { ImagePlus, Trash2 } from 'lucide-react'
import { ACCEPTED_TYPES } from '../api/assets.ts'
import {
  BRAND_FONTS,
  getAsset,
  getBranding,
  MAX_LOGO_BYTES,
  publicView,
  updateBranding,
  uploadLogo,
  type Branding,
  type BrandingInput,
} from '../api/branding.ts'
import { paths } from '../routing.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { ErrorText, Field, FormActions, formCard } from '../ui/Field.tsx'
import { noFormError, reference, toFormError, type FormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { useLoad } from '../useLoad.ts'
import { brandStyle, fontLabels } from './brand.ts'
import { BrandHeader } from './BrandHeader.tsx'

const POLL_MS = 1000
const POLL_LIMIT = 60

type Props = { workspaceId: string }

/** How the public inquiry pages look: name, logo, colors, font, and contact details. Any member may edit it. */
export function BrandingPage({ workspaceId }: Props) {
  const branding = useLoad((signal) => getBranding(workspaceId, signal), [workspaceId])
  return (
    <section aria-labelledby="branding-heading">
      <PageHeader
        id="branding-heading"
        title="Branding"
        description="What visitors of your inquiry form see. There is no custom CSS or HTML."
        back={{ href: paths.inquiryForm(), label: 'Inquiry form' }}
      />
      {branding.state.kind === 'loading' && <Loading>Loading branding…</Loading>}
      {branding.state.kind === 'error' && <LoadError error={branding.state.error} what="branding" onRetry={branding.reload} />}
      {branding.state.kind === 'loaded' && (
        <BrandingForm workspaceId={workspaceId} branding={branding.state.value} onSaved={branding.replace} />
      )}
    </section>
  )
}

function BrandingForm({
  workspaceId,
  branding,
  onSaved,
}: {
  workspaceId: string
  branding: Branding
  onSaved: (branding: Branding) => void
}) {
  const [input, setInput] = useState<BrandingInput>({
    displayName: branding.displayName,
    logoAssetId: branding.logo?.id ?? null,
    primaryColor: branding.primaryColor,
    accentColor: branding.accentColor,
    font: branding.font,
    email: branding.email,
    phone: branding.phone,
    description: branding.description,
  })
  const [logoUrl, setLogoUrl] = useState<string | null>(branding.logo?.url ?? null)
  const [logoState, setLogoState] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  const [saved, setSaved] = useState(false)
  const set = (change: Partial<BrandingInput>) => {
    setInput((current) => ({ ...current, ...change }))
    setSaved(false)
  }

  async function pickLogo(file: File | undefined) {
    if (!file) return
    if (!ACCEPTED_TYPES.includes(file.type)) {
      setLogoState('The logo must be a JPEG or PNG image.')
      return
    }
    if (file.size > MAX_LOGO_BYTES) {
      setLogoState('The logo must be at most 5 MB.')
      return
    }
    setLogoState('Uploading…')
    try {
      let asset = await uploadLogo(workspaceId, file)
      for (let i = 0; i < POLL_LIMIT && (asset.status === 'PENDING_UPLOAD' || asset.status === 'PROCESSING'); i++) {
        setLogoState('Processing…')
        await new Promise((resolve) => setTimeout(resolve, POLL_MS))
        asset = await getAsset(workspaceId, asset.id)
      }
      if (asset.status === 'READY') {
        set({ logoAssetId: asset.id })
        setLogoUrl(asset.thumbnailUrl)
        setLogoState('Logo ready. Save to use it.')
      } else if (asset.status === 'REJECTED' || asset.status === 'FAILED') {
        setLogoState(asset.failureReason ?? 'This file could not be used as a logo.')
      } else {
        setLogoState('The logo is still processing. Try again in a moment.')
      }
    } catch (e) {
      setLogoState(`The logo could not be uploaded${reference(e)}.`)
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError(noFormError)
    try {
      const result = await updateBranding(workspaceId, branding.version, {
        ...input,
        displayName: input.displayName?.trim() || null,
        email: input.email?.trim() || null,
        phone: input.phone?.trim() || null,
        description: input.description?.trim() || null,
      })
      setSaved(true)
      onSaved(result)
    } catch (err) {
      setError(toFormError(err, 'save the branding'))
    } finally {
      setSaving(false)
    }
  }

  const preview = publicView({ ...branding, ...input, displayName: input.displayName?.trim() || null }, input.logoAssetId ? logoUrl : null)

  return (
    <div className="grid gap-6 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <form onSubmit={submit} className={formCard} noValidate>
        {error.message && (
          <div className="md:col-span-2">
            <ErrorAlert>{error.message}</ErrorAlert>
          </div>
        )}
        {saved && (
          <p role="status" className="text-sm text-tone-success md:col-span-2">
            Branding saved.
          </p>
        )}
        <Field label="Business name" error={error.fields.displayName} className="md:col-span-2">
          <Input
            value={input.displayName ?? ''}
            placeholder={branding.workspaceName}
            maxLength={100}
            onChange={(e) => set({ displayName: e.target.value })}
          />
        </Field>
        <div className="grid gap-2 md:col-span-2">
          <span className="text-sm font-medium">Logo</span>
          <div className="flex flex-wrap items-center gap-3">
            {input.logoAssetId && logoUrl ? (
              <img src={logoUrl} alt="Current logo" className="h-14 w-auto max-w-40 rounded border bg-white object-contain p-1" />
            ) : (
              <span className="text-sm text-muted-foreground">No logo</span>
            )}
            <label className="inline-flex h-9 cursor-pointer items-center gap-2 rounded-lg border bg-card px-3 text-sm font-medium shadow-xs hover:bg-muted">
              <ImagePlus className="size-4" aria-hidden="true" />
              Upload logo
              <input
                type="file"
                accept={ACCEPTED_TYPES.join(',')}
                className="sr-only"
                onChange={(e) => {
                  void pickLogo(e.target.files?.[0])
                  e.target.value = ''
                }}
              />
            </label>
            {input.logoAssetId && (
              <Button type="button" variant="ghost" size="sm" onClick={() => set({ logoAssetId: null })}>
                <Trash2 aria-hidden="true" />
                Remove logo
              </Button>
            )}
          </div>
          <p className="text-xs text-muted-foreground" role="status">
            {logoState ?? 'JPEG or PNG, up to 5 MB. It is shown at most 480 px wide, on a white background.'}
          </p>
          {error.fields.logoAssetId && <p className="text-sm text-destructive">{error.fields.logoAssetId}</p>}
        </div>
        <ColorField label="Primary color" value={input.primaryColor} error={error.fields.primaryColor} onChange={(primaryColor) => set({ primaryColor })} />
        <ColorField label="Accent color" value={input.accentColor} error={error.fields.accentColor} onChange={(accentColor) => set({ accentColor })} />
        <Field label="Font" error={error.fields.font}>
          <NativeSelect className="w-full" value={input.font} onChange={(e) => set({ font: e.target.value as BrandingInput['font'] })}>
            {BRAND_FONTS.map((font) => (
              <NativeSelectOption key={font} value={font}>
                {fontLabels[font]}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <Field label="Contact email (optional)" error={error.fields.email}>
          <Input type="email" value={input.email ?? ''} maxLength={320} onChange={(e) => set({ email: e.target.value })} />
        </Field>
        <Field label="Contact phone (optional)" error={error.fields.phone}>
          <Input type="tel" value={input.phone ?? ''} maxLength={50} onChange={(e) => set({ phone: e.target.value })} />
        </Field>
        <Field label="Short description (optional)" error={error.fields.description} className="md:col-span-2">
          <Textarea rows={3} maxLength={500} value={input.description ?? ''} onChange={(e) => set({ description: e.target.value })} />
        </Field>
        <FormActions>
          <Button type="submit" disabled={saving}>
            {saving ? 'Saving…' : 'Save branding'}
          </Button>
        </FormActions>
      </form>
      <section aria-labelledby="branding-preview-heading" className="space-y-3">
        <h4 id="branding-preview-heading" className="text-base font-semibold">
          Preview
        </h4>
        <div style={brandStyle(preview)} className="space-y-5 rounded-2xl border border-dashed bg-muted/40 p-5">
          <BrandHeader branding={preview} />
          <div className="rounded-xl border border-t-4 border-t-[var(--brand-primary)] bg-card p-4 text-sm">
            <p className="font-semibold">Tell us about your session</p>
            <p className="mt-1 text-muted-foreground">
              Required fields are marked with <span className="text-[var(--brand-accent)]">*</span>.
            </p>
            <span className="mt-3 inline-flex h-10 items-center rounded-lg bg-[var(--brand-primary)] px-5 font-semibold text-[var(--brand-on-primary)]">
              Send inquiry
            </span>
          </div>
        </div>
      </section>
    </div>
  )
}

function ColorField({
  label,
  value,
  error,
  onChange,
}: {
  label: string
  value: string
  error?: string
  onChange: (value: string) => void
}) {
  const valid = /^#[0-9a-fA-F]{6}$/.test(value)
  return (
    <div className="grid content-start gap-1.5">
      <span className="text-sm font-medium">{label}</span>
      <span className="flex items-center gap-2">
        <input
          type="color"
          aria-label={`${label} picker`}
          className="h-9 w-12 shrink-0 cursor-pointer rounded-md border bg-card p-1"
          value={valid ? value.toLowerCase() : '#000000'}
          onChange={(e) => onChange(e.target.value)}
        />
        <Input
          aria-label={`${label} (hex)`}
          aria-invalid={error ? true : undefined}
          className="font-mono"
          value={value}
          maxLength={7}
          onChange={(e) => onChange(e.target.value)}
        />
      </span>
      {error && <ErrorText>{error}</ErrorText>}
    </div>
  )
}
