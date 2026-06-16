package com.nicolas.teleo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.nicolas.teleo.ui.theme.TeleoTheme
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.*

// --- MODELOS Y ESTADOS ---

enum class Screen {
    Home,
    PalabraViva,
    EscribirYMostrar,
    TeleoCercaEntry,
    TeleoCercaCreate,
    TeleoCercaJoin,
    TeleoCercaChat,
    Scanner,
    Profile
}

data class TeleoNearbyMessage(
    val type: String = "", // "partial", "final", "text", "system"
    val emotion: String = "normal", // "normal", "shouting", "whispering", "laughing"
    val senderId: String = "",
    val senderName: String = "",
    val currentWord: String = "",
    val currentSentence: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJSON(): String {
        val json = JSONObject()
        json.put("type", type)
        json.put("emotion", emotion)
        json.put("senderId", senderId)
        json.put("senderName", senderName)
        json.put("currentWord", currentWord)
        json.put("currentSentence", currentSentence)
        json.put("message", message)
        json.put("timestamp", timestamp)
        return json.toString()
    }

    companion object {
        fun fromJSON(jsonStr: String): TeleoNearbyMessage {
            val json = JSONObject(jsonStr)
            return TeleoNearbyMessage(
                type = json.optString("type"),
                emotion = json.optString("emotion", "normal"),
                senderId = json.optString("senderId"),
                senderName = json.optString("senderName"),
                currentWord = json.optString("currentWord"),
                currentSentence = json.optString("currentSentence"),
                message = json.optString("message"),
                timestamp = json.optLong("timestamp")
            )
        }
    }
}

// --- MANAGER DE CONEXIÓN CERCANA ---

class NearbyConnectionManager(private val context: Context) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_STAR
    private val SERVICE_ID = "com.nicolas.teleo.NEARBY_SERVICE"
    
    var myName = Build.MODEL
    var connectedEndpointId = mutableStateOf<String?>(null)
    var isAdvertising = mutableStateOf(false)
    var isDiscovering = mutableStateOf(false)
    var discoveredEndpoints = mutableStateListOf<Endpoint>()
    var messages = mutableStateListOf<TeleoNearbyMessage>()
    
    // Estado remoto (lo que el otro está hablando)
    var remoteWord = mutableStateOf("")
    var remoteSentence = mutableStateOf("")
    var remoteEmotion = mutableStateOf("normal")

    data class Endpoint(val id: String, val name: String)

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val jsonStr = String(bytes)
                try {
                    val msg = TeleoNearbyMessage.fromJSON(jsonStr)
                    handleIncomingMessage(msg)
                } catch (e: Exception) {
                    Log.e("TeleoNearby", "Error parseando JSON", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun handleIncomingMessage(msg: TeleoNearbyMessage) {
        when (msg.type) {
            "partial" -> {
                remoteWord.value = msg.currentWord
                remoteSentence.value = msg.currentSentence
                remoteEmotion.value = msg.emotion
            }
            "final", "text" -> {
                remoteWord.value = ""
                remoteSentence.value = ""
                remoteEmotion.value = "normal"
                messages.add(msg)
            }
            "system" -> {
                messages.add(msg)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: com.google.android.gms.nearby.connection.ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointId.value = endpointId
                stopAdvertising()
                stopDiscovery()
                messages.add(TeleoNearbyMessage(type = "system", message = "Conectado con éxito"))
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpointId.value = null
            messages.add(TeleoNearbyMessage(type = "system", message = "Desconectado"))
        }
    }

    fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(myName, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener { isAdvertising.value = true }
    }

    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        isAdvertising.value = false
    }

    fun startDiscovery() {
        discoveredEndpoints.clear()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                discoveredEndpoints.add(Endpoint(endpointId, info.endpointName))
            }
            override fun onEndpointLost(endpointId: String) {
                discoveredEndpoints.removeAll { it.id == endpointId }
            }
        }, options).addOnSuccessListener { isDiscovering.value = true }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        isDiscovering.value = false
    }

    fun requestConnection(endpoint: Endpoint) {
        connectionsClient.requestConnection(myName, endpoint.id, connectionLifecycleCallback)
    }

    fun sendMessage(msg: TeleoNearbyMessage) {
        connectedEndpointId.value?.let { id ->
            val msgWithName = msg.copy(senderName = myName)
            val payload = Payload.fromBytes(msgWithName.toJSON().toByteArray())
            connectionsClient.sendPayload(id, payload)
            if (msgWithName.type == "text" || msgWithName.type == "final") {
                messages.add(msgWithName)
            }
        }
    }

    fun disconnect() {
        connectedEndpointId.value?.let { connectionsClient.disconnectFromEndpoint(it) }
        connectedEndpointId.value = null
        messages.clear()
        remoteWord.value = ""
        remoteSentence.value = ""
    }
}

// --- UTILIDADES ---

object TeleoEmojiMapper {
    private val mapping = mapOf(
        "HOLA" to "👋", "CHAU" to "👋", "ADIOS" to "👋",
        "GRACIAS" to "🙏", "POR FAVOR" to "🥺", "AMOR" to "❤️",
        "PERRO" to "🐶", "GATO" to "🐱", "CASA" to "🏠",
        "COMIDA" to "🍕", "SOL" to "☀️", "LLUVIA" to "🌧️",
        "FUEGO" to "🔥", "FIESTA" to "🎉", "MATE" to "🧉",
        "JAJA" to "😂", "JEJE" to "😂", "HAHA" to "😂",
        "FUTBOL" to "⚽", "MIRA" to "👀", "OK" to "✅",
        "BIEN" to "👍", "MAL" to "👎", "CAFE" to "☕",
        "CERVEZA" to "🍺", "SALUD" to "🥂"
    )

    fun decorate(text: String): String {
        var result = text
        mapping.forEach { (word, emoji) ->
            val regex = "\\b$word\\b".toRegex(RegexOption.IGNORE_CASE)
            result = regex.replace(result) { "${it.value} $emoji" }
        }
        return result
    }

    fun generateQRCode(content: String): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

// --- ACTIVIDAD PRINCIPAL ---

class MainActivity : ComponentActivity() {
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var nearbyManager: NearbyConnectionManager
    
    private val currentSentence = mutableStateOf("")
    private val wordQueue = mutableStateListOf<String>()
    private val sentenceHistory = mutableStateListOf<String>()
    private val isProcessingFinal = mutableStateOf(false)
    private val isListening = mutableStateOf(false)
    private val hasRecordPermission = mutableStateOf(false)
    private val lastRms = mutableStateOf(0f)
    private val currentEmotion = mutableStateOf("normal")
    private val currentScreen = mutableStateOf(Screen.Home)
    private val useEmojis = mutableStateOf(true)
    private val useEmotions = mutableStateOf(true)
    private val userName = mutableStateOf("")

    private val RECORD_AUDIO_REQUEST = 1001
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("teleo_prefs", Context.MODE_PRIVATE)
        userName.value = prefs.getString("user_name", Build.MODEL) ?: Build.MODEL

        nearbyManager = NearbyConnectionManager(this)
        nearbyManager.myName = userName.value
        initSpeechRecognizer()

        hasRecordPermission.value = checkNearbyPermissions()

        setContent {
            TeleoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = CyberDark) {
                    when (currentScreen.value) {
                        Screen.Home -> HomeScreen(
                            useEmojis = useEmojis,
                            useEmotions = useEmotions,
                            userName = userName.value,
                            onNavigate = { currentScreen.value = it }
                        )
                        Screen.Profile -> ProfileScreen(
                            currentName = userName.value,
                            onSave = { newName ->
                                userName.value = newName
                                nearbyManager.myName = newName
                                prefs.edit().putString("user_name", newName).apply()
                                currentScreen.value = Screen.Home
                            },
                            onBack = { currentScreen.value = Screen.Home }
                        )
                        Screen.PalabraViva -> TeleoScreen(
                            currentSentence = currentSentence,
                            wordQueue = wordQueue,
                            sentenceHistory = sentenceHistory,
                            isListening = isListening,
                            isProcessingFinal = isProcessingFinal,
                            hasPermission = hasRecordPermission,
                            currentEmotion = currentEmotion,
                            useEmojis = useEmojis.value,
                            useEmotions = useEmotions.value,
                            onStart = { startListening() },
                            onPause = { pauseListening() },
                            onClear = { 
                                currentSentence.value = ""
                                wordQueue.clear()
                                sentenceHistory.clear()
                            },
                            onRequestPermission = { requestPermission() },
                            onBack = { pauseListening(); currentScreen.value = Screen.Home }
                        )
                        Screen.EscribirYMostrar -> WriteAndShowScreen(
                            useEmojis = useEmojis.value,
                            onBack = { currentScreen.value = Screen.Home }
                        )
                        Screen.TeleoCercaEntry -> TeleoNearbyEntryScreen(
                            useEmojis = useEmojis,
                            useEmotions = useEmotions,
                            onNavigate = { currentScreen.value = it },
                            onBack = { currentScreen.value = Screen.Home }
                        )
                        Screen.TeleoCercaCreate -> CreateNearbyChatScreen(
                            manager = nearbyManager,
                            onConnected = { currentScreen.value = Screen.TeleoCercaChat },
                            onBack = { nearbyManager.stopAdvertising(); currentScreen.value = Screen.TeleoCercaEntry }
                        )
                        Screen.TeleoCercaJoin -> JoinNearbyChatScreen(
                            manager = nearbyManager,
                            onScan = { currentScreen.value = Screen.Scanner },
                            onConnected = { currentScreen.value = Screen.TeleoCercaChat },
                            onBack = { nearbyManager.stopDiscovery(); currentScreen.value = Screen.TeleoCercaEntry }
                        )
                        Screen.Scanner -> QRScannerScreen(
                            onScanResult = { result ->
                                val endpoint = nearbyManager.discoveredEndpoints.find { it.name == result }
                                if (endpoint != null) nearbyManager.requestConnection(endpoint)
                                currentScreen.value = Screen.TeleoCercaJoin
                            },
                            onBack = { currentScreen.value = Screen.TeleoCercaJoin }
                        )
                        Screen.TeleoCercaChat -> NearbyChatScreen(
                            manager = nearbyManager,
                            isListening = isListening,
                            useEmojis = useEmojis.value,
                            useEmotions = useEmotions.value,
                            onStartVoice = { startListening() },
                            onPauseVoice = { pauseListening() },
                            onBack = { nearbyManager.disconnect(); currentScreen.value = Screen.Home }
                        )
                    }
                }
            }
        }
    }

    private fun checkNearbyPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermission() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), RECORD_AUDIO_REQUEST)
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isProcessingFinal.value = false }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { 
                lastRms.value = rmsdB
                if (isListening.value) currentEmotion.value = determineEmotion(currentSentence.value)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (isListening.value && (error == SpeechRecognizer.ERROR_NO_MATCH || 
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || 
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                    handler.postDelayed({ if (isListening.value) startListening() }, 500)
                } else if (error != SpeechRecognizer.ERROR_CLIENT) {
                    isListening.value = false
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0].uppercase()
                    currentEmotion.value = determineEmotion(text)
                    processLiveText(text)
                    if (currentScreen.value == Screen.TeleoCercaChat) {
                        val words = text.trim().split("\\s+".toRegex())
                        nearbyManager.sendMessage(TeleoNearbyMessage(
                            type = "partial",
                            emotion = currentEmotion.value,
                            currentWord = words.lastOrNull() ?: "",
                            currentSentence = text
                        ))
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                isProcessingFinal.value = true
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val finalResult = matches[0].uppercase()
                    val emotion = determineEmotion(finalResult)
                    if (finalResult.isNotBlank()) {
                        if (currentScreen.value == Screen.PalabraViva) {
                            sentenceHistory.clear()
                            sentenceHistory.add(finalResult)
                        } else if (currentScreen.value == Screen.TeleoCercaChat) {
                            nearbyManager.sendMessage(TeleoNearbyMessage(
                                type = "final",
                                emotion = emotion,
                                message = finalResult
                            ))
                        }
                    }
                }
                currentSentence.value = ""
                wordQueue.clear()
                currentEmotion.value = "normal"
                if (isListening.value) handler.postDelayed({ if (isListening.value) startListening() }, 400)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun processLiveText(text: String) {
        if (isProcessingFinal.value) return
        val upperText = text.uppercase()
        if (currentSentence.value.isBlank() && upperText.isNotBlank() && currentScreen.value == Screen.PalabraViva) sentenceHistory.clear()
        if (upperText == currentSentence.value) return
        val oldWords = currentSentence.value.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val newWords = upperText.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        currentSentence.value = upperText
        if (newWords.size > oldWords.size) {
            for (i in oldWords.size until newWords.size) wordQueue.add(newWords[i])
        }
    }

    private fun determineEmotion(text: String): String {
        if (!useEmotions.value) return "normal"
        val upper = text.uppercase()
        return when {
            upper.contains("JAJA") || upper.contains("HAHA") || upper.contains("JEJE") -> "laughing"
            lastRms.value > 10f -> "shouting"
            lastRms.value < 1.5f && lastRms.value > -2f -> "whispering"
            else -> "normal"
        }
    }

    private fun startListening() {
        if (!checkNearbyPermissions()) { requestPermission(); return }
        isListening.value = true
        isProcessingFinal.value = false
        try { speechRecognizer.startListening(recognizerIntent) } catch (e: Exception) { isListening.value = false }
    }

    private fun pauseListening() {
        try { speechRecognizer.stopListening() } catch (_: Exception) {}
        isListening.value = false
        wordQueue.clear()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST) {
            hasRecordPermission.value = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        }
    }

    override fun onDestroy() {
        try { speechRecognizer.destroy() } catch (e: Exception) {}
        nearbyManager.disconnect()
        super.onDestroy()
    }
}

// --- COLORES ---
val CyberCyan = Color(0xFF00E5FF)
val CyberTeal = Color(0xFF1DE9B6)
val CyberMagenta = Color(0xFFFF00FF)
val CyberYellow = Color(0xFFFEE715)
val CyberDark = Color(0xFF0A0E14)

// --- COMPOSABLES ---

@Composable
fun HomeScreen(useEmojis: MutableState<Boolean>, useEmotions: MutableState<Boolean>, userName: String, onNavigate: (Screen) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity

    Column(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CyberDark, Color(0xFF1A1F26)))).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { activity?.finish() }, modifier = Modifier.size(48.dp).background(Color.Red.copy(alpha = 0.1f), CircleShape).border(1.dp, Color.Red.copy(alpha = 0.4f), CircleShape)) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.Red)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = "TELEO", color = CyberCyan, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                    Text(text = "Hola, $userName", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.clickable { onNavigate(Screen.Profile) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.2f))) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ConfigToggle(label = "Emojis", checked = useEmojis.value, onCheckedChange = { useEmojis.value = it })
                        Spacer(modifier = Modifier.width(20.dp))
                        ConfigToggle(label = "Emociones", checked = useEmotions.value, onCheckedChange = { useEmotions.value = it })
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(onClick = { onNavigate(Screen.Profile) }, modifier = Modifier.size(48.dp).background(CyberCyan.copy(alpha = 0.1f), CircleShape).border(1.dp, CyberCyan.copy(alpha = 0.4f), CircleShape)) {
                    Icon(Icons.Default.AccountCircle, contentDescription = "Perfil", tint = CyberCyan)
                }
            }
        }
        Row(modifier = Modifier.fillMaxSize().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            HomeCard(modifier = Modifier.weight(1f), title = "Palabra Viva", description = "Subtítulos en tiempo real.", icon = Icons.Default.Mic, color = CyberMagenta, onClick = { onNavigate(Screen.PalabraViva) })
            HomeCard(modifier = Modifier.weight(1f), title = "Escribir", description = "Mensajes a pantalla completa.", icon = Icons.Default.Keyboard, color = CyberTeal, onClick = { onNavigate(Screen.EscribirYMostrar) })
            HomeCard(modifier = Modifier.weight(1f), title = "Teleo Cerca", description = "Conexión local sin internet.", icon = Icons.Default.Wifi, color = CyberCyan, onClick = { onNavigate(Screen.TeleoCercaEntry) })
        }
    }
}

@Composable
fun ProfileScreen(currentName: String, onSave: (String) -> Unit, onBack: () -> Unit) {
    var nameInput by remember { mutableStateOf(currentName) }
    Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CyberDark, Color(0xFF1A1F26)))).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(120.dp).background(CyberCyan.copy(alpha = 0.1f), CircleShape).border(2.dp, CyberCyan, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(80.dp), tint = CyberCyan)
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text("TU APODO EN TELEO", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = nameInput, onValueChange = { nameInput = it }, modifier = Modifier.width(400.dp), placeholder = { Text("Escribí tu nombre...", color = Color.Gray) }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CyberCyan, unfocusedBorderColor = Color.Gray),
            textStyle = LocalTextStyle.current.copy(fontSize = 24.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TextButton(onClick = onBack) { Text("CANCELAR", color = Color.Gray, fontSize = 18.sp) }
            Button(onClick = { if (nameInput.isNotBlank()) onSave(nameInput) }, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark), shape = RoundedCornerShape(12.dp), modifier = Modifier.height(56.dp).width(200.dp)) {
                Icon(Icons.Default.Save, contentDescription = null); Spacer(modifier = Modifier.width(8.dp)); Text("GUARDAR", fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun ConfigToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan, checkedTrackColor = CyberCyan.copy(alpha = 0.5f), uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)))
    }
}

@Composable
fun HomeCard(title: String, description: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.03f), border = BorderStroke(2.dp, color.copy(alpha = 0.4f))) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(modifier = Modifier.size(80.dp).background(color.copy(alpha = 0.1f), CircleShape).border(1.dp, color.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = color)
            }
            Spacer(modifier = Modifier.height(24.dp)); Text(text = title.uppercase(), color = color, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(12.dp)); Text(text = description, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        }
    }
}

@Composable
fun TeleoScreen(currentSentence: MutableState<String>, wordQueue: MutableList<String>, sentenceHistory: List<String>, isListening: MutableState<Boolean>, isProcessingFinal: MutableState<Boolean>, hasPermission: MutableState<Boolean>, currentEmotion: MutableState<String>, useEmojis: Boolean, useEmotions: Boolean, onStart: () -> Unit, onPause: () -> Unit, onClear: () -> Unit, onRequestPermission: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var activeWord by remember { mutableStateOf("") }
    val totalChars = currentSentence.value.length + sentenceHistory.sumOf { it.length }
    val baseFontSize = when { totalChars < 50 -> 60.sp; totalChars < 120 -> 45.sp; totalChars < 250 -> 32.sp; totalChars < 500 -> 26.sp; else -> 22.sp }
    LaunchedEffect(wordQueue.size, isProcessingFinal.value) {
        if (isProcessingFinal.value) { activeWord = ""; wordQueue.clear() }
        else if (wordQueue.isNotEmpty()) { while (wordQueue.isNotEmpty()) { activeWord = wordQueue.removeAt(0); delay(350) }; delay(350); activeWord = "" }
    }
    Box(modifier = modifier.fillMaxSize().background(CyberDark)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(40.dp))
            val emotionIcon = if (useEmotions) { when(currentEmotion.value) { "shouting" -> "📢 "; "whispering" -> "🤫 "; "laughing" -> "😂 "; else -> "" } } else ""
            if (currentSentence.value.isNotBlank()) {
                val text = if (useEmojis) TeleoEmojiMapper.decorate(currentSentence.value) else currentSentence.value
                Text(text = emotionIcon + text, color = if (useEmotions) { when(currentEmotion.value) { "shouting" -> Color.Red; "laughing" -> CyberYellow; "whispering" -> Color.Gray; else -> if (activeWord.isNotBlank()) CyberMagenta.copy(alpha = 0.25f) else CyberMagenta } } else if (activeWord.isNotBlank()) CyberMagenta.copy(alpha = 0.25f) else CyberMagenta, fontSize = if (useEmotions && currentEmotion.value == "shouting") (baseFontSize.value * 1.0f).sp else (baseFontSize.value * 0.8f).sp, fontWeight = if (useEmotions && currentEmotion.value == "shouting") FontWeight.Black else FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = (baseFontSize.value * 1.0f).sp, fontStyle = if (useEmotions && currentEmotion.value == "whispering") FontStyle.Italic else FontStyle.Normal, modifier = Modifier.fillMaxWidth().animateContentSize())
            }
            sentenceHistory.forEach { history ->
                val text = if (useEmojis) TeleoEmojiMapper.decorate(history.uppercase()) else history.uppercase()
                Text(text = text, color = if (useEmotions) { when(currentEmotion.value) { "shouting" -> Color.Red.copy(alpha = 0.7f); "laughing" -> CyberYellow.copy(alpha = 0.7f); else -> if (activeWord.isNotBlank()) CyberCyan.copy(alpha = 0.15f) else CyberCyan } } else if (activeWord.isNotBlank()) CyberCyan.copy(alpha = 0.15f) else CyberCyan, fontSize = baseFontSize, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = (baseFontSize.value * 1.2f).sp, modifier = Modifier.fillMaxWidth().animateContentSize())
            }
        }
        if (activeWord.isNotBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = activeWord, transitionSpec = { (fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(animationSpec = tween(100)) }, label = "activeWordAnimation") { target ->
                    val text = if (useEmojis) TeleoEmojiMapper.decorate(target) else target
                    Text(text = text, color = if (useEmotions) { when(currentEmotion.value) { "shouting" -> Color.Red; "laughing" -> CyberYellow; else -> Color.White } } else Color.White, fontSize = if (useEmotions && currentEmotion.value == "shouting") 180.sp else 160.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, fontStyle = FontStyle.Italic)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, contentDescription = "Home", tint = Color.White) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(8.dp).background(if (isListening.value) CyberTeal else Color.Red.copy(alpha = 0.4f), CircleShape))
                Text(text = if (isListening.value) "ONLINE" else "OFFLINE", color = if (isListening.value) CyberTeal else Color.Red.copy(alpha = 0.4f), fontSize = 10.sp)
            }
        }
        Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.Transparent) {
            Row(modifier = Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) {
                if (!hasPermission.value) { TextButton(onClick = onRequestPermission) { Text("PEDIR PERMISO", color = Color.Red) } }
                else {
                    TextButton(onClick = onStart, enabled = !isListening.value) { Text("HABLAR", color = if (!isListening.value) CyberCyan else CyberCyan.copy(alpha = 0.3f), fontSize = 20.sp, fontWeight = FontWeight.Black) }
                    TextButton(onClick = onPause, enabled = isListening.value) { Text("PAUSA", color = if (isListening.value) CyberMagenta else CyberMagenta.copy(alpha = 0.3f), fontSize = 20.sp, fontWeight = FontWeight.Black) }
                    TextButton(onClick = onClear) { Text("RESET", color = CyberYellow) }
                }
            }
        }
    }
}

@Composable
fun WriteAndShowScreen(useEmojis: Boolean, onBack: () -> Unit) {
    var textInput by remember { mutableStateOf("") }
    var isShowingFullscreen by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(48f) }
    var errorMessage by remember { mutableStateOf("") }
    if (isShowingFullscreen) { FullscreenMessageView(text = textInput, fontSize = fontSize, useEmojis = useEmojis, onClose = { isShowingFullscreen = false }, onFontSizeChange = { fontSize = it }, onClear = { textInput = ""; isShowingFullscreen = false }) }
    else {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, contentDescription = "Home", tint = Color.White) }
                Text("ESCRIBIR Y MOSTRAR", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.size(48.dp))
            }
            OutlinedTextField(value = textInput, onValueChange = { textInput = it; if (it.isNotBlank()) errorMessage = "" }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Escribí tu mensaje aquí...", color = Color.Gray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CyberCyan, unfocusedBorderColor = Color.Gray), textStyle = LocalTextStyle.current.copy(fontSize = 20.sp))
            if (errorMessage.isNotBlank()) Text(errorMessage, color = Color.Red, modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { textInput = "" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("LIMPIAR") }
                Button(onClick = { if (textInput.isBlank()) errorMessage = "Escribí una frase primero" else isShowingFullscreen = true }, modifier = Modifier.weight(1.5f), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark)) { Text("MOSTRAR", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun FullscreenMessageView(text: String, fontSize: Float, useEmojis: Boolean, onClose: () -> Unit, onFontSizeChange: (Float) -> Unit, onClear: () -> Unit) {
    val decoratedText = if (useEmojis) TeleoEmojiMapper.decorate(text) else text
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(text = decoratedText, color = Color.White, fontSize = fontSize.sp, textAlign = TextAlign.Center, lineHeight = (fontSize * 1.2).sp) }
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CyberControlBotton("A-") { if (fontSize > 28) onFontSizeChange(fontSize - 8f) }
            CyberControlBotton("A+") { if (fontSize < 96) onFontSizeChange(fontSize + 8f) }
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TextButton(onClick = onClose) { Text("EDITAR", color = CyberCyan) }
            TextButton(onClick = onClear) { Text("LIMPIAR", color = CyberYellow) }
        }
    }
}

@Composable
fun CyberControlBotton(text: String, onClick: () -> Unit) {
    Box(modifier = Modifier.size(48.dp).border(1.dp, CyberCyan, CircleShape).background(Color.White.copy(alpha = 0.1f), CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) { Text(text, color = CyberCyan, fontWeight = FontWeight.Bold) }
}

@Composable
fun TeleoNearbyEntryScreen(useEmojis: MutableState<Boolean>, useEmotions: MutableState<Boolean>, onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, contentDescription = "Home", tint = Color.White) }
            Text("TELEO CERCA", color = CyberCyan, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Surface(color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(12.dp)) { Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ConfigToggle(label = "Emojis", checked = useEmojis.value, onCheckedChange = { useEmojis.value = it }) } }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            HomeCard(modifier = Modifier.weight(1f), title = "Crear Charla", description = "Visible para otros.", icon = Icons.Default.Add, color = CyberCyan, onClick = { onNavigate(Screen.TeleoCercaCreate) })
            HomeCard(modifier = Modifier.weight(1f), title = "Unirme", description = "Busca una charla.", icon = Icons.Default.Search, color = CyberTeal, onClick = { onNavigate(Screen.TeleoCercaJoin) })
        }
    }
}

@Composable
fun CreateNearbyChatScreen(manager: NearbyConnectionManager, onConnected: () -> Unit, onBack: () -> Unit) {
    val qrBitmap = remember { TeleoEmojiMapper.generateQRCode(manager.myName) }
    LaunchedEffect(Unit) { manager.startAdvertising() }
    LaunchedEffect(manager.connectedEndpointId.value) { if (manager.connectedEndpointId.value != null) onConnected() }
    Column(modifier = Modifier.fillMaxSize().background(CyberDark).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.Home, contentDescription = "Volver", tint = Color.Gray) }
            Spacer(modifier = Modifier.weight(1f)); Text("CREAR CHARLA", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.weight(1f)); Spacer(modifier = Modifier.size(48.dp))
        }
        Spacer(modifier = Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Surface(color = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(240.dp).padding(8.dp)) { qrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxSize()) } }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOMBRE DEL DISPOSITIVO", color = Color.Gray, fontSize = 14.sp)
                Text(manager.myName, color = CyberCyan, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(24.dp)); CircularProgressIndicator(color = CyberCyan); Spacer(modifier = Modifier.height(16.dp)); Text("Escaneá el QR para conectarte", color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun JoinNearbyChatScreen(manager: NearbyConnectionManager, onScan: () -> Unit, onConnected: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { manager.startDiscovery() }
    LaunchedEffect(manager.connectedEndpointId.value) { if (manager.connectedEndpointId.value != null) onConnected() }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.Default.Home, contentDescription = "Volver", tint = Color.Gray) }
            Spacer(modifier = Modifier.width(16.dp)); Text("UNIRSE A CHARLA", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null); Spacer(modifier = Modifier.width(8.dp)); Text("ESCANEAR QR", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (manager.discoveredEndpoints.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = CyberCyan.copy(alpha = 0.3f)); Spacer(modifier = Modifier.height(16.dp)); Text("Buscando dispositivos...", color = Color.Gray) } }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(manager.discoveredEndpoints) { endpoint ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { manager.requestConnection(endpoint) }, colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))) {
                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Text(endpoint.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.weight(1f)); Text("CONECTAR >", color = CyberCyan, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun QRScannerScreen(onScanResult: (String) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var hasScanned by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().build())
                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !hasScanned) {
                            scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let { hasScanned = true; onScanResult(it) } }
                                .addOnCompleteListener { imageProxy.close() }
                        } else imageProxy.close()
                    }
                    try { cameraProvider.unbindAll(); cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis) } catch (e: Exception) { Log.e("TeleoScanner", "Error", e) }
                }, executor); previewView
            }, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().padding(64.dp).border(2.dp, CyberCyan, RoundedCornerShape(24.dp)))
        IconButton(onClick = onBack, modifier = Modifier.padding(24.dp).align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f), CircleShape)) { Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White) }
        Text("Enfocá el código QR del otro dispositivo", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp), textAlign = TextAlign.Center)
    }
}

@Composable
fun NearbyChatScreen(manager: NearbyConnectionManager, isListening: MutableState<Boolean>, useEmojis: Boolean, useEmotions: Boolean, onStartVoice: () -> Unit, onPauseVoice: () -> Unit, onBack: () -> Unit) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(manager.messages.size) { if (manager.messages.isNotEmpty()) listState.animateScrollToItem(manager.messages.size - 1) }
    Column(modifier = Modifier.fillMaxSize().background(CyberDark)) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).background(if (useEmotions) { when(manager.remoteEmotion.value) { "shouting" -> Color.Red.copy(alpha = 0.2f); "laughing" -> CyberYellow.copy(alpha = 0.1f); else -> Color.Black.copy(alpha = 0.3f) } } else Color.Black.copy(alpha = 0.3f)).padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val emotionIcon = if (useEmotions) { when(manager.remoteEmotion.value) { "shouting" -> "📢 "; "whispering" -> "🤫 "; "laughing" -> "😂 "; else -> "" } } else ""
                if (manager.remoteWord.value.isNotBlank()) {
                    val text = if (useEmojis) TeleoEmojiMapper.decorate(manager.remoteWord.value.uppercase()) else manager.remoteWord.value.uppercase()
                    Text(emotionIcon + text, color = if (useEmotions) { when(manager.remoteEmotion.value) { "shouting" -> Color.Red; "laughing" -> CyberYellow; else -> Color.White } } else Color.White, fontSize = if (useEmotions && manager.remoteEmotion.value == "shouting") 80.sp else 64.sp, fontWeight = FontWeight.Black, lineHeight = 72.sp)
                }
                if (manager.remoteSentence.value.isNotBlank()) {
                    val text = if (useEmojis) TeleoEmojiMapper.decorate(manager.remoteSentence.value) else manager.remoteSentence.value
                    Text(text, color = if (useEmotions && manager.remoteEmotion.value == "whispering") Color.Gray else CyberTeal, fontSize = if (useEmotions && manager.remoteEmotion.value == "whispering") 18.sp else 24.sp, textAlign = TextAlign.Center, lineHeight = 32.sp, fontStyle = if (useEmotions && manager.remoteEmotion.value == "whispering") FontStyle.Italic else FontStyle.Normal)
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            items(manager.messages) { msg -> ChatMessageBubble(msg, isMe = msg.type == "text" || msg.type == "final", useEmojis = useEmojis, useEmotions = useEmotions) }
        }
        Surface(modifier = Modifier.fillMaxWidth(), color = Color.Black.copy(alpha = 0.5f)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = textInput, onValueChange = { textInput = it }, modifier = Modifier.weight(1f), placeholder = { Text("Escribí algo...", color = Color.Gray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CyberCyan))
                    IconButton(onClick = { if (textInput.isNotBlank()) { manager.sendMessage(TeleoNearbyMessage(type = "text", message = textInput)); textInput = "" } }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = CyberCyan) }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("SALIR", color = Color.Red) }
                    Button(onClick = { if (isListening.value) onPauseVoice() else onStartVoice() }, colors = ButtonDefaults.buttonColors(containerColor = if (isListening.value) CyberMagenta else Color.White)) { Text(if (isListening.value) "DETENER" else "HABLAR", color = if (isListening.value) Color.White else Color.Black, fontWeight = FontWeight.Black) }
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(msg: TeleoNearbyMessage, isMe: Boolean, useEmojis: Boolean, useEmotions: Boolean) {
    if (msg.type == "system") { Text(msg.message, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center); return }
    val align = if (isMe) Alignment.End else Alignment.Start
    val color = if (useEmotions) { when(msg.emotion) { "shouting" -> if (isMe) Color.Red.copy(alpha = 0.2f) else Color.Red.copy(alpha = 0.1f); "laughing" -> if (isMe) CyberYellow.copy(alpha = 0.2f) else CyberYellow.copy(alpha = 0.1f); else -> if (isMe) CyberCyan.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f) } } else if (isMe) CyberCyan.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
    val textColor = if (useEmotions) { when(msg.emotion) { "shouting" -> Color.Red; "laughing" -> CyberYellow; "whispering" -> Color.Gray; else -> if (isMe) CyberCyan else Color.White } } else if (isMe) CyberCyan else Color.White
    val text = if (useEmojis) TeleoEmojiMapper.decorate(msg.message) else msg.message
    val prefix = if (useEmotions) { when(msg.emotion) { "shouting" -> "📢 "; "whispering" -> "🤫 "; "laughing" -> "😂 "; else -> "" } } else ""
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
        if (!isMe && msg.senderName.isNotBlank()) Text(text = msg.senderName, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        Surface(color = color, shape = RoundedCornerShape(12.dp), border = if (isMe) BorderStroke(1.dp, (if (useEmotions && msg.emotion == "shouting") Color.Red else CyberCyan).copy(alpha = 0.3f)) else null) {
            Text(text = prefix + text, color = textColor, fontSize = if (useEmotions && msg.emotion == "whispering") 15.sp else 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontStyle = if (useEmotions && msg.emotion == "whispering") FontStyle.Italic else FontStyle.Normal, fontWeight = if (useEmotions && msg.emotion == "shouting") FontWeight.Bold else FontWeight.Normal)
        }
    }
}
