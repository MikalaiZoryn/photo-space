import { describe, expect, it } from 'vitest'
import { formatDate, formatSessionTime, toInputValue } from './time.ts'

// The test machine's own zone is irrelevant: every case names the zone to show times in.
describe('formatSessionTime', () => {
  it('shows a UTC instant in the workspace time zone', () => {
    expect(formatSessionTime('2026-07-04T08:00:00Z', null, 'Europe/Warsaw', 'en-GB')).toBe('4 Jul 2026, 10:00')
    expect(formatSessionTime('2026-07-04T08:00:00Z', null, 'America/New_York', 'en-GB')).toBe('4 Jul 2026, 04:00')
  })

  it('follows daylight saving time in the workspace zone', () => {
    expect(formatSessionTime('2026-12-04T09:00:00Z', null, 'Europe/Warsaw', 'en-GB')).toBe('4 Dec 2026, 10:00')
  })

  it('shows a range, repeating the date only when it changes', () => {
    expect(formatSessionTime('2026-07-04T08:00:00Z', '2026-07-04T10:30:00Z', 'Europe/Warsaw', 'en-GB')).toBe(
      '4 Jul 2026, 10:00–12:30',
    )
    // ICU puts thin spaces around the dash of a multi-day range.
    const multiDay = formatSessionTime('2026-07-04T20:00:00Z', '2026-07-05T10:30:00Z', 'Europe/Warsaw', 'en-GB')
    expect(multiDay?.replace(/\s/g, ' ')).toBe('4 Jul 2026, 22:00 – 5 Jul 2026, 12:30')
  })

  it('returns null for an unscheduled session', () => {
    expect(formatSessionTime(null, null, 'Europe/Warsaw')).toBeNull()
  })
})

describe('formatDate', () => {
  it('uses the zone to decide the day', () => {
    expect(formatDate('2026-07-04T23:30:00Z', 'Europe/Warsaw', 'en-GB')).toBe('5 Jul 2026')
    expect(formatDate('2026-07-04T23:30:00Z', 'America/New_York', 'en-GB')).toBe('4 Jul 2026')
  })
})

describe('toInputValue', () => {
  it('trims seconds for a datetime-local input', () => {
    expect(toInputValue('2026-07-04T10:00:00')).toBe('2026-07-04T10:00')
    expect(toInputValue(null)).toBe('')
  })
})
