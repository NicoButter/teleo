package com.nicolas.teleo.features.music.domain

fun adjustedTimelinePosition(playbackPositionMs: Long, syncOffsetMs: Int): Long =
    (playbackPositionMs + syncOffsetMs).coerceAtLeast(0)

fun MusicTimeline.activeEventsAt(positionMs: Long): List<MusicEvent> = events.filter { event ->
    positionMs >= event.timestampMs && positionMs <= event.timestampMs + event.durationMs
}

fun MusicTimeline.activeLyricAt(positionMs: Long): LyricLine? =
    lyrics.firstOrNull { positionMs in it.startMs..it.endMs }

fun MusicTimeline.eventsBetween(startExclusiveMs: Long, endInclusiveMs: Long): List<MusicEvent> {
    if (events.isEmpty() || endInclusiveMs < startExclusiveMs) return emptyList()
    return events.filter { it.timestampMs > startExclusiveMs && it.timestampMs <= endInclusiveMs }
}

/** Binary search keeps vocal lookup cheap and remains correct after backward seeks. */
fun MusicTimeline.activeVisemeAt(positionMs: Long): VocalVisemeEvent? {
    var low = 0
    var high = visemes.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (visemes[mid].startMs <= positionMs) {
            candidate = mid
            low = mid + 1
        } else high = mid - 1
    }
    return visemes.getOrNull(candidate)?.takeIf { positionMs < it.endMs }
}
