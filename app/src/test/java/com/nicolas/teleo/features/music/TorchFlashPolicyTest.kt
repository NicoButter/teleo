package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.torch.TorchFlashPolicy
import com.nicolas.teleo.features.music.torch.TorchFlashRateLimiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchFlashPolicyTest {
    @Test fun `torch accepts cymbal and section accents but not ordinary beats`() {
        assertTrue(TorchFlashPolicy.isEligible(event(MusicEventType.CYMBAL, .8f)))
        assertTrue(TorchFlashPolicy.isEligible(event(MusicEventType.SECTION_START, .4f)))
        assertTrue(TorchFlashPolicy.isEligible(event(MusicEventType.OTHER, .9f, "important hit")))
        assertFalse(TorchFlashPolicy.isEligible(event(MusicEventType.KICK, .95f)))
        assertFalse(TorchFlashPolicy.isEligible(event(MusicEventType.CYMBAL, .2f)))
    }

    @Test fun `torch rate limiter prevents rapid flashes`() {
        val limiter = TorchFlashRateLimiter(1_200)
        val accent = listOf(event(MusicEventType.CYMBAL, .8f))
        assertTrue(limiter.allow(accent, 1_000))
        assertFalse(limiter.allow(accent, 1_500))
        assertTrue(limiter.allow(accent, 2_200))
    }

    private fun event(type: MusicEventType, intensity: Float, label: String? = null) = MusicEvent(0, 40, type, intensity, label)
}
