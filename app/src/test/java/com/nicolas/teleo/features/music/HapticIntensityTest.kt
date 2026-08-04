package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.haptics.hapticAmplitude
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticIntensityTest {
    @Test
    fun `haptic intensity converts to safe Android amplitude`() {
        val soft = hapticAmplitude(0.5f, 0.55f)
        val medium = hapticAmplitude(0.5f, 1f)
        val strong = hapticAmplitude(0.5f, 1.35f)

        assertTrue(soft < medium)
        assertTrue(medium < strong)
        assertEquals(255, hapticAmplitude(1f, 1.5f))
        assertEquals(1, hapticAmplitude(0f, 0f))
    }
}
