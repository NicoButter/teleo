package com.nicolas.teleo.features.music.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nicolas.teleo.features.music.MusicExperienceViewModel
import com.nicolas.teleo.features.music.data.MusicTrackResolver
import com.nicolas.teleo.features.music.domain.HapticIntensity
import com.nicolas.teleo.features.music.domain.LyricsDisplayMode
import com.nicolas.teleo.features.music.domain.MusicAnalysisProgress
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicExperienceState
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.MusicTrack
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.TimedLyricLine
import com.nicolas.teleo.features.music.domain.VisualPreset
import com.nicolas.teleo.features.music.domain.VisualQuality
import com.nicolas.teleo.features.music.domain.VisualInstrument
import com.nicolas.teleo.features.music.domain.activeWordAt
import com.nicolas.teleo.features.music.domain.activeLyricAt
import com.nicolas.teleo.features.music.domain.adjustedTimelinePosition
import com.nicolas.teleo.features.music.visual.LinearMusicFeatureInterpolator
import java.util.Locale

private val MusicDark = Color(0xFF0A0E14)
private val MusicCyan = Color(0xFF00E5FF)
private val MusicTeal = Color(0xFF1DE9B6)
private val MusicMagenta = Color(0xFFFF00FF)
private val MusicYellow = Color(0xFFFEE715)

@Composable
fun MusicExperienceRoute(
    onExit: () -> Unit,
    viewModel: MusicExperienceViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedIntensity by remember { mutableStateOf(HapticIntensity.MEDIUM) }
    var hapticsEnabled by remember { mutableStateOf(true) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { MusicTrackResolver.resolve(context, uri) }
                .onSuccess {
                    selectionError = null
                    viewModel.selectTrack(it)
                }
                .onFailure { selectionError = "No se pudo leer el archivo seleccionado." }
        }
    }
    val leaveFeature = {
        viewModel.resetForExit()
        onExit()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MusicDark) {
        when (val current = state) {
            MusicExperienceState.Idle -> MusicSelectionScreen(
                track = null,
                hapticsEnabled = hapticsEnabled,
                selectedIntensity = selectedIntensity,
                errorMessage = selectionError,
                onSelectTrack = { picker.launch(arrayOf("audio/*")) },
                onPrepare = {},
                onHapticsChanged = {
                    hapticsEnabled = it
                    viewModel.setHapticsEnabled(it)
                },
                onIntensityChanged = {
                    selectedIntensity = it
                    viewModel.setHapticIntensity(it)
                },
                onBack = leaveFeature
            )
            is MusicExperienceState.TrackSelected -> MusicSelectionScreen(
                track = current.track,
                hapticsEnabled = hapticsEnabled,
                selectedIntensity = selectedIntensity,
                errorMessage = selectionError,
                onSelectTrack = { picker.launch(arrayOf("audio/*")) },
                onPrepare = viewModel::prepare,
                onHapticsChanged = {
                    hapticsEnabled = it
                    viewModel.setHapticsEnabled(it)
                },
                onIntensityChanged = {
                    selectedIntensity = it
                    viewModel.setHapticIntensity(it)
                },
                onBack = leaveFeature
            )
            is MusicExperienceState.Analyzing -> MusicPreparationScreen(
                progress = current.progress,
                countdown = null,
                onCancel = viewModel::cancelPreparation
            )
            is MusicExperienceState.Countdown -> MusicPreparationScreen(
                progress = null,
                countdown = current.secondsRemaining,
                onCancel = viewModel::cancelPreparation
            )
            is MusicExperienceState.Playing -> MusicPlaybackScreen(
                state = current,
                selectedIntensity = selectedIntensity,
                onBack = leaveFeature,
                onTogglePlay = viewModel::togglePlayPause,
                onRestart = viewModel::restart,
                onSeek = viewModel::seekTo,
                onHapticsChanged = {
                    hapticsEnabled = it
                    viewModel.setHapticsEnabled(it)
                },
                onIntensityChanged = {
                    selectedIntensity = it
                    viewModel.setHapticIntensity(it)
                },
                onSyncOffsetChanged = viewModel::setSyncOffset,
                playbackPositionProvider = viewModel::playbackPositionNow,
                onVisualPresetChanged = viewModel::setVisualPreset,
                onVisualQualityChanged = viewModel::setVisualQuality,
                onReducedMotionChanged = viewModel::setReducedMotion,
                onFlashesChanged = viewModel::setFlashesEnabled,
                onStableLyricsChanged = viewModel::setStableLyrics,
                onIntenseVisualWarningChanged = viewModel::setIntenseVisualWarningEnabled,
                onParticlesEnabledChanged = viewModel::setParticlesEnabled,
                onToggleInstrument = viewModel::toggleVisualInstrument,
                onLyricsDisplayModeChanged = viewModel::setLyricsDisplayMode,
                onLyricsTextScaleChanged = viewModel::setLyricsTextScale
            )
            is MusicExperienceState.Error -> MusicErrorScreen(
                message = current.message,
                recoverable = current.recoverable,
                onRetry = viewModel::prepare,
                onBack = leaveFeature
            )
        }
    }
}

@Composable
fun MusicSelectionScreen(
    track: MusicTrack?,
    hapticsEnabled: Boolean,
    selectedIntensity: HapticIntensity,
    errorMessage: String?,
    onSelectTrack: () -> Unit,
    onPrepare: () -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onIntensityChanged: (HapticIntensity) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FeatureHeader("TELEO MÚSICA", "EXPERIMENTAL", onBack)
        Spacer(Modifier.height(20.dp))
        Icon(Icons.Default.LibraryMusic, null, tint = MusicCyan, modifier = Modifier.size(62.dp))
        Text(
            "Transformá música en imagen y tacto",
            color = Color.White,
            fontSize = 25.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        Text(
            "Elegí una canción local. Teleo prepara carriles visuales, letra de demostración y pulsos hápticos sincronizados.",
            color = Color.White.copy(alpha = 0.68f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 680.dp).padding(top = 8.dp)
        )
        Spacer(Modifier.height(22.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
            color = Color.White.copy(alpha = 0.04f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MusicCyan.copy(alpha = 0.35f))
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("CANCIÓN", color = MusicCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    track?.title ?: "Todavía no seleccionaste un archivo",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                track?.artist?.let { Text(it, color = Color.White.copy(alpha = 0.58f)) }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onSelectTrack,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("music_select_track"),
                    border = BorderStroke(1.dp, MusicCyan)
                ) {
                    Icon(Icons.Default.LibraryMusic, "Seleccionar canción")
                    Spacer(Modifier.width(8.dp))
                    Text(if (track == null) "SELECCIONAR CANCIÓN" else "CAMBIAR CANCIÓN")
                }
                errorMessage?.let { Text(it, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        HapticControls(
            enabled = hapticsEnabled,
            selectedIntensity = selectedIntensity,
            onEnabledChanged = onHapticsChanged,
            onIntensityChanged = onIntensityChanged
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onPrepare,
            enabled = track != null,
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).height(52.dp).testTag("music_prepare"),
            colors = ButtonDefaults.buttonColors(containerColor = MusicCyan, contentColor = MusicDark)
        ) {
            Text("PREPARAR EXPERIENCIA", fontWeight = FontWeight.Black)
        }
        Text(
            "Teleo Música ofrece una experiencia visual y táctil. No reemplaza dispositivos auditivos ni tratamientos médicos.",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 720.dp).padding(top = 18.dp)
        )
    }
}

@Composable
fun MusicPreparationScreen(
    progress: MusicAnalysisProgress?,
    countdown: Int?,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(28.dp).testTag("music_preparation"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 620.dp)) {
            if (countdown != null) {
                Text(
                    countdown.toString(),
                    color = MusicYellow,
                    fontSize = 112.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = "La experiencia comienza en $countdown"
                    }
                )
                Text("La experiencia está lista", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            } else {
                CircularProgressIndicator(color = MusicCyan, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
                Spacer(Modifier.height(22.dp))
                Text(
                    progress?.message ?: "Preparando la canción para que puedas verla y sentirla.",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(
                    progress = { (progress?.percentage ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MusicCyan,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stageLabel(progress), color = Color.White.copy(alpha = 0.62f))
                    Text("${progress?.percentage ?: 0}%", color = MusicCyan, fontWeight = FontWeight.Bold)
                }
                Text(
                    "${((progress?.bufferedUntilMs ?: 0) / 1000)} segundos preparados",
                    color = MusicTeal,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            Spacer(Modifier.height(28.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("CANCELAR") }
        }
    }
}

@Composable
fun MusicPlaybackScreen(
    state: MusicExperienceState.Playing,
    selectedIntensity: HapticIntensity,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onRestart: () -> Unit,
    onSeek: (Long) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onIntensityChanged: (HapticIntensity) -> Unit,
    onSyncOffsetChanged: (Int) -> Unit,
    playbackPositionProvider: () -> Long = { state.playbackPositionMs },
    onVisualPresetChanged: (VisualPreset) -> Unit = {},
    onVisualQualityChanged: (VisualQuality) -> Unit = {},
    onReducedMotionChanged: (Boolean) -> Unit = {},
    onFlashesChanged: (Boolean) -> Unit = {},
    onStableLyricsChanged: (Boolean) -> Unit = {},
    onIntenseVisualWarningChanged: (Boolean) -> Unit = {},
    onParticlesEnabledChanged: (Boolean) -> Unit = {},
    onToggleInstrument: (VisualInstrument) -> Unit = {},
    onLyricsDisplayModeChanged: (LyricsDisplayMode) -> Unit = {},
    onLyricsTextScaleChanged: (Float) -> Unit = {}
) {
    val adjustedPosition = adjustedTimelinePosition(state.playbackPositionMs, state.syncOffsetMs)
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp).testTag("music_playback")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver a inicio", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(state.track.title, color = Color.White, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("TELEO MÚSICA · EXPERIMENTAL", color = MusicCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            BufferBadge(state.bufferedUntilMs, state.timeline.durationMs, state.isRecoveringBuffer)
        }
        MusicVisualStage(
            timeline = state.timeline,
            positionMs = adjustedPosition,
            isPlaying = state.isPlaying,
            playbackPositionProvider = playbackPositionProvider,
            settings = state.visualSettings,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        if (state.isRecoveringBuffer) {
            Text(
                "Recuperando contenido preparado… la experiencia continúa.",
                color = MusicYellow,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Slider(
            value = state.playbackPositionMs.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..state.timeline.durationMs.coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(state.playbackPositionMs), color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
            Text(formatTime(state.timeline.durationMs), color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onRestart, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.06f), CircleShape)) {
                Icon(Icons.Default.Refresh, "Reiniciar canción", tint = Color.White)
            }
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(52.dp).background(MusicCyan, CircleShape).testTag("music_play_pause")
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "Pausar" else "Reproducir",
                    tint = MusicDark
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Vibration, null, tint = MusicTeal)
                Switch(
                    checked = state.hapticSettings.enabled,
                    onCheckedChange = onHapticsChanged,
                    modifier = Modifier.testTag("music_haptic_toggle")
                        .semantics { contentDescription = "Activar o desactivar vibraciones" }
                )
            }
            IntensityChips(selectedIntensity, onIntensityChanged, compact = true)
            Text("Sincronía ${state.syncOffsetMs} ms", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            OutlinedButton(onClick = { onSyncOffsetChanged(state.syncOffsetMs - 50) }, enabled = state.syncOffsetMs > -250) {
                Text("−50")
            }
            OutlinedButton(onClick = { onSyncOffsetChanged(state.syncOffsetMs + 50) }, enabled = state.syncOffsetMs < 250) {
                Text("+50")
            }
        }
        VisualControls(
            settings = state.visualSettings,
            onPresetChanged = onVisualPresetChanged,
            onQualityChanged = onVisualQualityChanged,
            onReducedMotionChanged = onReducedMotionChanged,
            onFlashesChanged = onFlashesChanged,
            onStableLyricsChanged = onStableLyricsChanged,
            onIntenseVisualWarningChanged = onIntenseVisualWarningChanged,
            onParticlesEnabledChanged = onParticlesEnabledChanged,
            onToggleInstrument = onToggleInstrument,
            onLyricsModeChanged = onLyricsDisplayModeChanged,
            onLyricsTextScaleChanged = onLyricsTextScaleChanged
        )
    }
}

@Composable
private fun MusicVisualStage(
    timeline: MusicTimeline,
    positionMs: Long,
    isPlaying: Boolean,
    playbackPositionProvider: () -> Long,
    settings: MusicVisualSettings,
    modifier: Modifier = Modifier
) {
    val lyric = timeline.activeLyricAt(positionMs)
    val interpolator = remember { LinearMusicFeatureInterpolator() }
    val features = interpolator.interpolate(timeline.featureFrames, positionMs)
    Box(
        modifier = modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .border(1.dp, MusicCyan.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
    ) {
        if (settings.preset == VisualPreset.LANES) {
            Column(Modifier.fillMaxSize().background(Color(0xFF050810)).padding(8.dp)) {
                LyricsOverlay(lyric, positionMs, features.vocalPresence, settings, Modifier.fillMaxWidth())
                MusicLanes(timeline, positionMs, Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            GenerativeMusicCanvas(
                timeline = timeline,
                isPlaying = isPlaying,
                playbackPositionProvider = playbackPositionProvider,
                settings = settings,
                modifier = Modifier.fillMaxSize().testTag("music_generative_canvas")
            )
            LyricsOverlay(
                lyric = lyric,
                positionMs = positionMs,
                vocalPresence = features.vocalPresence,
                settings = settings,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(0.88f).padding(top = 12.dp)
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                color = MusicDark.copy(alpha = 0.72f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MusicTeal.copy(alpha = 0.4f))
            ) {
                Text(
                    "SECCIÓN · ${features.sectionId?.uppercase() ?: "—"}",
                    color = MusicTeal,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
            if (settings.intenseVisualWarningEnabled && settings.preset == VisualPreset.SYNESTHETIC && !settings.reducedMotion) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    color = MusicYellow.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MusicYellow.copy(alpha = 0.45f))
                ) {
                    Text(
                        "MOVIMIENTO INTENSO · podés activar movimiento reducido",
                        color = MusicYellow,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsOverlay(
    lyric: TimedLyricLine?,
    positionMs: Long,
    vocalPresence: Float,
    settings: MusicVisualSettings,
    modifier: Modifier = Modifier
) {
    if (settings.lyricsDisplayMode == LyricsDisplayMode.HIDDEN) return
    val scale = if (settings.stableLyrics || settings.reducedMotion) 1f else 1f + vocalPresence * 0.035f
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        color = MusicDark.copy(alpha = 0.68f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (lyric == null) Color.White.copy(alpha = 0.12f) else MusicMagenta.copy(alpha = 0.42f))
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (lyric == null) {
                Text("Esta parte no tiene letra", color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center)
            } else {
                if (settings.lyricsDisplayMode != LyricsDisplayMode.TRANSLATED) {
                    Text(
                        highlightedOriginal(lyric, positionMs),
                        color = Color.White,
                        fontSize = (19f * settings.lyricsTextScale).sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    )
                }
                if (settings.lyricsDisplayMode != LyricsDisplayMode.ORIGINAL) {
                    val translated = lyric.translations[settings.targetTranslationLanguage]
                    Text(
                        translated ?: "Traducción no disponible",
                        color = if (translated == null) Color.White.copy(alpha = 0.48f) else MusicTeal,
                        fontSize = (15f * settings.lyricsTextScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun highlightedOriginal(lyric: TimedLyricLine, positionMs: Long) = buildAnnotatedString {
    val activeWord = lyric.activeWordAt(positionMs)
    if (lyric.words.isEmpty()) {
        append(lyric.originalText)
    } else {
        lyric.words.forEachIndexed { index, word ->
            if (index > 0) append(' ')
            withStyle(
                SpanStyle(
                    color = if (word == activeWord) MusicYellow else Color.White,
                    fontWeight = if (word == activeWord) FontWeight.Black else FontWeight.Bold
                )
            ) { append(word.text) }
        }
    }
}

@Composable
private fun VisualControls(
    settings: MusicVisualSettings,
    onPresetChanged: (VisualPreset) -> Unit,
    onQualityChanged: (VisualQuality) -> Unit,
    onReducedMotionChanged: (Boolean) -> Unit,
    onFlashesChanged: (Boolean) -> Unit,
    onStableLyricsChanged: (Boolean) -> Unit,
    onIntenseVisualWarningChanged: (Boolean) -> Unit,
    onParticlesEnabledChanged: (Boolean) -> Unit,
    onToggleInstrument: (VisualInstrument) -> Unit,
    onLyricsModeChanged: (LyricsDisplayMode) -> Unit,
    onLyricsTextScaleChanged: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ESCENA", color = MusicCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
            listOf(VisualPreset.SYNESTHETIC, VisualPreset.LANES, VisualPreset.MINIMAL).forEach { preset ->
                FilterChip(
                    selected = settings.preset == preset,
                    onClick = { onPresetChanged(preset) },
                    label = { Text(preset.label, fontSize = 10.sp) },
                    modifier = Modifier.testTag("music_preset_${preset.name.lowercase()}")
                )
            }
            Text("CALIDAD", color = MusicCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
            VisualQuality.entries.forEach { quality ->
                FilterChip(
                    selected = settings.quality == quality,
                    onClick = { onQualityChanged(quality) },
                    label = { Text(quality.label, fontSize = 10.sp) }
                )
            }
            AccessibilitySwitch("Movimiento reducido", settings.reducedMotion, onReducedMotionChanged, "music_reduced_motion")
            AccessibilitySwitch("Destellos", settings.flashesEnabled, onFlashesChanged, "music_flashes")
            AccessibilitySwitch("Letra estable", settings.stableLyrics, onStableLyricsChanged, "music_stable_lyrics")
            AccessibilitySwitch("Aviso intenso", settings.intenseVisualWarningEnabled, onIntenseVisualWarningChanged, "music_visual_warning")
            AccessibilitySwitch("Partículas", settings.particlesEnabled, onParticlesEnabledChanged, "music_particles")
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LETRA", color = MusicMagenta, fontSize = 10.sp, fontWeight = FontWeight.Black)
            LyricsDisplayMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.lyricsDisplayMode == mode,
                    onClick = { onLyricsModeChanged(mode) },
                    label = { Text(mode.label, fontSize = 10.sp) },
                    modifier = Modifier.testTag("music_lyrics_${mode.name.lowercase()}")
                )
            }
            Text("Tamaño", color = Color.White.copy(alpha = 0.68f), fontSize = 10.sp)
            OutlinedButton(onClick = { onLyricsTextScaleChanged(settings.lyricsTextScale - 0.1f) }, enabled = settings.lyricsTextScale > 0.8f) { Text("A−") }
            OutlinedButton(onClick = { onLyricsTextScaleChanged(settings.lyricsTextScale + 0.1f) }, enabled = settings.lyricsTextScale < 1.5f) { Text("A+") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("INSTRUMENTOS", color = MusicTeal, fontSize = 10.sp, fontWeight = FontWeight.Black)
            VisualInstrument.entries.forEach { instrument ->
                FilterChip(
                    selected = instrument in settings.visibleInstruments,
                    onClick = { onToggleInstrument(instrument) },
                    label = { Text(instrument.label, fontSize = 10.sp) },
                    modifier = Modifier.testTag("music_instrument_${instrument.name.lowercase()}")
                )
            }
        }
    }
}

@Composable
private fun AccessibilitySwitch(label: String, checked: Boolean, onChanged: (Boolean) -> Unit, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
        Switch(checked, onChanged, modifier = Modifier.testTag(tag).semantics { contentDescription = label })
    }
}

@Composable
private fun MusicLanes(timeline: MusicTimeline, positionMs: Long, modifier: Modifier = Modifier) {
    val lanes = listOf(
        LaneSpec("Voz", Icons.Default.KeyboardVoice, MusicMagenta, setOf(MusicEventType.VOCAL_START, MusicEventType.VOCAL_END)),
        LaneSpec("Melodía", Icons.Default.MusicNote, MusicCyan, setOf(MusicEventType.MELODY_UP, MusicEventType.MELODY_DOWN)),
        LaneSpec("Bajo", Icons.Default.GraphicEq, MusicTeal, setOf(MusicEventType.BASS)),
        LaneSpec("Batería", Icons.Default.Vibration, MusicYellow, setOf(MusicEventType.KICK, MusicEventType.SNARE, MusicEventType.HI_HAT, MusicEventType.SECTION_START))
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lanes.forEach { lane -> MusicLane(lane, timeline.events, positionMs, Modifier.weight(1f)) }
    }
}

@Composable
private fun MusicLane(lane: LaneSpec, events: List<MusicEvent>, positionMs: Long, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.width(92.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(lane.icon, null, tint = lane.color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(lane.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 34.dp)
                .background(Color.White.copy(alpha = 0.035f), RoundedCornerShape(8.dp))
                .border(1.dp, lane.color.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
        ) {
            val markerFraction = 0.2f
            val lookAheadMs = 6_000f
            Box(
                Modifier.fillMaxHeight().width(3.dp)
                    .offset(x = maxWidth * markerFraction)
                    .background(Color.White)
                    .semantics { contentDescription = "Línea del momento actual" }
            )
            events.asSequence()
                .filter { it.type in lane.types }
                .filter { it.timestampMs + it.durationMs >= positionMs - 350 && it.timestampMs <= positionMs + lookAheadMs.toLong() }
                .take(22)
                .forEach { event ->
                    val delta = (event.timestampMs - positionMs).toFloat()
                    val fraction = (markerFraction + (delta / lookAheadMs) * (1f - markerFraction)).coerceIn(0f, 0.96f)
                    val shape = eventShape(event.type)
                    Box(
                        modifier = Modifier.align(Alignment.CenterStart)
                            .offset(x = maxWidth * fraction)
                            .size(eventSize(event), 25.dp)
                            .background(lane.color.copy(alpha = 0.35f + event.intensity * 0.55f), shape)
                            .border(1.dp, lane.color, shape)
                            .semantics { contentDescription = event.label ?: event.type.name },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(eventSymbol(event.type), color = MusicDark, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }
        }
    }
}

@Composable
private fun HapticControls(
    enabled: Boolean,
    selectedIntensity: HapticIntensity,
    onEnabledChanged: (Boolean) -> Unit,
    onIntensityChanged: (HapticIntensity) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MusicTeal.copy(alpha = 0.28f))
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Vibration, null, tint = MusicTeal)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("VIBRACIONES", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Pulsos breves, principalmente con la batería", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChanged,
                    modifier = Modifier.testTag("music_haptic_toggle")
                        .semantics { contentDescription = "Activar o desactivar vibraciones" }
                )
            }
            if (enabled) {
                Spacer(Modifier.height(9.dp))
                IntensityChips(selectedIntensity, onIntensityChanged, compact = false)
            }
        }
    }
}

@Composable
private fun IntensityChips(
    selected: HapticIntensity,
    onSelected: (HapticIntensity) -> Unit,
    compact: Boolean
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) Text("Intensidad", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
        HapticIntensity.entries.forEach { intensity ->
            FilterChip(
                selected = intensity == selected,
                onClick = { onSelected(intensity) },
                label = { Text(intensity.label, fontSize = if (compact) 10.sp else 12.sp) }
            )
        }
    }
}

@Composable
private fun FeatureHeader(title: String, badge: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White)
        }
        Text(title, color = MusicCyan, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(10.dp))
        Surface(color = MusicMagenta.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
            Text(badge, color = MusicMagenta, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun BufferBadge(bufferedUntilMs: Long, durationMs: Long, recovering: Boolean) {
    Surface(
        color = (if (recovering) MusicYellow else MusicTeal).copy(alpha = 0.13f),
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, (if (recovering) MusicYellow else MusicTeal).copy(alpha = 0.4f))
    ) {
        Text(
            if (recovering) "RECUPERANDO" else "PREPARADO ${bufferedUntilMs / 1000}/${durationMs / 1000}s",
            color = if (recovering) MusicYellow else MusicTeal,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun MusicErrorScreen(message: String, recoverable: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No pudimos preparar Teleo Música", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(message, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
        if (recoverable) Button(onClick = onRetry) { Text("REINTENTAR") }
        TextButtonLike("VOLVER", onBack)
    }
}

@Composable
private fun TextButtonLike(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(label) }
}

private fun stageLabel(progress: MusicAnalysisProgress?): String = when (progress?.stage) {
    null -> "Preparando"
    else -> progress.stage.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(Locale.getDefault(), totalSeconds / 60, totalSeconds % 60)
}

private fun eventShape(type: MusicEventType): Shape = when (type) {
    MusicEventType.KICK, MusicEventType.VOCAL_START -> CircleShape
    MusicEventType.SNARE, MusicEventType.MELODY_UP -> CutCornerShape(6.dp)
    else -> RoundedCornerShape(5.dp)
}

private fun eventSize(event: MusicEvent) = when (event.type) {
    MusicEventType.KICK -> 28.dp
    MusicEventType.SNARE -> 24.dp
    MusicEventType.HI_HAT -> 13.dp
    MusicEventType.BASS, MusicEventType.VOCAL_START -> 34.dp
    MusicEventType.SECTION_START -> 38.dp
    else -> 25.dp
}

private fun eventSymbol(type: MusicEventType): String = when (type) {
    MusicEventType.KICK -> "K"
    MusicEventType.SNARE -> "S"
    MusicEventType.HI_HAT -> "H"
    MusicEventType.BASS -> "B"
    MusicEventType.VOCAL_START -> "V"
    MusicEventType.VOCAL_END -> "×"
    MusicEventType.MELODY_UP -> "↑"
    MusicEventType.MELODY_DOWN -> "↓"
    MusicEventType.SECTION_START -> "§"
    MusicEventType.SECTION_END -> "×"
}

private data class LaneSpec(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val types: Set<MusicEventType>
)
