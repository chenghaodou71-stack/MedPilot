<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, RefreshCw, ShieldCheck } from 'lucide-vue-next'
import client from '../api/client'

const rows = ref([])
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const actor = ref('')
const status = ref('')
const page = ref(0)
const size = ref(30)
const total = ref(0)

function payloadOf(response) { return response?.data?.data ?? response?.data }
function metaOf(response) { return response?.data?.meta ?? {} }
function errorText(value, fallback) {
  const raw = value?.response?.data?.error || value?.response?.data?.detail
  return typeof raw === 'string' && raw.trim() ? raw : fallback
}

async function loadLogs() {
  loading.value = true
  error.value = ''
  try {
    const response = await client.get('/audit/logs', {
      params: { actor: actor.value.trim() || undefined, status: status.value || undefined, page: page.value, size: size.value },
    })
    rows.value = Array.isArray(payloadOf(response)) ? payloadOf(response) : []
    total.value = Number(metaOf(response).total) || 0
  } catch (value) {
    rows.value = []
    error.value = errorText(value, '审计记录暂时无法加载。')
  } finally { loading.value = false }
}

function formatTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(date)
}

async function exportLogs() {
  exporting.value = true
  try {
    const response = await client.get('/audit/export', { responseType: 'blob' })
    const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'medpilot-audit.csv'
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已生成脱敏审计导出')
  } catch (value) { ElMessage.error(errorText(value, '导出失败，请稍后重试。')) }
  finally { exporting.value = false }
}

function changePage(nextPage) { page.value = nextPage - 1; loadLogs() }
function resetFilters() { actor.value = ''; status.value = ''; page.value = 0; loadLogs() }

onMounted(loadLogs)
</script>

<template>
  <div class="aud-page">
    <header class="aud-header">
      <div>
        <span class="aud-eyebrow">AUDIT TRAIL</span>
        <h1>审计日志</h1>
        <p>记录访问边界、变更结果与耗时，不保存请求正文、令牌或医疗文本。</p>
      </div>
      <div class="aud-actions">
        <el-button plain :loading="loading" aria-label="刷新审计日志" @click="loadLogs"><RefreshCw :size="16" />刷新</el-button>
        <el-button type="primary" plain :loading="exporting" @click="exportLogs"><Download :size="16" />脱敏导出</el-button>
      </div>
    </header>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <section class="aud-panel">
      <div class="aud-filters">
        <el-input v-model="actor" clearable placeholder="按操作者筛选" aria-label="按操作者筛选" @keyup.enter="resetFilters" />
        <el-select v-model="status" clearable placeholder="响应状态" aria-label="按响应状态筛选" @change="resetFilters">
          <el-option label="成功（2xx/3xx）" value="200" />
          <el-option label="客户端错误（4xx）" value="400" />
          <el-option label="服务端错误（5xx）" value="500" />
        </el-select>
        <el-button type="primary" @click="resetFilters"><ShieldCheck :size="16" />应用筛选</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" row-key="eventId" class="aud-table">
        <el-table-column label="时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column prop="actor" label="操作者" min-width="115" />
        <el-table-column prop="role" label="角色" min-width="130" />
        <el-table-column prop="action" label="动作" min-width="230" />
        <el-table-column label="结果" width="100"><template #default="scope"><el-tag size="small" :type="scope.row.success ? 'success' : 'danger'">{{ scope.row.status }} {{ scope.row.success ? '成功' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column label="耗时" width="100"><template #default="scope">{{ scope.row.durationMs || 0 }} ms</template></el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无符合条件的审计记录" />
      <div class="aud-footer">
        <small>共 {{ total }} 条 · 已脱敏展示</small>
        <el-pagination v-if="total" background layout="prev, pager, next" :page-size="size" :current-page="page + 1" :total="total" @current-change="changePage" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.aud-page { width: min(100%, 1180px); margin: 0 auto; display: grid; gap: 16px; color: var(--text-primary); }
.aud-header, .aud-actions, .aud-filters, .aud-footer { display: flex; align-items: center; }
.aud-header { justify-content: space-between; gap: 20px; padding: 4px 2px 8px; }
.aud-actions { gap: 8px; }
.aud-actions .el-button, .aud-filters .el-button { display: inline-flex; align-items: center; gap: 7px; }
.aud-eyebrow { color: var(--primary); font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.aud-header h1 { margin: 5px 0 4px; font-size: 25px; }
.aud-header p { margin: 0; color: var(--text-muted); font-size: 13px; }
.aud-panel { padding: 18px; border: 1px solid var(--border-default); border-radius: var(--radius-lg); background: var(--glass-surface, var(--surface-elevated)); box-shadow: var(--shadow-card); }
.aud-filters { gap: 9px; margin-bottom: 14px; }
.aud-filters .el-input { max-width: 240px; }
.aud-filters .el-select { width: 180px; }
.aud-table { --el-table-bg-color: transparent; --el-table-tr-bg-color: transparent; --el-table-header-bg-color: var(--surface-muted); --el-table-border-color: var(--border-subtle); }
.aud-footer { justify-content: space-between; gap: 12px; margin-top: 14px; color: var(--text-muted); font-size: 12px; }
@media (max-width: 720px) { .aud-header { align-items: flex-start; flex-direction: column; } .aud-actions { width: 100%; } .aud-actions .el-button { flex: 1; } .aud-filters { align-items: stretch; flex-direction: column; } .aud-filters .el-input, .aud-filters .el-select, .aud-filters .el-button { width: 100%; max-width: none; } .aud-footer { align-items: flex-start; flex-direction: column; } }
</style>
