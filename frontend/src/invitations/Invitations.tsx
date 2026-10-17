import { useEffect, useEffectEvent, useState, type FormEvent } from 'react'
import { Mail } from 'lucide-react'
import {
  createInvitation,
  listInvitations,
  resendInvitation,
  revokeInvitation,
  type Invitation,
} from '../api/invitations.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { formatDate } from '../time.ts'
import { EmptyState, ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { ErrorText } from '../ui/Field.tsx'
import { noFormError, toFormError, type FormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { Panel } from '../ui/Panel.tsx'
import { RecordList } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'

const POLL_MS = 3000

type Props = { workspaceId: string; timezone: string }

/**
 * Owners invite people by email and manage open invitations. The email goes out in the background, so a new
 * invitation shows "Sending…" until the worker reports back.
 */
export function Invitations({ workspaceId, timezone }: Props) {
  const invitations = useLoad((signal) => listInvitations(workspaceId, signal), [workspaceId])
  const [email, setEmail] = useState('')
  const [formError, setFormError] = useState<FormError>(noFormError)
  const [rowError, setRowError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const refresh = useEffectEvent(async () => {
    try {
      invitations.replace(await listInvitations(workspaceId))
    } catch {
      // The next poll tries again.
    }
  })
  const sending =
    invitations.state.kind === 'loaded' && invitations.state.value.some((i) => i.emailStatus === 'QUEUED')
  useEffect(() => {
    if (!sending) return
    const timer = setInterval(() => void refresh(), POLL_MS)
    return () => clearInterval(timer)
  }, [sending])

  async function invite(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setFormError(noFormError)
    try {
      await createInvitation(workspaceId, email.trim())
      setEmail('')
      invitations.reload()
    } catch (e) {
      setFormError(toFormError(e, 'send the invitation'))
    } finally {
      setBusy(false)
    }
  }

  async function act(action: () => Promise<unknown>, description: string) {
    setBusy(true)
    setRowError(null)
    try {
      await action()
      invitations.reload()
    } catch (e) {
      setRowError(toFormError(e, description).message ?? `Could not ${description}.`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Panel id="invitations-heading" title="Invitations" className="mt-6">
      <form onSubmit={invite} aria-label="Invite by email" className="grid gap-2">
        <label htmlFor="invite-email" className="text-sm font-medium">
          Invite someone by email
        </label>
        <div className="flex flex-col gap-2 sm:flex-row">
          <Input
            id="invite-email"
            type="email"
            required
            maxLength={254}
            placeholder="name@example.com"
            className="flex-1"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            aria-invalid={formError.fields.email ? true : undefined}
          />
          <Button type="submit" disabled={busy}>
            <Mail aria-hidden="true" />
            Send invitation
          </Button>
        </div>
        <p className="text-xs text-muted-foreground">
          They join as a member after signing in with the Google account for that address. The link works for 7 days.
        </p>
        {formError.fields.email && <ErrorText>{formError.fields.email}</ErrorText>}
        {formError.message && <ErrorText>{formError.message}</ErrorText>}
      </form>
      {rowError && <ErrorAlert>{rowError}</ErrorAlert>}
      {invitations.state.kind === 'loading' && <Loading>Loading invitations…</Loading>}
      {invitations.state.kind === 'error' && (
        <LoadError error={invitations.state.error} what="invitations" onRetry={invitations.reload} />
      )}
      {invitations.state.kind === 'loaded' &&
        (invitations.state.value.length === 0 ? (
          <EmptyState>No open invitations.</EmptyState>
        ) : (
          <RecordList label="Open invitations">
            {invitations.state.value.map((invitation) => (
              <li key={invitation.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
                <div className="min-w-0 flex-1 text-sm">
                  <p className="truncate font-medium">{invitation.email}</p>
                  <p className="text-muted-foreground">{describe(invitation, timezone)}</p>
                </div>
                {invitation.state === 'EXPIRED' && <StatusBadge tone="warning">Expired</StatusBadge>}
                {invitation.emailStatus === 'FAILED' && <StatusBadge tone="danger">Email failed</StatusBadge>}
                <div className="flex gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={busy || invitation.emailStatus === 'QUEUED'}
                    onClick={() => act(() => resendInvitation(workspaceId, invitation.id), 'resend the invitation')}
                  >
                    Resend
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="destructive"
                    disabled={busy}
                    onClick={() => act(() => revokeInvitation(workspaceId, invitation.id), 'revoke the invitation')}
                  >
                    Revoke
                  </Button>
                </div>
              </li>
            ))}
          </RecordList>
        ))}
    </Panel>
  )
}

function describe(invitation: Invitation, timezone: string): string {
  const email =
    invitation.emailStatus === 'QUEUED'
      ? 'Sending…'
      : invitation.emailStatus === 'FAILED'
        ? 'The email could not be sent; resend to try again'
        : `Email sent ${invitation.emailSentAt ? formatDate(invitation.emailSentAt, timezone) : ''}`.trim()
  const expiry = `${invitation.state === 'EXPIRED' ? 'expired' : 'expires'} ${formatDate(invitation.expiresAt, timezone)}`
  return `${email} · ${expiry}`
}
