import { useEffect, useState } from 'react'
import { CreateWorkspaceForm } from './CreateWorkspaceForm.tsx'
import { listMembers, listWorkspaces, type Member, type Workspace } from './api/workspaces.ts'

type Loadable<T> = { kind: 'loading' } | { kind: 'error' } | { kind: 'loaded'; value: T }

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
      .catch(() => {
        if (!controller.signal.aborted) setWorkspaces({ kind: 'error' })
      })
    return () => controller.abort()
  }, [])

  function select(id: string) {
    setSelectedId(id)
    writeSelected(id)
  }

  function created(workspace: Workspace) {
    setWorkspaces((current) =>
      current.kind === 'loaded' ? { kind: 'loaded', value: [...current.value, workspace] } : current,
    )
    setCreating(false)
    select(workspace.id)
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
          <select value={selected.id} onChange={(e) => select(e.target.value)}>
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
  const [members, setMembers] = useState<Loadable<Member[]>>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    listMembers(workspace.id, controller.signal)
      .then((value) => setMembers({ kind: 'loaded', value }))
      .catch(() => {
        if (!controller.signal.aborted) setMembers({ kind: 'error' })
      })
    return () => controller.abort()
  }, [workspace.id])

  return (
    <section aria-labelledby="workspace-heading">
      <h2 id="workspace-heading">{workspace.name}</h2>
      <p>
        Time zone {workspace.timezone} · Your role: {workspace.role.toLowerCase()}
      </p>
      <h3>Members</h3>
      {members.kind === 'loading' && <p role="status">Loading members…</p>}
      {members.kind === 'error' && <p role="alert">Could not load members.</p>}
      {members.kind === 'loaded' && (
        <ul>
          {members.value.map((m) => (
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
