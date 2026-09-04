<script setup>
import { ArrowLeft, Home } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

function goBack() {
  if (window.history.length > 1) router.back()
  else router.replace(auth.isAuthenticated ? auth.homePath : '/login')
}
</script>

<template>
  <main class="not-found" aria-labelledby="not-found-title">
    <p>404</p>
    <h1 id="not-found-title">页面不存在</h1>
    <span>当前地址无对应功能，可能已被移动或输入有误。</span>
    <div class="not-found__actions">
      <el-button plain @click="goBack"><ArrowLeft :size="16" />返回上一页</el-button>
      <el-button type="primary" @click="router.replace(auth.isAuthenticated ? auth.homePath : '/login')">
        <Home :size="16" />返回工作台
      </el-button>
    </div>
  </main>
</template>

<style scoped>
.not-found {
  width: min(100%, 720px);
  min-height: min(560px, calc(100vh - 160px));
  margin: 0 auto;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 12px;
  padding: 32px 20px;
  color: var(--text-primary);
  text-align: center;
}

.not-found > p {
  margin: 0;
  color: var(--primary);
  font-size: 52px;
  font-weight: 750;
  line-height: 1;
}

.not-found h1 {
  margin: 4px 0 0;
  font-size: 28px;
  letter-spacing: 0;
}

.not-found > span {
  color: var(--text-muted);
  font-size: 14px;
}

.not-found__actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.not-found__actions .el-button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

@media (max-width: 480px) {
  .not-found__actions { width: 100%; flex-direction: column; }
  .not-found__actions .el-button { width: 100%; margin: 0; justify-content: center; }
}
</style>
