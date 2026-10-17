import { useState, type ReactNode } from 'react'
import { acceptInvitation, fetchInvitation, type InvitationPreview } from '../api/invitations.ts'
import { ApiError } from '../api/client.ts'
import { Button } from '@/components/ui/button'
import { CenteredLayout } from '../layout/CenteredLayout.tsx'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { useLoad } from '../useLoad.ts'

type Props = {
  token: string
  /** The signed-in user's email, to explain a mismatch. */
  signedInAs: string | null
  /** Done with the invitation: joined `workspaceId`, or set it aside (null). */
  onDone: (workspaceId: string | null) => void
  onSignOut: () => void
}

/** The page an invitation link opens once its owner is signed in: what they are invited to, and Accept. */
export function InvitePage({ token, signedInAs, onDone, onSignOut }: Props) {
  const invitation = useLoad((signal) => fetchInvitation(token, signal), [token])

  return (
    <CenteredLayout status={false}>
      <section aria-labelledby="invite-heading" className="space-y-6 rounded-2xl border bg-card p-6 shadow-sm sm:p-8">
        {invitation.state.kind === 'loading' && <Loading>Loading the invitation…</Loading>}
        {invitation.state.kind === 'error' &&
          (invitation.state.error instanceof ApiError && invitation.state.error.status === 404 ? (
            <Card title="This invitation link does not work">
              <p className="text-sm text-muted-foreground">
                It may be mistyped, or a newer invitation email replaced it. Use the link in the most recent email.
              </p>
              <Button type="button" variant="outline" onClick={() => onDone(null)}>
                Go to your workspaces
              </Button>
            </Card>
          ) : (
            <LoadError error={invitation.state.error} what="the invitation" onRetry={invitation.reload} />
          ))}
        {invitation.state.kind === 'loaded' && (
          <Details
            token={token}
            invitation={invitation.state.value}
            signedInAs={signedInAs}
            onDone={onDone}
            onSignOut={onSignOut}
          />
        )}
      </section>
    </CenteredLayout>
  )
}

function Details({ token, invitation, signedInAs, onDone, onSignOut }: Props & { invitation: InvitationPreview }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const title = `Join ${invitation.workspaceName}`
  const notNow = (
    <Button type="button" variant="ghost" onClick={() => onDone(null)}>
      Not now
    </Button>
  )

  async function accept() {
    setBusy(true)
    setError(null)
    try {
      const workspace = await acceptInvitation(token)
      onDone(workspace.id)
    } catch (e) {
      setError(toFormError(e, 'accept the invitation').message ?? 'Could not accept the invitation.')
      setBusy(false)
    }
  }

  const intro = (
    <p className="text-sm text-muted-foreground">
      {invitation.invitedBy ?? 'A workspace owner'} invited <strong className="text-foreground">{invitation.email}</strong> to
      join this workspace as a member.
    </p>
  )

  if (invitation.state === 'ACCEPTED') {
    return (
      <Card title="This invitation was already used">
        <p className="text-sm text-muted-foreground">If you accepted it, the workspace is in your list.</p>
        <Button type="button" variant="outline" onClick={() => onDone(null)}>
          Go to your workspaces
        </Button>
      </Card>
    )
  }
  if (invitation.state === 'REVOKED' || invitation.state === 'EXPIRED') {
    return (
      <Card title={invitation.state === 'REVOKED' ? 'This invitation was withdrawn' : 'This invitation has expired'}>
        <p className="text-sm text-muted-foreground">
          Ask the owner of {invitation.workspaceName} to {invitation.state === 'REVOKED' ? 'invite you again' : 'send it again'}.
        </p>
        <Button type="button" variant="outline" onClick={() => onDone(null)}>
          Go to your workspaces
        </Button>
      </Card>
    )
  }
  if (invitation.alreadyMember) {
    return (
      <Card title={title}>
        <p className="text-sm text-muted-foreground">You already belong to {invitation.workspaceName}.</p>
        <Button type="button" variant="outline" onClick={() => onDone(null)}>
          Go to your workspaces
        </Button>
      </Card>
    )
  }
  if (!invitation.emailMatches) {
    return (
      <Card title={title}>
        {intro}
        <ErrorAlert>
          You are signed in as {signedInAs ?? 'another account'}. Sign out, then sign in with the Google account for{' '}
          {invitation.email} to accept.
        </ErrorAlert>
        <div className="flex flex-wrap gap-2">
          <Button type="button" onClick={onSignOut}>
            Sign out
          </Button>
          {notNow}
        </div>
      </Card>
    )
  }
  return (
    <Card title={title}>
      {intro}
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="flex flex-wrap gap-2">
        <Button type="button" disabled={busy} onClick={accept}>
          Accept invitation
        </Button>
        {notNow}
      </div>
    </Card>
  )
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <>
      <h2 id="invite-heading" className="text-2xl font-semibold">
        {title}
      </h2>
      {children}
    </>
  )
}
