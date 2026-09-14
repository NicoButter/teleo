package com.nicolas.teleo.features.music.visual

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.VocalVisemeShape
import kotlin.math.exp

data class PremiumVoiceState(
    val pose: MouthPose = MouthPose.REST,
    val viseme: VocalVisemeShape = VocalVisemeShape.X,
    val presence: Float = 0f,
    val intensity: Float = 0f,
    val pitchNormalized: Float = .5f,
    val pitchConfidence: Float = 0f,
    val spectralBrightness: Float = 0f
)

data class PremiumDrumAccent(
    val timestampMs: Long = Long.MIN_VALUE,
    val intensity: Float = 0f
)

data class PremiumMusicState(
    val positionMs: Long = 0,
    val voice: PremiumVoiceState = PremiumVoiceState(),
    val ambientEnergy: Float = 0f,
    val ambientLow: Float = 0f,
    val ambientMid: Float = 0f,
    val ambientHigh: Float = 0f,
    val kick: PremiumDrumAccent = PremiumDrumAccent(),
    val snare: PremiumDrumAccent = PremiumDrumAccent(),
    val hiHat: PremiumDrumAccent = PremiumDrumAccent(),
    val cymbal: PremiumDrumAccent = PremiumDrumAccent(),
    val tom: PremiumDrumAccent = PremiumDrumAccent(),
    val ensemble: PremiumEnsembleState = PremiumEnsembleState()
)

/**
 * Composes protocol samples into stable renderer state. It owns easing and recent
 * accent memory, but never owns or advances musical time.
 */
class PremiumMusicScene {
    var state: PremiumMusicState = PremiumMusicState()
        private set

    fun update(
        sample: TeleoVisualState,
        events: List<MusicEvent>,
        deltaTimeSeconds: Float,
        settings: MusicVisualSettings
    ): PremiumMusicState {
        val delta = deltaTimeSeconds.coerceIn(0f, .05f)
        val targetViseme = sample.articulation ?: VocalVisemeShape.X
        val targetPose = sample.articulatedPose ?: RhubarbMouthPoseMapper.poseFor(targetViseme)
        val signalSpeed = if (settings.reducedMotion) 7f else 10f
        val previous = state
        var kick = previous.kick
        var snare = previous.snare
        var hiHat = previous.hiHat
        var cymbal = previous.cymbal
        var tom = previous.tom
        events.forEach { event ->
            val accent = PremiumDrumAccent(event.timestampMs, event.intensity.coerceIn(0f, 1f))
            when (event.type) {
                MusicEventType.KICK -> kick = accent
                MusicEventType.SNARE -> snare = accent
                MusicEventType.HI_HAT -> hiHat = accent
                MusicEventType.CYMBAL -> cymbal = accent
                MusicEventType.TOM -> tom = accent
                else -> Unit
            }
        }
        val pitchTarget = sample.vocals.pitchNormalized ?: .5f
        state = PremiumMusicState(
            positionMs = sample.positionMs,
            ensemble = sample.ensemble,
            voice = PremiumVoiceState(
                pose = targetPose,
                viseme = targetViseme,
                presence = damp(previous.voice.presence, sample.vocals.presence, signalSpeed, delta),
                intensity = damp(previous.voice.intensity, sample.vocals.intensity, signalSpeed, delta),
                pitchNormalized = damp(previous.voice.pitchNormalized, pitchTarget, 6f, delta),
                pitchConfidence = damp(previous.voice.pitchConfidence, sample.vocals.pitchConfidence, 8f, delta),
                spectralBrightness = damp(previous.voice.spectralBrightness, sample.vocals.spectralBrightness, 6f, delta)
            ),
            ambientEnergy = damp(previous.ambientEnergy, sample.ambient.overallEnergy, 4.5f, delta),
            ambientLow = damp(previous.ambientLow, sample.ambient.lowEnergy, 4f, delta),
            ambientMid = damp(previous.ambientMid, sample.ambient.midEnergy, 4f, delta),
            ambientHigh = damp(previous.ambientHigh, sample.ambient.highEnergy, 4f, delta),
            kick = sample.drumAccents?.kick ?: kick,
            snare = sample.drumAccents?.snare ?: snare,
            hiHat = sample.drumAccents?.hiHat ?: hiHat,
            cymbal = sample.drumAccents?.cymbal ?: cymbal,
            tom = sample.drumAccents?.tom ?: tom
        )
        return state
    }

    fun reset(positionMs: Long = 0) {
        state = PremiumMusicState(positionMs = positionMs.coerceAtLeast(0))
    }

    private fun damp(current: Float, target: Float, speed: Float, delta: Float): Float {
        if (delta <= 0f) return target
        val factor = (1f - exp(-speed * delta)).coerceIn(0f, 1f)
        return current + (target - current) * factor
    }
}
