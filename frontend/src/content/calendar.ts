import type { Post } from '../api/content.ts'

/**
 * Calendar days are ISO dates ("2026-11-07") in the workspace's time zone, like the API's. Arithmetic runs in
 * UTC so no browser zone or daylight-saving change can shift a day. Weeks start on Monday.
 */

/** Today in the given time zone. */
export function todayIn(timeZone: string, now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}

function toDate(day: string): Date {
  return new Date(`${day}T00:00:00Z`)
}

function toDay(date: Date): string {
  return date.toISOString().slice(0, 10)
}

export function addDays(day: string, days: number): string {
  const date = toDate(day)
  date.setUTCDate(date.getUTCDate() + days)
  return toDay(date)
}

/** The Monday of the day's week. */
export function weekStart(day: string): string {
  const weekday = (toDate(day).getUTCDay() + 6) % 7
  return addDays(day, -weekday)
}

/** Every day from `from` to `to`, inclusive. */
export function daysBetween(from: string, to: string): string[] {
  const days: string[] = []
  for (let day = from; day <= to; day = addDays(day, 1)) days.push(day)
  return days
}

/** The whole weeks that cover the day's month, Monday to Sunday: what a month view shows. */
export function monthRange(day: string): { from: string; to: string } {
  const first = `${day.slice(0, 7)}-01`
  const next = toDate(first)
  next.setUTCMonth(next.getUTCMonth() + 1)
  const last = addDays(toDay(next), -1)
  return { from: weekStart(first), to: addDays(weekStart(last), 6) }
}

export function addMonths(day: string, months: number): string {
  const date = toDate(`${day.slice(0, 7)}-01`)
  date.setUTCMonth(date.getUTCMonth() + months)
  return toDay(date)
}

/** "Mon 9" style label for a day cell. */
export function dayLabel(day: string, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', timeZone: 'UTC' }).format(toDate(day))
}

/** "November 2026" style label for a month. */
export function monthLabel(day: string, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(toDate(day))
}

/** The day a post shows on: when it was posted, else when it is planned. */
export function postDay(post: Pick<Post, 'postedOn' | 'plannedOn'>): string | null {
  return post.postedOn ?? post.plannedOn
}

/** Feed posts count toward the weekly goal; Stories do not. */
export function countsTowardGoal(post: Pick<Post, 'format'>): boolean {
  return post.format !== 'STORY'
}

/** One week's progress toward the goal: posted and approved count, proposals are shown apart. */
export type WeekCadence = { start: string; ready: number; posted: number; proposed: number }

export function cadenceByWeek(posts: Post[], weekStarts: string[]): WeekCadence[] {
  return weekStarts.map((start) => {
    const end = addDays(start, 6)
    const inWeek = posts.filter((post) => {
      const day = postDay(post)
      return day !== null && day >= start && day <= end && countsTowardGoal(post)
    })
    return {
      start,
      posted: inWeek.filter((post) => post.status === 'POSTED').length,
      ready: inWeek.filter((post) => post.status !== 'PROPOSED').length,
      proposed: inWeek.filter((post) => post.status === 'PROPOSED').length,
    }
  })
}
