import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test } from '@playwright/test'

import { installApiMocks, installMonitorEventSource } from './mock-api'

const currentDir = path.dirname(fileURLToPath(import.meta.url))
const outputDir = path.resolve(currentDir, '../../outputs')

function rgbAverage(color) {
  const channels = color.match(/\d+(?:\.\d+)?/g)?.slice(0, 3).map(Number) || []
  return channels.length === 3
    ? channels.reduce((total, channel) => total + channel, 0) / channels.length
    : 255
}

async function expectLightBackground(locator) {
  await expect(locator).toBeVisible()
  const color = await locator.evaluate((node) => getComputedStyle(node).backgroundColor)
  expect(rgbAverage(color), `expected light background, received ${color}`).toBeGreaterThan(180)
}

test('knowledge and monitor operational surfaces remain light', async ({ page }) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))

  await installApiMocks(page, {
    profile: { username: 'admin', role: 'ADMIN' },
  })
  await installMonitorEventSource(page)

  await page.goto('/knowledge')
  await page.waitForLoadState('networkidle')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'medical-light')
  await expectLightBackground(page.locator('.kbx-search-panel'))
  await expectLightBackground(page.locator('.kbx-search-input .el-input__wrapper'))
  await expectLightBackground(page.locator('.kbx-metrics'))
  await page.screenshot({
    path: path.join(outputDir, 'light-knowledge-surfaces.png'),
    fullPage: true,
  })

  await page.goto('/monitor')
  await page.waitForLoadState('networkidle')
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'medical-light')
  await expectLightBackground(page.locator('.mon-live-panel'))
  await expectLightBackground(page.locator('.mon-status-grid'))
  await expectLightBackground(page.locator('.mon-trace-inventory'))
  await expectLightBackground(page.locator('.mon-trace-filters'))
  await page.screenshot({
    path: path.join(outputDir, 'light-monitor-surfaces.png'),
    fullPage: true,
  })

  expect(await page.evaluate(() => (
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  ))).toBe(false)
  expect(errors).toEqual([])
})
