package com.nicolas.teleo.features.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nicolas.teleo.features.music.visual.voice.VisualVowel
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualFrame
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualQuality
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualSettings
import com.nicolas.teleo.features.music.visual.voice.VowelProbabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private val VoiceLabDark = Color(0xFF03050B)
private val VoiceLabCyan = Color(0xFF80F3FF)
private val VoiceLabGold = Color(0xFFFFD86B)

@Composable
fun VoiceVisualLabScreen(onBack: () -> Unit) {
    var presence by remember { mutableFloatStateOf(0.72f) }
    var intensity by remember { mutableFloatStateOf(0.58f) }
    var pitch by remember { mutableFloatStateOf(0.5f) }
    var vibrato by remember { mutableFloatStateOf(0.18f) }
    var onset by remember { mutableFloatStateOf(0f) }
    var primaryVowel by remember { mutableStateOf(VisualVowel.A) }
    var secondaryVowel by remember { mutableStateOf(VisualVowel.O) }
    var vowelMix by remember { mutableFloatStateOf(0.12f) }
    var quality by remember { mutableStateOf(VoiceVisualQuality.AUTO) }
    var particlesEnabled by remember { mutableStateOf(true) }
    var reducedMotion by remember { mutableStateOf(false) }
    var flashesEnabled by remember { mutableStateOf(true) }
    var automaticSequence by remember { mutableStateOf(false) }
    var onsetPulse by remember { mutableIntStateOf(0) }

    fun applyDemo(demo: VoiceVisualFrame) {
        automaticSequence = false
        presence = demo.presence
        intensity = demo.intensity
        pitch = demo.pitchNormalized
        vibrato = demo.vibrato
        onset = demo.onsetStrength
        val blend = demo.vowelProbabilities.asList().sortedByDescending { it.second }
        primaryVowel = blend[0].first
        secondaryVowel = blend[1].first
        vowelMix = blend[1].second.coerceIn(0f, 0.5f)
    }

    LaunchedEffect(onsetPulse) {
        if (onsetPulse > 0) {
            onset = 1f
            delay(180)
            onset = 0f
        }
    }

    LaunchedEffect(automaticSequence) {
        if (!automaticSequence) return@LaunchedEffect
        val sequence = listOf(
            demoFrame(VisualVowel.A, 0.55f, 0.36f, 0.28f),
            demoFrame(VisualVowel.E, 0.78f, 0.7f, 0.43f, onset = 0.8f),
            demoFrame(VisualVowel.I, 0.66f, 0.52f, 0.78f, vibrato = 0.72f),
            demoFrame(VisualVowel.O, 0.92f, 0.9f, 0.55f, onset = 1f),
            demoFrame(VisualVowel.U, 0.62f, 0.46f, 0.35f),
            VoiceVisualFrame.SILENCE
        )
        var current = VoiceVisualFrame.of(
            presence, intensity, pitch, vibrato, onset,
            probabilitiesFor(primaryVowel, secondaryVowel, vowelMix)
        )
        while (isActive) {
            for (target in sequence) {
                repeat(24) { step ->
                    val amount = (step + 1) / 24f
                    val interpolated = interpolateFrame(current, target, amount)
                    presence = interpolated.presence
                    intensity = interpolated.intensity
                    pitch = interpolated.pitchNormalized
                    vibrato = interpolated.vibrato
                    onset = interpolated.onsetStrength
                    val blend = interpolated.vowelProbabilities.asList().sortedByDescending { it.second }
                    primaryVowel = blend[0].first
                    secondaryVowel = blend[1].first
                    vowelMix = blend[1].second.coerceIn(0f, 0.5f)
                    delay(32)
                }
                current = target
                delay(220)
            }
        }
    }

    val frame = VoiceVisualFrame.of(
        presence = presence,
        intensity = intensity,
        pitchNormalized = pitch,
        vibrato = vibrato,
        onsetStrength = onset,
        vowelProbabilities = probabilitiesFor(primaryVowel, secondaryVowel, vowelMix)
    )
    val settings = VoiceVisualSettings(
        quality = quality,
        reducedMotion = reducedMotion,
        particlesEnabled = particlesEnabled,
        flashesEnabled = flashesEnabled
    )

    Column(
        modifier = Modifier.fillMaxSize().testTag("voice_lab"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver a Teleo Música", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("VOICE VISUAL LAB", color = VoiceLabCyan, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("Simulador visual · sin micrófono", color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp)
            }
            Icon(Icons.Default.AutoAwesome, null, tint = VoiceLabGold)
        }

        Surface(
            modifier = Modifier.weight(0.85f).fillMaxWidth().padding(horizontal = 12.dp),
            color = VoiceLabDark,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, VoiceLabCyan.copy(alpha = 0.3f))
        ) {
            Box(Modifier.fillMaxSize()) {
                VoiceBlobCanvas(frame, settings, Modifier.fillMaxSize())
                Text(
                    "${primaryVowel.label}  ${if (primaryVowel == VisualVowel.UNKNOWN) "" else "VOZ"}",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1.15f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                "Presencia ${(presence * 100).roundToInt()}% · Intensidad ${(intensity * 100).roundToInt()}% · " +
                    "Tono ${(pitch * 100).roundToInt()}% · ${primaryVowel.label}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().testTag("voice_lab_state"),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            DemoControls(
                automaticSequence = automaticSequence,
                onDemo = ::applyDemo,
                onStrongOnset = {
                    automaticSequence = false
                    presence = 1f
                    intensity = 1f
                    onsetPulse++
                },
                onAutomaticChanged = { automaticSequence = it }
            )
            LabSlider("Presencia", presence, "voice_presence") { automaticSequence = false; presence = it }
            LabSlider("Intensidad", intensity, "voice_intensity") { automaticSequence = false; intensity = it }
            LabSlider("Tono: grave → agudo", pitch, "voice_pitch") { automaticSequence = false; pitch = it }
            LabSlider("Vibrato", vibrato, "voice_vibrato") { automaticSequence = false; vibrato = it }
            LabSlider("Ataque", onset, "voice_onset") { automaticSequence = false; onset = it }

            SectionLabel("VOCAL DOMINANTE")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                VisualVowel.entries.forEach { vowel ->
                    FilterChip(
                        selected = primaryVowel == vowel,
                        onClick = { automaticSequence = false; primaryVowel = vowel },
                        label = { Text(vowel.label) }
                    )
                }
            }
            SectionLabel("MEZCLA CON ${secondaryVowel.label}")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                VisualVowel.entries.filter { it != primaryVowel }.forEach { vowel ->
                    FilterChip(
                        selected = secondaryVowel == vowel,
                        onClick = { automaticSequence = false; secondaryVowel = vowel },
                        label = { Text(vowel.label) }
                    )
                }
            }
            Slider(
                value = vowelMix,
                onValueChange = { automaticSequence = false; vowelMix = it },
                valueRange = 0f..0.5f,
                modifier = Modifier.fillMaxWidth().testTag("voice_vowel_mix")
            )

            SectionLabel("CALIDAD")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                VoiceVisualQuality.entries.forEach { option ->
                    FilterChip(selected = quality == option, onClick = { quality = option }, label = { Text(option.name) })
                }
            }
            LabSwitch("Partículas", particlesEnabled) { particlesEnabled = it }
            LabSwitch("Movimiento reducido", reducedMotion) { reducedMotion = it }
            LabSwitch("Destellos permitidos", flashesEnabled) { flashesEnabled = it }
            Text(
                "Los valores son simulados para diseñar y validar el lenguaje visual. La conexión con análisis de voz real queda desacoplada.",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun DemoControls(
    automaticSequence: Boolean,
    onDemo: (VoiceVisualFrame) -> Unit,
    onStrongOnset: () -> Unit,
    onAutomaticChanged: (Boolean) -> Unit
) {
    SectionLabel("DEMOS")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { onDemo(VoiceVisualFrame.SILENCE) }, label = { Text("Silencio") })
        AssistChip(onClick = { onDemo(demoFrame(VisualVowel.A, 0.38f, 0.22f, 0.48f)) }, label = { Text("Voz suave") })
        AssistChip(
            onClick = { onDemo(demoFrame(VisualVowel.O, 1f, 1f, 0.55f, onset = 1f)) },
            label = { Text("Voz intensa") },
            modifier = Modifier.testTag("voice_demo_intense")
        )
        VisualVowel.entries.filter { it != VisualVowel.UNKNOWN }.forEach { vowel ->
            AssistChip(onClick = { onDemo(demoFrame(vowel, 0.78f, 0.6f, 0.5f)) }, label = { Text("Vocal ${vowel.label}") })
        }
        AssistChip(onClick = { onDemo(demoFrame(VisualVowel.I, 0.78f, 0.56f, 0.72f, vibrato = 1f)) }, label = { Text("Vibrato") })
        AssistChip(onClick = onStrongOnset, label = { Text("Ataque fuerte") })
        OutlinedButton(
            onClick = { onAutomaticChanged(!automaticSequence) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("voice_demo_automatic")
        ) {
            Icon(if (automaticSequence) Icons.Default.Stop else Icons.Default.AutoAwesome, null)
            Spacer(Modifier.width(6.dp))
            Text(if (automaticSequence) "DETENER" else "SECUENCIA AUTOMÁTICA")
        }
    }
}

@Composable
private fun LabSlider(label: String, value: Float, tag: String, onValueChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.76f), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("${(value * 100).roundToInt()}%", color = VoiceLabCyan, fontSize = 11.sp)
    }
    Slider(value, onValueChange, modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun LabSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.76f), modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = VoiceLabGold, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

private fun demoFrame(
    vowel: VisualVowel,
    presence: Float,
    intensity: Float,
    pitch: Float,
    vibrato: Float = 0.16f,
    onset: Float = 0f
) = VoiceVisualFrame.of(
    presence = presence,
    intensity = intensity,
    pitchNormalized = pitch,
    vibrato = vibrato,
    onsetStrength = onset,
    vowelProbabilities = probabilitiesFor(vowel, VisualVowel.UNKNOWN, 0.06f)
)

private fun probabilitiesFor(primary: VisualVowel, secondary: VisualVowel, mix: Float): VowelProbabilities {
    val values = VisualVowel.entries.associateWith { 0f }.toMutableMap()
    values[primary] = 1f - mix
    values[secondary] = (values[secondary] ?: 0f) + mix
    return VowelProbabilities.of(
        a = values.getValue(VisualVowel.A),
        e = values.getValue(VisualVowel.E),
        i = values.getValue(VisualVowel.I),
        o = values.getValue(VisualVowel.O),
        u = values.getValue(VisualVowel.U),
        unknown = values.getValue(VisualVowel.UNKNOWN)
    )
}

private fun interpolateFrame(from: VoiceVisualFrame, to: VoiceVisualFrame, amount: Float): VoiceVisualFrame {
    fun lerp(start: Float, end: Float) = start + (end - start) * amount
    fun vowel(vowel: VisualVowel) = lerp(from.vowelProbabilities[vowel], to.vowelProbabilities[vowel])
    return VoiceVisualFrame.of(
        presence = lerp(from.presence, to.presence),
        intensity = lerp(from.intensity, to.intensity),
        pitchNormalized = lerp(from.pitchNormalized, to.pitchNormalized),
        vibrato = lerp(from.vibrato, to.vibrato),
        onsetStrength = lerp(from.onsetStrength, to.onsetStrength),
        vowelProbabilities = VowelProbabilities.of(
            a = vowel(VisualVowel.A), e = vowel(VisualVowel.E), i = vowel(VisualVowel.I),
            o = vowel(VisualVowel.O), u = vowel(VisualVowel.U), unknown = vowel(VisualVowel.UNKNOWN)
        )
    )
}
