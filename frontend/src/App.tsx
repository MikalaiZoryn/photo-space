import { useEffect, useState } from 'react'
import { fetchAuthOptions, fetchMe, signOut, type AuthOptions, type Me } from './api/auth.ts'
import { PublicGalleryPage } from './galleries/PublicGalleryPage.tsx'
import { usePublicGalleryToken } from './routing.ts'
import { CenteredLayout } from './layout/CenteredLayout.tsx'
import { SignIn } from './SignIn.tsx'
import { Button } from '@/components/ui/button'
import { ErrorAlert, Loading } from './ui/Feedback.tsx'
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
    return <PublicGalleryPage key={galleryToken} token={galleryToken} />
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

  const signOutAlert = signOutFailed && <ErrorAlert>Sign-out failed. Please try again.</ErrorAlert>

  if (session.kind === 'signedIn') {
    return <Workspaces userName={session.me.displayName} onSignOut={handleSignOut} notice={signOutAlert} />
  }
  return (
    <CenteredLayout>
      {session.kind === 'loading' && <Loading>Loading…</Loading>}
      {session.kind === 'error' && (
        <ErrorAlert>
          <span className="flex flex-wrap items-center gap-3">
            Could not reach the server.{' '}
            <Button type="button" variant="outline" size="sm" onClick={reload}>
              Retry
            </Button>
          </span>
        </ErrorAlert>
      )}
      {session.kind === 'signedOut' && (
        <SignIn options={session.options} signInFailed={signInFailed} onSignedIn={reload} />
      )}
    </CenteredLayout>
  )
}

export default App
