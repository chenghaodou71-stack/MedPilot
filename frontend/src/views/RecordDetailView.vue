<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  Calendar,
  ChatDotRound,
  Clock,
  DataAnalysis,
  Document,
  FirstAidKit,
  InfoFilled,
  Reading,
  WarningFilled,
} from '@element-plus/icons-vue'
import client from '../api/client'
import { formatTriageSupportScore, normalizeTriageFactors } from '../lib/triageExplanation'

const route = useRoute()
const router = useRouter()
const record = ref(null)
const loading = ref(true)
const loadError = ref('')

const citationList = computed(() => {
  const value = record.value?.citations
  if (Array.isArray(value)) {
    return value.map((item) => (typeof item === 'string' ? { source: item } : item))
  }
  if (!value) return []
  return String(value)
    .split(/[,，]/)
    .map((item) => ({ source: item.trim() }))
    .filter((item) => item.source)
})

const conversationEntries = computed(() => {
  const value = record.value?.conversationHistory
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    if (Array.isArray(parsed)) {
      return parsed.map((item) => ({
        role: item.role || 'user',
        text: item.text || item.content || JSON.stringify(item),
      }))
    }
    if (parsed.text) return [{ role: 'user', text: parsed.text }]
  } catch {
    return [{ role: 'system', text: value }]
  }
  return []
})

const supportScore = computed(() => formatTriageSupportScore(
  record.value?.supportScore ?? record.value?.confidence,
))
const triageFactors = computed(() => normalizeTriageFactors(record.value?.triageFactors))

const supportScoreLabel = computed(() => (
  record.value?.matchedRule ? '规则支持分' : '检索支持度'
))

async function fetchDetail() {
  loading.value = true
  loadError.value = ''
  try {
    const response = await client.get(`/records/${route.params.id}`)
    record.value = response.data?.data || null
  } catch {
    loadError.value = '问诊记录不存在，或当前账号无权查看。'
  } finally {
    loading.value = false
  }
}

function riskType(level) {
  return { 高: 'danger', 中: 'warning', 低: 'success' }[level] || 'info'
}

function formatDate(value) {
  if (!value) return '--'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '--' : date.toLocaleString('zh-CN')
}

function formatScore(score) {
  const value = Number(score)
  if (!Number.isFinite(value)) return ''
  return `${Math.round((value <= 1 ? value * 100 : value))}%`
}

onMounted(fetchDetail)
</script>

<template>
  <div class="record-detail-page">
    <header class="record-detail-heading">
      <div>
        <span>问诊记录</span>
        <h1>问诊详情</h1>
        <p v-if="record">会话编号：{{ record.sessionId }}</p>
      </div>
      <el-button @click="router.push('/records')">
        <el-icon><ArrowLeft /></el-icon>
        返回记录
      </el-button>
    </header>

    <section v-if="loading" class="record-detail-panel detail-loading">
      <el-skeleton :rows="8" animated />
    </section>

    <section v-else-if="loadError" class="record-detail-panel detail-error">
      <el-result icon="warning" title="无法打开记录" :sub-title="loadError">
        <template #extra><el-button type="primary" @click="router.push('/records')">返回列表</el-button></template>
      </el-result>
    </section>

    <template v-else-if="record">
      <el-alert
        v-if="record.riskLevel === '高'"
        class="record-high-risk"
        type="error"
        :closable="false"
        show-icon
      >
        <template #title>本次问诊识别到高风险信号</template>
        <p>{{ record.urgency || '建议优先线下就医，情况紧急时请拨打 120。' }}</p>
      </el-alert>

      <div class="record-detail-layout">
        <main class="record-detail-main">
          <section class="record-detail-panel detail-result-panel">
            <div class="detail-section-heading">
              <div>
                <span>分诊结果</span>
                <h2>辅助分诊概览</h2>
              </div>
              <el-tag :type="riskType(record.riskLevel)" effect="dark" size="large">
                {{ record.riskLevel || '未知' }}风险
              </el-tag>
            </div>

            <div class="detail-metrics">
              <div class="detail-metric">
                <span class="detail-metric-icon blue"><el-icon><FirstAidKit /></el-icon></span>
                <div><small>推荐科室</small><strong>{{ record.department || '--' }}</strong></div>
              </div>
              <div class="detail-metric">
                <span class="detail-metric-icon orange"><el-icon><WarningFilled /></el-icon></span>
                <div><small>风险等级</small><strong>{{ record.riskLevel || '未知' }}</strong></div>
              </div>
              <div class="detail-metric">
                <span class="detail-metric-icon green"><el-icon><DataAnalysis /></el-icon></span>
                <div><small>{{ supportScoreLabel }}</small><strong>{{ supportScore || '--' }}</strong></div>
              </div>
              <div class="detail-metric">
                <span class="detail-metric-icon purple"><el-icon><Clock /></el-icon></span>
                <div><small>建议就医时效</small><strong>{{ record.urgency || '--' }}</strong></div>
              </div>
            </div>

            <div class="detail-block">
              <h3>症状摘要</h3>
              <p>{{ record.symptoms || '未生成结构化症状摘要' }}</p>
              <el-tag v-if="record.matchedRule" type="warning" effect="plain">
                命中规则：{{ record.matchedRule }}
              </el-tag>
              <div v-if="record.explanation || triageFactors.length" class="record-explanation">
                <strong>判定依据</strong>
                <p>{{ record.explanation || '以下依据来自本次安全规则或知识检索。' }}</p>
                <ul v-if="triageFactors.length">
                  <li v-for="factor in triageFactors" :key="`${factor.kind}-${factor.reference}-${factor.label}`">
                    <span>{{ factor.kind === 'rule' ? '安全规则' : '知识证据' }}</span>
                    <b>{{ factor.label }}</b>
                    <small v-if="factor.support">支持 {{ factor.support }}</small>
                  </li>
                </ul>
              </div>
            </div>

            <div class="detail-block answer-block">
              <h3>系统建议</h3>
              <p>{{ record.answer || '该记录暂无可展示的自然语言回答。' }}</p>
            </div>

            <div class="detail-safety">
              <el-icon :size="19"><InfoFilled /></el-icon>
              <div>
                <strong>医疗安全声明</strong>
                <p>本系统提供的是辅助分诊建议，不替代执业医生的诊断与治疗。</p>
              </div>
            </div>
          </section>

          <section v-if="conversationEntries.length" class="record-detail-panel conversation-history-panel">
            <div class="detail-section-heading compact-heading">
              <div>
                <span>会话内容</span>
                <h2>问诊信息</h2>
              </div>
              <el-icon :size="20"><ChatDotRound /></el-icon>
            </div>
            <div class="history-message-list">
              <div v-for="(message, index) in conversationEntries" :key="index" :class="['history-message', message.role]">
                <span>{{ message.role === 'user' ? '您' : 'AI' }}</span>
                <p>{{ message.text }}</p>
              </div>
            </div>
          </section>
        </main>

        <aside class="record-detail-side">
          <section class="record-detail-panel record-meta-panel">
            <div class="detail-side-title">
              <span class="detail-side-icon"><el-icon><Document /></el-icon></span>
              <div><strong>记录信息</strong><small>问诊档案</small></div>
            </div>
            <dl>
              <div><dt><el-icon><Calendar /></el-icon>问诊时间</dt><dd>{{ formatDate(record.createdAt) }}</dd></div>
              <div><dt><el-icon><Document /></el-icon>记录编号</dt><dd>#{{ record.id }}</dd></div>
              <div><dt><el-icon><ChatDotRound /></el-icon>会话编号</dt><dd>{{ record.sessionId }}</dd></div>
              <div v-if="record.traceId"><dt><el-icon><DataAnalysis /></el-icon>Trace ID</dt><dd>{{ record.traceId }}</dd></div>
            </dl>
          </section>

          <section class="record-detail-panel citation-panel">
            <div class="detail-side-title">
              <span class="detail-side-icon green"><el-icon><Reading /></el-icon></span>
              <div><strong>依据与参考</strong><small>{{ citationList.length }} 条来源</small></div>
            </div>
            <div v-if="citationList.length" class="citation-list">
              <article
                v-for="(citation, index) in citationList"
                :key="citation.citation_id || `${citation.source}-${index}`"
              >
                <span>{{ String(index + 1).padStart(2, '0') }}</span>
                <div>
                  <div class="citation-heading">
                    <strong>{{ citation.source || '医学知识库资料' }}</strong>
                    <el-tag v-if="formatScore(citation.score)" type="success" effect="plain" size="small">
                      检索支持度 {{ formatScore(citation.score) }}
                    </el-tag>
                  </div>
                  <small v-if="citation.department">{{ citation.department }}</small>
                  <blockquote v-if="citation.quote">{{ citation.quote }}</blockquote>
                  <code v-if="citation.chunk_id || citation.index_version">
                    {{ citation.chunk_id || '--' }} · {{ citation.index_version || 'legacy' }}
                  </code>
                </div>
              </article>
            </div>
            <div v-else class="citation-empty">
              <el-icon :size="26"><Reading /></el-icon>
              <p>该记录没有保存可展示的引用来源。</p>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.record-detail-page {
  width: min(100%, 1260px);
  min-width: 0;
  margin: 0 auto;
  color: var(--text-primary);
}

.record-detail-heading,
.detail-section-heading,
.detail-metric,
.detail-safety,
.detail-side-title,
.record-meta-panel dt {
  display: flex;
  align-items: center;
}

.record-detail-heading {
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 16px;
}

.record-detail-heading > div > span,
.detail-section-heading > div > span {
  color: var(--primary);
  font-size: 11px;
  font-weight: 700;
}

.record-detail-heading h1 {
  margin: 3px 0;
  font-size: 22px;
  letter-spacing: 0;
}

.record-detail-heading p {
  margin: 0;
  color: var(--text-muted);
  font-size: 10px;
}

.record-detail-panel {
  min-width: 0;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  background: var(--glass-surface);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(125%);
  -webkit-backdrop-filter: blur(18px) saturate(125%);
}

.detail-loading,
.detail-error {
  padding: 28px;
}

.record-high-risk {
  margin-bottom: 16px;
}

.record-high-risk p {
  margin: 4px 0 0;
  font-size: 11px;
}

.record-detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(300px, 0.65fr);
  gap: 16px;
  align-items: start;
}

.record-detail-main,
.record-detail-side {
  display: grid;
  min-width: 0;
  gap: 16px;
}

.detail-result-panel,
.conversation-history-panel {
  padding: 24px 26px;
}

.detail-section-heading {
  justify-content: space-between;
  gap: 14px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--border-subtle);
}

.detail-section-heading h2 {
  margin: 3px 0 0;
  font-size: 16px;
  letter-spacing: 0;
}

.detail-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-bottom: 1px solid var(--border-subtle);
}

.detail-metric {
  min-width: 0;
  gap: 10px;
  padding: 17px 12px 17px 0;
}

.detail-metric:nth-child(even) {
  padding-left: 16px;
  border-left: 1px solid var(--border-subtle);
}

.detail-metric-icon,
.detail-side-icon {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 7px;
}

.detail-metric-icon {
  width: 36px;
  height: 36px;
}

.detail-metric-icon.blue,
.detail-side-icon {
  background: var(--primary-soft);
  color: var(--primary);
}

.detail-metric-icon.orange {
  background: var(--warning-soft);
  color: var(--warning);
}

.detail-metric-icon.green,
.detail-side-icon.green {
  background: var(--success-soft);
  color: var(--success);
}

.detail-metric-icon.purple {
  background: var(--accent-violet-soft);
  color: var(--accent-violet);
}

.detail-metric small,
.detail-metric strong {
  display: block;
}

.detail-metric small {
  color: var(--text-muted);
  font-size: 10px;
}

.detail-metric strong {
  margin-top: 4px;
  color: var(--text-primary);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.detail-block {
  padding: 19px 0;
  border-bottom: 1px solid var(--border-subtle);
}

.detail-block h3 {
  margin: 0 0 10px;
  font-size: 13px;
}

.detail-block p {
  margin: 0 0 10px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.record-explanation {
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md, 6px);
  background: var(--surface-muted);
}

.record-explanation > strong,
.record-explanation > p {
  display: block;
  margin: 0;
}

.record-explanation > p {
  margin-top: 5px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.65;
}

.record-explanation ul {
  display: grid;
  gap: 8px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.record-explanation li {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.record-explanation li > span,
.record-explanation li > small {
  color: var(--text-muted);
  font-size: 12px;
}

.record-explanation li > b {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--text-primary);
  font-size: 12px;
}

.detail-safety {
  align-items: flex-start;
  gap: 9px;
  margin-top: 18px;
  padding: 13px;
  border: 1px solid var(--primary-subtle);
  border-radius: 7px;
  background: var(--info-soft);
  color: var(--primary);
}

.detail-safety strong {
  font-size: 11px;
}

.detail-safety p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 10px;
  line-height: 1.6;
}

.compact-heading {
  padding-bottom: 14px;
  color: var(--primary);
}

.history-message-list {
  display: grid;
  gap: 12px;
  padding-top: 16px;
}

.history-message {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.history-message > span {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 9px;
  font-weight: 700;
}

.history-message p {
  margin: 0;
  padding: 9px 11px;
  border-radius: 6px;
  border: 1px solid var(--border-subtle);
  background: var(--surface-muted);
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.history-message.user p {
  border-color: var(--primary-subtle);
  background: var(--primary-soft);
}

.history-message.system > span {
  background: var(--accent-violet-soft);
  color: var(--accent-violet);
}

.record-meta-panel,
.citation-panel {
  overflow: hidden;
}

.detail-side-title {
  gap: 10px;
  padding: 18px;
  border-bottom: 1px solid var(--border-subtle);
}

.detail-side-icon {
  width: 36px;
  height: 36px;
}

.detail-side-title strong,
.detail-side-title small {
  display: block;
}

.detail-side-title strong {
  font-size: 12px;
}

.detail-side-title small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 9px;
}

.record-meta-panel dl {
  margin: 0;
  padding: 8px 18px;
}

.record-meta-panel dl > div {
  padding: 12px 0;
  border-bottom: 1px solid var(--border-subtle);
}

.record-meta-panel dl > div:last-child {
  border-bottom: 0;
}

.record-meta-panel dt {
  gap: 6px;
  color: var(--text-muted);
  font-size: 9px;
}

.record-meta-panel dd {
  margin: 5px 0 0;
  color: var(--text-secondary);
  font-size: 10px;
  overflow-wrap: anywhere;
}

.citation-list {
  display: grid;
}

.citation-list > article {
  display: grid;
  grid-template-columns: 25px minmax(0, 1fr);
  gap: 8px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border-subtle);
}

.citation-list > article:last-child {
  border-bottom: 0;
}

.citation-list span {
  color: var(--primary);
  font-size: 9px;
  font-weight: 700;
}

.citation-list article > div {
  min-width: 0;
}

.citation-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.citation-list strong {
  font-size: 10px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.citation-list small,
.citation-list code {
  display: block;
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 9px;
  overflow-wrap: anywhere;
}

.citation-list blockquote {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-left: 2px solid var(--primary);
  background: var(--surface-muted);
  color: var(--text-secondary);
  font-size: 10px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.citation-empty {
  display: grid;
  min-height: 150px;
  padding: 24px;
  place-items: center;
  align-content: center;
  color: var(--text-subtle);
  text-align: center;
}

.citation-empty p {
  margin: 8px 0 0;
  font-size: 10px;
  line-height: 1.6;
}

@media (max-width: 1050px) {
  .record-detail-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .record-detail-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .record-detail-heading > .el-button {
    align-self: flex-start;
  }

  .detail-result-panel,
  .conversation-history-panel {
    padding: 20px 16px;
  }

  .detail-metrics {
    grid-template-columns: 1fr;
  }

  .detail-metric,
  .detail-metric:nth-child(even) {
    padding: 13px 0;
    border-left: 0;
    border-bottom: 1px solid var(--border-subtle);
  }

  .detail-section-heading,
  .citation-heading {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .detail-side-title,
  .citation-list > article,
  .record-meta-panel dl {
    padding-inline: 14px;
  }
}
</style>
