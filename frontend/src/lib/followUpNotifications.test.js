import { describe, expect, it } from 'vitest'
import { collectUnseenDueFollowUps, normalizeDueFollowUps } from './followUpNotifications'

describe('follow-up notifications', () => {
  it('normalizes the API envelope and keeps only due open reminders', () => {
    const result = normalizeDueFollowUps({
      data: {
        data: [
          { id: 2, title: 'later', status: 'OPEN', dueAt: '2099-01-01T00:00:00Z' },
          { id: 1, title: 'due', status: 'OPEN', due: true, dueAt: '2020-01-01T00:00:00Z' },
          { id: 3, title: 'done', status: 'COMPLETED', due: true, dueAt: '2020-01-01T00:00:00Z' },
        ],
      },
    })

    expect(result.map((task) => task.id)).toEqual([1])
  })

  it('falls back to dueAt for older API payloads', () => {
    const result = normalizeDueFollowUps([
      { id: 'past', status: 'OPEN', dueAt: '2020-01-01T00:00:00Z' },
      { id: 'invalid', status: 'OPEN', dueAt: 'not-a-date' },
    ])

    expect(result.map((task) => task.id)).toEqual(['past'])
  })

  it('returns each reminder only once per session', () => {
    const seen = new Set(['1'])
    const tasks = [{ id: 1 }, { id: 2 }]

    expect(collectUnseenDueFollowUps(tasks, seen).map((task) => task.id)).toEqual([2])
    expect(collectUnseenDueFollowUps(tasks, seen)).toEqual([])
  })
})
