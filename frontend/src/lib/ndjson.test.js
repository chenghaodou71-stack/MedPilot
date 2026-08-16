import { describe, expect, it } from 'vitest'

import { NdjsonProtocolError, parseNdjsonChunk } from './ndjson'

describe('parseNdjsonChunk', () => {
  it('parses multiple lines and ignores blank CRLF lines', () => {
    const result = parseNdjsonChunk('', '{"sequence":1}\r\n\r\n{"sequence":2}\n')

    expect(result.events).toEqual([{ sequence: 1 }, { sequence: 2 }])
    expect(result.buffer).toBe('')
  })

  it('keeps a JSON object split across chunks', () => {
    const first = parseNdjsonChunk('', '{"sequence":')
    const second = parseNdjsonChunk(first.buffer, '1}\n')

    expect(first.events).toEqual([])
    expect(second.events).toEqual([{ sequence: 1 }])
    expect(second.buffer).toBe('')
  })

  it('flushes a final line without a newline exactly once', () => {
    const pending = parseNdjsonChunk('', '{"sequence":1}')
    const flushed = parseNdjsonChunk(pending.buffer, '', { flush: true })
    const empty = parseNdjsonChunk(flushed.buffer, '', { flush: true })

    expect(flushed.events).toEqual([{ sequence: 1 }])
    expect(flushed.buffer).toBe('')
    expect(empty.events).toEqual([])
  })

  it('throws a protocol error for an invalid complete line', () => {
    expect(() => parseNdjsonChunk('', '{invalid}\n')).toThrow(NdjsonProtocolError)
  })
})
