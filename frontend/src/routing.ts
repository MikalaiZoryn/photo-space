import { useEffect, useState } from 'react'

/**
 * The page inside the selected workspace, kept in the URL hash (#/clients/…) so the back button and reloads
 * work without a router dependency. The workspace itself is not in the URL; see Workspaces.tsx.
 */
export type Route =
  | { page: 'dashboard' }
  | { page: 'sessions' }
  | { page: 'session'; id: string }
  | { page: 'sessionNew'; clientId: string | null }
  | { page: 'sessionEdit'; id: string }
  | { page: 'clients' }
  | { page: 'client'; id: string }
  | { page: 'clientNew' }
  | { page: 'clientEdit'; id: string }
  | { page: 'galleries' }
  | { page: 'gallery'; id: string }
  | { page: 'content' }
  | { page: 'contentBatch'; id: string }
  | { page: 'tasks' }
  | { page: 'taskNew'; sessionId: string | null }
  | { page: 'taskEdit'; id: string }
  | { page: 'automation' }
  | { page: 'activity' }
  | { page: 'members' }
  | { page: 'inquiries' }
  | { page: 'inquiry'; id: string }
  | { page: 'inquiryForm' }
  | { page: 'branding' }
  | { page: 'settings' }
  | { page: 'contentStudio' }

export function parseRoute(hash: string): Route {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  const [section, id, action] = path.split('/').map(decodeURIComponent)
  if (section === 'sessions') {
    if (!id) return { page: 'sessions' }
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
  if (section === 'galleries') {
    if (id) return { page: 'gallery', id }
    return { page: 'galleries' }
  }
  if (section === 'content') {
    if (id === 'batches' && action) return { page: 'contentBatch', id: action }
    return { page: 'content' }
  }
  if (section === 'tasks') {
    if (id === 'new') return { page: 'taskNew', sessionId: new URLSearchParams(query).get('session') }
    if (id && action === 'edit') return { page: 'taskEdit', id }
    return { page: 'tasks' }
  }
  if (section === 'inquiries') {
    if (id) return { page: 'inquiry', id }
    return { page: 'inquiries' }
  }
  if (section === 'inquiry-form') return { page: 'inquiryForm' }
  if (section === 'branding') return { page: 'branding' }
  if (section === 'automation') return { page: 'automation' }
  if (section === 'activity') return { page: 'activity' }
  if (section === 'members') return { page: 'members' }
  if (section === 'settings') {
    if (id === 'content-studio') return { page: 'contentStudio' }
    return { page: 'settings' }
  }
  return { page: 'dashboard' }
}

export const paths = {
  dashboard: () => '#/dashboard',
  sessions: () => '#/sessions',
  session: (id: string) => `#/sessions/${encodeURIComponent(id)}`,
  sessionNew: (clientId?: string) => (clientId ? `#/sessions/new?client=${encodeURIComponent(clientId)}` : '#/sessions/new'),
  sessionEdit: (id: string) => `#/sessions/${encodeURIComponent(id)}/edit`,
  clients: () => '#/clients',
  client: (id: string) => `#/clients/${encodeURIComponent(id)}`,
  clientNew: () => '#/clients/new',
  clientEdit: (id: string) => `#/clients/${encodeURIComponent(id)}/edit`,
  galleries: () => '#/galleries',
  gallery: (id: string) => `#/galleries/${encodeURIComponent(id)}`,
  content: () => '#/content',
  contentBatch: (id: string) => `#/content/batches/${encodeURIComponent(id)}`,
  tasks: () => '#/tasks',
  taskNew: (sessionId?: string) => (sessionId ? `#/tasks/new?session=${encodeURIComponent(sessionId)}` : '#/tasks/new'),
  taskEdit: (id: string) => `#/tasks/${encodeURIComponent(id)}/edit`,
  automation: () => '#/automation',
  activity: () => '#/activity',
  members: () => '#/members',
  inquiries: () => '#/inquiries',
  inquiry: (id: string) => `#/inquiries/${encodeURIComponent(id)}`,
  inquiryForm: () => '#/inquiry-form',
  branding: () => '#/branding',
  settings: () => '#/settings',
  contentStudio: () => '#/settings/content-studio',
}

/**
 * The public inquiry pages are real paths, not hash routes, because CloudFront lets other sites frame only
 * `/embed/*`: `/inquiry/<slug>` is the hosted page, `/embed/inquiry/<slug>` the embeddable one.
 */
export type PublicInquiryRoute = { slug: string; embed: boolean }

export function publicInquiryRoute(pathname: string): PublicInquiryRoute | null {
  const match = /^\/(embed\/)?inquiry\/([^/]+)\/?$/.exec(pathname)
  if (!match) return null
  try {
    return { slug: decodeURIComponent(match[2]), embed: match[1] !== undefined }
  } catch {
    return { slug: match[2], embed: match[1] !== undefined }
  }
}

/**
 * A gallery visitor's link: `#/g/<token>`. The token is in the fragment, which browsers never send to a server,
 * so it stays out of request lines, access logs, and Referer headers.
 */
export function publicGalleryHash(token: string): string {
  return `#/g/${token}`
}

/**
 * The share token in a visitor's link, or null if `hash` is not a visitor's link. Any `#/g/…` is one, even with
 * a mangled token: the visitor then sees that the link does not work, not a sign-in page.
 */
export function publicGalleryToken(hash: string): string | null {
  const match = /^#\/g\/(.*)$/.exec(hash)
  return match ? match[1] : null
}

/** The share token of the current page, kept up to date as the hash changes. */
export function usePublicGalleryToken(): string | null {
  const [token, setToken] = useState(() => publicGalleryToken(window.location.hash))
  useEffect(() => {
    const update = () => setToken(publicGalleryToken(window.location.hash))
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])
  return token
}

/**
 * An invitation link from an email: `#/invite/<token>`. As with gallery links, the token is in the fragment, so
 * it never reaches a server, an access log, or a Referer header.
 */
export function invitationToken(hash: string): string | null {
  const match = /^#\/invite\/(.*)$/.exec(hash)
  return match ? match[1] : null
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
