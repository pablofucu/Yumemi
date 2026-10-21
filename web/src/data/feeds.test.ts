import { describe, expect, it } from 'vitest'
import { parseEnvelope, parseHistory, parseIndex } from './feeds'

describe('feeds parser', () => {
  it('reads the index and drops unsafe or duplicate tags', () => {
    const body = JSON.stringify({
      feeds: [{ tag: 'luz', title: 'Precio de la luz', emoji: '⚡', job_id: 'j1' }, { tag: '../x' }, { tag: 'luz' }, { tag: 'bolsa' }],
    })
    const feeds = parseIndex(body)
    expect(feeds.map((f) => f.tag)).toEqual(['luz', 'bolsa'])
    expect(feeds[0]).toMatchObject({ title: 'Precio de la luz', emoji: '⚡', jobId: 'j1' })
    expect(feeds[1].title).toBe('Bolsa')
    expect(parseIndex('basura')).toEqual([])
  })

  it('parses every block type and skips malformed ones', () => {
    const env = parseEnvelope(
      JSON.stringify({
        tag: 'luz',
        run_at: '2026-10-20T07:00:00Z',
        status: 'failed',
        summary: 'ok',
        view: [
          { type: 'stat', label: 'Media', value: '0,12', delta: '-3%' },
          { type: 'stat', label: 'sin valor' },
          { type: 'kv', items: [['a', 1], { label: 'b', value: 'x' }, 5] },
          { type: 'table', columns: ['h', 'p'], rows: [[1, 'a'], 'mal'] },
          { type: 'series', label: 'p', unit: '€', points: [1, ['x', 2], { date: 'd', y: 3 }, 'no'] },
          { type: 'link', label: 'web', url: 'javascript:alert(1)' },
          { type: 'link', url: 'https://example.com' },
          { type: 'futuro', text: 'hola' },
        ],
      }),
    )!
    expect(env.status).toBe('error')
    expect(env.runAt).toBe(Date.parse('2026-10-20T07:00:00Z'))
    expect(env.view.map((b) => b.type)).toEqual(['stat', 'kv', 'table', 'series', 'link', 'text'])
    expect(env.view[1]).toMatchObject({ items: [['a', '1'], ['b', 'x']] })
    expect(env.view[2]).toMatchObject({ rows: [['1', 'a']] })
    expect(env.view[3]).toMatchObject({ points: [['1', 1], ['x', 2], ['d', 3]] })
  })

  it('returns history newest first', () => {
    const lines = [{ tag: 't', run_at: 100 }, { tag: 't', run_at: 300 }, { tag: 't', run_at: 200 }].map((o) => JSON.stringify(o)).join('\n')
    expect(parseHistory(lines + '\nbasura\n').map((e) => e.runAt)).toEqual([300000, 200000, 100000])
  })
})
