import { ChevronLeft, ChevronRight } from 'lucide-react'
import type { Page } from '../api/client.ts'
import { Button } from '@/components/ui/button'

type Props = {
  page: Page<unknown>
  onPage: (page: number) => void
}

/** "26–50 of 60" with previous and next buttons. Hidden when everything fits on the first page. */
export function Pager({ page, onPage }: Props) {
  if (page.page === 0 && page.totalItems <= page.size) return null
  const first = page.page * page.size + 1
  const last = page.page * page.size + page.items.length
  const hasNext = (page.page + 1) * page.size < page.totalItems
  return (
    <nav aria-label="Pages" className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm">
      <span className="text-muted-foreground">
        {page.items.length > 0 ? `${first}–${last} of ${page.totalItems}` : `No more results (${page.totalItems} total)`}
      </span>
      <span className="flex gap-2">
        <Button type="button" variant="outline" size="sm" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>
          <ChevronLeft aria-hidden="true" />
          Previous
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={!hasNext} onClick={() => onPage(page.page + 1)}>
          Next
          <ChevronRight aria-hidden="true" />
        </Button>
      </span>
    </nav>
  )
}
