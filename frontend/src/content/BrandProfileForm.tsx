import { useState, type FormEvent } from 'react'
import { getBrandProfile, updateBrandProfile, type BrandProfile } from '../api/content.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { useLoad } from '../useLoad.ts'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Loading } from '../ui/Feedback.tsx'
import { ErrorText, Field } from '../ui/Field.tsx'
import { Panel } from '../ui/Panel.tsx'

const FIELDS = [
  { name: 'voice', label: 'Voice', max: 1000, hint: 'How you sound, e.g. warm, calm, a little playful.' },
  { name: 'audience', label: 'Audience', max: 1000, hint: 'Who you write for, e.g. young families in Warsaw.' },
  { name: 'preferences', label: 'Preferences', max: 2000, hint: 'Length, emoji, language, what to mention.' },
  { name: 'avoid', label: 'Avoid', max: 1000, hint: 'Words, phrases, or topics never to use.' },
] as const

type FieldName = (typeof FIELDS)[number]['name']

/** The workspace's brand profile, sent to the AI provider with every request. */
export function BrandProfilePanel({ workspaceId }: { workspaceId: string }) {
  const profile = useLoad((signal) => getBrandProfile(workspaceId, signal), [workspaceId])

  return (
    <Panel id="brand-profile-heading" title="Brand profile">
      <p className="text-sm text-muted-foreground">
        Drafts follow this profile. Everything here is sent to the AI provider with each request, so leave out
        anything private.
      </p>
      {profile.state.kind === 'loading' && <Loading>Loading the brand profile…</Loading>}
      {profile.state.kind === 'error' && (
        <LoadError error={profile.state.error} what="brand profile" onRetry={profile.reload} />
      )}
      {profile.state.kind === 'loaded' && (
        <ProfileForm key={profile.state.value.version} workspaceId={workspaceId} profile={profile.state.value} onSaved={profile.replace} />
      )}
    </Panel>
  )
}

function ProfileForm({
  workspaceId,
  profile,
  onSaved,
}: {
  workspaceId: string
  profile: BrandProfile
  onSaved: (profile: BrandProfile) => void
}) {
  const [values, setValues] = useState<Record<FieldName, string>>({
    voice: profile.voice ?? '',
    audience: profile.audience ?? '',
    preferences: profile.preferences ?? '',
    avoid: profile.avoid ?? '',
  })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  const changed = FIELDS.some(({ name }) => values[name] !== (profile[name] ?? ''))

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(noFormError)
    try {
      onSaved(
        await updateBrandProfile(workspaceId, profile.version, {
          voice: values.voice || null,
          audience: values.audience || null,
          preferences: values.preferences || null,
          avoid: values.avoid || null,
        }),
      )
    } catch (e) {
      setError(toFormError(e, 'save the brand profile'))
      setSaving(false)
    }
  }

  return (
    <form className="grid gap-5 md:grid-cols-2" onSubmit={submit} aria-label="Brand profile">
      {FIELDS.map(({ name, label, max, hint }) => (
        <Field key={name} label={label} error={error.fields[name]}>
          <Textarea
            value={values[name]}
            maxLength={max}
            rows={3}
            placeholder={hint}
            onChange={(e) => setValues((current) => ({ ...current, [name]: e.target.value }))}
          />
        </Field>
      ))}
      {error.message && (
        <div className="md:col-span-2">
          <ErrorText>{error.message}</ErrorText>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-3 md:col-span-2">
        <Button type="submit" disabled={saving || !changed}>
          Save profile
        </Button>
        {!changed && profile.updatedAt && <span className="text-sm text-muted-foreground">Saved</span>}
      </div>
    </form>
  )
}
