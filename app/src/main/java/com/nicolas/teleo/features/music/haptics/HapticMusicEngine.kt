package com.nicolas.teleo.features.music.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.nicolas.teleo.features.music.domain.HapticSettings
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import kotlin.math.roundToInt

interface HapticMusicEngine {
    fun playEvent(event: MusicEvent)
    fun stop()
    fun isAvailable(): Boolean
    fun supportsAmplitudeControl(): Boolean
    fun updateSettings(settings: HapticSettings)
}

fun hapticAmplitude(intensity: Float, multiplier: Float): Int =
    (intensity.coerceIn(0f, 1f) * multiplier.coerceIn(0f, 1.5f) * 255f)
        .roundToInt()
        .coerceIn(1, 255)

class AndroidHapticMusicEngine(context: Context) : HapticMusicEngine {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private var settings = HapticSettings()

    override fun playEvent(event: MusicEvent) {
        if (!settings.enabled || !isAvailable() || !isEnabled(event.type)) return
        val amplitude = hapticAmplitude(event.intensity, settings.intensityMultiplier)
        val effect = if (supportsAmplitudeControl()) {
            when (event.type) {
                MusicEventType.KICK -> VibrationEffect.createOneShot(55, amplitude)
                MusicEventType.SNARE -> VibrationEffect.createWaveform(
                    longArrayOf(0, 28, 34, 28),
                    intArrayOf(0, amplitude, 0, (amplitude * 0.8f).roundToInt().coerceAtLeast(1)),
                    -1
                )
                MusicEventType.HI_HAT -> VibrationEffect.createOneShot(18, minOf(amplitude, 90))
                MusicEventType.BASS -> VibrationEffect.createOneShot(90, minOf(amplitude, 190))
                MusicEventType.SECTION_START -> VibrationEffect.createWaveform(
                    longArrayOf(0, 24, 32, 30, 32, 38),
                    intArrayOf(0, minOf(amplitude, 90), 0, minOf(amplitude, 150), 0, amplitude),
                    -1
                )
                else -> return
            }
        } else {
            val timings = when (event.type) {
                MusicEventType.KICK -> longArrayOf(0, 55)
                MusicEventType.SNARE -> longArrayOf(0, 28, 34, 28)
                MusicEventType.HI_HAT -> longArrayOf(0, 18)
                MusicEventType.BASS -> longArrayOf(0, 80)
                MusicEventType.SECTION_START -> longArrayOf(0, 20, 30, 28, 30, 36)
                else -> return
            }
            VibrationEffect.createWaveform(timings, -1)
        }
        vibrator.vibrate(effect)
    }

    override fun stop() = vibrator.cancel()

    override fun isAvailable(): Boolean = vibrator.hasVibrator()

    override fun supportsAmplitudeControl(): Boolean = vibrator.hasAmplitudeControl()

    override fun updateSettings(settings: HapticSettings) {
        this.settings = settings
        if (!settings.enabled) stop()
    }

    private fun isEnabled(type: MusicEventType): Boolean = when (type) {
        MusicEventType.KICK -> settings.kickEnabled
        MusicEventType.SNARE -> settings.snareEnabled
        MusicEventType.HI_HAT -> settings.hiHatEnabled
        MusicEventType.BASS -> settings.bassEnabled
        MusicEventType.SECTION_START -> settings.kickEnabled || settings.snareEnabled
        else -> false
    }
}
