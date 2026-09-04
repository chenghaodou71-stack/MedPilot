import { describe, expect, it } from 'vitest'

import { describeConsultError } from './consultError'

describe('consult error recovery copy', () => {
  it('maps capacity and service failures to retry guidance', () => {
    expect(describeConsultError({ status: 429 }).title).toBe('当前问诊服务较忙')
    expect(describeConsultError({ status: 429 }).action).toBe('retry')
    expect(describeConsultError({ status: 503 }).detail).toContain('保留当前填写内容')
  })

  it('maps timeout, attachment and validation failures to the right next step', () => {
    expect(describeConsultError({ name: 'AbortError' }).title).toBe('本次问诊已取消')
    expect(describeConsultError(new Error('附件上传失败')).action).toBe('edit')
    expect(describeConsultError({ status: 400 }).title).toBe('问诊信息还不完整')
    expect(describeConsultError(new Error('请求超时')).title).toBe('响应时间较长')
  })

  it('does not expose provider details in the default fallback', () => {
    const result = describeConsultError(new Error('private provider token leaked'))

    expect(result.detail).not.toContain('private provider token')
    expect(result.action).toBe('retry')
  })
})
