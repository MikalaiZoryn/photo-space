import { useEffect, useEffectEvent, useState } from 'react'

export type Loadable<T> = { kind: 'loading' } | { kind: 'error'; error: unknown } | { kind: 'loaded'; value: T }

type Key = string | number | boolean | null | undefined

/**
 * Runs `load` whenever the values in `deps` change and tracks its result. `deps` holds primitives only (IDs,
 * filters, page numbers): they are compared by value. A newer load aborts the previous one, so a slow response
 * can never overwrite a fresher one, and the state reads as loading until the result for the current deps
 * arrives. `reload` runs it again, for Retry buttons; `replace` swaps in a value the caller already has, such
 * as the response of a save.
 */
export function useLoad<T>(load: (signal: AbortSignal) => Promise<T>, deps: readonly Key[]) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<{ key: string; state: Loadable<T> } | null>(null)
  const depsKey = JSON.stringify(deps)
  const key = `${depsKey}#${attempt}`
  const runLoad = useEffectEvent(load)

  useEffect(() => {
    const controller = new AbortController()
    runLoad(controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setResult({ key, state: { kind: 'loaded', value } })
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setResult({ key, state: { kind: 'error', error } })
      })
    return () => controller.abort()
  }, [key])

  const state: Loadable<T> = result?.key === key ? result.state : { kind: 'loading' }
  return {
    state,
    reload: () => setAttempt((n) => n + 1),
    replace: (value: T) => setResult({ key, state: { kind: 'loaded', value } }),
  }
}
