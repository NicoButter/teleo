package com.nicolas.teleo.features.music.visual

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicFeatureFrame
import com.nicolas.teleo.features.music.domain.MusicFrameMetrics
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.VisualPreset
import com.nicolas.teleo.features.music.domain.VisualQuality
import java.util.ArrayDeque
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ParticleShape { CIRCLE, LINE, TRIANGLE, DIAMOND, RING, WAVE_POINT }

enum class ParticleSemanticRole { KICK, SNARE, HI_HAT, BASS, VOCAL, MELODY, AMBIENT }

class MusicParticle {
    var x = 0f
    var y = 0f
    var velocityX = 0f
    var velocityY = 0f
    var accelerationX = 0f
    var accelerationY = 0f
    var size = 0f
    var rotation = 0f
    var rotationSpeed = 0f
    var lifetimeMs = 1L
    var ageMs = 0L
    var opacity = 1f
    var shape = ParticleShape.CIRCLE
    var semanticRole = ParticleSemanticRole.AMBIENT

    fun configure(
        x: Float,
        y: Float,
        velocityX: Float,
        velocityY: Float,
        accelerationX: Float,
        accelerationY: Float,
        size: Float,
        rotation: Float,
        rotationSpeed: Float,
        lifetimeMs: Long,
        opacity: Float,
        shape: ParticleShape,
        semanticRole: ParticleSemanticRole
    ) {
        this.x = x
        this.y = y
        this.velocityX = velocityX
        this.velocityY = velocityY
        this.accelerationX = accelerationX
        this.accelerationY = accelerationY
        this.size = size
        this.rotation = rotation
        this.rotationSpeed = rotationSpeed
        this.lifetimeMs = lifetimeMs.coerceAtLeast(1)
        this.ageMs = 0
        this.opacity = opacity.coerceIn(0f, 1f)
        this.shape = shape
        this.semanticRole = semanticRole
    }
}

class MusicParticlePool(maxParticles: Int) {
    private val available = ArrayDeque<MusicParticle>()
    private val particles = ArrayList<MusicParticle>(maxParticles)
    var maxParticles: Int = maxParticles.coerceAtLeast(0)
        private set

    val activeParticles: List<MusicParticle> get() = particles
    val activeCount: Int get() = particles.size

    fun resize(newLimit: Int) {
        maxParticles = newLimit.coerceAtLeast(0)
        while (particles.size > maxParticles) recycleAt(particles.lastIndex)
    }

    fun acquire(): MusicParticle? {
        if (particles.size >= maxParticles) return null
        val particle = if (available.isEmpty()) MusicParticle() else available.removeLast()
        particles += particle
        return particle
    }

    fun update(deltaTimeSeconds: Float, motionMultiplier: Float) {
        val safeDelta = deltaTimeSeconds.coerceIn(0f, 0.05f)
        val ageDelta = (safeDelta * 1_000f).toLong()
        var index = particles.lastIndex
        while (index >= 0) {
            val particle = particles[index]
            particle.ageMs += ageDelta
            if (particle.ageMs >= particle.lifetimeMs) {
                recycleAt(index)
            } else {
                particle.velocityX += particle.accelerationX * safeDelta
                particle.velocityY += particle.accelerationY * safeDelta
                particle.x += particle.velocityX * safeDelta * motionMultiplier
                particle.y += particle.velocityY * safeDelta * motionMultiplier
                particle.rotation += particle.rotationSpeed * safeDelta * motionMultiplier
                val life = particle.ageMs.toFloat() / particle.lifetimeMs
                particle.opacity = (1f - life * life).coerceIn(0f, 1f)
            }
            index--
        }
    }

    fun clear() {
        particles.forEach(available::addLast)
        particles.clear()
    }

    private fun recycleAt(index: Int) {
        val removed = particles.removeAt(index)
        available.addLast(removed)
    }
}

interface ParticleEmitter {
    fun emit(
        event: MusicEvent,
        features: MusicFeatureFrame,
        random: Random,
        pool: MusicParticlePool,
        settings: MusicVisualSettings
    )
}

interface MusicVisualEngine {
    fun update(
        playbackPositionMs: Long,
        deltaTimeSeconds: Float,
        features: MusicFeatureFrame,
        activeEvents: List<MusicEvent>
    )
    fun reset(positionMs: Long)
    fun setPreset(preset: VisualPreset)
    fun setQuality(quality: VisualQuality)
    fun setSettings(settings: MusicVisualSettings)
    fun particles(): List<MusicParticle>
    fun metrics(): MusicFrameMetrics
}

class DeterministicMusicVisualEngine(
    trackHash: String,
    analysisVersion: Int,
    initialSettings: MusicVisualSettings = MusicVisualSettings()
) : MusicVisualEngine {
    private val seedPrefix = "$trackHash|$analysisVersion"
    private var settings = initialSettings
    private var preset = settings.preset
    private var requestedQuality = settings.quality
    private var effectiveQuality = qualityFor(settings.quality)
    private val pool = MusicParticlePool(effectiveQuality.particleLimit)
    private val consumedEvents = HashSet<String>(64)
    private val emitters = mapOf(
        MusicEventType.KICK to KickParticleEmitter,
        MusicEventType.SNARE to SnareParticleEmitter,
        MusicEventType.HI_HAT to HiHatParticleEmitter,
        MusicEventType.BASS to BassParticleEmitter,
        MusicEventType.VOCAL_START to VocalParticleEmitter,
        MusicEventType.MELODY_UP to MelodyParticleEmitter,
        MusicEventType.MELODY_DOWN to MelodyParticleEmitter
    )
    private var averageFrameMs = 0f
    private var frameSamples = 0
    private var slowFrames = 0
    private var consecutiveSlowFrames = 0
    private var positionMs = 0L

    override fun update(
        playbackPositionMs: Long,
        deltaTimeSeconds: Float,
        features: MusicFeatureFrame,
        activeEvents: List<MusicEvent>
    ) {
        if (playbackPositionMs < positionMs || playbackPositionMs - positionMs > 750) reset(playbackPositionMs)
        positionMs = playbackPositionMs
        trackFrameTime(deltaTimeSeconds)
        pool.resize(particleCap())
        if (preset != VisualPreset.MINIMAL && preset != VisualPreset.LANES) {
            activeEvents.forEach { event ->
                val key = "${event.timestampMs}:${event.type}:${event.label.orEmpty()}"
                if (consumedEvents.add(key)) emitters[event.type]?.emit(event, features, randomFor(event), pool, settings)
            }
        }
        val motion = if (settings.reducedMotion) 0.22f else settings.motionIntensity
        pool.update(deltaTimeSeconds, motion)
        if (consumedEvents.size > 512) consumedEvents.clear()
    }

    fun rebuild(
        playbackPositionMs: Long,
        features: MusicFeatureFrame,
        recentEvents: List<MusicEvent>
    ) {
        reset(playbackPositionMs)
        recentEvents.sortedBy { it.timestampMs }.forEach { event ->
            val elapsedSeconds = ((playbackPositionMs - event.timestampMs).coerceAtLeast(0) / 1_000f).coerceAtMost(1.5f)
            update(event.timestampMs, 0f, features, listOf(event))
            if (elapsedSeconds > 0f) pool.update(elapsedSeconds, if (settings.reducedMotion) 0.22f else settings.motionIntensity)
        }
        positionMs = playbackPositionMs
    }

    override fun reset(positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0)
        pool.clear()
        consumedEvents.clear()
    }

    override fun setPreset(preset: VisualPreset) {
        if (this.preset != preset) {
            this.preset = preset
            reset(positionMs)
        }
    }

    override fun setQuality(quality: VisualQuality) {
        requestedQuality = quality
        effectiveQuality = qualityFor(quality)
        pool.resize(particleCap())
    }

    override fun setSettings(settings: MusicVisualSettings) {
        val needsReset = this.settings.reducedMotion != settings.reducedMotion || this.settings.preset != settings.preset
        this.settings = settings
        preset = settings.preset
        requestedQuality = settings.quality
        effectiveQuality = qualityFor(settings.quality)
        pool.resize(particleCap())
        if (needsReset) reset(positionMs)
    }

    override fun particles(): List<MusicParticle> = pool.activeParticles

    override fun metrics(): MusicFrameMetrics = MusicFrameMetrics(averageFrameMs, slowFrames, effectiveQuality)

    private fun particleCap(): Int {
        val accessibilityFactor = if (settings.reducedMotion) 0.35f else settings.particleIntensity
        return (effectiveQuality.particleLimit * accessibilityFactor).toInt().coerceIn(0, effectiveQuality.particleLimit)
    }

    private fun randomFor(event: MusicEvent): Random {
        val seed = "$seedPrefix|${preset.name}|${event.timestampMs}|${event.type.name}".hashCode().toLong()
        return Random(seed)
    }

    private fun trackFrameTime(deltaTimeSeconds: Float) {
        if (deltaTimeSeconds <= 0f || deltaTimeSeconds > 0.25f) return
        val milliseconds = deltaTimeSeconds * 1_000f
        frameSamples++
        averageFrameMs += (milliseconds - averageFrameMs) / frameSamples.coerceAtMost(120)
        if (milliseconds > 22f) {
            slowFrames++
            consecutiveSlowFrames++
        } else {
            consecutiveSlowFrames = 0
        }
        if (requestedQuality == VisualQuality.AUTO && consecutiveSlowFrames >= 12) {
            effectiveQuality = when (effectiveQuality) {
                VisualQuality.HIGH -> VisualQuality.MEDIUM
                VisualQuality.MEDIUM -> VisualQuality.LOW
                else -> VisualQuality.LOW
            }
            pool.resize(particleCap())
            consecutiveSlowFrames = 0
        }
    }

    private fun qualityFor(quality: VisualQuality): VisualQuality =
        if (quality == VisualQuality.AUTO) VisualQuality.HIGH else quality
}

private object KickParticleEmitter : ParticleEmitter {
    override fun emit(event: MusicEvent, features: MusicFeatureFrame, random: Random, pool: MusicParticlePool, settings: MusicVisualSettings) {
        val chorusBoost = if (features.sectionId == "chorus") 1.3f else 1f
        val count = ((8 + 14 * features.beatStrength) * chorusBoost).toInt()
        repeat(count) {
            val angle = random.nextFloat() * (PI * 2).toFloat()
            val speed = (0.22f + random.nextFloat() * 0.46f) * (0.6f + features.lowEnergy)
            pool.acquire()?.configure(
                0.5f, 0.58f, cos(angle) * speed, sin(angle) * speed,
                0f, 0.08f, 0.018f + random.nextFloat() * 0.035f,
                random.nextFloat() * 360f, random.nextFloat() * 80f - 40f,
                460L + random.nextInt(360), 1f, ParticleShape.RING, ParticleSemanticRole.KICK
            )
        }
    }
}

private object SnareParticleEmitter : ParticleEmitter {
    override fun emit(event: MusicEvent, features: MusicFeatureFrame, random: Random, pool: MusicParticlePool, settings: MusicVisualSettings) {
        repeat(12) { index ->
            val direction = if (index % 2 == 0) -1f else 1f
            pool.acquire()?.configure(
                0.5f, 0.48f, direction * (0.25f + random.nextFloat() * 0.5f), random.nextFloat() * 0.28f - 0.14f,
                -direction * 0.04f, 0.12f, 0.014f + random.nextFloat() * 0.022f,
                random.nextFloat() * 360f, direction * (100f + random.nextFloat() * 180f),
                300L + random.nextInt(300), 0.95f, if (index % 3 == 0) ParticleShape.LINE else ParticleShape.TRIANGLE,
                ParticleSemanticRole.SNARE
            )
        }
    }
}

private object HiHatParticleEmitter : ParticleEmitter {
    override fun emit(event: MusicEvent, features: MusicFeatureFrame, random: Random, pool: MusicParticlePool, settings: MusicVisualSettings) {
        if (!settings.flashesEnabled) return
        repeat(4) {
            pool.acquire()?.configure(
                0.25f + random.nextFloat() * 0.5f, 0.2f + random.nextFloat() * 0.38f,
                random.nextFloat() * 0.12f - 0.06f, -0.12f - random.nextFloat() * 0.15f,
                0f, 0.08f, 0.006f + random.nextFloat() * 0.01f,
                45f, 0f, 140L + random.nextInt(100), if (settings.limitBrightnessChanges) 0.62f else 0.95f,
                ParticleShape.DIAMOND, ParticleSemanticRole.HI_HAT
            )
        }
    }
}

private object BassParticleEmitter : ParticleEmitter {
    override fun emit(event: MusicEvent, features: MusicFeatureFrame, random: Random, pool: MusicParticlePool, settings: MusicVisualSettings) {
        repeat(5) { index ->
            pool.acquire()?.configure(
                0.18f + index * 0.16f, 0.74f, 0f, -0.025f - random.nextFloat() * 0.035f,
                0f, 0f, 0.035f + features.lowEnergy * 0.04f,
                0f, 12f, 1_100L, 0.68f, ParticleShape.WAVE_POINT, ParticleSemanticRole.BASS
            )
        }
    }
}

private object VocalParticleEmitter : ParticleEmitter {
    override fun emit(event: MusicEvent, features: MusicFeatureFrame, random: Random, pool: MusicParticlePool, settings: MusicVisualSettings) {
        repeat((6 + features.vocalPresence * 10).toInt()) {
            val angle = random.nextFloat() * (PI * 2).toFloat()
            pool.acquire()?.configure(
                0.5f + cos(angle) * 0.13f, 0.25f + sin(angle) * 0.08f,
                cos(angle) * 0.045f, sin(angle) * 0.035f,
                0f, -0.01f, 0.009f + random.nextFloat() * 0.018f,
                random.nextFloat() * 360f, 24f,
                900L + random.nextInt(500), 0.72f, ParticleShape.CIRCLE, ParticleSemanticRole.VOCAL
            )
        }
    }
}

private object MelodyParticleEmitter : ParticleEmitter {
    override fun emit(event: MusicEvent, features: MusicFeatureFrame, random: Random, pool: MusicParticlePool, settings: MusicVisualSettings) {
        val pitchY = 0.72f - (features.melodicPitchNormalized ?: 0.5f) * 0.48f
        repeat(3) {
            pool.acquire()?.configure(
                0.12f, pitchY + random.nextFloat() * 0.04f - 0.02f,
                0.16f + random.nextFloat() * 0.08f, if (event.type == MusicEventType.MELODY_UP) -0.04f else 0.04f,
                0f, 0f, 0.012f + random.nextFloat() * 0.012f,
                45f, 45f, 1_100L, 0.78f, ParticleShape.DIAMOND, ParticleSemanticRole.MELODY
            )
        }
    }
}
