<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  CircleCheckFilled,
  Clock,
  Collection,
  Connection,
  Delete,
  Document,
  Files,
  Grid,
  Link,
  OfficeBuilding,
  Plus,
  Refresh,
  Search,
  UploadFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const stats = ref(null)
const docs = ref([])
const versions = ref([])
const currentVersion = ref(null)
const loadingStats = ref(false)
const loadingDocs = ref(false)
const loadingVersions = ref(false)
const submitting = ref(false)
const deletingId = ref(null)
const buildingVersion = ref(false)
  const activatingVersion = ref(null)
const diffVisible = ref(false)
const diffLoading = ref(false)
const diffError = ref('')
const diffVersion = ref('')
const diffAgainst = ref('')
const diffResult = ref(null)
const reviewingId = ref(null)
const loadError = ref('')
const versionError = ref('')
const searchText = ref('')
const ingestVisible = ref(false)
const formRef = ref(null)

function emptyIngestForm() {
  return {
    doc_id: '',
    department: '',
    institution: '',
    title: '',
    url: '',
    published_date: '',
    version: '',
    license: '',
    expires_at: '',
    change_reason: '',
    text: '',
    reviewApproved: false,
  }
}

function validateDocumentId(_rule, value, callback) {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(value || '')) {
    callback(new Error('仅支持字母、数字、点、下划线和连字符'))
    return
  }
  callback()
}

function validateHttpsUrl(_rule, value, callback) {
  try {
    const parsed = new URL(value)
    if (parsed.protocol === 'https:') {
      callback()
      return
    }
  } catch {
    // Report the same validation message for malformed and insecure URLs.
  }
  callback(new Error('请输入有效的 HTTPS 来源地址'))
}

function validateApproval(_rule, value, callback) {
  if (value === true) callback()
  else callback(new Error('请确认资料已完成来源与内容审核'))
}

const form = reactive(emptyIngestForm())
const departments = ['心血管内科', '呼吸内科', '消化内科', '皮肤科']
const formRules = {
  doc_id: [
    { required: true, message: '请输入唯一文档 ID', trigger: 'blur' },
    { validator: validateDocumentId, trigger: 'blur' },
  ],
  department: [{ required: true, message: '请选择所属科室', trigger: 'change' }],
  institution: [{ required: true, message: '请输入发布机构', trigger: 'blur' }],
  title: [{ required: true, message: '请输入资料标题', trigger: 'blur' }],
  url: [
    { required: true, message: '请输入来源地址', trigger: 'blur' },
    { validator: validateHttpsUrl, trigger: 'blur' },
  ],
  published_date: [{ required: true, message: '请选择发布日期', trigger: 'change' }],
  version: [{ required: true, message: '请输入资料版本', trigger: 'blur' }],
  license: [{ required: true, message: '请输入许可信息', trigger: 'blur' }],
  text: [{ required: true, message: '请输入医学资料正文', trigger: 'blur' }],
  reviewApproved: [{ validator: validateApproval, trigger: 'change' }],
}

const sourceRows = computed(() => {
  const grouped = new Map()
  docs.value.forEach((doc) => {
    const source = normalizeText(doc.source) || '来源未标注'
    const current = grouped.get(source) || { source, docs: 0, chunks: 0 }
    current.docs += 1
    current.chunks += Number(doc.chunk_count) || 0
    grouped.set(source, current)
  })
  return [...grouped.values()].sort((left, right) => right.docs - left.docs)
})

const totalDocs = computed(() => Number(stats.value?.total_docs) || docs.value.length)
const totalChunks = computed(() => {
  if (Number.isFinite(Number(stats.value?.total_chunks))) return Number(stats.value.total_chunks)
  return docs.value.reduce((sum, doc) => sum + (Number(doc.chunk_count) || 0), 0)
})
const departmentCount = computed(() => {
  if (stats.value?.departments) return Object.keys(stats.value.departments).length
  return new Set(docs.value.map((doc) => normalizeText(doc.department)).filter(Boolean)).size
})
const activeVersion = computed(
  () => normalizeText(currentVersion.value) || normalizeText(stats.value?.active_version),
)
const versionRows = computed(() =>
  versions.value.filter((version) => normalizeText(version?.version)),
)
const activeManifest = computed(() =>
  versionRows.value.find((version) => normalizeText(version.version) === activeVersion.value),
)
const diffCandidates = computed(() =>
  versionRows.value.filter((version) => normalizeText(version.version) !== diffVersion.value),
)

const metricItems = computed(() => [
  {
    label: '待审核资料',
    value: Number(stats.value?.pending_docs) || 0,
    unit: '篇',
    detail: '尚未进入检索索引',
    icon: Clock,
    tone: 'orange',
  },
  {
    label: '知识文档',
    value: totalDocs.value,
    unit: '篇',
    detail: '当前已收录资料',
    icon: Document,
    tone: 'blue',
  },
  {
    label: '知识切片',
    value: totalChunks.value,
    unit: '块',
    detail: '用于向量检索',
    icon: Grid,
    tone: 'green',
  },
  {
    label: '覆盖科室',
    value: departmentCount.value,
    unit: '个',
    detail: '按切片科室统计',
    icon: OfficeBuilding,
    tone: 'orange',
  },
  {
    label: '资料来源',
    value: sourceRows.value.length,
    unit: '类',
    detail: '按来源名称去重',
    icon: Link,
    tone: 'purple',
  },
  {
    label: '平均切片',
    value: totalDocs.value ? (totalChunks.value / totalDocs.value).toFixed(1) : '0',
    unit: '块/篇',
    detail: '当前文档平均值',
    icon: Collection,
    tone: 'red',
  },
])

const filteredDocs = computed(() => {
  const keyword = searchText.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return docs.value

  return docs.value.filter((doc) =>
    [
      doc.doc_id,
      doc.department,
      doc.source,
      doc.institution,
      doc.title,
      doc.published_date,
      doc.text_preview,
    ]
      .map((value) => normalizeText(value).toLocaleLowerCase('zh-CN'))
      .some((value) => value.includes(keyword)),
  )
})

const isLoading = computed(
  () => loadingStats.value || loadingDocs.value || loadingVersions.value,
)

function normalizeText(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function responsePayload(data) {
  return data && Object.prototype.hasOwnProperty.call(data, 'data') ? data.data : data
}

function errorMessage(error, fallback) {
  const payload = error.response?.data
  const raw = payload?.error || payload?.message || payload?.detail
  if (typeof raw !== 'string' || !raw.trim()) return fallback

  try {
    const parsed = JSON.parse(raw)
    return parsed.detail || parsed.message || parsed.error || raw
  } catch {
    return raw
  }
}

function formatVersionTime(value) {
  const raw = normalizeText(value)
  if (!raw) return '创建时间未记录'
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return raw
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

function isActiveVersion(version) {
  return normalizeText(version?.version) === activeVersion.value
}

function reviewStatusLabel(status) {
  return { approved: '已审核', pending: '待审核', rejected: '已退回' }[status] || '状态未知'
}

function reviewStatusType(status) {
  return { approved: 'success', pending: 'warning', rejected: 'danger' }[status] || 'info'
}

async function fetchStats() {
  loadingStats.value = true
  try {
    const { data } = await client.get('/knowledge/stats')
    stats.value = responsePayload(data) || null
  } catch (error) {
    stats.value = null
    loadError.value = errorMessage(error, '知识库统计暂时无法加载，请稍后重试。')
  } finally {
    loadingStats.value = false
  }
}

async function fetchDocs() {
  loadingDocs.value = true
  try {
    const { data } = await client.get('/knowledge/docs')
    const payload = responsePayload(data)
    docs.value = Array.isArray(payload?.docs) ? payload.docs : []
  } catch (error) {
    docs.value = []
    loadError.value = errorMessage(error, '知识文档暂时无法加载，请稍后重试。')
  } finally {
    loadingDocs.value = false
  }
}

async function fetchVersions() {
  loadingVersions.value = true
  versionError.value = ''
  try {
    const { data } = await client.get('/knowledge/versions')
    const payload = responsePayload(data)
    currentVersion.value = normalizeText(payload?.current) || null
    versions.value = Array.isArray(payload?.versions) ? payload.versions : []
  } catch (error) {
    currentVersion.value = null
    versions.value = []
    versionError.value = errorMessage(error, '索引版本暂时无法加载，请稍后重试。')
  } finally {
    loadingVersions.value = false
  }
}

async function refreshAll() {
  loadError.value = ''
  await Promise.all([fetchStats(), fetchDocs(), fetchVersions()])
}

function openIngest() {
  ingestVisible.value = true
}

async function handleIngest() {
  if (submitting.value) return

  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const institution = normalizeText(form.institution)
    const title = normalizeText(form.title)
    const payload = {
      doc_id: normalizeText(form.doc_id),
      department: normalizeText(form.department),
      source: `${institution}｜${title}`,
      institution,
      title,
      url: normalizeText(form.url),
      published_date: normalizeText(form.published_date),
      version: normalizeText(form.version),
      license: normalizeText(form.license),
      expires_at: normalizeText(form.expires_at),
      change_reason: normalizeText(form.change_reason),
      review_status: 'pending',
      text: normalizeText(form.text),
    }
    await client.post('/knowledge/ingest', payload)
    ElMessage.success(`文档“${payload.doc_id}”已提交审核，通过后才会进入检索索引`)
    ingestVisible.value = false
    Object.assign(form, emptyIngestForm())
    formRef.value?.clearValidate()
    await refreshAll()
  } catch (error) {
    ElMessage.error(errorMessage(error, '录入失败，请检查 AI 服务与向量模型状态。'))
  } finally {
    submitting.value = false
  }
}

async function handleReview(doc, action) {
  if (!doc?.doc_id || reviewingId.value) return
  const approving = action === 'approve'
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      approving ? '请填写本次医学内容复核说明。' : '请填写退回原因，便于后续修订。',
      approving ? '审核通过' : '退回文档',
      {
        type: approving ? 'success' : 'warning',
        confirmButtonText: approving ? '确认通过' : '确认退回',
        cancelButtonText: '取消',
        inputPlaceholder: approving ? '例如：来源、许可与内容复核通过' : '例如：来源日期无法核验',
        inputValidator: (value) => normalizeText(value).length >= 4 || '请至少输入 4 个字符',
      },
    )
    reason = normalizeText(result.value)
  } catch {
    return
  }

  reviewingId.value = doc.doc_id
  try {
    const { data } = await client.post(`/knowledge/docs/${encodeURIComponent(doc.doc_id)}/review`, {
      action,
      reviewer: auth.username || 'admin',
      change_reason: reason,
    })
    const payload = responsePayload(data)
    const version = normalizeText(payload?.version)
    ElMessage.success(
      approving
        ? `文档“${doc.doc_id}”已通过审核${version ? `，生成版本 ${version}` : ''}`
        : `文档“${doc.doc_id}”已退回`,
    )
    await refreshAll()
  } catch (error) {
    ElMessage.error(errorMessage(error, '审核操作失败，请稍后重试。'))
  } finally {
    reviewingId.value = null
  }
}

async function handleDelete(docId) {
  try {
    await ElMessageBox.confirm(
      `删除“${docId}”后将生成新的待激活索引版本，是否继续？`,
      '删除知识文档',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  deletingId.value = docId
  try {
    const { data } = await client.delete(`/knowledge/${encodeURIComponent(docId)}`)
    const result = responsePayload(data)
    const version = normalizeText(result?.version)
    ElMessage.success(
      version
        ? `文档“${docId}”已删除，并生成待激活版本 ${version}`
        : `文档“${docId}”已删除，当前活动版本保持不变`,
    )
    await refreshAll()
  } catch (error) {
    ElMessage.error(errorMessage(error, '删除失败，请稍后重试。'))
  } finally {
    deletingId.value = null
  }
}

async function handleBuildVersion() {
  if (buildingVersion.value) return

  buildingVersion.value = true
  try {
    const { data } = await client.post('/knowledge/versions/build')
    const manifest = responsePayload(data)
    const version = normalizeText(manifest?.version)
    ElMessage.success(version ? `索引版本 ${version} 构建完成` : '新索引版本构建完成')
    await fetchVersions()
  } catch (error) {
    ElMessage.error(errorMessage(error, '索引版本构建失败，请稍后重试。'))
  } finally {
    buildingVersion.value = false
  }
}

async function handleActivateVersion(version) {
  const versionId = normalizeText(version?.version)
  if (!versionId || isActiveVersion(version) || activatingVersion.value) return

  try {
    await ElMessageBox.confirm(
      `确认将索引版本 ${versionId} 设为当前活动版本？`,
      '切换活动版本',
      {
        type: 'warning',
        confirmButtonText: '确认激活',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  activatingVersion.value = versionId
  try {
    await client.post(`/knowledge/versions/${encodeURIComponent(versionId)}/activate`)
    ElMessage.success(`索引版本 ${versionId} 已激活`)
    await Promise.all([fetchVersions(), fetchStats()])
  } catch (error) {
    ElMessage.error(errorMessage(error, '版本激活失败，请稍后重试。'))
  } finally {
    activatingVersion.value = null
  }
}

function versionLabel(version) {
  const id = normalizeText(version?.version)
  if (!id) return '未知版本'
  return isActiveVersion(version) ? `${id}（当前）` : id
}

function openVersionDiff(version) {
  const selected = normalizeText(version?.version)
  if (!selected) return
  diffVersion.value = selected
  const preferred = activeVersion.value && activeVersion.value !== selected
    ? activeVersion.value
    : diffCandidates.value.find((item) => normalizeText(item.version) !== selected)?.version || ''
  diffAgainst.value = normalizeText(preferred)
  diffResult.value = null
  diffError.value = ''
  diffVisible.value = true
  if (diffAgainst.value) loadVersionDiff()
}

async function loadVersionDiff() {
  if (!diffVersion.value || !diffAgainst.value || diffVersion.value === diffAgainst.value) return
  diffLoading.value = true
  diffError.value = ''
  try {
    const { data } = await client.get(
      `/knowledge/versions/${encodeURIComponent(diffVersion.value)}/diff`,
      { params: { against: diffAgainst.value } },
    )
    diffResult.value = responsePayload(data) || null
  } catch (error) {
    diffResult.value = null
    diffError.value = errorMessage(error, '版本差异暂时无法加载，请稍后重试。')
  } finally {
    diffLoading.value = false
  }
}

onMounted(refreshAll)
</script>

<template>
  <div class="kbx-page">
    <header class="kbx-header">
      <div class="kbx-heading">
        <span>MEDICAL KNOWLEDGE</span>
        <h1>医学知识库</h1>
        <p>管理本地医学资料与向量索引，为多智能体检索提供可追溯依据</p>
      </div>

      <div class="kbx-header-actions">
        <el-tooltip content="刷新知识库" placement="bottom">
          <el-button circle plain aria-label="刷新知识库" :loading="isLoading" @click="refreshAll">
            <el-icon v-if="!isLoading"><Refresh /></el-icon>
          </el-button>
        </el-tooltip>
        <el-button type="primary" @click="openIngest">
          <el-icon><Plus /></el-icon>
          录入文档
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="loadError"
      class="kbx-alert"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
    >
      <template #default>
        <el-button text type="primary" @click="refreshAll">重新加载</el-button>
      </template>
    </el-alert>

    <section class="kbx-search-panel" aria-label="知识库检索">
      <el-input
        v-model="searchText"
        class="kbx-search-input"
        size="large"
        clearable
        placeholder="搜索文档 ID、科室、来源或内容摘要"
        aria-label="搜索医学知识库"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
        <template #append>
          <el-button type="primary" aria-label="执行检索">
            <el-icon><Search /></el-icon>
            检索
          </el-button>
        </template>
      </el-input>
    </section>

    <section class="kbx-metrics" aria-label="知识库统计">
      <article v-for="metric in metricItems" :key="metric.label" class="kbx-metric">
        <span :class="['kbx-metric-icon', `kbx-tone-${metric.tone}`]">
          <el-icon :size="21"><component :is="metric.icon" /></el-icon>
        </span>
        <div class="kbx-metric-copy">
          <span>{{ metric.label }}</span>
          <strong v-if="!loadingStats">
            {{ metric.value }}
            <small>{{ metric.unit }}</small>
          </strong>
          <strong v-else class="kbx-metric-loading">--</strong>
          <p>{{ metric.detail }}</p>
        </div>
      </article>
    </section>

    <section
      :class="['kbx-version-panel', { 'is-building': buildingVersion }]"
      aria-label="知识索引版本"
      :aria-busy="buildingVersion"
    >
      <div class="kbx-panel-heading kbx-version-heading">
        <div>
          <span>INDEX VERSIONS</span>
          <h2>索引版本</h2>
        </div>
        <el-button
          type="primary"
          plain
          :loading="buildingVersion"
          :disabled="loadingVersions || Boolean(activatingVersion)"
          @click="handleBuildVersion"
        >
          <el-icon v-if="!buildingVersion"><Refresh /></el-icon>
          {{ buildingVersion ? '正在构建' : '构建新版本' }}
        </el-button>
      </div>

      <el-alert
        v-if="versionError"
        class="kbx-version-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="versionError"
      >
        <template #default>
          <el-button text type="primary" @click="fetchVersions">重新加载</el-button>
        </template>
      </el-alert>

      <div v-if="loadingVersions" class="kbx-version-loading">
        <el-skeleton :rows="3" animated />
      </div>
      <template v-else>
        <div class="kbx-active-version" aria-live="polite">
          <span class="kbx-active-version-icon">
            <el-icon><CircleCheckFilled /></el-icon>
          </span>
          <div class="kbx-active-version-copy">
            <small>当前活动版本</small>
            <code :title="activeVersion || '尚未激活索引版本'">
              {{ activeVersion || '尚未激活索引版本' }}
            </code>
            <p v-if="activeManifest">
              {{ formatVersionTime(activeManifest.created_at) }} ·
              {{ Number(activeManifest.document_count) || 0 }} 篇文档 ·
              {{ Number(activeManifest.chunk_count) || 0 }} 个切片
            </p>
          </div>
          <el-tag :type="activeVersion ? 'success' : 'info'" effect="light">
            {{ activeVersion ? '使用中' : '未激活' }}
          </el-tag>
        </div>

        <div class="kbx-version-list-heading">
          <strong>可用版本</strong>
          <small>共 {{ versionRows.length }} 个</small>
        </div>
        <el-empty
          v-if="!versionRows.length"
          :image-size="54"
          description="暂无可用索引版本"
        />
        <div v-else class="kbx-version-list">
          <div
            v-for="version in versionRows"
            :key="version.version"
            class="kbx-version-row"
          >
            <div class="kbx-version-identity">
              <code :title="version.version">{{ version.version }}</code>
              <span><el-icon><Clock /></el-icon>{{ formatVersionTime(version.created_at) }}</span>
            </div>
            <div class="kbx-version-meta">
              <span><strong>{{ Number(version.document_count) || 0 }}</strong> 篇文档</span>
              <span><strong>{{ Number(version.chunk_count) || 0 }}</strong> 个切片</span>
            </div>
            <el-tag v-if="isActiveVersion(version)" type="success" size="small" effect="light">
              当前版本
            </el-tag>
            <el-button
              v-else
              size="small"
              type="primary"
              plain
              :loading="activatingVersion === version.version"
              :disabled="buildingVersion || Boolean(activatingVersion)"
              @click="handleActivateVersion(version)"
            >
              激活
            </el-button>
            <el-button
              size="small"
              text
              type="primary"
              :disabled="versionRows.length < 2 || Boolean(activatingVersion)"
              @click="openVersionDiff(version)"
            >
              查看差异
            </el-button>
          </div>
        </div>
      </template>
    </section>

    <el-dialog
      v-model="diffVisible"
      title="索引版本差异"
      width="min(720px, 94vw)"
      class="kbx-diff-dialog"
      destroy-on-close
    >
      <div class="kbx-diff-toolbar">
        <div>
          <span>比较版本</span>
          <el-select v-model="diffVersion" aria-label="选择比较版本" @change="loadVersionDiff">
            <el-option
              v-for="version in versionRows"
              :key="`diff-current-${version.version}`"
              :label="versionLabel(version)"
              :value="version.version"
            />
          </el-select>
        </div>
        <el-icon class="kbx-diff-arrow" aria-hidden="true"><Connection /></el-icon>
        <div>
          <span>对比基线</span>
          <el-select
            v-model="diffAgainst"
            aria-label="选择对比基线"
            :disabled="!diffCandidates.length"
            @change="loadVersionDiff"
          >
            <el-option
              v-for="version in diffCandidates"
              :key="`diff-against-${version.version}`"
              :label="versionLabel(version)"
              :value="version.version"
            />
          </el-select>
        </div>
        <el-button type="primary" :loading="diffLoading" :disabled="!diffAgainst" @click="loadVersionDiff">
          <el-icon v-if="!diffLoading"><Search /></el-icon>
          刷新差异
        </el-button>
      </div>

      <el-alert v-if="diffError" type="warning" :closable="false" show-icon :title="diffError" />
      <el-empty v-else-if="!diffCandidates.length" description="至少需要两个索引版本才能比较" />
      <div v-else-if="diffLoading" class="kbx-diff-loading"><el-skeleton :rows="4" animated /></div>
      <template v-else-if="diffResult">
        <div class="kbx-diff-summary">
          <div><strong>{{ diffResult.added?.length || 0 }}</strong><span>新增</span></div>
          <div><strong>{{ diffResult.changed?.length || 0 }}</strong><span>变更</span></div>
          <div><strong>{{ diffResult.removed?.length || 0 }}</strong><span>移除</span></div>
          <div><strong>{{ diffResult.unchanged || 0 }}</strong><span>未变</span></div>
        </div>
        <div class="kbx-diff-groups">
          <section v-for="group in [
            { key: 'added', label: '新增文档', type: 'success' },
            { key: 'changed', label: '内容变更', type: 'warning' },
            { key: 'removed', label: '移除文档', type: 'danger' },
          ]" :key="group.key" class="kbx-diff-group">
            <div class="kbx-diff-group-heading">
              <strong>{{ group.label }}</strong>
              <el-tag size="small" :type="group.type">{{ diffResult[group.key]?.length || 0 }}</el-tag>
            </div>
            <div v-if="diffResult[group.key]?.length" class="kbx-diff-id-list">
              <code v-for="id in diffResult[group.key]" :key="id">{{ id }}</code>
            </div>
            <span v-else class="kbx-diff-none">无</span>
          </section>
        </div>
        <p class="kbx-diff-note">差异仅展示文档标识，不在监控界面暴露医学正文。</p>
      </template>
      <el-empty v-else description="选择两个版本后查看差异" />
    </el-dialog>

    <section class="kbx-source-panel">
      <div class="kbx-panel-heading">
        <div>
          <span>AUTHORIZED SOURCES</span>
          <h2>资料来源</h2>
        </div>
        <small>依据当前已收录文档统计</small>
      </div>

      <div v-if="loadingDocs" class="kbx-source-skeleton">
        <el-skeleton :rows="1" animated />
      </div>
      <el-empty v-else-if="!sourceRows.length" :image-size="54" description="暂未收录资料来源" />
      <div v-else class="kbx-source-list">
        <div v-for="source in sourceRows" :key="source.source" class="kbx-source-item">
          <span class="kbx-source-icon"><el-icon><Connection /></el-icon></span>
          <div>
            <strong>{{ source.source }}</strong>
            <small>{{ source.docs }} 篇文档 · {{ source.chunks }} 个切片</small>
          </div>
        </div>
      </div>
    </section>

    <section class="kbx-doc-panel">
      <div class="kbx-panel-heading kbx-doc-heading">
        <div>
          <span>KNOWLEDGE DOCUMENTS</span>
          <h2>知识文档</h2>
        </div>
        <small>
          {{ searchText.trim() ? `检索到 ${filteredDocs.length} 篇` : `共 ${docs.length} 篇` }}
        </small>
      </div>

      <div v-if="loadingDocs" class="kbx-doc-loading">
        <div v-for="index in 3" :key="index" class="kbx-skeleton-row">
          <el-skeleton :rows="2" animated />
        </div>
      </div>
      <el-empty
        v-else-if="!filteredDocs.length"
        :description="searchText.trim() ? '未找到匹配的知识文档' : '暂无知识文档'"
      >
        <el-button v-if="searchText.trim()" type="primary" plain @click="searchText = ''">
          清除检索
        </el-button>
        <el-button v-else type="primary" @click="openIngest">录入首篇文档</el-button>
      </el-empty>
      <div v-else class="kbx-doc-list">
        <article v-for="doc in filteredDocs" :key="doc.doc_id" class="kbx-doc-row">
          <span class="kbx-doc-icon"><el-icon><Files /></el-icon></span>

          <div class="kbx-doc-content">
            <div class="kbx-doc-title-row">
              <h3>{{ normalizeText(doc.title) || normalizeText(doc.source) || doc.doc_id }}</h3>
              <code>{{ doc.doc_id }}</code>
            </div>
            <p>{{ normalizeText(doc.text_preview) || '接口未提供内容摘要' }}</p>
            <div class="kbx-doc-meta">
              <el-tag size="small" effect="light">{{ normalizeText(doc.department) || '科室未标注' }}</el-tag>
              <el-tag
                size="small"
                :type="reviewStatusType(doc.review_status)"
                effect="plain"
              >
                {{ reviewStatusLabel(doc.review_status) }}
              </el-tag>
              <el-tag v-if="doc.expired" size="small" type="danger" effect="plain">已过期</el-tag>
              <span v-if="normalizeText(doc.institution)"><el-icon><OfficeBuilding /></el-icon>{{ doc.institution }}</span>
              <span v-if="normalizeText(doc.published_date)"><el-icon><Clock /></el-icon>{{ doc.published_date }}</span>
              <span><el-icon><Grid /></el-icon>{{ Number(doc.chunk_count) || 0 }} 个知识切片</span>
            </div>
          </div>

          <div class="kbx-doc-actions">
            <template v-if="doc.review_status !== 'approved'">
              <el-button
                size="small"
                type="success"
                plain
                :loading="reviewingId === doc.doc_id"
                @click="handleReview(doc, 'approve')"
              >审核通过</el-button>
              <el-button
                v-if="doc.review_status === 'pending'"
                size="small"
                type="warning"
                plain
                :disabled="Boolean(reviewingId)"
                @click="handleReview(doc, 'reject')"
              >退回</el-button>
            </template>
            <el-tooltip content="删除文档" placement="left">
              <el-button
                class="kbx-delete-button"
                text
                type="danger"
                circle
                :aria-label="`删除文档 ${doc.doc_id}`"
                :loading="deletingId === doc.doc_id"
                @click="handleDelete(doc.doc_id)"
              >
                <el-icon v-if="deletingId !== doc.doc_id"><Delete /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </article>
      </div>
    </section>

    <el-dialog
      v-model="ingestVisible"
      class="kbx-ingest-dialog"
      width="720px"
      top="5vh"
      title="录入医学知识文档"
      append-to-body
      destroy-on-close
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
    >
      <p class="kbx-dialog-intro">提交后进入待审核队列，只有复核通过的资料才会构建索引版本。</p>
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <div class="kbx-form-grid">
          <el-form-item label="文档 ID" prop="doc_id">
            <el-input v-model="form.doc_id" placeholder="例如 guideline-headache-2024" />
          </el-form-item>
          <el-form-item label="所属科室" prop="department">
            <el-select v-model="form.department" placeholder="选择专科">
              <el-option v-for="department in departments" :key="department" :label="department" :value="department" />
            </el-select>
          </el-form-item>
        </div>
        <div class="kbx-form-grid">
          <el-form-item label="过期日期（可选）" prop="expires_at">
            <el-date-picker
              v-model="form.expires_at"
              class="kbx-form-control"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="到期后自动退出检索"
            />
          </el-form-item>
          <el-form-item label="变更说明（可选）" prop="change_reason">
            <el-input v-model="form.change_reason" maxlength="512" placeholder="例如：新增 2026 年指南版本" />
          </el-form-item>
        </div>
        <div class="kbx-form-grid">
          <el-form-item label="发布机构" prop="institution">
            <el-input v-model="form.institution" maxlength="256" placeholder="例如 World Health Organization" />
          </el-form-item>
          <el-form-item label="发布日期" prop="published_date">
            <el-date-picker
              v-model="form.published_date"
              class="kbx-form-control"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择发布日期"
            />
          </el-form-item>
        </div>
        <el-form-item label="资料标题" prop="title">
          <el-input v-model="form.title" maxlength="512" placeholder="输入来源页面或指南的完整标题" />
        </el-form-item>
        <el-form-item label="来源地址" prop="url">
          <el-input v-model="form.url" maxlength="2048" placeholder="https://..." />
        </el-form-item>
        <div class="kbx-form-grid">
          <el-form-item label="资料版本" prop="version">
            <el-input v-model="form.version" maxlength="256" placeholder="例如 reviewed-2026-01-15" />
          </el-form-item>
          <el-form-item label="许可信息" prop="license">
            <el-input v-model="form.license" maxlength="512" placeholder="例如 CC BY 4.0" />
          </el-form-item>
        </div>
        <el-form-item label="医学资料正文" prop="text">
          <el-input
            v-model="form.text"
            type="textarea"
            :rows="7"
            maxlength="12000"
            show-word-limit
            resize="vertical"
            placeholder="录入用于检索的医学资料正文"
          />
        </el-form-item>
        <el-form-item prop="reviewApproved">
          <el-checkbox v-model="form.reviewApproved">
            已核对来源、许可与正文，确认提交后仍需独立审核
          </el-checkbox>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button :disabled="submitting" @click="ingestVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleIngest">
          <el-icon v-if="!submitting"><UploadFilled /></el-icon>
          {{ submitting ? '正在提交' : '提交待审核' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
@property --kbx-beam-angle {
  syntax: '<angle>';
  inherits: false;
  initial-value: 0deg;
}

.kbx-page {
  width: min(100%, 1180px);
  min-width: 0;
  margin: 0 auto;
  display: grid;
  gap: 14px;
  overflow-x: clip;
  color: var(--text-primary);
}

.kbx-header,
.kbx-header-actions,
.kbx-panel-heading,
.kbx-version-list-heading,
.kbx-version-row,
.kbx-version-meta,
.kbx-source-item,
.kbx-doc-row,
.kbx-doc-title-row,
.kbx-doc-meta {
  display: flex;
  align-items: center;
}

.kbx-header {
  min-width: 0;
  justify-content: space-between;
  gap: 24px;
  padding: 4px 2px 8px;
}

.kbx-heading,
.kbx-panel-heading > div {
  min-width: 0;
}

.kbx-heading > span,
.kbx-panel-heading > div > span {
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
}

.kbx-heading h1 {
  margin: 5px 0 4px;
  font-size: 24px;
  line-height: 1.3;
  letter-spacing: 0;
}

.kbx-heading p {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.kbx-header-actions {
  flex: 0 0 auto;
  gap: 10px;
}

.kbx-alert {
  border-radius: 8px;
}

.kbx-search-panel,
.kbx-version-panel,
.kbx-source-panel,
.kbx-doc-panel {
  min-width: 0;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  background: var(--surface-elevated);
  box-shadow: var(--shadow-card);
}

.kbx-search-panel {
  --kbx-beam-angle: 0deg;

  padding: 13px;
  border-color: transparent;
  background:
    linear-gradient(var(--surface-elevated), var(--surface-elevated)) padding-box,
    conic-gradient(
      from var(--kbx-beam-angle),
      color-mix(in srgb, var(--primary) 8%, transparent) 0deg,
      color-mix(in srgb, var(--primary) 8%, transparent) 255deg,
      var(--primary) 292deg,
      var(--success) 314deg,
      var(--accent-violet) 336deg,
      color-mix(in srgb, var(--primary) 8%, transparent) 360deg
    ) border-box;
  animation: kbx-search-beam 9s linear infinite;
}

.kbx-version-panel.is-building {
  border-color: transparent;
  background:
    linear-gradient(var(--surface-elevated), var(--surface-elevated)) padding-box,
    linear-gradient(
      120deg,
      var(--primary),
      var(--success),
      var(--accent-violet),
      var(--primary)
    ) border-box;
  background-position: 0 0, 0% 50%;
  background-size: 100% 100%, 240% 240%;
  box-shadow: var(--shadow-md);
  animation: kbx-index-gradient 3.2s ease-in-out infinite;
}

@keyframes kbx-search-beam {
  to {
    --kbx-beam-angle: 360deg;
  }
}

@keyframes kbx-index-gradient {
  0%,
  100% {
    background-position: 0 0, 0% 50%;
  }

  50% {
    background-position: 0 0, 100% 50%;
  }
}

.kbx-search-input :deep(.el-input-group__append) {
  padding: 0;
  overflow: hidden;
  border-color: var(--primary-solid);
  background: var(--primary-solid);
  box-shadow: none;
}

.kbx-search-input :deep(.el-input-group__append .el-button) {
  min-width: 104px;
  height: 38px;
  margin: 0;
  border: 0;
  border-radius: 0;
  color: var(--el-color-white);
  background: var(--primary-solid);
}

.kbx-search-input :deep(.el-input-group__append .el-button:hover) {
  color: var(--el-color-white);
  background: color-mix(in srgb, var(--primary-solid) 96%, var(--el-color-white));
}

.kbx-search-input {
  min-width: 0;
}

.kbx-search-input :deep(.el-input__wrapper) {
  min-width: 0;
  box-shadow: 0 0 0 1px var(--border-strong) inset;
}

.kbx-search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--primary) inset;
}

.kbx-metrics {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.kbx-metric {
  min-width: 0;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 11px;
  padding: 15px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  background: var(--surface-elevated);
  box-shadow: var(--shadow-card);
}

.kbx-metric-icon,
.kbx-source-icon,
.kbx-doc-icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 7px;
}

.kbx-metric-icon {
  width: 40px;
  height: 40px;
}

.kbx-tone-blue,
.kbx-doc-icon {
  color: var(--primary);
  background: var(--primary-soft);
}

.kbx-tone-green,
.kbx-source-icon {
  color: var(--success);
  background: var(--success-soft);
}

.kbx-tone-orange {
  color: var(--warning);
  background: var(--warning-soft);
}

.kbx-tone-purple {
  color: var(--accent-violet);
  background: var(--accent-violet-soft);
}

.kbx-tone-red {
  color: var(--danger);
  background: var(--danger-soft);
}

.kbx-metric-copy {
  min-width: 0;
}

.kbx-metric-copy > span {
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-metric-copy strong {
  display: block;
  margin-top: 3px;
  overflow: hidden;
  font-size: 20px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kbx-metric-copy strong small {
  margin-left: 3px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}

.kbx-metric-copy p {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kbx-metric-loading {
  color: var(--text-muted);
}

.kbx-source-panel,
.kbx-version-panel,
.kbx-doc-panel {
  padding: 18px 20px;
}

.kbx-panel-heading {
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 14px;
}

.kbx-panel-heading h2 {
  margin: 3px 0 0;
  font-size: 15px;
  line-height: 1.3;
  letter-spacing: 0;
}

.kbx-panel-heading > small {
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-version-heading > .el-button {
  flex: 0 0 auto;
  margin: 0;
}

.kbx-version-alert {
  margin-bottom: 12px;
}

.kbx-version-loading {
  padding: 12px 0 4px;
}

.kbx-active-version {
  min-width: 0;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  margin: 0 -20px;
  padding: 13px 20px;
  border-top: 1px solid var(--primary-subtle);
  border-bottom: 1px solid var(--primary-subtle);
  background: var(--primary-soft);
}

.kbx-active-version-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  color: var(--success);
  background: var(--success-soft);
}

.kbx-active-version-copy {
  min-width: 0;
}

.kbx-active-version-copy small,
.kbx-active-version-copy code,
.kbx-active-version-copy p {
  display: block;
}

.kbx-active-version-copy small {
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-active-version-copy code {
  margin-top: 2px;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kbx-active-version-copy p {
  margin: 3px 0 0;
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-version-list-heading {
  justify-content: space-between;
  gap: 12px;
  padding: 14px 0 8px;
}

.kbx-version-list-heading strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.kbx-version-list-heading small {
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-version-list {
  margin: 0 -20px -18px;
  border-top: 1px solid var(--border-subtle);
}

.kbx-version-row {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(180px, 1.4fr) minmax(180px, 1fr) 88px;
  gap: 14px;
  min-height: 58px;
  padding: 11px 20px;
  border-bottom: 1px solid var(--border-subtle);
}

.kbx-version-row:last-child {
  border-bottom: 0;
}

.kbx-version-identity {
  min-width: 0;
}

.kbx-version-identity code,
.kbx-version-identity > span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kbx-version-identity code {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.kbx-version-identity > span {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-version-identity .el-icon {
  margin-right: 4px;
  vertical-align: -1px;
}

.kbx-version-meta {
  flex-wrap: wrap;
  gap: 16px;
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-version-meta strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.kbx-version-row > .el-tag,
.kbx-version-row > .el-button {
  width: 88px;
  justify-self: end;
  margin: 0;
}

.kbx-source-skeleton {
  padding: 8px 0;
}

.kbx-source-list {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  border-top: 1px solid var(--border-subtle);
  border-bottom: 1px solid var(--border-subtle);
}

.kbx-source-item {
  min-width: 0;
  gap: 10px;
  padding: 14px 12px;
  border-right: 1px solid var(--border-subtle);
}

.kbx-source-item:nth-child(4n) {
  border-right: 0;
}

.kbx-source-icon {
  width: 32px;
  height: 32px;
}

.kbx-source-item > div {
  min-width: 0;
}

.kbx-source-item strong,
.kbx-source-item small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kbx-source-item strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.kbx-source-item small {
  margin-top: 3px;
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-doc-heading {
  padding-bottom: 13px;
  border-bottom: 1px solid var(--border-subtle);
}

.kbx-doc-loading {
  margin: 0 -20px -18px;
}

.kbx-skeleton-row {
  padding: 17px 20px;
  border-bottom: 1px solid var(--border-subtle);
}

.kbx-skeleton-row:last-child {
  border-bottom: 0;
}

.kbx-doc-list {
  margin: 0 -20px -18px;
}

.kbx-doc-row {
  min-width: 0;
  align-items: flex-start;
  gap: 13px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--border-subtle);
  transition: background 0.16s ease;
}

.kbx-doc-row:last-child {
  border-bottom: 0;
}

.kbx-doc-row:hover {
  background: var(--primary-soft);
}

.kbx-doc-icon {
  width: 34px;
  height: 34px;
  margin-top: 2px;
}

.kbx-doc-content {
  min-width: 0;
  flex: 1;
}

.kbx-doc-title-row {
  min-width: 0;
  gap: 9px;
}

.kbx-doc-title-row h3 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kbx-doc-title-row code {
  max-width: 240px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kbx-doc-content > p {
  margin: 4px 0 8px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kbx-doc-meta {
  flex-wrap: wrap;
  gap: 10px;
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-doc-meta > span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.kbx-delete-button {
  flex: 0 0 auto;
  margin-top: 2px;
}

.kbx-doc-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
}

.kbx-doc-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.kbx-dialog-intro {
  margin: -4px 0 18px;
  padding: 10px 12px;
  border-left: 3px solid var(--primary);
  border-radius: 4px;
  color: var(--text-secondary);
  background: var(--primary-soft);
  font-size: 13px;
  line-height: 1.6;
}

.kbx-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.kbx-form-grid :deep(.el-select) {
  width: 100%;
}

.kbx-form-control {
  width: 100%;
}

:global(.kbx-ingest-dialog) {
  max-width: calc(100vw - 24px);
}

:global(.kbx-ingest-dialog .el-dialog__body) {
  max-height: calc(90vh - 132px);
  overflow-y: auto;
}

@media (prefers-reduced-motion: reduce) {
  .kbx-search-panel,
  .kbx-version-panel.is-building {
    animation: none;
  }
}

@media (max-width: 1180px) {
  .kbx-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .kbx-source-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .kbx-source-item:nth-child(4n) {
    border-right: 1px solid var(--border-subtle);
  }

  .kbx-source-item:nth-child(2n) {
    border-right: 0;
  }
}

@media (max-width: 760px) {
  .kbx-header {
    align-items: flex-start;
  }

  .kbx-header-actions > .el-button:last-child {
    padding-inline: 10px;
  }

  .kbx-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .kbx-search-input :deep(.el-input-group__append .el-button) {
    min-width: 72px;
    padding-inline: 12px;
    font-size: 12px;
  }

  .kbx-search-input :deep(.el-input-group__append .el-icon) {
    margin-right: 6px;
    font-size: 15px;
  }

  .kbx-form-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .kbx-version-row {
    grid-template-columns: minmax(0, 1fr) 88px;
  }

  .kbx-version-meta {
    grid-column: 1;
    grid-row: 2;
  }

  .kbx-version-row > .el-tag,
  .kbx-version-row > .el-button {
    grid-column: 2;
    grid-row: 1 / span 2;
  }
}

@media (max-width: 520px) {
  .kbx-header {
    flex-direction: column;
    gap: 12px;
  }

  .kbx-header-actions {
    width: 100%;
  }

  .kbx-header-actions > .el-button:last-child {
    flex: 1;
  }

  .kbx-version-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .kbx-version-heading > .el-button {
    width: 100%;
  }

  .kbx-active-version {
    grid-template-columns: 36px minmax(0, 1fr);
  }

  .kbx-active-version > .el-tag {
    grid-column: 2;
    justify-self: start;
  }

  .kbx-active-version-copy code {
    overflow-wrap: anywhere;
    white-space: normal;
  }

  .kbx-version-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
    align-items: stretch;
  }

  .kbx-version-meta,
  .kbx-version-row > .el-tag,
  .kbx-version-row > .el-button {
    grid-column: 1;
    grid-row: auto;
  }

  .kbx-version-row > .el-tag {
    width: auto;
    justify-self: start;
  }

  .kbx-version-row > .el-button {
    width: 100%;
    justify-self: stretch;
  }

  .kbx-metrics,
  .kbx-source-list {
    grid-template-columns: 1fr;
  }

  .kbx-source-item,
  .kbx-source-item:nth-child(4n),
  .kbx-source-item:nth-child(2n) {
    border-right: 0;
  }

  .kbx-source-item:not(:last-child) {
    border-bottom: 1px solid var(--border-subtle);
  }

  .kbx-doc-title-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 2px;
  }

  .kbx-doc-title-row code {
    max-width: 100%;
  }
}

/* Borderless deep-space workspace surfaces. */
.kbx-page {
  gap: 16px;
}

.kbx-search-panel,
.kbx-version-panel,
.kbx-source-panel,
.kbx-doc-panel {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  border: 0;
  background:
    linear-gradient(128deg, rgba(87, 188, 255, 0.07), transparent 38%, rgba(184, 121, 255, 0.055)),
    var(--glass-surface, rgba(12, 28, 55, 0.66));
  box-shadow: inset 0 1px 0 rgba(215, 242, 255, 0.09), 0 14px 34px rgba(0, 3, 14, 0.18);
  backdrop-filter: blur(18px) saturate(132%);
}

.kbx-search-panel::before,
.kbx-version-panel::before,
.kbx-source-panel::before,
.kbx-doc-panel::before,
.kbx-metrics::before {
  position: absolute;
  top: 0;
  right: 4%;
  left: 4%;
  z-index: 0;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(91, 205, 255, 0.44), rgba(186, 125, 255, 0.34), transparent);
  pointer-events: none;
}

.kbx-search-panel {
  animation: none;
}

.kbx-version-panel.is-building {
  border: 0;
  background:
    linear-gradient(120deg, rgba(91, 200, 255, 0.13), transparent 42%, rgba(184, 121, 255, 0.1)),
    var(--glass-surface, rgba(12, 28, 55, 0.66));
  box-shadow: inset 0 1px 0 rgba(220, 245, 255, 0.13), 0 0 28px rgba(84, 181, 246, 0.16);
  animation: kbx-index-glow 2.8s ease-in-out infinite;
}

.kbx-metrics {
  position: relative;
  isolation: isolate;
  gap: 0;
  padding: 7px;
  overflow: hidden;
  border-radius: var(--radius-lg);
  background:
    linear-gradient(105deg, rgba(87, 188, 255, 0.07), transparent 45%, rgba(246, 191, 104, 0.045)),
    var(--glass-surface, rgba(12, 28, 55, 0.66));
  box-shadow: inset 0 1px 0 rgba(215, 242, 255, 0.08), 0 12px 30px rgba(0, 3, 14, 0.16);
  backdrop-filter: blur(18px) saturate(132%);
}

.kbx-metric {
  position: relative;
  z-index: 1;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  box-shadow: none;
  transition: background 0.18s ease, transform 0.18s ease;
}

.kbx-metric:hover {
  background: rgba(100, 181, 241, 0.07);
  transform: translateY(-1px);
}

.kbx-metric-icon,
.kbx-source-icon,
.kbx-doc-icon,
.kbx-active-version-icon {
  box-shadow: inset 0 1px 0 rgba(224, 246, 255, 0.14), 0 0 15px color-mix(in srgb, currentColor 14%, transparent);
}

.kbx-active-version {
  border: 0;
  background: linear-gradient(90deg, var(--primary-soft), rgba(182, 123, 255, 0.05), transparent);
}

.kbx-version-list,
.kbx-source-list,
.kbx-doc-heading {
  border: 0;
}

.kbx-version-row,
.kbx-doc-row {
  position: relative;
  border: 0;
}

.kbx-version-row:not(:last-child)::after,
.kbx-doc-row:not(:last-child)::after {
  position: absolute;
  right: 20px;
  bottom: 0;
  left: 20px;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, var(--border-subtle), rgba(185, 127, 255, 0.11), transparent);
}

.kbx-source-item,
.kbx-source-item:nth-child(4n),
.kbx-source-item:nth-child(2n),
.kbx-source-item:not(:last-child) {
  border: 0;
}

.kbx-source-item {
  border-radius: var(--radius-md);
  transition: background 0.16s ease;
}

.kbx-source-item:hover,
.kbx-doc-row:hover,
.kbx-version-row:hover {
  background: rgba(101, 184, 244, 0.07);
}

.kbx-search-input :deep(.el-input__wrapper) {
  background: rgba(5, 16, 35, 0.56);
  box-shadow: inset 0 0 0 1px rgba(132, 196, 248, 0.16) !important;
}

.kbx-search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: inset 0 0 0 1px var(--primary), 0 0 0 3px var(--focus-ring) !important;
}

.kbx-page :deep(.el-tag) {
  background: color-mix(in srgb, currentColor 11%, transparent);
  border-color: color-mix(in srgb, currentColor 30%, transparent);
}

@keyframes kbx-index-glow {
  0%,
  100% {
    filter: brightness(1);
  }

  50% {
    filter: brightness(1.08);
  }
}

@media (max-width: 760px) {
  .kbx-metrics {
    gap: 2px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .kbx-version-panel.is-building,
  .kbx-metric {
    animation: none;
    transition: none;
  }
}

@supports not ((backdrop-filter: blur(1px))) {
  .kbx-search-panel,
  .kbx-version-panel,
  .kbx-source-panel,
  .kbx-doc-panel,
  .kbx-metrics {
    background: var(--surface-base);
  }
}

.kbx-diff-dialog :deep(.el-dialog__body) {
  padding-top: 8px;
}

.kbx-diff-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto;
  align-items: end;
  gap: 10px;
  margin-bottom: 16px;
}

.kbx-diff-toolbar > div {
  min-width: 0;
}

.kbx-diff-toolbar > div > span {
  display: block;
  margin-bottom: 5px;
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-diff-arrow {
  margin-bottom: 9px;
  color: var(--primary);
}

.kbx-diff-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;
}

.kbx-diff-summary > div {
  display: grid;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
}

.kbx-diff-summary strong {
  color: var(--text-primary);
  font-size: 20px;
}

.kbx-diff-summary span {
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-diff-groups {
  display: grid;
  gap: 8px;
}

.kbx-diff-group {
  padding: 11px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--surface-muted) 72%, transparent);
}

.kbx-diff-group-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.kbx-diff-group-heading strong {
  color: var(--text-secondary);
  font-size: 13px;
}

.kbx-diff-id-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 116px;
  overflow: auto;
}

.kbx-diff-id-list code {
  max-width: 100%;
  padding: 3px 6px;
  overflow-wrap: anywhere;
  border: 1px solid var(--border-default);
  border-radius: 4px;
  background: var(--surface-elevated);
  color: var(--text-secondary);
  font-size: 11px;
}

.kbx-diff-none,
.kbx-diff-note {
  color: var(--text-muted);
  font-size: 12px;
}

.kbx-diff-note {
  margin: 12px 0 0;
}

.kbx-diff-loading {
  min-height: 180px;
}

@media (max-width: 640px) {
  .kbx-diff-toolbar {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }

  .kbx-diff-arrow {
    display: none;
  }

  .kbx-diff-toolbar > .el-button {
    grid-column: 1 / -1;
    width: 100%;
  }

  .kbx-diff-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
