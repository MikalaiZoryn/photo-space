/**
 * Session times arrive as UTC instants and are shown in the workspace time zone, whatever the browser's own
 * zone is. Formatting uses the browser's locale unless one is given (tests pass one for stable output).
 */
export function formatSessionTime(
  startsAt: string | null,
  endsAt: string | null,
  timeZone: string,
  locale?: string,
): string | null {
  if (!startsAt) return null
  const format = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone })
  const start = new Date(startsAt)
  return endsAt ? format.formatRange(start, new Date(endsAt)) : format.format(start)
}

/** An instant as a date in the given zone, for record metadata such as "archived on". */
export function formatDate(instant: string, timeZone: string, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone }).format(new Date(instant))
}

/** A server wall-clock time ("2026-07-04T10:00:00") as a datetime-local input value ("2026-07-04T10:00"). */
export function toInputValue(local: string | null): string {
  return local ? local.slice(0, 16) : ''
}

/** A calendar date ("2026-11-07"), such as a task's due date, formatted without any time zone shift. */
export function formatDay(date: string, locale?: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(`${date}T00:00:00Z`))
}
