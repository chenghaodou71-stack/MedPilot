<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BrainCircuit } from 'lucide-vue-next'
import lottie from 'lottie-web/build/player/lottie_light'
import animationData from '../assets/lottie/search-ripple.json'

const props = defineProps({
  active: {
    type: Boolean,
    default: true,
  },
  label: {
    type: String,
    default: '医学知识检索中',
  },
})

const animationContainer = ref(null)
let animation = null
let motionMediaQuery = null

function syncPlayback() {
  if (!animation) return
  if (props.active && !motionMediaQuery?.matches) animation.play()
  else {
    animation.pause()
    animation.goToAndStop(82, true)
  }
}

function handleMotionPreferenceChange() {
  syncPlayback()
}

watch(() => props.active, syncPlayback)

onMounted(async () => {
  await nextTick()
  if (!animationContainer.value) return

  motionMediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  motionMediaQuery.addEventListener('change', handleMotionPreferenceChange)
  animation = lottie.loadAnimation({
    container: animationContainer.value,
    renderer: 'svg',
    loop: true,
    autoplay: false,
    animationData,
    rendererSettings: {
      preserveAspectRatio: 'xMidYMid slice',
      progressiveLoad: true,
    },
  })
  animation.addEventListener('DOMLoaded', syncPlayback)
})

onBeforeUnmount(() => {
  motionMediaQuery?.removeEventListener('change', handleMotionPreferenceChange)
  animation?.destroy()
  animation = null
})
</script>

<template>
  <span class="agent-pulse" role="img" :aria-label="label">
    <span ref="animationContainer" class="agent-pulse-lottie" aria-hidden="true" />
    <BrainCircuit class="agent-pulse-icon" :size="20" :stroke-width="1.8" aria-hidden="true" />
  </span>
</template>

<style scoped>
.agent-pulse {
  position: relative;
  width: 44px;
  height: 44px;
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  overflow: hidden;
  border-radius: var(--radius-lg);
  background: var(--success-soft);
  color: var(--success);
}

.agent-pulse-lottie {
  position: absolute;
  inset: -12px;
  opacity: 0.9;
  filter: hue-rotate(64deg) saturate(1.15);
}

.agent-pulse-lottie :deep(svg) {
  display: block;
  width: 100% !important;
  height: 100% !important;
}

.agent-pulse-icon {
  position: relative;
  z-index: 1;
  filter: drop-shadow(0 1px 5px var(--surface-elevated));
}

@media (prefers-reduced-motion: reduce) {
  .agent-pulse-lottie {
    opacity: 0.55;
  }
}
</style>
