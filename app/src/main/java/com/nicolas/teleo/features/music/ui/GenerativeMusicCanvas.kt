package com.nicolas.teleo.features.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nicolas.teleo.features.music.domain.MusicFeatureFrame
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.VisualPreset
import com.nicolas.teleo.features.music.domain.activeEventsAt
import com.nicolas.teleo.features.music.domain.eventsBetween
import com.nicolas.teleo.features.music.visual.DeterministicMusicVisualEngine
import com.nicolas.teleo.features.music.visual.LinearMusicFeatureInterpolator
import com.nicolas.teleo.features.music.visual.MusicParticle
import com.nicolas.teleo.features.music.visual.ParticleSemanticRole
import com.nicolas.teleo.features.music.visual.ParticleShape
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GenerativeMusicCanvas(
    timeline: MusicTimeline,
    isPlaying: Boolean,
    playbackPositionProvider: () -> Long,
    settings: MusicVisualSettings,
    modifier: Modifier = Modifier
) {
    val interpolator = remember { LinearMusicFeatureInterpolator() }
    val engine = remember(timeline.trackId, timeline.analysisVersion) {
        DeterministicMusicVisualEngine(timeline.trackId, timeline.analysisVersion, settings)
    }
    val wavePath = remember { Path() }
    val secondaryWavePath = remember { Path() }
    val particlePath = remember { Path() }
    var renderTick by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(playbackPositionProvider()) }
    var features by remember { mutableStateOf(interpolator.interpolate(timeline.featureFrames, positionMs)) }

    LaunchedEffect(engine, settings) {
        engine.setSettings(settings)
    }
    LaunchedEffect(engine, timeline, isPlaying, settings.preset) {
        if (!isPlaying) return@LaunchedEffect
        var previousFrameNanos = 0L
        var previousPosition = playbackPositionProvider()
        while (isActive) {
            withFrameNanos { frameNanos ->
                val currentPosition = playbackPositionProvider().coerceIn(0, timeline.durationMs)
                val deltaSeconds = if (previousFrameNanos == 0L) 0f else {
                    ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                }
                val currentFeatures = interpolator.interpolate(timeline.featureFrames, currentPosition)
                val distance = currentPosition - previousPosition
                if (distance < 0 || distance > 750) {
                    val recent = timeline.events.filter {
                        it.timestampMs in (currentPosition - 1_200L).coerceAtLeast(0)..currentPosition
                    }
                    engine.rebuild(currentPosition, currentFeatures, recent)
                } else {
                    val frameEvents = (timeline.eventsBetween(previousPosition, currentPosition) +
                        timeline.activeEventsAt(currentPosition)).distinctBy { "${it.timestampMs}:${it.type}" }
                    engine.update(currentPosition, deltaSeconds, currentFeatures, frameEvents)
                }
                previousFrameNanos = frameNanos
                previousPosition = currentPosition
                positionMs = currentPosition
                features = currentFeatures
                renderTick++
            }
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val invalidateCanvasOnly = renderTick
        drawGenerativeBackground(features, settings, positionMs)
        drawReactiveWaves(features, settings, positionMs, wavePath, secondaryWavePath)
        if (settings.preset != VisualPreset.MINIMAL) {
            engine.particles().forEach { drawMusicParticle(it, particlePath, settings) }
        }
        drawBeatFocus(features, settings)
    }
}

private fun DrawScope.drawGenerativeBackground(
    features: MusicFeatureFrame,
    settings: MusicVisualSettings,
    positionMs: Long
) {
    drawRect(Color(0xFF03050B))
    val brightnessLimit = if (settings.limitBrightnessChanges) 0.72f else 1f
    val energy = features.overallEnergy * brightnessLimit
    val pulse = (1f - features.beatPhase) * features.beatStrength
    val center = Offset(size.width * 0.5f, size.height * (0.48f - (features.melodicPitchNormalized ?: 0.5f) * 0.08f))
    val baseRadius = size.minDimension * (0.18f + energy * 0.24f + pulse * 0.04f)
    val paletteShift = ((positionMs / 16_000L) % 3).toInt()
    val primary = when (paletteShift) {
        0 -> Color(0xFF00E5FF)
        1 -> Color(0xFFFF00C8)
        else -> Color(0xFF7C4DFF)
    }
    repeat(if (settings.reducedMotion) 2 else 4) { layer ->
        val fraction = 1f - layer * 0.17f
        drawCircle(
            color = primary.copy(alpha = (0.025f + energy * 0.045f) * fraction),
            radius = baseRadius * (1f + layer * 0.5f),
            center = center,
            blendMode = BlendMode.Screen
        )
    }
    val lowGlow = Color(0xFF1DE9B6).copy(alpha = 0.035f + features.lowEnergy * 0.08f)
    drawOval(
        color = lowGlow,
        topLeft = Offset(-size.width * 0.1f, size.height * 0.62f),
        size = androidx.compose.ui.geometry.Size(size.width * 1.2f, size.height * 0.42f),
        blendMode = BlendMode.Screen
    )
}

private fun DrawScope.drawReactiveWaves(
    features: MusicFeatureFrame,
    settings: MusicVisualSettings,
    positionMs: Long,
    wavePath: Path,
    secondaryPath: Path
) {
    val reduced = settings.reducedMotion
    val samples = if (reduced) 36 else 72
    val motion = if (reduced) 0.2f else settings.motionIntensity
    val time = positionMs / 1_000f * motion
    val centerY = size.height * 0.68f
    val amplitude = size.height * (0.025f + features.lowEnergy * if (reduced) 0.035f else 0.095f)
    wavePath.reset()
    secondaryPath.reset()
    for (index in 0..samples) {
        val fraction = index / samples.toFloat()
        val x = size.width * fraction
        val envelope = sin(fraction * PI).toFloat()
        val y = centerY + sin(fraction * PI.toFloat() * 4f + time * 2.1f) * amplitude * envelope
        val y2 = centerY + sin(fraction * PI.toFloat() * 6f - time * 1.4f) * amplitude * 0.55f * envelope
        if (index == 0) {
            wavePath.moveTo(x, y)
            secondaryPath.moveTo(x, y2)
        } else {
            wavePath.lineTo(x, y)
            secondaryPath.lineTo(x, y2)
        }
    }
    drawPath(wavePath, Color(0xFF1DE9B6).copy(alpha = 0.28f + features.lowEnergy * 0.42f), style = Stroke(2.5f + features.lowEnergy * 7f))
    if (settings.preset != VisualPreset.MINIMAL) {
        drawPath(secondaryPath, Color(0xFF00E5FF).copy(alpha = 0.18f + features.midEnergy * 0.34f), style = Stroke(1.5f + features.midEnergy * 4f))
    }
}

private fun DrawScope.drawBeatFocus(features: MusicFeatureFrame, settings: MusicVisualSettings) {
    val pulse = (1f - features.beatPhase) * features.beatStrength
    if (pulse <= 0.02f) return
    val alpha = pulse * if (settings.limitBrightnessChanges) 0.18f else 0.3f
    drawCircle(
        color = Color.White.copy(alpha = alpha),
        radius = size.minDimension * (0.055f + pulse * 0.065f),
        center = center,
        style = Stroke(width = 2f + pulse * 8f)
    )
}

private fun DrawScope.drawMusicParticle(
    particle: MusicParticle,
    reusablePath: Path,
    settings: MusicVisualSettings
) {
    val center = Offset(particle.x * size.width, particle.y * size.height)
    val base = particleColor(particle.semanticRole)
    val alpha = particle.opacity * if (settings.limitBrightnessChanges) 0.78f else 1f
    val radius = (particle.size * size.minDimension).coerceAtLeast(1f)
    if (particle.semanticRole == ParticleSemanticRole.KICK || particle.semanticRole == ParticleSemanticRole.VOCAL) {
        drawCircle(base.copy(alpha = alpha * 0.1f), radius * 2.4f, center, blendMode = BlendMode.Screen)
    }
    when (particle.shape) {
        ParticleShape.CIRCLE -> drawCircle(base.copy(alpha = alpha), radius, center)
        ParticleShape.RING -> drawCircle(base.copy(alpha = alpha), radius, center, style = Stroke((radius * 0.22f).coerceAtLeast(1f)))
        ParticleShape.LINE -> {
            val radians = particle.rotation / 180f * PI.toFloat()
            val vector = Offset(cos(radians) * radius, sin(radians) * radius)
            drawLine(base.copy(alpha = alpha), center - vector, center + vector, strokeWidth = (radius * 0.28f).coerceAtLeast(1f))
        }
        ParticleShape.TRIANGLE -> {
            reusablePath.reset()
            reusablePath.moveTo(center.x, center.y - radius)
            reusablePath.lineTo(center.x - radius, center.y + radius)
            reusablePath.lineTo(center.x + radius, center.y + radius)
            reusablePath.close()
            drawPath(reusablePath, base.copy(alpha = alpha))
        }
        ParticleShape.DIAMOND -> {
            reusablePath.reset()
            reusablePath.moveTo(center.x, center.y - radius)
            reusablePath.lineTo(center.x - radius, center.y)
            reusablePath.lineTo(center.x, center.y + radius)
            reusablePath.lineTo(center.x + radius, center.y)
            reusablePath.close()
            drawPath(reusablePath, base.copy(alpha = alpha))
        }
        ParticleShape.WAVE_POINT -> drawCircle(base.copy(alpha = alpha), radius, center, style = Stroke((radius * 0.18f).coerceAtLeast(1f)))
    }
}

private fun particleColor(role: ParticleSemanticRole): Color = when (role) {
    ParticleSemanticRole.KICK -> Color(0xFFFEE715)
    ParticleSemanticRole.SNARE -> Color(0xFFFF4FD8)
    ParticleSemanticRole.HI_HAT -> Color.White
    ParticleSemanticRole.BASS -> Color(0xFF1DE9B6)
    ParticleSemanticRole.VOCAL -> Color(0xFFFF00FF)
    ParticleSemanticRole.MELODY -> Color(0xFF00E5FF)
    ParticleSemanticRole.AMBIENT -> Color(0xFF7C4DFF)
}
