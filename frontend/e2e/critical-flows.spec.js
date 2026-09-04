import { expect, test } from '@playwright/test'

import {
  installApiMocks,
  installCancellableConsult,
  installConsultStream,
  installMonitorEventSource,
} from './mock-api'

const pageErrors = new WeakMap()

test.beforeEach(async ({ page }) => {
  const errors = []
  pageErrors.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
})

test.afterEach(async ({ page }) => {
  expect(pageErrors.get(page) || []).toEqual([])
})

async function startConsult(page) {
  await page.goto('/consult')
  await expect(page.getByRole('heading', { name: /您好/ })).toBeVisible()
  await page.getByLabel('症状描述').fill('咳嗽三天伴低热')
  await page.getByTestId('quick-start-button').click()
  await expect(page.getByText('已将快速描述带入下方症状描述，可直接修改。')).toBeVisible()
  await expect(page.getByText('第 1/3 步：补充症状')).toBeVisible()
  await page.locator('.symptom-options .el-checkbox').filter({ hasText: '咳嗽' }).click()
  await expect(page.getByRole('checkbox', { name: '咳嗽' })).toBeChecked()
  await page.getByRole('button', { name: '提交并开始分析' }).click()
}

test('登录后可以安全注销', async ({ page }) => {
  const api = await installApiMocks(page, {
    loginProfile: { username: 'patient-e2e', role: 'USER' },
  })

  await page.goto('/login')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const loginDialog = page.getByRole('dialog', { name: '登录' })
  await expect(loginDialog).toBeVisible()
  await loginDialog.getByRole('textbox', { name: '账号' }).fill('patient-e2e')
  await loginDialog.getByRole('textbox', { name: '密码' }).fill('correct-password')
  await loginDialog.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/consult$/)
  await expect(page.getByRole('heading', { name: '您好，patient-e2e' })).toBeVisible()
  await page.locator('.user-trigger').click()
  await page.getByRole('menuitem', { name: /退出登录/ }).click()

  await expect(page).toHaveURL(/\/login$/)
  expect(api.requests.some((request) => request.method === 'POST' && request.path === '/auth/logout'))
    .toBe(true)
})

test('注册入口打开说明弹窗且不会伪造登录请求', async ({ page }) => {
  const api = await installApiMocks(page)

  await page.goto('/login')
  await page.getByRole('button', { name: '注册', exact: true }).click()

  await expect(page.getByRole('dialog', { name: '注册' })).toBeVisible()
  await expect(page.getByText('当前系统不支持自助注册，请联系管理员开通账号。')).toBeVisible()
  expect(api.requests.some((request) => request.method === 'POST' && request.path === '/auth/login'))
    .toBe(false)
})

test('问诊回答按 answer_delta 渐进呈现并完成', async ({ page }) => {
  await installApiMocks(page, { profile: { username: 'patient-e2e', role: 'USER' } })
  await installConsultStream(page)

  await startConsult(page)

  const streamingAnswer = page.locator('.streaming-message p')
  await expect(streamingAnswer).toContainText('建议先休息，')
  expect(await streamingAnswer.textContent()).not.toContain('呼吸内科就诊')

  await expect(page.getByRole('heading', { name: '辅助分诊结果' })).toBeVisible()
  await expect(page.locator('.answer-section')).toContainText('建议先休息，并在24小时内到呼吸内科就诊。')
  await expect(page.getByLabel('分诊结果摘要')).toContainText('呼吸内科')
})

test('用户取消问诊时浏览器中止唯一请求', async ({ page }) => {
  await installApiMocks(page, { profile: { username: 'patient-e2e', role: 'USER' } })
  await installCancellableConsult(page)

  await startConsult(page)
  await page.getByRole('button', { name: '取消分析' }).click()

  await expect(page.getByText('本次处理已取消，您可以修改问诊信息后重新提交。')).toBeVisible()
  await expect.poll(() => page.evaluate(() => window.__consultAbortObserved)).toBe(true)
})

test('记录筛选发送服务端分页查询', async ({ page }) => {
  const api = await installApiMocks(page, {
    profile: { username: 'patient-e2e', role: 'USER' },
    recordsByPage: true,
    recordsTotal: 42,
    recordPages: 3,
  })

  await page.goto('/records')
  await expect(page.getByRole('heading', { name: '问诊记录' })).toBeVisible()
  await page.getByLabel('按记录 ID 筛选').fill('17')
  await page.getByLabel('按会话 ID 筛选').fill('session-record-e2e')
  await page.getByLabel('按症状筛选').fill('咳嗽')
  await page.getByLabel('按科室筛选').fill('呼吸内科')

  const filteredRequest = page.waitForRequest((request) => {
    const url = new URL(request.url())
    return url.pathname === '/api/records'
      && url.searchParams.get('id') === '17'
      && url.searchParams.get('sessionId') === 'session-record-e2e'
  })
  await page.getByRole('button', { name: '应用', exact: true }).click()
  await filteredRequest

  const latest = [...api.requests].reverse().find((request) => request.path === '/records')
  const params = new URLSearchParams(latest.search)
  expect(params.get('symptoms')).toBe('咳嗽')
  expect(params.get('department')).toBe('呼吸内科')
  expect(params.get('page')).toBe('0')
  expect(params.get('size')).toBe('20')
  await expect(page.getByText('共 42 条记录')).toBeVisible()
})

test('管理员上传 Markdown 并完成独立审核', async ({ page }) => {
  const api = await installApiMocks(page, {
    profile: { username: 'admin', role: 'ADMIN' },
  })

  await page.goto('/knowledge')
  await expect(page.getByRole('heading', { name: '医学知识库' })).toBeVisible()
  await page.getByRole('button', { name: /录入文档/ }).click()
  const dialog = page.getByRole('dialog', { name: '录入医学知识文档' })
  await dialog.getByPlaceholder('例如 guideline-headache-2024').fill('e2e-respiratory-guide')
  await dialog.getByText('选择专科', { exact: true }).click()
  await page.getByRole('option', { name: '呼吸内科' }).click()
  await dialog.getByPlaceholder('例如 World Health Organization').fill('中华医学会')
  await dialog.getByPlaceholder('输入来源页面或指南的完整标题').fill('呼吸道症状分诊指南')
  await dialog.getByPlaceholder('https://...').fill('https://example.org/respiratory-guide')
  const publishedDate = dialog.getByRole('combobox', { name: /发布日期/ })
  await publishedDate.fill('2026-08-01')
  await publishedDate.press('Enter')
  await dialog.getByPlaceholder('例如 reviewed-2026-01-15').fill('reviewed-2026-08-01')
  await dialog.getByPlaceholder('例如 CC BY 4.0').fill('CC BY 4.0')
  await dialog.locator('input[type="file"]').setInputFiles({
    name: 'respiratory-guide.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# 呼吸道症状分诊指南\n\n咳嗽伴低热时应结合病程评估。'),
  })
  await dialog.getByText('已核对来源、许可与正文，确认提交后仍需独立审核', { exact: true }).click()
  await expect(dialog.getByRole('checkbox', { name: /已核对来源/ })).toBeChecked()
  await dialog.getByRole('button', { name: '提交待审核' }).click()

  await expect(page.getByText(/e2e-respiratory-guide/).first()).toBeVisible()
  const upload = api.requests.find((request) => request.path === '/knowledge/upload')
  expect(upload.headers['content-type']).toContain('multipart/form-data; boundary=')
  expect(upload.body).toContain('respiratory-guide.md')

  const row = page.locator('.kbx-doc-row').filter({ hasText: 'e2e-respiratory-guide' })
  await row.getByRole('button', { name: '审核通过' }).click()
  const reviewPrompt = page.locator('.el-message-box')
  await reviewPrompt.getByRole('textbox').fill('来源许可与内容复核通过')
  await reviewPrompt.getByRole('button', { name: '确认通过' }).click()

  await expect(row).toContainText('已审核')
  const review = api.requests.find((request) => request.path.endsWith('/review'))
  const reviewPayload = JSON.parse(review.body)
  expect(reviewPayload).toMatchObject({
    action: 'approve',
    change_reason: '来源许可与内容复核通过',
  })
  expect(reviewPayload).not.toHaveProperty('reviewer')
})

test('管理员可以创建、重置密码并删除用户', async ({ page }) => {
  const api = await installApiMocks(page, {
    profile: { username: 'admin', role: 'ADMIN' },
  })

  await page.goto('/users')
  await expect(page.getByRole('heading', { name: '用户权限' })).toBeVisible()
  await page.getByRole('button', { name: /创建用户/ }).click()
  const createDialog = page.getByRole('dialog', { name: '创建用户' })
  await createDialog.getByPlaceholder('例如 reviewer-01').fill('patient-new')
  const createPasswords = createDialog.locator('input[type="password"]')
  await createPasswords.nth(0).fill('secure-pass-123')
  await createPasswords.nth(1).fill('secure-pass-123')
  await createDialog.getByRole('button', { name: '创建账号' }).click()
  await expect(page.getByText('patient-new', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '重置 patient-new 的密码' }).click()
  const resetDialog = page.getByRole('dialog', { name: '重置 patient-new 的密码' })
  const resetPasswords = resetDialog.locator('input[type="password"]')
  await resetPasswords.nth(0).fill('new-secure-pass-456')
  await resetPasswords.nth(1).fill('new-secure-pass-456')
  await resetDialog.getByRole('button', { name: '确认重置' }).click()
  await expect(page.getByText(/旧会话已失效/)).toBeVisible()

  await page.getByRole('button', { name: '删除用户 patient-new' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByText('patient-new', { exact: true })).toHaveCount(0)

  const createRequest = api.requests.find((request) => request.method === 'POST' && request.path === '/admin/users')
  expect(JSON.parse(createRequest.body)).toMatchObject({ username: 'patient-new', role: 'USER' })
  const resetRequest = api.requests.find((request) => (
    request.method === 'PATCH' && request.path.startsWith('/admin/users/')
  ))
  expect(JSON.parse(resetRequest.body)).toEqual({ password: 'new-secure-pass-456' })
  expect(api.requests.some((request) => request.method === 'DELETE' && request.path.startsWith('/admin/users/')))
    .toBe(true)
})

test('实时监控从 SSE 快照显示活动链路', async ({ page }) => {
  await installApiMocks(page, { profile: { username: 'auditor-e2e', role: 'AUDITOR' } })
  await installMonitorEventSource(page)

  await page.goto('/monitor')

  await expect(page.getByRole('heading', { name: '智能体运行监控' })).toBeVisible()
  await expect(page.getByText('实时连接正常')).toBeVisible()
  const livePanel = page.getByRole('region', { name: '实时问诊链路' })
  await expect(livePanel).toContainText('执行中')
  await expect(livePanel).toContainText('安全筛查')
  await livePanel.getByRole('button', { name: '查看节点' }).click()
  await expect(page.getByText('2c293933-6590-4bfc-b0e8-507d3063c90b')).toBeVisible()
})
