package com.nicolas.teleo.features.music.ui

import android.graphics.Matrix
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.graphics.shapes.toPath
import com.nicolas.teleo.features.music.visual.voice.DefaultVoiceVisualRenderer
import com.nicolas.teleo.features.music.visual.voice.VisualVowel
import com.nicolas.teleo.features.music.visual.voice.VoiceShapeLibrary
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualFrame
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualSettings
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceBlobCanvas(
    frame: VoiceVisualFrame,
    settings: VoiceVisualSettings,
    modifier: Modifier = Modifier
) {
    val renderer = remember { DefaultVoiceVisualRenderer(settings) }
    val currentFrame by rememberUpdatedState(frame)
    val currentSettings by rememberUpdatedState(settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    val androidPath = remember { AndroidPath() }
    val transform = remember { Matrix() }
    val vibratoPath = remember { Path() }
    var renderTick by remember { mutableIntStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(renderer, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previousNanos = 0L
            while (isActive) {
                withFrameNanos { frameNanos ->
                    val delta = if (previousNanos == 0L) 0f else {
                        ((frameNanos - previousNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    }
                    renderer.setSettings(currentSettings)
                    renderer.update(currentFrame, delta)
                    previousNanos = frameNanos
                    renderTick++
                }
            }
        }
    }
    DisposableEffect(renderer) {
        onDispose(renderer::reset)
    }

    Spacer(
        modifier = modifier.drawWithCache {
            onDrawBehind {
                @Suppress("UNUSED_VARIABLE")
                val invalidateDrawingOnly = renderTick
                drawRect(Color(0xFF03050B))
                val center = Offset(renderer.centerX * size.width, renderer.centerY * size.height)
                val radius = renderer.scale * size.minDimension
                val alpha = renderer.opacity.coerceIn(0f, 1f)
                val blend = renderer.dominantBlend
                val morph = VoiceShapeLibrary.morph(blend.primary, blend.secondary)

                androidPath.rewind()
                morph.toPath(blend.progress, androidPath)
                transform.reset()
                transform.setScale(radius, radius)
                transform.postTranslate(center.x, center.y)
                androidPath.transform(transform)
                val shapePath = androidPath.asComposePath()

                if (alpha <= 0.015f) {
                    drawCircle(Color(0xFFB9F6FF).copy(alpha = 0.62f), radius.coerceAtLeast(3f), center)
                } else {
                    val voiceColor = vowelColor(blend.primary)
                    repeat(if (settings.reducedMotion) 2 else 4) { layer ->
                        drawCircle(
                            color = voiceColor.copy(alpha = alpha * (0.07f - layer * 0.011f).coerceAtLeast(0.02f)),
                            radius = radius * (1.35f + layer * 0.42f),
                            center = center,
                            blendMode = BlendMode.Screen
                        )
                    }
                    drawPath(shapePath, voiceColor.copy(alpha = 0.13f + alpha * 0.2f))
                    drawPath(
                        shapePath,
                        Color.White.copy(alpha = 0.32f + renderer.intensity * 0.6f),
                        style = Stroke(width = 2f + renderer.intensity * 7f)
                    )
                    drawVibratoContour(renderer, center, radius, vibratoPath, voiceColor)
                }

                renderer.particles.forEach { particle ->
                    val particleCenter = Offset(particle.x * size.width, particle.y * size.height)
                    val particleRadius = (particle.size * size.minDimension).coerceAtLeast(1f)
                    drawCircle(
                        Color(0xFFFFD86B).copy(alpha = particle.opacity * 0.16f),
                        particleRadius * 2.5f,
                        particleCenter,
                        blendMode = BlendMode.Screen
                    )
                    drawCircle(Color(0xFFFFF2B0).copy(alpha = particle.opacity), particleRadius, particleCenter)
                }
            }
        }
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVibratoContour(
    renderer: DefaultVoiceVisualRenderer,
    center: Offset,
    radius: Float,
    path: Path,
    color: Color
) {
    val blend = renderer.dominantBlend
    val (primaryX, primaryY) = vowelAspect(blend.primary)
    val (secondaryX, secondaryY) = vowelAspect(blend.secondary)
    val aspectX = primaryX + (secondaryX - primaryX) * blend.progress
    val aspectY = primaryY + (secondaryY - primaryY) * blend.progress
    val reducedFactor = if (renderer.settings.reducedMotion) 0.2f else renderer.settings.motionIntensity
    val deformation = renderer.vibrato * renderer.presence * renderer.tuning.vibratoAmplitude * reducedFactor
    path.reset()
    val points = if (renderer.settings.reducedMotion) 32 else 64
    repeat(points) { index ->
        val angle = index / points.toFloat() * PI.toFloat() * 2f
        val wave = 1f + sin(angle * 7f + renderer.elapsedSeconds * 7f) * deformation
        val point = Offset(
            center.x + cos(angle) * radius * aspectX * wave,
            center.y + sin(angle) * radius * aspectY * wave
        )
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(
        path,
        color.copy(alpha = 0.45f + renderer.intensity * 0.42f),
        style = Stroke(width = 1.5f + renderer.intensity * 3.5f)
    )
}

private fun vowelAspect(vowel: VisualVowel): Pair<Float, Float> = when (vowel) {
    VisualVowel.A -> 1.26f to 0.77f
    VisualVowel.E -> 1.2f to 0.61f
    VisualVowel.I -> 0.53f to 1.22f
    VisualVowel.O -> 0.98f to 0.98f
    VisualVowel.U -> 0.78f to 0.96f
    VisualVowel.UNKNOWN -> 0.9f to 0.9f
}

private fun vowelColor(vowel: VisualVowel): Color = when (vowel) {
    VisualVowel.A -> Color(0xFFFF5E8A)
    VisualVowel.E -> Color(0xFFFFB74D)
    VisualVowel.I -> Color(0xFF80D8FF)
    VisualVowel.O -> Color(0xFFCE93D8)
    VisualVowel.U -> Color(0xFF64FFDA)
    VisualVowel.UNKNOWN -> Color(0xFFB9F6FF)
}
