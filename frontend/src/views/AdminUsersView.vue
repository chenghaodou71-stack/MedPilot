<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  KeyRound,
  RefreshCw,
  ShieldCheck,
  Trash2,
  UserCheck,
  UserPlus,
  UserRoundX,
} from 'lucide-vue-next'
import client from '../api/client'
import { adminUserErrorText, validAdminPassword, validAdminUsername } from '../lib/adminUsers'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const users = ref([])
const loading = ref(false)
const updatingId = ref(null)
const deletingId = ref(null)
const creating = ref(false)
const resetting = ref(false)
const listError = ref('')
const operationError = ref('')
const createVisible = ref(false)
const resetVisible = ref(false)
const resetTarget = ref(null)
const createFormRef = ref(null)
const resetFormRef = ref(null)

const roleOptions = [
  { value: 'USER', label: '患者用户' },
  { value: 'KNOWLEDGE_EDITOR', label: '知识编辑' },
  { value: 'REVIEWER', label: '医学审核员' },
  { value: 'DOCTOR', label: '医生顾问' },
  { value: 'AUDITOR', label: '审计员' },
  { value: 'ADMIN', label: '系统管理员' },
]

const createForm = reactive({ username: '', password: '', confirmPassword: '', role: 'USER' })
const resetForm = reactive({ password: '', confirmPassword: '' })

function validateUsername(_rule, value, callback) {
  if (validAdminUsername(value)) callback()
  else callback(new Error('请输入 3-64 位小写字母、数字、点、下划线或连字符'))
}

function validatePassword(_rule, value, callback) {
  if (validAdminPassword(value)) callback()
  else callback(new Error('密码长度必须为 10-128 个字符'))
}

function validateCreateConfirmation(_rule, value, callback) {
  if (value === createForm.password) callback()
  else callback(new Error('两次输入的密码不一致'))
}

function validateResetConfirmation(_rule, value, callback) {
  if (value === resetForm.password) callback()
  else callback(new Error('两次输入的密码不一致'))
}

const createRules = {
  username: [{ validator: validateUsername, trigger: 'blur' }],
  password: [{ validator: validatePassword, trigger: 'blur' }],
  confirmPassword: [{ validator: validateCreateConfirmation, trigger: 'blur' }],
  role: [{ required: true, message: '请选择账号职责', trigger: 'change' }],
}

const resetRules = {
  password: [{ validator: validatePassword, trigger: 'blur' }],
  confirmPassword: [{ validator: validateResetConfirmation, trigger: 'blur' }],
}

const activeCount = computed(() => users.value.filter((user) => user.active).length)
const roleLabel = (role) => roleOptions.find((item) => item.value === role)?.label || role

function payloadOf(response) {
  return response?.data?.data ?? response?.data
}

function showOperationError(errorValue, fallback) {
  operationError.value = adminUserErrorText(errorValue, fallback)
  ElMessage.error(operationError.value)
}

function formatCreatedAt(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value || '未记录'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

async function loadUsers() {
  loading.value = true
  listError.value = ''
  try {
    const response = await client.get('/admin/users')
    const payload = payloadOf(response)
    users.value = Array.isArray(payload) ? payload : []
  } catch (errorValue) {
    listError.value = adminUserErrorText(errorValue, '用户列表暂时无法加载。')
  } finally {
    loading.value = false
  }
}

async function updateUser(user, patch) {
  if (!user?.id || updatingId.value) return
  const next = { ...patch }
  const isSelf = user.username === auth.username
  if (isSelf && (next.active === false || next.role)) {
    operationError.value = '不能禁用当前账号或变更自己的管理员职责。'
    ElMessage.warning(operationError.value)
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

  operationError.value = ''
  updatingId.value = user.id
  try {
    const response = await client.patch(`/admin/users/${user.id}`, next)
    const updated = payloadOf(response)
    const index = users.value.findIndex((item) => item.id === user.id)
    if (index >= 0 && updated) users.value[index] = updated
    ElMessage.success('权限已更新')
  } catch (errorValue) {
    showOperationError(errorValue, '权限更新失败，请稍后重试。')
    await loadUsers()
  } finally {
    updatingId.value = null
  }
}

function openCreate() {
  operationError.value = ''
  createVisible.value = true
}

function resetCreateForm() {
  Object.assign(createForm, { username: '', password: '', confirmPassword: '', role: 'USER' })
  createFormRef.value?.clearValidate()
}

async function createUser() {
  if (creating.value) return
  try {
    await createFormRef.value?.validate()
  } catch {
    return
  }

  creating.value = true
  operationError.value = ''
  try {
    await client.post('/admin/users', {
      username: createForm.username.trim(),
      password: createForm.password,
      role: createForm.role,
    })
    ElMessage.success(`账号“${createForm.username.trim()}”已创建`)
    createVisible.value = false
    resetCreateForm()
    await loadUsers()
  } catch (errorValue) {
    showOperationError(errorValue, '创建用户失败，请稍后重试。')
  } finally {
    creating.value = false
  }
}

function openPasswordReset(user) {
  operationError.value = ''
  resetTarget.value = user
  Object.assign(resetForm, { password: '', confirmPassword: '' })
  resetVisible.value = true
}

function resetPasswordForm() {
  Object.assign(resetForm, { password: '', confirmPassword: '' })
  resetTarget.value = null
  resetFormRef.value?.clearValidate()
}

async function resetPassword() {
  if (!resetTarget.value?.id || resetting.value) return
  try {
    await resetFormRef.value?.validate()
  } catch {
    return
  }

  resetting.value = true
  operationError.value = ''
  try {
    await client.patch(`/admin/users/${resetTarget.value.id}`, { password: resetForm.password })
    ElMessage.success(`账号“${resetTarget.value.username}”的密码已重置，旧会话已失效`)
    resetVisible.value = false
    resetPasswordForm()
  } catch (errorValue) {
    showOperationError(errorValue, '密码重置失败，请稍后重试。')
  } finally {
    resetting.value = false
  }
}

async function deleteUser(user) {
  if (!user?.id || deletingId.value) return
  if (user.username === auth.username) {
    operationError.value = '不能删除当前登录账号。'
    ElMessage.warning(operationError.value)
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除“${user.username}”后无法恢复；如有关联问诊记录，服务端将拒绝删除。`,
      '删除用户',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  deletingId.value = user.id
  operationError.value = ''
  try {
    await client.delete(`/admin/users/${user.id}`)
    users.value = users.value.filter((item) => item.id !== user.id)
    ElMessage.success(`账号“${user.username}”已删除`)
  } catch (errorValue) {
    showOperationError(errorValue, '删除用户失败，请稍后重试。')
  } finally {
    deletingId.value = null
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
      <div class="usr-header-actions">
        <el-button type="primary" @click="openCreate">
          <UserPlus :size="16" aria-hidden="true" />
          创建用户
        </el-button>
        <el-button plain :loading="loading" aria-label="刷新用户列表" @click="loadUsers">
          <RefreshCw :size="16" aria-hidden="true" />
          刷新
        </el-button>
      </div>
    </header>

    <el-alert v-if="listError" type="error" :closable="false" show-icon :title="listError" />
    <el-alert
      v-if="operationError"
      type="warning"
      show-icon
      :title="operationError"
      @close="operationError = ''"
    />

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
        <el-table-column label="创建时间" min-width="190">
          <template #default="scope">{{ formatCreatedAt(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="安全提示" min-width="170">
          <template #default="scope">
            <span class="usr-note" v-if="scope.row.username === auth.username"><ShieldCheck :size="14" /> 当前会话</span>
            <span class="usr-note usr-note-muted" v-else-if="!scope.row.active"><UserRoundX :size="14" /> 登录已阻断</span>
            <span class="usr-note usr-note-muted" v-else>{{ roleLabel(scope.row.role) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="126" fixed="right">
          <template #default="scope">
            <div class="usr-row-actions">
              <el-tooltip content="重置密码" placement="top">
                <el-button
                  text
                  circle
                  type="primary"
                  :aria-label="`重置 ${scope.row.username} 的密码`"
                  @click="openPasswordReset(scope.row)"
                >
                  <KeyRound :size="16" aria-hidden="true" />
                </el-button>
              </el-tooltip>
              <el-tooltip :content="scope.row.username === auth.username ? '不能删除当前账号' : '删除用户'" placement="top">
                <el-button
                  text
                  circle
                  type="danger"
                  :disabled="scope.row.username === auth.username"
                  :loading="deletingId === scope.row.id"
                  :aria-label="`删除用户 ${scope.row.username}`"
                  @click="deleteUser(scope.row)"
                >
                  <Trash2 v-if="deletingId !== scope.row.id" :size="16" aria-hidden="true" />
                </el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog
      v-model="createVisible"
      title="创建用户"
      width="min(500px, 94vw)"
      destroy-on-close
      :close-on-click-modal="!creating"
      :close-on-press-escape="!creating"
      @closed="resetCreateForm"
    >
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="账号" prop="username">
          <el-input v-model="createForm.username" maxlength="64" autocomplete="off" placeholder="例如 reviewer-01" />
        </el-form-item>
        <el-form-item label="职责" prop="role">
          <el-select v-model="createForm.role" class="usr-form-control">
            <el-option v-for="role in roleOptions" :key="`create-${role.value}`" :label="role.label" :value="role.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input v-model="createForm.password" type="password" show-password maxlength="128" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="createForm.confirmPassword" type="password" show-password maxlength="128" autocomplete="new-password" @keyup.enter="createUser" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="creating" @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createUser">
          <UserPlus v-if="!creating" :size="16" aria-hidden="true" />
          {{ creating ? '正在创建' : '创建账号' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="resetVisible"
      :title="`重置 ${resetTarget?.username || ''} 的密码`"
      width="min(500px, 94vw)"
      destroy-on-close
      :close-on-click-modal="!resetting"
      :close-on-press-escape="!resetting"
      @closed="resetPasswordForm"
    >
      <el-alert class="usr-dialog-alert" type="warning" :closable="false" show-icon title="密码更新后，该账号此前签发的登录令牌会立即失效。" />
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-position="top">
        <el-form-item label="新密码" prop="password">
          <el-input v-model="resetForm.password" type="password" show-password maxlength="128" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="resetForm.confirmPassword" type="password" show-password maxlength="128" autocomplete="new-password" @keyup.enter="resetPassword" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="resetting" @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="resetPassword">
          <KeyRound v-if="!resetting" :size="16" aria-hidden="true" />
          {{ resetting ? '正在重置' : '确认重置' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.usr-page { width: min(100%, 1180px); margin: 0 auto; display: grid; gap: 16px; color: var(--text-primary); }
.usr-header, .usr-header-actions, .usr-panel-heading, .usr-name, .usr-status, .usr-note, .usr-row-actions { display: flex; align-items: center; }
.usr-header { justify-content: space-between; gap: 20px; padding: 4px 2px 8px; }
.usr-header-actions { gap: 8px; flex: 0 0 auto; }
.usr-eyebrow { color: var(--primary); font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.usr-header h1, .usr-panel-heading h2 { margin: 5px 0 4px; letter-spacing: 0; }
.usr-header h1 { font-size: 25px; }
.usr-header p, .usr-panel-heading small { margin: 0; color: var(--text-muted); font-size: 13px; }
.usr-header .el-button, :deep(.el-dialog__footer .el-button) { display: inline-flex; align-items: center; gap: 7px; }
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
.usr-row-actions { gap: 2px; }
.usr-form-control { width: 100%; }
.usr-dialog-alert { margin-bottom: 16px; }
.usr-table { --el-table-bg-color: transparent; --el-table-tr-bg-color: transparent; --el-table-header-bg-color: var(--surface-muted); --el-table-border-color: var(--border-subtle); }
@media (max-width: 720px) { .usr-header { align-items: flex-start; flex-direction: column; } .usr-header-actions { width: 100%; } .usr-header-actions .el-button { flex: 1; } .usr-summary { grid-template-columns: 1fr; } .usr-panel { padding: 13px; } .usr-panel-heading { align-items: flex-start; flex-direction: column; } }
</style>
