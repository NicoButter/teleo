package com.nicolas.teleo.features.music.visual

import com.nicolas.teleo.features.music.domain.VocalVisemeEvent
import com.nicolas.teleo.features.music.domain.VocalVisemeShape

/** Anatomical controls, all in 0..1. No audio analysis or animation clock. */
data class MouthPose(
    val jawOpen: Float = 0f,
    val lipOpen: Float = 0f,
    val lipWidth: Float = .65f,
    val lipRound: Float = .1f,
    val lipPucker: Float = 0f,
    val lipSpread: Float = .1f,
    val lipClosure: Float = 1f,
    val lipPressure: Float = 0f,
    val upperTeethVisible: Float = 0f,
    val lowerTeethVisible: Float = 0f,
    val lowerLipRaise: Float = 0f,
    val labiodentalContact: Float = 0f,
    val tongueVisible: Float = 0f,
    val tongueRaise: Float = 0f,
    val tongueForward: Float = 0f
) {
    fun blend(to: MouthPose, amount: Float): MouthPose {
        val f = amount.coerceIn(0f, 1f)
        if (f == 0f) return this
        if (f == 1f) return to
        fun mix(a: Float, b: Float) = a + (b - a) * f
        return MouthPose(
            mix(jawOpen, to.jawOpen), mix(lipOpen, to.lipOpen), mix(lipWidth, to.lipWidth),
            mix(lipRound, to.lipRound), mix(lipPucker, to.lipPucker), mix(lipSpread, to.lipSpread),
            mix(lipClosure, to.lipClosure), mix(lipPressure, to.lipPressure),
            mix(upperTeethVisible, to.upperTeethVisible), mix(lowerTeethVisible, to.lowerTeethVisible),
            mix(lowerLipRaise, to.lowerLipRaise), mix(labiodentalContact, to.labiodentalContact),
            mix(tongueVisible, to.tongueVisible), mix(tongueRaise, to.tongueRaise), mix(tongueForward, to.tongueForward)
        )
    }
    companion object { val REST = MouthPose() }
}

/** Cached Rhubarb poses: codes are visual groups, not phoneme recognition. */
object RhubarbMouthPoseMapper {
    private val poses = arrayOf(
        MouthPose(lipWidth = .76f, lipSpread = .3f, lipPressure = 1f), // A: MBP
        MouthPose(.12f, .18f, .87f, lipSpread = .8f, lipClosure = 0f, upperTeethVisible = .85f, lowerTeethVisible = .45f),
        MouthPose(.44f, .50f, .8f, lipSpread = .4f, lipClosure = 0f, upperTeethVisible = .55f, lowerTeethVisible = .25f, tongueVisible = .18f),
        MouthPose(.95f, .95f, .78f, lipClosure = 0f, upperTeethVisible = .45f, lowerTeethVisible = .3f, tongueVisible = .28f),
        MouthPose(.5f, .6f, .40f, .85f, .4f, lipClosure = 0f, upperTeethVisible = .08f),
        MouthPose(.10f, .22f, .13f, 1f, 1f, 0f, 0f), // F: UW
        MouthPose(.12f, .24f, .74f, lipSpread = .4f, lipClosure = 0f, upperTeethVisible = 1f, lowerLipRaise = 1f, labiodentalContact = 1f),
        MouthPose(.40f, .52f, .76f, lipSpread = .25f, lipClosure = 0f, upperTeethVisible = .72f, lowerTeethVisible = .15f, tongueVisible = 1f, tongueRaise = 1f, tongueForward = 1f),
        MouthPose.REST
    )
    fun poseFor(shape: VocalVisemeShape?): MouthPose = poses[(shape ?: VocalVisemeShape.X).ordinal]
}

/** Position-only coarticulation. Neighbor windows never consume a pose's middle. */
class MouthArticulationTimeline(private val visemes: List<VocalVisemeEvent>) {
    fun poseAt(positionMs: Long): MouthPose {
        val index = upperBound(visemes, positionMs) { it.startMs } - 1
        val current = visemes.getOrNull(index)?.takeIf { positionMs < it.endMs }
        if (current == null) {
            val previous = visemes.getOrNull(index)
            val next = visemes.getOrNull(index + 1)
            val gap = ((next?.startMs ?: Long.MAX_VALUE) - (previous?.endMs ?: 0L)).coerceAtLeast(0)
            if (previous != null) {
                val window = minOf(35L, (previous.endMs - previous.startMs) / 5, gap / 2)
                if (window > 0 && positionMs - previous.endMs < window) {
                    return transition(RhubarbMouthPoseMapper.poseFor(previous.shape), MouthPose.REST,
                        .5f + (positionMs - previous.endMs) / (2f * window))
                }
            }
            if (next != null) {
                val window = minOf(35L, (next.endMs - next.startMs) / 5, gap / 2)
                if (window > 0 && next.startMs - positionMs < window) {
                    return transition(MouthPose.REST, RhubarbMouthPoseMapper.poseFor(next.shape),
                        .5f - (next.startMs - positionMs) / (2f * window))
                }
            }
            return MouthPose.REST
        }
        val pose = RhubarbMouthPoseMapper.poseFor(current.shape)
        val previous = visemes.getOrNull(index - 1)
        val next = visemes.getOrNull(index + 1)
        val ownWindow = minOf(35L, (current.endMs - current.startMs) / 5)
        val beforeWindow = minOf(ownWindow, if (previous?.endMs == current.startMs)
            (previous.endMs - previous.startMs) / 5 else (current.startMs - (previous?.endMs ?: 0L)) / 2)
        val afterWindow = minOf(ownWindow, if (next?.startMs == current.endMs)
            (next.endMs - next.startMs) / 5 else ((next?.startMs ?: Long.MAX_VALUE) - current.endMs) / 2)
        if (beforeWindow > 0 && positionMs - current.startMs < beforeWindow) {
            val before = previous?.takeIf { it.endMs == current.startMs }?.shape
            return transition(RhubarbMouthPoseMapper.poseFor(before), pose,
                .5f + (positionMs - current.startMs) / (2f * beforeWindow))
        }
        if (afterWindow > 0 && current.endMs - positionMs < afterWindow) {
            val after = next?.takeIf { it.startMs == current.endMs }?.shape
            return transition(pose, RhubarbMouthPoseMapper.poseFor(after),
                .5f - (current.endMs - positionMs) / (2f * afterWindow))
        }
        return pose
    }
    private fun transition(a: MouthPose, b: MouthPose, fraction: Float): MouthPose {
        val f = fraction.coerceIn(0f, 1f)
        return a.blend(b, f * f * (3f - 2f * f))
    }
}

internal inline fun <T> upperBound(items: List<T>, position: Long, time: (T) -> Long): Int {
    var low = 0
    var high = items.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (time(items[middle]) <= position) low = middle + 1 else high = middle
    }
    return low
}
