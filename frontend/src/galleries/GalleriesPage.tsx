import { useState } from 'react'
import { GalleryList } from './GalleryList.tsx'

export function GalleriesPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [archived, setArchived] = useState(false)

  return (
    <section aria-labelledby="galleries-heading">
      <h3 id="galleries-heading">Galleries</h3>
      <div className="toolbar filters">
        <label>
          <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} /> Show archived
        </label>
        <span className="muted">To create a gallery, open the session its photos belong to.</span>
      </div>
      <GalleryList
        key={String(archived)}
        workspaceId={workspaceId}
        timezone={timezone}
        filter={{ archived }}
        emptyText={archived ? 'No archived galleries.' : 'No galleries yet.'}
      />
    </section>
  )
}
