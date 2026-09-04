<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Activity,
  BookOpenCheck,
  ClipboardPenLine,
  ShieldAlert,
  Sparkles,
} from 'lucide-vue-next'
import {
  ArrowLeft,
  ArrowRight,
  ChatDotRound,
  CircleCheck,
  Clock,
  Close,
  Delete,
  Document,
  EditPen,
  FirstAidKit,
  InfoFilled,
  Promotion,
  Reading,
  RefreshRight,
  Search,
  Upload,
  User,
  WarningFilled,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import client, { apiFetch } from '../api/client'
import doctorIllustration from '../assets/illustrations/doctor.svg'
import AgentFlowGraph from '../components/AgentFlowGraph.vue'
import DecisionFlowGraph from '../components/DecisionFlowGraph.vue'
import TriageScalePanel from '../components/TriageScalePanel.vue'
import { parseNdjsonChunk } from '../lib/ndjson'
import {
  ConsultTraceProtocolError,
  createConsultTraceState,
  reduceConsultTraceEvent,
} from '../lib/consultTrace'
import { createCanonicalUuid } from '../lib/uuid'
import {
  buildInitialConsultText as serializeConsultInput,
  isQuickConsultReady,
  normalizeQuickConsultText,
} from '../lib/consultInput'
import {
  isEmergencyWorkflow,
  resolveWorkflowAgentKeys,
  summarizeWorkflowProgress,
} from '../lib/consultWorkflow'
import { normalizeTriageFactors } from '../lib/triageExplanation'
import {
  appendConfirmedAttachments,
  ATTACHMENT_ACCEPT,
  formatAttachmentSize,
  normalizeAttachment,
  validateAttachmentCandidate,
} from '../lib/consultAttachments'
import { describeConsultError } from '../lib/consultError'
import {
  clearConsultDraft,
  createConsultDraft,
  loadConsultDraft,
  saveConsultDraft,
} from '../lib/consultDraft'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const SYMPTOM_OPTIONS = [
  '头痛',
  '发热',
  '咳嗽',
  '咽痛',
  '胸痛',
  '胸闷',
  '呼吸困难',
  '心悸',
  '腹痛',
  '恶心',
  '呕吐',
  '头晕',
  '乏力',
  '皮疹',
  '其他',
]

const DURATION_OPTIONS = ['1 天以内', '1-3 天', '4-7 天', '1-2 周', '2 周以上', '反复发作']
const SEVERITY_OPTIONS = ['轻微', '中等', '严重', '暂不确定']
const SETTINGS_KEY = 'medpilot-user-settings'

const AGENTS = [
  {
    key: 'safety_screen',
    number: '01',
    title: '医疗安全筛查',
    shortTitle: '安全筛查',
    description: '优先识别胸痛、呼吸困难等危险信号',
    icon: ShieldAlert,
    tone: 'red',
  },
  {
    key: 'extract',
    number: '02',
    title: '信息采集智能体',
    shortTitle: '信息采集',
    description: '识别症状、病史与危险信号',
    icon: ClipboardPenLine,
    tone: 'blue',
  },
  {
    key: 'retrieve',
    number: '03',
    title: '医学知识检索智能体',
    shortTitle: '知识检索',
    description: '匹配本地医学知识与诊疗依据',
    icon: BookOpenCheck,
    tone: 'green',
  },
  {
    key: 'classify',
    number: '04',
    title: '辅助分诊智能体',
    shortTitle: '辅助分诊',
    description: '评估风险等级并推荐就诊科室',
    icon: Activity,
    tone: 'purple',
  },
  {
    key: 'compose',
    number: '05',
    title: '建议编排智能体',
    shortTitle: '建议生成',
    description: '汇总建议、证据与安全边界',
    icon: Sparkles,
    tone: 'orange',
  },
]

const EMERGENCY_AGENTS = [
  {
    ...AGENTS[0],
    number: '01',
    title: '危险信号筛查',
    shortTitle: '危险筛查',
    description: '优先识别需要立即处置的危险信号',
  },
  {
    ...AGENTS[3],
    number: '02',
    title: '安全规则分级',
    shortTitle: '规则分级',
    description: '依据本地安全规则确定风险与就医时效',
  },
  {
    ...AGENTS[4],
    number: '03',
    title: '紧急行动指令',
    shortTitle: '行动指令',
    description: '直接生成清晰、可执行的紧急就医建议',
  },
]

const FOLLOWUP_AGENT = {
  key: 'ask_followup',
  number: '03',
  title: '补充问诊智能体',
  shortTitle: '补充问诊',
  description: '识别缺失信息并生成需要补充的问题',
  icon: ChatDotRound,
  tone: 'blue',
}

const WORKFLOW_AGENT_CATALOG = [...AGENTS, FOLLOWUP_AGENT]

const stage = ref('landing')
const workflowStep = ref(0)
const quickInput = ref('')
const quickInputImported = ref(false)
const selectedSymptoms = ref([])
const attachmentInput = ref(null)
const attachments = ref([])
const isUploadingAttachment = ref(false)
const confirmingAttachmentId = ref('')
const form = reactive({
  name: '',
  gender: '',
  age: null,
  duration: '',
  severity: '',
  description: '',
})

const sessionId = ref(createSessionId())
const agentStatus = reactive(createAgentStatus())
const progress = ref(0)
const isSubmitting = ref(false)
const abortController = ref(null)
const requestError = ref('')
const requestErrorSource = ref(null)
const intakeTouched = reactive({ symptoms: false, description: false })
const feedbackSelection = ref('')
const feedbackSubmitted = ref(false)
const lastSubmittedText = ref('')
const followupQuestion = ref('')
const followupInput = ref('')
const conversation = ref([])
const traceState = ref(createConsultTraceState())
const traceRuns = ref([])

const structuredSymptoms = ref(null)
const evidenceList = ref([])
const triageData = ref(null)
const answerText = ref('')
const answerCitations = ref([])
const safetyBoundary = ref('')
const resultCreatedAt = ref(null)

const recentRecords = ref([])
const recentLoading = ref(true)
const pendingDraft = ref(null)
const draftSaveSuspended = ref(false)
let draftSaveTimer = null

const displayName = computed(() => {
  if (!auth.username) return '用户'
  return auth.username === 'admin' ? '管理员' : auth.username
})

const canSubmitIntake = computed(
  () => selectedSymptoms.value.length > 0 && isQuickConsultReady(form.description),
)
const canSubmitFollowup = computed(() => followupInput.value.trim().length >= 1)
const followupMissing = computed(() => traceState.value.followup?.missing || [])
const followupSuggestions = computed(() => {
  const missing = new Set(followupMissing.value)
  if (missing.has('duration')) return ['今天开始', '1-3 天', '超过一周', '暂时无法回答']
  if (missing.has('severity')) return ['轻微', '中等', '严重', '不确定']
  if (missing.has('more_symptoms')) return ['没有其他不适', '还有其他不适', '不确定']
  return ['不确定', '暂时无法回答']
})
const followupExample = computed(() => {
  const missing = new Set(followupMissing.value)
  if (missing.has('duration')) return '例如：刚开始 2 天、反复一个月'
  if (missing.has('severity')) return '例如：轻微、不影响活动，或正在加重'
  if (missing.has('more_symptoms')) return '例如：没有，或还有头晕、乏力'
  return '可以选择“不确定”，也可以先跳过这一项。'
})
const stageContext = computed(() => {
  if (stage.value === 'intake') {
    return { title: '第 1/3 步：补充症状', detail: '补充越完整，风险评估和科室建议越可靠。' }
  }
  if (stage.value === 'processing') {
    return { title: '第 2/3 步：安全分析中', detail: '系统会先检查危险信号，再整理症状和医学依据。' }
  }
  if (stage.value === 'result') {
    return { title: '第 3/3 步：查看分诊建议', detail: '先看行动建议，再展开判定依据和医学来源。' }
  }
  return null
})
const requestErrorInfo = computed(() => (
  requestErrorSource.value
    ? describeConsultError(requestErrorSource.value)
    : requestError.value
      ? { title: '问诊服务暂时不可用', detail: requestError.value, action: 'retry' }
      : null
))
const journeySteps = [
  { number: '1', label: '补充症状', stage: 'intake' },
  { number: '2', label: '安全分析', stage: 'processing' },
  { number: '3', label: '查看建议', stage: 'result' },
]
const processingSteps = computed(() => (isEmergencyFastPath.value
  ? [
      { key: 'safety_screen', label: '正在检查危险信号' },
      { key: 'classify', label: '正在确认风险与就医时效' },
      { key: 'compose', label: '正在生成紧急行动建议' },
    ]
  : [
      { key: 'safety_screen', label: '正在检查危险信号' },
      { key: 'extract', label: '正在识别症状' },
      { key: 'retrieve', label: '正在匹配医学资料' },
      { key: 'compose', label: '正在生成分诊建议' },
    ]))
const isHighRisk = computed(
  () => Boolean(traceState.value.emergency) || triageData.value?.risk_level === '高',
)
const isEmergencyFastPath = computed(() => isEmergencyWorkflow(traceState.value))
const workflowAgents = computed(() => {
  const activeKeys = new Set(resolveWorkflowAgentKeys(traceState.value))
  const catalog = isEmergencyFastPath.value ? EMERGENCY_AGENTS : WORKFLOW_AGENT_CATALOG
  return catalog.filter((agent) => activeKeys.has(agent.key))
})

const emergencyNotice = computed(() => {
  const emergency = traceState.value.emergency
  if (!emergency) return null
  return {
    title: '检测到高风险信号，请立即采取行动',
    detail: emergency.urgency || '请立即就医或拨打 120，并避免自行驾车。',
  }
})

const currentTraceElapsedMs = computed(() => Object.values(traceState.value.nodes)
  .reduce((total, node) => total + (Number(node.elapsedMs) || 0), 0))

const hasSupportScore = computed(() => (
  Object.prototype.hasOwnProperty.call(triageData.value || {}, 'support_score')
))
const triageFactors = computed(() => normalizeTriageFactors(triageData.value?.factors))

const resultSymptoms = computed(() => {
  const extracted = structuredSymptoms.value?.symptoms
  return extracted?.length ? extracted : selectedSymptoms.value
})
const decisionSymptoms = computed(() => (
  structuredSymptoms.value || { symptoms: resultSymptoms.value }
))

const workflowProgress = computed(() => summarizeWorkflowProgress(
  workflowAgents.value.map((agent) => agent.key),
  agentStatus,
  traceState.value.status === 'done' && !traceState.value.awaitingFollowup,
))
const completedAgentCount = computed(() => workflowProgress.value.completed)
const supportScoreLabel = computed(() => (
  hasSupportScore.value
    ? (isEmergencyFastPath.value || Boolean(triageData.value?.matched_rule) ? '规则支持分' : '检索支持度')
    : '辅助依据评分'
))

const visualizationSupportScore = computed(() => (
  hasSupportScore.value
    ? triageData.value?.support_score
    : triageData.value?.confidence ?? ''
))

function createSessionId() {
  return createCanonicalUuid()
}

function createAgentStatus() {
  return {
    safety_screen: 'waiting',
    extract: 'waiting',
    retrieve: 'waiting',
    classify: 'waiting',
    compose: 'waiting',
    ask_followup: 'waiting',
  }
}

function resetAgentStatus() {
  Object.assign(agentStatus, createAgentStatus())
}

function stepStatus(agentKey) {
  return {
    waiting: 'wait',
    running: 'process',
    done: 'success',
    error: 'error',
  }[agentStatus[agentKey]] || 'wait'
}

function processingStepState(key) {
  if (key === 'compose' && agentStatus.classify === 'done' && agentStatus.compose === 'waiting') {
    return 'waiting'
  }
  return agentStatus[key] || 'waiting'
}

function traceEventLabel(event) {
  if (event.type === 'answer_delta') return '回答输出'
  if (event.type === 'done') return '流程完成'
  if (event.type === 'error') return '流程异常'
  if (event.node === 'ask_followup') return '主动追问'
  return workflowAgents.value.find((agent) => agent.key === event.node)?.shortTitle
    || WORKFLOW_AGENT_CATALOG.find((agent) => agent.key === event.node)?.shortTitle
    || event.label
    || event.node
}

function traceEventStatus(event) {
  return {
    started: '开始',
    streaming: '输出中',
    completed: '完成',
    error: '异常',
  }[event.status] || event.status
}

function draftUsername() {
  return auth.username || 'anonymous'
}

function hasDraftContent() {
  return Boolean(
    quickInput.value.trim()
    || selectedSymptoms.value.length
    || form.description.trim()
    || form.name.trim()
    || form.gender
    || form.age
    || form.duration
    || form.severity
    || attachments.value.length,
  )
}

function persistConsultDraft() {
  if (draftSaveSuspended.value || !['landing', 'intake'].includes(stage.value)) return
  if (!hasDraftContent()) {
    clearConsultDraft(draftUsername())
    return
  }
  saveConsultDraft(draftUsername(), createConsultDraft({
    stage: stage.value,
    sessionId: sessionId.value,
    quickInput: quickInput.value,
    selectedSymptoms: selectedSymptoms.value,
    form,
    attachments: attachments.value,
  }))
}

function scheduleConsultDraftSave() {
  if (draftSaveTimer) window.clearTimeout(draftSaveTimer)
  draftSaveTimer = window.setTimeout(() => {
    draftSaveTimer = null
    persistConsultDraft()
  }, 250)
}

function restoreDraft() {
  const draft = pendingDraft.value
  if (!draft) return
  draftSaveSuspended.value = true
  sessionId.value = draft.sessionId || createSessionId()
  quickInput.value = draft.quickInput
  quickInputImported.value = Boolean(draft.quickInput)
  selectedSymptoms.value = [...draft.selectedSymptoms]
  Object.assign(form, draft.form)
  attachments.value = draft.attachments.map(normalizeAttachment)
  stage.value = 'intake'
  workflowStep.value = 0
  pendingDraft.value = null
  draftSaveSuspended.value = false
  ElMessage.success('已恢复上次未完成的问诊')
  scrollToTop()
}

function discardDraft() {
  pendingDraft.value = null
  clearConsultDraft(draftUsername())
  ElMessage.info('已清除未完成的问诊草稿')
}

function formatDraftTime(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '最近保存'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function missingLabel(missing) {
  return {
    symptoms: '主要症状',
    more_symptoms: '其他不适',
    duration: '持续时间',
    severity: '严重程度',
  }[missing] || missing
}

function beginIntake() {
  const quickDescription = normalizeQuickConsultText(quickInput.value)
  quickInputImported.value = Boolean(quickDescription)
  if (quickDescription) form.description = quickDescription
  stage.value = 'intake'
  workflowStep.value = 0
  scrollToTop()
}

function buildInitialConsultText() {
  return appendConfirmedAttachments(
    serializeConsultInput({ form, selectedSymptoms: selectedSymptoms.value }),
    attachments.value,
  )
}

function openAttachmentPicker() {
  attachmentInput.value?.click()
}

async function uploadAttachment(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const validationError = validateAttachmentCandidate(file)
  if (validationError) {
    ElMessage.warning(validationError)
    return
  }

  isUploadingAttachment.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    body.append('session_id', sessionId.value)
    const response = await client.post('/consult/attachments', body)
    attachments.value.push(normalizeAttachment(response.data?.data || {}))
    ElMessage.success('附件已上传，请确认草稿后再提交')
  } catch (error) {
    const message = error.response?.data?.error || '附件上传失败，请稍后重试'
    ElMessage.error(message)
  } finally {
    isUploadingAttachment.value = false
  }
}

async function confirmAttachment(attachment) {
  const draftText = String(attachment.draftText || '').trim()
  if (!draftText) {
    ElMessage.warning('请先补充附件说明，再确认')
    return
  }
  confirmingAttachmentId.value = attachment.id
  try {
    const response = await client.patch(
      `/consult/attachments/${encodeURIComponent(attachment.id)}/confirm`,
      { draftText },
    )
    Object.assign(attachment, normalizeAttachment(response.data?.data || {}))
    ElMessage.success('附件草稿已确认，将随本次问诊提交')
  } catch (error) {
    ElMessage.error(error.response?.data?.error || '附件确认失败，请稍后重试')
  } finally {
    confirmingAttachmentId.value = ''
  }
}

async function removeAttachment(attachment) {
  try {
    await client.delete(`/consult/attachments/${encodeURIComponent(attachment.id)}`)
    attachments.value = attachments.value.filter((item) => item.id !== attachment.id)
  } catch (error) {
    ElMessage.error(error.response?.data?.error || '附件移除失败，请稍后重试')
  }
}

function resetResultData() {
  structuredSymptoms.value = null
  evidenceList.value = []
  triageData.value = null
  answerText.value = ''
  answerCitations.value = []
  safetyBoundary.value = ''
  resultCreatedAt.value = null
  requestError.value = ''
  requestErrorSource.value = null
  followupQuestion.value = ''
  followupInput.value = ''
  traceState.value = createConsultTraceState()
  traceRuns.value = []
}

async function submitIntake() {
  intakeTouched.symptoms = true
  intakeTouched.description = true
  if (!canSubmitIntake.value) {
    ElMessage.warning('请先完成标有“必填”的内容')
    return
  }

  resetResultData()
  resetAgentStatus()
  conversation.value = []
  const text = buildInitialConsultText()
  conversation.value.push({ role: 'user', text: form.description.trim() })
  await requestConsult(text)
}

async function submitFollowup(value = followupInput.value) {
  const text = String(value || '').trim()
  if (!text || isSubmitting.value) return

  conversation.value.push({ role: 'user', text })
  followupInput.value = ''
  followupQuestion.value = ''
  resetAgentStatus()
  await requestConsult(text)
}

function selectFollowupSuggestion(value) {
  followupInput.value = value
  submitFollowup(value)
}

async function requestConsult(text) {
  stage.value = 'processing'
  workflowStep.value = 0
  progress.value = 0
  requestError.value = ''
  requestErrorSource.value = null
  feedbackSelection.value = ''
  feedbackSubmitted.value = false
  followupQuestion.value = ''
  structuredSymptoms.value = null
  evidenceList.value = []
  triageData.value = null
  answerText.value = ''
  answerCitations.value = []
  safetyBoundary.value = ''
  resultCreatedAt.value = null
  lastSubmittedText.value = text
  isSubmitting.value = true
  traceState.value = createConsultTraceState()
  resetAgentStatus()

  const controller = new AbortController()
  abortController.value = controller

  try {
    const response = await apiFetch('/api/consult', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ text, session_id: sessionId.value }),
      signal: controller.signal,
    })

    if (!response.ok) {
      const payload = await response.json().catch(() => null)
      const requestFailure = new Error(payload?.error || `问诊服务请求失败（${response.status}）`)
      requestFailure.status = response.status
      throw requestFailure
    }
    if (!response.body) {
      throw new Error('问诊服务未返回可读取的响应流。')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let receivedTerminalEvent = false

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const parsed = parseNdjsonChunk(buffer, decoder.decode(value, { stream: true }))
      buffer = parsed.buffer
      for (const event of parsed.events) {
        if (applyTraceEvent(event)) receivedTerminalEvent = true
      }
    }

    const parsed = parseNdjsonChunk(buffer, decoder.decode(), { flush: true })
    for (const event of parsed.events) {
      if (applyTraceEvent(event)) receivedTerminalEvent = true
    }

    if (!receivedTerminalEvent && !requestError.value) {
      throw new Error('问诊服务响应提前结束，请重新发起问诊。')
    }
  } catch (error) {
    requestErrorSource.value = error
    if (error instanceof ConsultTraceProtocolError || error.name === 'NdjsonProtocolError') {
      requestErrorSource.value = { status: 503 }
    }
    requestError.value = describeConsultError(requestErrorSource.value).detail
    markRunningAgentAsError()
  } finally {
    isSubmitting.value = false
    if (abortController.value === controller) abortController.value = null
  }
}

function consultationNotificationsEnabled() {
  try {
    const settings = JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null')
    return settings?.notifications?.consultationComplete !== false
  } catch {
    return true
  }
}

function applyTraceEvent(event) {
  const next = reduceConsultTraceEvent(traceState.value, event)
  traceState.value = next
  syncTraceUi(next)
  saveTraceSnapshot(next)

  if (next.status === 'error') {
    requestErrorSource.value = { status: 503 }
    requestError.value = next.error || '智能问诊服务发生异常，请稍后重试。'
    return true
  }
  if (next.status !== 'done') return false

  if (next.awaitingFollowup) {
    const question = next.followup?.question || '请继续补充您的症状信息。'
    followupQuestion.value = question
    if (conversation.value.at(-1)?.text !== question) {
      conversation.value.push({ role: 'assistant', text: question })
    }
    progress.value = workflowProgress.value.percentage
    return true
  }

  if (!next.answer?.text) {
    throw new ConsultTraceProtocolError('完成事件缺少回答或追问信息。')
  }

  if (conversation.value.at(-1)?.text !== next.answer.text) {
    conversation.value.push({ role: 'assistant', text: next.answer.text })
  }
  progress.value = 100
  workflowStep.value = workflowAgents.value.length
  stage.value = 'result'
  resultCreatedAt.value = new Date()
  clearConsultDraft(draftUsername())
  if (consultationNotificationsEnabled()) ElMessage.success('问诊分析已完成')
  scrollToTop()
  return true
}

function syncTraceUi(state) {
  for (const agent of WORKFLOW_AGENT_CATALOG) {
    agentStatus[agent.key] = state.nodes[agent.key]?.status || 'waiting'
  }

  if (state.symptoms) structuredSymptoms.value = state.symptoms
  if (state.nodes.retrieve?.status === 'done') evidenceList.value = state.evidence
  if (state.triage) triageData.value = state.triage
  // Compose completion carries the full answer as a validation target. The
  // user sees only the deltas already received; the terminal event promotes
  // the validated answer to the final display.
  answerText.value = state.answer?.text || state.streamedAnswerText || ''
  answerCitations.value = state.citations
  safetyBoundary.value = state.answer?.safety_boundary
    || state.expectedAnswer?.safety_boundary
    || ''

  const summary = summarizeWorkflowProgress(
    workflowAgents.value.map((agent) => agent.key),
    agentStatus,
  )
  progress.value = summary.percentage
  workflowStep.value = summary.activeStep
}

function saveTraceSnapshot(state) {
  if (!state.traceId) return
  const snapshot = {
    traceId: state.traceId,
    sessionId: state.sessionId,
    status: state.status,
    phase: state.phase,
    elapsedMs: Object.values(state.nodes)
      .reduce((total, node) => total + (Number(node.elapsedMs) || 0), 0),
    events: [...state.events],
  }
  const index = traceRuns.value.findIndex((run) => run.traceId === state.traceId)
  if (index === -1) traceRuns.value.push(snapshot)
  else traceRuns.value[index] = snapshot
}

function markRunningAgentAsError() {
  const runningKey = WORKFLOW_AGENT_CATALOG.find((agent) => agentStatus[agent.key] === 'running')?.key
  if (runningKey) agentStatus[runningKey] = 'error'
}

function cancelConsult() {
  abortController.value?.abort()
}

function retryConsult() {
  if (!lastSubmittedText.value || isSubmitting.value) return
  requestError.value = ''
  requestErrorSource.value = null
  resetAgentStatus()
  requestConsult(lastSubmittedText.value)
}

function backToIntake() {
  abortController.value?.abort()
  stage.value = 'intake'
  workflowStep.value = 0
  requestError.value = ''
  requestErrorSource.value = null
  scrollToTop()
}

function startNewConsult() {
  abortController.value?.abort()
  draftSaveSuspended.value = true
  if (draftSaveTimer) window.clearTimeout(draftSaveTimer)
  draftSaveTimer = null
  clearConsultDraft(draftUsername())
  pendingDraft.value = null
  sessionId.value = createSessionId()
  stage.value = 'landing'
  workflowStep.value = 0
  quickInput.value = ''
  quickInputImported.value = false
  selectedSymptoms.value = []
  attachments.value = []
  Object.assign(form, {
    name: '',
    gender: '',
    age: null,
    duration: '',
    severity: '',
    description: '',
  })
  conversation.value = []
  progress.value = 0
  lastSubmittedText.value = ''
  resetAgentStatus()
  resetResultData()
  intakeTouched.symptoms = false
  intakeTouched.description = false
  feedbackSelection.value = ''
  feedbackSubmitted.value = false
  fetchRecentRecords()
  draftSaveSuspended.value = false
  scrollToTop()
}

function formatEvidenceScore(score) {
  const value = Number(score)
  if (!Number.isFinite(value)) return ''
  const percent = value <= 1 ? value * 100 : value
  return `${Math.max(0, Math.min(100, Math.round(percent)))}%`
}

function formatEvidenceType(sourceType) {
  return {
    pdf: 'PDF',
    md: 'Markdown',
    txt: '纯文本',
    text: '文字资料',
  }[String(sourceType || '').toLowerCase()] || '医学知识库资料'
}

function riskTagType(level) {
  return { 高: 'danger', 中: 'warning', 低: 'success' }[level] || 'info'
}

function recordTitle(record) {
  const symptoms = record.symptoms?.replace(/;\s*/g, '、').replace(/、$/, '')
  return symptoms || '问诊记录'
}

function formatDate(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function submitResultFeedback(value) {
  feedbackSelection.value = value
  feedbackSubmitted.value = true
  ElMessage.success('感谢反馈，我们会把它用于改进后续问诊体验')
}

watch(
  [stage, quickInput, selectedSymptoms, form, attachments],
  () => scheduleConsultDraftSave(),
  { deep: true },
)

async function fetchRecentRecords() {
  recentLoading.value = true
  try {
    const response = await client.get('/records')
    recentRecords.value = (response.data?.data || []).slice(0, 3)
  } catch {
    recentRecords.value = []
  } finally {
    recentLoading.value = false
  }
}

onMounted(() => {
  const symptom = Array.isArray(route.query.symptom) ? route.query.symptom[0] : route.query.symptom
  if (symptom) quickInput.value = String(symptom)
  if (!symptom) pendingDraft.value = loadConsultDraft(draftUsername())
  fetchRecentRecords()
})

onBeforeUnmount(() => {
  abortController.value?.abort()
  if (draftSaveTimer) window.clearTimeout(draftSaveTimer)
})
</script>

<template>
  <div class="consult-page">
    <template v-if="stage === 'landing'">
      <section
        v-if="pendingDraft"
        class="draft-resume-banner"
        role="status"
        data-testid="draft-resume-banner"
      >
        <div class="draft-resume-copy">
          <strong>发现未完成的问诊</strong>
          <p>上次填写于 {{ formatDraftTime(pendingDraft.savedAt) }}，草稿仅保存在当前浏览器 24 小时。</p>
        </div>
        <div class="draft-resume-actions">
          <el-button text @click="discardDraft">清除草稿</el-button>
          <el-button type="primary" @click="restoreDraft">继续填写</el-button>
        </div>
      </section>
      <section class="welcome-banner" aria-labelledby="welcome-title">
        <div class="welcome-content">
          <div class="welcome-kicker">
            <span class="online-dot" />
            智能问诊服务已就绪
          </div>
          <h1 id="welcome-title">您好，{{ displayName }}</h1>
          <p class="welcome-subtitle">请描述您的健康问题，多智能体将协同提供辅助分诊建议。</p>

          <div class="quick-consult">
            <el-input
              v-model="quickInput"
              size="large"
              clearable
              aria-label="症状描述"
              placeholder="请描述您的症状，如：咳嗽三天，伴有低烧和乏力"
              @keyup.enter="beginIntake"
            >
              <template #prefix><el-icon><EditPen /></el-icon></template>
            </el-input>
            <el-button
              type="primary"
              size="large"
              class="quick-start-button"
              data-testid="quick-start-button"
              @click="beginIntake"
            >
              <el-icon><Promotion /></el-icon>
              开始问诊
            </el-button>
          </div>
        </div>

        <div class="doctor-visual" aria-hidden="true">
          <img :src="doctorIllustration" alt="" />
        </div>
      </section>

      <section class="workflow-panel" aria-labelledby="workflow-title">
        <div class="section-heading">
          <div>
            <span class="section-kicker">分支协同流程</span>
            <h2 id="workflow-title">先完成安全筛查，再进入对应处理路径</h2>
          </div>
          <el-tag type="success" effect="light" round>
            <span class="tag-dot" />安全筛查优先执行
          </el-tag>
        </div>

        <div class="workflow-entry">
          <article class="workflow-node workflow-node-entry" :class="`tone-${AGENTS[0].tone}`">
            <div class="workflow-icon">
              <component :is="AGENTS[0].icon" :size="24" :stroke-width="1.8" aria-hidden="true" />
              <span>{{ AGENTS[0].number }}</span>
            </div>
            <strong>{{ AGENTS[0].shortTitle }}</strong>
            <p>{{ AGENTS[0].description }}</p>
          </article>
          <div class="workflow-fork" aria-hidden="true"><i /><i /><i /></div>
        </div>

        <div class="workflow-lanes">
          <section class="workflow-lane standard-lane" aria-label="常规协同路径">
            <header>
              <span>常规协同路径</span>
              <small>未命中危险信号</small>
            </header>
            <div class="workflow-lane-track">
              <template v-for="(agent, index) in AGENTS.slice(1)" :key="agent.key">
                <article class="workflow-node" :class="`tone-${agent.tone}`">
                  <div class="workflow-icon">
                    <component :is="agent.icon" :size="22" :stroke-width="1.8" aria-hidden="true" />
                    <span>{{ agent.number }}</span>
                  </div>
                  <strong>{{ agent.shortTitle }}</strong>
                  <p>{{ agent.description }}</p>
                </article>
                <div v-if="index < AGENTS.length - 2" class="workflow-arrow" aria-hidden="true">
                  <el-icon :size="17"><ArrowRight /></el-icon>
                </div>
              </template>
            </div>
          </section>

          <section class="workflow-lane emergency-lane" aria-label="安全快速通道">
            <header>
              <span><ShieldAlert :size="15" :stroke-width="1.9" aria-hidden="true" />安全快速通道</span>
              <small>命中危险信号，跳过常规采集与检索</small>
            </header>
            <div class="workflow-lane-track">
              <template v-for="(agent, index) in EMERGENCY_AGENTS.slice(1)" :key="agent.key">
                <article class="workflow-node" :class="`tone-${agent.tone}`">
                  <div class="workflow-icon">
                    <component :is="agent.icon" :size="22" :stroke-width="1.8" aria-hidden="true" />
                    <span>{{ agent.number }}</span>
                  </div>
                  <strong>{{ agent.shortTitle }}</strong>
                  <p>{{ agent.description }}</p>
                </article>
                <div v-if="index < EMERGENCY_AGENTS.length - 2" class="workflow-arrow" aria-hidden="true">
                  <el-icon :size="17"><ArrowRight /></el-icon>
                </div>
              </template>
            </div>
          </section>
        </div>
      </section>

      <section class="recent-panel" aria-labelledby="recent-title">
        <div class="section-heading recent-heading">
          <div>
            <span class="section-kicker">历史记录</span>
            <h2 id="recent-title">最近问诊</h2>
          </div>
          <el-button text type="primary" @click="router.push('/records')">
            查看全部
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>

        <el-skeleton v-if="recentLoading" :rows="2" animated />
        <div v-else-if="recentRecords.length" class="record-list">
          <button
            v-for="record in recentRecords"
            :key="record.id"
            type="button"
            class="record-row"
            @click="router.push(`/records/${record.id}`)"
          >
            <span class="record-icon"><el-icon><Document /></el-icon></span>
            <span class="record-copy">
              <strong>{{ recordTitle(record) }}</strong>
              <small>{{ record.department || '待查看问诊详情' }}</small>
            </span>
            <el-tag v-if="record.riskLevel" :type="riskTagType(record.riskLevel)" effect="light">
              {{ record.riskLevel }}风险
            </el-tag>
            <time>{{ formatDate(record.createdAt) }}</time>
            <el-icon class="record-arrow"><ArrowRight /></el-icon>
          </button>
        </div>
        <div v-else class="empty-records">
          <span class="record-icon"><el-icon><Document /></el-icon></span>
          <div>
            <strong>暂无问诊记录</strong>
            <p>完成首次问诊后，记录会显示在这里。</p>
          </div>
        </div>
      </section>
    </template>

    <template v-else>
      <header class="consult-workspace-header">
        <div>
          <span class="section-kicker">智能问诊</span>
          <h1>{{ stage === 'result' ? '辅助分诊结果' : '多智能体协同问诊' }}</h1>
        </div>
        <el-button plain @click="startNewConsult">
          <el-icon><RefreshRight /></el-icon>
          新建问诊
        </el-button>
      </header>

      <div v-if="stageContext" class="step-context" role="status" aria-live="polite">
        <strong>{{ stageContext.title }}</strong>
        <span>{{ stageContext.detail }}</span>
      </div>

      <ol class="journey-steps" aria-label="问诊任务进度">
        <li
          v-for="item in journeySteps"
          :key="item.stage"
          :class="{ 'is-active': stage === item.stage, 'is-complete': journeySteps.findIndex((step) => step.stage === stage) > journeySteps.findIndex((step) => step.stage === item.stage) }"
        >
          <span>{{ item.number }}</span>
          <strong>{{ item.label }}</strong>
        </li>
      </ol>

      <section class="steps-panel" :class="{ 'steps-panel-fast': isEmergencyFastPath }" aria-label="问诊进度">
        <div v-if="isEmergencyFastPath" class="fast-path-banner" role="status">
          <span class="fast-path-icon"><ShieldAlert :size="20" :stroke-width="1.9" aria-hidden="true" /></span>
          <div>
            <strong>安全快速通道</strong>
            <p>已跳过常规信息采集与知识检索，直接执行安全规则分级和行动指令。</p>
          </div>
          <el-tag type="danger" effect="light">{{ completedAgentCount }}/{{ workflowAgents.length }} 节点</el-tag>
        </div>
        <el-steps :active="workflowStep" finish-status="success" align-center>
          <el-step
            v-for="agent in workflowAgents"
            :key="agent.key"
            :title="agent.shortTitle"
            :status="stepStatus(agent.key)"
          />
        </el-steps>
      </section>

      <section v-if="stage === 'intake'" class="intake-panel">
        <div class="panel-title">
          <span class="title-icon tone-blue"><el-icon><EditPen /></el-icon></span>
          <div>
            <h2>完善问诊信息</h2>
            <p>带 * 的内容将直接影响症状识别与风险评估。</p>
          </div>
        </div>

        <div v-if="quickInputImported" class="imported-description-note" role="status">
          <el-icon><CircleCheck /></el-icon>
          <span>已将快速描述带入下方症状描述，可直接修改。</span>
        </div>

        <el-form label-position="top" class="intake-form">
          <div class="form-section">
            <div class="form-section-title">
              <el-icon><User /></el-icon>
              <strong>基本信息</strong>
              <span>选填</span>
            </div>
            <div class="form-grid profile-grid">
              <el-form-item label="称呼">
                <el-input v-model="form.name" maxlength="20" placeholder="请输入称呼" />
              </el-form-item>
              <el-form-item label="性别">
                <el-radio-group v-model="form.gender">
                  <el-radio-button label="男" value="男" />
                  <el-radio-button label="女" value="女" />
                  <el-radio-button label="不便透露" value="不便透露" />
                </el-radio-group>
              </el-form-item>
              <el-form-item label="年龄">
                <el-input-number v-model="form.age" :min="1" :max="120" controls-position="right" />
              </el-form-item>
            </div>
          </div>

          <div class="form-section">
            <div class="form-section-title required-title">
              <el-icon><FirstAidKit /></el-icon>
              <strong>主要症状</strong>
              <span>至少选择一项 *</span>
            </div>
            <el-checkbox-group
              v-model="selectedSymptoms"
              class="symptom-options"
              :class="{ 'has-error': intakeTouched.symptoms && !selectedSymptoms.length }"
              @change="intakeTouched.symptoms = true"
            >
              <el-checkbox
                v-for="symptom in SYMPTOM_OPTIONS"
                :key="symptom"
                :value="symptom"
                border
              >
                {{ symptom }}
              </el-checkbox>
            </el-checkbox-group>
            <p v-if="intakeTouched.symptoms && !selectedSymptoms.length" class="field-error" role="alert">
              请至少选择一项主要症状。
            </p>
          </div>

          <div class="form-section">
            <div class="form-section-title required-title">
              <el-icon><ChatDotRound /></el-icon>
              <strong>症状描述</strong>
              <span>必填 *</span>
            </div>
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="5"
              maxlength="500"
              show-word-limit
              resize="none"
              :class="{ 'has-error': intakeTouched.description && !form.description.trim() }"
              :aria-invalid="intakeTouched.description && !form.description.trim()"
              placeholder="请描述症状出现的时间、部位、变化，以及是否伴有其他不适"
              @blur="intakeTouched.description = true"
            />
            <p v-if="intakeTouched.description && !form.description.trim()" class="field-error" role="alert">
              请补充症状描述，例如出现时间、变化或伴随不适。
            </p>
          </div>

          <div class="form-section attachment-section">
            <div class="form-section-title">
              <el-icon><Document /></el-icon>
              <strong>附件补充</strong>
              <span>可选</span>
            </div>
            <input
              ref="attachmentInput"
              class="attachment-input"
              type="file"
              :accept="ATTACHMENT_ACCEPT"
              aria-label="选择问诊附件"
              @change="uploadAttachment"
            />
            <div class="attachment-toolbar">
              <el-button
                plain
                :loading="isUploadingAttachment"
                :disabled="isUploadingAttachment"
                @click="openAttachmentPicker"
              >
                <el-icon><Upload /></el-icon>
                添加附件
              </el-button>
              <span>上传后请确认草稿；图片和音频不会自动诊断</span>
            </div>
            <div v-if="attachments.length" class="attachment-list">
              <article v-for="attachment in attachments" :key="attachment.id" class="attachment-item">
                <div class="attachment-item-heading">
                  <div class="attachment-file-name">
                    <el-icon><Document /></el-icon>
                    <strong :title="attachment.originalFilename">{{ attachment.originalFilename }}</strong>
                    <small>{{ formatAttachmentSize(attachment.sizeBytes) }}</small>
                  </div>
                  <el-tag
                    size="small"
                    :type="attachment.status === 'CONFIRMED' ? 'success' : 'warning'"
                  >
                    {{ attachment.status === 'CONFIRMED' ? '已确认' : '待确认' }}
                  </el-tag>
                </div>
                <el-input
                  v-model="attachment.draftText"
                  type="textarea"
                  :rows="3"
                  maxlength="4000"
                  show-word-limit
                  resize="none"
                  :disabled="attachment.status === 'CONFIRMED'"
                  placeholder="请核对或补充这份附件希望表达的症状信息"
                />
                <div class="attachment-item-actions">
                  <small v-if="attachment.kind === 'IMAGE' || attachment.kind === 'AUDIO'">
                    仅作为您确认后的文字草稿，不会触发自动诊断
                  </small>
                  <span />
                  <el-button
                    v-if="attachment.status !== 'CONFIRMED'"
                    text
                    type="primary"
                    :loading="confirmingAttachmentId === attachment.id"
                    @click="confirmAttachment(attachment)"
                  >
                    <el-icon><CircleCheck /></el-icon>
                    确认草稿
                  </el-button>
                  <el-button text type="danger" @click="removeAttachment(attachment)">
                    <el-icon><Delete /></el-icon>
                    移除
                  </el-button>
                </div>
              </article>
            </div>
          </div>

          <div class="form-section">
            <div class="form-section-title">
              <el-icon><Clock /></el-icon>
              <strong>症状情况</strong>
              <span>选填</span>
            </div>
            <div class="form-grid detail-grid">
              <el-form-item label="持续时间">
                <el-select v-model="form.duration" clearable placeholder="请选择">
                  <el-option v-for="item in DURATION_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </el-form-item>
              <el-form-item label="严重程度">
                <el-select v-model="form.severity" clearable placeholder="请选择">
                  <el-option v-for="item in SEVERITY_OPTIONS" :key="item" :label="item" :value="item" />
                </el-select>
              </el-form-item>
            </div>
          </div>

          <div class="form-actions intake-submit-bar">
            <el-button @click="stage = 'landing'">
              <el-icon><ArrowLeft /></el-icon>
              返回
            </el-button>
            <el-button type="primary" :disabled="!canSubmitIntake" @click="submitIntake">
              提交并开始分析
              <el-icon><ArrowRight /></el-icon>
            </el-button>
          </div>
        </el-form>
      </section>

      <template v-else-if="stage === 'processing'">
        <el-alert
          v-if="emergencyNotice"
          class="high-risk-alert"
          type="error"
          :closable="false"
          show-icon
        >
          <template #title>{{ emergencyNotice.title }}</template>
          <p>{{ emergencyNotice.detail }}</p>
        </el-alert>

        <div class="processing-layout">
        <section class="processing-panel" :class="{ 'is-active': isSubmitting }">
          <div class="processing-summary" aria-live="polite">
            <div>
              <span class="section-kicker">实时进度</span>
              <h2>{{ followupQuestion ? '需要补充问诊信息' : isEmergencyFastPath ? '安全快速通道正在执行' : '智能体正在协同分析' }}</h2>
            </div>
            <strong>{{ progress }}%</strong>
          </div>
          <el-progress :percentage="progress" :show-text="false" :stroke-width="8" />
          <div class="friendly-progress-list" aria-live="polite">
            <div
              v-for="item in processingSteps"
              :key="item.key"
              class="friendly-progress-item"
              :class="`is-${processingStepState(item.key)}`"
            >
              <span class="friendly-progress-icon">
                <el-icon v-if="processingStepState(item.key) === 'done'"><CircleCheck /></el-icon>
                <el-icon v-else-if="processingStepState(item.key) === 'error'"><WarningFilled /></el-icon>
                <i v-else />
              </span>
              <strong>{{ item.label }}</strong>
              <small>{{ processingStepState(item.key) === 'done' ? '已完成' : processingStepState(item.key) === 'running' ? '进行中' : processingStepState(item.key) === 'error' ? '需要重试' : '等待中' }}</small>
            </div>
          </div>

          <details class="runtime-details">
            <summary>查看运行详情</summary>
            <div class="trace-meta-bar">
              <span>Trace ID</span>
              <code>{{ traceState.traceId || '等待首个事件' }}</code>
              <small>{{ currentTraceElapsedMs }} ms</small>
            </div>

            <AgentFlowGraph
              class="processing-agent-flow"
              :agents="workflowAgents"
              :status-by-key="agentStatus"
              :trace-state="traceState"
            />

            <div v-if="traceState.events.length" class="live-events">
              <div class="live-events-heading">
                <strong>实时事件序列</strong>
                <span>{{ traceState.events.length }} 条</span>
              </div>
              <div class="live-events-list" aria-live="polite">
                <div
                  v-for="event in traceState.events"
                  :key="`${event.trace_id}-${event.sequence}`"
                  class="live-event-row"
                  :class="{ 'is-error': event.status === 'error' }"
                >
                  <code>#{{ event.sequence }}</code>
                  <strong>{{ traceEventLabel(event) }}</strong>
                  <span>{{ traceEventStatus(event) }}</span>
                  <small>{{ event.elapsed_ms }} ms</small>
                </div>
              </div>
            </div>
          </details>

          <div v-if="evidenceList.length" class="live-evidence">
            <div class="live-evidence-title">
              <el-icon><Search /></el-icon>
              <strong>已匹配 {{ evidenceList.length }} 条医学依据</strong>
            </div>
            <div class="live-evidence-sources">
              <el-tag v-for="item in evidenceList" :key="item.doc_id" effect="plain">
                {{ item.source }}
              </el-tag>
            </div>
          </div>

          <div v-if="requestError" class="processing-error">
            <el-alert :title="requestErrorInfo?.title || '问诊服务暂时不可用'" type="error" :closable="false" show-icon>
              <p>{{ requestErrorInfo?.detail || requestError }}</p>
            </el-alert>
            <div class="inline-actions">
              <el-button v-if="requestErrorInfo?.action === 'edit'" @click="backToIntake">返回修改症状</el-button>
              <el-button v-else plain @click="backToIntake">返回修改</el-button>
              <el-button type="primary" @click="retryConsult">
                <el-icon><RefreshRight /></el-icon>
                保留内容并重试
              </el-button>
            </div>
          </div>

          <div v-else-if="isSubmitting" class="cancel-row">
            <span>已完成 {{ completedAgentCount }}/{{ workflowAgents.length }} 个处理节点</span>
            <el-button text type="danger" @click="cancelConsult">
              <el-icon><Close /></el-icon>
              取消分析
            </el-button>
          </div>
        </section>

        <aside class="conversation-panel">
          <div class="conversation-header">
            <span class="title-icon tone-blue"><el-icon><ChatDotRound /></el-icon></span>
            <div>
              <strong>本次问诊</strong>
              <small>会话 {{ sessionId.slice(0, 8).toUpperCase() }}</small>
            </div>
          </div>

          <div class="message-list" aria-live="polite">
            <div v-for="(message, index) in conversation" :key="index" :class="['message-row', message.role]">
              <span class="message-role">{{ message.role === 'user' ? '您' : 'AI' }}</span>
              <p>{{ message.text }}</p>
            </div>
            <div v-if="isSubmitting && answerText" class="message-row assistant streaming-message">
              <span class="message-role">AI</span>
              <p>{{ answerText }}<span class="streaming-caret" aria-hidden="true" /></p>
            </div>
            <div v-else-if="isSubmitting" class="message-row assistant pending-message">
              <span class="message-role">AI</span>
              <p><i /><i /><i /></p>
            </div>
          </div>

            <div v-if="followupQuestion && !isSubmitting" class="followup-composer">
              <div class="followup-prompt">
                <span class="followup-kicker">还需要补充</span>
                <strong>{{ followupQuestion }}</strong>
                <small>这样可以帮助系统判断就医时效和是否需要进一步分诊。</small>
                <small v-if="followupMissing.length">
                  用于完善：{{ followupMissing.map(missingLabel).join('、') }}
                </small>
                <small class="followup-example">{{ followupExample }}</small>
              </div>
            <div v-if="followupSuggestions.length" class="followup-suggestions" aria-label="快捷回答">
              <button
                v-for="suggestion in followupSuggestions"
                :key="suggestion"
                type="button"
                @click="selectFollowupSuggestion(suggestion)"
              >
                {{ suggestion }}
              </button>
            </div>
              <el-input
              v-model="followupInput"
              type="textarea"
              :rows="3"
              maxlength="300"
              resize="none"
              placeholder="也可以直接输入您的情况，例如：咳嗽已经 3 天了"
              @keyup.ctrl.enter="submitFollowup"
              />
              <div class="followup-actions">
                <el-button plain @click="selectFollowupSuggestion('暂时无法回答')">暂时无法回答</el-button>
                <el-button plain @click="backToIntake">返回补充表单</el-button>
              <el-button type="primary" :disabled="!canSubmitFollowup" @click="submitFollowup">
                <el-icon><Promotion /></el-icon>
                提交补充
              </el-button>
            </div>
          </div>
        </aside>
        </div>
      </template>

      <template v-else-if="stage === 'result'">
        <el-alert
          v-if="isHighRisk"
          class="high-risk-alert"
          type="error"
          :closable="false"
          show-icon
        >
          <template #title>{{ emergencyNotice?.title || '检测到高风险信号，请优先线下就医' }}</template>
          <p>{{ emergencyNotice?.detail || triageData?.urgency || '如症状明显或持续加重，请立即拨打 120。' }}</p>
        </el-alert>

        <div class="result-layout">
          <section class="result-panel" :class="{ 'result-panel-high-risk': isHighRisk }">
            <div class="result-heading">
              <div>
                <span class="section-kicker">分诊建议</span>
                <h2>本次问诊结果</h2>
              </div>
              <el-tag
                :type="riskTagType(triageData?.risk_level)"
                effect="dark"
                size="large"
                disable-transitions
              >
                {{ triageData?.risk_level || '未知' }}风险
              </el-tag>
            </div>

            <div class="result-action-summary" :class="{ 'is-high-risk': isHighRisk }">
              <div class="result-action-summary__copy">
                <span>{{ isHighRisk ? '现在最重要的行动' : '建议下一步' }}</span>
                <strong>{{ isHighRisk ? '请优先线下就医，不要自行驾车' : `建议前往${triageData?.department || '相应科室'}` }}</strong>
                <small>{{ triageData?.urgency || '请结合症状变化及时就医' }}</small>
              </div>
              <a v-if="isHighRisk" class="result-emergency-link" href="tel:120">
                <el-icon><WarningFilled /></el-icon>
                拨打 120
              </a>
            </div>

            <div class="result-metrics" aria-label="分诊结果摘要">
              <div class="metric-item">
                <span class="metric-icon orange"><el-icon><WarningFilled /></el-icon></span>
                <div>
                  <small>风险等级</small>
                  <strong>{{ triageData?.risk_level || '未知' }}</strong>
                </div>
              </div>
              <div class="metric-item">
                <span class="metric-icon blue"><el-icon><FirstAidKit /></el-icon></span>
                <div>
                  <small>推荐科室</small>
                  <strong>{{ triageData?.department || '暂未生成' }}</strong>
                </div>
              </div>
              <div class="metric-item">
                <span class="metric-icon purple"><el-icon><Clock /></el-icon></span>
                <div>
                  <small>建议就医时效</small>
                  <strong>{{ triageData?.urgency || '请结合症状变化及时就医' }}</strong>
                </div>
              </div>
            </div>

            <div class="result-section answer-section" :class="{ 'emergency-answer-section': emergencyNotice }">
              <h3>{{ emergencyNotice ? '紧急行动指令' : '首要行动建议' }}</h3>
              <p>{{ answerText || '系统暂未返回自然语言建议。' }}</p>
            </div>

            <details class="result-details">
              <summary>查看判定依据与风险说明</summary>
              <div class="result-details__body">
                <div class="result-visualization-section">
                  <TriageScalePanel
                    :risk-level="triageData?.risk_level || ''"
                    :urgency="triageData?.urgency || ''"
                    :support-score="visualizationSupportScore"
                    :support-label="supportScoreLabel"
                    :abstained="Boolean(triageData?.abstained)"
                  />
                </div>

                <div class="result-section decision-visualization-section">
                  <DecisionFlowGraph
                    :symptoms="decisionSymptoms"
                    :triage="triageData"
                    :evidence="answerCitations"
                  />
                </div>

                <div class="result-section">
                  <h3>症状摘要</h3>
                  <div v-if="resultSymptoms.length" class="result-tags">
                    <el-tag v-for="symptom in resultSymptoms" :key="symptom" effect="light">
                      {{ symptom }}
                    </el-tag>
                    <el-tag v-if="structuredSymptoms?.duration" type="info" effect="plain">
                      持续 {{ structuredSymptoms.duration }}
                    </el-tag>
                    <el-tag v-if="structuredSymptoms?.severity" type="warning" effect="plain">
                      {{ structuredSymptoms.severity }}
                    </el-tag>
                  </div>
                  <p v-else class="muted-copy">未提取到结构化症状，请以系统回答为准。</p>
                  <p v-if="triageData?.matched_rule" class="matched-rule">
                    <el-icon><InfoFilled /></el-icon>
                    已命中风险规则：{{ triageData.matched_rule }}
                  </p>
                  <div v-if="triageData?.explanation || triageFactors.length" class="triage-explanation">
                    <strong>判定依据</strong>
                    <p>{{ triageData?.explanation || '以下依据来自本次安全规则或知识检索。' }}</p>
                    <ul v-if="triageFactors.length">
                      <li v-for="factor in triageFactors" :key="`${factor.kind}-${factor.reference}-${factor.label}`">
                        <span>{{ factor.kind === 'rule' ? '安全规则' : '知识证据' }}</span>
                        <strong>{{ factor.label }}</strong>
                        <small v-if="factor.support">支持 {{ factor.support }}</small>
                      </li>
                    </ul>
                  </div>
                </div>

                <div class="safety-boundary">
                  <el-icon :size="19"><InfoFilled /></el-icon>
                  <div>
                    <strong>医疗安全声明</strong>
                    <p>
                      {{ safetyBoundary || '本系统提供的是辅助分诊建议，不替代执业医生的诊断与治疗。如症状持续、加重或出现危险信号，请及时线下就医。' }}
                    </p>
                  </div>
                </div>
              </div>
            </details>
          </section>

          <aside class="evidence-panel">
            <div class="evidence-heading">
              <span class="title-icon tone-green"><el-icon><Reading /></el-icon></span>
              <div>
                <h2>医学依据</h2>
                <p>{{ evidenceList.length ? `本次命中 ${evidenceList.length} 条资料` : isEmergencyFastPath ? '安全规则直接触发，无需等待知识检索' : '本次未检索到可展示的资料' }}</p>
              </div>
            </div>

            <details class="evidence-details" :open="isHighRisk">
              <summary>
                {{ evidenceList.length ? `查看 ${evidenceList.length} 条医学依据` : '查看依据说明' }}
              </summary>
              <div v-if="evidenceList.length" class="evidence-list">
                <article v-for="(evidence, index) in evidenceList" :key="evidence.citation_id || evidence.chunk_id || index" class="evidence-item">
                  <div class="evidence-meta">
                    <span :title="evidence.citation_id || ''">
                      引用 {{ evidence.citation_id || String(index + 1).padStart(2, '0') }}
                    </span>
                    <el-tag v-if="formatEvidenceScore(evidence.score)" type="success" effect="plain" size="small">
                      检索支持度 {{ formatEvidenceScore(evidence.score) }}
                    </el-tag>
                  </div>
                  <strong>{{ evidence.source || '医学知识库资料' }}</strong>
                  <div class="evidence-context">
                    <span>{{ formatEvidenceType(evidence.source_type) }}</span>
                    <span v-if="evidence.published_date">更新于 {{ evidence.published_date }}</span>
                  </div>
                  <p class="evidence-explanation">与本次症状相关的医学资料摘录，供你核对，不代表疾病确诊。</p>
                  <p>{{ evidence.quote || evidence.text }}</p>
                  <small v-if="evidence.department">{{ evidence.department }}</small>
                  <details class="evidence-technical-details">
                    <summary>技术详情</summary>
                    <div class="evidence-technical">
                      <code v-if="evidence.chunk_id">{{ evidence.chunk_id }}</code>
                      <code v-if="evidence.index_version">索引 {{ evidence.index_version }}</code>
                    </div>
                  </details>
                </article>
              </div>

              <div v-else class="evidence-empty">
                <el-icon :size="30"><Document /></el-icon>
                <strong>{{ isEmergencyFastPath ? '安全快速通道未执行知识检索' : '暂无可展示依据' }}</strong>
                <p>{{ isEmergencyFastPath ? '危险信号由本地安全规则直接判定，以缩短紧急响应时间。' : '系统不会在没有检索结果时生成引用来源。' }}</p>
              </div>
            </details>

            <div v-if="answerCitations.length" class="citation-summary">
              <strong>回答引用</strong>
              <span
                v-for="(citation, index) in answerCitations"
                :key="citation.citation_id || index"
                :title="citation.quote || ''"
              >
                {{ citation.source || citation.citation_id || `引用 ${index + 1}` }}
              </span>
            </div>
          </aside>
        </div>

        <div class="result-actions">
          <div class="result-meta">
            生成时间：{{ resultCreatedAt?.toLocaleString('zh-CN') || '--' }}
            <details class="technical-details">
              <summary>技术详情</summary>
              <code>Trace {{ traceState.traceId || '--' }} · {{ currentTraceElapsedMs }} ms</code>
            </details>
          </div>
          <div>
            <el-button @click="router.push('/records')">
              <el-icon><Document /></el-icon>
              查看问诊记录
            </el-button>
            <el-button type="primary" @click="startNewConsult">
              <el-icon><RefreshRight /></el-icon>
              再次问诊
            </el-button>
          </div>
        </div>

        <section class="result-feedback" aria-labelledby="result-feedback-title">
          <div>
            <strong id="result-feedback-title">这次建议对你有帮助吗？</strong>
            <small>你的反馈只用于改进问诊体验，不会改变本次分诊结果。</small>
          </div>
          <div v-if="!feedbackSubmitted" class="result-feedback-actions">
            <el-button plain size="small" @click="submitResultFeedback('helpful')">有帮助</el-button>
            <el-button plain size="small" @click="submitResultFeedback('symptoms_inaccurate')">症状描述不准确</el-button>
            <el-button plain size="small" @click="submitResultFeedback('department_mismatch')">科室推荐不符合预期</el-button>
          </div>
          <span v-else class="feedback-thanks">已收到反馈，谢谢你的提醒。</span>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped>
@property --consult-beam-angle {
  syntax: "<angle>";
  inherits: false;
  initial-value: 0deg;
}

.consult-page {
  width: min(100%, 1260px);
  margin: 0 auto;
  color: #1d2129;
}

.welcome-banner,
.workflow-panel,
.recent-panel,
.steps-panel,
.intake-panel,
.processing-panel,
.conversation-panel,
.result-panel,
.evidence-panel {
  border: 1px solid #e5eaf0;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 4px 14px rgba(31, 45, 61, 0.04);
}

.welcome-banner {
  position: relative;
  min-height: 262px;
  display: flex;
  align-items: stretch;
  overflow: hidden;
  background: #eef6ff;
}

.welcome-content {
  position: relative;
  z-index: 2;
  width: 68%;
  padding: 36px 38px 34px;
}

.welcome-kicker,
.section-kicker {
  color: #1677ff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
}

.welcome-kicker {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 14px;
}

.online-dot,
.tag-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #1677ff;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.12);
}

.welcome-content h1 {
  margin: 0;
  font-size: 26px;
  line-height: 1.35;
  letter-spacing: 0;
}

.welcome-subtitle {
  margin: 9px 0 24px;
  color: #4e5969;
  font-size: 14px;
}

.quick-consult {
  display: flex;
  max-width: 720px;
  gap: 10px;
  padding: 7px;
  border: 1px solid #b7d5ff;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 7px 20px rgba(22, 119, 255, 0.1);
}

.quick-consult :deep(.el-input__wrapper) {
  box-shadow: none;
}

.quick-consult .el-button {
  min-width: 132px;
}

.doctor-visual {
  position: relative;
  width: 36%;
  min-width: 300px;
  background: #e6f1ff;
}

.doctor-visual::before,
.doctor-visual::after {
  position: absolute;
  content: '';
  background: #d8e9ff;
}

.doctor-visual::before {
  inset: 22px 0 auto;
  height: 1px;
}

.doctor-visual::after {
  inset: auto 22px 24px auto;
  width: 72px;
  height: 6px;
  border-radius: 3px;
}

.doctor-visual img {
  position: absolute;
  z-index: 1;
  right: 12px;
  bottom: -8px;
  width: min(100%, 350px);
  height: 250px;
  object-fit: contain;
  object-position: center bottom;
}

.workflow-panel,
.recent-panel {
  margin-top: 18px;
  padding: 24px 28px;
}

.section-heading,
.consult-workspace-header,
.processing-summary,
.result-heading,
.result-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.section-heading h2,
.processing-summary h2,
.result-heading h2,
.evidence-heading h2 {
  margin: 4px 0 0;
  font-size: 17px;
  line-height: 1.4;
  letter-spacing: 0;
}

.section-heading :deep(.el-tag__content) {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.workflow-track {
  display: flex;
  align-items: center;
  margin-top: 24px;
}

.workflow-entry {
  display: grid;
  justify-items: center;
  margin-top: 22px;
}

.workflow-node-entry {
  width: min(100%, 230px);
}

.workflow-fork {
  position: relative;
  width: 52%;
  height: 34px;
  border-bottom: 1px solid var(--border-default);
}

.workflow-fork::before {
  position: absolute;
  top: 0;
  bottom: -1px;
  left: 50%;
  content: '';
  border-left: 1px solid var(--border-default);
}

.workflow-fork i {
  position: absolute;
  bottom: -4px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.workflow-fork i:first-child { left: -3px; }
.workflow-fork i:nth-child(2) { left: calc(50% - 3px); }
.workflow-fork i:last-child { right: -3px; background: var(--danger); box-shadow: 0 0 0 3px var(--danger-soft); }

.workflow-lanes {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, 0.8fr);
  gap: 18px;
}

.workflow-lane {
  min-width: 0;
  padding: 16px 14px 4px;
  border-top: 2px solid var(--primary);
  background: var(--surface-muted);
}

.workflow-lane.emergency-lane {
  border-top-color: var(--danger);
  background: var(--danger-soft);
}

.workflow-lane > header,
.workflow-lane > header span {
  display: flex;
  align-items: center;
}

.workflow-lane > header {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.workflow-lane > header span {
  gap: 6px;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 700;
}

.workflow-lane > header small {
  color: var(--text-muted);
  font-size: 12px;
  text-align: right;
}

.emergency-lane > header span {
  color: var(--danger);
}

.workflow-lane-track {
  display: flex;
  align-items: flex-start;
}

.workflow-lane .workflow-icon {
  width: 50px;
  height: 50px;
}

.fast-path-banner {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  padding: 13px 15px;
  border: 1px solid color-mix(in srgb, var(--danger) 34%, transparent);
  border-radius: var(--radius-md);
  background: var(--danger-soft);
}

.fast-path-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--danger);
  color: var(--text-inverse);
}

.fast-path-banner strong,
.fast-path-banner p {
  display: block;
  margin: 0;
}

.fast-path-banner strong {
  color: var(--danger);
  font-size: 14px;
}

.fast-path-banner p {
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.55;
}

.workflow-node {
  min-width: 0;
  flex: 1;
  text-align: center;
}

.workflow-icon {
  position: relative;
  display: grid;
  width: 58px;
  height: 58px;
  margin: 0 auto 11px;
  place-items: center;
  border-radius: 50%;
}

.workflow-icon span {
  position: absolute;
  right: -2px;
  bottom: -2px;
  display: grid;
  width: 21px;
  height: 21px;
  place-items: center;
  border: 2px solid #ffffff;
  border-radius: 50%;
  color: #ffffff;
  font-size: 9px;
  font-weight: 700;
}

.workflow-node strong {
  display: block;
  font-size: 13px;
}

.workflow-node p {
  margin: 4px auto 0;
  max-width: 170px;
  color: #86909c;
  font-size: 11px;
  line-height: 1.55;
}

.workflow-arrow {
  display: grid;
  flex: 0 0 28px;
  place-items: center;
  color: #a9b9ca;
}

.tone-red .workflow-icon,
.agent-status-item.tone-red .agent-status-icon {
  background: #fff0f0;
  color: #e5484d;
}

.tone-red .workflow-icon span {
  background: #e5484d;
}

.tone-blue .workflow-icon,
.title-icon.tone-blue,
.agent-status-item.tone-blue .agent-status-icon {
  background: #e8f3ff;
  color: #1677ff;
}

.tone-blue .workflow-icon span {
  background: #1677ff;
}

.tone-green .workflow-icon,
.title-icon.tone-green,
.agent-status-item.tone-green .agent-status-icon {
  background: #e8ffea;
  color: #00a63c;
}

.tone-green .workflow-icon span {
  background: #00a63c;
}

.tone-purple .workflow-icon,
.agent-status-item.tone-purple .agent-status-icon {
  background: #f3ecff;
  color: #7a38c7;
}

.tone-purple .workflow-icon span {
  background: #7a38c7;
}

.tone-orange .workflow-icon,
.agent-status-item.tone-orange .agent-status-icon {
  background: #fff3e8;
  color: #e86f00;
}

.tone-orange .workflow-icon span {
  background: #e86f00;
}

.recent-heading {
  margin-bottom: 10px;
}

.record-list {
  border-top: 1px solid #edf0f3;
}

.record-row {
  width: 100%;
  display: grid;
  grid-template-columns: 38px minmax(180px, 1fr) auto 126px 20px;
  align-items: center;
  gap: 12px;
  padding: 14px 2px;
  border: 0;
  border-bottom: 1px solid #edf0f3;
  background: transparent;
  color: #1d2129;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.record-row:last-child {
  border-bottom: 0;
}

.record-row:hover .record-copy strong,
.record-row:hover .record-arrow {
  color: #1677ff;
}

.record-icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 7px;
  background: #f0f6ff;
  color: #1677ff;
}

.record-copy {
  min-width: 0;
}

.record-copy strong,
.record-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-copy strong {
  font-size: 13px;
}

.record-copy small,
.record-row time {
  color: #86909c;
  font-size: 11px;
}

.record-copy small {
  margin-top: 3px;
}

.record-row time {
  text-align: right;
}

.record-arrow {
  color: #c2c8d0;
}

.empty-records {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 76px;
  border-top: 1px solid #edf0f3;
}

.empty-records strong {
  font-size: 13px;
}

.empty-records p {
  margin: 3px 0 0;
  color: #86909c;
  font-size: 11px;
}

.consult-workspace-header {
  margin-bottom: 16px;
}

.consult-workspace-header h1 {
  margin: 3px 0 0;
  font-size: 22px;
  letter-spacing: 0;
}

.steps-panel {
  margin-bottom: 16px;
  padding: 20px 26px 16px;
}

.steps-panel :deep(.el-step__title) {
  font-size: 12px;
}

.steps-panel :deep(.el-step__icon) {
  width: 28px;
  height: 28px;
  font-size: 12px;
}

.intake-panel,
.processing-panel,
.result-panel {
  padding: 26px 28px;
}

.panel-title,
.form-section-title,
.conversation-header,
.evidence-heading,
.live-evidence-title,
.safety-boundary,
.matched-rule {
  display: flex;
  align-items: center;
}

.triage-explanation {
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid var(--border-default, #dfe4ea);
  border-radius: var(--radius-md, 6px);
  background: var(--surface-muted, #f7f9fc);
}

.triage-explanation > strong,
.triage-explanation > p {
  display: block;
  margin: 0;
}

.triage-explanation > p {
  margin-top: 5px;
  color: var(--text-secondary, #475467);
  font-size: 12px;
  line-height: 1.65;
}

.triage-explanation ul {
  display: grid;
  gap: 8px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.triage-explanation li {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.triage-explanation li > span,
.triage-explanation li > small {
  color: var(--text-muted, #667085);
  font-size: 12px;
}

.triage-explanation li > strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--text-primary, #182230);
  font-size: 12px;
}

.panel-title {
  gap: 12px;
  padding-bottom: 22px;
  border-bottom: 1px solid #edf0f3;
}

.title-icon {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
}

.panel-title h2,
.panel-title p {
  margin: 0;
}

.panel-title h2 {
  font-size: 17px;
}

.panel-title p {
  margin-top: 4px;
  color: #86909c;
  font-size: 12px;
}

.intake-form {
  margin-top: 4px;
}

.form-section {
  padding: 22px 0;
  border-bottom: 1px solid #edf0f3;
}

.form-section-title {
  gap: 7px;
  margin-bottom: 16px;
  color: #4e5969;
  font-size: 13px;
}

.form-section-title strong {
  color: #1d2129;
  font-size: 14px;
}

.form-section-title span {
  margin-left: auto;
  color: #9aa3ae;
  font-size: 11px;
}

.required-title span {
  color: #f53f3f;
}

.form-grid {
  display: grid;
  gap: 16px;
}

.profile-grid {
  grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) minmax(180px, 1fr);
}

.detail-grid {
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  max-width: 720px;
}

.intake-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.intake-form :deep(.el-form-item__label) {
  color: #4e5969;
  font-size: 12px;
}

.intake-form :deep(.el-input-number),
.intake-form :deep(.el-select) {
  width: 100%;
}

.symptom-options {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.attachment-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

.attachment-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.attachment-toolbar > span {
  color: #86909c;
  font-size: 12px;
}

.attachment-list {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.attachment-item {
  display: grid;
  gap: 9px;
  padding: 12px;
  border: 1px solid #e5eaf0;
  border-radius: 6px;
  background: #fafbfc;
}

.attachment-item-heading,
.attachment-item-actions,
.attachment-file-name {
  display: flex;
  align-items: center;
}

.attachment-item-heading,
.attachment-item-actions {
  justify-content: space-between;
  gap: 10px;
}

.attachment-file-name {
  min-width: 0;
  gap: 7px;
  color: #1d2129;
}

.attachment-file-name strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-file-name small,
.attachment-item-actions small {
  color: #86909c;
  font-size: 11px;
}

.attachment-item-actions > span {
  flex: 1;
}

.symptom-options :deep(.el-checkbox.is-bordered) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 34px;
  padding: 7px 13px;
  border: 1px solid #e5eaf0;
  border-radius: 5px;
  background: #ffffff;
  color: #4e5969;
  font-size: 12px;
  margin: 0;
}

.symptom-options :deep(.el-checkbox.is-bordered:hover),
.symptom-options :deep(.el-checkbox.is-bordered:focus-within) {
  border-color: #84b9ff;
  color: #1677ff;
}

.symptom-options :deep(.el-checkbox.is-bordered.is-checked) {
  border-color: #84b9ff;
  background: #e8f3ff;
  color: #1677ff;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 22px;
}

.processing-layout,
.result-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(310px, 0.75fr);
  gap: 16px;
  align-items: start;
}

.processing-summary h2 {
  margin-bottom: 14px;
}

.processing-summary > strong {
  color: #1677ff;
  font-size: 24px;
}

.processing-agent-flow {
  margin-top: 24px;
}

.trace-meta-bar {
  min-width: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 8px 10px;
  border: 1px solid #edf0f3;
  border-radius: 6px;
  background: #fafbfc;
  color: #86909c;
  font-size: 10px;
}

.trace-meta-bar code {
  min-width: 0;
  overflow: hidden;
  color: #4e5969;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-status-list {
  display: grid;
  gap: 10px;
  margin-top: 24px;
}

.agent-status-item {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 72px;
  padding: 12px 14px;
  border: 1px solid #edf0f3;
  border-radius: 7px;
  background: #ffffff;
}

.agent-status-item.status-running {
  border-color: #84b9ff;
  background: #f7fbff;
}

.agent-status-item.status-done {
  background: #fbfdfb;
}

.agent-status-item.status-error {
  border-color: #ffc4c4;
  background: #fff8f8;
}

.agent-status-icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 8px;
}

.agent-status-copy strong {
  display: block;
  font-size: 13px;
}

.agent-status-copy p {
  margin: 4px 0 0;
  color: #86909c;
  font-size: 11px;
}

.agent-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #86909c;
  font-size: 11px;
  white-space: nowrap;
}

.agent-state small {
  color: #9aa3ae;
  font-size: 9px;
}

.agent-state i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #c9cdd4;
}

.status-running .agent-state {
  color: #1677ff;
}

.status-running .agent-state i {
  background: #1677ff;
  animation: pulse 1.1s ease-in-out infinite;
}

.status-done .agent-state {
  color: #00a63c;
}

.status-done .agent-state i {
  background: #00a63c;
}

.status-error .agent-state,
.status-error .agent-state i {
  color: #f53f3f;
  background: #f53f3f;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}

.live-evidence {
  margin-top: 18px;
  padding: 14px;
  border: 1px solid #d6f1df;
  border-radius: 7px;
  background: #f7fcf8;
}

.live-events {
  margin-top: 18px;
  overflow: hidden;
  border: 1px solid #e5eaf0;
  border-radius: 7px;
  background: #fafbfc;
}

.live-events-heading,
.live-event-row {
  display: flex;
  align-items: center;
}

.live-events-heading {
  justify-content: space-between;
  padding: 9px 11px;
  border-bottom: 1px solid #e5eaf0;
  color: #4e5969;
  font-size: 10px;
}

.live-events-heading span {
  color: #9aa3ae;
}

.live-events-list {
  max-height: 184px;
  overflow-y: auto;
}

.live-event-row {
  min-width: 0;
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 40px 54px;
  gap: 8px;
  min-height: 34px;
  padding: 6px 11px;
  border-bottom: 1px solid #edf0f3;
  font-size: 9px;
}

.live-event-row:last-child {
  border-bottom: 0;
}

.live-event-row code {
  color: #1677ff;
}

.live-event-row strong {
  overflow: hidden;
  color: #4e5969;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live-event-row span,
.live-event-row small {
  color: #86909c;
  text-align: right;
}

.live-event-row.is-error code,
.live-event-row.is-error strong,
.live-event-row.is-error span {
  color: #e5484d;
}

.live-evidence-title {
  gap: 7px;
  color: #008a34;
  font-size: 12px;
}

.live-evidence-sources {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}

.processing-error {
  margin-top: 18px;
}

.inline-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

.cancel-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
  color: #86909c;
  font-size: 11px;
}

.conversation-panel {
  position: sticky;
  top: 98px;
  overflow: hidden;
}

.conversation-header {
  gap: 11px;
  padding: 18px;
  border-bottom: 1px solid #edf0f3;
}

.conversation-header strong,
.conversation-header small {
  display: block;
}

.conversation-header strong {
  font-size: 13px;
}

.conversation-header small {
  margin-top: 3px;
  color: #86909c;
  font-size: 10px;
}

.message-list {
  display: grid;
  gap: 14px;
  min-height: 240px;
  max-height: 440px;
  padding: 18px;
  overflow-y: auto;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.message-row.user {
  flex-direction: row-reverse;
}

.message-role {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: #e8f3ff;
  color: #1677ff;
  font-size: 10px;
  font-weight: 700;
}

.message-row.user .message-role {
  background: #1677ff;
  color: #ffffff;
}

.message-row p {
  max-width: calc(100% - 40px);
  margin: 0;
  padding: 10px 12px;
  border-radius: 7px;
  background: #f4f6f8;
  color: #4e5969;
  font-size: 12px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.message-row.user p {
  background: #e8f3ff;
  color: #174d91;
}

.pending-message p {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 36px;
}

.pending-message p i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #7f8b99;
  animation: typing 1s ease-in-out infinite;
}

.pending-message p i:nth-child(2) {
  animation-delay: 0.15s;
}

.pending-message p i:nth-child(3) {
  animation-delay: 0.3s;
}

.streaming-caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 3px;
  border-radius: 1px;
  background: currentColor;
  vertical-align: -0.14em;
  animation: streaming-caret 0.9s steps(1, end) infinite;
}

@keyframes streaming-caret {
  50% {
    opacity: 0;
  }
}

@keyframes typing {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-3px);
  }
}

.followup-composer {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-top: 1px solid #edf0f3;
  background: #fafbfc;
}

.followup-composer .el-button {
  justify-self: end;
}

.high-risk-alert {
  margin-bottom: 16px;
}

.high-risk-alert p {
  margin: 5px 0 0;
  font-size: 12px;
}

.result-layout {
  grid-template-columns: minmax(0, 1.4fr) minmax(320px, 0.8fr);
}

.result-heading {
  padding-bottom: 20px;
  border-bottom: 1px solid #edf0f3;
}

.result-heading > div {
  min-width: 0;
}

.result-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-bottom: 1px solid #edf0f3;
}

.metric-item {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
  padding: 18px 16px 18px 0;
}

.metric-item:nth-child(even) {
  padding-left: 18px;
  border-left: 1px solid #edf0f3;
}

.metric-icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 7px;
}

.metric-icon.blue {
  background: #e8f3ff;
  color: #1677ff;
}

.metric-icon.orange {
  background: #fff3e8;
  color: #e86f00;
}

.metric-icon.green {
  background: #e8ffea;
  color: #00a63c;
}

.metric-icon.purple {
  background: #f3ecff;
  color: #7a38c7;
}

.metric-item small,
.metric-item strong {
  display: block;
}

.metric-item small {
  color: #86909c;
  font-size: 10px;
}

.metric-item strong {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.result-section {
  padding: 20px 0;
  border-bottom: 1px solid #edf0f3;
}

.result-visualization-section {
  min-width: 0;
  padding: 22px 0;
  border-bottom: 1px solid #edf0f3;
}

.decision-visualization-section {
  min-width: 0;
}

.result-section h3 {
  margin: 0 0 12px;
  font-size: 13px;
}

.result-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.matched-rule {
  gap: 6px;
  margin: 12px 0 0;
  color: #c76400;
  font-size: 11px;
}

.muted-copy {
  color: #86909c;
  font-size: 12px;
}

.answer-section p {
  margin: 0;
  color: #4e5969;
  font-size: 13px;
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.emergency-answer-section {
  margin-top: 18px;
  padding: 16px;
  border: 1px solid #ffc4c4;
  border-radius: 7px;
  background: #fff8f8;
}

.emergency-answer-section h3,
.emergency-answer-section p {
  color: #b4232b;
}

.safety-boundary {
  align-items: flex-start;
  gap: 10px;
  margin-top: 20px;
  padding: 14px;
  border: 1px solid #cfe4ff;
  border-radius: 7px;
  background: #f3f8ff;
  color: #1c64b7;
}

.safety-boundary strong {
  display: block;
  font-size: 12px;
}

.safety-boundary p {
  margin: 4px 0 0;
  color: #4e6e91;
  font-size: 11px;
  line-height: 1.65;
}

.evidence-panel {
  overflow: hidden;
}

.evidence-heading {
  gap: 11px;
  padding: 20px;
  border-bottom: 1px solid #edf0f3;
}

.evidence-heading h2 {
  margin: 0;
  font-size: 15px;
}

.evidence-heading p {
  margin: 3px 0 0;
  color: #86909c;
  font-size: 10px;
}

.evidence-list {
  display: grid;
}

.evidence-item {
  padding: 18px 20px;
  border-bottom: 1px solid #edf0f3;
}

.evidence-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.evidence-meta > span {
  color: #1677ff;
  font-size: 10px;
  font-weight: 700;
}

.evidence-item > strong {
  display: block;
  font-size: 12px;
  line-height: 1.5;
}

.evidence-item p {
  margin: 7px 0;
  color: #4e5969;
  font-size: 11px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.evidence-item > small {
  color: #86909c;
  font-size: 10px;
}

.evidence-technical {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.evidence-technical code {
  max-width: 100%;
  overflow-wrap: anywhere;
  padding: 2px 5px;
  border-radius: 3px;
  background: #f4f6f8;
  color: #66717e;
  font-size: 9px;
}

.evidence-empty {
  display: grid;
  min-height: 240px;
  padding: 34px 22px;
  place-items: center;
  align-content: center;
  color: #9aa3ae;
  text-align: center;
}

.evidence-empty strong {
  margin-top: 10px;
  color: #4e5969;
  font-size: 12px;
}

.evidence-empty p {
  margin: 5px 0 0;
  font-size: 10px;
  line-height: 1.55;
}

.citation-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  padding: 15px 20px;
  background: #fafbfc;
}

.citation-summary strong {
  width: 100%;
  color: #4e5969;
  font-size: 10px;
}

.citation-summary span {
  padding: 3px 7px;
  border: 1px solid #dfe4ea;
  border-radius: 4px;
  color: #66717e;
  font-size: 9px;
}

.result-actions {
  margin-top: 16px;
  padding: 14px 2px;
  color: #86909c;
  font-size: 10px;
}

.result-actions > div {
  display: flex;
  gap: 8px;
}

.result-actions > span {
  min-width: 0;
  overflow-wrap: anywhere;
}

@media (max-width: 1100px) {
  .welcome-content {
    width: 65%;
  }

  .doctor-visual {
    width: 35%;
    min-width: 250px;
  }

  .processing-layout,
  .result-layout {
    grid-template-columns: 1fr;
  }

  .conversation-panel {
    position: static;
  }

  .message-list {
    min-height: 160px;
    max-height: 300px;
  }
}

@media (max-width: 820px) {
  .welcome-content {
    width: 66%;
    padding: 28px 24px;
  }

  .welcome-content h1 {
    font-size: 23px;
  }

  .doctor-visual {
    width: 34%;
    min-width: 210px;
  }

  .doctor-visual img {
    right: -12px;
    width: 260px;
    height: 220px;
  }

  .workflow-track {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 22px 14px;
  }

  .workflow-fork {
    width: 74%;
  }

  .workflow-lanes {
    grid-template-columns: 1fr;
  }

  .workflow-lane-track {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px 12px;
  }

  .workflow-arrow {
    display: none;
  }

  .profile-grid,
  .detail-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .profile-grid > :last-child {
    grid-column: 1 / -1;
    max-width: calc(50% - 8px);
  }
}

@media (max-width: 640px) {
  .welcome-banner {
    min-height: 0;
    flex-direction: column;
  }

  .welcome-content {
    width: 100%;
    padding: 24px 18px 20px;
  }

  .welcome-content h1 {
    font-size: 21px;
  }

  .welcome-subtitle {
    margin-bottom: 18px;
    font-size: 12px;
    line-height: 1.65;
  }

  .quick-consult {
    flex-direction: column;
    padding: 6px;
  }

  .quick-consult .el-button {
    width: 100%;
  }

  .doctor-visual {
    width: 100%;
    min-width: 0;
    height: 148px;
  }

  .doctor-visual img {
    right: 50%;
    bottom: -28px;
    width: 240px;
    height: 190px;
    transform: translateX(50%);
  }

  .workflow-panel,
  .recent-panel,
  .intake-panel,
  .processing-panel,
  .result-panel {
    padding: 20px 16px;
  }

  .section-heading {
    align-items: flex-start;
  }

  .section-heading h2 {
    font-size: 15px;
  }

  .section-heading > .el-tag {
    display: none;
  }

  .workflow-track {
    margin-top: 20px;
  }

  .workflow-entry {
    margin-top: 18px;
  }

  .workflow-fork {
    width: 86%;
    height: 28px;
  }

  .workflow-lane {
    padding-inline: 10px;
  }

  .workflow-lane > header {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }

  .workflow-lane > header small {
    text-align: left;
  }

  .fast-path-banner {
    grid-template-columns: 38px minmax(0, 1fr);
  }

  .fast-path-banner > .el-tag {
    grid-column: 1 / -1;
    justify-self: start;
  }

  .workflow-node p {
    font-size: 10px;
  }

  .record-row {
    grid-template-columns: 36px minmax(0, 1fr) 18px;
  }

  .record-row .el-tag,
  .record-row time {
    display: none;
  }

  .consult-workspace-header {
    align-items: flex-start;
  }

  .consult-workspace-header h1 {
    font-size: 18px;
  }

  .consult-workspace-header .el-button {
    padding-inline: 10px;
  }

  .steps-panel {
    padding: 16px 8px 12px;
  }

  .steps-panel :deep(.el-step__title) {
    font-size: 10px;
    line-height: 1.35;
  }

  .steps-panel :deep(.el-step__line) {
    left: 56%;
    right: -44%;
  }

  .panel-title {
    align-items: flex-start;
  }

  .profile-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .profile-grid > :last-child {
    grid-column: auto;
    max-width: none;
  }

  .symptom-options {
    gap: 8px;
  }

  .form-actions {
    display: grid;
    grid-template-columns: 1fr 1.5fr;
  }

  .form-actions .el-button {
    width: 100%;
    margin: 0;
  }

  .processing-summary > strong {
    font-size: 20px;
  }

  .agent-status-item {
    grid-template-columns: 38px minmax(0, 1fr);
    padding: 11px;
  }

  .agent-status-icon {
    width: 36px;
    height: 36px;
  }

  .agent-state {
    grid-column: 2;
  }

  .cancel-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .message-list {
    padding: 14px;
  }

  .result-metrics {
    grid-template-columns: 1fr;
  }

  .metric-item,
  .metric-item:nth-child(even) {
    padding: 14px 0;
    border-left: 0;
    border-bottom: 1px solid #edf0f3;
  }

  .metric-item:last-child {
    border-bottom: 0;
  }

  .result-heading {
    align-items: flex-start;
  }

  .result-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .result-actions > div {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .result-actions .el-button {
    width: 100%;
    margin: 0;
  }

  .evidence-empty {
    min-height: 164px;
    padding: 24px 18px;
  }
}

/* Core consultation surfaces use the shared semantic theme tokens. */
.consult-page {
  color: var(--text-primary);
}

.welcome-banner,
.workflow-panel,
.recent-panel,
.steps-panel,
.intake-panel,
.processing-panel,
.conversation-panel,
.result-panel,
.evidence-panel {
  border-color: var(--border-default);
  border-radius: var(--radius-lg);
  background: var(--glass-surface);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(125%);
  -webkit-backdrop-filter: blur(18px) saturate(125%);
}

.welcome-banner {
  background: linear-gradient(
    118deg,
    color-mix(in srgb, var(--info-soft) 72%, var(--glass-surface)),
    color-mix(in srgb, var(--success-soft) 68%, var(--glass-surface)),
    color-mix(in srgb, var(--accent-violet-soft) 70%, var(--glass-surface)),
    color-mix(in srgb, var(--info-soft) 72%, var(--glass-surface))
  );
  background-size: 240% 240%;
  animation: consult-gradient-shift 12s ease-in-out infinite;
}

.doctor-visual {
  background: linear-gradient(
    150deg,
    color-mix(in srgb, var(--primary-soft) 88%, transparent),
    color-mix(in srgb, var(--success-soft) 72%, transparent),
    color-mix(in srgb, var(--accent-violet-soft) 70%, transparent)
  );
}

.doctor-visual::before,
.doctor-visual::after {
  background: var(--primary-subtle);
}

.quick-consult {
  border-color: var(--border-strong);
  background: var(--control-surface);
  box-shadow: var(--shadow-md);
  backdrop-filter: blur(16px) saturate(125%);
  -webkit-backdrop-filter: blur(16px) saturate(125%);
}

.quick-consult .quick-start-button {
  min-height: 40px;
  border: 1px solid var(--primary-solid);
  border-radius: var(--radius-md);
  background: var(--primary-solid);
  color: var(--text-inverse);
  font-weight: 700;
  box-shadow: 0 6px 14px var(--focus-ring);
  transition: background 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.quick-consult .quick-start-button:hover {
  border-color: var(--primary-solid-hover);
  background: var(--primary-solid-hover);
  box-shadow: 0 8px 18px var(--focus-ring);
}

.quick-consult .quick-start-button:active {
  transform: translateY(1px);
}

.quick-consult .quick-start-button:focus-visible {
  outline: 2px solid var(--focus-outline);
  outline-offset: 2px;
  box-shadow: 0 0 0 4px var(--focus-ring), 0 6px 14px var(--focus-ring);
}

.welcome-kicker,
.section-kicker,
.processing-summary > strong,
.live-event-row code,
.evidence-meta > span {
  color: var(--primary);
}

.welcome-subtitle,
.workflow-node p,
.record-copy small,
.record-row time,
.panel-title p,
.form-section-title,
.agent-status-copy p,
.agent-state,
.conversation-header small,
.message-row p,
.muted-copy,
.answer-section p,
.evidence-item p,
.result-actions {
  color: var(--text-secondary);
}

.agent-status-item,
.symptom-options :deep(.el-checkbox.is-bordered) {
  color: var(--text-primary);
  background: var(--surface-muted);
}

.record-row {
  color: var(--text-primary);
  background: transparent;
}

.record-icon {
  background: var(--primary-soft);
  color: var(--primary);
}

.record-list,
.record-row,
.panel-title,
.form-section,
.conversation-header,
.followup-composer,
.result-heading,
.result-metrics,
.result-section,
.evidence-heading,
.evidence-item {
  border-color: var(--border-subtle);
}

.record-row:focus-visible {
  border-radius: var(--radius-md, 6px);
}

.trace-meta-bar,
.live-events,
.followup-composer,
.citation-summary {
  border-color: var(--border-default);
  background: var(--surface-muted);
}

.online-dot,
.tag-dot {
  background: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-soft), 0 0 12px var(--focus-ring);
}

.workflow-icon span {
  border-color: var(--glass-surface-strong);
  color: var(--text-inverse);
}

.workflow-arrow,
.record-arrow,
.form-section-title span,
.agent-state small,
.live-events-heading span,
.pending-message p i,
.evidence-empty {
  color: var(--text-subtle);
}

.record-row:hover .record-copy strong,
.record-row:hover .record-arrow,
.symptom-options :deep(.el-checkbox.is-bordered:hover),
.symptom-options :deep(.el-checkbox.is-bordered:focus-within) {
  color: var(--primary-hover);
}

.form-section-title,
.intake-form :deep(.el-form-item__label),
.trace-meta-bar code,
.live-events-heading,
.live-event-row strong,
.evidence-empty strong,
.citation-summary strong {
  color: var(--text-secondary);
}

.trace-meta-bar,
.agent-state,
.cancel-row,
.live-event-row span,
.live-event-row small,
.empty-records p,
.evidence-heading p,
.evidence-item > small,
.result-actions {
  color: var(--text-muted);
}

.form-section-title strong,
.metric-item strong,
.evidence-item > strong {
  color: var(--text-primary);
}

.required-title span,
.live-event-row.is-error code,
.live-event-row.is-error strong,
.live-event-row.is-error span {
  color: var(--danger);
}

.matched-rule {
  color: var(--warning);
}

.pending-message p i {
  background: var(--text-subtle);
}

.symptom-options :deep(.el-checkbox.is-bordered) {
  border-color: var(--border-default);
}

.symptom-options :deep(.el-checkbox.is-bordered.is-checked) {
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--primary);
}

.tone-red .workflow-icon,
.agent-status-item.tone-red .agent-status-icon {
  background: var(--danger-soft);
  color: var(--danger);
}

.tone-red .workflow-icon span {
  background: var(--danger);
}

.tone-blue .workflow-icon,
.title-icon.tone-blue,
.agent-status-item.tone-blue .agent-status-icon,
.message-role {
  background: var(--primary-soft);
  color: var(--primary);
}

.tone-blue .workflow-icon span,
.message-row.user .message-role {
  background: var(--primary-solid);
  color: var(--text-inverse);
}

.tone-green .workflow-icon,
.title-icon.tone-green,
.agent-status-item.tone-green .agent-status-icon {
  background: var(--success-soft);
  color: var(--success);
}

.tone-green .workflow-icon span {
  background: var(--success);
}

.tone-purple .workflow-icon,
.agent-status-item.tone-purple .agent-status-icon {
  background: var(--accent-violet-soft);
  color: var(--accent-violet);
}

.tone-purple .workflow-icon span {
  background: var(--accent-violet);
}

.tone-orange .workflow-icon,
.agent-status-item.tone-orange .agent-status-icon {
  background: var(--warning-soft);
  color: var(--warning);
}

.tone-orange .workflow-icon span {
  background: var(--warning);
}

.live-evidence {
  border-color: color-mix(in srgb, var(--success) 34%, transparent);
  background: var(--success-soft);
}

.live-evidence-title,
.status-done .agent-state {
  color: var(--success);
}

.status-done .agent-state i {
  background: var(--success);
}

.status-running .agent-state {
  color: var(--primary);
}

.status-running .agent-state i {
  background: var(--primary);
}

.status-error .agent-state,
.status-error .agent-state i {
  color: var(--danger);
  background: var(--danger);
}

.live-events {
  border-color: var(--border-default);
  background: var(--surface-muted);
}

.message-row p {
  border: 1px solid var(--border-subtle);
  background: var(--surface-muted);
  color: var(--text-secondary);
}

.message-row.user p {
  border-color: var(--primary-subtle);
  background: var(--primary-soft);
  color: var(--text-primary);
}

.trace-meta-bar,
.trace-meta-bar code,
.live-events-heading,
.live-event-row,
.cancel-row,
.conversation-header small,
.message-role,
.metric-item small,
.matched-rule,
.safety-boundary p,
.evidence-heading p,
.evidence-meta > span,
.evidence-item > small,
.evidence-technical code,
.evidence-empty p,
.empty-records p,
.citation-summary strong,
.citation-summary span,
.result-actions,
.workflow-icon span,
.workflow-node p {
  font-size: 12px;
}

.steps-panel :deep(.el-step__title),
.consult-page small {
  font-size: 12px;
}

.workflow-node strong,
.record-copy strong,
.agent-status-copy strong,
.conversation-header strong,
.message-row p,
.result-section h3,
.answer-section p,
.evidence-item > strong,
.evidence-item p,
.safety-boundary strong {
  font-size: 14px;
}

.agent-status-item {
  position: relative;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  min-height: 78px;
  overflow: hidden;
  border-color: var(--border-default);
}

.agent-status-item::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  content: '';
  background: transparent;
}

.agent-status-item.status-running {
  border-color: var(--primary);
  background: var(--primary-soft);
  box-shadow: 0 0 0 3px var(--focus-ring);
}

.agent-status-item.status-running::before {
  background: var(--primary);
}

.agent-status-item.status-running::after {
  --consult-beam-angle: 0deg;

  position: absolute;
  inset: -1px;
  padding: 1.5px;
  border-radius: inherit;
  content: '';
  pointer-events: none;
  background: conic-gradient(
    from var(--consult-beam-angle),
    transparent 0 60%,
    var(--primary) 70%,
    var(--success) 78%,
    var(--accent-violet) 86%,
    transparent 94%
  );
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  animation: consult-border-beam 2.8s linear infinite;
}

.processing-panel {
  position: relative;
  overflow: hidden;
}

.processing-panel.is-active::after {
  position: absolute;
  top: 0;
  left: -38%;
  width: 34%;
  height: 2px;
  content: '';
  pointer-events: none;
  background: linear-gradient(90deg, transparent, var(--primary), var(--success), transparent);
  filter: drop-shadow(0 0 6px var(--focus-ring));
  animation: consult-panel-scan 3.6s ease-in-out infinite;
}

.processing-panel :deep(.el-progress-bar__inner) {
  background: linear-gradient(90deg, var(--primary), var(--success), var(--accent-violet), var(--primary));
  background-size: 220% 100%;
  animation: consult-progress-flow 2.8s linear infinite;
}

.workflow-node,
.workflow-icon {
  transition: transform 0.2s ease, filter 0.2s ease;
}

@media (hover: hover) {
  .workflow-node:hover .workflow-icon {
    filter: drop-shadow(0 7px 12px var(--focus-ring));
    transform: translateY(-3px) scale(1.04);
  }
}

@keyframes consult-border-beam {
  to {
    --consult-beam-angle: 360deg;
  }
}

@keyframes consult-gradient-shift {
  0%,
  100% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
}

@keyframes consult-panel-scan {
  0% {
    transform: translateX(0);
  }
  60%,
  100% {
    transform: translateX(410%);
  }
}

@keyframes consult-progress-flow {
  to {
    background-position: 220% 0;
  }
}

.agent-status-item.status-done {
  background: var(--success-soft);
}

.agent-status-item.status-error {
  border-color: var(--danger);
  background: var(--danger-soft);
}

.agent-state small {
  color: var(--text-muted);
  font-size: 12px;
}

.message-row p {
  background: var(--surface-muted);
}

.message-row.user p {
  background: var(--primary-soft);
  color: var(--text-primary);
}

.result-panel-high-risk {
  border-top: 3px solid var(--danger);
}

.result-heading :deep(.el-tag) {
  min-width: 72px;
  min-height: 32px;
  flex: 0 0 auto;
  justify-content: center;
  padding-inline: 12px;
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.result-heading :deep(.el-tag--success) {
  border-color: var(--success, #087f5b);
  background: var(--success, #087f5b);
  color: var(--text-inverse, #ffffff);
}

.result-heading :deep(.el-tag--warning) {
  border-color: var(--warning, #b45309);
  background: var(--warning, #b45309);
  color: var(--text-inverse, #ffffff);
}

.result-heading :deep(.el-tag--danger) {
  border-color: var(--danger, #d92d3a);
  background: var(--danger, #d92d3a);
  color: var(--text-inverse, #ffffff);
}

.result-heading :deep(.el-tag--info) {
  border-color: var(--text-muted, #667085);
  background: var(--text-muted, #667085);
  color: var(--text-inverse, #ffffff);
}

.result-metrics {
  margin-top: 2px;
}

.metric-item {
  min-height: 92px;
}

.metric-icon {
  width: 42px;
  height: 42px;
}

.metric-item strong {
  margin-top: 5px;
  color: var(--text-primary);
  font-size: 15px;
}

.metric-item:nth-child(-n + 2) strong {
  font-size: 18px;
}

.metric-icon.blue {
  background: var(--primary-soft);
  color: var(--primary);
}

.metric-icon.orange {
  background: var(--warning-soft);
  color: var(--warning);
}

.metric-icon.green {
  background: var(--success-soft);
  color: var(--success);
}

.metric-icon.purple {
  background: var(--accent-violet-soft);
  color: var(--accent-violet);
}

.emergency-answer-section {
  border-color: var(--danger);
  background: var(--danger-soft);
}

.emergency-answer-section h3,
.emergency-answer-section p {
  color: var(--danger);
}

.safety-boundary {
  border-color: var(--primary-subtle);
  background: var(--info-soft);
  color: var(--primary);
}

.safety-boundary p {
  color: var(--text-secondary);
}

.evidence-technical code,
.citation-summary span {
  border-color: var(--border-default);
  background: var(--surface-subtle);
  color: var(--text-secondary);
}

@media (max-width: 640px) {
  .metric-item {
    min-height: 0;
  }

  .metric-item,
  .metric-item:nth-child(even) {
    border-color: var(--border-subtle);
  }

  .consult-workspace-header,
  .result-heading,
  .result-actions {
    flex-wrap: wrap;
  }
}

@media (prefers-reduced-motion: reduce) {
  .welcome-banner,
  .agent-status-item.status-running::after,
  .processing-panel.is-active::after,
  .processing-panel :deep(.el-progress-bar__inner) {
    animation: none;
  }

  .status-running .agent-state i,
  .pending-message p i,
  .streaming-caret,
  .online-dot {
    animation: none;
  }

  .workflow-node,
  .workflow-icon {
    transition: none;
  }
}

.draft-resume-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid var(--primary-subtle);
  border-radius: var(--radius-lg);
  background: var(--primary-soft);
}

.draft-resume-copy,
.draft-resume-copy p {
  min-width: 0;
}

.draft-resume-copy strong,
.draft-resume-copy p {
  display: block;
}

.draft-resume-copy strong {
  color: var(--text-primary);
  font-size: 13px;
}

.draft-resume-copy p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.draft-resume-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.step-context {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: -4px 0 12px;
  color: var(--text-muted);
  font-size: 12px;
}

.step-context strong {
  color: var(--text-primary);
  font-size: 13px;
}

.result-action-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 18px 0 4px;
  padding: 16px;
  border: 1px solid var(--primary-subtle);
  border-left: 3px solid var(--primary);
  border-radius: var(--radius-md);
  background: var(--primary-soft);
}

.result-action-summary.is-high-risk {
  border-color: var(--danger);
  border-left-color: var(--danger);
  background: var(--danger-soft);
}

.result-action-summary__copy span,
.result-action-summary__copy strong,
.result-action-summary__copy small {
  display: block;
}

.result-action-summary__copy span {
  color: var(--text-muted);
  font-size: 11px;
}

.result-action-summary__copy strong {
  margin-top: 4px;
  color: var(--text-primary);
  font-size: 16px;
  line-height: 1.45;
}

.result-action-summary.is-high-risk .result-action-summary__copy strong {
  color: var(--danger);
}

  .result-action-summary__copy small {
    margin-top: 4px;
    color: var(--text-secondary);
    font-size: 12px;
  }

.result-emergency-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--danger);
  border-radius: var(--radius-md);
  color: var(--danger);
  background: var(--surface-elevated);
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
}

.result-emergency-link:hover {
  color: var(--text-inverse);
  background: var(--danger);
}

.result-details,
.evidence-details {
  border-bottom: 1px solid var(--border-subtle);
}

.result-details > summary,
.evidence-details > summary,
.technical-details > summary {
  cursor: pointer;
  list-style: none;
}

.result-details > summary::-webkit-details-marker,
.evidence-details > summary::-webkit-details-marker,
.technical-details > summary::-webkit-details-marker {
  display: none;
}

.result-details > summary,
.evidence-details > summary {
  padding: 14px 0;
  color: var(--primary);
  font-size: 12px;
  font-weight: 600;
}

.result-details > summary::after,
.evidence-details > summary::after,
.technical-details > summary::after {
  display: inline-block;
  margin-left: 6px;
  content: '▾';
  transition: transform .16s ease;
}

.result-details[open] > summary::after,
.evidence-details[open] > summary::after,
.technical-details[open] > summary::after {
  transform: rotate(180deg);
}

.result-details__body {
  padding-bottom: 4px;
}

.result-details__body .result-section:first-child,
.result-details__body .result-visualization-section {
  padding-top: 8px;
}

.technical-details {
  display: inline-block;
  margin-left: 8px;
  vertical-align: middle;
}

.technical-details > summary {
  display: inline-block;
  color: var(--text-muted);
  font-size: 11px;
}

.technical-details > code {
  display: block;
  margin-top: 5px;
  padding: 4px 6px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: var(--surface-muted);
  font-size: 10px;
}

.followup-prompt {
  display: grid;
  gap: 4px;
}

.followup-kicker {
  color: var(--primary);
  font-size: 10px;
  font-weight: 700;
}

.followup-prompt strong {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.55;
}

.followup-prompt small {
  color: var(--text-muted);
  font-size: 11px;
}

.followup-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.followup-suggestions button {
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  background: var(--surface-elevated);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}

.followup-suggestions button:hover,
.followup-suggestions button:focus-visible {
  border-color: var(--primary);
  color: var(--primary);
  outline: none;
}

.followup-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.journey-steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 0 0 14px;
  padding: 0;
  list-style: none;
}

.journey-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 9px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  color: var(--text-muted);
  background: var(--surface-muted);
  font-size: 11px;
}

.journey-steps li > span {
  display: grid;
  width: 22px;
  height: 22px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: var(--surface-elevated);
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
}

.journey-steps li strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.journey-steps li.is-active {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-soft);
}

.journey-steps li.is-active > span,
.journey-steps li.is-complete > span {
  background: var(--primary);
  color: var(--text-inverse);
}

.journey-steps li.is-complete {
  color: var(--text-secondary);
}

.imported-description-note {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: -2px 0 16px;
  padding: 9px 11px;
  border: 1px solid var(--primary-subtle);
  border-radius: var(--radius-md);
  color: var(--primary);
  background: var(--primary-soft);
  font-size: 12px;
}

.field-error {
  margin: 7px 0 0;
  color: var(--danger);
  font-size: 11px;
  line-height: 1.45;
}

.symptom-options.has-error :deep(.el-checkbox.is-bordered) {
  border-color: color-mix(in srgb, var(--danger) 55%, var(--border-default));
}

.friendly-progress-list {
  display: grid;
  gap: 8px;
  margin-top: 20px;
}

.friendly-progress-item {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  min-height: 38px;
  padding: 8px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  color: var(--text-muted);
  background: var(--surface-muted);
  font-size: 12px;
}

.friendly-progress-icon {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  border-radius: 50%;
  color: var(--text-muted);
  background: var(--surface-elevated);
}

.friendly-progress-icon i {
  width: 7px;
  height: 7px;
  border: 2px solid currentColor;
  border-radius: 50%;
}

.friendly-progress-item strong {
  color: var(--text-secondary);
  font-weight: 600;
}

.friendly-progress-item small {
  color: var(--text-muted);
  font-size: 10px;
}

.friendly-progress-item.is-running {
  border-color: var(--primary-subtle);
  background: var(--primary-soft);
}

.friendly-progress-item.is-running .friendly-progress-icon {
  color: var(--primary);
}

.friendly-progress-item.is-running .friendly-progress-icon i {
  animation: pulse 1.1s ease-in-out infinite;
}

.friendly-progress-item.is-done,
.friendly-progress-item.is-done .friendly-progress-icon {
  color: var(--success);
}

.friendly-progress-item.is-done strong {
  color: var(--text-secondary);
}

.friendly-progress-item.is-error,
.friendly-progress-item.is-error .friendly-progress-icon,
.friendly-progress-item.is-error strong {
  border-color: var(--danger);
  color: var(--danger);
}

.runtime-details {
  margin-top: 14px;
  border-top: 1px solid var(--border-subtle);
  border-bottom: 1px solid var(--border-subtle);
}

.runtime-details > summary {
  padding: 11px 0;
  cursor: pointer;
  color: var(--text-muted);
  font-size: 11px;
  list-style: none;
}

.runtime-details > summary::-webkit-details-marker {
  display: none;
}

.runtime-details > summary::after {
  display: inline-block;
  margin-left: 6px;
  content: '▾';
  transition: transform .16s ease;
}

.runtime-details[open] > summary::after {
  transform: rotate(180deg);
}

.runtime-details .trace-meta-bar {
  margin-top: 0;
}

.evidence-context {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 10px;
}

.evidence-explanation {
  color: var(--text-secondary) !important;
  font-size: 11px !important;
}

.evidence-technical-details {
  margin-top: 7px;
}

.evidence-technical-details > summary {
  cursor: pointer;
  color: var(--text-muted);
  font-size: 10px;
}

.evidence-technical-details > summary::before {
  content: '＋ ';
}

.evidence-technical-details[open] > summary::before {
  content: '－ ';
}

.result-feedback {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 12px;
  padding: 13px 2px;
  color: var(--text-secondary);
}

.result-feedback strong,
.result-feedback small {
  display: block;
}

.result-feedback strong {
  font-size: 12px;
}

.result-feedback small {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 10px;
}

.result-feedback-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.result-metrics {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.feedback-thanks {
  flex: 0 0 auto;
  color: var(--success);
  font-size: 11px;
}

@media (max-width: 640px) {
  .draft-resume-banner,
  .result-action-summary,
  .result-feedback {
    align-items: stretch;
    flex-direction: column;
  }

  .draft-resume-actions,
  .result-emergency-link {
    width: 100%;
  }

  .draft-resume-actions > .el-button,
  .result-emergency-link {
    justify-content: center;
    flex: 1;
  }

  .step-context {
    align-items: flex-start;
    flex-direction: column;
    gap: 2px;
  }

  .journey-steps {
    gap: 5px;
  }

  .journey-steps li {
    justify-content: center;
    padding-inline: 5px;
  }

  .journey-steps li > span {
    width: 20px;
    height: 20px;
  }

  .journey-steps li strong {
    font-size: 10px;
  }

  .form-actions.intake-submit-bar {
    position: sticky;
    bottom: 0;
    z-index: 10;
    margin: 12px -16px -20px;
    padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
    border-top: 1px solid var(--border-default);
    background: color-mix(in srgb, var(--glass-surface) 94%, transparent);
    box-shadow: 0 -8px 18px color-mix(in srgb, var(--shadow-color, #101828) 10%, transparent);
    backdrop-filter: blur(16px);
  }

  .result-feedback-actions {
    justify-content: stretch;
    width: 100%;
  }

  .result-feedback-actions .el-button {
    flex: 1 1 30%;
    min-height: 34px;
    margin: 0;
  }

  .result-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
