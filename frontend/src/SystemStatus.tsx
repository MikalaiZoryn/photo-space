import { useEffect, useState } from 'react'
import { cn } from 'cn'
import { fetchHealth, type Health } from './api/health.ts'

type HealthState =
  | { kind: 'loading' }
  | { kind: 'loaded'; health: Health }
  | { kind: 'error' }

export function SystemStatus() {
  const [state, setState] = useState<HealthState>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchHealth(controller.signal)
      .then((health) => setState({ kind: 'loaded', health }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ kind: 'error' })
      })
    return () => controller.abort()
  }, [])

  const healthy = state.kind === 'loaded' && state.health.status === 'UP' && state.health.database === 'UP'
  return (
    <section aria-labelledby="status-heading" className="flex items-center gap-2 text-xs text-muted-foreground">
      <h2 id="status-heading" className="sr-only">
        System status
      </h2>
      <span
        aria-hidden="true"
        className={cn(
          'size-2 rounded-full',
          state.kind === 'loading' ? 'bg-muted-foreground/40' : healthy ? 'bg-tone-success' : 'bg-tone-danger',
        )}
      />
      <p role="status">{describe(state)}</p>
    </section>
  )
}

function describe(state: HealthState): string {
  switch (state.kind) {
    case 'loading':
      return 'Checking backend…'
    case 'error':
      return 'Backend unreachable'
    case 'loaded':
      return `Backend ${state.health.status}, database ${state.health.database}`
  }
}
