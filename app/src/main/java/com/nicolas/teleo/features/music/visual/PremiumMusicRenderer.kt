package com.nicolas.teleo.features.music.visual

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.VisualInstrument
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val PremiumNavy = Color(0xFF040713)
private val PremiumViolet = Color(0xFF8B5CFF)
private val PremiumCyan = Color(0xFF42E8FF)
private val PremiumPink = Color(0xFFFF4FB8)
private val PremiumGold = Color(0xFFFFD84A)
private val PremiumTeal = Color(0xFF31E6B2)

/** Canvas renderer for the premium scene; it contains no sampling or clock logic. */
fun DrawScope.drawPremiumMusicScene(
    state: PremiumMusicState,
    settings: MusicVisualSettings,
    outerMouthPath: Path,
    innerMouthPath: Path,
    accentPath: Path
) {
    drawPremiumAmbient(state, settings)
    if (settings.particlesEnabled) drawAmbientContours(state, settings, accentPath)
    if (VisualInstrument.RHYTHM in settings.visibleInstruments) drawPremiumDrums(state, settings, accentPath)
    if (VisualInstrument.VOICE in settings.visibleInstruments) drawArticulatedMouth(state.voice, settings, outerMouthPath, innerMouthPath)
}

private fun DrawScope.drawPremiumAmbient(state: PremiumMusicState, settings: MusicVisualSettings) {
    drawRect(PremiumNavy)
    val brightness = if (settings.limitBrightnessChanges) .62f else .82f
    val energy = state.ambientEnergy.coerceIn(0f, 1f)
    val center = Offset(size.width * .5f, size.height * .46f)
    val radius = size.minDimension * (.42f + energy * .18f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                PremiumViolet.copy(alpha = (.08f + state.ambientMid * .11f) * brightness),
                PremiumCyan.copy(alpha = (.025f + state.ambientHigh * .045f) * brightness),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center,
        blendMode = BlendMode.Screen
    )
    drawOval(
        color = PremiumTeal.copy(alpha = .025f + state.ambientLow * .065f * brightness),
        topLeft = Offset(-size.width * .12f, size.height * .72f),
        size = Size(size.width * 1.24f, size.height * .32f),
        blendMode = BlendMode.Screen
    )
}

private fun DrawScope.drawAmbientContours(state: PremiumMusicState, settings: MusicVisualSettings, path: Path) {
    val layerCount = if (settings.reducedMotion) 2 else 4
    repeat(layerCount) { layer ->
        val fraction = layer / layerCount.toFloat()
        val y = size.height * (.18f + fraction * .58f)
        val bend = size.height * (.012f + state.ambientEnergy * .025f) * if (layer % 2 == 0) 1f else -1f
        path.reset()
        path.moveTo(0f, y)
        path.cubicTo(size.width * .28f, y + bend, size.width * .72f, y - bend, size.width, y)
        drawPath(path, Color.White.copy(alpha = .025f + state.ambientEnergy * .035f), style = Stroke(1.2f))
    }
}

private fun DrawScope.drawArticulatedMouth(
    voice: PremiumVoiceState,
    settings: MusicVisualSettings,
    outerPath: Path,
    innerPath: Path
) {
    val pose = voice.pose
    val pitchTravel = if (settings.reducedMotion || voice.pitchConfidence < .55f) 0f else (.5f - voice.pitchNormalized) * size.height * .09f
    val center = Offset(size.width * .5f, size.height * .39f + pitchTravel)
    val presence = voice.presence.coerceIn(0f, 1f)
    val intensity = voice.intensity.coerceIn(0f, 1f)
    val width = size.minDimension * (.24f + pose.width * .22f) * (1f + intensity * .05f)
    val outerHeight = size.minDimension * (.075f + pose.openness * .13f + pose.jawDrop * .045f)
    val innerWidth = width * (.68f + pose.roundness * .10f)
    val innerHeight = (outerHeight * (.18f + pose.openness * .68f)).coerceAtLeast(size.minDimension * .018f)
    val lipAlpha = (.58f + presence * .38f).coerceIn(0f, 1f)
    val lipColor = blend(PremiumPink, PremiumCyan, voice.spectralBrightness)

    mouthPath(outerPath, center, width, outerHeight, pose.upperLipLift, pose.roundness)
    drawPath(outerPath, lipColor.copy(alpha = .10f + presence * .12f))
    drawPath(outerPath, lipColor.copy(alpha = lipAlpha), style = Stroke(size.minDimension * (.010f + intensity * .008f)))
    drawPath(outerPath, lipColor.copy(alpha = .11f + presence * .12f), style = Stroke(size.minDimension * .035f), blendMode = BlendMode.Screen)

    mouthPath(innerPath, center.copy(y = center.y + pose.jawDrop * outerHeight * .08f), innerWidth, innerHeight, pose.upperLipLift * .25f, pose.roundness)
    drawPath(innerPath, Color(0xFF09030D).copy(alpha = .94f))
    drawPath(innerPath, Color.White.copy(alpha = .16f + presence * .20f), style = Stroke(size.minDimension * .0035f))

    if (pose.teethVisibility > .04f && innerHeight > size.minDimension * .025f) {
        val teethHeight = innerHeight * (.12f + pose.teethVisibility * .23f)
        drawRoundRect(
            color = Color(0xFFF7FBFF).copy(alpha = (.24f + pose.teethVisibility * .62f) * lipAlpha),
            topLeft = Offset(center.x - innerWidth * .36f, center.y - innerHeight * .34f),
            size = Size(innerWidth * .72f, teethHeight),
            cornerRadius = CornerRadius(teethHeight * .35f)
        )
    }
    if (pose.tongueVisibility > .04f && innerHeight > size.minDimension * .04f) {
        drawOval(
            color = Color(0xFFFF668D).copy(alpha = .22f + pose.tongueVisibility * .46f),
            topLeft = Offset(center.x - innerWidth * .25f, center.y + innerHeight * .08f),
            size = Size(innerWidth * .5f, innerHeight * (.18f + pose.tongueVisibility * .18f))
        )
    }
    if (presence < .08f) {
        drawLine(
            color = Color.White.copy(alpha = .36f),
            start = Offset(center.x - width * .31f, center.y),
            end = Offset(center.x + width * .31f, center.y),
            strokeWidth = size.minDimension * .004f
        )
    }
}

private fun mouthPath(path: Path, center: Offset, width: Float, height: Float, lift: Float, roundness: Float) {
    val halfWidth = width * .5f
    val halfHeight = height * .5f
    val control = (.36f + roundness * .20f).coerceIn(.32f, .60f)
    path.reset()
    path.moveTo(center.x - halfWidth, center.y)
    path.cubicTo(
        center.x - halfWidth * control,
        center.y - halfHeight * (1f + lift * .35f),
        center.x + halfWidth * control,
        center.y - halfHeight * (1f + lift * .35f),
        center.x + halfWidth,
        center.y
    )
    path.cubicTo(
        center.x + halfWidth * control,
        center.y + halfHeight,
        center.x - halfWidth * control,
        center.y + halfHeight,
        center.x - halfWidth,
        center.y
    )
    path.close()
}

private fun DrawScope.drawPremiumDrums(state: PremiumMusicState, settings: MusicVisualSettings, path: Path) {
    drawKick(state, settings)
    drawSnare(state, settings, path)
    drawHiHat(state, settings)
    drawCymbal(state, settings)
    drawTom(state, settings)
}

private fun DrawScope.drawKick(state: PremiumMusicState, settings: MusicVisualSettings) {
    val progress = accentProgress(state.positionMs, state.kick, 520L) ?: return
    val motion = if (settings.reducedMotion) .28f else 1f
    val radius = size.minDimension * (.055f + progress * .15f * motion)
    val alpha = (1f - progress) * (.50f + state.kick.intensity * .35f)
    val center = Offset(size.width * .5f, size.height * .84f)
    drawCircle(PremiumGold.copy(alpha = alpha * .14f), radius * 1.5f, center, blendMode = BlendMode.Screen)
    drawCircle(PremiumGold.copy(alpha = alpha), radius, center, style = Stroke(size.minDimension * .008f))
    drawCircle(PremiumGold.copy(alpha = alpha * .72f), radius * .42f, center, style = Stroke(size.minDimension * .014f))
}

private fun DrawScope.drawSnare(state: PremiumMusicState, settings: MusicVisualSettings, path: Path) {
    val progress = accentProgress(state.positionMs, state.snare, 360L) ?: return
    val spread = size.width * (.07f + progress * if (settings.reducedMotion) .05f else .16f)
    val center = Offset(size.width * .5f, size.height * .61f)
    val alpha = (1f - progress) * (.48f + state.snare.intensity * .45f)
    repeat(if (settings.reducedMotion) 4 else 8) { index ->
        val direction = if (index % 2 == 0) -1f else 1f
        val row = index / 2
        val y = center.y + (row - 1.5f) * size.minDimension * .025f
        val tip = Offset(center.x + direction * spread * (1f + row * .14f), y + direction * row * 2f)
        path.reset()
        path.moveTo(center.x + direction * size.minDimension * .018f, y)
        path.lineTo(tip.x, tip.y - size.minDimension * .012f)
        path.lineTo(tip.x, tip.y + size.minDimension * .012f)
        path.close()
        drawPath(path, PremiumPink.copy(alpha = alpha))
    }
    drawLine(PremiumPink.copy(alpha = alpha), Offset(center.x - spread * .38f, center.y), Offset(center.x + spread * .38f, center.y), size.minDimension * .006f)
}

private fun DrawScope.drawHiHat(state: PremiumMusicState, settings: MusicVisualSettings) {
    val progress = accentProgress(state.positionMs, state.hiHat, 170L) ?: return
    val alphaLimit = if (settings.flashesEnabled) .78f else .34f
    val alpha = (1f - progress) * alphaLimit * (.55f + state.hiHat.intensity * .4f)
    val angle = ((state.hiHat.timestampMs / 37L) % 8).toFloat() / 8f * PI.toFloat() * 2f
    val center = Offset(
        size.width * .5f + cos(angle) * size.width * .39f,
        size.height * .42f + sin(angle) * size.height * .30f
    )
    val length = size.minDimension * if (settings.reducedMotion) .025f else .045f
    repeat(4) { spoke ->
        val spokeAngle = spoke * PI.toFloat() / 4f
        val vector = Offset(cos(spokeAngle) * length, sin(spokeAngle) * length)
        drawLine(Color.White.copy(alpha = alpha), center - vector, center + vector, size.minDimension * .003f)
    }
}

private fun DrawScope.drawCymbal(state: PremiumMusicState, settings: MusicVisualSettings) {
    val progress = accentProgress(state.positionMs, state.cymbal, 650L) ?: return
    val flashFactor = if (settings.flashesEnabled) 1f else .38f
    val alpha = (1f - progress) * flashFactor * (.30f + state.cymbal.intensity * .30f)
    val width = size.width * (.42f + progress * if (settings.reducedMotion) .05f else .30f)
    val height = size.height * .16f
    val topLeft = Offset((size.width - width) * .5f, size.height * .12f)
    drawArc(
        color = Color.White.copy(alpha = alpha),
        startAngle = 190f,
        sweepAngle = 160f,
        useCenter = false,
        topLeft = topLeft,
        size = Size(width, height),
        style = Stroke(size.minDimension * .007f)
    )
    drawLine(PremiumCyan.copy(alpha = alpha * .8f), Offset(size.width * .5f, topLeft.y + height * .48f), Offset(size.width * .5f, topLeft.y + height * .82f), size.minDimension * .004f)
}

private fun DrawScope.drawTom(state: PremiumMusicState, settings: MusicVisualSettings) {
    val progress = accentProgress(state.positionMs, state.tom, 440L) ?: return
    val motion = if (settings.reducedMotion) .25f else 1f
    val radius = size.minDimension * (.035f + progress * .07f * motion)
    val side = if ((state.tom.timestampMs / 100L) % 2 == 0L) -.19f else .19f
    val center = Offset(size.width * (.5f + side), size.height * .70f)
    val alpha = (1f - progress) * (.45f + state.tom.intensity * .40f)
    drawOval(
        color = PremiumTeal.copy(alpha = alpha * .16f),
        topLeft = Offset(center.x - radius * 1.3f, center.y - radius),
        size = Size(radius * 2.6f, radius * 2f),
        blendMode = BlendMode.Screen
    )
    drawOval(
        color = PremiumTeal.copy(alpha = alpha),
        topLeft = Offset(center.x - radius, center.y - radius * .7f),
        size = Size(radius * 2f, radius * 1.4f),
        style = Stroke(size.minDimension * .006f)
    )
}

private fun accentProgress(positionMs: Long, accent: PremiumDrumAccent, durationMs: Long): Float? {
    if (accent.timestampMs == Long.MIN_VALUE) return null
    val age = positionMs - accent.timestampMs
    if (age !in 0..durationMs) return null
    return age / durationMs.toFloat()
}

private fun blend(from: Color, to: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = 1f
    )
}
