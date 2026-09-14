package com.nicolas.teleo.features.music.visual

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.TeleoDrumImpulse
import com.nicolas.teleo.features.music.domain.VisualQuality
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Five immutable, indexed families. Lookup is O(5 log n), independent of playback history. */
class RhythmicBackgroundSampler(drums: List<TeleoDrumImpulse>) {
    private val families = listOf(MusicEventType.KICK, MusicEventType.SNARE, MusicEventType.HI_HAT, MusicEventType.TOM, MusicEventType.CYMBAL)
        .map { type -> drums.filter { it.type == type }.groupBy { it.startMs }.map { (time, hits) ->
            PremiumDrumAccent(time, hits.maxOf { it.intensity }.coerceIn(0f, 1f))
        }.sortedBy { it.timestampMs } }
    fun sample(positionMs: Long): RhythmicBackgroundState {
        fun latest(index: Int): PremiumDrumAccent {
            val hits = families[index]
            return hits.getOrNull(upperBound(hits, positionMs) { it.timestampMs } - 1) ?: EMPTY_ACCENT
        }
        return RhythmicBackgroundState(latest(0), latest(1), latest(2), latest(3), latest(4))
    }
    private companion object { val EMPTY_ACCENT = PremiumDrumAccent() }
}

data class RhythmicBackgroundState(
    val kick: PremiumDrumAccent = PremiumDrumAccent(),
    val snare: PremiumDrumAccent = PremiumDrumAccent(),
    val hiHat: PremiumDrumAccent = PremiumDrumAccent(),
    val tom: PremiumDrumAccent = PremiumDrumAccent(),
    val cymbal: PremiumDrumAccent = PremiumDrumAccent()
)

/** Finite attack/decay in media milliseconds: frozen on pause, reconstructible after seek. */
internal fun drumEnvelope(position: Long, accent: PremiumDrumAccent, duration: Long): Float {
    if (accent.timestampMs == Long.MIN_VALUE) return 0f
    val age = position - accent.timestampMs
    if (age < 0 || age >= duration) return 0f
    val attack = (age / 12f).coerceIn(0f, 1f)
    val decay = 1f - age / duration.toFloat()
    return attack * decay * decay * accent.intensity
}

/** Fixed-size pressure grid; the draw adapter never allocates per-vertex objects. */
class RhythmicPressureField(val columns: Int = 32, val rows: Int = 22) {
    val x = FloatArray((columns + 1) * (rows + 1))
    val y = FloatArray(x.size)
    fun update(position: Long, drums: RhythmicBackgroundState, motion: Float) {
        val kick = drumEnvelope(position, drums.kick, 800)
        val snare = drumEnvelope(position, drums.snare, 260)
        val tom = drumEnvelope(position, drums.tom, 850)
        val kickAge = if (kick > 0f) (position - drums.kick.timestampMs) / 800f else 0f
        val tomAge = if (tom > 0f) (position - drums.tom.timestampMs) / 850f else 0f
        val tomX = if ((drums.tom.timestampMs / 100L) % 2L == 0L) .27f else .73f
        for (row in 0..rows) for (column in 0..columns) {
            val index = row * (columns + 1) + column
            val px = column / columns.toFloat()
            val py = row / rows.toFloat()
            val dx = px - .5f
            val dy = py - .76f
            val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(.015f)
            val ring = distance - (.04f + kickAge * .62f)
            val radial = exp(-ring * ring * 95f) * kick * .095f
            val tx = px - tomX
            val ty = py - .64f
            val td = sqrt(tx * tx + ty * ty)
            val resonant = sin(td * 18f - tomAge * PI.toFloat() * 2f) * exp(-td * td * 12f) * tom * .045f
            val tension = sin(px * 38f) * exp(-(py - .59f) * (py - .59f) * 100f) * snare * .032f
            x[index] = px + dx / distance * radial * motion
            y[index] = py + (dy / distance * radial + resonant + tension) * motion
        }
    }
}

/** Low-contrast mesh and localized light. No timers, random colors or particle emitters. */
class RhythmicBackgroundRenderer {
    private val field = RhythmicPressureField()
    private val path = Path()
    fun draw(scope: DrawScope, state: PremiumMusicState, settings: MusicVisualSettings) = with(scope) {
        val drums = RhythmicBackgroundState(state.kick, state.snare, state.hiHat, state.tom, state.cymbal)
        val motion = settings.motionIntensity * if (settings.reducedMotion) .18f else 1f
        field.update(state.positionMs, drums, motion)
        val step = if (settings.quality == VisualQuality.LOW || settings.reducedMotion) 2 else 1
        val kick = drumEnvelope(state.positionMs, drums.kick, 800)
        val snare = drumEnvelope(state.positionMs, drums.snare, 260)
        val tom = drumEnvelope(state.positionMs, drums.tom, 850)
        val lightLimit = if (settings.limitBrightnessChanges) .6f else 1f
        val color = Color(0xFF81B6D8).copy(alpha = .065f + (kick * .22f + snare * .15f + tom * .15f).coerceAtMost(.28f) * lightLimit)
        for (row in 0..field.rows step step) {
            path.reset()
            for (column in 0..field.columns) {
                val i = row * (field.columns + 1) + column
                if (column == 0) path.moveTo(field.x[i] * size.width, field.y[i] * size.height)
                else path.lineTo(field.x[i] * size.width, field.y[i] * size.height)
            }
            drawPath(path, color, style = Stroke(size.minDimension * .002f))
        }
        for (column in 0..field.columns step step) {
            path.reset()
            for (row in 0..field.rows) {
                val i = row * (field.columns + 1) + column
                if (row == 0) path.moveTo(field.x[i] * size.width, field.y[i] * size.height)
                else path.lineTo(field.x[i] * size.width, field.y[i] * size.height)
            }
            drawPath(path, color.copy(alpha = color.alpha * .6f), style = Stroke(size.minDimension * .0015f))
        }
        val cymbal = drumEnvelope(state.positionMs, drums.cymbal, 650)
        val flash = if (settings.flashesEnabled) 1f else .18f
        if (cymbal > 0f) {
            val age = (state.positionMs - drums.cymbal.timestampMs) / 650f
            val breadth = size.width * (.6f + age * .3f * motion)
            drawOval(Color(0xFFACE3EB).copy(alpha = cymbal * .09f * flash * lightLimit),
                Offset((size.width - breadth) / 2f, size.height * .07f), Size(breadth, size.height * .13f))
            drawArc(Color(0xFFE0F8FF).copy(alpha = cymbal * .48f * flash * lightLimit), 190f, 160f, false,
                Offset((size.width - breadth) / 2f, size.height * .08f), Size(breadth, size.height * .18f), style = Stroke(size.minDimension * .004f))
        }
        val hat = drumEnvelope(state.positionMs, drums.hiHat, 150)
        if (hat > 0f) {
            val side = if ((drums.hiHat.timestampMs / 37L) % 2L == 0L) .13f else .87f
            val center = Offset(size.width * side, size.height * .24f)
            val length = size.minDimension * .018f
            val hatColor = Color(0xFFD6EFFF).copy(alpha = hat * .65f * flash * lightLimit)
            drawLine(hatColor, center - Offset(length, 0f), center + Offset(length, 0f), size.minDimension * .003f)
            drawLine(hatColor, center - Offset(0f, length), center + Offset(0f, length), size.minDimension * .002f)
        }
    }
}
