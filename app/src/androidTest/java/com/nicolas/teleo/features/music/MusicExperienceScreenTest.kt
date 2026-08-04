package com.nicolas.teleo.features.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nicolas.teleo.features.music.domain.HapticIntensity
import com.nicolas.teleo.features.music.domain.HapticSettings
import com.nicolas.teleo.features.music.domain.MusicAnalysisProgress
import com.nicolas.teleo.features.music.domain.MusicAnalysisStage
import com.nicolas.teleo.features.music.domain.MusicExperienceState
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.MusicTrack
import com.nicolas.teleo.features.music.ui.MusicPlaybackScreen
import com.nicolas.teleo.features.music.ui.MusicPreparationScreen
import com.nicolas.teleo.features.music.ui.MusicSelectionScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MusicExperienceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionButtonIsAccessibleAndActionable() {
        var selected = false
        composeRule.setContent {
            MaterialTheme {
                MusicSelectionScreen(null, true, HapticIntensity.MEDIUM, null, { selected = true }, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithTag("music_select_track").assertIsDisplayed().performClick()
        assertTrue(selected)
    }

    @Test
    fun preparationShowsProgress() {
        composeRule.setContent {
            MaterialTheme {
                MusicPreparationScreen(
                    MusicAnalysisProgress(MusicAnalysisStage.DETECTING_RHYTHM, 52, 10_000, "Analizando ritmo."),
                    null,
                    {}
                )
            }
        }
        composeRule.onNodeWithTag("music_preparation").assertIsDisplayed()
        composeRule.onNodeWithText("Analizando ritmo.").assertIsDisplayed()
    }

    @Test
    fun readyCountdownIsVisible() {
        composeRule.setContent { MaterialTheme { MusicPreparationScreen(null, 3, {}) } }
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("La experiencia está lista").assertIsDisplayed()
    }

    @Test
    fun playPauseControlRunsItsAction() {
        var toggled = false
        composeRule.setContent {
            MaterialTheme { PlaybackScreenForTest(onTogglePlay = { toggled = true }) }
        }
        composeRule.onNodeWithTag("music_play_pause").performClick()
        assertTrue(toggled)
    }

    @Test
    fun vibrationCanBeDisabled() {
        var enabled = true
        composeRule.setContent {
            MaterialTheme { PlaybackScreenForTest(onHapticsChanged = { enabled = it }) }
        }
        composeRule.onNodeWithTag("music_haptic_toggle").performClick()
        assertTrue(!enabled)
    }

    @androidx.compose.runtime.Composable
    private fun PlaybackScreenForTest(
        onTogglePlay: () -> Unit = {},
        onHapticsChanged: (Boolean) -> Unit = {}
    ) {
        val track = MusicTrack("id", "Demo", null, "content://demo", 20_000)
        val timeline = MusicTimeline("id", 20_000, 120f, 1, emptyList(), emptyList())
        MusicPlaybackScreen(
            state = MusicExperienceState.Playing(track, timeline, 0, 10_000, false, HapticSettings(), 0, false),
            selectedIntensity = HapticIntensity.MEDIUM,
            onBack = {},
            onTogglePlay = onTogglePlay,
            onRestart = {},
            onSeek = {},
            onHapticsChanged = onHapticsChanged,
            onIntensityChanged = {},
            onSyncOffsetChanged = {}
        )
    }
}
