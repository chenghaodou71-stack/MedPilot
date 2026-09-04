const BASE_URL = 'http://127.0.0.1:4173'

function envelope(data, meta) {
  return meta ? { success: true, data, meta } : { success: true, data }
}

function defaultRecord(page = 0) {
  return {
    id: page * 20 + 17,
    sessionId: 'session-record-e2e',
    symptoms: '咳嗽、低热',
    department: '呼吸内科',
    riskLevel: '中',
    urgency: '24 小时内就医',
    createdAt: '2026-08-17T08:30:00Z',
  }
}

function pendingKnowledgeDocument() {
  return {
    doc_id: 'e2e-respiratory-guide',
    department: '呼吸内科',
    source: '中华医学会｜呼吸道症状分诊指南',
    institution: '中华医学会',
    title: '呼吸道症状分诊指南',
    published_date: '2026-08-01',
    source_type: 'md',
    review_status: 'pending',
    parsing_status: 'completed',
    vector_status: 'pending',
    chunk_count: 2,
    text_preview: '咳嗽伴低热时应结合病程评估。',
  }
}

export async function installApiMocks(page, options = {}) {
  const state = {
    profile: options.profile ?? null,
    loginProfile: options.loginProfile ?? { username: 'patient-e2e', role: 'USER' },
    records: options.records ?? [defaultRecord()],
    knowledgeDocs: options.knowledgeDocs ? [...options.knowledgeDocs] : [],
    users: options.users ? [...options.users] : [
      {
        id: 1,
        username: 'admin',
        role: 'ADMIN',
        active: true,
        createdAt: '2026-08-01T08:00:00Z',
      },
      {
        id: 2,
        username: 'reviewer-01',
        role: 'REVIEWER',
        active: true,
        createdAt: '2026-08-02T08:00:00Z',
      },
    ],
    requests: [],
  }

  await page.context().addCookies([{
    name: 'XSRF-TOKEN',
    value: 'e2e-csrf-token',
    url: BASE_URL,
  }])

  await page.route(/^https?:\/\/[^/]+\/api(?:\/|$)/, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api/, '') || '/'
    const method = request.method()
    const requestEntry = {
      method,
      path,
      search: url.search,
      headers: request.headers(),
      body: request.postData() || '',
    }
    state.requests.push(requestEntry)

    const reply = (data, status = 200) => route.fulfill({ status, json: data })

    if (path === '/auth/csrf' && method === 'GET') {
      await reply(envelope({ token: 'e2e-csrf-token' }))
      return
    }
    if (path === '/auth/me' && method === 'GET') {
      if (state.profile) await reply(envelope(state.profile))
      else await reply({ success: false, error: 'Unauthorized' }, 401)
      return
    }
    if (path === '/auth/login' && method === 'POST') {
      state.profile = { ...state.loginProfile }
      await reply(envelope(state.profile))
      return
    }
    if (path === '/auth/logout' && method === 'POST') {
      state.profile = null
      await reply(envelope({ loggedOut: true }))
      return
    }
    if (path === '/profile/follow-ups/due' && method === 'GET') {
      await reply(envelope([]))
      return
    }
    if (path === '/records' && method === 'GET') {
      const requestedPage = Number(url.searchParams.get('page') || 0)
      const rows = options.recordsByPage
        ? [defaultRecord(requestedPage)]
        : state.records
      await reply(envelope(rows, {
        page: requestedPage,
        size: Number(url.searchParams.get('size') || 20),
        total: options.recordsTotal ?? rows.length,
        pages: options.recordPages ?? (rows.length ? 1 : 0),
      }))
      return
    }
    if (path === '/knowledge/stats' && method === 'GET') {
      await reply(envelope({
        total_docs: state.knowledgeDocs.length,
        pending_docs: state.knowledgeDocs.filter((doc) => doc.review_status === 'pending').length,
        total_chunks: state.knowledgeDocs.reduce((total, doc) => total + doc.chunk_count, 0),
        departments: { '呼吸内科': state.knowledgeDocs.length },
        active_version: 'v-e2e-active',
        parsing_statuses: { completed: state.knowledgeDocs.length },
        vector_statuses: { pending: state.knowledgeDocs.length },
        retrieval_requests: 10,
        retrieval_hits: 8,
        hit_rate: 0.8,
      }))
      return
    }
    if (path === '/knowledge/docs' && method === 'GET') {
      await reply(envelope({ docs: state.knowledgeDocs }))
      return
    }
    if (path === '/knowledge/versions' && method === 'GET') {
      await reply(envelope({
        current: 'v-e2e-active',
        versions: [{
          version: 'v-e2e-active',
          status: 'active',
          created_at: '2026-08-17T08:00:00Z',
          document_count: state.knowledgeDocs.length,
          chunk_count: state.knowledgeDocs.reduce((total, doc) => total + doc.chunk_count, 0),
        }],
      }))
      return
    }
    if (path === '/knowledge/upload' && method === 'POST') {
      const created = pendingKnowledgeDocument()
      state.knowledgeDocs = [created, ...state.knowledgeDocs.filter((doc) => doc.doc_id !== created.doc_id)]
      await reply(envelope(created))
      return
    }
    const reviewMatch = /^\/knowledge\/docs\/([^/]+)\/review$/.exec(path)
    if (reviewMatch && method === 'POST') {
      const docId = decodeURIComponent(reviewMatch[1])
      state.knowledgeDocs = state.knowledgeDocs.map((doc) => (
        doc.doc_id === docId ? { ...doc, review_status: 'approved', vector_status: 'completed' } : doc
      ))
      await reply(envelope({ doc_id: docId, status: 'approved', version: 'v-e2e-reviewed' }))
      return
    }
    if (path === '/admin/users' && method === 'GET') {
      await reply(envelope(state.users))
      return
    }
    if (path === '/admin/users' && method === 'POST') {
      const body = request.postDataJSON()
      state.users.push({
        id: Math.max(...state.users.map((user) => user.id), 0) + 1,
        username: body.username,
        role: body.role,
        active: true,
        createdAt: '2026-08-17T09:00:00Z',
      })
      await reply(envelope(state.users.at(-1)), 201)
      return
    }
    const userMatch = /^\/admin\/users\/(\d+)$/.exec(path)
    if (userMatch && method === 'PATCH') {
      const userId = Number(userMatch[1])
      const body = request.postDataJSON()
      state.users = state.users.map((user) => (
        user.id === userId ? { ...user, ...body, password: undefined } : user
      ))
      await reply(envelope(state.users.find((user) => user.id === userId)))
      return
    }
    if (userMatch && method === 'DELETE') {
      const userId = Number(userMatch[1])
      state.users = state.users.filter((user) => user.id !== userId)
      await reply(envelope({ deleted: true }))
      return
    }
    if (path === '/monitor/health' && method === 'GET') {
      await reply(envelope({
        ollama: { ok: true, model: 'qwen3:8b' },
        sessions: { active: 1 },
        knowledge: { docs: 4, index_loaded: true },
      }))
      return
    }
    if (path === '/monitor/traces' && method === 'GET') {
      await reply(envelope([], { page: 0, size: 10, total: 0, pages: 0 }))
      return
    }
    if (path === '/monitor/stats' && method === 'GET') {
      await reply(envelope({
        totalTraces: 4,
        completedTraces: 3,
        failedTraces: 1,
        cancelledTraces: 0,
        timeoutTraces: 0,
        averageDurationMs: 640,
        errorCodes: { upstream_failure: 1 },
      }))
      return
    }

    await reply({ success: false, error: `Unhandled E2E API route: ${method} ${path}` }, 501)
  })

  return state
}

export function consultationEvents() {
  const traceId = '2c293933-6590-4bfc-b0e8-507d3063c90b'
  const sessionId = '1779673a-c983-47e4-9715-f2d9548f469a'
  const base = (sequence, overrides) => ({
    protocol_version: '1.0',
    trace_id: traceId,
    session_id: sessionId,
    sequence,
    type: 'node',
    node: 'extract',
    status: 'started',
    elapsed_ms: 0,
    state: {
      intent: 'medical_consult',
      phase: 'collecting',
      turn_count: 1,
      history_mode: 'full',
    },
    data: {},
    ...overrides,
  })
  const answer = {
    text: '建议先休息，并在24小时内到呼吸内科就诊。',
    citations: [{ citation_id: 'resp-e2e#0' }],
    safety_boundary: '如出现呼吸困难或高热不退，请立即急诊。',
  }
  const evidence = {
    citation_id: 'resp-e2e#0',
    doc_id: 'resp-e2e',
    chunk_id: 'resp-e2e#0',
    source: '呼吸道症状分诊指南',
    department: '呼吸内科',
    quote: '咳嗽伴低热时应结合病程评估。',
    score: 0.86,
    index_version: 'v-e2e-active',
  }

  return [
    base(1, { node: 'safety_screen', state: { intent: 'medical_consult', phase: 'screening', turn_count: 1, history_mode: 'full' } }),
    base(2, {
      node: 'safety_screen',
      status: 'completed',
      state: { intent: 'medical_consult', phase: 'screening', turn_count: 1, history_mode: 'full' },
      data: { safety: { matched: false, matched_terms: [] } },
    }),
    base(3),
    base(4, {
      status: 'completed',
      elapsed_ms: 18,
      data: { symptoms: { symptoms: ['咳嗽', '低热'], raw_text: '咳嗽三天伴低热', duration: '3天' } },
    }),
    base(5, { node: 'retrieve', state: { intent: 'medical_consult', phase: 'retrieving', turn_count: 1, history_mode: 'full' } }),
    base(6, {
      node: 'retrieve',
      status: 'completed',
      state: { intent: 'medical_consult', phase: 'retrieving', turn_count: 1, history_mode: 'full' },
      data: { evidence: [evidence] },
    }),
    base(7, { node: 'classify', state: { intent: 'medical_consult', phase: 'triaging', turn_count: 1, history_mode: 'full' } }),
    base(8, {
      node: 'classify',
      status: 'completed',
      state: { intent: 'medical_consult', phase: 'triaging', turn_count: 1, history_mode: 'full' },
      data: {
        triage: {
          department: '呼吸内科',
          risk_level: '中',
          urgency: '24 小时内就医',
          support_score: 0.86,
          explanation: '依据症状和检索证据综合判断。',
          factors: [{ kind: 'evidence', label: '咳嗽伴低热', reference: evidence.citation_id, support: 0.86 }],
        },
      },
    }),
    base(9, { node: 'compose', state: { intent: 'medical_consult', phase: 'composing', turn_count: 1, history_mode: 'full' } }),
    base(10, {
      node: 'compose',
      status: 'completed',
      state: { intent: 'medical_consult', phase: 'composing', turn_count: 1, history_mode: 'full' },
      data: { answer },
    }),
    base(11, {
      type: 'answer_delta',
      node: undefined,
      status: 'streaming',
      state: { intent: 'medical_consult', phase: 'composing', turn_count: 1, history_mode: 'full' },
      data: { delta: '建议先休息，' },
    }),
    base(12, {
      type: 'answer_delta',
      node: undefined,
      status: 'streaming',
      state: { intent: 'medical_consult', phase: 'composing', turn_count: 1, history_mode: 'full' },
      data: { delta: '并在24小时内到呼吸内科就诊。' },
    }),
    base(13, {
      type: 'done',
      node: undefined,
      status: 'completed',
      state: { intent: 'medical_consult', phase: 'completed', turn_count: 1, history_mode: 'full' },
      data: { answer },
    }),
  ]
}

export async function installConsultStream(page) {
  const events = consultationEvents()
  const chunks = [
    `${events.slice(0, 11).map((event) => JSON.stringify(event)).join('\n')}\n`,
    `${JSON.stringify(events[11])}\n`,
    `${JSON.stringify(events[12])}\n`,
  ]
  await page.addInitScript(({ streamChunks }) => {
    const nativeFetch = window.fetch.bind(window)
    window.fetch = (input, init = {}) => {
      const url = new URL(String(input), window.location.origin)
      if (url.pathname !== '/api/consult') return nativeFetch(input, init)

      const encoder = new TextEncoder()
      const stream = new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode(streamChunks[0]))
          window.setTimeout(() => controller.enqueue(encoder.encode(streamChunks[1])), 1200)
          window.setTimeout(() => {
            controller.enqueue(encoder.encode(streamChunks[2]))
            controller.close()
          }, 1700)
        },
      })
      return Promise.resolve(new Response(stream, {
        status: 200,
        headers: { 'Content-Type': 'application/x-ndjson; charset=utf-8' },
      }))
    }
  }, { streamChunks: chunks })
}

export async function installCancellableConsult(page) {
  await page.addInitScript(() => {
    const nativeFetch = window.fetch.bind(window)
    window.__consultAbortObserved = false
    window.fetch = (input, init = {}) => {
      const url = new URL(String(input), window.location.origin)
      if (url.pathname !== '/api/consult') return nativeFetch(input, init)

      return new Promise((_resolve, reject) => {
        const rejectAsAborted = () => {
          window.__consultAbortObserved = true
          reject(new DOMException('The operation was aborted.', 'AbortError'))
        }
        if (init.signal?.aborted) rejectAsAborted()
        else init.signal?.addEventListener('abort', rejectAsAborted, { once: true })
      })
    }
  })
}

export async function installMonitorEventSource(page) {
  const activeTrace = {
    requestId: 'request-live-e2e',
    traceId: '2c293933-6590-4bfc-b0e8-507d3063c90b',
    sessionId: '1779673a-c983-47e4-9715-f2d9548f469a',
    status: 'active',
    startedAt: '2026-08-17T10:00:00Z',
    events: consultationEvents().slice(0, 1),
  }
  await page.addInitScript(({ trace }) => {
    class MockEventSource {
      static CONNECTING = 0
      static OPEN = 1
      static CLOSED = 2

      constructor(url, options) {
        this.url = url
        this.withCredentials = Boolean(options?.withCredentials)
        this.readyState = MockEventSource.CONNECTING
        this.listeners = new Map()
        window.setTimeout(() => {
          if (this.readyState === MockEventSource.CLOSED) return
          this.readyState = MockEventSource.OPEN
          this.onopen?.(new Event('open'))
          this.emit('snapshot', { traces: [] })
          this.emit('started', { trace })
        }, 30)
      }

      addEventListener(type, listener) {
        const listeners = this.listeners.get(type) || []
        listeners.push(listener)
        this.listeners.set(type, listeners)
      }

      emit(type, payload) {
        const event = new MessageEvent(type, { data: JSON.stringify(payload) })
        for (const listener of this.listeners.get(type) || []) listener(event)
      }

      close() {
        this.readyState = MockEventSource.CLOSED
      }
    }

    window.EventSource = MockEventSource
  }, { trace: activeTrace })
}
