import { useEffect, useState } from 'react'

/**
 * The page inside the selected workspace, kept in the URL hash (#/clients/…) so the back button and reloads
 * work without a router dependency. The workspace itself is not in the URL; see Workspaces.tsx.
 */
export type Route =
  | { page: 'sessions' }
  | { page: 'session'; id: string }
  | { page: 'sessionNew'; clientId: string | null }
  | { page: 'sessionEdit'; id: string }
  | { page: 'clients' }
  | { page: 'client'; id: string }
  | { page: 'clientNew' }
  | { page: 'clientEdit'; id: string }
  | { page: 'members' }

export function parseRoute(hash: string): Route {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  const [section, id, action] = path.split('/').map(decodeURIComponent)
  if (section === 'sessions') {
    if (id === 'new') return { page: 'sessionNew', clientId: new URLSearchParams(query).get('client') }
    if (id && action === 'edit') return { page: 'sessionEdit', id }
    if (id) return { page: 'session', id }
  }
  if (section === 'clients') {
    if (id === 'new') return { page: 'clientNew' }
    if (id && action === 'edit') return { page: 'clientEdit', id }
    if (id) return { page: 'client', id }
    return { page: 'clients' }
  }
  if (section === 'members') return { page: 'members' }
  return { page: 'sessions' }
}

export const paths = {
  sessions: () => '#/sessions',
  session: (id: string) => `#/sessions/${encodeURIComponent(id)}`,
  sessionNew: (clientId?: string) => (clientId ? `#/sessions/new?client=${encodeURIComponent(clientId)}` : '#/sessions/new'),
  sessionEdit: (id: string) => `#/sessions/${encodeURIComponent(id)}/edit`,
  clients: () => '#/clients',
  client: (id: string) => `#/clients/${encodeURIComponent(id)}`,
  clientNew: () => '#/clients/new',
  clientEdit: (id: string) => `#/clients/${encodeURIComponent(id)}/edit`,
  members: () => '#/members',
}

export function navigate(hash: string) {
  window.location.hash = hash
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash))
  useEffect(() => {
    const update = () => setRoute(parseRoute(window.location.hash))
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])
  return route
}
