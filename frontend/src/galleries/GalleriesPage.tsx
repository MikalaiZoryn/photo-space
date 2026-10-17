import { useState } from 'react'
import { CheckboxField } from '../ui/Field.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { FilterBar } from '../ui/RecordList.tsx'
import { GalleryList } from './GalleryList.tsx'

export function GalleriesPage({ workspaceId, timezone }: { workspaceId: string; timezone: string }) {
  const [archived, setArchived] = useState(false)

  return (
    <section aria-labelledby="galleries-heading">
      <PageHeader
        id="galleries-heading"
        title="Galleries"
        description="To create a gallery, open the session its photos belong to."
      />
      <FilterBar>
        <CheckboxField label="Show archived" checked={archived} onChange={setArchived} />
      </FilterBar>
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
