export type Health = {
  status: 'UP' | 'DOWN'
  database: 'UP' | 'DOWN'
}

/** Calls the backend health endpoint. A 503 still carries a valid body describing what is down. */
export async function fetchHealth(signal?: AbortSignal): Promise<Health> {
  const response = await fetch('/api/health', { signal, headers: { Accept: 'application/json' } })
  if (!response.ok && response.status !== 503) {
    throw new Error(`Health check failed with HTTP ${response.status}`)
  }
  return (await response.json()) as Health
}
