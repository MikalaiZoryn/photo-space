import { useEffect, useState } from 'react'
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

  return (
    <section aria-labelledby="status-heading" className="system-status">
      <h2 id="status-heading">System status</h2>
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
