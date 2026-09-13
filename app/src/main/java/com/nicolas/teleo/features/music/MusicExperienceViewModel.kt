package com.nicolas.teleo.features.music

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nicolas.teleo.features.music.data.DefaultTeleoMusicRepository
import com.nicolas.teleo.features.music.data.AndroidKeystoreResonanceCredentialStore
import com.nicolas.teleo.features.music.data.FileTeleoExperienceLocalDataSource
import com.nicolas.teleo.features.music.data.FileResonanceServerProfileStore
import com.nicolas.teleo.features.music.data.FileMusicTimelineRepository
import com.nicolas.teleo.features.music.data.MockMusicAnalyzer
import com.nicolas.teleo.features.music.data.MusicTimelineRepository
import com.nicolas.teleo.features.music.data.MusicTrackResolver
import com.nicolas.teleo.features.music.data.OkHttpTeleoMusicRemoteDataSource
import com.nicolas.teleo.features.music.data.RemoteMusicCachePaths
import com.nicolas.teleo.features.music.data.ResonanceServerProfileManager
import com.nicolas.teleo.features.music.data.TeleoMusicRepository
import com.nicolas.teleo.features.music.domain.AudioHashStatus
import com.nicolas.teleo.features.music.domain.AudioValidationResult
import com.nicolas.teleo.features.music.domain.HapticIntensity
import com.nicolas.teleo.features.music.domain.HapticSettings
import com.nicolas.teleo.features.music.domain.MusicAnalyzer
import com.nicolas.teleo.features.music.domain.MusicBufferConfig
import com.nicolas.teleo.features.music.domain.MusicExperienceSource
import com.nicolas.teleo.features.music.domain.MusicExperienceState
import com.nicolas.teleo.features.music.domain.MusicPlaybackState
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.MusicTrack
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.RemoteMusicExperience
import com.nicolas.teleo.features.music.domain.RemotePlaybackDebugInfo
import com.nicolas.teleo.features.music.domain.ResonanceServerProfile
import com.nicolas.teleo.features.music.domain.TeleoMusicCatalogTrack
import com.nicolas.teleo.features.music.domain.adjustedTimelinePosition
import com.nicolas.teleo.features.music.domain.eventsBetween
import com.nicolas.teleo.features.music.domain.validateAudioDuration
import com.nicolas.teleo.features.music.haptics.AndroidHapticMusicEngine
import com.nicolas.teleo.features.music.haptics.HapticMusicEngine
import com.nicolas.teleo.features.music.playback.ExoPlayerMusicPlaybackController
import com.nicolas.teleo.features.music.playback.MusicPlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MusicExperienceViewModel(application: Application) : AndroidViewModel(application) {
    private val analyzer: MusicAnalyzer = MockMusicAnalyzer()
    private val mockRepository: MusicTimelineRepository = FileMusicTimelineRepository(application)
    private val credentialStore = AndroidKeystoreResonanceCredentialStore(application)
    private val profileManager = ResonanceServerProfileManager(
        FileResonanceServerProfileStore(application), credentialStore
    ) { serverId -> RemoteMusicCachePaths.clearServer(application, serverId) }
    private val playbackController: MusicPlaybackController = ExoPlayerMusicPlaybackController(application)
    private val hapticEngine: HapticMusicEngine = AndroidHapticMusicEngine(application)

    private val mutableState = MutableStateFlow<MusicExperienceState>(MusicExperienceState.LoadingCatalog)
    val state: StateFlow<MusicExperienceState> = mutableState.asStateFlow()
    private val mutableServerProfiles = MutableStateFlow<List<ResonanceServerProfile>>(emptyList())
    val serverProfiles: StateFlow<List<ResonanceServerProfile>> = mutableServerProfiles.asStateFlow()
    private val mutableServerTestMessage = MutableStateFlow<String?>(null)
    val serverTestMessage: StateFlow<String?> = mutableServerTestMessage.asStateFlow()

    private var operationJob: Job? = null
    private var bufferJob: Job? = null
    private var currentTrack: MusicTrack? = null
    private var currentTimeline: MusicTimeline? = null
    private var currentRemoteExperience: RemoteMusicExperience? = null
    private var currentSource = MusicExperienceSource.MOCK
    private var hapticSettings = HapticSettings()
    private var visualSettings = MusicVisualSettings()
    private var bufferedUntilMs = 0L
    private var lastHapticPositionMs = 0L
    private var syncOffsetMs = 0
    private var remoteDebugInfo: RemotePlaybackDebugInfo? = null

    init {
        hapticEngine.updateSettings(hapticSettings)
        viewModelScope.launch { playbackController.playbackState.collectLatest(::onPlaybackState) }
        viewModelScope.launch { reloadProfiles(); refreshCatalog() }
    }

    fun refreshCatalog() {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            mutableState.value = MusicExperienceState.LoadingCatalog
            val enabled = mutableServerProfiles.value.filter { it.enabled }
            if (enabled.isEmpty()) {
                mutableState.value = MusicExperienceState.CatalogReady(
                    com.nicolas.teleo.features.music.domain.TeleoMusicCatalog(emptyList()), false,
                    "Agregá y activá un servidor para cargar experiencias. El modo demo sigue disponible."
                )
                return@launch
            }
            val loads = enabled.map { profile ->
                runCatching { repositoryFor(profile).loadCatalog() }.onSuccess { profileManager.markSynced(profile) }
            }
            reloadProfiles()
            val successful = loads.mapNotNull { it.getOrNull() }
            val failed = loads.count { it.isFailure }
            mutableState.value = MusicExperienceState.CatalogReady(
                com.nicolas.teleo.features.music.domain.TeleoMusicCatalog(successful.flatMap { it.catalog.tracks }),
                successful.isNotEmpty() && successful.all { it.offlineCache },
                when {
                    successful.isEmpty() -> "No se pudieron cargar los servidores habilitados. El modo demo sigue disponible."
                    failed > 0 -> "$failed servidor(es) no respondieron; se muestran los demás."
                    else -> successful.mapNotNull { it.warning }.firstOrNull()
                }
            )
        }
    }

    fun selectRemoteExperience(track: TeleoMusicCatalogTrack) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            mutableState.value = MusicExperienceState.DownloadingExperience(track)
            val profile = mutableServerProfiles.value.firstOrNull { it.id == track.serverId }
            if (profile == null) {
                mutableState.value = MusicExperienceState.Error("El servidor de esta experiencia ya no existe.", true)
                return@launch
            }
            runCatching { repositoryFor(profile).loadExperience(track) }
                .onSuccess { experience ->
                    currentRemoteExperience = experience
                    currentSource = MusicExperienceSource.REMOTE
                    mutableState.value = MusicExperienceState.AwaitingAudio(experience)
                }
                .onFailure { mutableState.value = MusicExperienceState.Error(it.message ?: "No se pudo descargar la experiencia.", true) }
        }
    }

    fun saveServerProfile(draft: ResonanceServerProfile, bearerToken: String?) {
        viewModelScope.launch {
            runCatching { profileManager.save(draft, bearerToken) }
                .onSuccess { reloadProfiles(); mutableServerTestMessage.value = null; refreshCatalog() }
                .onFailure { mutableServerTestMessage.value = it.message ?: "No se pudo guardar el servidor." }
        }
    }

    fun deleteServerProfile(profile: ResonanceServerProfile) {
        viewModelScope.launch { profileManager.delete(profile); reloadProfiles(); refreshCatalog() }
    }

    fun setServerEnabled(profile: ResonanceServerProfile, enabled: Boolean) {
        viewModelScope.launch { profileManager.setEnabled(profile, enabled); reloadProfiles(); refreshCatalog() }
    }

    fun testServerConnection(profile: ResonanceServerProfile) {
        viewModelScope.launch {
            mutableServerTestMessage.value = "Probando ${profile.name}…"
            runCatching {
                val remote = OkHttpTeleoMusicRemoteDataSource(profile, credentialStore).downloadCatalog()
                com.nicolas.teleo.features.music.data.TeleoMusicDtoMapper().catalog(remote.body, profile.id)
                profileManager.markSynced(profile)
            }.onSuccess {
                reloadProfiles(); mutableServerTestMessage.value = "Conexión correcta con ${profile.name}."
            }.onFailure { mutableServerTestMessage.value = it.message ?: "No se pudo conectar con el servidor." }
        }
    }

    private fun repositoryFor(profile: ResonanceServerProfile): TeleoMusicRepository = DefaultTeleoMusicRepository(
        profile,
        OkHttpTeleoMusicRemoteDataSource(profile, credentialStore),
        OkHttpTeleoMusicRemoteDataSource(profile, credentialStore),
        FileTeleoExperienceLocalDataSource(getApplication(), profile.id)
    )

    private suspend fun reloadProfiles() { mutableServerProfiles.value = profileManager.load() }

    fun selectAudioForRemote(track: MusicTrack) {
        val experience = currentRemoteExperience ?: return
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            mutableState.value = MusicExperienceState.ValidatingAudio(experience)
            when (val duration = validateAudioDuration(experience.timeline.durationMs, track.durationMs)) {
                is AudioValidationResult.Mismatch -> mutableState.value = MusicExperienceState.AwaitingAudio(experience, duration.message)
                is AudioValidationResult.Valid -> {
                    val expectedHash = experience.catalogTrack.sourceHash
                    val hashStatus = if (expectedHash?.isSha256 == true) {
                        val calculated = runCatching { MusicTrackResolver.sha256(getApplication(), track.uri.toUri()) }
                            .getOrElse {
                            mutableState.value = MusicExperienceState.AwaitingAudio(experience, "No se pudo verificar el audio seleccionado.")
                                return@launch
                            }
                        if (calculated.equals(expectedHash.value, ignoreCase = true)) AudioHashStatus.MATCH else {
                            mutableState.value = MusicExperienceState.AwaitingAudio(experience, "El audio seleccionado no corresponde a esta experiencia.")
                            return@launch
                        }
                    } else AudioHashStatus.NOT_AVAILABLE
                    currentTrack = track
                    currentTimeline = experience.timeline
                    currentSource = MusicExperienceSource.REMOTE
                    bufferedUntilMs = experience.timeline.durationMs
                    remoteDebugInfo = RemotePlaybackDebugInfo(
                        experience.serverId, experience.catalogTrack.id, experience.schemaVersion, experience.catalogTrack.experienceVersion,
                        experience.sourceUrl, experience.cacheStatus, experience.downloadedBytes, track.durationMs,
                        experience.timeline.durationMs, hashStatus
                    )
                    mutableState.value = MusicExperienceState.RemoteReady(track, experience)
                }
            }
        }
    }

    /** Explicit local demo path. It is never used by a selected remote experience. */
    fun selectTrack(track: MusicTrack) {
        operationJob?.cancel(); bufferJob?.cancel(); playbackController.pause(); hapticEngine.stop()
        currentTrack = track; currentTimeline = null; currentRemoteExperience = null; currentSource = MusicExperienceSource.MOCK
        remoteDebugInfo = null; bufferedUntilMs = 0
        mutableState.value = MusicExperienceState.TrackSelected(track)
    }

    fun openMockSelection() {
        currentRemoteExperience = null
        currentSource = MusicExperienceSource.MOCK
        currentTrack = null
        currentTimeline = null
        mutableState.value = MusicExperienceState.Idle
    }

    fun prepare() {
        when (val current = mutableState.value) {
            is MusicExperienceState.RemoteReady -> prepareRemote(current.track, current.experience)
            is MusicExperienceState.TrackSelected -> prepareMock(current.track)
            else -> Unit
        }
    }

    private fun prepareRemote(track: MusicTrack, experience: RemoteMusicExperience) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch { runCountdownAndPlay(track, experience.timeline, fullyBuffered = true) }
    }

    private fun prepareMock(track: MusicTrack) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                val cached = mockRepository.findByTrackHash(track.id)?.takeIf { it.analysisVersion >= 2 && it.featureFrames.isNotEmpty() }
                val timeline = cached ?: analyzer.analyze(track) { progress ->
                    bufferedUntilMs = progress.bufferedUntilMs
                    mutableState.value = MusicExperienceState.Analyzing(track, progress)
                }.also { mockRepository.save(track.id, it) }
                currentTimeline = timeline; bufferedUntilMs = if (cached != null) timeline.durationMs else minOf(timeline.durationMs, maxOf(bufferedUntilMs, MusicBufferConfig.INITIAL_BUFFER_MS))
                runCountdownAndPlay(track, timeline, cached != null)
            } catch (_: kotlinx.coroutines.CancellationException) { } catch (error: Exception) {
                mutableState.value = MusicExperienceState.Error(error.localizedMessage ?: "No se pudo preparar la experiencia.", true)
            }
        }
    }

    fun cancelPreparation() {
        operationJob?.cancel(); bufferJob?.cancel(); hapticEngine.stop()
        mutableState.value = when {
            currentRemoteExperience != null && currentTrack == null -> MusicExperienceState.AwaitingAudio(currentRemoteExperience!!)
            currentRemoteExperience != null && currentTrack != null -> MusicExperienceState.RemoteReady(currentTrack!!, currentRemoteExperience!!)
            currentTrack != null -> MusicExperienceState.TrackSelected(currentTrack!!)
            else -> MusicExperienceState.Idle
        }
    }

    fun backToCatalog() { operationJob?.cancel(); hapticEngine.stop(); currentTrack = null; currentTimeline = null; currentRemoteExperience = null; refreshCatalog() }
    fun togglePlayPause() { val playing = mutableState.value as? MusicExperienceState.Playing ?: return; if (playing.isPlaying) { playbackController.pause(); hapticEngine.stop() } else { lastHapticPositionMs = adjustedTimelinePosition(playing.playbackPositionMs, syncOffsetMs); playbackController.play() } }
    fun seekTo(positionMs: Long) { lastHapticPositionMs = adjustedTimelinePosition(positionMs, syncOffsetMs); hapticEngine.stop(); playbackController.seekTo(positionMs) }
    fun restart() = seekTo(0)
    fun playbackPositionNow(): Long = playbackController.currentPositionMs()
    fun setHapticsEnabled(enabled: Boolean) = updateHaptics(hapticSettings.copy(enabled = enabled))
    fun setHapticIntensity(intensity: HapticIntensity) = updateHaptics(hapticSettings.copy(intensityMultiplier = intensity.multiplier))
    fun setVisualPreset(preset: com.nicolas.teleo.features.music.domain.VisualPreset) = updateVisuals(visualSettings.copy(preset = preset))
    fun setVisualQuality(quality: com.nicolas.teleo.features.music.domain.VisualQuality) = updateVisuals(visualSettings.copy(quality = quality))
    fun setReducedMotion(enabled: Boolean) = updateVisuals(visualSettings.copy(reducedMotion = enabled, motionIntensity = if (enabled) .25f else 1f, particleIntensity = if (enabled) minOf(visualSettings.particleIntensity, .55f) else 1f))
    fun setFlashesEnabled(enabled: Boolean) = updateVisuals(visualSettings.copy(flashesEnabled = enabled))
    fun setStableLyrics(enabled: Boolean) = updateVisuals(visualSettings.copy(stableLyrics = enabled))
    fun setIntenseVisualWarningEnabled(enabled: Boolean) = updateVisuals(visualSettings.copy(intenseVisualWarningEnabled = enabled))
    fun setParticlesEnabled(enabled: Boolean) = updateVisuals(visualSettings.copy(particlesEnabled = enabled))
    fun toggleVisualInstrument(instrument: com.nicolas.teleo.features.music.domain.VisualInstrument) { val visible = visualSettings.visibleInstruments.toMutableSet().apply { if (!add(instrument)) remove(instrument) }; updateVisuals(visualSettings.copy(visibleInstruments = visible)) }
    fun setLyricsDisplayMode(mode: com.nicolas.teleo.features.music.domain.LyricsDisplayMode) = updateVisuals(visualSettings.copy(lyricsDisplayMode = mode))
    fun setLyricsTextScale(scale: Float) = updateVisuals(visualSettings.copy(lyricsTextScale = scale.coerceIn(.8f, 1.5f)))
    fun setSyncOffset(offsetMs: Int) { syncOffsetMs = offsetMs.coerceIn(-250, 250); lastHapticPositionMs = adjustedTimelinePosition((mutableState.value as? MusicExperienceState.Playing)?.playbackPositionMs ?: 0, syncOffsetMs); refreshPlayingState(playbackController.playbackState.value) }

    fun resetForExit() { operationJob?.cancel(); bufferJob?.cancel(); playbackController.pause(); playbackController.seekTo(0); hapticEngine.stop(); currentTrack = null; currentTimeline = null; currentRemoteExperience = null; bufferedUntilMs = 0; mutableState.value = MusicExperienceState.Idle }

    private suspend fun runCountdownAndPlay(track: MusicTrack, timeline: MusicTimeline, fullyBuffered: Boolean) {
        for (seconds in 3 downTo 1) { mutableState.value = MusicExperienceState.Countdown(seconds); delay(1_000) }
        playbackController.load(track); lastHapticPositionMs = 0; mutableState.value = playingState(track, timeline, playbackController.playbackState.value); playbackController.play()
        if (!fullyBuffered && currentSource == MusicExperienceSource.MOCK) startProgressiveBuffer(timeline.durationMs)
    }
    private fun startProgressiveBuffer(durationMs: Long) { bufferJob?.cancel(); bufferJob = viewModelScope.launch { while (bufferedUntilMs < durationMs) { delay(2_000); bufferedUntilMs = minOf(durationMs, bufferedUntilMs + MusicBufferConfig.ANALYSIS_WINDOW_MS - MusicBufferConfig.OVERLAP_MS); refreshPlayingState(playbackController.playbackState.value) } } }
    private fun onPlaybackState(playback: MusicPlaybackState) { if (playback.errorMessage != null) { hapticEngine.stop(); mutableState.value = MusicExperienceState.Error(playback.errorMessage, true); return }; val playing = mutableState.value as? MusicExperienceState.Playing ?: return; val position = adjustedTimelinePosition(playback.positionMs, syncOffsetMs); if (playback.isPlaying) { val distance = position - lastHapticPositionMs; if (distance in 0..500) playing.timeline.hapticEvents.filter { it.timestampMs > lastHapticPositionMs && it.timestampMs <= position }.forEach(hapticEngine::playEvent); lastHapticPositionMs = position } else { hapticEngine.stop(); lastHapticPositionMs = position }; mutableState.value = playingState(playing.track, playing.timeline, playback) }
    private fun refreshPlayingState(playback: MusicPlaybackState) { val playing = mutableState.value as? MusicExperienceState.Playing ?: return; mutableState.value = playingState(playing.track, playing.timeline, playback) }
    private fun playingState(track: MusicTrack, timeline: MusicTimeline, playback: MusicPlaybackState) = MusicExperienceState.Playing(track, timeline, playback.positionMs.coerceIn(0, timeline.durationMs), bufferedUntilMs, playback.isPlaying, hapticSettings, visualSettings, syncOffsetMs, bufferedUntilMs < timeline.durationMs && bufferedUntilMs - playback.positionMs < MusicBufferConfig.MINIMUM_SAFE_BUFFER_MS, currentSource, remoteDebugInfo)
    private fun updateHaptics(settings: HapticSettings) { hapticSettings = settings; hapticEngine.updateSettings(settings); refreshPlayingState(playbackController.playbackState.value) }
    private fun updateVisuals(settings: MusicVisualSettings) { visualSettings = settings; refreshPlayingState(playbackController.playbackState.value) }
    override fun onCleared() { hapticEngine.stop(); playbackController.release(); super.onCleared() }
}
