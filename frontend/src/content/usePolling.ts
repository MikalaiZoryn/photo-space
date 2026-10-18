import { useEffect, useEffectEvent } from 'react'

/** How often a pending plan or post rewrite is checked again. */
export const PLAN_POLL_MS = 2000

/** Calls `check` every `PLAN_POLL_MS` while `active`. A failed check is ignored; the next one tries again. */
export function usePolling(active: boolean, check: () => Promise<void>) {
  const run = useEffectEvent(async () => {
    try {
      await check()
    } catch {
      // Try again on the next tick.
    }
  })
  useEffect(() => {
    if (!active) return
    const timer = setInterval(() => void run(), PLAN_POLL_MS)
    return () => clearInterval(timer)
  }, [active])
}
