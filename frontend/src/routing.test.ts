import { describe, expect, it } from 'vitest'
import { parseRoute, paths } from './routing.ts'

describe('parseRoute', () => {
  it('reads every page from the hash', () => {
    expect(parseRoute('')).toEqual({ page: 'sessions' })
    expect(parseRoute('#/sessions')).toEqual({ page: 'sessions' })
    expect(parseRoute('#/sessions/new')).toEqual({ page: 'sessionNew', clientId: null })
    expect(parseRoute('#/sessions/new?client=c-1')).toEqual({ page: 'sessionNew', clientId: 'c-1' })
    expect(parseRoute('#/sessions/s-1')).toEqual({ page: 'session', id: 's-1' })
    expect(parseRoute('#/sessions/s-1/edit')).toEqual({ page: 'sessionEdit', id: 's-1' })
    expect(parseRoute('#/clients')).toEqual({ page: 'clients' })
    expect(parseRoute('#/clients/new')).toEqual({ page: 'clientNew' })
    expect(parseRoute('#/clients/c-1')).toEqual({ page: 'client', id: 'c-1' })
    expect(parseRoute('#/clients/c-1/edit')).toEqual({ page: 'clientEdit', id: 'c-1' })
    expect(parseRoute('#/tasks')).toEqual({ page: 'tasks' })
    expect(parseRoute('#/tasks/new')).toEqual({ page: 'taskNew', sessionId: null })
    expect(parseRoute('#/tasks/new?session=s-1')).toEqual({ page: 'taskNew', sessionId: 's-1' })
    expect(parseRoute('#/tasks/t-1/edit')).toEqual({ page: 'taskEdit', id: 't-1' })
    expect(parseRoute('#/automation')).toEqual({ page: 'automation' })
    expect(parseRoute('#/activity')).toEqual({ page: 'activity' })
    expect(parseRoute('#/members')).toEqual({ page: 'members' })
  })

  it('falls back to sessions for unknown hashes', () => {
    expect(parseRoute('#/nowhere')).toEqual({ page: 'sessions' })
  })

  it('round-trips the paths it builds', () => {
    expect(parseRoute(paths.session('a/b'))).toEqual({ page: 'session', id: 'a/b' })
    expect(parseRoute(paths.sessionNew('c 1'))).toEqual({ page: 'sessionNew', clientId: 'c 1' })
    expect(parseRoute(paths.clientEdit('c-1'))).toEqual({ page: 'clientEdit', id: 'c-1' })
    expect(parseRoute(paths.taskNew('s 1'))).toEqual({ page: 'taskNew', sessionId: 's 1' })
    expect(parseRoute(paths.taskEdit('t-1'))).toEqual({ page: 'taskEdit', id: 't-1' })
  })
})
