import { useState } from 'react'
import { CalendarRange } from 'lucide-react'
import { ApiError } from '../api/client.ts'
import { getPlan, requestPlan, type PlanDetail } from '../api/content.ts'
import { navigate, paths } from '../routing.ts'
import { toFormError } from '../ui/formError.ts'
import { Button } from '@/components/ui/button'
import { ErrorText } from '../ui/Field.tsx'
import { Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { Panel } from '../ui/Panel.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { batchStatusLabels, batchStatusTones } from './labels.ts'

/** The session's content plan at a glance, with a way to create one. A missing plan reads as "none yet". */
export function SessionPlanPanel({ workspaceId, sessionId, archived }: { workspaceId: string; sessionId: string; archived: boolean }) {
  const plan = useLoad(
    (signal) =>
      getPlan(workspaceId, sessionId, signal).catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 404) return null
        throw e
      }),
    [workspaceId, sessionId],
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function create() {
    setBusy(true)
    setError(null)
    try {
      await requestPlan(workspaceId, sessionId)
      navigate(paths.contentPlan(sessionId))
    } catch (e) {
      setError(toFormError(e, 'plan posts').message ?? 'Could not plan posts.')
      setBusy(false)
    }
  }

  return (
    <Panel id="session-plan-heading" title="Content plan">
      {plan.state.kind === 'loading' && <Loading>Loading the plan…</Loading>}
      {plan.state.kind === 'error' && <LoadError error={plan.state.error} what="content plan" onRetry={plan.reload} />}
      {plan.state.kind === 'loaded' && plan.state.value === null && (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">
            No plan yet. One is proposed by itself once this session&apos;s photos are ready, or you can plan posts now.
          </p>
          {!archived && (
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={create}>
              <CalendarRange aria-hidden="true" />
              Plan posts
            </Button>
          )}
          {error && <ErrorText>{error}</ErrorText>}
        </div>
      )}
      {plan.state.kind === 'loaded' && plan.state.value !== null && <PlanSummary sessionId={sessionId} detail={plan.state.value} />}
    </Panel>
  )
}

function PlanSummary({ sessionId, detail }: { sessionId: string; detail: PlanDetail }) {
  const { plan } = detail
  return (
    <div className="flex flex-wrap items-center gap-3">
      {plan.status !== 'SUCCEEDED' && (
        <StatusBadge tone={batchStatusTones[plan.status]}>{batchStatusLabels[plan.status]}</StatusBadge>
      )}
      <p className="min-w-0 flex-1 text-sm text-muted-foreground">
        {plan.status === 'SUCCEEDED'
          ? `${plan.postCount} posts: ${plan.proposedCount} to review, ${plan.approvedCount} approved, ${plan.postedCount} posted.`
          : plan.status === 'PENDING'
            ? 'Planning posts from this session’s photos…'
            : plan.failureReason}
      </p>
      <Button asChild variant="outline" size="sm">
        <a href={paths.contentPlan(sessionId)}>Open plan</a>
      </Button>
    </div>
  )
}
