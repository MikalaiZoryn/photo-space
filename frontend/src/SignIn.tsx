import { useState, type FormEvent } from 'react'
import { devSignIn, type AuthOptions } from './api/auth.ts'

type Props = {
  options: AuthOptions
  signInFailed: boolean
  onSignedIn: () => void
}

export function SignIn({ options, signInFailed, onSignedIn }: Props) {
  const hasMethod = options.googleSignInUrl !== null || options.devLogin

  return (
    <section aria-labelledby="sign-in-heading">
      <h2 id="sign-in-heading">Sign in</h2>
      {signInFailed && <p role="alert">Sign-in did not complete. Please try again.</p>}
      {options.googleSignInUrl && (
        // A full-page navigation: the backend redirects to Google and back, then sets the session cookie.
        <a className="button" href={options.googleSignInUrl}>
          Sign in with Google
        </a>
      )}
      {options.devLogin && <DevSignIn onSignedIn={onSignedIn} />}
      {!hasMethod && (
        <p role="alert">No sign-in method is configured. See the README section on local sign-in.</p>
      )}
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
    <form onSubmit={submit} aria-label="Development sign-in">
      <p>Development sign-in (local only, no password):</p>
      <label>
        Email <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
      </label>{' '}
      <button type="submit">Sign in (dev)</button>
      {failed && <p role="alert">Development sign-in failed.</p>}
    </form>
  )
}
