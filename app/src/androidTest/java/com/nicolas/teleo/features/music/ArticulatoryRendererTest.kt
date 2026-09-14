package com.nicolas.teleo.features.music

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.nicolas.teleo.features.music.domain.*
import com.nicolas.teleo.features.music.ui.GenerativeMusicCanvas
import com.nicolas.teleo.features.music.ui.VoiceVisualLabScreen
import com.nicolas.teleo.features.music.visual.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ArticulatoryRendererTest {
    @get:Rule val compose = createComposeRule()

    @Test fun labSelectsKeyPoses() {
        compose.setContent { MaterialTheme { VoiceVisualLabScreen({}) } }
        compose.onNodeWithTag("open_mouth_lab").performClick()
        for (shape in listOf("A", "F", "G", "H", "X")) {
            compose.onNodeWithTag("mouth_pose_$shape").performClick()
            compose.onNodeWithTag("mouth_lab_pose").assertTextContains(shape)
        }
    }

    @Test fun pausedCanvasReconstructsAfterSeekingBothWays() {
        val position = mutableLongStateOf(200L)
        val experience = TeleoExperience(visemes = listOf(VocalVisemeEvent(0, 400, VocalVisemeShape.A), VocalVisemeEvent(400, 1000, VocalVisemeShape.H)))
        compose.setContent {
            GenerativeMusicCanvas(MusicTimeline("visual", 1000, 120f, 1, emptyList(), emptyList()), experience,
                false, { position.longValue }, MusicVisualSettings(preset = VisualPreset.PREMIUM),
                Modifier.fillMaxSize().testTag("paused_mouth"), position.longValue)
        }
        compose.waitForIdle()
        val closed = compose.onNodeWithTag("paused_mouth").captureToImage()
        compose.runOnIdle { position.longValue = 600 }
        compose.waitForIdle()
        val tongue = compose.onNodeWithTag("paused_mouth").captureToImage()
        compose.runOnIdle { position.longValue = 200 }
        compose.waitForIdle()
        val returned = compose.onNodeWithTag("paused_mouth").captureToImage()
        fun pixels(image: androidx.compose.ui.graphics.ImageBitmap) = IntArray(image.width * image.height).also { image.readPixels(it) }
        assertFalse(pixels(closed).contentEquals(pixels(tongue)))
        assertArrayEquals(pixels(closed), pixels(returned))
    }

    @Test fun renderActualCanvasContactSheetsAndCheckTeethOcclusion() {
        val renderer = ArticulatoryMouthRenderer()
        val scope = CanvasDrawScope()
        val board = Bitmap.createBitmap(1080, 1320, Bitmap.Config.ARGB_8888)
        val androidCanvas = android.graphics.Canvas(board)
        androidCanvas.drawColor(android.graphics.Color.rgb(9, 12, 26))
        val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 24f; isAntiAlias = true }
        VocalVisemeShape.entries.forEachIndexed { index, shape ->
            val tile = Bitmap.createBitmap(360, 400, Bitmap.Config.ARGB_8888)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(tile.asImageBitmap()), Size(360f, 400f)) {
                renderer.draw(this, PremiumVoiceState(RhubarbMouthPoseMapper.poseFor(shape), shape, 1f, .5f), Offset(180f, 170f), 300f)
            }
            if (shape == VocalVisemeShape.A || shape == VocalVisemeShape.X || shape == VocalVisemeShape.F) {
                var whitePixels = 0
                for (y in 100..240) for (x in 70..290) {
                    val c = tile.getPixel(x, y)
                    if (android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) > 190 && android.graphics.Color.blue(c) > 170) whitePixels++
                }
                assertEquals("No teeth for $shape", 0, whitePixels)
            }
            androidCanvas.drawBitmap(tile, (index % 3) * 360f, (index / 3) * 440f, null)
            androidCanvas.drawText(shape.name, (index % 3) * 360f + 165f, (index / 3) * 440f + 410f, paint)
        }
        save(board, "mouth-poses.png")
        val background = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
        val bgCanvas = android.graphics.Canvas(background)
        val rhythm = RhythmicBackgroundRenderer()
        val accent = PremiumDrumAccent(100, 1f)
        val states = listOf(PremiumMusicState(kick = accent), PremiumMusicState(snare = accent), PremiumMusicState(tom = accent), PremiumMusicState(cymbal = accent), PremiumMusicState(hiHat = accent))
        states.forEachIndexed { row, state ->
            listOf(140L, 240L, 450L).forEachIndexed { col, time ->
                val tile = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(tile.asImageBitmap()), Size(360f, 360f)) {
                    drawRect(androidx.compose.ui.graphics.Color(0xFF040713))
                    rhythm.draw(this, state.copy(positionMs = time), MusicVisualSettings())
                }
                bgCanvas.drawBitmap(tile, col * 360f, row * 360f, null)
                bgCanvas.drawText("${listOf("kick", "snare", "tom", "cymbal", "hat")[row]} +${time - 100}ms", col * 360f + 20f, row * 360f + 30f, paint)
            }
        }
        save(background, "rhythm-families.png")
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("visual-review")!!
        directory.mkdirs()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
