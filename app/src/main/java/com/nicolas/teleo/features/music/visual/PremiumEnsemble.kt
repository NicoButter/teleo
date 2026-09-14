package com.nicolas.teleo.features.music.visual

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.TeleoExperience
import com.nicolas.teleo.features.music.domain.VisualInstrument
import com.nicolas.teleo.features.music.domain.VisualQuality
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Render envelopes are derived only from supplied events, including on pause/seek. */
data class PremiumInstrumentSignal(val intensity: Float = 0f, val onsetMs: Long = 0L)
data class PremiumEnsembleState(
    val bass: PremiumInstrumentSignal = PremiumInstrumentSignal(),
    val guitar: PremiumInstrumentSignal = PremiumInstrumentSignal(),
    val piano: PremiumInstrumentSignal = PremiumInstrumentSignal(),
    val section: String? = null
)

class PremiumEnsembleSampler(experience: TeleoExperience) {
    private val bass = EventEnvelopeIndex(experience.eventsFor("bass", MusicEventType.BASS))
    private val guitar = EventEnvelopeIndex(experience.eventsFor("guitar", MusicEventType.GUITAR))
    private val piano = EventEnvelopeIndex(experience.eventsFor("piano", MusicEventType.PIANO))
    private val sections = experience.eventsFor("sections", MusicEventType.SECTION_START)

    fun sample(positionMs: Long) = PremiumEnsembleState(
        bass.sample(positionMs), guitar.sample(positionMs), piano.sample(positionMs),
        sections.getOrNull(upperBound(sections, positionMs) { it.timestampMs } - 1)?.label
    )

    private fun TeleoExperience.eventsFor(channel: String, type: MusicEventType) =
        if (isReliable(channel)) timelineEvents.filter { it.type == type } else emptyList()

    /** Prefix end index retains held notes under shorter overlapping notes/chords. */
    private class EventEnvelopeIndex(private val events: List<MusicEvent>) {
        private val ends = ArrayList<Long>(events.size).apply {
            var end = 0L
            events.forEach { event ->
                end = maxOf(end, event.timestampMs + maxOf(event.durationMs, 100L) + 160L)
                add(end)
            }
        }
        fun sample(position: Long): PremiumInstrumentSignal {
            val first = upperBound(ends, position) { it }
            val last = upperBound(events, position) { it.timestampMs }
            var strength = 0f
            var onset = 0L
            for (index in first until last) {
                val event = events[index]
                val age = position - event.timestampMs
                val held = maxOf(event.durationMs, 100L)
                val attack = (age / 20f).coerceIn(0f, 1f)
                val release = (1f - (age - held).coerceAtLeast(0) / 160f).coerceIn(0f, 1f)
                val level = event.intensity * attack * release
                if (level > strength || (level == strength && event.timestampMs > onset)) {
                    strength = level
                    onset = event.timestampMs
                }
            }
            return PremiumInstrumentSignal(strength.coerceIn(0f, 1f), onset)
        }
    }
}

/** Independent visual identities below the mouth; no synthetic pitch or audio analysis. */
class PremiumEnsembleRenderer {
    private val wave = Path()
    private val bassColor = Color(0xFF45E6B7)
    private val guitarColor = Color(0xFF53DFFF)
    private val pianoColor = Color(0xFFBCACFF)

    fun draw(scope: DrawScope, state: PremiumMusicState, settings: MusicVisualSettings) = with(scope) {
        val ensemble = state.ensemble
        val motion = settings.motionIntensity * if (settings.reducedMotion) .18f else 1f
        if (VisualInstrument.BASS in settings.visibleInstruments) {
            drawString(ensemble.bass, state.positionMs, .89f, 2f, .032f, bassColor, motion, .009f)
            if (settings.particlesEnabled) drawNoteParticles(ensemble.bass, state.positionMs, .89f, bassColor, settings)
        }
        if (VisualInstrument.GUITAR in settings.visibleInstruments) {
            repeat(3) { string ->
                drawString(ensemble.guitar, state.positionMs, .78f + string * .012f, 6f + string, .011f, guitarColor, motion, .003f)
            }
            if (settings.particlesEnabled) drawNoteParticles(ensemble.guitar, state.positionMs, .79f, guitarColor, settings)
        }
        if (VisualInstrument.PIANO in settings.visibleInstruments) {
            val level = ensemble.piano.intensity
            // Equal response across keys: the event-only timeline supplies no piano pitch.
            repeat(12) { key ->
                val x = size.width * (.14f + key * .06f)
                val height = size.height * (.008f + level * .027f)
                drawLine(pianoColor.copy(alpha = .12f + level * .70f),
                    Offset(x, size.height * .70f - height), Offset(x, size.height * .70f + height),
                    size.width * .020f)
            }
            if (settings.particlesEnabled) drawNoteParticles(ensemble.piano, state.positionMs, .70f, pianoColor, settings)
        }
    }

    private fun DrawScope.drawString(signal: PremiumInstrumentSignal, position: Long, y: Float, cycles: Float, amplitude: Float, color: Color, motion: Float, thickness: Float) {
        val level = signal.intensity
        val phase = ((position - signal.onsetMs).coerceAtLeast(0) % 8000) / 1000f
        wave.reset()
        for (i in 0..64) {
            val t = i / 64f
            val px = size.width * (.10f + t * .80f)
            val py = size.height * (y + sin(t * PI.toFloat() * cycles * 2f + phase * cycles) * sin(t * PI.toFloat()) * amplitude * level * motion)
            if (i == 0) wave.moveTo(px, py) else wave.lineTo(px, py)
        }
        if (level > 0f) drawPath(wave, color.copy(alpha = level * .12f), style = Stroke(size.minDimension * thickness * 4f))
        drawPath(wave, color.copy(alpha = .12f + level * .73f), style = Stroke(size.minDimension * thickness))
    }

    private fun DrawScope.drawNoteParticles(signal: PremiumInstrumentSignal, position: Long, y: Float, color: Color, settings: MusicVisualSettings) {
        if (signal.intensity <= 0f) return
        val count = if (settings.reducedMotion || settings.quality == VisualQuality.LOW) 4 else 10
        val travel = if (settings.reducedMotion) .008f else .035f * settings.motionIntensity
        repeat(count) { index ->
            val phase = (((position - signal.onsetMs).coerceAtLeast(0) + index * 73L) % 900L) / 900f
            val x = size.width * (.13f + .74f * index / (count - 1f))
            val py = size.height * (y - sin(phase * PI.toFloat()) * travel)
            val alpha = (signal.intensity * (1f - phase) * .45f * settings.particleIntensity).coerceIn(0f, .65f)
            drawCircle(color.copy(alpha = alpha), size.minDimension * .0035f, Offset(x, py))
        }
    }
}

/** Foreground rhythm accents stay peripheral, outside the reading target. */
fun DrawScope.drawPremiumRhythmAccents(state: PremiumMusicState, settings: MusicVisualSettings) {
    val motion = if (settings.reducedMotion) .18f else settings.motionIntensity
    val kick = drumEnvelope(state.positionMs, state.kick, 800)
    if (kick > 0f) {
        val progress = (state.positionMs - state.kick.timestampMs) / 800f
        val center = Offset(size.width * .5f, size.height * .59f)
        val radius = size.minDimension * (.025f + progress * .12f * motion)
        drawCircle(Color(0xFFFFD36F).copy(alpha = kick * .8f), radius, center, style = Stroke(size.minDimension * .009f))
        drawCircle(Color(0xFFFFD36F).copy(alpha = kick * .18f), radius * .6f, center)
    }
    val snare = drumEnvelope(state.positionMs, state.snare, 260)
    val tom = drumEnvelope(state.positionMs, state.tom, 850)
    for (side in listOf(.07f, .93f)) {
        if (snare > 0f) repeat(3) { index ->
            val y = size.height * (.46f + index * .018f)
            val spread = size.width * (.018f + snare * .018f * motion)
            drawLine(Color(0xFFFF75B6).copy(alpha = snare * .8f), Offset(size.width * side - spread, y - spread * .3f), Offset(size.width * side + spread, y + spread * .3f), size.minDimension * .004f)
        }
        if (tom > 0f) {
            val age = (state.positionMs - state.tom.timestampMs) / 850f
            val radius = size.minDimension * (.025f + age * .045f * motion)
            drawCircle(Color(0xFF56E1C5).copy(alpha = tom * .7f), radius, Offset(size.width * side, size.height * .57f), style = Stroke(size.minDimension * .004f))
        }
    }
    if (settings.particlesEnabled && kick > 0f) repeat(if (settings.reducedMotion) 4 else 8) { index ->
        val progress = (state.positionMs - state.kick.timestampMs) / 800f
        val angle = index * PI.toFloat() / 4f
        val radius = size.minDimension * (.04f + progress * .13f * motion)
        drawCircle(Color(0xFFFFD36F).copy(alpha = (kick * .5f * settings.particleIntensity).coerceIn(0f, .7f)), size.minDimension * .003f,
            Offset(size.width * .5f + cos(angle) * radius, size.height * .59f + sin(angle) * radius))
    }
}
