import { useState, type FormEvent } from 'react'
import {
  changeDraftStatus,
  getDraft,
  parseHashtags,
  postText,
  updateDraft,
  type ContentDraft,
  type DraftVersion,
} from '../api/content.ts'
import { formatDate } from '../time.ts'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { BadgeCheck, Check, Copy, History, Info, Undo2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { ErrorText, Field } from '../ui/Field.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { draftStatusLabels, draftStatusTones } from './labels.ts'

/** Instagram's limit for a whole caption, hashtags included. */
const MAX_POST = 2200

type Props = {
  workspaceId: string
  timezone: string
  number: number
  draft: ContentDraft
  onChange: (draft: ContentDraft) => void
}

/**
 * One draft: edit and save (a new version), approve the saved version, and copy it once approved. Approval is
 * the only way to mark text ready; it never posts anything.
 */
export function DraftEditor({ workspaceId, timezone, number, draft, onChange }: Props) {
  const [caption, setCaption] = useState(draft.caption)
  const [callToAction, setCallToAction] = useState(draft.callToAction ?? '')
  const [hashtags, setHashtags] = useState(draft.hashtags.map((tag) => `#${tag}`).join(' '))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<FormError>(noFormError)
  const [copied, setCopied] = useState(false)
  const [history, setHistory] = useState<DraftVersion[] | null>(null)
  const edited = { caption: caption.trim(), callToAction: callToAction.trim() || null, hashtags: parseHashtags(hashtags) }
  const dirty =
    edited.caption !== draft.caption ||
    edited.callToAction !== draft.callToAction ||
    edited.hashtags.join(' ') !== draft.hashtags.join(' ')
  const length = postText(edited).length
  const approved = draft.status === 'APPROVED'

  async function run(action: () => Promise<ContentDraft>, description: string) {
    setBusy(true)
    setError(noFormError)
    try {
      onChange(await action())
    } catch (e) {
      setError(toFormError(e, description))
    } finally {
      setBusy(false)
    }
  }

  function save(event: FormEvent) {
    event.preventDefault()
    void run(() => updateDraft(workspaceId, draft.id, draft.version, edited), 'save the draft')
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(postText(draft))
      setCopied(true)
    } catch {
      setError({ fields: {}, message: 'Could not copy. Select the text and copy it yourself.' })
    }
  }

  async function toggleHistory() {
    if (history) {
      setHistory(null)
      return
    }
    try {
      setHistory((await getDraft(workspaceId, draft.id)).versions)
    } catch (e) {
      setError(toFormError(e, 'load the history'))
    }
  }

  function loadVersion(version: DraftVersion) {
    setCaption(version.caption)
    setCallToAction(version.callToAction ?? '')
    setHashtags(version.hashtags.map((tag) => `#${tag}`).join(' '))
  }

  return (
    <form
      onSubmit={save}
      aria-label={`Draft ${number}`}
      className="space-y-4 rounded-xl border bg-card p-4 shadow-xs sm:p-6"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-base font-semibold">Draft {number}</h4>
        <StatusBadge tone={draftStatusTones[draft.status]}>{draftStatusLabels[draft.status]}</StatusBadge>
        <span className="text-xs text-muted-foreground">
          Version {draft.version} · {draft.source === 'GENERATED' ? 'as written by AI' : 'edited'}
        </span>
      </div>
      <Field label="Caption" error={error.fields.caption}>
        <Textarea value={caption} rows={5} maxLength={MAX_POST} onChange={(e) => setCaption(e.target.value)} required />
      </Field>
      <div className="grid gap-4 md:grid-cols-2">
        <Field label="Call to action" error={error.fields.callToAction}>
          <Input value={callToAction} maxLength={300} onChange={(e) => setCallToAction(e.target.value)} />
        </Field>
        <Field label="Hashtags" error={error.fields.hashtags}>
          <Input value={hashtags} onChange={(e) => setHashtags(e.target.value)} placeholder="#family #goldenhour" />
        </Field>
      </div>
      <p className={length > MAX_POST ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}>
        {length} / {MAX_POST} characters with the call to action and hashtags
      </p>
      {draft.checks.length > 0 && (
        <ul className="space-y-1 rounded-lg bg-tone-warning-bg p-3 text-sm text-tone-warning" aria-label="Review hints">
          {draft.checks.map((check) => (
            <li key={check} className="flex gap-2">
              <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              {check}
            </li>
          ))}
        </ul>
      )}
      {error.message && <ErrorText>{error.message}</ErrorText>}
      <div className="flex flex-wrap items-center gap-2">
        <Button type="submit" variant="secondary" disabled={busy || !dirty}>
          Save changes
        </Button>
        {approved ? (
          <>
            <Button type="button" disabled={dirty} onClick={copy}>
              {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
              {copied ? 'Copied' : 'Copy for Instagram'}
            </Button>
            <Button
              type="button"
              variant="ghost"
              disabled={busy}
              onClick={() => run(() => changeDraftStatus(workspaceId, draft.id, draft.version, 'DRAFT'), 'withdraw the approval')}
            >
              <Undo2 aria-hidden="true" />
              Withdraw approval
            </Button>
          </>
        ) : (
          <Button
            type="button"
            disabled={busy || dirty}
            title={dirty ? 'Save your changes before approving' : undefined}
            onClick={() => run(() => changeDraftStatus(workspaceId, draft.id, draft.version, 'APPROVED'), 'approve the draft')}
          >
            <BadgeCheck aria-hidden="true" />
            Approve
          </Button>
        )}
        <Button type="button" variant="ghost" className="ml-auto" onClick={toggleHistory} aria-expanded={history !== null}>
          <History aria-hidden="true" />
          {history ? 'Hide history' : 'History'}
        </Button>
      </div>
      {dirty && !approved && <p className="text-xs text-muted-foreground">Save your changes before approving.</p>}
      {approved && draft.approvedAt && (
        <p className="text-xs text-muted-foreground">
          Version {draft.approvedVersion} approved on {formatDate(draft.approvedAt, timezone)}. Editing it needs a new
          approval.
        </p>
      )}
      {history && (
        <ol className="space-y-3 border-t pt-4" aria-label="Versions">
          {history.map((version) => (
            <li key={version.version} className="space-y-1 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">
                  Version {version.version} · {version.source === 'GENERATED' ? 'AI' : 'edited'} ·{' '}
                  {formatDate(version.createdAt, timezone)}
                </span>
                {version.version !== draft.version && (
                  <Button type="button" variant="outline" size="sm" onClick={() => loadVersion(version)}>
                    Use this text
                  </Button>
                )}
              </div>
              <p className="whitespace-pre-wrap text-muted-foreground">{postText(version)}</p>
            </li>
          ))}
        </ol>
      )}
    </form>
  )
}
