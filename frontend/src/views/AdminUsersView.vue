<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCw, ShieldCheck, UserCheck, UserRoundX } from 'lucide-vue-next'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const users = ref([])
const loading = ref(false)
const updatingId = ref(null)
const error = ref('')

const roleOptions = [
  { value: 'USER', label: '患者用户' },
  { value: 'KNOWLEDGE_EDITOR', label: '知识编辑' },
  { value: 'REVIEWER', label: '医学审核员' },
  { value: 'DOCTOR', label: '医生顾问' },
  { value: 'AUDITOR', label: '审计员' },
  { value: 'ADMIN', label: '系统管理员' },
]

const activeCount = computed(() => users.value.filter((user) => user.active).length)
const roleLabel = (role) => roleOptions.find((item) => item.value === role)?.label || role

function payloadOf(response) {
  return response?.data?.data ?? response?.data
}

function errorText(errorValue, fallback) {
  const raw = errorValue?.response?.data?.error || errorValue?.response?.data?.detail
  return typeof raw === 'string' && raw.trim() ? raw : fallback
}

async function loadUsers() {
  loading.value = true
  error.value = ''
  try {
    const response = await client.get('/admin/users')
    const payload = payloadOf(response)
    users.value = Array.isArray(payload) ? payload : []
  } catch (errorValue) {
    error.value = errorText(errorValue, '用户列表暂时无法加载。')
  } finally {
    loading.value = false
  }
}

async function updateUser(user, patch) {
  if (!user?.id || updatingId.value) return
  const next = { ...patch }
  const isSelf = user.username === auth.username
  if (isSelf && next.active === false) {
    ElMessage.warning('当前账号不能在自己的会话中被禁用。')
    return
  }
  try {
    await ElMessageBox.confirm(
      next.role ? `确认将“${user.username}”调整为${roleLabel(next.role)}？` : `确认${next.active ? '启用' : '禁用'}“${user.username}”？`,
      '确认权限变更',
      { type: next.active === false ? 'warning' : 'info', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  updatingId.value = user.id
  try {
    const response = await client.patch(`/admin/users/${user.id}`, next)
    const updated = payloadOf(response)
    const index = users.value.findIndex((item) => item.id === user.id)
    if (index >= 0 && updated) users.value[index] = updated
    ElMessage.success('权限已更新')
  } catch (errorValue) {
    ElMessage.error(errorText(errorValue, '权限更新失败，请稍后重试。'))
    await loadUsers()
  } finally {
    updatingId.value = null
  }
}

onMounted(loadUsers)
</script>

<template>
  <div class="usr-page">
    <header class="usr-header">
      <div>
        <span class="usr-eyebrow">ACCESS CONTROL</span>
        <h1>用户权限</h1>
        <p>按职责分离访问知识库、监控和审计数据；敏感操作由服务端再次校验。</p>
      </div>
      <el-button plain :loading="loading" aria-label="刷新用户列表" @click="loadUsers">
        <RefreshCw :size="16" aria-hidden="true" />
        刷新
      </el-button>
    </header>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <section class="usr-summary" aria-label="用户统计">
      <div><span>账号总数</span><strong>{{ users.length }}</strong></div>
      <div><span>已启用</span><strong>{{ activeCount }}</strong></div>
      <div><span>当前操作者</span><strong>{{ auth.username || '管理员' }}</strong></div>
    </section>

    <section class="usr-panel">
      <div class="usr-panel-heading">
        <div>
          <span class="usr-eyebrow">ROLE MATRIX</span>
          <h2>账号与职责</h2>
        </div>
        <small>不会展示密码、令牌或问诊正文</small>
      </div>

      <el-table v-loading="loading" :data="users" row-key="id" class="usr-table">
        <el-table-column prop="username" label="账号" min-width="150">
          <template #default="scope">
            <div class="usr-name"><span class="usr-avatar"><UserCheck :size="15" /></span><strong>{{ scope.row.username }}</strong></div>
          </template>
        </el-table-column>
        <el-table-column label="职责" min-width="190">
          <template #default="scope">
            <el-select
              :model-value="scope.row.role"
              size="small"
              :disabled="updatingId === scope.row.id || scope.row.username === auth.username"
              :aria-label="`修改 ${scope.row.username} 的职责`"
              @change="(role) => updateUser(scope.row, { role })"
            >
              <el-option v-for="role in roleOptions" :key="role.value" :label="role.label" :value="role.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="scope">
            <div class="usr-status">
              <el-switch
                :model-value="scope.row.active"
                :loading="updatingId === scope.row.id"
                :disabled="scope.row.username === auth.username"
                :aria-label="`${scope.row.active ? '禁用' : '启用'} ${scope.row.username}`"
                @change="(active) => updateUser(scope.row, { active })"
              />
              <el-tag size="small" :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? '已启用' : '已禁用' }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="190" />
        <el-table-column label="安全提示" min-width="170">
          <template #default="scope">
            <span class="usr-note" v-if="scope.row.username === auth.username"><ShieldCheck :size="14" /> 当前会话</span>
            <span class="usr-note usr-note-muted" v-else-if="!scope.row.active"><UserRoundX :size="14" /> 登录已阻断</span>
            <span class="usr-note usr-note-muted" v-else>{{ roleLabel(scope.row.role) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.usr-page { width: min(100%, 1180px); margin: 0 auto; display: grid; gap: 16px; color: var(--text-primary); }
.usr-header, .usr-panel-heading, .usr-name, .usr-status, .usr-note { display: flex; align-items: center; }
.usr-header { justify-content: space-between; gap: 20px; padding: 4px 2px 8px; }
.usr-eyebrow { color: var(--primary); font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.usr-header h1, .usr-panel-heading h2 { margin: 5px 0 4px; letter-spacing: 0; }
.usr-header h1 { font-size: 25px; }
.usr-header p, .usr-panel-heading small { margin: 0; color: var(--text-muted); font-size: 13px; }
.usr-header .el-button { display: inline-flex; align-items: center; gap: 7px; }
.usr-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.usr-summary > div, .usr-panel { border: 1px solid var(--border-default); border-radius: var(--radius-lg); background: var(--glass-surface, var(--surface-elevated)); box-shadow: var(--shadow-card); }
.usr-summary > div { display: grid; gap: 5px; padding: 15px 17px; }
.usr-summary span { color: var(--text-muted); font-size: 12px; }
.usr-summary strong { font-size: 23px; }
.usr-panel { padding: 19px; overflow: hidden; }
.usr-panel-heading { justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.usr-panel-heading h2 { font-size: 17px; }
.usr-name { gap: 9px; }
.usr-avatar { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 7px; color: var(--primary); background: var(--primary-soft); }
.usr-status { gap: 9px; }
.usr-note { gap: 5px; color: var(--success); font-size: 12px; }
.usr-note-muted { color: var(--text-muted); }
.usr-table { --el-table-bg-color: transparent; --el-table-tr-bg-color: transparent; --el-table-header-bg-color: var(--surface-muted); --el-table-border-color: var(--border-subtle); }
@media (max-width: 720px) { .usr-header { align-items: flex-start; flex-direction: column; } .usr-summary { grid-template-columns: 1fr; } .usr-panel { padding: 13px; } .usr-panel-heading { align-items: flex-start; flex-direction: column; } }
</style>
