package com.nicolas.teleo.features.music.visual

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.VocalVisemeShape
import kotlin.math.exp

/** A renderer-ready, recognizable articulatory pose. Values are normalized. */
data class MouthPose(
    val width: Float,
    val openness: Float,
    val roundness: Float,
    val upperLipLift: Float,
    val jawDrop: Float,
    val teethVisibility: Float,
    val tongueVisibility: Float
) {
    companion object {
        val REST = MouthPose(.72f, .05f, .25f, 0f, 0f, 0f, 0f)
    }
}

/** Rhubarb codes are visual mouth groups, not phoneme labels. */
object RhubarbMouthPoseMapper {
    fun poseFor(shape: VocalVisemeShape?): MouthPose = when (shape ?: VocalVisemeShape.X) {
        VocalVisemeShape.A -> MouthPose(.82f, .08f, .18f, .05f, .02f, .18f, 0f)
        VocalVisemeShape.B -> MouthPose(.88f, .22f, .12f, .16f, .12f, .42f, 0f)
        VocalVisemeShape.C -> MouthPose(.82f, .48f, .18f, .22f, .38f, .20f, .08f)
        VocalVisemeShape.D -> MouthPose(.72f, .82f, .30f, .25f, .72f, .10f, .12f)
        VocalVisemeShape.E -> MouthPose(.62f, .62f, .58f, .12f, .50f, .06f, .08f)
        VocalVisemeShape.F -> MouthPose(.48f, .48f, .92f, .02f, .32f, 0f, 0f)
        VocalVisemeShape.G -> MouthPose(.78f, .13f, .20f, .04f, .06f, .58f, 0f)
        VocalVisemeShape.H -> MouthPose(.76f, .30f, .18f, .20f, .18f, .26f, .72f)
        VocalVisemeShape.X -> MouthPose.REST
    }
}

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
    val tom: PremiumDrumAccent = PremiumDrumAccent()
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
        val targetPose = RhubarbMouthPoseMapper.poseFor(targetViseme)
        val poseSpeed = if (settings.reducedMotion) 8f else 12f
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
        val dynamicPose = targetPose.copy(
            openness = (targetPose.openness + sample.vocals.intensity * .12f).coerceIn(0f, 1f),
            jawDrop = (targetPose.jawDrop + sample.vocals.intensity * .08f).coerceIn(0f, 1f)
        )
        state = PremiumMusicState(
            positionMs = sample.positionMs,
            voice = PremiumVoiceState(
                pose = smoothPose(previous.voice.pose, dynamicPose, poseSpeed, delta),
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
            kick = kick,
            snare = snare,
            hiHat = hiHat,
            cymbal = cymbal,
            tom = tom
        )
        return state
    }

    fun reset(positionMs: Long = 0) {
        state = PremiumMusicState(positionMs = positionMs.coerceAtLeast(0))
    }

    private fun smoothPose(current: MouthPose, target: MouthPose, speed: Float, delta: Float) = MouthPose(
        damp(current.width, target.width, speed, delta),
        damp(current.openness, target.openness, speed, delta),
        damp(current.roundness, target.roundness, speed, delta),
        damp(current.upperLipLift, target.upperLipLift, speed, delta),
        damp(current.jawDrop, target.jawDrop, speed, delta),
        damp(current.teethVisibility, target.teethVisibility, speed, delta),
        damp(current.tongueVisibility, target.tongueVisibility, speed, delta)
    )

    private fun damp(current: Float, target: Float, speed: Float, delta: Float): Float {
        if (delta <= 0f) return target
        val factor = (1f - exp(-speed * delta)).coerceIn(0f, 1f)
        return current + (target - current) * factor
    }
}
