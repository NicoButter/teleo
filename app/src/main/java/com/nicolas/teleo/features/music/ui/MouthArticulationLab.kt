package com.nicolas.teleo.features.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nicolas.teleo.features.music.domain.VocalVisemeShape
import com.nicolas.teleo.features.music.visual.ArticulatoryMouthRenderer
import com.nicolas.teleo.features.music.visual.PremiumVoiceState
import com.nicolas.teleo.features.music.visual.RhubarbMouthPoseMapper

@Composable
fun MouthArticulationLab(onDismiss: () -> Unit) {
    var shape by remember { mutableStateOf(VocalVisemeShape.A) }
    var intensity by remember { mutableFloatStateOf(.5f) }
    val renderer = remember { ArticulatoryMouthRenderer() }
    val pose = RhubarbMouthPoseMapper.poseFor(shape)
    val label = when (shape) {
        VocalVisemeShape.A -> "MBP · cierre con presión"
        VocalVisemeShape.F -> "UW · redondeo y protrusión"
        VocalVisemeShape.G -> "FV · contacto labiodental"
        VocalVisemeShape.H -> "L · lengua elevada"
        VocalVisemeShape.X -> "REST · labios relajados"
        else -> "Apertura ${shape.name}"
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color(0xFF090C1A), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).testTag("mouth_lab")) {
                Text("Boca · poses Rhubarb", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Inspección manual · sin audio", color = Color(0xFFB3C4D8))
                Canvas(Modifier.fillMaxWidth().height(200.dp).testTag("mouth_lab_canvas").semantics { contentDescription = label }) {
                    renderer.draw(this, PremiumVoiceState(pose, shape, 1f, intensity), Offset(size.width * .5f, size.height * .42f), size.width * .78f)
                }
                Text("${shape.name} · $label", color = Color.White, modifier = Modifier.testTag("mouth_lab_pose"))
                val rows = remember { listOf(listOf(VocalVisemeShape.A, VocalVisemeShape.F, VocalVisemeShape.G), listOf(VocalVisemeShape.H, VocalVisemeShape.X), listOf(VocalVisemeShape.B, VocalVisemeShape.C, VocalVisemeShape.D, VocalVisemeShape.E)) }
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { candidate ->
                            FilterChip(selected = shape == candidate, onClick = { shape = candidate },
                                modifier = Modifier.testTag("mouth_pose_${candidate.name}"), label = { Text(candidate.name) })
                        }
                    }
                }
                Text("Intensidad · conserva la articulación", color = Color(0xFFB3C4D8))
                Slider(value = intensity, onValueChange = { intensity = it })
                Text("Mandíbula %.2f · cierre %.2f\nContacto FV %.2f · lengua %.2f".format(pose.jawOpen, pose.lipClosure, pose.labiodentalContact, pose.tongueRaise), color = Color(0xFFB3C4D8), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        }
    }
}
