import { useState } from 'react'
import { listInquiries, INQUIRY_STATUSES, type InquiryStatus } from '../api/inquiries.ts'
import { paths } from '../routing.ts'
import { formatDate, formatDay } from '../time.ts'
import { Button } from '@/components/ui/button'
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select'
import { EmptyState, Loading } from '../ui/Feedback.tsx'
import { LoadError } from '../ui/LoadError.tsx'
import { PageHeader } from '../ui/PageHeader.tsx'
import { Pager } from '../ui/Pager.tsx'
import { FilterBar, Initials, RecordList, RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { useLoad } from '../useLoad.ts'
import { inquiryStatusLabels, inquiryStatusTones } from './labels.ts'

type Props = { workspaceId: string; timezone: string }

/** Submissions of the public inquiry form, newest first. Dismissed ones are hidden unless asked for. */
export function InquiriesPage({ workspaceId, timezone }: Props) {
  const [status, setStatus] = useState<InquiryStatus | ''>('')
  const [pageNumber, setPageNumber] = useState(0)
  const inquiries = useLoad(
    (signal) => listInquiries(workspaceId, { status: status || undefined, page: pageNumber }, signal),
    [workspaceId, status, pageNumber],
  )

  return (
    <section aria-labelledby="inquiries-heading">
      <PageHeader
        id="inquiries-heading"
        title="Inquiries"
        description="Sent through your inquiry form. Nothing is booked or linked to a client until you do it."
        actions={
          <Button asChild variant="outline">
            <a href={paths.inquiryForm()}>Edit the form</a>
          </Button>
        }
      />
      <FilterBar>
        <label className="inline-flex items-center gap-2 text-sm">
          Status
          <NativeSelect
            size="sm"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as InquiryStatus | '')
              setPageNumber(0)
            }}
          >
            <NativeSelectOption value="">All but dismissed</NativeSelectOption>
            {INQUIRY_STATUSES.map((s) => (
              <NativeSelectOption key={s} value={s}>
                {inquiryStatusLabels[s]}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </label>
      </FilterBar>
      {inquiries.state.kind === 'loading' && <Loading>Loading inquiries…</Loading>}
      {inquiries.state.kind === 'error' && <LoadError error={inquiries.state.error} what="inquiries" onRetry={inquiries.reload} />}
      {inquiries.state.kind === 'loaded' &&
        (inquiries.state.value.totalItems === 0 ? (
          <EmptyState>{status ? 'No inquiries with this status.' : 'No inquiries yet. Share your inquiry form to get some.'}</EmptyState>
        ) : (
          <>
            <RecordList label="Inquiries">
              {inquiries.state.value.items.map((inquiry) => (
                <RecordRow
                  key={inquiry.id}
                  href={paths.inquiry(inquiry.id)}
                  leading={<Initials name={inquiry.name} />}
                  title={inquiry.name}
                  meta={[
                    inquiry.email,
                    inquiry.service,
                    inquiry.preferredDate && `prefers ${formatDay(inquiry.preferredDate)}`,
                    `sent ${formatDate(inquiry.submittedAt, timezone)}`,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                  trailing={<StatusBadge tone={inquiryStatusTones[inquiry.status]}>{inquiryStatusLabels[inquiry.status]}</StatusBadge>}
                />
              ))}
            </RecordList>
            <Pager page={inquiries.state.value} onPage={setPageNumber} />
          </>
        ))}
    </section>
  )
}
