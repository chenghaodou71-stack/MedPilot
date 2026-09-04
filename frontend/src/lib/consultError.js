const DEFAULT_ERROR = {
  title: '问诊暂时未完成',
  detail: '网络或服务出现波动。当前填写内容已保留，请重新尝试；如果症状描述需要修改，也可以返回补充表单。',
  action: 'retry',
}

export function describeConsultError(error = {}) {
  const status = Number(error?.status || error?.response?.status)
  const message = String(error?.message || error?.response?.data?.error || '').toLowerCase()

  if (error?.name === 'AbortError') {
    return {
      title: '本次问诊已取消',
      detail: '本次处理已取消，您可以修改问诊信息后重新提交。当前填写内容已保留。',
      action: 'edit',
    }
  }
  if (message.includes('附件') || message.includes('attachment')) {
    return {
      title: '附件未上传成功',
      detail: '文字症状仍已保留。可以移除附件后继续问诊，或稍后重新上传。',
      action: 'edit',
    }
  }
  if (status === 400 || message.includes('不完整') || message.includes('validation')) {
    return {
      title: '问诊信息还不完整',
      detail: '请返回补充表单，检查主要症状和症状描述后再提交。',
      action: 'edit',
    }
  }
  if (status === 408 || message.includes('超时') || message.includes('timeout')) {
    return {
      title: '响应时间较长',
      detail: '服务暂时没有及时返回。当前填写内容已保留，可以重新尝试。',
      action: 'retry',
    }
  }
  if (status === 429 || status === 502 || status === 503 || status === 504) {
    return {
      title: status === 429 ? '当前问诊服务较忙' : '问诊服务暂时不可用',
      detail: '请稍后重新尝试。已保留当前填写内容，不需要重新填写。',
      action: 'retry',
    }
  }
  return { ...DEFAULT_ERROR }
}
