export class NdjsonProtocolError extends Error {
  constructor(message, options) {
    super(message, options)
    this.name = 'NdjsonProtocolError'
  }
}

function parseLine(line) {
  const value = line.replace(/\r$/, '').trim()
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch (cause) {
    throw new NdjsonProtocolError('服务返回了无效的 NDJSON 事件。', { cause })
  }
}

/** Parse a text chunk without losing a JSON object split across chunks. */
export function parseNdjsonChunk(buffer = '', chunk = '', { flush = false } = {}) {
  const lines = `${buffer}${chunk}`.split('\n')
  const remainder = flush ? '' : (lines.pop() || '')
  const events = []

  for (const line of lines) {
    const event = parseLine(line)
    if (event) events.push(event)
  }

  if (flush && remainder === '') {
    return { events, buffer: '' }
  }
  return { events, buffer: remainder }
}
