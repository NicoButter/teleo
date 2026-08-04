package com.nicolas.teleo.features.music.data

import com.nicolas.teleo.features.music.domain.LyricLine
import com.nicolas.teleo.features.music.domain.MusicAnalysisProgress
import com.nicolas.teleo.features.music.domain.MusicAnalysisStage
import com.nicolas.teleo.features.music.domain.MusicAnalyzer
import com.nicolas.teleo.features.music.domain.MusicBufferConfig
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.MusicTrack
import kotlinx.coroutines.delay

class MockMusicAnalyzer(
    private val bpm: Float = 112f,
    private val simulatedStageDelayMs: Long = 180L,
    private val fallbackDurationMs: Long = 180_000L
) : MusicAnalyzer {

    override suspend fun analyze(
        track: MusicTrack,
        onProgress: (MusicAnalysisProgress) -> Unit
    ): MusicTimeline {
        val duration = (track.durationMs ?: fallbackDurationMs).coerceAtLeast(1_000L)
        val stages = listOf(
            ProgressStep(MusicAnalysisStage.PREPARING, 8, "Preparando la canción para que puedas verla y sentirla."),
            ProgressStep(MusicAnalysisStage.READING_AUDIO, 24, "Leyendo el audio."),
            ProgressStep(MusicAnalysisStage.DETECTING_RHYTHM, 52, "Analizando ritmo."),
            ProgressStep(MusicAnalysisStage.PREPARING_VISUALS, 76, "Preparando representaciones visuales."),
            ProgressStep(MusicAnalysisStage.PREPARING_HAPTICS, 92, "Preparando vibraciones."),
            ProgressStep(MusicAnalysisStage.READY, 100, "Experiencia lista.")
        )

        stages.forEach { step ->
            if (simulatedStageDelayMs > 0) delay(simulatedStageDelayMs)
            val buffered = when (step.stage) {
                MusicAnalysisStage.PREPARING -> 0L
                MusicAnalysisStage.READING_AUDIO -> minOf(duration, 4_000L)
                MusicAnalysisStage.DETECTING_RHYTHM -> minOf(duration, MusicBufferConfig.INITIAL_BUFFER_MS)
                MusicAnalysisStage.PREPARING_VISUALS -> minOf(duration, 14_000L)
                MusicAnalysisStage.PREPARING_HAPTICS -> minOf(duration, 18_000L)
                MusicAnalysisStage.READY -> minOf(duration, 20_000L)
                MusicAnalysisStage.FAILED -> 0L
            }
            onProgress(MusicAnalysisProgress(step.stage, step.percentage, buffered, step.message))
        }

        return MusicTimeline(
            trackId = track.id,
            durationMs = duration,
            bpm = bpm,
            analysisVersion = 1,
            events = generateEvents(duration),
            lyrics = generateDemoLyrics(duration)
        )
    }

    private fun generateEvents(durationMs: Long): List<MusicEvent> {
        val beatMs = (60_000f / bpm).toLong().coerceAtLeast(1L)
        val events = mutableListOf<MusicEvent>()
        var beat = 0
        var timestamp = 0L
        while (timestamp < durationMs) {
            val beatInBar = beat % 4
            events += MusicEvent(
                timestampMs = timestamp,
                durationMs = if (beatInBar % 2 == 0) 130 else 105,
                type = if (beatInBar % 2 == 0) MusicEventType.KICK else MusicEventType.SNARE,
                intensity = if (beatInBar == 0) 0.95f else 0.76f,
                label = if (beatInBar % 2 == 0) "Bombo" else "Redoblante"
            )
            events += MusicEvent(timestamp, 70, MusicEventType.HI_HAT, 0.32f, "Hi-hat")
            val halfBeat = timestamp + beatMs / 2
            if (halfBeat < durationMs) events += MusicEvent(halfBeat, 60, MusicEventType.HI_HAT, 0.25f, "Hi-hat")
            if (beat % 2 == 0) events += MusicEvent(timestamp, beatMs, MusicEventType.BASS, 0.62f, "Bajo")
            events += MusicEvent(
                timestamp,
                beatMs / 2,
                if ((beat / 4) % 2 == 0) MusicEventType.MELODY_UP else MusicEventType.MELODY_DOWN,
                0.48f + (beatInBar * 0.08f),
                if ((beat / 4) % 2 == 0) "Melodía sube" else "Melodía baja"
            )
            if (beat % 16 == 0) {
                events += MusicEvent(timestamp, 400, MusicEventType.SECTION_START, 0.9f, "Nueva sección")
            }
            beat++
            timestamp = beat * beatMs
        }

        var vocalStart = 4_000L
        while (vocalStart < durationMs) {
            val vocalEnd = minOf(vocalStart + 8_000L, durationMs)
            events += MusicEvent(vocalStart, vocalEnd - vocalStart, MusicEventType.VOCAL_START, 0.7f, "Voz")
            events += MusicEvent(vocalEnd, 160, MusicEventType.VOCAL_END, 0.45f, "Fin de voz")
            vocalStart += 16_000L
        }
        return events.sortedBy { it.timestampMs }
    }

    private fun generateDemoLyrics(durationMs: Long): List<LyricLine> {
        val lines = listOf(
            "La luz dibuja el ritmo",
            "cada pulso encuentra una forma",
            "el movimiento marca el camino",
            "y la canción se vuelve imagen"
        )
        val result = mutableListOf<LyricLine>()
        var start = 4_000L
        var index = 0
        while (start < durationMs) {
            val end = minOf(start + 3_600L, durationMs)
            result += LyricLine(start, end, lines[index % lines.size])
            start += 4_000L
            index++
        }
        return result
    }

    private data class ProgressStep(
        val stage: MusicAnalysisStage,
        val percentage: Int,
        val message: String
    )
}
