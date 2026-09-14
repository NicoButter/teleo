package com.nicolas.teleo.features.music.visual

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform

/** Shared corner anchors and aperture for all anatomical layers, in mouth units. */
data class MouthGeometry(val halfWidth: Float, val apertureHalfWidth: Float, val upperOpening: Float, val lowerOpening: Float, val upperThickness: Float, val lowerThickness: Float) {
    val isClosed: Boolean get() = upperOpening + lowerOpening < .002f
    companion object {
        fun from(pose: MouthPose): MouthGeometry {
            val opening = pose.lipOpen * (1f - pose.lipClosure)
            val width = .24f + pose.lipWidth * .30f + pose.lipSpread * .10f - pose.lipPucker * .05f
            return MouthGeometry(
                width, width * (1f - pose.lipRound * .70f),
                opening * (.14f + pose.lipRound * .10f),
                opening * (.22f + pose.jawOpen * .20f) * (1f - pose.lowerLipRaise * .7f),
                .075f + pose.lipPucker * .04f - pose.lipPressure * .038f,
                .105f + pose.lipPucker * .055f - pose.lipPressure * .062f
            )
        }
    }
}

/** Reuses paths/brushes. Both the player and fixed-pose lab draw this exact renderer. */
class ArticulatoryMouthRenderer {
    private val cavity = Path()
    private val upperLip = Path()
    private val lowerLip = Path()
    private val detail = Path()
    private val tongue = Path()
    private val upperPaint = Brush.verticalGradient(listOf(Color(0xFFAB496D), Color(0xFFDC7790)), -.25f, .08f)
    private val lowerPaint = Brush.verticalGradient(listOf(Color(0xFFF399A3), Color(0xFFB95878)), 0f, .5f)
    private val tonguePaint = Brush.verticalGradient(listOf(Color(0xFFEE8D9D), Color(0xFFA33C62)), -.12f, .45f)
    private val backdrop = Brush.radialGradient(listOf(Color(0xFF090C1A), Color(0xFA090C1A), Color.Transparent), Offset.Zero, .95f)

    fun draw(scope: DrawScope, voice: PremiumVoiceState, center: Offset = Offset(scope.size.width * .5f, scope.size.height * .39f), scale: Float = scope.size.minDimension * .72f, backdropAlpha: Float = 1f) = with(scope) {
        val pose = voice.pose
        val g = MouthGeometry.from(pose)
        val w = g.halfWidth
        // Intensity affects a small highlight only. Pitch cannot move the reading target.
        withTransform({ translate(center.x, center.y); scale(scale, scale, Offset.Zero) }) {
            drawCircle(backdrop, .95f, Offset.Zero, alpha = backdropAlpha)
            // Jaw/chin support follows opening, behind the lips, without a second mouth outline.
            drawOval(Color(0xFFBB718B).copy(alpha = .10f), Offset(-w * 1.12f, .025f), Size(w * 2.24f, .24f + pose.jawOpen * .53f))
            detail.reset()
            detail.moveTo(-w * .70f, .21f + pose.jawOpen * .37f)
            detail.cubicTo(-w * .4f, .31f + pose.jawOpen * .39f, w * .4f, .31f + pose.jawOpen * .39f, w * .70f, .21f + pose.jawOpen * .37f)
            drawPath(detail, Color(0xFFDEA1B4).copy(alpha = .19f), style = Stroke(.009f))

            cavity.reset()
            cavity.moveTo(-w, 0f)
            upperInner(cavity, g)
            lowerInnerReverse(cavity, g)
            cavity.close()
            if (!g.isClosed) {
                drawPath(cavity, Color(0xFF220F20))
                clipPath(cavity) {
                    drawOval(Color(0xFF090810), Offset(-w * .8f, -g.upperOpening), Size(w * 1.6f, g.upperOpening + g.lowerOpening * .8f))
                    if (pose.tongueVisible > .01f) {
                        val tw = w * (.42f + pose.tongueForward * .16f)
                        val tip = g.lowerOpening * .6f - pose.tongueRaise * (g.lowerOpening * .6f + g.upperOpening * .9f)
                        tongue.reset()
                        tongue.moveTo(-tw, g.lowerOpening + .02f)
                        tongue.cubicTo(-tw, tip + .035f, -tw * .6f, tip, 0f, tip)
                        tongue.cubicTo(tw * .6f, tip, tw, tip + .035f, tw, g.lowerOpening + .02f)
                        tongue.close()
                        drawPath(tongue, tonguePaint, alpha = pose.tongueVisible)
                        drawLine(Color(0xFF8C365C).copy(alpha = pose.tongueVisible * .6f), Offset(0f, tip + .04f), Offset(0f, g.lowerOpening), .006f)
                    }
                    // Individual incisors, clipped by the aperture; lip overlays establish FV contact.
                    if (pose.upperTeethVisible > .01f) {
                        val bottom = if (pose.labiodentalContact > 0f)
                            -g.upperOpening * .35f + pose.labiodentalContact * (g.upperOpening * .35f + g.lowerOpening + .006f)
                        else -g.upperOpening * .48f
                        val toothWidth = w * .24f
                        repeat(6) { index ->
                            val x = (index - 3) * toothWidth
                            val edge = kotlin.math.abs(index - 2.5f) / 2.5f
                            drawRoundRect(Color(0xFFF9E9DC).copy(alpha = pose.upperTeethVisible),
                                Offset(x + .002f, -g.upperOpening - .04f),
                                Size(toothWidth - .004f, (bottom + g.upperOpening + .04f - edge * .015f).coerceAtLeast(.01f)), CornerRadius(.015f))
                        }
                    }
                    if (pose.lowerTeethVisible > .01f) {
                        repeat(6) { index ->
                            val toothWidth = w * .20f
                            drawRoundRect(Color(0xFFC9B7B5).copy(alpha = pose.lowerTeethVisible),
                                Offset((index - 3) * toothWidth + .002f, g.lowerOpening * .79f),
                                Size(toothWidth - .004f, .09f), CornerRadius(.012f))
                        }
                    }
                }
            }
            // Upper lip with Cupid's bow; every edge meets the same left/right corner.
            upperLip.reset()
            upperLip.moveTo(-w, 0f)
            upperLip.cubicTo(-w * .7f, -g.upperOpening - g.upperThickness * .9f, -w * .32f, -g.upperOpening - g.upperThickness * 1.3f, -w * .19f, -g.upperOpening - g.upperThickness)
            upperLip.cubicTo(-w * .08f, -g.upperOpening - g.upperThickness * .63f, w * .08f, -g.upperOpening - g.upperThickness * .63f, w * .19f, -g.upperOpening - g.upperThickness)
            upperLip.cubicTo(w * .32f, -g.upperOpening - g.upperThickness * 1.3f, w * .7f, -g.upperOpening - g.upperThickness * .9f, w, 0f)
            upperInnerReverse(upperLip, g)
            upperLip.close()
            drawPath(upperLip, upperPaint)

            lowerLip.reset()
            lowerLip.moveTo(-w, 0f)
            lowerInner(lowerLip, g)
            lowerLip.cubicTo(w * .65f, g.lowerOpening + g.lowerThickness * 1.3f, -w * .65f, g.lowerOpening + g.lowerThickness * 1.3f, -w, 0f)
            lowerLip.close()
            drawPath(lowerLip, lowerPaint)

            detail.reset()
            detail.moveTo(-w, 0f)
            upperInner(detail, g)
            drawPath(detail, Color(0xFF60243F), style = Stroke(.006f + pose.lipPressure * .004f))
            if (!g.isClosed) {
                detail.reset(); detail.moveTo(-w, 0f); lowerInner(detail, g)
                drawPath(detail, Color(0xFF753047), style = Stroke(.005f))
            }
            // A single shared seam when closed; never two separate parallel strokes.
            detail.reset()
            detail.moveTo(-w * .45f, g.lowerOpening + g.lowerThickness * .57f)
            detail.cubicTo(-w * .20f, g.lowerOpening + g.lowerThickness * .86f, w * .20f, g.lowerOpening + g.lowerThickness * .86f, w * .45f, g.lowerOpening + g.lowerThickness * .57f)
            drawPath(detail, Color(0xFFFFC6C7).copy(alpha = .24f + voice.intensity.coerceIn(0f, 1f) * .05f), style = Stroke(.008f))
            drawCircle(Color(0xFF76334F), .009f, Offset(-w, 0f))
            drawCircle(Color(0xFF76334F), .009f, Offset(w, 0f))
        }
    }

    private fun upperInner(path: Path, g: MouthGeometry) {
        val w = g.apertureHalfWidth
        path.lineTo(-w, 0f)
        path.cubicTo(-w, -g.upperOpening * 1.2f, -w * .20f, -g.upperOpening - .014f, 0f, -g.upperOpening)
        path.cubicTo(w * .20f, -g.upperOpening - .014f, w, -g.upperOpening * 1.2f, w, 0f)
        path.lineTo(g.halfWidth, 0f)
    }
    private fun upperInnerReverse(path: Path, g: MouthGeometry) {
        val w = g.apertureHalfWidth
        path.lineTo(w, 0f)
        path.cubicTo(w, -g.upperOpening * 1.2f, w * .20f, -g.upperOpening - .014f, 0f, -g.upperOpening)
        path.cubicTo(-w * .20f, -g.upperOpening - .014f, -w, -g.upperOpening * 1.2f, -w, 0f)
        path.lineTo(-g.halfWidth, 0f)
    }
    private fun lowerInner(path: Path, g: MouthGeometry) {
        if (g.isClosed) { upperInner(path, g); return }
        val w = g.apertureHalfWidth
        path.lineTo(-w, 0f)
        path.cubicTo(-w, g.lowerOpening * 1.33f, w, g.lowerOpening * 1.33f, w, 0f)
        path.lineTo(g.halfWidth, 0f)
    }
    private fun lowerInnerReverse(path: Path, g: MouthGeometry) {
        if (g.isClosed) { upperInnerReverse(path, g); return }
        val w = g.apertureHalfWidth
        path.lineTo(w, 0f)
        path.cubicTo(w, g.lowerOpening * 1.33f, -w, g.lowerOpening * 1.33f, -w, 0f)
        path.lineTo(-g.halfWidth, 0f)
    }
}
