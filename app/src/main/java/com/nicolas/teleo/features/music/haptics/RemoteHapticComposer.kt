package com.nicolas.teleo.features.music.haptics

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.TeleoChannelQuality

/**
 * Builds tactile cues exclusively from validated protocol events when Kinetra did
 * not provide an explicit haptic track. No mock signal is introduced.
 */
object RemoteHapticComposer {
    fun compose(
        explicitEvents: List<MusicEvent>,
        protocolEvents: List<MusicEvent>,
        channelQuality: Map<String, TeleoChannelQuality>
    ): List<MusicEvent> {
        if (explicitEvents.isNotEmpty()) return explicitEvents.sortedBy(MusicEvent::timestampMs)
        return protocolEvents.asSequence()
            .filter { event ->
                when (event.type) {
                    MusicEventType.KICK,
                    MusicEventType.SNARE,
                    MusicEventType.HI_HAT -> isReliable(channelQuality, "drums")
                    MusicEventType.CYMBAL -> event.intensity >= .75f && isReliable(channelQuality, "drums")
                    MusicEventType.BASS -> isReliable(channelQuality, "bass")
                    MusicEventType.SECTION_START -> true
                    else -> false
                }
            }
            .distinctBy { "${it.timestampMs}:${it.type}" }
            .sortedBy(MusicEvent::timestampMs)
            .toList()
    }

    private fun isReliable(quality: Map<String, TeleoChannelQuality>, channel: String): Boolean =
        (quality[channel] ?: TeleoChannelQuality.RELIABLE) == TeleoChannelQuality.RELIABLE
}
