package com.nicolas.teleo.features.music.visual.voice

import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object VoiceShapeLibrary {
    const val VERTEX_COUNT = 20

    private val shapes = EnumMap<VisualVowel, RoundedPolygon>(VisualVowel::class.java).apply {
        VisualVowel.entries.forEach { put(it, createShape(it)) }
    }
    private val morphs = mutableMapOf<Pair<VisualVowel, VisualVowel>, Morph>()

    fun shape(vowel: VisualVowel): RoundedPolygon = requireNotNull(shapes[vowel])

    fun morph(from: VisualVowel, to: VisualVowel): Morph = morphs.getOrPut(from to to) {
        Morph(shape(from), shape(to))
    }

    internal fun vertices(vowel: VisualVowel): FloatArray = createVertices(vowel)

    private fun createShape(vowel: VisualVowel): RoundedPolygon = RoundedPolygon(
        vertices = createVertices(vowel),
        rounding = CornerRounding(radius = 0.16f, smoothing = 0.65f)
    )

    private fun createVertices(vowel: VisualVowel): FloatArray {
        val vertices = FloatArray(VERTEX_COUNT * 2)
        repeat(VERTEX_COUNT) { index ->
            val angle = index / VERTEX_COUNT.toFloat() * PI.toFloat() * 2f - PI.toFloat() / 2f
            val wave = sin(angle * 3f) * 0.055f
            val (radiusX, radiusY) = when (vowel) {
                VisualVowel.A -> 1.26f + wave to 0.77f + cos(angle * 2f) * 0.08f
                VisualVowel.E -> 1.2f + sin(angle * 2f) * 0.06f to 0.61f + wave
                VisualVowel.I -> 0.53f + wave * 0.4f to 1.22f + cos(angle * 2f) * 0.04f
                VisualVowel.O -> 0.98f + wave * 0.25f to 0.98f + wave * 0.25f
                VisualVowel.U -> {
                    val horizontal = if (sin(angle) < 0f) 0.62f else 0.88f
                    horizontal + wave * 0.3f to 0.96f
                }
                VisualVowel.UNKNOWN -> 0.9f + wave * 0.35f to 0.9f + wave * 0.35f
            }
            vertices[index * 2] = cos(angle) * radiusX
            vertices[index * 2 + 1] = sin(angle) * radiusY
        }
        return vertices
    }
}
