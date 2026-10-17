import { useEffect, useState, type MouseEvent, type ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { LogOut, Menu } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet'
import { SystemStatus } from '../SystemStatus.tsx'
import { Initials } from '../ui/RecordList.tsx'
import { Brand } from './Brand.tsx'

export type NavItem = { href: string; label: string; icon: LucideIcon; current: boolean }

type Props = {
  userName: string
  onSignOut: () => void
  /** Shown at the top of the sidebar, e.g. the workspace switcher. */
  switcher?: ReactNode
  nav?: NavItem[]
  /** Shown in the mobile top bar next to the brand. */
  context?: string
  children: ReactNode
}

/**
 * The signed-in layout: a sidebar on large screens, a top bar with a slide-in menu on small ones. The sidebar
 * content is rendered once per open surface, so labels and links stay unique.
 */
export function AppShell({ userName, onSignOut, switcher, nav, context, children }: Props) {
  const [menuOpen, setMenuOpen] = useState(false)

  useEffect(() => {
    const close = () => setMenuOpen(false)
    window.addEventListener('hashchange', close)
    return () => window.removeEventListener('hashchange', close)
  }, [])

  const sidebar = <Sidebar userName={userName} onSignOut={onSignOut} switcher={switcher} nav={nav} />

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[16rem_minmax(0,1fr)]">
      <SkipLink />
      <aside aria-label="Sidebar" className="sticky top-0 hidden h-dvh border-r bg-sidebar text-sidebar-foreground lg:block">
        {sidebar}
      </aside>
      <div className="flex min-h-dvh min-w-0 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b bg-background/85 px-4 backdrop-blur supports-[backdrop-filter]:bg-background/70 lg:hidden">
          <Button type="button" variant="ghost" size="icon" aria-label="Open menu" onClick={() => setMenuOpen(true)}>
            <Menu aria-hidden="true" />
          </Button>
          <Brand />
          {context && <span className="ml-auto truncate text-sm text-muted-foreground">{context}</span>}
        </header>
        {menuOpen && (
          <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
            <SheetContent
              side="left"
              className="w-72 bg-sidebar p-0 text-sidebar-foreground"
              // A tap on a link or button inside the menu acts and closes it.
              onClick={(e: MouseEvent) => {
                if ((e.target as Element).closest('a, button')) setMenuOpen(false)
              }}
            >
              <SheetTitle className="sr-only">Menu</SheetTitle>
              <SheetDescription className="sr-only">Workspace navigation</SheetDescription>
              {sidebar}
            </SheetContent>
          </Sheet>
        )}
        <main id="main-content" tabIndex={-1} className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 outline-none sm:px-6 lg:px-10 lg:py-10">
          {children}
        </main>
        <footer className="mx-auto w-full max-w-6xl px-4 pb-6 sm:px-6 lg:px-10">
          <SystemStatus />
        </footer>
      </div>
    </div>
  )
}

function Sidebar({ userName, onSignOut, switcher, nav }: Omit<Props, 'children' | 'context'>) {
  return (
    <div className="flex h-full flex-col gap-6 overflow-y-auto p-4">
      <div className="px-2 pt-1">
        <Brand />
      </div>
      {switcher}
      {nav && (
        <nav aria-label="Workspace sections">
          <ul className="grid gap-1">
            {nav.map((item) => (
              <li key={item.href}>
                <a
                  href={item.href}
                  aria-current={item.current ? 'page' : undefined}
                  className={cn(
                    'flex min-h-10 items-center gap-3 rounded-lg px-3 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground',
                    item.current && 'bg-sidebar-accent text-sidebar-accent-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
                  )}
                >
                  <item.icon className="size-4.5" aria-hidden="true" />
                  {item.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>
      )}
      <div className="mt-auto flex items-center gap-3 border-t pt-4">
        <Initials name={userName} />
        <span className="min-w-0 flex-1 truncate text-sm font-medium">{userName}</span>
        <Button type="button" variant="ghost" size="icon" aria-label="Sign out" title="Sign out" onClick={onSignOut}>
          <LogOut aria-hidden="true" />
        </Button>
      </div>
    </div>
  )
}

/** Moves focus to the page content. A plain #fragment link would change the hash route. */
function SkipLink() {
  return (
    <a
      href="#main-content"
      onClick={(e) => {
        e.preventDefault()
        document.getElementById('main-content')?.focus()
      }}
      className="sr-only z-50 rounded-lg bg-primary px-4 py-2 text-primary-foreground focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
    >
      Skip to content
    </a>
  )
}
