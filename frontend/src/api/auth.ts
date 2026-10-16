import { api, ApiError } from './client.ts'

export type Me = {
  id: string
  displayName: string
  email: string | null
}

export type AuthOptions = {
  /** Where a full-page navigation starts Google sign-in, or null when Google is not configured. */
  googleSignInUrl: string | null
  /** True only when the backend runs with the test-classpath dev sign-in (local development). */
  devLogin: boolean
}

/** The signed-in user, or null when there is no valid session. */
export async function fetchMe(signal?: AbortSignal): Promise<Me | null> {
  try {
    return await api<Me>('/api/me', { signal })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}

export function fetchAuthOptions(signal?: AbortSignal): Promise<AuthOptions> {
  return api<AuthOptions>('/api/auth/options', { signal })
}

export function devSignIn(email: string): Promise<void> {
  return api<void>('/api/dev/login', { method: 'POST', body: { email } })
}

export function signOut(): Promise<void> {
  return api<void>('/api/auth/logout', { method: 'POST' })
}
