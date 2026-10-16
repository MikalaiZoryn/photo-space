import { useEffect, useState } from 'react'
import { fetchAuthOptions, fetchMe, signOut, type AuthOptions, type Me } from './api/auth.ts'
import { PublicGalleryPage } from './galleries/PublicGalleryPage.tsx'
import { usePublicGalleryToken } from './routing.ts'
import { SignIn } from './SignIn.tsx'
import { SystemStatus } from './SystemStatus.tsx'
import { Workspaces } from './Workspaces.tsx'

type Session =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'signedOut'; options: AuthOptions }
  | { kind: 'signedIn'; me: Me }

function App() {
  const galleryToken = usePublicGalleryToken()
  // A gallery visitor's link: only that gallery, without the sign-in flow or any workspace API call.
  if (galleryToken !== null) {
    return (
      <main>
        <PublicGalleryPage key={galleryToken} token={galleryToken} />
      </main>
    )
  }
  return <WorkspaceApp />
}

function WorkspaceApp() {
  const [session, setSession] = useState<Session>({ kind: 'loading' })
  const [reloadCount, setReloadCount] = useState(0)
  const [signInFailed] = useState(() => new URLSearchParams(window.location.search).has('signInError'))
  const [signOutFailed, setSignOutFailed] = useState(false)

  useEffect(() => {
    // The backend redirects here with ?signInError after a failed Google sign-in; keep the URL clean.
    if (signInFailed) window.history.replaceState(null, '', window.location.pathname)
  }, [signInFailed])

  useEffect(() => {
    const controller = new AbortController()
    Promise.all([fetchMe(controller.signal), fetchAuthOptions(controller.signal)])
      .then(([me, options]) => setSession(me ? { kind: 'signedIn', me } : { kind: 'signedOut', options }))
      .catch(() => {
        if (!controller.signal.aborted) setSession({ kind: 'error' })
      })
    return () => controller.abort()
  }, [reloadCount])

  function reload() {
    setSession({ kind: 'loading' })
    setReloadCount((n) => n + 1)
  }

  async function handleSignOut() {
    setSignOutFailed(false)
    try {
      await signOut()
      reload()
    } catch {
      setSignOutFailed(true)
    }
  }

  return (
    <main>
      <header className="toolbar">
        <h1>photo-space</h1>
        {session.kind === 'signedIn' && (
          <span>
            {session.me.displayName}{' '}
            <button type="button" onClick={handleSignOut}>
              Sign out
            </button>
          </span>
        )}
      </header>
      {signOutFailed && <p role="alert">Sign-out failed. Please try again.</p>}
      {session.kind === 'loading' && <p role="status">Loading…</p>}
      {session.kind === 'error' && (
        <p role="alert">
          Could not reach the server.{' '}
          <button type="button" onClick={reload}>
            Retry
          </button>
        </p>
      )}
      {session.kind === 'signedOut' && (
        <SignIn options={session.options} signInFailed={signInFailed} onSignedIn={reload} />
      )}
      {session.kind === 'signedIn' && <Workspaces />}
      <SystemStatus />
    </main>
  )
}

export default App
