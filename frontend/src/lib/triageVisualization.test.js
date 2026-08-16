import { describe, expect, it } from 'vitest'
import {
  buildAgentFlowModel,
  buildDecisionFlowModel,
  buildRiskScale,
  buildUrgencyScale,
} from './triageVisualization'

describe('triage visualization models', () => {
  it('maps the risk result onto a semantic three-level scale', () => {
    expect(buildRiskScale('中')).toMatchObject({
      activeIndex: 1,
      label: '中风险',
      steps: [
        { key: 'low', active: false },
        { key: 'medium', active: true },
        { key: 'high', active: false },
      ],
    })
  })

  it('does not exaggerate an unknown risk value', () => {
    expect(buildRiskScale('critical')).toMatchObject({
      activeIndex: -1,
      label: '风险待确认',
    })
  })

  it('maps emergency and outpatient wording to a readable urgency track', () => {
    expect(buildUrgencyScale('建议立即呼叫急救').activeIndex).toBe(0)
    expect(buildUrgencyScale('建议尽早于门诊就诊').activeIndex).toBe(2)
    expect(buildUrgencyScale('').activeIndex).toBe(-1)
  })

  it('keeps the emergency fast path explicit in both scales', () => {
    expect(buildRiskScale('高风险')).toMatchObject({
      activeIndex: 2,
      label: '高风险',
    })
    expect(buildUrgencyScale('请立即拨打 120，不要自行驾车')).toMatchObject({
      activeIndex: 0,
      label: '立即处置',
    })
  })

  it('builds a live agent graph from real workflow status', () => {
    const agents = [
      { key: 'safety_screen', shortTitle: '安全筛查', description: '识别危险信号' },
      { key: 'extract', shortTitle: '信息采集', description: '提取症状' },
      { key: 'retrieve', shortTitle: '知识检索', description: '匹配依据' },
    ]
    const model = buildAgentFlowModel(agents, {
      safety_screen: 'done',
      extract: 'running',
      retrieve: 'waiting',
    }, {
      nodes: { safety_screen: { elapsedMs: 18 } },
      symptoms: { symptoms: ['腹痛', '恶心'] },
    })

    expect(model.nodes).toHaveLength(3)
    expect(model.edges).toHaveLength(2)
    expect(model.nodes[0].data).toMatchObject({ status: 'done', detail: '安全筛查完成 · 18 ms' })
    expect(model.nodes[1].data).toMatchObject({ status: 'running', detail: '正在识别症状信息' })
  })

  it('connects symptoms and evidence to triage outcomes without inventing data', () => {
    const model = buildDecisionFlowModel({
      symptoms: { symptoms: ['腹痛', '恶心'], duration: '1-3 天' },
      triage: {
        department: '消化内科',
        risk_level: '中',
        urgency: '建议尽早于门诊就诊',
        factors: [
          { kind: 'evidence', label: '消化系统诊疗规范', reference: 'C1', support: 0.72 },
        ],
      },
      evidence: [{ citation_id: 'C1', source: '诊疗规范', score: 0.82 }],
    })

    expect(model.nodes.some((node) => node.id === 'symptom-0' && node.data.label === '腹痛')).toBe(true)
    expect(model.nodes.some((node) => (
      node.id === 'factor-0'
      && node.data.supportLabel === '检索支持度 72%'
    ))).toBe(true)
    expect(model.nodes.some((node) => node.id === 'outcome-department' && node.data.value === '消化内科')).toBe(true)
    expect(model.edges.some((edge) => edge.source === 'factor-0' && edge.target === 'outcome-department')).toBe(true)
    expect(model.relationshipNote).toBe('连线仅表示本次返回数据的结构化关联，不代表单项因果或疾病诊断。')
  })

  it('returns an honest empty state when no factors are available', () => {
    const model = buildDecisionFlowModel({
      symptoms: { symptoms: ['乏力'] },
      triage: { department: '全科/建议线下分诊台', risk_level: '低', urgency: '' },
      evidence: [],
    })

    expect(model.hasEvidence).toBe(false)
    expect(model.nodes.some((node) => node.id === 'factor-empty')).toBe(true)
  })

  it('does not turn retrieved material into a decision basis after abstaining', () => {
    const model = buildDecisionFlowModel({
      symptoms: { symptoms: ['乏力'] },
      triage: {
        department: '全科/建议线下分诊台',
        risk_level: '低',
        urgency: '建议尽早于门诊就诊',
        support_score: 0,
        matched_rule: 'stale-rule',
        factors: [
          { kind: 'evidence', label: '过期依据', reference: 'C1', support: 0.78 },
        ],
        abstained: true,
      },
      evidence: [{ citation_id: 'C1', source: '门诊资料', score: 0.78 }],
    })

    expect(model.hasEvidence).toBe(false)
    expect(model.nodes.some((node) => (
      node.id === 'material-0'
      && node.data.category === 'retrieval'
      && node.data.categoryLabel === '检索资料'
    ))).toBe(true)
    expect(model.edges).toHaveLength(0)
  })

  it('keeps legacy citations visible without claiming they determined the outcome', () => {
    const model = buildDecisionFlowModel({
      symptoms: { symptoms: ['咳嗽'] },
      triage: {
        department: '呼吸内科',
        risk_level: '中',
        urgency: '建议尽早于门诊就诊',
        abstained: false,
      },
      evidence: [{ citation_id: 'C1', source: '呼吸科指南', score: 0.84 }],
    })

    expect(model.hasEvidence).toBe(false)
    expect(model.nodes.some((node) => (
      node.id === 'material-0'
      && node.data.supportLabel === '检索相似度 84%'
    ))).toBe(true)
    expect(model.edges).toHaveLength(0)
  })

  it('uses a real matched rule when the emergency payload has no factor array', () => {
    const model = buildDecisionFlowModel({
      symptoms: null,
      triage: {
        department: '心血管内科',
        risk_level: '高',
        urgency: '建议立即就医或呼叫急救',
        matched_rule: 'red-flag-chest-pain',
      },
      evidence: [],
    })

    expect(model.hasEvidence).toBe(true)
    expect(model.nodes.some((node) => (
      node.id === 'factor-0'
      && node.data.category === 'rule'
      && node.data.label === 'red-flag-chest-pain'
      && node.data.supportLabel === ''
    ))).toBe(true)
  })

  it('labels rule support separately from retrieval support', () => {
    const model = buildDecisionFlowModel({
      symptoms: { symptoms: ['胸痛'] },
      triage: {
        department: '心血管内科',
        risk_level: '高',
        urgency: '建议立即就医或呼叫急救',
        factors: [
          { kind: 'rule', label: '胸痛', reference: '胸痛高风险规则', support: 1 },
        ],
      },
      evidence: [],
    })

    expect(model.nodes.some((node) => (
      node.id === 'factor-0'
      && node.data.supportLabel === '规则支持分 100%'
    ))).toBe(true)
  })

  it('handles malformed collections and support values without throwing', () => {
    const model = buildDecisionFlowModel({
      symptoms: { symptoms: '腹痛' },
      triage: {
        department: '',
        risk_level: null,
        urgency: null,
        factors: [null, { kind: 'evidence', label: '', support: 'invalid' }],
      },
      evidence: 'not-an-array',
    })

    expect(model.hasEvidence).toBe(false)
    expect(model.nodes.some((node) => node.id === 'symptom-empty')).toBe(true)
    expect(model.nodes.some((node) => node.id === 'outcome-risk' && node.data.value === '待确认')).toBe(true)
  })
})
