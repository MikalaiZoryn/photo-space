import { useState } from 'react'
import { associateInquiry, dismissInquiry, getInquiry, reopenInquiry, type Inquiry } from '../api/inquiries.ts'
import { paths } from '../routing.ts'
import { formatDate, formatDay } from '../time.ts'
import { Button } from '@/components/ui/button'
import { ErrorAlert, Loading } from '../ui/Feedback.tsx'
import { FieldItem, Fields } from '../ui/Fields.tsx'
import { toFormError } from '../ui/formError.ts'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Panel } from '../ui/Panel.tsx'
import { RecordList } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { inquiryStatusLabels, inquiryStatusTones } from './labels.ts'

type Props = { workspaceId: string; inquiryId: string; timezone: string }

/**
 * One inquiry: the answers as submitted, clients with the same email, and the member's decision: link a client,
 * create one from the answers, or dismiss it. Each action is the server's to carry out, once.
 */
export function InquiryDetail({ workspaceId, inquiryId, timezone }: Props) {
  const inquiry = useLoad((signal) => getInquiry(workspaceId, inquiryId, signal), [workspaceId, inquiryId])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function act(action: (current: Inquiry) => Promise<Inquiry>, description: string) {
    if (inquiry.state.kind !== 'loaded') return
    setBusy(true)
    setError(null)
    try {
      inquiry.replace(await action(inquiry.state.value))
    } catch (e) {
      setError(toFormError(e, description).message ?? `Could not ${description}.`)
    } finally {
      setBusy(false)
    }
  }

  const back = { href: paths.inquiries(), label: 'Inquiries' }
  if (inquiry.state.kind === 'loading') return <Loading>Loading the inquiry…</Loading>
  if (inquiry.state.kind === 'error') return <LoadError error={inquiry.state.error} what="inquiry" onRetry={inquiry.reload} />
  const value = inquiry.state.value
  const open = value.status === 'NEW' || value.status === 'NEEDS_REVIEW'
  const utm = Object.entries(value.utm).filter(([, v]) => v)

  return (
    <section aria-labelledby="inquiry-heading" className="space-y-6">
      <PageHeader
        id="inquiry-heading"
        title={value.name}
        back={back}
        badges={<StatusBadge tone={inquiryStatusTones[value.status]}>{inquiryStatusLabels[value.status]}</StatusBadge>}
        description={`Sent ${formatDate(value.submittedAt, timezone)} through the ${value.source === 'EMBED' ? 'embedded' : 'hosted'} form (version ${value.formVersion})`}
      />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <Fields>
        <FieldItem label="Email">
          <a href={`mailto:${value.email}`}>{value.email}</a>
        </FieldItem>
        <FieldItem label="Phone">{value.phone ?? '—'}</FieldItem>
        <FieldItem label="Service">{value.service ?? '—'}</FieldItem>
        <FieldItem label="Preferred date">{value.preferredDate ? `${formatDay(value.preferredDate)} (not booked)` : '—'}</FieldItem>
        {value.referrer && <FieldItem label="Came from">{value.referrer}</FieldItem>}
        {utm.length > 0 && <FieldItem label="Campaign">{utm.map(([k, v]) => `${k}: ${v}`).join(', ')}</FieldItem>}
      </Fields>

      <Panel id="inquiry-answers-heading" title="Answers">
        <dl className="grid gap-4">
          {value.answers.map((answer) => (
            <div key={answer.questionId} className="space-y-1">
              <dt className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{answer.label}</dt>
              <dd className="text-sm break-words whitespace-pre-wrap">{answer.value}</dd>
            </div>
          ))}
        </dl>
      </Panel>

      <Panel id="inquiry-next-heading" title="Next step">
        {value.status === 'RECEIVED' && (
          <div className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
            Checking for clients with this email…
            <Button type="button" size="sm" variant="outline" onClick={inquiry.reload}>
              Refresh
            </Button>
          </div>
        )}
        {open && (
          <div className="space-y-4">
            {value.matches.length > 0 ? (
              <>
                <p className="text-sm">
                  {value.matches.length === 1 ? 'A client has' : 'These clients have'} the same email. Link the inquiry if
                  it is the same person; nothing about the client changes.
                </p>
                <RecordList label="Matching clients">
                  {value.matches.map((match) => (
                    <li key={match.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                      <div className="min-w-0 flex-1 text-sm">
                        <a className="font-medium hover:underline" href={paths.client(match.id)}>
                          {match.name}
                        </a>
                        <p className="text-muted-foreground">{[match.email, match.phone].filter(Boolean).join(' · ')}</p>
                      </div>
                      <Button
                        type="button"
                        size="sm"
                        disabled={busy}
                        onClick={() => act((c) => associateInquiry(workspaceId, c.id, c.version, match.id), 'link the client')}
                      >
                        Link to {match.name}
                      </Button>
                    </li>
                  ))}
                </RecordList>
              </>
            ) : (
              <p className="text-sm">No client has this email yet.</p>
            )}
            <p className="text-sm text-muted-foreground">
              Linking or creating a client also creates an inquiry session and a follow-up task. Nothing is booked or sent.
            </p>
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant={value.matches.length > 0 ? 'outline' : 'default'}
                disabled={busy}
                onClick={() => act((c) => associateInquiry(workspaceId, c.id, c.version, null), 'create the client')}
              >
                Create a new client from these answers
              </Button>
              <Button
                type="button"
                variant="ghost"
                disabled={busy}
                onClick={() => act((c) => dismissInquiry(workspaceId, c.id, c.version), 'dismiss the inquiry')}
              >
                Dismiss
              </Button>
            </div>
          </div>
        )}
        {value.status === 'ASSOCIATED' && (
          <ul className="grid gap-2 text-sm">
            {value.client && (
              <li>
                {value.clientCreated ? 'New client: ' : 'Client: '}
                <a className="font-medium text-accent-foreground hover:underline" href={paths.client(value.client.id)}>
                  {value.client.name}
                </a>
              </li>
            )}
            {value.session && (
              <li>
                Session:{' '}
                <a className="font-medium text-accent-foreground hover:underline" href={paths.session(value.session.id)}>
                  {value.session.title}
                </a>
              </li>
            )}
            {value.followUpTaskId && (
              <li>
                <a className="font-medium text-accent-foreground hover:underline" href={paths.taskEdit(value.followUpTaskId)}>
                  Follow-up task
                </a>
              </li>
            )}
          </ul>
        )}
        {value.status === 'DISMISSED' && (
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span className="text-muted-foreground">
              Dismissed{value.dismissedAt && ` ${formatDate(value.dismissedAt, timezone)}`}. The answers are kept.
            </span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => act((c) => reopenInquiry(workspaceId, c.id, c.version), 'reopen the inquiry')}
            >
              Reopen
            </Button>
          </div>
        )}
        {value.reviewTaskId && value.status !== 'ASSOCIATED' && (
          <p className="text-sm">
            <a className="font-medium text-accent-foreground hover:underline" href={paths.taskEdit(value.reviewTaskId)}>
              Review task
            </a>
          </p>
        )}
      </Panel>
    </section>
  )
}
