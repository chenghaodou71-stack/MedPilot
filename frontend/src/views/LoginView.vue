<script setup>
import { defineAsyncComponent, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  BrainCircuit,
  Eye,
  EyeOff,
  LoaderCircle,
  LockKeyhole,
  LogIn,
  Network,
  ShieldCheck,
  UserPlus,
  UserRound,
  X,
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

const modalMode = ref('')
const username = ref('')
const password = ref('')
const passwordVisible = ref(false)
const rememberUsername = ref(true)
const error = ref('')
const loading = ref(false)
const usernameInput = ref(null)
const modalCloseButton = ref(null)

function clearError() {
  if (error.value) error.value = ''
}

function openModal(mode) {
  if (loading.value) return
  modalMode.value = mode
  error.value = ''
  passwordVisible.value = false
}

function closeModal() {
  if (loading.value) return
  modalMode.value = ''
  error.value = ''
  password.value = ''
  passwordVisible.value = false
}

function handleBackdropClick(event) {
  if (event.target === event.currentTarget) closeModal()
}

function togglePasswordVisibility() {
  passwordVisible.value = !passwordVisible.value
}

function persistRememberedUsername(value) {
  try {
    if (rememberUsername.value) localStorage.setItem('rememberedUsername', value)
    else localStorage.removeItem('rememberedUsername')
  } catch {
    // Browser storage can be unavailable without invalidating a successful login.
  }
}

async function submitLogin() {
  if (loading.value) return

  error.value = ''
  const normalizedUsername = username.value.trim()
  if (!normalizedUsername || !password.value) {
    error.value = '请输入账号和密码。'
    await nextTick()
    if (!normalizedUsername) usernameInput.value?.focus()
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

watch(modalMode, async (mode) => {
  if (!mode) return
  await nextTick()
  if (mode === 'login') usernameInput.value?.focus()
  else modalCloseButton.value?.focus()
})

onMounted(() => {
  try {
    username.value = localStorage.getItem('rememberedUsername') || ''
  } catch {
    username.value = ''
  }
})
</script>

<template>
  <main class="login-page">
    <div class="login-scene-layer" aria-hidden="true">
      <MedicalNetworkScene class="login-network-scene" />
    </div>

    <div class="login-depth-grid" aria-hidden="true" />
    <div class="login-scanline" aria-hidden="true" />

    <section class="login-workspace" aria-labelledby="login-title">
      <header class="login-topbar">
        <div class="login-action-bar" aria-label="账号操作">
          <button
            class="login-action login-action-signup"
            type="button"
            :disabled="loading"
            @click="openModal('register')"
          >
            <UserPlus :size="22" :stroke-width="2" aria-hidden="true" />
            <span>注册</span>
          </button>
          <button
            class="login-action login-action-login"
            type="button"
            :disabled="loading"
            @click="openModal('login')"
          >
            <LogIn :size="22" :stroke-width="2" aria-hidden="true" />
            <span>登录</span>
          </button>
        </div>
      </header>

      <div class="login-visual-copy">
        <span class="login-kicker">MULTI-AGENT TRIAGE SYSTEM / 04</span>
        <h1 id="login-title">多智能体协同<br /><strong>辅助分诊</strong></h1>
        <p>融合本地大语言模型与医学知识库，构建可信、可追溯的辅助分诊工作流。</p>

        <div class="login-capabilities" aria-label="系统能力">
          <span><Network :size="14" aria-hidden="true" />多智能体协同</span>
          <span><BrainCircuit :size="14" aria-hidden="true" />知识检索</span>
          <span><ShieldCheck :size="14" aria-hidden="true" />安全可追溯</span>
        </div>
      </div>
    </section>

    <Transition name="login-modal">
      <div
        v-if="modalMode"
        class="login-modal-backdrop"
        role="presentation"
        @click="handleBackdropClick"
      >
        <section
          v-if="modalMode === 'login'"
          class="login-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="login-dialog-title"
          aria-describedby="login-dialog-description"
          @keydown.esc="closeModal"
        >
          <button
            ref="modalCloseButton"
            class="login-modal-close"
            type="button"
            aria-label="关闭登录弹窗"
            title="关闭"
            :disabled="loading"
            @click="closeModal"
          >
            <X :size="18" aria-hidden="true" />
          </button>

          <div class="login-modal-icon login-modal-icon-login" aria-hidden="true">
            <LogIn :size="22" :stroke-width="2" />
          </div>
          <span class="login-modal-eyebrow">MEDPILOT ACCESS</span>
          <h2 id="login-dialog-title">登录系统</h2>
          <p id="login-dialog-description" class="login-modal-description">
            使用已开通的系统账号进入辅助分诊工作台。
          </p>

          <form class="login-form" aria-label="登录表单" :aria-busy="loading" @submit.prevent="submitLogin">
            <label class="login-field" for="login-username">
              <span>账号</span>
              <span class="login-field-control">
                <UserRound :size="17" aria-hidden="true" />
                <input
                  id="login-username"
                  ref="usernameInput"
                  v-model="username"
                  name="username"
                  autocomplete="username"
                  placeholder="请输入账号"
                  :disabled="loading"
                  @input="clearError"
                />
              </span>
            </label>

            <label class="login-field" for="login-password">
              <span>密码</span>
              <span class="login-field-control">
                <LockKeyhole :size="17" aria-hidden="true" />
                <input
                  id="login-password"
                  v-model="password"
                  name="password"
                  :type="passwordVisible ? 'text' : 'password'"
                  autocomplete="current-password"
                  placeholder="请输入密码"
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
                  <EyeOff v-if="passwordVisible" :size="17" aria-hidden="true" />
                  <Eye v-else :size="17" aria-hidden="true" />
                </button>
              </span>
            </label>

            <label class="login-remember-control">
              <input v-model="rememberUsername" type="checkbox" :disabled="loading" />
              <span>记住账号</span>
            </label>

            <p v-if="error" class="login-form-error" role="alert">{{ error }}</p>

            <button class="login-form-submit" type="submit" :disabled="loading">
              <LoaderCircle v-if="loading" class="login-loading-icon" :size="18" aria-hidden="true" />
              <LogIn v-else :size="18" aria-hidden="true" />
              <span>{{ loading ? '登录中...' : '登录' }}</span>
            </button>
          </form>
        </section>

        <section
          v-else
          class="login-modal login-register-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="register-dialog-title"
          aria-describedby="register-dialog-description"
          @keydown.esc="closeModal"
        >
          <button
            ref="modalCloseButton"
            class="login-modal-close"
            type="button"
            aria-label="关闭注册弹窗"
            title="关闭"
            @click="closeModal"
          >
            <X :size="18" aria-hidden="true" />
          </button>

          <div class="login-modal-icon login-modal-icon-register" aria-hidden="true">
            <UserPlus :size="22" :stroke-width="2" />
          </div>
          <span class="login-modal-eyebrow">ACCOUNT ACCESS</span>
          <h2 id="register-dialog-title">注册账号</h2>
          <p id="register-dialog-description" class="login-modal-description">
            当前系统不支持自助注册，请联系管理员开通账号。
          </p>
          <div class="login-register-note">
            <ShieldCheck :size="18" aria-hidden="true" />
            <span>账号由管理员统一创建，开通后即可使用登录入口。</span>
          </div>
          <button class="login-form-submit login-register-back" type="button" @click="openModal('login')">
            <LogIn :size="18" aria-hidden="true" />
            <span>返回登录</span>
          </button>
        </section>
      </div>
    </Transition>
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
  padding: 32px 48px 30px;
}

.login-topbar {
  position: relative;
  z-index: 5;
  display: flex;
  justify-content: flex-end;
}

.login-action-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 10px;
  border: 1px solid rgba(143, 170, 222, 0.32);
  border-radius: 999px;
  background: rgba(54, 75, 116, 0.64);
  box-shadow: 0 14px 36px rgba(0, 0, 0, 0.24), inset 0 1px 0 rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
}

.login-action {
  min-width: 166px;
  min-height: 56px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 0 26px;
  border: 1px solid transparent;
  border-radius: 999px;
  color: #f4f7ff;
  cursor: pointer;
  font: inherit;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: 0;
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
}

.login-action svg {
  flex: 0 0 auto;
}

.login-action-signup {
  border-color: rgba(100, 148, 208, 0.22);
  background: rgba(8, 25, 56, 0.96);
  box-shadow: 0 8px 16px rgba(1, 7, 18, 0.28), inset 0 1px 0 rgba(132, 188, 255, 0.08);
}

.login-action-signup svg {
  color: #00dca2;
  filter: drop-shadow(0 0 7px rgba(0, 220, 162, 0.28));
}

.login-action-login {
  border-color: rgba(204, 123, 211, 0.24);
  background: rgba(48, 20, 47, 0.96);
  box-shadow: 0 8px 16px rgba(12, 4, 15, 0.28), inset 0 1px 0 rgba(255, 197, 251, 0.08);
}

.login-action-login svg {
  color: #ffc400;
  filter: drop-shadow(0 0 7px rgba(255, 196, 0, 0.3));
}

.login-action:hover,
.login-action:focus-visible {
  transform: translateY(-1px);
  outline: none;
}

.login-action-signup:hover,
.login-action-signup:focus-visible {
  border-color: rgba(0, 220, 162, 0.6);
  box-shadow: 0 12px 25px rgba(0, 220, 162, 0.14), inset 0 1px 0 rgba(148, 255, 222, 0.12);
}

.login-action-login:hover,
.login-action-login:focus-visible {
  border-color: rgba(255, 196, 0, 0.62);
  box-shadow: 0 12px 25px rgba(255, 196, 0, 0.12), inset 0 1px 0 rgba(255, 238, 165, 0.12);
}

.login-action:focus-visible,
.login-modal-close:focus-visible,
.login-password-toggle:focus-visible,
.login-form-submit:focus-visible {
  outline: 2px solid rgba(208, 238, 255, 0.96);
  outline-offset: 4px;
}

.login-action:disabled,
.login-modal-close:disabled,
.login-password-toggle:disabled,
.login-form-submit:disabled {
  cursor: wait;
  opacity: 0.64;
}

.login-visual-copy {
  position: relative;
  z-index: 4;
  width: min(46%, 540px);
  margin: 25vh 0 0 3%;
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
  display: flex;
  flex-wrap: wrap;
  align-items: center;
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

.login-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(1, 5, 16, 0.72);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.login-modal {
  position: relative;
  width: min(100%, 430px);
  max-height: min(680px, calc(100dvh - 48px));
  box-sizing: border-box;
  overflow: auto;
  padding: 38px 34px 32px;
  border: 1px solid rgba(135, 183, 244, 0.32);
  border-radius: 16px;
  background: linear-gradient(145deg, rgba(12, 29, 59, 0.98), rgba(11, 17, 37, 0.98));
  box-shadow: 0 26px 80px rgba(0, 0, 0, 0.52), inset 0 1px 0 rgba(214, 238, 255, 0.1);
  color: #eff7ff;
}

.login-modal::before {
  position: absolute;
  top: 0;
  right: 20%;
  left: 20%;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(93, 212, 255, 0.9), transparent);
  box-shadow: 0 0 18px rgba(93, 212, 255, 0.45);
}

.login-modal-close {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: rgba(146, 179, 224, 0.1);
  color: #a8bdd8;
  cursor: pointer;
}

.login-modal-close:hover {
  background: rgba(146, 179, 224, 0.2);
  color: #ffffff;
}

.login-modal-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  margin-bottom: 17px;
  border-radius: 14px;
}

.login-modal-icon-login {
  border: 1px solid rgba(255, 196, 0, 0.36);
  background: rgba(255, 196, 0, 0.12);
  color: #ffc400;
}

.login-modal-icon-register {
  border: 1px solid rgba(0, 220, 162, 0.36);
  background: rgba(0, 220, 162, 0.12);
  color: #00dca2;
}

.login-modal-eyebrow {
  color: #86a9cf;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.login-modal h2 {
  margin: 7px 0 0;
  color: #f6fbff;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0;
}

.login-modal-description {
  margin: 10px 0 24px;
  color: #9db2ce;
  font-size: 13px;
  line-height: 1.75;
}

.login-form {
  display: grid;
  gap: 16px;
}

.login-field {
  display: grid;
  gap: 7px;
  color: #bfd0e6;
  font-size: 12px;
  font-weight: 600;
}

.login-field-control {
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
  padding: 0 13px;
  border: 1px solid rgba(125, 174, 228, 0.26);
  border-radius: 8px;
  background: rgba(2, 11, 27, 0.64);
  color: #7faad3;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}

.login-field-control:focus-within {
  border-color: rgba(93, 212, 255, 0.76);
  background: rgba(3, 15, 34, 0.86);
  box-shadow: 0 0 0 3px rgba(93, 212, 255, 0.12);
}

.login-field-control input {
  min-width: 0;
  width: 100%;
  height: 44px;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: #f4f8ff;
  caret-color: #65d9ff;
  font: inherit;
  font-size: 14px;
  font-weight: 400;
  letter-spacing: 0;
}

.login-field-control input::placeholder {
  color: #647e9f;
}

.login-password-toggle {
  width: 30px;
  height: 30px;
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #8da5c4;
  cursor: pointer;
}

.login-password-toggle:hover {
  color: #d7e8ff;
  background: rgba(140, 188, 238, 0.12);
}

.login-remember-control {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  color: #91a9c6;
  cursor: pointer;
  font-size: 12px;
}

.login-remember-control input {
  width: 15px;
  height: 15px;
  margin: 0;
  accent-color: #43a8ff;
}

.login-form-error {
  margin: -3px 0 0;
  color: #ff9eae;
  font-size: 12px;
  line-height: 1.5;
}

.login-form-submit {
  min-height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  margin-top: 2px;
  border: 1px solid rgba(104, 229, 218, 0.5);
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(40, 168, 166, 0.92), rgba(33, 98, 145, 0.96));
  color: #f2fffd;
  cursor: pointer;
  font: inherit;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0;
  box-shadow: 0 10px 24px rgba(26, 165, 173, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.18);
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.login-form-submit:hover,
.login-form-submit:focus-visible {
  border-color: rgba(189, 255, 242, 0.9);
  box-shadow: 0 14px 30px rgba(26, 195, 190, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.24);
  transform: translateY(-1px);
}

.login-loading-icon {
  animation: login-spin 0.85s linear infinite;
}

.login-register-note {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  padding: 14px;
  border: 1px solid rgba(0, 220, 162, 0.2);
  border-radius: 8px;
  background: rgba(0, 220, 162, 0.07);
  color: #b6dbd4;
  font-size: 13px;
  line-height: 1.65;
}

.login-register-note svg {
  flex: 0 0 auto;
  margin-top: 2px;
  color: #00dca2;
}

.login-register-back {
  margin-top: 22px;
}

.login-modal-enter-active,
.login-modal-leave-active {
  transition: opacity 0.2s ease;
}

.login-modal-enter-active .login-modal,
.login-modal-leave-active .login-modal {
  transition: transform 0.2s ease, opacity 0.2s ease;
}

.login-modal-enter-from,
.login-modal-leave-to {
  opacity: 0;
}

.login-modal-enter-from .login-modal,
.login-modal-leave-to .login-modal {
  opacity: 0;
  transform: translateY(12px) scale(0.98);
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

@keyframes login-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1180px) {
  .login-workspace {
    padding-inline: 32px;
  }

  .login-visual-copy {
    width: min(45%, 455px);
    margin-left: 0;
  }

  .login-visual-copy h1 {
    font-size: 42px;
  }

  .login-action {
    min-width: 144px;
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

  .login-topbar {
    justify-content: center;
  }

  .login-action-bar {
    width: min(100%, 460px);
    box-sizing: border-box;
    gap: 8px;
    padding: 7px;
  }

  .login-action {
    min-width: 0;
    min-height: 48px;
    flex: 1;
    padding: 0 16px;
    font-size: 15px;
  }

  .login-visual-copy {
    position: absolute;
    top: 130px;
    left: 20px;
    right: 20px;
    width: auto;
    max-width: 460px;
    margin: 0;
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
}

@media (max-width: 480px) {
  .login-modal-backdrop {
    padding: 16px;
  }

  .login-modal {
    max-height: calc(100dvh - 32px);
    padding: 34px 22px 24px;
    border-radius: 14px;
  }
}

@media (max-width: 360px) {
  .login-action {
    gap: 8px;
    padding-inline: 10px;
    font-size: 14px;
  }

  .login-visual-copy h1 {
    font-size: 30px;
  }
}

@media (max-width: 820px) and (max-height: 720px) {
  .login-visual-copy {
    top: 104px;
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
}

@media (prefers-reduced-motion: reduce) {
  .login-scanline,
  .login-visual-copy,
  .login-loading-icon,
  .login-modal-enter-active,
  .login-modal-leave-active,
  .login-modal-enter-active .login-modal,
  .login-modal-leave-active .login-modal {
    animation: none !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
