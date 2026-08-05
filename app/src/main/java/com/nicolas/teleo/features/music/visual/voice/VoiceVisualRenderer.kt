package com.nicolas.teleo.features.music.visual.voice

import java.util.ArrayDeque
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

data class VoiceParticle(
    var x: Float = 0f,
    var y: Float = 0f,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
    var size: Float = 0f,
    var opacity: Float = 0f,
    var ageMs: Long = 0,
    var lifetimeMs: Long = 1
)

class VoiceParticlePool(maxParticles: Int) {
    private val active = ArrayList<VoiceParticle>(maxParticles)
    private val recycled = ArrayDeque<VoiceParticle>()
    var maxParticles = maxParticles.coerceAtLeast(0)
        private set
    var allocatedCount = 0
        private set

    val particles: List<VoiceParticle> get() = active
    val activeCount: Int get() = active.size
    val recycledCount: Int get() = recycled.size

    fun resize(limit: Int) {
        maxParticles = limit.coerceAtLeast(0)
        while (active.size > maxParticles) recycleAt(active.lastIndex)
    }

    fun acquire(): VoiceParticle? {
        if (active.size >= maxParticles) return null
        val particle = if (recycled.isEmpty()) {
            allocatedCount++
            VoiceParticle()
        } else recycled.removeLast()
        active += particle
        return particle
    }

    fun update(deltaTimeSeconds: Float, motionMultiplier: Float) {
        val delta = deltaTimeSeconds.coerceIn(0f, 0.05f)
        val elapsedMs = (delta * 1_000f).toLong()
        var index = active.lastIndex
        while (index >= 0) {
            val particle = active[index]
            particle.ageMs += elapsedMs
            if (particle.ageMs >= particle.lifetimeMs) {
                recycleAt(index)
            } else {
                particle.x += particle.velocityX * delta * motionMultiplier
                particle.y += particle.velocityY * delta * motionMultiplier
                val life = particle.ageMs.toFloat() / particle.lifetimeMs
                particle.opacity = (1f - life * life).coerceIn(0f, 1f)
            }
            index--
        }
    }

    fun clear() {
        active.forEach(recycled::addLast)
        active.clear()
    }

    private fun recycleAt(index: Int) {
        recycled.addLast(active.removeAt(index))
    }
}

interface VoiceVisualRenderer {
    fun update(frame: VoiceVisualFrame, deltaTimeSeconds: Float)
    fun reset()
}

class DefaultVoiceVisualRenderer(
    initialSettings: VoiceVisualSettings = VoiceVisualSettings(),
    private val smoothing: VoiceVisualSmoothing = VoiceVisualSmoothing(),
    val tuning: VoiceVisualTuning = VoiceVisualTuning(),
    private val seed: Long = 0x54454C454FL
) : VoiceVisualRenderer {
    var settings: VoiceVisualSettings = initialSettings
        private set
    var presence = 0f
        private set
    var intensity = 0f
        private set
    var pitchNormalized = 0.5f
        private set
    var vibrato = 0f
        private set
    var onsetStrength = 0f
        private set
    var centerX = 0.5f
        private set
    var centerY = 0.5f
        private set
    var scale = tuning.silentPointScale
        private set
    var opacity = 0f
        private set
    var elapsedSeconds = 0f
        private set
    var dominantBlend = DominantVowelBlend(VisualVowel.UNKNOWN, VisualVowel.O, 0f)
        private set
    var smoothedProbabilities = VowelProbabilities.NEUTRAL
        private set

    private val particlePool = VoiceParticlePool(particleLimit(initialSettings))
    private var previousRawOnset = 0f
    private var previousRawIntensity = 0f
    private var emissionIndex = 0L

    val particles: List<VoiceParticle> get() = particlePool.particles
    val particleCount: Int get() = particlePool.activeCount
    val allocatedParticleCount: Int get() = particlePool.allocatedCount
    val recycledParticleCount: Int get() = particlePool.recycledCount

    fun setSettings(settings: VoiceVisualSettings) {
        this.settings = settings
        particlePool.resize(particleLimit(settings))
        if (!settings.particlesEnabled) particlePool.clear()
    }

    override fun update(frame: VoiceVisualFrame, deltaTimeSeconds: Float) {
        val delta = deltaTimeSeconds.coerceIn(0f, 0.05f)
        elapsedSeconds += delta
        val target = if (settings.enabled) frame else VoiceVisualFrame.SILENCE
        presence = damp(presence, target.presence, smoothing.attackSpeed, smoothing.releaseSpeed, delta)
        intensity = damp(intensity, target.intensity, smoothing.attackSpeed, smoothing.releaseSpeed, delta)
        pitchNormalized = damp(pitchNormalized, target.pitchNormalized, smoothing.positionSpeed, smoothing.positionSpeed, delta)
        vibrato = damp(vibrato, target.vibrato, smoothing.attackSpeed, smoothing.releaseSpeed, delta)
        onsetStrength = damp(onsetStrength, target.onsetStrength, smoothing.attackSpeed * 1.4f, smoothing.releaseSpeed * 2f, delta)
        smoothedProbabilities = smoothProbabilities(smoothedProbabilities, target.vowelProbabilities, delta)
        dominantBlend = smoothedProbabilities.dominantBlend()

        val motionFactor = if (settings.reducedMotion) 0.22f else settings.motionIntensity
        val targetY = 0.5f + (0.5f - pitchNormalized) * tuning.pitchTravel * motionFactor
        centerY = damp(centerY, targetY, smoothing.positionSpeed, smoothing.positionSpeed, delta)
        val targetScale = tuning.silentPointScale + presence * (tuning.presenceScale + intensity * tuning.intensityScale)
        scale = damp(scale, targetScale, smoothing.attackSpeed, smoothing.releaseSpeed, delta)
        opacity = damp(opacity, presence, smoothing.attackSpeed, smoothing.releaseSpeed, delta)

        val abruptIntensityRise = target.intensity - previousRawIntensity > 0.16f
        val newOnset = target.onsetStrength > 0.12f && previousRawOnset <= 0.12f
        if (settings.particlesEnabled && presence > 0.02f && (newOnset || abruptIntensityRise)) {
            emitParticles(target.onsetStrength.coerceAtLeast(target.intensity * 0.55f))
        }
        previousRawOnset = target.onsetStrength
        previousRawIntensity = target.intensity
        particlePool.resize(particleLimit(settings))
        particlePool.update(delta, motionFactor)
    }

    override fun reset() {
        presence = 0f
        intensity = 0f
        pitchNormalized = 0.5f
        vibrato = 0f
        onsetStrength = 0f
        centerX = 0.5f
        centerY = 0.5f
        scale = tuning.silentPointScale
        opacity = 0f
        elapsedSeconds = 0f
        dominantBlend = DominantVowelBlend(VisualVowel.UNKNOWN, VisualVowel.O, 0f)
        smoothedProbabilities = VowelProbabilities.NEUTRAL
        previousRawOnset = 0f
        previousRawIntensity = 0f
        emissionIndex = 0
        particlePool.clear()
    }

    private fun smoothProbabilities(
        current: VowelProbabilities,
        target: VowelProbabilities,
        delta: Float
    ): VowelProbabilities {
        fun value(vowel: VisualVowel) = damp(
            current[vowel],
            target[vowel],
            smoothing.shapeMorphSpeed,
            smoothing.shapeMorphSpeed,
            delta
        )
        return VowelProbabilities.of(
            a = value(VisualVowel.A),
            e = value(VisualVowel.E),
            i = value(VisualVowel.I),
            o = value(VisualVowel.O),
            u = value(VisualVowel.U),
            unknown = value(VisualVowel.UNKNOWN)
        )
    }

    private fun emitParticles(strength: Float) {
        val reducedFactor = if (settings.reducedMotion) 0.28f else 1f
        val count = ((tuning.particleBaseCount + strength * tuning.particleStrengthCount) *
            settings.particleIntensity * reducedFactor).toInt().coerceAtLeast(1)
        val random = Random(seed xor emissionIndex++)
        repeat(count) { index ->
            val angle = index / count.toFloat() * PI.toFloat() * 2f + random.nextFloat() * 0.32f
            val edgeRadius = scale * (0.8f + random.nextFloat() * 0.28f)
            val speed = (0.055f + random.nextFloat() * 0.16f) * (0.55f + strength)
            particlePool.acquire()?.apply {
                x = centerX + cos(angle) * edgeRadius
                y = centerY + sin(angle) * edgeRadius
                velocityX = cos(angle) * speed
                velocityY = sin(angle) * speed
                size = 0.004f + random.nextFloat() * 0.012f * (0.55f + strength)
                opacity = 0.9f
                ageMs = 0
                lifetimeMs = 420L + random.nextInt(620)
            }
        }
    }

    private fun particleLimit(settings: VoiceVisualSettings): Int {
        if (!settings.particlesEnabled) return 0
        val base = settings.quality.particleLimit
        return if (settings.reducedMotion) (base * 0.35f).toInt() else base
    }

    private fun damp(current: Float, target: Float, attack: Float, release: Float, delta: Float): Float {
        if (delta <= 0f) return current
        val speed = if (target > current) attack else release
        val factor = (1f - exp(-speed * delta)).coerceIn(0f, 1f)
        return current + (target - current) * factor
    }
}
