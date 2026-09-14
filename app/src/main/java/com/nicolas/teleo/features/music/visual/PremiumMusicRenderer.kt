package com.nicolas.teleo.features.music.visual

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.VisualInstrument

private val PremiumNavy = Color(0xFF040713)
private val PremiumViolet = Color(0xFF8B5CFF)
private val PremiumCyan = Color(0xFF42E8FF)
private val PremiumTeal = Color(0xFF31E6B2)

/** Canvas renderer for the premium scene; it contains no sampling or clock logic. */
fun DrawScope.drawPremiumMusicScene(
    state: PremiumMusicState,
    settings: MusicVisualSettings,
    mouthRenderer: ArticulatoryMouthRenderer,
    backgroundRenderer: RhythmicBackgroundRenderer,
    ensembleRenderer: PremiumEnsembleRenderer
) {
    drawPremiumAmbient(state, settings)
    if (VisualInstrument.RHYTHM in settings.visibleInstruments) backgroundRenderer.draw(this, state, settings)
    if (VisualInstrument.VOICE in settings.visibleInstruments) {
        mouthRenderer.draw(this, state.voice,
            center = Offset(size.width * .5f, size.height * .30f),
            scale = minOf(size.minDimension * .72f, size.height * .48f),
            backdropAlpha = .45f)
    }
    // Draw musical layers after the local mouth backdrop so it cannot erase them.
    ensembleRenderer.draw(this, state, settings)
    if (VisualInstrument.RHYTHM in settings.visibleInstruments) drawPremiumRhythmAccents(state, settings)
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
                PremiumViolet.copy(alpha = (.10f + state.ambientMid * .22f) * brightness),
                PremiumCyan.copy(alpha = (.035f + state.ambientHigh * .10f) * brightness),
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
