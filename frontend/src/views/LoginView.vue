<script setup>
import { defineAsyncComponent, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  BrainCircuit,
  Check,
  Database,
  Eye,
  EyeOff,
  LoaderCircle,
  LockKeyhole,
  LogIn,
  Network,
  UserRound,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { resolvePostLoginTarget } from '../lib/navigation'

const MedicalNetworkScene = defineAsyncComponent({
  loader: () => import('../components/MedicalNetworkScene.vue'),
  delay: 0,
  timeout: 15_000,
})

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const accessOpen = ref(false)
const username = ref('')
const password = ref('')
const passwordVisible = ref(false)
const rememberUsername = ref(true)
const error = ref('')
const loading = ref(false)
const usernameInput = ref(null)
const passwordInput = ref(null)

function clearError() {
  if (error.value) error.value = ''
}

function toggleAccessChain() {
  if (loading.value) return
  accessOpen.value = !accessOpen.value
  error.value = ''
}

function togglePasswordVisibility() {
  passwordVisible.value = !passwordVisible.value
  requestAnimationFrame(() => passwordInput.value?.focus())
}

function persistRememberedUsername(value) {
  try {
    if (rememberUsername.value) localStorage.setItem('rememberedUsername', value)
    else localStorage.removeItem('rememberedUsername')
  } catch {
    // Browser storage can be unavailable without invalidating a successful login.
  }
}

async function submit() {
  if (loading.value) return

  error.value = ''
  const normalizedUsername = username.value.trim()
  if (!normalizedUsername || !password.value) {
    error.value = '请输入账号和密码。'
    requestAnimationFrame(() => {
      if (!normalizedUsername) usernameInput.value?.focus()
      else passwordInput.value?.focus()
    })
    return
  }

  loading.value = true
  try {
    await auth.login(normalizedUsername, password.value)
    persistRememberedUsername(normalizedUsername)
    await router.push(resolvePostLoginTarget(route.query.redirect, auth.homePath))
  } catch (loginError) {
    error.value = loginError.response?.data?.error || loginError.message || '登录失败，请检查账号信息。'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  try {
    username.value = localStorage.getItem('rememberedUsername') || ''
  } catch {
    username.value = ''
  }
})
</script>

<template>
  <main class="login-page" :class="{ 'is-access-open': accessOpen }">
    <div class="login-scene-layer" aria-hidden="true">
      <MedicalNetworkScene class="login-network-scene" />
    </div>

    <div class="login-depth-grid" aria-hidden="true" />
    <div class="login-scanline" aria-hidden="true" />

    <section class="login-workspace" aria-labelledby="login-title">
      <div class="login-visual-copy">
        <span class="login-kicker">MULTI-AGENT TRIAGE SYSTEM / 04</span>
        <h1 id="login-title">多智能体协同<br /><strong>辅助分诊</strong></h1>
        <p>融合本地大语言模型与医学知识库，构建可信、可追溯的辅助分诊工作流。</p>

        <div class="login-capabilities" aria-label="系统能力">
          <span><Database :size="14" aria-hidden="true" />信息采集</span>
          <span><BrainCircuit :size="14" aria-hidden="true" />知识检索</span>
          <span><Network :size="14" aria-hidden="true" />辅助分诊</span>
        </div>
      </div>

      <div class="login-access-chain">
        <svg
          v-if="accessOpen"
          class="login-chain-rail is-desktop"
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <polyline class="rail-shadow" points="29,89 42,75 60,54 80,32" />
          <polyline class="rail-core" points="29,89 42,75 60,54 80,32" />
          <circle cx="42" cy="75" r="0.65" />
          <circle cx="60" cy="54" r="0.65" />
          <circle cx="80" cy="32" r="0.65" />
        </svg>

        <svg
          v-if="accessOpen"
          class="login-chain-rail is-mobile"
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <polyline class="rail-shadow" points="13,91 21,82 36,69 87,56" />
          <polyline class="rail-core" points="13,91 21,82 36,69 87,56" />
          <circle cx="21" cy="82" r="0.8" />
          <circle cx="36" cy="69" r="0.8" />
          <circle cx="87" cy="56" r="0.8" />
        </svg>

        <button
          class="login-nexus-trigger"
          type="button"
          :aria-expanded="accessOpen"
          aria-controls="login-chain-form"
          :aria-label="accessOpen ? '收起登录通道' : '展开登录通道'"
          :title="accessOpen ? '收起登录通道' : '展开登录通道'"
          :disabled="loading"
          @click="toggleAccessChain"
        >
          <span class="login-nexus-radar" aria-hidden="true" />
          <Network :size="23" :stroke-width="1.65" aria-hidden="true" />
          <small aria-hidden="true">登录</small>
        </button>

        <form
          v-if="accessOpen"
          id="login-chain-form"
          class="login-chain-form"
          aria-label="登录凭据"
          :aria-busy="loading"
          @submit.prevent="submit"
        >
          <h2 class="login-sr-only">登录工作台</h2>

          <div class="login-entry login-account-entry" data-entry="account">
            <label class="login-sr-only" for="login-username">账号</label>
            <UserRound class="login-entry-icon" :size="19" :stroke-width="1.7" aria-hidden="true" />
            <input
              id="login-username"
              ref="usernameInput"
              v-model="username"
              class="login-entry-input"
              name="username"
              autocomplete="username"
              placeholder="输入系统账号"
              :disabled="loading"
              @input="clearError"
            />
            <label class="login-remember-control" title="记住账号">
              <input v-model="rememberUsername" type="checkbox" :disabled="loading" aria-label="记住账号" />
              <Check :size="10" :stroke-width="2.4" aria-hidden="true" />
              <span>记住</span>
            </label>
          </div>

          <div class="login-entry login-password-entry" data-entry="password">
            <label class="login-sr-only" for="login-password">密码</label>
            <LockKeyhole class="login-entry-icon" :size="18" :stroke-width="1.7" aria-hidden="true" />
            <input
              id="login-password"
              ref="passwordInput"
              v-model="password"
              class="login-entry-input"
              name="password"
              :type="passwordVisible ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="输入访问密码"
              :disabled="loading"
              @input="clearError"
            />
            <button
              class="login-password-toggle"
              type="button"
              :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
              :title="passwordVisible ? '隐藏密码' : '显示密码'"
              :disabled="loading"
              @click="togglePasswordVisibility"
            >
              <EyeOff v-if="passwordVisible" :size="16" aria-hidden="true" />
              <Eye v-else :size="16" aria-hidden="true" />
            </button>
          </div>

          <button
            class="login-submit-orb"
            type="submit"
            aria-label="登录"
            title="登录"
            :disabled="loading"
          >
            <LoaderCircle v-if="loading" class="login-loading-icon" :size="20" aria-hidden="true" />
            <LogIn v-else :size="20" :stroke-width="1.8" aria-hidden="true" />
            <span>{{ loading ? '验证' : '登录' }}</span>
          </button>
        </form>

        <Transition name="login-status">
          <p
            v-if="error || loading"
            class="login-chain-status"
            :class="{ 'is-error': error, 'is-loading': loading && !error }"
            :role="error ? 'alert' : 'status'"
            :title="error || '正在验证访问凭据'"
          >
            <i aria-hidden="true" />
            <span>{{ error || '正在验证访问凭据' }}</span>
          </p>
        </Transition>
      </div>

    </section>
  </main>
</template>

<style scoped>
.login-page {
  --login-bg: #030712;
  --login-text: #f2f7ff;
  --login-muted: #9fb0cf;
  --login-blue: #4c9dff;
  --login-cyan: #4ce3dc;
  --login-violet: #a788ff;

  position: relative;
  min-height: 100dvh;
  overflow: hidden;
  background:
    linear-gradient(112deg, rgba(13, 33, 80, 0.7) 0%, rgba(4, 10, 25, 0.4) 42%, rgba(20, 8, 46, 0.48) 100%),
    var(--login-bg);
  color: var(--login-text);
  color-scheme: dark;
  font-family: Inter, "Microsoft YaHei", "PingFang SC", sans-serif;
  isolation: isolate;
}

.login-page::before,
.login-page::after {
  position: absolute;
  inset: 0;
  pointer-events: none;
  content: '';
}

.login-page::before {
  z-index: 1;
  background:
    linear-gradient(90deg, rgba(2, 6, 17, 0.25), transparent 36%, transparent 74%, rgba(2, 5, 15, 0.35)),
    linear-gradient(180deg, rgba(2, 5, 14, 0.08), transparent 64%, rgba(2, 5, 14, 0.72));
}

.login-page::after {
  z-index: 1;
  box-shadow: inset 0 0 180px 36px rgba(0, 0, 0, 0.56);
}

.login-scene-layer,
.login-depth-grid,
.login-scanline {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.login-scene-layer {
  z-index: 0;
}

.login-network-scene {
  inset: 0;
  min-height: 100%;
}

.login-depth-grid {
  z-index: 1;
  opacity: 0.16;
  background-image:
    linear-gradient(rgba(111, 174, 255, 0.24) 1px, transparent 1px),
    linear-gradient(90deg, rgba(111, 174, 255, 0.19) 1px, transparent 1px);
  background-size: 72px 72px;
  mask-image: linear-gradient(90deg, transparent 2%, black 25%, transparent 72%);
  transform: perspective(900px) rotateX(67deg) translateY(48%);
  transform-origin: bottom;
}

.login-scanline {
  z-index: 2;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(75, 183, 255, 0.5), rgba(139, 110, 255, 0.42), transparent);
  box-shadow: 0 0 18px rgba(75, 183, 255, 0.36);
  opacity: 0.48;
  animation: login-scan 8s linear infinite;
}

.login-workspace {
  position: relative;
  z-index: 3;
  box-sizing: border-box;
  width: min(100%, 1600px);
  min-height: 100dvh;
  margin: 0 auto;
  padding: 34px 48px 30px;
  display: grid;
  grid-template-rows: 1fr;
}

.login-capabilities {
  display: flex;
  align-items: center;
}

.login-chain-status i {
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--login-cyan);
  box-shadow: 0 0 10px rgba(76, 227, 220, 0.9);
}

.login-visual-copy {
  position: relative;
  z-index: 4;
  width: min(46%, 540px);
  align-self: center;
  margin-left: 3%;
  padding-bottom: 104px;
  animation: login-rise 0.85s 0.08s ease-out both;
}

.login-kicker {
  display: block;
  margin-bottom: 15px;
  color: #73bfff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
}

.login-visual-copy h1 {
  margin: 0;
  color: #eef6ff;
  font-size: 46px;
  font-weight: 600;
  line-height: 1.17;
  letter-spacing: 0;
  text-shadow: 0 10px 35px rgba(0, 0, 0, 0.55);
}

.login-visual-copy h1 strong {
  color: #ffffff;
  font-weight: 750;
}

.login-visual-copy > p {
  max-width: 470px;
  margin: 18px 0 0;
  color: var(--login-muted);
  font-size: 14px;
  line-height: 1.8;
}

.login-capabilities {
  flex-wrap: wrap;
  gap: 9px;
  margin-top: 25px;
}

.login-capabilities span {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  box-sizing: border-box;
  padding: 6px 10px;
  border: 1px solid rgba(125, 180, 255, 0.26);
  border-radius: 5px;
  background: rgba(7, 18, 42, 0.48);
  color: #c7d7ef;
  font-size: 12px;
  backdrop-filter: blur(9px);
  -webkit-backdrop-filter: blur(9px);
}

.login-capabilities span:nth-child(2) {
  color: #cbbcff;
}

.login-capabilities span:nth-child(3) {
  color: #a9eee8;
}

.login-access-chain {
  position: absolute;
  z-index: 7;
  inset: 0;
  pointer-events: none;
}

.login-chain-rail {
  position: absolute;
  z-index: 0;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
  pointer-events: none;
}

.login-chain-rail.is-mobile {
  display: none;
}

.login-chain-rail polyline {
  fill: none;
  vector-effect: non-scaling-stroke;
}

.login-chain-rail .rail-shadow {
  stroke: rgba(56, 161, 255, 0.16);
  stroke-width: 8;
  filter: blur(5px);
}

.login-chain-rail .rail-core {
  stroke: rgba(104, 205, 255, 0.72);
  stroke-width: 1.1;
  stroke-dasharray: 8 6;
  animation: login-rail-draw 1.05s ease-out both, login-rail-flow 3.2s 1.05s linear infinite;
}

.login-chain-rail circle {
  fill: #68e3dd;
  stroke: rgba(141, 224, 255, 0.62);
  stroke-width: 0.35;
  vector-effect: non-scaling-stroke;
  filter: drop-shadow(0 0 4px rgba(76, 227, 220, 0.9));
  animation: login-node-flare 1.8s ease-in-out infinite;
}

.login-nexus-trigger,
.login-submit-orb {
  position: absolute;
  z-index: 4;
  display: grid;
  place-items: center;
  box-sizing: border-box;
  margin: 0;
  padding: 0;
  border-radius: 50%;
  color: #e9f7ff;
  cursor: pointer;
  pointer-events: auto;
  -webkit-tap-highlight-color: transparent;
}

.login-nexus-trigger {
  left: 27%;
  bottom: 7.5%;
  width: 72px;
  height: 72px;
  border: 1px solid rgba(91, 204, 255, 0.72);
  background:
    radial-gradient(circle at 38% 34%, rgba(121, 224, 255, 0.45), transparent 27%),
    rgba(8, 30, 61, 0.72);
  box-shadow:
    0 0 0 8px rgba(41, 138, 216, 0.08),
    0 0 34px rgba(48, 170, 255, 0.42),
    inset 0 0 22px rgba(88, 202, 255, 0.2);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.login-nexus-trigger::before,
.login-nexus-trigger::after {
  position: absolute;
  pointer-events: none;
  content: '';
  border-radius: 50%;
}

.login-nexus-trigger::before {
  inset: 7px;
  border: 1px dashed rgba(120, 214, 255, 0.32);
  animation: login-nexus-turn 9s linear infinite;
}

.login-nexus-trigger::after {
  inset: -12px;
  border: 1px solid rgba(76, 227, 220, 0.14);
  animation: login-nexus-breathe 2.8s ease-in-out infinite;
}

.login-nexus-trigger:hover,
.login-nexus-trigger:focus-visible,
.is-access-open .login-nexus-trigger {
  border-color: rgba(116, 229, 255, 0.94);
  box-shadow:
    0 0 0 9px rgba(41, 138, 216, 0.1),
    0 0 44px rgba(49, 185, 255, 0.58),
    inset 0 0 25px rgba(88, 225, 255, 0.24);
}

.login-nexus-trigger:focus-visible,
.login-submit-orb:focus-visible {
  outline: 2px solid rgba(202, 241, 255, 0.96);
  outline-offset: 5px;
}

.login-nexus-trigger:active,
.login-submit-orb:active {
  transform: scale(0.96);
}

.login-nexus-trigger:disabled,
.login-submit-orb:disabled {
  cursor: wait;
}

.login-nexus-trigger > svg {
  position: relative;
  z-index: 2;
  transform: translateY(-4px);
}

.login-nexus-trigger small {
  position: absolute;
  z-index: 2;
  bottom: 11px;
  color: #86cfff;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0;
}

.login-nexus-radar {
  position: absolute;
  inset: 18px;
  border-radius: 50%;
  background: rgba(72, 171, 255, 0.13);
  box-shadow: 0 0 18px rgba(72, 190, 255, 0.26);
}

.login-chain-form {
  position: absolute;
  z-index: 2;
  inset: 0;
  pointer-events: none;
}

.login-entry {
  --entry-open-width: 260px;

  position: absolute;
  z-index: 3;
  width: 56px;
  height: 56px;
  overflow: hidden;
  box-sizing: border-box;
  border: 1px solid rgba(105, 190, 255, 0.48);
  border-radius: 28px;
  background:
    radial-gradient(circle at 27px 24px, rgba(95, 196, 255, 0.2), transparent 25px),
    rgba(7, 18, 40, 0.7);
  color: #a9d9ff;
  box-shadow:
    0 16px 36px rgba(0, 0, 0, 0.3),
    0 0 24px rgba(62, 150, 255, 0.18),
    inset 0 0 18px rgba(85, 188, 255, 0.08);
  backdrop-filter: blur(18px) saturate(135%);
  -webkit-backdrop-filter: blur(18px) saturate(135%);
  pointer-events: auto;
  transition:
    width 0.34s cubic-bezier(0.2, 0.76, 0.2, 1),
    border-color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.login-account-entry {
  left: 40%;
  bottom: 22%;
  animation: login-chain-node-in 0.55s 0.1s cubic-bezier(0.18, 0.8, 0.2, 1) both;
}

.login-password-entry {
  left: 58%;
  bottom: 43%;
  color: #c5b5ff;
  border-color: rgba(158, 133, 255, 0.5);
  background:
    radial-gradient(circle at 27px 24px, rgba(157, 126, 255, 0.21), transparent 25px),
    rgba(12, 16, 42, 0.72);
  animation: login-chain-node-in 0.55s 0.24s cubic-bezier(0.18, 0.8, 0.2, 1) both;
}

.login-entry::after {
  position: absolute;
  inset: 5px auto 5px 5px;
  z-index: 0;
  width: 44px;
  border: 1px solid rgba(121, 207, 255, 0.16);
  border-radius: 50%;
  content: '';
  pointer-events: none;
}

.login-password-entry::after {
  border-color: rgba(181, 155, 255, 0.17);
}

.login-entry:focus-within {
  width: var(--entry-open-width);
  border-color: rgba(103, 209, 255, 0.9);
  background: rgba(8, 22, 48, 0.9);
  box-shadow:
    0 18px 42px rgba(0, 0, 0, 0.34),
    0 0 30px rgba(61, 173, 255, 0.3),
    inset 0 0 22px rgba(82, 194, 255, 0.1);
}

.login-password-entry:focus-within {
  border-color: rgba(174, 148, 255, 0.88);
  box-shadow:
    0 18px 42px rgba(0, 0, 0, 0.34),
    0 0 30px rgba(144, 105, 255, 0.28),
    inset 0 0 22px rgba(158, 126, 255, 0.1);
}

.login-entry-icon {
  position: absolute;
  z-index: 3;
  top: 50%;
  left: 18px;
  pointer-events: none;
  transform: translateY(-50%);
}

.login-entry-input {
  position: absolute;
  z-index: 1;
  inset: 0;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  margin: 0;
  padding: 0 46px 0 56px;
  border: 0;
  outline: 0;
  background: transparent;
  color: #eef7ff;
  caret-color: #73d5ff;
  font: inherit;
  font-size: 13px;
  letter-spacing: 0;
}

.login-account-entry .login-entry-input {
  padding-right: 74px;
}

.login-entry-input::placeholder {
  color: #7187aa;
  opacity: 0;
  transition: opacity 0.16s ease;
}

.login-entry:focus-within .login-entry-input::placeholder,
.login-entry:focus-within .login-password-toggle,
.login-entry:focus-within .login-remember-control {
  opacity: 1;
}

.login-entry-input:disabled {
  cursor: wait;
}

.login-password-toggle {
  position: absolute;
  z-index: 3;
  top: 50%;
  right: 12px;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #8e82bd;
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
  transform: translateY(-50%);
  transition: color 0.2s ease, opacity 0.16s ease;
}

.login-password-entry:focus-within .login-password-toggle {
  pointer-events: auto;
}

.login-password-toggle:hover,
.login-password-toggle:focus-visible {
  color: #ddd5ff;
  outline: 0;
}

.login-remember-control {
  position: absolute;
  z-index: 3;
  top: 50%;
  right: 13px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #829abb;
  cursor: pointer;
  font-size: 9px;
  line-height: 1;
  opacity: 0;
  pointer-events: none;
  transform: translateY(-50%);
  transition: color 0.2s ease, opacity 0.16s ease;
}

.login-account-entry:focus-within .login-remember-control {
  pointer-events: auto;
}

.login-remember-control input {
  width: 14px;
  height: 14px;
  margin: 0;
  border: 1px solid rgba(111, 190, 255, 0.48);
  border-radius: 4px;
  appearance: none;
  background: rgba(5, 14, 32, 0.72);
  cursor: pointer;
}

.login-remember-control > svg {
  position: absolute;
  top: 2px;
  left: 2px;
  color: #ffffff;
  opacity: 0;
  pointer-events: none;
}

.login-remember-control input:checked {
  border-color: #4c9dff;
  background: #3188ed;
}

.login-remember-control input:checked + svg {
  opacity: 1;
}

.login-remember-control:focus-within,
.login-remember-control:hover {
  color: #c7d9ef;
}

.login-submit-orb {
  left: 78%;
  bottom: 64%;
  width: 68px;
  height: 68px;
  grid-template-rows: 26px 12px;
  align-content: center;
  gap: 2px;
  color: #ffffff;
  border: 1px solid rgba(88, 227, 215, 0.72);
  background:
    radial-gradient(circle at 35% 30%, rgba(156, 255, 235, 0.54), transparent 28%),
    radial-gradient(circle, rgba(46, 178, 184, 0.76), rgba(22, 82, 125, 0.87) 68%);
  box-shadow:
    0 0 0 8px rgba(70, 221, 211, 0.06),
    0 0 36px rgba(58, 225, 215, 0.4),
    inset 0 0 24px rgba(156, 255, 235, 0.2);
  animation: login-chain-node-in 0.58s 0.4s cubic-bezier(0.18, 0.8, 0.2, 1) both, login-orb-breathe 2.8s 1s ease-in-out infinite;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.login-submit-orb:hover,
.login-submit-orb:focus-visible {
  border-color: rgba(194, 255, 242, 0.96);
  box-shadow:
    0 0 0 9px rgba(70, 221, 211, 0.08),
    0 0 46px rgba(72, 244, 226, 0.58),
    inset 0 0 26px rgba(189, 255, 241, 0.28);
}

.login-submit-orb span {
  color: #eafffb;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0;
}

.login-submit-orb svg {
  animation: none;
}

.login-submit-orb .login-loading-icon {
  animation: login-spin 0.9s linear infinite;
}

.login-chain-status {
  position: absolute;
  z-index: 5;
  left: calc(27% + 86px);
  bottom: calc(7.5% + 27px);
  width: min(300px, 24vw);
  height: 18px;
  display: flex;
  align-items: center;
  gap: 7px;
  box-sizing: border-box;
  margin: 0;
  color: #8ba6c8;
  font-size: 10px;
  line-height: 18px;
  letter-spacing: 0;
  pointer-events: none;
  white-space: nowrap;
}

.login-chain-status span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.login-chain-status.is-error {
  color: #ff9caf;
}

.login-chain-status.is-error i {
  background: #ff6f89;
  box-shadow: 0 0 10px rgba(255, 83, 118, 0.84);
}

.login-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  margin: -1px;
  padding: 0;
  border: 0;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

@media (hover: hover) and (pointer: fine) {
  .login-entry:hover {
    width: var(--entry-open-width);
    border-color: rgba(103, 209, 255, 0.9);
    background: rgba(8, 22, 48, 0.9);
    box-shadow:
      0 18px 42px rgba(0, 0, 0, 0.34),
      0 0 30px rgba(61, 173, 255, 0.3),
      inset 0 0 22px rgba(82, 194, 255, 0.1);
  }

  .login-password-entry:hover {
    border-color: rgba(174, 148, 255, 0.88);
    box-shadow:
      0 18px 42px rgba(0, 0, 0, 0.34),
      0 0 30px rgba(144, 105, 255, 0.28),
      inset 0 0 22px rgba(158, 126, 255, 0.1);
  }

  .login-entry:hover .login-entry-input::placeholder,
  .login-entry:hover .login-password-toggle,
  .login-entry:hover .login-remember-control {
    opacity: 1;
  }

  .login-account-entry:hover .login-remember-control,
  .login-password-entry:hover .login-password-toggle {
    pointer-events: auto;
  }
}

@keyframes login-rise {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes login-scan {
  from {
    transform: translateY(-8vh);
  }
  to {
    transform: translateY(108vh);
  }
}

@keyframes login-chain-node-in {
  from {
    opacity: 0;
    transform: translate(-34px, 34px) scale(0.58);
  }
  to {
    opacity: 1;
    transform: translate(0, 0) scale(1);
  }
}

@keyframes login-rail-draw {
  from {
    stroke-dashoffset: 110;
  }
  to {
    stroke-dashoffset: 0;
  }
}

@keyframes login-rail-flow {
  to {
    stroke-dashoffset: -28;
  }
}

@keyframes login-node-flare {
  50% {
    opacity: 0.45;
  }
}

@keyframes login-nexus-turn {
  to {
    transform: rotate(360deg);
  }
}

@keyframes login-nexus-breathe {
  50% {
    opacity: 0.32;
    transform: scale(1.09);
  }
}

@keyframes login-orb-breathe {
  50% {
    box-shadow:
      0 0 0 11px rgba(70, 221, 211, 0.08),
      0 0 48px rgba(58, 225, 215, 0.52),
      inset 0 0 28px rgba(156, 255, 235, 0.24);
  }
}

@keyframes login-spin {
  to {
    transform: rotate(360deg);
  }
}

.login-status-enter-active,
.login-status-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.login-status-enter-from,
.login-status-leave-to {
  opacity: 0;
  transform: translateX(-6px);
}

@media (max-width: 1180px) {
  .login-workspace {
    padding-inline: 32px;
  }

  .login-visual-copy {
    margin-left: 0;
  }

  .login-visual-copy {
    width: min(45%, 455px);
  }

  .login-visual-copy h1 {
    font-size: 42px;
  }

  .login-nexus-trigger {
    left: 25%;
  }

  .login-account-entry {
    left: 39%;
  }

  .login-password-entry {
    left: 58%;
  }

  .login-submit-orb {
    left: 82%;
  }

  .login-chain-status {
    left: calc(25% + 84px);
    width: min(280px, 28vw);
  }
}

@media (max-width: 820px) {
  .login-page::after {
    box-shadow: inset 0 0 100px 20px rgba(0, 0, 0, 0.46);
  }

  .login-depth-grid {
    opacity: 0.12;
    mask-image: linear-gradient(180deg, transparent 8%, black 38%, transparent 82%);
  }

  .login-workspace {
    display: block;
    padding: 18px 16px 14px;
  }

  .login-visual-copy {
    position: absolute;
    top: 116px;
    left: 20px;
    right: 20px;
    width: auto;
    max-width: 460px;
    margin: 0;
    padding: 0;
  }

  .login-kicker {
    margin-bottom: 12px;
    font-size: 10px;
  }

  .login-visual-copy h1 {
    font-size: 34px;
    line-height: 1.12;
  }

  .login-visual-copy > p {
    max-width: 360px;
    margin-top: 14px;
    font-size: 12px;
    line-height: 1.65;
  }

  .login-capabilities {
    gap: 6px;
    margin-top: 16px;
  }

  .login-capabilities span {
    min-height: 30px;
    padding: 5px 8px;
    font-size: 11px;
  }

  .login-chain-rail.is-desktop {
    display: none;
  }

  .login-chain-rail.is-mobile {
    display: block;
  }

  .login-nexus-trigger {
    left: 16px;
    bottom: 38px;
  }

  .login-account-entry {
    --entry-open-width: min(316px, calc(100vw - 74px));
    left: 54px;
    bottom: 126px;
  }

  .login-password-entry {
    --entry-open-width: min(260px, calc(100vw - 132px));
    left: 112px;
    bottom: 232px;
  }

  .login-submit-orb {
    right: 18px;
    bottom: 340px;
    left: auto;
    width: 68px;
    height: 68px;
  }

  .login-chain-status {
    right: 20px;
    bottom: 62px;
    left: 100px;
    width: auto;
  }

}

@media (max-width: 360px) {
  .login-visual-copy h1 {
    font-size: 30px;
  }

  .login-account-entry {
    --entry-open-width: calc(100vw - 64px);
    left: 44px;
  }

  .login-password-entry {
    --entry-open-width: calc(100vw - 112px);
    left: 92px;
  }

  .login-submit-orb {
    right: 16px;
  }
}

@media (max-height: 720px) and (min-width: 821px) {
  .login-workspace {
    padding-block: 24px 20px;
  }

  .login-visual-copy {
    padding-bottom: 64px;
  }

  .login-visual-copy h1 {
    font-size: 38px;
  }

  .login-visual-copy > p {
    margin-top: 10px;
    line-height: 1.55;
  }

  .login-capabilities {
    margin-top: 14px;
  }
}

@media (max-width: 820px) and (max-height: 720px) {
  .login-visual-copy {
    top: 92px;
  }

  .login-visual-copy h1 {
    font-size: 30px;
  }

  .login-visual-copy > p {
    display: none;
  }

  .login-capabilities {
    margin-top: 13px;
  }

  .login-nexus-trigger {
    bottom: 18px;
  }

  .login-account-entry {
    bottom: 92px;
  }

  .login-password-entry {
    bottom: 180px;
  }

  .login-submit-orb {
    bottom: 276px;
  }

  .login-chain-status {
    bottom: 42px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-scanline,
  .login-visual-copy,
  .login-nexus-trigger::before,
  .login-nexus-trigger::after,
  .login-chain-rail .rail-core,
  .login-chain-rail circle,
  .login-entry,
  .login-submit-orb,
  .login-submit-orb svg,
  .login-status-enter-active,
  .login-status-leave-active {
    animation: none !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
