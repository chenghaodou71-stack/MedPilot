<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import doctorIllustration from '../assets/illustrations/doctor.svg'

const container = ref(null)
const sceneReady = ref(false)
const sceneFailed = ref(false)

let renderer = null
let scene = null
let camera = null
let sceneRoot = null
let globeGroup = null
let nexusGroup = null
let nexusCore = null
let starField = null
let dustField = null
let animationFrame = null
let resizeObserver = null
let motionMediaQuery = null
let clock = null
let isVisible = true
let pointerX = 0
let pointerY = 0
let compactScene = false
let accentNodes = []
let orbitRings = []
let streamDots = []

const palette = [0x54a8ff, 0x55e1d8, 0xa98cff, 0xff7897, 0xffbd67]
const nexusPosition = new THREE.Vector3(-0.05, -0.82, 1.52)

function fibonacciSphere(count, radius) {
  const points = []
  const goldenAngle = Math.PI * (3 - Math.sqrt(5))

  for (let index = 0; index < count; index += 1) {
    const y = 1 - (index / (count - 1)) * 2
    const ringRadius = Math.sqrt(Math.max(0, 1 - y * y))
    const angle = goldenAngle * index
    points.push(new THREE.Vector3(
      Math.cos(angle) * ringRadius * radius,
      y * radius,
      Math.sin(angle) * ringRadius * radius,
    ))
  }

  return points
}

function lineMaterial(color, opacity) {
  return new THREE.LineBasicMaterial({
    color,
    transparent: true,
    opacity,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
  })
}

function addGlobeNetwork() {
  const radius = compactScene ? 1.52 : 1.72
  const nodeCount = compactScene ? 42 : 76
  const positions = fibonacciSphere(nodeCount, radius)

  const shell = new THREE.Mesh(
    new THREE.SphereGeometry(radius, compactScene ? 20 : 32, compactScene ? 14 : 22),
    new THREE.MeshBasicMaterial({
      color: 0x438edb,
      wireframe: true,
      transparent: true,
      opacity: 0.09,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    }),
  )
  globeGroup.add(shell)

  const innerShell = new THREE.Mesh(
    new THREE.SphereGeometry(radius * 0.985, 28, 18),
    new THREE.MeshBasicMaterial({
      color: 0x163f83,
      transparent: true,
      opacity: 0.045,
      side: THREE.BackSide,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
    }),
  )
  globeGroup.add(innerShell)

  const pointPositions = new Float32Array(nodeCount * 3)
  const pointColors = new Float32Array(nodeCount * 3)
  positions.forEach((position, index) => {
    pointPositions.set(position.toArray(), index * 3)
    const color = new THREE.Color(palette[index % palette.length])
    pointColors.set(color.toArray(), index * 3)
  })

  const pointGeometry = new THREE.BufferGeometry()
  pointGeometry.setAttribute('position', new THREE.BufferAttribute(pointPositions, 3))
  pointGeometry.setAttribute('color', new THREE.BufferAttribute(pointColors, 3))
  const nodeField = new THREE.Points(
    pointGeometry,
    new THREE.PointsMaterial({
      size: compactScene ? 0.045 : 0.055,
      vertexColors: true,
      transparent: true,
      opacity: 0.92,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      sizeAttenuation: true,
    }),
  )
  globeGroup.add(nodeField)

  const connectionPoints = []
  const threshold = compactScene ? 0.92 : 0.82
  for (let left = 0; left < positions.length; left += 1) {
    for (let right = left + 1; right < positions.length; right += 1) {
      if (positions[left].distanceTo(positions[right]) < threshold) {
        connectionPoints.push(positions[left], positions[right])
      }
    }
  }
  const connectionGeometry = new THREE.BufferGeometry().setFromPoints(connectionPoints)
  globeGroup.add(new THREE.LineSegments(
    connectionGeometry,
    lineMaterial(0x6db5ff, compactScene ? 0.2 : 0.27),
  ))

  const accentGeometry = new THREE.IcosahedronGeometry(compactScene ? 0.055 : 0.07, 1)
  const accentMaterials = palette.map((color) => new THREE.MeshBasicMaterial({
    color,
    transparent: true,
    opacity: 0.92,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
  }))
  positions.forEach((position, index) => {
    if (index % (compactScene ? 6 : 5) !== 0) return
    const accentIndex = Math.floor(index / (compactScene ? 6 : 5))
    const node = new THREE.Mesh(accentGeometry, accentMaterials[accentIndex % accentMaterials.length])
    node.position.copy(position)
    node.userData.phase = index * 0.43
    globeGroup.add(node)
    accentNodes.push(node)
  })

  const ringDefinitions = [
    { scale: 1.08, rotation: [Math.PI / 2.65, 0.18, 0.25], color: 0x5ab9ff, opacity: 0.28 },
    { scale: 1.22, rotation: [Math.PI / 2.12, -0.52, -0.3], color: 0x9b81ff, opacity: 0.2 },
    { scale: 1.34, rotation: [Math.PI / 1.88, 0.72, 0.06], color: 0x54ded5, opacity: 0.17 },
  ]

  ringDefinitions.forEach((definition, index) => {
    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(radius * definition.scale, compactScene ? 0.006 : 0.009, 6, compactScene ? 96 : 160),
      new THREE.MeshBasicMaterial({
        color: definition.color,
        transparent: true,
        opacity: definition.opacity,
        blending: THREE.AdditiveBlending,
        depthWrite: false,
      }),
    )
    ring.rotation.set(...definition.rotation)
    ring.userData.speed = (index % 2 === 0 ? 1 : -1) * (0.025 + index * 0.012)
    globeGroup.add(ring)
    orbitRings.push(ring)
  })
}

function addNexus() {
  nexusGroup = new THREE.Group()
  nexusGroup.position.copy(nexusPosition)
  globeGroup.add(nexusGroup)

  const coreGeometry = new THREE.IcosahedronGeometry(compactScene ? 0.13 : 0.16, 2)
  nexusCore = new THREE.Mesh(
    coreGeometry,
    new THREE.MeshStandardMaterial({
      color: 0x7fc8ff,
      emissive: 0x277edd,
      emissiveIntensity: 1.2,
      roughness: 0.18,
      metalness: 0.24,
      transparent: true,
      opacity: 0.9,
      wireframe: true,
    }),
  )
  nexusGroup.add(nexusCore)

  const haloDefinitions = [
    [0.24, 0x5eb9ff, 0.78],
    [0.34, 0x55dfe0, 0.52],
    [0.47, 0x7b86ff, 0.28],
  ]
  haloDefinitions.forEach(([radius, color, opacity], index) => {
    const halo = new THREE.Mesh(
      new THREE.TorusGeometry(radius, compactScene ? 0.007 : 0.01, 6, 96),
      new THREE.MeshBasicMaterial({
        color,
        transparent: true,
        opacity,
        blending: THREE.AdditiveBlending,
        depthWrite: false,
      }),
    )
    halo.rotation.x = index * 0.72
    halo.rotation.y = 0.3 + index * 0.48
    halo.userData.speed = 0.38 + index * 0.14
    nexusGroup.add(halo)
    orbitRings.push(halo)
  })

  const satelliteGeometry = new THREE.IcosahedronGeometry(compactScene ? 0.022 : 0.03, 1)
  const satelliteMaterial = new THREE.MeshBasicMaterial({
    color: 0x8fd8ff,
    transparent: true,
    opacity: 0.9,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
  })
  const satelliteCount = compactScene ? 8 : 13
  for (let index = 0; index < satelliteCount; index += 1) {
    const angle = (index / satelliteCount) * Math.PI * 2
    const distance = 0.24 + (index % 3) * 0.09
    const satellite = new THREE.Mesh(satelliteGeometry, satelliteMaterial)
    satellite.position.set(
      Math.cos(angle) * distance,
      Math.sin(angle) * distance,
      Math.sin(angle * 2) * 0.08,
    )
    satellite.userData.phase = index * 0.7
    nexusGroup.add(satellite)
    accentNodes.push(satellite)
  }

  const nexusLight = new THREE.PointLight(0x4aa8ff, compactScene ? 3 : 6, 2.6)
  nexusLight.position.set(0, 0, 0.3)
  nexusGroup.add(nexusLight)
}

function streamPoint(start, end, lane, t) {
  const phase = lane * 0.76
  const turns = Math.PI * (compactScene ? 2.2 : 3.4)
  const amplitude = (1 - t) * (compactScene ? 0.32 : 0.5) + 0.03
  return new THREE.Vector3(
    THREE.MathUtils.lerp(start.x, end.x, t),
    THREE.MathUtils.lerp(start.y, end.y, t) + Math.sin(t * turns + phase) * amplitude,
    THREE.MathUtils.lerp(start.z, end.z, t) + Math.cos(t * turns + phase) * amplitude * 0.72,
  )
}

function addDataStreams() {
  const start = nexusPosition.clone()
  const laneCount = compactScene ? 3 : 7

  for (let lane = 0; lane < laneCount; lane += 1) {
    const end = new THREE.Vector3(
      compactScene ? 2.2 : 3.6,
      (lane - (laneCount - 1) / 2) * (compactScene ? 0.14 : 0.18) + 0.3,
      -0.72 + (lane % 3) * 0.32,
    )
    const guidePoints = []
    for (let step = 0; step <= 14; step += 1) {
      guidePoints.push(streamPoint(start, end, lane, step / 14))
    }
    const curve = new THREE.CatmullRomCurve3(guidePoints)
    const color = palette[lane % palette.length]
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(curve.getPoints(compactScene ? 70 : 120)),
      lineMaterial(color, lane % 2 === 0 ? 0.34 : 0.2),
    )
    sceneRoot.add(line)

    if (!compactScene && lane % 3 === 1) {
      const ribbon = new THREE.Mesh(
        new THREE.TubeGeometry(curve, 100, 0.008, 5, false),
        new THREE.MeshBasicMaterial({
          color,
          transparent: true,
          opacity: 0.22,
          blending: THREE.AdditiveBlending,
          depthWrite: false,
        }),
      )
      sceneRoot.add(ribbon)
    }

    const dot = new THREE.Mesh(
      new THREE.IcosahedronGeometry(compactScene ? 0.028 : 0.038, 1),
      new THREE.MeshBasicMaterial({
        color,
        transparent: true,
        opacity: 0.95,
        blending: THREE.AdditiveBlending,
        depthWrite: false,
      }),
    )
    dot.position.copy(start)
    sceneRoot.add(dot)
    streamDots.push({
      curve,
      dot,
      offset: lane / laneCount,
      speed: 0.055 + lane * 0.007,
    })
  }

  const rayPoints = []
  const rayCount = compactScene ? 12 : 28
  for (let index = 0; index < rayCount; index += 1) {
    const spread = (index / Math.max(1, rayCount - 1) - 0.5) * Math.PI * 0.78
    const length = 2.2 + (index % 5) * 0.32
    rayPoints.push(
      start,
      new THREE.Vector3(
        start.x + Math.cos(spread) * length,
        start.y + Math.sin(spread) * length,
        -0.8 + (index % 4) * 0.38,
      ),
    )
  }
  sceneRoot.add(new THREE.LineSegments(
    new THREE.BufferGeometry().setFromPoints(rayPoints),
    lineMaterial(0x5f9fff, compactScene ? 0.08 : 0.12),
  ))
}

function createParticleField(count, spread, color, size, opacity) {
  const positions = new Float32Array(count * 3)
  for (let index = 0; index < count; index += 1) {
    const seed = index * 12.9898
    const x = Math.sin(seed) * 43758.5453
    const y = Math.sin(seed * 1.37 + 4.2) * 24634.6345
    const z = Math.sin(seed * 0.83 + 1.7) * 17342.2345
    positions[index * 3] = ((x - Math.floor(x)) - 0.5) * spread.x
    positions[index * 3 + 1] = ((y - Math.floor(y)) - 0.5) * spread.y
    positions[index * 3 + 2] = ((z - Math.floor(z)) - 0.5) * spread.z
  }
  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  return new THREE.Points(
    geometry,
    new THREE.PointsMaterial({
      color,
      size,
      transparent: true,
      opacity,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      sizeAttenuation: true,
    }),
  )
}

function addAtmosphere() {
  starField = createParticleField(
    compactScene ? 220 : 560,
    new THREE.Vector3(11, 6.4, 6),
    0x719ee8,
    compactScene ? 0.012 : 0.016,
    0.52,
  )
  scene.add(starField)

  dustField = createParticleField(
    compactScene ? 80 : 190,
    new THREE.Vector3(8, 4.6, 3.5),
    0xb18cff,
    compactScene ? 0.018 : 0.024,
    0.24,
  )
  scene.add(dustField)
}

function createScene() {
  const host = container.value
  if (!host) return

  compactScene = host.clientWidth < 820 || window.matchMedia('(max-width: 820px)').matches
  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(compactScene ? 52 : 40, 1, 0.1, 100)

  renderer = new THREE.WebGLRenderer({
    alpha: true,
    antialias: !compactScene,
    powerPreference: 'high-performance',
  })
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, compactScene ? 1.1 : 1.5))
  renderer.domElement.className = 'medical-network-canvas'
  renderer.domElement.setAttribute('aria-hidden', 'true')
  host.appendChild(renderer.domElement)

  sceneRoot = new THREE.Group()
  globeGroup = new THREE.Group()
  globeGroup.rotation.set(-0.08, -0.16, -0.03)
  sceneRoot.add(globeGroup)
  scene.add(sceneRoot)

  const ambient = new THREE.AmbientLight(0x9bc4ff, 1.1)
  const blueRim = new THREE.PointLight(0x469dff, compactScene ? 7 : 12, 10)
  blueRim.position.set(-2.4, 1.8, 3.4)
  const violetRim = new THREE.PointLight(0xa070ff, compactScene ? 4 : 8, 9)
  violetRim.position.set(2.8, -1.4, 2.6)
  const cyanRim = new THREE.PointLight(0x4ce3dc, compactScene ? 3 : 6, 8)
  cyanRim.position.set(0.4, 2.2, -1)
  scene.add(ambient, blueRim, violetRim, cyanRim)

  addGlobeNetwork()
  addNexus()
  addDataStreams()
  addAtmosphere()

  clock = new THREE.Timer()
  clock.connect(document)
  resizeScene()
  sceneReady.value = true
  sceneFailed.value = false
  host.dataset.sceneReady = 'true'
}

function resizeScene() {
  if (!container.value || !renderer || !camera || !sceneRoot) return
  const { width, height } = container.value.getBoundingClientRect()
  if (!width || !height) return

  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.fov = width < 820 ? 52 : 40
  camera.position.set(0, width < 820 ? 0.25 : 0, width < 820 ? 7 : 6)
  camera.updateProjectionMatrix()

  if (width < 820) {
    sceneRoot.position.set(0.12, 1.0, 0)
    sceneRoot.scale.setScalar(0.8)
  } else if (width < 1180) {
    sceneRoot.position.set(-0.62, 0.08, 0)
    sceneRoot.scale.setScalar(0.98)
  } else {
    sceneRoot.position.set(-0.88, 0.04, 0)
    sceneRoot.scale.setScalar(1.12)
  }

  renderer.render(scene, camera)
}

function renderFrame() {
  if (!renderer || !scene || !camera || !sceneRoot || !globeGroup) return
  clock?.update()
  const elapsed = clock?.getElapsed() || 0

  const targetY = -0.16 + pointerX * 0.11 + Math.sin(elapsed * 0.12) * 0.035
  const targetX = -0.08 + pointerY * 0.075
  globeGroup.rotation.y += (targetY - globeGroup.rotation.y) * 0.025
  globeGroup.rotation.x += (targetX - globeGroup.rotation.x) * 0.025
  globeGroup.rotation.z = -0.03 + Math.sin(elapsed * 0.16) * 0.018

  accentNodes.forEach((node) => {
    const pulse = 1 + Math.sin(elapsed * 2 + node.userData.phase) * 0.18
    node.scale.setScalar(pulse)
    node.rotation.x += 0.007
    node.rotation.y += 0.009
  })

  if (nexusGroup) nexusGroup.rotation.z = elapsed * 0.82
  if (nexusCore) {
    nexusCore.rotation.x = elapsed * 0.62
    nexusCore.rotation.y = elapsed * 0.9
    nexusCore.scale.setScalar(1 + Math.sin(elapsed * 2.7) * 0.12)
  }

  orbitRings.forEach((ring) => {
    ring.rotation.z += ring.userData.speed * 0.012
  })

  streamDots.forEach(({ curve, dot, offset, speed }) => {
    const progress = (elapsed * speed + offset) % 1
    dot.position.copy(curve.getPointAt(progress))
    dot.scale.setScalar(0.7 + Math.sin(elapsed * 4 + offset * 10) * 0.22)
  })

  if (starField) starField.rotation.y = elapsed * -0.006
  if (dustField) {
    dustField.rotation.y = elapsed * 0.009
    dustField.position.y = Math.sin(elapsed * 0.18) * 0.08
  }

  renderer.render(scene, camera)
  if (isVisible && !motionMediaQuery?.matches) animationFrame = requestAnimationFrame(renderFrame)
}

function startRendering() {
  if (animationFrame !== null || !renderer) return
  clock?.reset()
  renderFrame()
}

function stopRendering() {
  if (animationFrame !== null) cancelAnimationFrame(animationFrame)
  animationFrame = null
}

function handlePointerMove(event) {
  if (!container.value || motionMediaQuery?.matches) return
  const rect = container.value.getBoundingClientRect()
  pointerX = ((event.clientX - rect.left) / rect.width - 0.5) * 2
  pointerY = ((event.clientY - rect.top) / rect.height - 0.5) * 2
}

function resetPointer() {
  pointerX = 0
  pointerY = 0
}

function handleMotionPreferenceChange(event) {
  if (event.matches) {
    stopRendering()
    renderer?.render(scene, camera)
  } else if (isVisible) startRendering()
}

function handleVisibilityChange() {
  isVisible = !document.hidden
  if (isVisible && !motionMediaQuery?.matches) startRendering()
  else stopRendering()
}

function handleContextLost(event) {
  event.preventDefault()
  stopRendering()
  sceneReady.value = false
  sceneFailed.value = true
}

function disposeScene() {
  stopRendering()
  clock?.dispose()
  resizeObserver?.disconnect()
  motionMediaQuery?.removeEventListener('change', handleMotionPreferenceChange)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('blur', resetPointer)
  renderer?.domElement.removeEventListener('webglcontextlost', handleContextLost)

  const geometries = new Set()
  const materials = new Set()
  scene?.traverse((object) => {
    if (object.geometry) geometries.add(object.geometry)
    if (Array.isArray(object.material)) object.material.forEach((material) => materials.add(material))
    else if (object.material) materials.add(object.material)
  })
  geometries.forEach((geometry) => geometry.dispose())
  materials.forEach((material) => {
    material.map?.dispose?.()
    material.dispose()
  })

  renderer?.dispose()
  renderer?.domElement.remove()
  renderer = null
  clock = null
  scene = null
  camera = null
  sceneRoot = null
  globeGroup = null
  nexusGroup = null
  accentNodes = []
  orbitRings = []
  streamDots = []
}

onMounted(() => {
  try {
    createScene()
    resizeObserver = new ResizeObserver(resizeScene)
    resizeObserver.observe(container.value)
    motionMediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    motionMediaQuery.addEventListener('change', handleMotionPreferenceChange)
    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('pointermove', handlePointerMove, { passive: true })
    window.addEventListener('blur', resetPointer)
    renderer.domElement.addEventListener('webglcontextlost', handleContextLost)
    if (motionMediaQuery.matches) renderer?.render(scene, camera)
    else startRendering()
  } catch (error) {
    console.warn('MedicalNetworkScene fallback:', error)
    sceneFailed.value = true
    disposeScene()
  }
})

onBeforeUnmount(disposeScene)
</script>

<template>
  <div
    ref="container"
    class="medical-network-scene"
    :class="{ 'is-ready': sceneReady, 'is-fallback': sceneFailed }"
    :data-scene-ready="sceneReady ? 'true' : 'false'"
    aria-hidden="true"
  >
    <img v-if="sceneFailed" :src="doctorIllustration" alt="" />
  </div>
</template>

<style scoped>
.medical-network-scene {
  position: absolute;
  inset: 0;
  min-width: 0;
  min-height: 320px;
  overflow: hidden;
  background:
    linear-gradient(125deg, rgba(25, 59, 131, 0.2), transparent 45%),
    linear-gradient(315deg, rgba(91, 38, 147, 0.18), transparent 38%);
}

.medical-network-scene::before,
.medical-network-scene::after {
  position: absolute;
  pointer-events: none;
  content: '';
}

.medical-network-scene::before {
  inset: 8% 4%;
  border-top: 1px solid rgba(99, 158, 237, 0.08);
  border-bottom: 1px solid rgba(119, 93, 219, 0.07);
}

.medical-network-scene::after {
  inset: auto 4% 10%;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(88, 160, 255, 0.42), rgba(140, 98, 235, 0.36), transparent);
  box-shadow: 0 0 18px rgba(91, 151, 255, 0.18);
}

.medical-network-scene :deep(.medical-network-canvas) {
  width: 100%;
  height: 100%;
  display: block;
  opacity: 0;
  filter: saturate(1.14) contrast(1.04);
  transition: opacity 0.55s ease;
}

.medical-network-scene.is-ready :deep(.medical-network-canvas) {
  opacity: 1;
}

.medical-network-scene img {
  position: absolute;
  left: 8%;
  top: 12%;
  width: 62%;
  height: 72%;
  object-fit: contain;
  opacity: 0.24;
  filter: hue-rotate(188deg) saturate(1.5) brightness(0.72);
}

@media (max-width: 820px) {
  .medical-network-scene::before {
    inset-inline: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .medical-network-scene :deep(.medical-network-canvas) {
    transition: none;
  }
}
</style>
