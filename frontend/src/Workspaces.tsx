import { useEffect, useState, type ReactNode } from 'react'
import { CalendarDays, History, Images, Inbox, LayoutDashboard, ListChecks, Plus, Settings, Sparkles, Users } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { ActivityPage } from './activity/ActivityPage.tsx'
import { AutomationPage } from './automation/AutomationPage.tsx'
import { ClientDetail, EditClient, NewClient } from './clients/ClientDetail.tsx'
import { ClientsPage } from './clients/ClientsPage.tsx'
import { BatchPage } from './content/BatchPage.tsx'
import { ContentPage } from './content/ContentPage.tsx'
import { CreateWorkspaceForm } from './CreateWorkspaceForm.tsx'
import { DashboardPage } from './dashboard/DashboardPage.tsx'
import { GalleriesPage } from './galleries/GalleriesPage.tsx'
import { GalleryPage } from './galleries/GalleryPage.tsx'
import { BrandingPage } from './inquiries/BrandingPage.tsx'
import { FormBuilderPage } from './inquiries/FormBuilderPage.tsx'
import { InquiriesPage } from './inquiries/InquiriesPage.tsx'
import { InquiryDetail } from './inquiries/InquiryDetail.tsx'
import { Invitations } from './invitations/Invitations.tsx'
import { AppShell, type NavItem } from './layout/AppShell.tsx'
import { CenteredLayout } from './layout/CenteredLayout.tsx'
import { listMembers, listWorkspaces, type Workspace } from './api/workspaces.ts'
import { navigate, paths, useRoute, type Route } from './routing.ts'
import { readSelected, writeSelected } from './selectedWorkspace.ts'
import { EditSession, NewSession, SessionDetail } from './sessions/SessionDetail.tsx'
import { SessionsPage } from './sessions/SessionsPage.tsx'
import { ContentStudioPage } from './settings/ContentStudioPage.tsx'
import { SettingsPage } from './settings/SettingsPage.tsx'
import { EditTask, NewTask, TasksPage } from './tasks/TasksPage.tsx'
import { ErrorAlert, Loading } from './ui/Feedback.tsx'
import { LoadError } from './ui/LoadError.tsx'
import { PageHeader } from './ui/PageHeader.tsx'
import { Initials, RecordList, RecordRow } from './ui/RecordList.tsx'
import { StatusBadge } from './ui/StatusBadge.tsx'
import { useLoad, type Loadable } from './useLoad.ts'

/** Workspace onboarding (create the first one) and selection between the user's workspaces. */
type Props = {
  userId: string
  userName: string
  onSignOut: () => void
  /** Shown above the page, e.g. a failed sign-out. */
  notice?: ReactNode
}

export function Workspaces({ userId, userName, onSignOut, notice }: Props) {
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
    navigate(paths.dashboard())
  }

  function created(workspace: Workspace) {
    setWorkspaces((current) =>
      current.kind === 'loaded' ? { kind: 'loaded', value: [...current.value, workspace] } : current,
    )
    setCreating(false)
    switchTo(workspace.id)
  }

  const shell = (children: ReactNode, rest: Partial<Parameters<typeof AppShell>[0]> = {}) => (
    <AppShell userName={userName} onSignOut={onSignOut} {...rest}>
      {notice && <div className="mb-6">{notice}</div>}
      {children}
    </AppShell>
  )

  if (workspaces.kind === 'loading') {
    return (
      <CenteredLayout status={false}>
        <Loading>Loading workspaces…</Loading>
      </CenteredLayout>
    )
  }
  if (workspaces.kind === 'error') {
    return (
      <CenteredLayout>
        <div className="space-y-4">
          {notice}
          <ErrorAlert>Could not load your workspaces.</ErrorAlert>
          <Button type="button" variant="outline" onClick={onSignOut}>
            Sign out
          </Button>
        </div>
      </CenteredLayout>
    )
  }

  if (workspaces.value.length === 0) {
    return (
      <CenteredLayout>
        {notice && <div className="mb-6">{notice}</div>}
        <section aria-labelledby="onboarding-heading" className="space-y-6 rounded-2xl border bg-card p-6 shadow-sm sm:p-8">
          <div className="space-y-1.5">
            <h2 id="onboarding-heading" className="text-2xl font-semibold">
              Create your workspace
            </h2>
            <p className="text-sm text-muted-foreground">
              A workspace holds one photography business: its clients, sessions, photos, and galleries.
            </p>
          </div>
          <CreateWorkspaceForm onCreated={created} />
          <div className="flex items-center justify-between gap-3 border-t pt-4 text-sm text-muted-foreground">
            <span className="truncate">Signed in as {userName}</span>
            <Button type="button" variant="ghost" size="sm" onClick={onSignOut}>
              Sign out
            </Button>
          </div>
        </section>
      </CenteredLayout>
    )
  }

  const selected = workspaces.value.find((w) => w.id === selectedId) ?? workspaces.value[0]

  const switcher = (
    <div className="grid gap-2">
      <label className="grid gap-1.5 px-1 text-xs font-medium text-muted-foreground">
        Workspace
        <NativeSelect className="w-full" value={selected.id} onChange={(e) => switchTo(e.target.value)}>
          {workspaces.value.map((w) => (
            <NativeSelectOption key={w.id} value={w.id}>
              {w.name}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </label>
      {!creating && (
        <Button type="button" variant="ghost" size="sm" className="justify-start text-muted-foreground" onClick={() => setCreating(true)}>
          <Plus aria-hidden="true" />
          New workspace
        </Button>
      )}
    </div>
  )

  return (
    <WorkspaceHome
      key={selected.id}
      workspace={selected}
      userId={userId}
      render={(nav, children) =>
        shell(
          <>
            {creating && (
              <section aria-labelledby="new-workspace-heading" className="mb-8 max-w-lg space-y-4 rounded-xl border bg-card p-4 shadow-xs sm:p-6">
                <h3 id="new-workspace-heading" className="text-base font-semibold">
                  New workspace
                </h3>
                <CreateWorkspaceForm onCreated={created} onCancel={() => setCreating(false)} />
              </section>
            )}
            {children}
          </>,
          { switcher, nav, context: selected.name },
        )
      }
    />
  )
}

function WorkspaceHome({
  workspace,
  userId,
  render,
}: {
  workspace: Workspace
  userId: string
  render: (nav: NavItem[], children: ReactNode) => ReactNode
}) {
  const route = useRoute()
  const section = sectionOf(route)
  const nav: NavItem[] = [
    { href: paths.dashboard(), label: 'Dashboard', icon: LayoutDashboard, current: section === 'dashboard' },
    { href: paths.sessions(), label: 'Sessions', icon: CalendarDays, current: section === 'sessions' },
    { href: paths.tasks(), label: 'Tasks', icon: ListChecks, current: section === 'tasks' },
    { href: paths.clients(), label: 'Clients', icon: Users, current: section === 'clients' },
    { href: paths.inquiries(), label: 'Inquiries', icon: Inbox, current: section === 'inquiries' },
    { href: paths.galleries(), label: 'Galleries', icon: Images, current: section === 'galleries' },
    { href: paths.content(), label: 'Content', icon: Sparkles, current: section === 'content' },
    // The audit log is for owners; the server refuses it to members anyway.
    ...(workspace.role === 'OWNER'
      ? [{ href: paths.activity(), label: 'Activity', icon: History, current: section === 'activity' }]
      : []),
    { href: paths.settings(), label: 'Settings', icon: Settings, current: section === 'settings' },
  ]

  return render(
    nav,
    <section aria-labelledby="workspace-heading">
      <h2 id="workspace-heading" className="sr-only">
        {workspace.name}
      </h2>
      <RoutedPage workspace={workspace} userId={userId} route={route} />
    </section>,
  )
}

/** The nav item a page belongs to. The pages listed on the Settings page highlight Settings. */
function sectionOf(route: Route) {
  switch (route.page) {
    case 'clients':
    case 'client':
    case 'clientNew':
    case 'clientEdit':
      return 'clients'
    case 'inquiries':
    case 'inquiry':
      return 'inquiries'
    case 'galleries':
    case 'gallery':
      return 'galleries'
    case 'content':
    case 'contentBatch':
      return 'content'
    case 'tasks':
    case 'taskNew':
    case 'taskEdit':
      return 'tasks'
    case 'activity':
      return 'activity'
    case 'settings':
    case 'contentStudio':
    case 'automation':
    case 'members':
    case 'inquiryForm':
    case 'branding':
      return 'settings'
    case 'dashboard':
      return 'dashboard'
    default:
      return 'sessions'
  }
}

function RoutedPage({ workspace, userId, route }: { workspace: Workspace; userId: string; route: Route }) {
  const { id: workspaceId, timezone } = workspace
  switch (route.page) {
    case 'dashboard':
      return <DashboardPage workspace={workspace} userId={userId} />
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
    case 'galleries':
      return <GalleriesPage workspaceId={workspaceId} timezone={timezone} />
    case 'gallery':
      return <GalleryPage key={route.id} workspaceId={workspaceId} galleryId={route.id} timezone={timezone} />
    case 'content':
      return <ContentPage workspaceId={workspaceId} timezone={timezone} />
    case 'contentBatch':
      return <BatchPage key={route.id} workspaceId={workspaceId} batchId={route.id} timezone={timezone} />
    case 'tasks':
      return <TasksPage workspaceId={workspaceId} timezone={timezone} />
    case 'taskNew':
      return <NewTask key={route.sessionId} workspaceId={workspaceId} sessionId={route.sessionId} />
    case 'taskEdit':
      return <EditTask key={route.id} workspaceId={workspaceId} taskId={route.id} timezone={timezone} />
    case 'automation':
      return <AutomationPage workspaceId={workspaceId} timezone={timezone} role={workspace.role} />
    case 'activity':
      return <ActivityPage workspaceId={workspaceId} timezone={timezone} />
    case 'members':
      return <MembersPage workspace={workspace} />
    case 'inquiries':
      return <InquiriesPage workspaceId={workspaceId} timezone={timezone} />
    case 'inquiry':
      return <InquiryDetail key={route.id} workspaceId={workspaceId} inquiryId={route.id} timezone={timezone} />
    case 'inquiryForm':
      return <FormBuilderPage workspaceId={workspaceId} />
    case 'branding':
      return <BrandingPage workspaceId={workspaceId} />
    case 'settings':
      return <SettingsPage />
    case 'contentStudio':
      return <ContentStudioPage workspaceId={workspaceId} />
  }
}

function MembersPage({ workspace }: { workspace: Workspace }) {
  const members = useLoad((signal) => listMembers(workspace.id, signal), [workspace.id])

  return (
    <section aria-labelledby="members-heading">
      <PageHeader
        id="members-heading"
        title="Members"
        description={`Time zone ${workspace.timezone} · Your role: ${workspace.role.toLowerCase()}`}
        back={{ href: paths.settings(), label: 'Settings' }}
      />
      {members.state.kind === 'loading' && <Loading>Loading members…</Loading>}
      {members.state.kind === 'error' && (
        <LoadError error={members.state.error} what="members" onRetry={members.reload} />
      )}
      {members.state.kind === 'loaded' && (
        <RecordList label="Members">
          {members.state.value.map((m) => (
            <RecordRow
              key={m.userId}
              leading={<Initials name={m.displayName} />}
              title={m.displayName}
              trailing={<StatusBadge tone={m.role === 'OWNER' ? 'info' : 'neutral'}>{m.role === 'OWNER' ? 'Owner' : 'Member'}</StatusBadge>}
            />
          ))}
        </RecordList>
      )}
      {workspace.role === 'OWNER' && <Invitations workspaceId={workspace.id} timezone={workspace.timezone} />}
    </section>
  )
}
