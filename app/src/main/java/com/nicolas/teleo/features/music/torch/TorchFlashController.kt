package com.nicolas.teleo.features.music.torch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType

interface TorchFlashController {
    fun setEnabled(enabled: Boolean)
    fun onEvents(events: List<MusicEvent>)
    fun stop()
    fun isAvailable(): Boolean
}

object TorchFlashPolicy {
    fun isEligible(event: MusicEvent): Boolean = when {
        event.type == MusicEventType.CYMBAL && event.intensity >= .55f -> true
        event.type == MusicEventType.SECTION_START -> true
        event.intensity >= .85f && event.label.orEmpty().lowercase().let {
            "accent" in it || "important" in it || "hit" in it || "acento" in it
        } -> true
        else -> false
    }
}

class TorchFlashRateLimiter(private val minimumIntervalMs: Long = 1_200L) {
    private var lastFlashAtMs = Long.MIN_VALUE

    fun allow(events: List<MusicEvent>, nowMs: Long): Boolean {
        if (events.none(TorchFlashPolicy::isEligible)) return false
        if (lastFlashAtMs != Long.MIN_VALUE && nowMs - lastFlashAtMs < minimumIntervalMs) return false
        lastFlashAtMs = nowMs
        return true
    }
}

/** Optional enhancement. Missing permission/hardware is intentionally a silent no-op. */
class AndroidTorchFlashController(context: Context) : TorchFlashController {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val limiter = TorchFlashRateLimiter()
    private val turnOff = Runnable { setTorch(false) }
    private var enabled = false
    private val cameraId: String? by lazy {
        runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) stop()
    }

    override fun onEvents(events: List<MusicEvent>) {
        if (!enabled || !isAvailable() || !limiter.allow(events, SystemClock.elapsedRealtime())) return
        handler.removeCallbacks(turnOff)
        if (setTorch(true)) handler.postDelayed(turnOff, MAX_FLASH_DURATION_MS)
    }

    override fun stop() {
        handler.removeCallbacks(turnOff)
        setTorch(false)
    }

    override fun isAvailable(): Boolean = cameraId != null &&
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun setTorch(on: Boolean): Boolean {
        val id = cameraId ?: return false
        return runCatching { cameraManager.setTorchMode(id, on); true }.getOrDefault(false)
    }

    private companion object {
        const val MAX_FLASH_DURATION_MS = 65L
    }
}
