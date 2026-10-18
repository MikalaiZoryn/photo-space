import { useState, type FormEvent } from 'react'
import { getStudioSettings, updateStudioSettings, type StudioSettings } from '../api/content.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { useLoad } from '../useLoad.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Loading } from '../ui/Feedback.tsx'
import { CheckboxField, ErrorText, Field } from '../ui/Field.tsx'
import { Panel } from '../ui/Panel.tsx'

/** The weekly goal, automatic plans, the plan template, and the studio area. */
export function StudioSettingsPanel({ workspaceId }: { workspaceId: string }) {
  const settings = useLoad((signal) => getStudioSettings(workspaceId, signal), [workspaceId])

  return (
    <Panel id="studio-settings-heading" title="Plans and posting">
      <p className="text-sm text-muted-foreground">
        When a session&apos;s photos are ready, the Content Studio proposes a run of posts: a sneak peek, posts for the
        day the gallery is published, and later posts. You review and approve every one; nothing is posted for you.
      </p>
      {settings.state.kind === 'loading' && <Loading>Loading the settings…</Loading>}
      {settings.state.kind === 'error' && (
        <LoadError error={settings.state.error} what="Content Studio settings" onRetry={settings.reload} />
      )}
      {settings.state.kind === 'loaded' && (
        <SettingsForm
          key={settings.state.value.version}
          workspaceId={workspaceId}
          settings={settings.state.value}
          onSaved={settings.replace}
        />
      )}
    </Panel>
  )
}

function SettingsForm({
  workspaceId,
  settings,
  onSaved,
}: {
  workspaceId: string
  settings: StudioSettings
  onSaved: (settings: StudioSettings) => void
}) {
  const [weeklyGoal, setWeeklyGoal] = useState(String(settings.weeklyGoal))
  const [autoPlans, setAutoPlans] = useState(settings.autoPlans)
  const [sneakPeekDays, setSneakPeekDays] = useState(String(settings.sneakPeekDays))
  const [dripPosts, setDripPosts] = useState(String(settings.dripPosts))
  const [dripSpacingDays, setDripSpacingDays] = useState(String(settings.dripSpacingDays))
  const [studioArea, setStudioArea] = useState(settings.studioArea ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  const changed =
    Number(weeklyGoal) !== settings.weeklyGoal ||
    autoPlans !== settings.autoPlans ||
    Number(sneakPeekDays) !== settings.sneakPeekDays ||
    Number(dripPosts) !== settings.dripPosts ||
    Number(dripSpacingDays) !== settings.dripSpacingDays ||
    studioArea.trim() !== (settings.studioArea ?? '')

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(noFormError)
    try {
      onSaved(
        await updateStudioSettings(workspaceId, settings.version, {
          weeklyGoal: Number(weeklyGoal),
          autoPlans,
          sneakPeekDays: Number(sneakPeekDays),
          dripPosts: Number(dripPosts),
          dripSpacingDays: Number(dripSpacingDays),
          studioArea: studioArea.trim() || null,
        }),
      )
    } catch (e) {
      setError(toFormError(e, 'save the settings'))
      setSaving(false)
    }
  }

  return (
    <form className="space-y-5" onSubmit={submit} aria-label="Plans and posting">
      <div className="grid gap-5 md:grid-cols-2">
        <Field label="Feed posts per week" error={error.fields['settings.weeklyGoal']}>
          <Input type="number" min={1} max={14} value={weeklyGoal} onChange={(e) => setWeeklyGoal(e.target.value)} required />
        </Field>
        <Field label="Studio area" error={error.fields['settings.studioArea']}>
          <Input
            value={studioArea}
            maxLength={100}
            placeholder="e.g. Kraków"
            onChange={(e) => setStudioArea(e.target.value)}
          />
        </Field>
      </div>
      <p className="text-xs text-muted-foreground">
        3 to 5 feed posts a week is the usual sweet spot for reach. Captions are checked for the studio area, and the
        AI may mention it, so people searching for a photographer nearby find your posts.
      </p>
      <CheckboxField
        label="Propose a plan when a session's photos are ready"
        checked={autoPlans}
        onChange={setAutoPlans}
      />
      <fieldset className="grid gap-5 md:grid-cols-3">
        <legend className="mb-2 text-sm font-medium">Plan template</legend>
        <Field label="Sneak peek, days after the plan" error={error.fields['settings.sneakPeekDays']}>
          <Input type="number" min={0} max={3} value={sneakPeekDays} onChange={(e) => setSneakPeekDays(e.target.value)} required />
        </Field>
        <Field label="Later posts after the gallery" error={error.fields['settings.dripPosts']}>
          <Input type="number" min={0} max={8} value={dripPosts} onChange={(e) => setDripPosts(e.target.value)} required />
        </Field>
        <Field label="Days between later posts" error={error.fields['settings.dripSpacingDays']}>
          <Input
            type="number"
            min={1}
            max={30}
            value={dripSpacingDays}
            onChange={(e) => setDripSpacingDays(e.target.value)}
            required
          />
        </Field>
      </fieldset>
      {error.message && <ErrorText>{error.message}</ErrorText>}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={saving || !changed}>
          Save settings
        </Button>
        {!changed && settings.updatedAt && <span className="text-sm text-muted-foreground">Saved</span>}
      </div>
    </form>
  )
}
