import type { Post } from '../api/content.ts'
import { paths } from '../routing.ts'
import { formatDay } from '../time.ts'
import { RecordRow } from '../ui/RecordList.tsx'
import { StatusBadge } from '../ui/StatusBadge.tsx'
import { postDay } from './calendar.ts'
import { formatLabels, postStatusLabels, postStatusTones, stageLabels } from './labels.ts'

/** A post's cover, or a muted square while it has none. */
export function PostCover({ post, className = 'size-12' }: { post: Post; className?: string }) {
  return post.coverThumbnailUrl ? (
    <img src={post.coverThumbnailUrl} alt="" className={`${className} shrink-0 rounded-md bg-muted object-cover`} />
  ) : (
    <span aria-hidden="true" className={`${className} shrink-0 rounded-md bg-muted`} />
  )
}

/** One post in a list: cover, stage and format, session, day, and status. */
export function PostRow({ post, showSession = true }: { post: Post; showSession?: boolean }) {
  const day = postDay(post)
  return (
    <RecordRow
      href={paths.contentPost(post.id)}
      leading={<PostCover post={post} />}
      title={`${stageLabels[post.stage]} · ${formatLabels[post.format]}${showSession ? ` · ${post.sessionTitle}` : ''}`}
      meta={
        <>
          <span>{day ? formatDay(day) : 'Day set when the gallery is published'}</span>
          <span className="block truncate">{post.caption}</span>
        </>
      }
      trailing={<StatusBadge tone={postStatusTones[post.status]}>{postStatusLabels[post.status]}</StatusBadge>}
    />
  )
}
