import { useState, type FormEvent } from 'react'
import { devSignIn, type AuthOptions } from './api/auth.ts'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ErrorAlert } from './ui/Feedback.tsx'
import { ErrorText, Field } from './ui/Field.tsx'

type Props = {
  options: AuthOptions
  signInFailed: boolean
  onSignedIn: () => void
  /** Replaces the line under the heading, e.g. to explain which account accepts an invitation. */
  intro?: string
}

export function SignIn({ options, signInFailed, onSignedIn, intro }: Props) {
  const hasMethod = options.googleSignInUrl !== null || options.devLogin

  return (
    <section aria-labelledby="sign-in-heading" className="space-y-6 rounded-2xl border bg-card p-6 shadow-sm sm:p-8">
      <div className="space-y-1.5 text-center">
        <h2 id="sign-in-heading" className="text-2xl font-semibold">
          Sign in
        </h2>
        <p className="text-sm text-muted-foreground">
          {intro ?? 'Manage your clients, sessions, and galleries in one place.'}
        </p>
      </div>
      {signInFailed && <ErrorAlert>Sign-in did not complete. Please try again.</ErrorAlert>}
      {options.googleSignInUrl && (
        // A full-page navigation: the backend redirects to Google and back, then sets the session cookie.
        <Button asChild size="lg" className="w-full">
          <a href={options.googleSignInUrl}>
            <GoogleIcon />
            Sign in with Google
          </a>
        </Button>
      )}
      {options.devLogin && <DevSignIn onSignedIn={onSignedIn} />}
      {!hasMethod && <ErrorAlert>No sign-in method is configured. See the README section on local sign-in.</ErrorAlert>}
    </section>
  )
}

function DevSignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const [email, setEmail] = useState('photographer@example.com')
  const [failed, setFailed] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setFailed(false)
    try {
      await devSignIn(email)
      onSignedIn()
    } catch {
      setFailed(true)
    }
  }

  return (
    <form onSubmit={submit} aria-label="Development sign-in" className="grid gap-4 border-t pt-6">
      <p className="text-sm text-muted-foreground">Development sign-in (local only, no password):</p>
      <Field label="Email">
        <Input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
      </Field>
      <Button type="submit" variant="outline" className="w-full">
        Sign in (dev)
      </Button>
      {failed && <ErrorText>Development sign-in failed.</ErrorText>}
    </form>
  )
}

function GoogleIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="size-4">
      <path fill="currentColor" d="M21.35 11.1H12v2.98h5.35c-.23 1.4-1.66 4.1-5.35 4.1a5.95 5.95 0 0 1 0-11.9c1.85 0 3.1.79 3.8 1.47l2.6-2.5A9.4 9.4 0 0 0 12 2.6a9.4 9.4 0 1 0 0 18.8c5.43 0 9.03-3.82 9.03-9.2 0-.62-.07-1.09-.15-1.56Z" />
    </svg>
  )
}
