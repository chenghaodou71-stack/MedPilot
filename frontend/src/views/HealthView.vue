<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  ChatDotRound,
  Clock,
  Collection,
  Delete,
  FirstAidKit,
  InfoFilled,
  Reading,
  Search,
  WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import {
  SETTINGS_KEY,
  clearHealthHistory as removeHealthHistory,
  healthHistoryKey,
} from '../lib/privacy'

const router = useRouter()
const auth = useAuthStore()
const historyKey = computed(() => healthHistoryKey(auth.username))

const keyword = ref('')
const history = ref([])
const activeGuide = ref(null)

const hotTopics = ['头痛', '发热', '咳嗽', '高血压', '睡眠', '合理用药', '健康饮食']

const guideCatalog = [
  {
    keywords: ['症状记录'],
    title: '症状记录模板',
    summary: '把不适整理成时间、变化和伴随表现，问诊时更容易快速说明重点。',
    points: ['记录首次出现时间、持续多久，以及症状是变轻还是加重', '补充发生部位、诱因和伴随表现，例如发热、胸痛或呼吸困难', '就诊前整理正在使用的药物、过敏史和既往相关检查'],
    urgent: '若出现明显呼吸困难、突发剧烈胸痛、意识异常或大量出血，请立即拨打 120。',
  },
  {
    keywords: ['头痛', '偏头痛', '头疼'],
    title: '头痛的日常观察要点',
    summary: '头痛原因很多，单凭一次症状不能判断病因。先记录规律，有助于后续问诊。',
    points: ['记录开始时间、持续时长、疼痛位置和诱因', '注意是否伴随发热、视物异常、肢体无力或意识改变', '避免自行长期或超量服用止痛药'],
    urgent: '若为突发且剧烈的头痛，或伴意识障碍、抽搐、肢体无力，请立即拨打 120。',
  },
  {
    keywords: ['发热', '发烧', '体温'],
    title: '发热期间的观察与照护',
    summary: '体温升高是多种情况的共同表现，应结合持续时间和伴随症状综合评估。',
    points: ['使用体温计定时测量并记录，不以手感代替测量', '适量饮水、充分休息，避免酒精擦浴', '儿童、孕妇、老年人或慢病患者用药前先咨询专业人员'],
    urgent: '若出现呼吸困难、意识异常、持续高热或明显脱水，请尽快就医。',
  },
  {
    keywords: ['咳嗽', '呼吸', '咽痛'],
    title: '咳嗽症状记录建议',
    summary: '咳嗽可能与感染、过敏或环境刺激等因素有关，持续时间和伴随表现很重要。',
    points: ['记录干咳或有痰、痰液颜色及发作时段', '避免烟草和刺激性气味，保持适当通风', '不要自行使用处方抗生素'],
    urgent: '若出现明显呼吸困难、口唇发紫、胸痛或咯血，请立即就医。',
  },
  {
    keywords: ['高血压', '血压', '慢病'],
    title: '家庭血压管理基础',
    summary: '单次读数不能代替诊断，规范测量和连续记录更有参考价值。',
    points: ['测量前安静休息 5 分钟，手臂与心脏保持同高', '在相近时段测量并记录日期、时间和读数', '已在用药者不要根据一次读数自行停药或加量'],
    urgent: '若血压显著升高并伴胸痛、呼吸困难、神经功能异常，请立即就医。',
  },
  {
    keywords: ['用药', '药物', '吃药', '合理用药'],
    title: '安全用药基本原则',
    summary: '药物选择需要结合适应证、禁忌证和既往用药，不建议仅凭网络信息自行调整。',
    points: ['按医嘱或说明书规定的剂量和时间使用', '就诊时主动说明过敏史、基础疾病和正在使用的药物', '处方药、抗菌药及儿童用药应由专业人员指导'],
    urgent: '服药后出现呼吸困难、面唇肿胀、意识异常等严重反应，请立即拨打 120。',
  },
  {
    keywords: ['睡眠', '失眠', '熬夜'],
    title: '睡眠习惯改善建议',
    summary: '短期睡眠波动较常见，持续记录作息和白天状态有助于识别影响因素。',
    points: ['尽量固定入睡和起床时间', '睡前减少咖啡因、酒精和长时间屏幕刺激', '白天适量活动，避免临睡前剧烈运动'],
    urgent: '若睡眠问题持续数周并明显影响日间功能，或伴持续情绪低落，请寻求专业评估。',
  },
  {
    keywords: ['饮食', '营养', '健康饮食'],
    title: '日常均衡饮食提示',
    summary: '健康饮食应以长期均衡和食物多样为核心，不建议依赖单一食物或极端节食。',
    points: ['保证谷薯类、蔬果、蛋白质来源和奶豆类合理搭配', '减少高盐、高糖和高油加工食品', '慢病、孕期或特殊人群应接受个体化营养建议'],
    urgent: '若进食后出现严重过敏反应、持续呕吐或明显脱水，请立即就医。',
  },
]

const defaultGuide = {
  title: '健康问题记录建议',
  summary: '当前关键词没有匹配到本地专题。你可以先整理症状信息，再进入智能问诊获得更有针对性的辅助建议。',
  points: ['记录主要不适、开始时间和变化趋势', '补充年龄段、既往病史、过敏史及正在使用的药物', '不要依据网络搜索结果自行确诊或调整处方药'],
  urgent: '若出现呼吸困难、意识异常、剧烈胸痛、大量出血等紧急情况，请立即拨打 120。',
}

const discoveries = [
  {
    title: '症状记录',
    description: '整理发生时间、持续时长和伴随表现，为问诊提供清晰信息。',
    icon: Reading,
    tone: 'blue',
    action: '症状记录',
  },
  {
    title: '合理用药',
    description: '了解通用用药安全原则，避免自行停药、加量或混用处方药。',
    icon: FirstAidKit,
    tone: 'green',
    action: '合理用药',
  },
  {
    title: '慢病管理',
    description: '从规范测量和连续记录开始，建立可供专业人员参考的健康日志。',
    icon: Collection,
    tone: 'violet',
    action: '高血压',
  },
]

const resultKeyword = computed(() => keyword.value.trim())

function loadHistory() {
  if (!shouldSaveHistory()) {
    removeHealthHistory(auth.username)
    history.value = []
    return
  }

  try {
    const stored = JSON.parse(localStorage.getItem(historyKey.value) || '[]')
    history.value = Array.isArray(stored)
      ? stored.filter((item) => typeof item?.keyword === 'string').slice(0, 6)
      : []
  } catch {
    history.value = []
  }
}

function shouldSaveHistory() {
  try {
    const stored = JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null')
    return stored?.privacy?.saveHealthHistory !== false
  } catch {
    return true
  }
}

function persistHistory() {
  localStorage.setItem(historyKey.value, JSON.stringify(history.value))
}

function resolveGuide(query) {
  const normalized = query.toLowerCase()
  return guideCatalog.find((guide) => guide.keywords.some((item) => normalized.includes(item))) || defaultGuide
}

function runSearch(value = keyword.value) {
  const query = String(value || '').trim()
  if (!query) {
    ElMessage.warning('请输入想了解的健康问题')
    return
  }

  keyword.value = query
  activeGuide.value = resolveGuide(query)
  if (!shouldSaveHistory()) return

  history.value = [
    { keyword: query, searchedAt: Date.now() },
    ...history.value.filter((item) => item.keyword !== query),
  ].slice(0, 6)
  persistHistory()
}

async function selectDiscovery(item) {
  runSearch(item.action)
  await nextTick()
  document.querySelector('.health-guide')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function clearHistory() {
  history.value = []
  removeHealthHistory(auth.username)
  ElMessage.success('搜索历史已清空')
}

function formatHistoryTime(timestamp) {
  if (!timestamp) return '最近搜索'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(timestamp))
}

function startConsult() {
  router.push({ path: '/consult', query: resultKeyword.value ? { symptom: resultKeyword.value } : {} })
}

onMounted(loadHistory)
</script>

<template>
  <div class="health-page">
    <header class="health-page__header">
      <div>
        <p class="health-page__eyebrow">PERSONAL HEALTH HUB</p>
        <h1>健康检索</h1>
        <p class="health-page__subtitle">查找本地整理的日常健康提示，记录需要进一步咨询的问题。</p>
      </div>
      <el-button type="primary" :icon="ChatDotRound" @click="startConsult">发起智能问诊</el-button>
    </header>

    <section class="health-search" aria-labelledby="health-search-title">
      <div class="health-search__title">
        <div class="health-section-icon health-section-icon--blue"><el-icon><Search /></el-icon></div>
        <div>
          <h2 id="health-search-title">健康检索</h2>
          <p>输入症状、生活方式或用药相关关键词</p>
        </div>
      </div>

      <el-input
        v-model="keyword"
        size="large"
        clearable
        placeholder="例如：头痛、血压测量、合理用药"
        aria-label="健康问题关键词"
        @keyup.enter="runSearch()"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
        <template #append>
          <el-button type="primary" :icon="Search" @click="runSearch()">搜索</el-button>
        </template>
      </el-input>

      <div class="health-hot-topics">
        <span>热门主题</span>
        <button v-for="topic in hotTopics" :key="topic" type="button" @click="runSearch(topic)">
          {{ topic }}
        </button>
      </div>
    </section>

    <transition name="health-result">
      <section v-if="activeGuide" class="health-guide" aria-live="polite">
        <div class="health-guide__heading">
          <div>
            <el-tag effect="light" type="primary">本地健康科普</el-tag>
            <h2>{{ activeGuide.title }}</h2>
            <p>{{ activeGuide.summary }}</p>
          </div>
          <el-button type="primary" plain :icon="ChatDotRound" @click="startConsult">带着问题去问诊</el-button>
        </div>

        <div class="health-guide__content">
          <ul>
            <li v-for="point in activeGuide.points" :key="point">{{ point }}</li>
          </ul>
          <div class="health-guide__warning">
            <el-icon><WarningFilled /></el-icon>
            <span>{{ activeGuide.urgent }}</span>
          </div>
        </div>

        <footer>
          <el-icon><InfoFilled /></el-icon>
          <span>本页为本地整理的通用健康教育提示，尚未关联可追溯医学文档，不构成诊断或治疗方案。</span>
        </footer>
      </section>
    </transition>

    <section class="health-history" aria-labelledby="health-history-title">
      <div class="health-section-head">
        <div>
          <h2 id="health-history-title">搜索历史</h2>
          <p>点击记录可再次查看</p>
        </div>
        <el-button v-if="history.length" text type="primary" :icon="Delete" @click="clearHistory">清空记录</el-button>
      </div>

      <div v-if="history.length" class="health-history__list">
        <button v-for="item in history" :key="`${item.keyword}-${item.searchedAt}`" type="button" @click="runSearch(item.keyword)">
          <span class="health-history__copy">
            <el-icon><Clock /></el-icon>
            <strong>{{ item.keyword }}</strong>
          </span>
          <span class="health-history__meta">
            {{ formatHistoryTime(item.searchedAt) }}
            <el-icon><ArrowRight /></el-icon>
          </span>
        </button>
      </div>

      <el-empty v-else :image-size="66" description="暂无搜索记录" />
    </section>

    <section class="health-discovery" aria-labelledby="health-discovery-title">
      <div class="health-section-head">
        <div>
          <h2 id="health-discovery-title">健康发现</h2>
          <p>从常用主题开始建立更清晰的健康记录</p>
        </div>
      </div>

      <div class="health-discovery__grid">
        <button
          v-for="item in discoveries"
          :key="item.title"
          type="button"
          class="health-discovery__card"
          data-testid="health-discovery-card"
          :aria-label="`查看${item.title}健康指南`"
          @click="selectDiscovery(item)"
        >
          <div :class="['health-discovery__icon', `health-discovery__icon--${item.tone}`]">
            <el-icon><component :is="item.icon" /></el-icon>
          </div>
          <div class="health-discovery__body">
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
          </div>
          <span class="health-discovery__action" aria-hidden="true">
            <el-icon><ArrowRight /></el-icon>
          </span>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.health-page {
  width: min(100%, 1180px);
  margin: 0 auto;
  color: var(--text-primary);
}

.health-page__header,
.health-search__title,
.health-section-head,
.health-guide__heading,
.health-guide footer,
.health-history__list button,
.health-history__copy,
.health-history__meta,
.health-discovery__card {
  display: flex;
  align-items: center;
}

.health-page__header {
  min-height: 72px;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

.health-page__eyebrow {
  margin: 0 0 5px;
  color: var(--stream-cyan);
  font-size: 11px;
  font-weight: 700;
}

.health-page h1 {
  margin: 0;
  font-size: 24px;
  line-height: 1.3;
  letter-spacing: 0;
}

.health-page__subtitle {
  margin: 6px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

.health-search,
.health-guide,
.health-history,
.health-discovery {
  border: 0;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(98, 215, 255, 0.06), transparent 44%, rgba(183, 124, 255, 0.05)),
    var(--glass-surface);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(132%);
}

.health-history,
.health-discovery {
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
}

.health-search {
  padding: 24px 26px 22px;
}

.health-search__title {
  gap: 12px;
  margin-bottom: 18px;
}

.health-section-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 8px;
  font-size: 18px;
}

.health-section-icon--blue {
  color: var(--primary);
  background: var(--primary-soft);
  box-shadow: inset 0 1px 0 rgba(218, 243, 255, 0.14), 0 0 16px rgba(88, 186, 255, 0.12);
}

.health-search h2,
.health-section-head h2,
.health-guide h2 {
  margin: 0;
  font-size: 16px;
  line-height: 1.45;
  letter-spacing: 0;
}

.health-search__title p,
.health-section-head p {
  margin: 3px 0 0;
  color: var(--text-muted);
  font-size: 12px;
}

.health-search :deep(.el-input-group__append) {
  padding: 0;
  overflow: hidden;
  border-color: transparent;
  background: var(--primary-solid);
  box-shadow: none;
}

.health-search :deep(.el-input-group__append .el-button) {
  height: 38px;
  margin: 0;
  padding: 0 24px;
  border-radius: 0;
  color: var(--text-inverse);
  background: var(--primary-solid);
}

.health-search :deep(.el-input__wrapper) {
  background: var(--control-surface);
  box-shadow: inset 0 0 0 1px var(--border-subtle);
}

.health-search :deep(.el-input__wrapper.is-focus) {
  box-shadow: inset 0 0 0 1px var(--primary), 0 0 0 3px var(--focus-ring);
}

.health-hot-topics {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.health-hot-topics > span {
  margin-right: 2px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.health-hot-topics button {
  min-height: 28px;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: var(--surface-muted);
  color: var(--text-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.health-hot-topics button:hover,
.health-hot-topics button:focus-visible {
  border-color: var(--border-default);
  outline: none;
  background: var(--primary-soft);
  color: var(--primary);
}

.health-guide {
  margin-top: 16px;
  overflow: hidden;
  background:
    linear-gradient(130deg, rgba(92, 202, 255, 0.09), transparent 42%, rgba(190, 137, 255, 0.06)),
    var(--glass-surface-strong);
}

.health-guide__heading {
  justify-content: space-between;
  gap: 24px;
  padding: 20px 24px 18px;
}

.health-guide__heading h2 {
  margin-top: 9px;
}

.health-guide__heading p {
  max-width: 760px;
  margin: 7px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.health-guide__content {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(260px, 0.65fr);
  gap: 18px;
  padding: 18px 24px;
  border-top: 1px solid var(--border-subtle);
  background: var(--surface-muted);
}

.health-guide ul {
  display: grid;
  gap: 10px;
  margin: 0;
  padding-left: 20px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.65;
}

.health-guide li::marker {
  color: var(--stream-cyan);
}

.health-guide__warning {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 14px;
  border: 0;
  border-left: 2px solid var(--danger);
  border-radius: 6px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 12px;
  line-height: 1.7;
}

.health-guide__warning .el-icon {
  margin-top: 3px;
  flex: 0 0 auto;
}

.health-guide footer {
  gap: 7px;
  padding: 12px 24px;
  border-top: 1px solid var(--border-subtle);
  color: var(--text-muted);
  font-size: 11px;
}

.health-history,
.health-discovery {
  margin-top: 16px;
  padding: 20px 2px;
}

.health-section-head {
  justify-content: space-between;
  gap: 20px;
  min-height: 38px;
  margin-bottom: 12px;
}

.health-history__list {
  display: grid;
}

.health-history__list button {
  justify-content: space-between;
  gap: 20px;
  min-height: 48px;
  padding: 0 4px;
  border: 0;
  border-top: 1px solid var(--border-subtle);
  background: transparent;
  color: var(--text-primary);
  font: inherit;
  cursor: pointer;
}

.health-history__list button:hover .health-history__copy,
.health-history__list button:focus-visible .health-history__copy {
  color: var(--primary);
}

.health-history__list button:focus-visible {
  outline: 2px solid var(--focus-outline);
  outline-offset: 2px;
}

.health-history__copy {
  gap: 10px;
  min-width: 0;
  font-size: 13px;
  transition: color 0.16s ease;
}

.health-history__copy .el-icon {
  color: var(--text-muted);
}

.health-history__copy strong {
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.health-history__meta {
  gap: 10px;
  flex: 0 0 auto;
  color: var(--text-muted);
  font-size: 11px;
}

.health-history :deep(.el-empty) {
  padding: 18px 0 8px;
}

.health-history :deep(.el-empty__description) {
  margin-top: 4px;
}

.health-discovery__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.health-discovery__card {
  width: 100%;
  min-width: 0;
  min-height: 126px;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--surface-elevated);
  box-shadow: var(--shadow-sm);
  color: var(--text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  appearance: none;
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.health-discovery__card:hover,
.health-discovery__card:focus-within {
  border-color: var(--primary-subtle);
  background: color-mix(in srgb, var(--primary-soft) 42%, var(--surface-elevated));
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.health-discovery__icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 8px;
  font-size: 20px;
}

.health-discovery__icon--blue {
  color: var(--primary);
  background: var(--primary-soft);
}

.health-discovery__icon--green {
  color: var(--success);
  background: var(--success-soft);
}

.health-discovery__icon--violet {
  color: var(--accent-violet);
  background: var(--accent-violet-soft);
}

.health-discovery__body {
  min-width: 0;
  flex: 1;
}

.health-discovery__body h3 {
  margin: 0 0 7px;
  color: var(--text-primary);
  font-size: 14px;
  letter-spacing: 0;
}

.health-discovery__body p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.65;
}

.health-discovery__action {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 50%;
  background: var(--primary-soft);
  color: var(--primary);
  transition: background 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.health-discovery__card:hover .health-discovery__action,
.health-discovery__card:focus-visible .health-discovery__action {
  background: var(--primary);
  color: var(--text-inverse);
  transform: translateX(1px);
}

.health-result-enter-active,
.health-result-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.health-result-enter-from,
.health-result-leave-to {
  opacity: 0;
  transform: translateY(-5px);
}

@media (max-width: 1000px) {
  .health-guide__content {
    grid-template-columns: 1fr;
  }

  .health-discovery__grid {
    grid-template-columns: 1fr;
  }

  .health-discovery__card {
    min-height: 106px;
  }
}

@media (max-width: 640px) {
  .health-page__header,
  .health-guide__heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .health-search,
  .health-guide__heading,
  .health-guide__content,
  .health-history,
  .health-discovery {
    padding-right: 16px;
    padding-left: 16px;
  }

  .health-search :deep(.el-input-group__append .el-button) {
    padding: 0 13px;
  }

  .health-history__meta {
    font-size: 0;
  }
}
</style>
