<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  ChatDotRound,
  FirstAidKit,
  InfoFilled,
  Search,
  WarningFilled,
} from '@element-plus/icons-vue'
import doctorIllustration from '../assets/illustrations/doctor.svg'

const router = useRouter()

const FAQ_ITEMS = [
  {
    id: 'fever-care',
    category: '症状相关',
    question: '发热时应该如何处理？',
    keywords: ['发烧', '体温', '退热'],
    answer: [
      '先使用可靠的体温计复测并记录体温、测量时间和伴随症状。注意休息并适量补充水分，避免捂汗或酒精擦浴。退热药应按说明书或医嘱使用，不要同时服用含相同成分的复方感冒药。',
      '成人发热持续不退、反复超过 3 天，或婴幼儿、孕妇、老年人及免疫功能低下者出现发热时，建议尽早就医评估。',
    ],
    alert: '如伴有呼吸困难、意识异常、抽搐、颈项强直或口唇发紫，请立即拨打 120。',
  },
  {
    id: 'cold-flu-difference',
    category: '症状相关',
    question: '普通感冒和流感有什么区别？',
    keywords: ['感冒', '流感', '咳嗽', '咽痛'],
    answer: [
      '普通感冒通常以鼻塞、流涕、咽部不适为主，全身症状相对较轻；流感往往起病更急，更容易出现高热、明显乏力、肌肉酸痛和头痛。仅凭症状不能完全区分，必要时需结合流行病学信息和病原检测。',
      '出现持续高热、气促、胸痛、精神状态改变，或本身属于高风险人群时，应尽快就医，不要只依赖自行用药。',
    ],
  },
  {
    id: 'headache-warning',
    category: '症状相关',
    question: '头痛在什么情况下需要尽快就医？',
    keywords: ['头疼', '偏头痛', '神经'],
    answer: [
      '突然发生且迅速达到高峰的剧烈头痛、头部外伤后的头痛，或头痛伴有肢体无力、言语不清、视物异常、意识改变、抽搐、发热和颈项强直，均需要紧急评估。',
      '新出现并逐渐加重的头痛、年龄较大后首次出现的明显头痛，或原有头痛规律发生变化，也建议尽早就医。就诊时可记录发作时间、部位、持续时长、诱因及用药情况。',
    ],
    alert: '出现突发剧烈头痛或神经功能异常时，请立即拨打 120。',
  },
  {
    id: 'hypertension-exercise',
    category: '疾病预防',
    question: '高血压患者可以运动吗？',
    keywords: ['血压', '锻炼', '运动'],
    answer: [
      '血压控制稳定且医生评估允许时，多数患者可以进行规律的中等强度有氧运动，例如快走、骑车或游泳，并结合适量抗阻训练。建议循序渐进，运动前后测量血压，避免突然进行高强度或屏气用力的运动。',
      '如果血压明显升高、近期调整药物，或合并心脑血管疾病，应先咨询医生制定运动方案。运动中出现胸痛、明显气短、晕厥或神经系统症状，应立即停止并寻求帮助。',
    ],
  },
  {
    id: 'respiratory-prevention',
    category: '疾病预防',
    question: '日常如何减少呼吸道感染风险？',
    keywords: ['感染', '预防', '口罩', '洗手'],
    answer: [
      '保持良好手卫生，咳嗽或打喷嚏时遮挡口鼻，在人群密集或通风较差的场所根据风险佩戴口罩。规律作息、均衡饮食、适量运动和按建议接种疫苗有助于降低感染及重症风险。',
      '出现呼吸道症状后应尽量减少与高龄、婴幼儿和免疫功能低下者近距离接触，并注意室内通风。',
    ],
  },
  {
    id: 'blood-pressure-measurement',
    category: '检查检验',
    question: '如何在家正确测量血压？',
    keywords: ['血压计', '收缩压', '舒张压'],
    answer: [
      '测量前 30 分钟避免吸烟、饮用咖啡或剧烈运动，安静坐位休息至少 5 分钟。选择尺寸合适的上臂式袖带，双脚平放地面，背部有支撑，手臂放松并与心脏同高，测量时不要说话。',
      '每次间隔约 1 分钟测量 2 次并记录结果。初次评估可在早晚固定时段连续记录数日，复诊时带给医生判断；不要仅凭单次读数自行增减药物。',
    ],
  },
  {
    id: 'fasting-exam',
    category: '检查检验',
    question: '体检抽血一定需要空腹吗？',
    keywords: ['空腹', '抽血', '体检', '血糖'],
    answer: [
      '是否需要空腹取决于检查项目。空腹血糖、部分血脂和腹部影像检查通常有特定准备要求，血常规等许多项目则不一定需要空腹。不同机构的要求可能不同，应以检查单或医院通知为准。',
      '需要空腹时通常应按通知停止进食，但能否饮少量白水、常用药是否照常服用需提前向开单医生或检查机构确认，切勿自行停用长期处方药。',
    ],
  },
  {
    id: 'antibiotic-use',
    category: '药品用药',
    question: '感冒后可以自行服用抗生素吗？',
    keywords: ['消炎药', '抗菌药', '病毒'],
    answer: [
      '不建议自行服用。多数普通感冒由病毒引起，抗生素对病毒无效；不恰当使用可能引起过敏、腹泻等不良反应，并增加细菌耐药风险。是否存在细菌感染需要医生结合症状、体征和必要检查判断。',
      '已由医生开具抗生素时，应按处方剂量和疗程使用，不要与他人共用，也不要使用上次剩余药物。出现皮疹、呼吸困难或严重腹泻等情况应及时就医。',
    ],
  },
  {
    id: 'missed-dose',
    category: '药品用药',
    question: '漏服一次药物应该怎么办？',
    keywords: ['忘记吃药', '补服', '剂量'],
    answer: [
      '不同药物的补服规则不同，应先查看药品说明书或处方提示。通常发现漏服时要结合距离下一次服药的时间判断，不要未经确认就加倍服用。',
      '胰岛素、抗凝药、抗癫痫药、激素及其他需要严密调整剂量的药物，漏服后应尽快联系开药医生或药师获得针对性建议。',
    ],
  },
  {
    id: 'emergency-or-clinic',
    category: '就医流程',
    question: '哪些情况应该去急诊，而不是等待门诊？',
    keywords: ['急诊', '门诊', '120', '危急'],
    answer: [
      '严重胸痛或呼吸困难、意识障碍、抽搐、疑似卒中表现、严重过敏反应、大量出血、重度外伤或持续不能缓解的剧烈疼痛，通常需要急诊评估。情况危急时应拨打 120，并按调度人员指导处理。',
      '症状相对稳定、无危险信号的慢性问题或复诊需求，可根据病情选择普通门诊或专科门诊。无法判断时，可先联系当地医疗机构或急救中心获得指导。',
    ],
    alert: '突发胸痛、呼吸困难、意识异常或疑似卒中时，不要自行驾车，请立即拨打 120。',
  },
  {
    id: 'prepare-for-visit',
    category: '就医流程',
    question: '就诊前需要准备哪些信息？',
    keywords: ['挂号', '病历', '就诊准备'],
    answer: [
      '建议携带身份证件、医保凭证、既往病历和近期检查报告，并整理正在使用的处方药、非处方药及保健品清单，标明名称、剂量和频次。',
      '可提前记录主要症状的开始时间、变化过程、诱发或缓解因素、伴随症状、过敏史和重要既往病史。这些信息有助于医生更准确地评估。',
    ],
  },
  {
    id: 'online-limitations',
    category: '其他',
    question: '智能问诊可以替代线下医生诊断吗？',
    keywords: ['在线问诊', '人工智能', '辅助分诊'],
    answer: [
      '不能。智能问诊可以帮助整理症状、提示风险并提供就诊科室参考，但无法完成体格检查，也可能缺少化验和影像资料，因此不能作为确诊、处方或调整治疗方案的唯一依据。',
      '如果建议与自身感受不一致、症状持续或加重，应及时线下就医。任何紧急或危及生命的情况都应优先拨打 120。',
    ],
  },
]

const categoryOrder = ['全部', '症状相关', '疾病预防', '检查检验', '药品用药', '就医流程', '其他']
const popularSearches = ['发热', '血压', '抗生素', '急诊']

const draftQuery = ref('')
const appliedQuery = ref('')
const activeCategory = ref('全部')
const activeQuestion = ref('')

const categories = computed(() =>
  categoryOrder.map((name) => ({
    name,
    count:
      name === '全部' ? FAQ_ITEMS.length : FAQ_ITEMS.filter((item) => item.category === name).length,
  })),
)

const filteredItems = computed(() => {
  const query = appliedQuery.value.trim().toLocaleLowerCase('zh-CN')

  return FAQ_ITEMS.filter((item) => {
    if (activeCategory.value !== '全部' && item.category !== activeCategory.value) return false
    if (!query) return true

    const searchable = [
      item.question,
      item.category,
      ...item.keywords,
      ...item.answer,
      item.alert || '',
    ]
      .join(' ')
      .toLocaleLowerCase('zh-CN')

    return searchable.includes(query)
  })
})

const filterSummary = computed(() => {
  if (appliedQuery.value) return `“${appliedQuery.value}”相关结果 ${filteredItems.value.length} 条`
  return `${activeCategory.value}问题 ${filteredItems.value.length} 条`
})

function submitSearch() {
  appliedQuery.value = draftQuery.value.trim()
  activeQuestion.value = ''
}

function handleSearchClear() {
  appliedQuery.value = ''
  activeQuestion.value = ''
}

function chooseCategory(category) {
  activeCategory.value = category
  activeQuestion.value = ''
}

function choosePopular(keyword) {
  draftQuery.value = keyword
  appliedQuery.value = keyword
  activeCategory.value = '全部'
  activeQuestion.value = ''
}

function clearFilters() {
  draftQuery.value = ''
  appliedQuery.value = ''
  activeCategory.value = '全部'
  activeQuestion.value = ''
}
</script>

<template>
  <div class="faq-page">
    <header class="faq-hero">
      <div class="faq-hero-copy">
        <span class="faq-eyebrow">HEALTH FAQ</span>
        <h1>常见问题</h1>
        <p>查看常见健康疑问及规范就医提示</p>
      </div>
      <img :src="doctorIllustration" alt="医生健康咨询插画" class="faq-doctor" />
    </header>

    <section class="faq-search-panel" aria-label="搜索常见问题">
      <el-input
        v-model="draftQuery"
        class="faq-search-input"
        size="large"
        clearable
        placeholder="搜索症状、检查、用药或就医问题"
        @clear="handleSearchClear"
        @keyup.enter="submitSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
        <template #append>
          <el-button type="primary" aria-label="搜索常见问题" @click="submitSearch">
            <el-icon><Search /></el-icon>
            搜索
          </el-button>
        </template>
      </el-input>

      <div class="faq-popular">
        <span>热门搜索</span>
        <button
          v-for="keyword in popularSearches"
          :key="keyword"
          type="button"
          @click="choosePopular(keyword)"
        >
          {{ keyword }}
        </button>
      </div>
    </section>

    <nav class="faq-categories" aria-label="问题分类">
      <button
        v-for="category in categories"
        :key="category.name"
        type="button"
        :aria-current="activeCategory === category.name ? 'page' : undefined"
        :class="['faq-category', { 'faq-category-active': activeCategory === category.name }]"
        @click="chooseCategory(category.name)"
      >
        {{ category.name }}
        <span>{{ category.count }}</span>
      </button>
    </nav>

    <section class="faq-list" aria-live="polite">
      <div class="faq-list-header">
        <div>
          <strong>健康问题解答</strong>
          <span>{{ filterSummary }}</span>
        </div>
        <el-button v-if="appliedQuery || activeCategory !== '全部'" text type="primary" @click="clearFilters">
          清除筛选
        </el-button>
      </div>

      <el-empty v-if="!filteredItems.length" class="faq-empty" description="没有找到相关问题">
        <el-button type="primary" plain @click="clearFilters">查看全部问题</el-button>
      </el-empty>

      <el-collapse v-else v-model="activeQuestion" class="faq-collapse" accordion>
        <el-collapse-item v-for="(item, index) in filteredItems" :key="item.id" :name="item.id">
          <template #title>
            <div class="faq-question-title">
              <span class="faq-question-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <strong>{{ item.question }}</strong>
              <el-tag size="small" effect="plain">{{ item.category }}</el-tag>
            </div>
          </template>

          <div class="faq-answer">
            <div class="faq-answer-heading">
              <span><el-icon><InfoFilled /></el-icon></span>
              <strong>健康建议</strong>
            </div>
            <p v-for="paragraph in item.answer" :key="paragraph">{{ paragraph }}</p>
            <div v-if="item.alert" class="faq-answer-alert">
              <el-icon><WarningFilled /></el-icon>
              <span>{{ item.alert }}</span>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </section>

    <el-alert class="faq-safety" type="warning" :closable="false" show-icon>
      <template #title>健康信息仅供参考，不能替代医生面诊、检查与个体化治疗。</template>
      紧急情况请立即拨打 120；症状持续、加重或无法判断时，请及时前往正规医疗机构。
    </el-alert>

    <footer class="faq-support">
      <div>
        <span class="faq-support-icon"><el-icon><ChatDotRound /></el-icon></span>
        <div>
          <strong>没有找到需要的信息？</strong>
          <p>可发起智能问诊，补充具体症状后获取分诊参考。</p>
        </div>
      </div>
      <el-button type="primary" @click="router.push('/consult')">
        <el-icon><FirstAidKit /></el-icon>
        发起智能问诊
        <el-icon><ArrowRight /></el-icon>
      </el-button>
    </footer>
  </div>
</template>

<style scoped>
.faq-page {
  width: min(100%, 1180px);
  min-width: 0;
  margin: 0 auto;
  display: grid;
  gap: 16px;
  color: var(--text-primary);
}

.faq-hero {
  position: relative;
  min-height: 128px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 30px;
  overflow: hidden;
  padding: 4px 24px 0 3px;
  border-bottom: 1px solid var(--border-subtle);
}

.faq-hero-copy {
  position: relative;
  z-index: 1;
}

.faq-eyebrow {
  color: var(--stream-cyan);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
}

.faq-hero h1 {
  margin: 5px 0 5px;
  font-size: 24px;
  line-height: 1.3;
  letter-spacing: 0;
}

.faq-hero p {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.faq-doctor {
  width: 190px;
  height: 126px;
  flex: 0 0 auto;
  object-fit: contain;
  object-position: center bottom;
  opacity: 0.86;
  filter: drop-shadow(0 14px 26px rgba(45, 144, 221, 0.14));
}

.faq-search-panel {
  padding: 16px;
  border: 0;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(98, 215, 255, 0.07), transparent 44%, rgba(183, 124, 255, 0.05)),
    var(--glass-surface);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px) saturate(132%);
}

.faq-search-input {
  width: 100%;
}

.faq-search-input :deep(.el-input-group__append) {
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.faq-search-input :deep(.el-input-group__append .el-button) {
  height: 40px;
  margin: 0;
  padding: 0 22px;
  border: 0;
  border-radius: 0 6px 6px 0;
  background: var(--primary-solid);
  color: var(--text-inverse);
}

.faq-popular {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 12px;
}

.faq-popular > span {
  margin-right: 2px;
  color: var(--text-muted);
  font-size: 10px;
}

.faq-popular button {
  min-height: 25px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: var(--surface-muted);
  color: var(--text-secondary);
  font: inherit;
  font-size: 10px;
  cursor: pointer;
}

.faq-popular button:hover {
  border-color: var(--border-default);
  color: var(--primary);
  background: var(--primary-soft);
}

.faq-categories {
  display: flex;
  align-items: center;
  gap: 7px;
  overflow-x: auto;
  padding: 1px 0 3px;
  scrollbar-width: thin;
}

.faq-category {
  min-width: max-content;
  min-height: 35px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: var(--surface-muted);
  color: var(--text-secondary);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
  transition: color 0.16s ease, background 0.16s ease, border-color 0.16s ease;
}

.faq-category:hover {
  color: var(--primary);
  border-color: var(--border-default);
}

.faq-category span {
  margin-left: 4px;
  color: var(--text-subtle);
  font-size: 9px;
}

.faq-category-active {
  border-color: var(--border-default);
  background: linear-gradient(135deg, var(--primary-soft), var(--accent-violet-soft));
  color: var(--primary);
  font-weight: 600;
}

.faq-category-active span {
  color: var(--primary);
}

.faq-list {
  overflow: hidden;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.faq-list-header,
.faq-list-header > div,
.faq-answer-heading,
.faq-answer-alert,
.faq-support,
.faq-support > div {
  display: flex;
  align-items: center;
}

.faq-list-header {
  min-height: 58px;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px;
  border-bottom: 1px solid var(--border-subtle);
  background: transparent;
}

.faq-list-header > div {
  gap: 10px;
}

.faq-list-header strong {
  font-size: 13px;
}

.faq-list-header span {
  color: var(--text-muted);
  font-size: 10px;
}

.faq-collapse {
  border: 0;
}

.faq-collapse :deep(.el-collapse-item__header) {
  min-height: 58px;
  height: auto;
  padding: 10px 20px;
  border-bottom-color: var(--border-subtle);
  background: transparent;
  color: var(--text-primary);
  line-height: 1.5;
}

.faq-collapse :deep(.el-collapse-item__header:hover),
.faq-collapse :deep(.el-collapse-item__header.is-active) {
  color: var(--primary);
  background: var(--primary-soft);
}

.faq-collapse :deep(.el-collapse-item__wrap) {
  border-bottom-color: var(--border-subtle);
  background: var(--surface-muted);
}

.faq-collapse :deep(.el-collapse-item__content) {
  padding: 0;
}

.faq-question-title {
  width: 100%;
  min-width: 0;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding-right: 12px;
}

.faq-question-index {
  color: var(--text-subtle);
  font-size: 10px;
  font-weight: 700;
}

.faq-question-title strong {
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0;
}

.faq-answer {
  padding: 18px 60px;
  color: var(--text-secondary);
}

.faq-answer-heading {
  gap: 8px;
  margin-bottom: 11px;
  color: var(--text-primary);
}

.faq-answer-heading > span {
  width: 25px;
  height: 25px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: var(--primary-soft);
  color: var(--primary);
}

.faq-answer-heading strong {
  font-size: 12px;
}

.faq-answer p {
  margin: 0 0 9px;
  font-size: 12px;
  line-height: 1.85;
}

.faq-answer-alert {
  align-items: flex-start;
  gap: 8px;
  margin-top: 13px;
  padding: 10px 12px;
  border-left: 2px solid var(--danger);
  border-radius: 6px;
  background: linear-gradient(110deg, var(--danger-soft), rgba(123, 34, 67, 0.06));
  color: var(--danger);
  font-size: 11px;
  line-height: 1.7;
}

.faq-answer-alert .el-icon {
  margin-top: 3px;
  flex: 0 0 auto;
}

.faq-empty {
  min-height: 320px;
}

.faq-safety {
  border: 0;
  border-radius: 8px;
  background: linear-gradient(105deg, var(--warning-soft), rgba(95, 65, 21, 0.05));
  color: var(--text-secondary);
  box-shadow: inset 0 1px 0 rgba(255, 222, 166, 0.08);
}

.faq-support {
  justify-content: space-between;
  gap: 18px;
  padding: 17px 2px 2px;
  border-top: 1px solid var(--border-subtle);
}

.faq-support > div {
  gap: 11px;
  min-width: 0;
}

.faq-support-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 7px;
  background: var(--primary-soft);
  color: var(--primary);
}

.faq-support strong {
  display: block;
  font-size: 12px;
}

.faq-support p {
  margin: 3px 0 0;
  color: var(--text-muted);
  font-size: 10px;
}

@media (max-width: 900px) {
  .faq-hero {
    min-height: 116px;
  }

  .faq-doctor {
    width: 166px;
    height: 112px;
  }

  .faq-answer {
    padding-inline: 38px;
  }
}

@media (max-width: 640px) {
  .faq-hero {
    min-height: 104px;
    padding-right: 2px;
  }

  .faq-doctor {
    width: 118px;
    height: 94px;
  }

  .faq-search-input :deep(.el-input-group__append .el-button) {
    padding-inline: 14px;
  }

  .faq-question-title {
    grid-template-columns: 22px minmax(0, 1fr);
  }

  .faq-question-title .el-tag {
    display: none;
  }

  .faq-answer {
    padding: 16px 22px;
  }

  .faq-support {
    align-items: stretch;
    flex-direction: column;
  }

  .faq-support > .el-button {
    width: 100%;
  }
}
</style>
