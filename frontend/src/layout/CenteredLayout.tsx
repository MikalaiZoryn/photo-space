import type { ReactNode } from 'react'
import { SystemStatus } from '../SystemStatus.tsx'
import { Brand } from './Brand.tsx'

/** Signed-out and first-run pages: the brand and one centered card. */
export function CenteredLayout({ children, status = true }: { children: ReactNode; status?: boolean }) {
  return (
    <div className="flex min-h-dvh flex-col items-center px-4 py-10 sm:py-16">
      <header className="mb-8">
        <h1>
          <Brand />
        </h1>
      </header>
      <main className="w-full max-w-md flex-1">{children}</main>
      {status && (
        <footer className="mt-10">
          <SystemStatus />
        </footer>
      )}
    </div>
  )
}
