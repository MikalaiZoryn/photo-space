import { useEffect, useState } from 'react'
import { ClientDetail, EditClient, NewClient } from './clients/ClientDetail.tsx'
import { ClientsPage } from './clients/ClientsPage.tsx'
import { CreateWorkspaceForm } from './CreateWorkspaceForm.tsx'
import { listMembers, listWorkspaces, type Workspace } from './api/workspaces.ts'
import { navigate, paths, useRoute, type Route } from './routing.ts'
import { EditSession, NewSession, SessionDetail } from './sessions/SessionDetail.tsx'
import { SessionsPage } from './sessions/SessionsPage.tsx'
import { LoadError } from './ui/LoadError.tsx'
import { useLoad, type Loadable } from './useLoad.ts'

// Only remembers which workspace was last open in this browser. Access is always checked by the server.
const SELECTED_KEY = 'photospace.selectedWorkspace'

/** Workspace onboarding (create the first one) and selection between the user's workspaces. */
export function Workspaces() {
  const [workspaces, setWorkspaces] = useState<Loadable<Workspace[]>>({ kind: 'loading' })
  const [selectedId, setSelectedId] = useState<string | null>(readSelected)
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    listWorkspaces(controller.signal)
      .then((value) => setWorkspaces({ kind: 'loaded', value }))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setWorkspaces({ kind: 'error', error })
      })
    return () => controller.abort()
  }, [])

  function select(id: string) {
    setSelectedId(id)
    writeSelected(id)
  }

  function switchTo(id: string) {
    select(id)
    // Record IDs in the URL belong to the previous workspace.
    navigate(paths.sessions())
  }

  function created(workspace: Workspace) {
    setWorkspaces((current) =>
      current.kind === 'loaded' ? { kind: 'loaded', value: [...current.value, workspace] } : current,
    )
    setCreating(false)
    switchTo(workspace.id)
  }

  if (workspaces.kind === 'loading') return <p role="status">Loading workspaces…</p>
  if (workspaces.kind === 'error') return <p role="alert">Could not load your workspaces.</p>

  if (workspaces.value.length === 0) {
    return (
      <section aria-labelledby="onboarding-heading">
        <h2 id="onboarding-heading">Create your workspace</h2>
        <p>A workspace holds one photography business: its clients, sessions, photos, and galleries.</p>
        <CreateWorkspaceForm onCreated={created} />
      </section>
    )
  }

  const selected = workspaces.value.find((w) => w.id === selectedId) ?? workspaces.value[0]

  return (
    <>
      <div className="toolbar">
        <label>
          Workspace{' '}
          <select value={selected.id} onChange={(e) => switchTo(e.target.value)}>
            {workspaces.value.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </select>
        </label>
        {!creating && (
          <button type="button" onClick={() => setCreating(true)}>
            New workspace
          </button>
        )}
      </div>
      {creating && <CreateWorkspaceForm onCreated={created} onCancel={() => setCreating(false)} />}
      <WorkspaceHome key={selected.id} workspace={selected} />
    </>
  )
}

function WorkspaceHome({ workspace }: { workspace: Workspace }) {
  const route = useRoute()
  const section = route.page.startsWith('client') ? 'clients' : route.page === 'members' ? 'members' : 'sessions'

  return (
    <section aria-labelledby="workspace-heading">
      <h2 id="workspace-heading">{workspace.name}</h2>
      <nav aria-label="Workspace sections" className="tabs">
        <a href={paths.sessions()} aria-current={section === 'sessions' ? 'page' : undefined}>
          Sessions
        </a>
        <a href={paths.clients()} aria-current={section === 'clients' ? 'page' : undefined}>
          Clients
        </a>
        <a href={paths.members()} aria-current={section === 'members' ? 'page' : undefined}>
          Members
        </a>
      </nav>
      <RoutedPage workspace={workspace} route={route} />
    </section>
  )
}

function RoutedPage({ workspace, route }: { workspace: Workspace; route: Route }) {
  const { id: workspaceId, timezone } = workspace
  switch (route.page) {
    case 'sessions':
      return <SessionsPage workspaceId={workspaceId} timezone={timezone} />
    case 'session':
      return <SessionDetail key={route.id} workspaceId={workspaceId} sessionId={route.id} />
    case 'sessionNew':
      return <NewSession key={route.clientId} workspaceId={workspaceId} timezone={timezone} clientId={route.clientId} />
    case 'sessionEdit':
      return <EditSession key={route.id} workspaceId={workspaceId} timezone={timezone} sessionId={route.id} />
    case 'clients':
      return <ClientsPage workspaceId={workspaceId} />
    case 'client':
      return <ClientDetail key={route.id} workspaceId={workspaceId} timezone={timezone} clientId={route.id} />
    case 'clientNew':
      return <NewClient workspaceId={workspaceId} />
    case 'clientEdit':
      return <EditClient key={route.id} workspaceId={workspaceId} clientId={route.id} />
    case 'members':
      return <MembersPage workspace={workspace} />
  }
}

function MembersPage({ workspace }: { workspace: Workspace }) {
  const members = useLoad((signal) => listMembers(workspace.id, signal), [workspace.id])

  return (
    <section aria-labelledby="members-heading">
      <h3 id="members-heading">Members</h3>
      <p>
        Time zone {workspace.timezone} · Your role: {workspace.role.toLowerCase()}
      </p>
      {members.state.kind === 'loading' && <p role="status">Loading members…</p>}
      {members.state.kind === 'error' && (
        <LoadError error={members.state.error} what="members" onRetry={members.reload} />
      )}
      {members.state.kind === 'loaded' && (
        <ul>
          {members.state.value.map((m) => (
            <li key={m.userId}>
              {m.displayName} ({m.role.toLowerCase()})
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function readSelected(): string | null {
  try {
    return window.localStorage.getItem(SELECTED_KEY)
  } catch {
    return null
  }
}

function writeSelected(id: string) {
  try {
    window.localStorage.setItem(SELECTED_KEY, id)
  } catch {
    // Storage can be unavailable (private mode, blocked site data); selection then lasts for this page only.
  }
}
