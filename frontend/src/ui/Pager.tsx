import type { Page } from '../api/client.ts'

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
    <nav aria-label="Pages" className="toolbar">
      <span>{page.items.length > 0 ? `${first}–${last} of ${page.totalItems}` : `No more results (${page.totalItems} total)`}</span>
      <span>
        <button type="button" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>
          Previous
        </button>{' '}
        <button type="button" disabled={!hasNext} onClick={() => onPage(page.page + 1)}>
          Next
        </button>
      </span>
    </nav>
  )
}
