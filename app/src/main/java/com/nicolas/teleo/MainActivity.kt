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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
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
    TeleoCercaChat
}

data class TeleoNearbyMessage(
    val type: String = "", // "partial", "final", "text", "system"
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
            }
            "final" -> {
                remoteWord.value = ""
                remoteSentence.value = ""
                messages.add(msg)
            }
            "text" -> {
                messages.add(msg)
            }
            "system" -> {
                messages.add(msg)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: com.google.android.gms.nearby.connection.ConnectionInfo) {
            // Aceptar automáticamente
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
            val payload = Payload.fromBytes(msg.toJSON().toByteArray())
            connectionsClient.sendPayload(id, payload)
            if (msg.type == "text" || msg.type == "final") {
                messages.add(msg)
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

// --- ACTIVIDAD PRINCIPAL ---

class MainActivity : ComponentActivity() {
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var nearbyManager: NearbyConnectionManager
    
    // Estados Palabra Viva
    private val currentSentence = mutableStateOf("")
    private val wordQueue = mutableStateListOf<String>()
    private val sentenceHistory = mutableStateListOf<String>()
    private val isProcessingFinal = mutableStateOf(false)
    private val isListening = mutableStateOf(false)
    private val hasRecordPermission = mutableStateOf(false)
    
    // Estado de Navegación
    private val currentScreen = mutableStateOf(Screen.Home)

    private val RECORD_AUDIO_REQUEST = 1001
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        nearbyManager = NearbyConnectionManager(this)
        initSpeechRecognizer()

        hasRecordPermission.value = checkNearbyPermissions()

        setContent {
            TeleoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = CyberDark) {
                    when (currentScreen.value) {
                        Screen.Home -> HomeScreen(onNavigate = { currentScreen.value = it })
                        Screen.PalabraViva -> TeleoScreen(
                            currentSentence = currentSentence,
                            wordQueue = wordQueue,
                            sentenceHistory = sentenceHistory,
                            isListening = isListening,
                            isProcessingFinal = isProcessingFinal,
                            hasPermission = hasRecordPermission,
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
                        Screen.EscribirYMostrar -> WriteAndShowScreen(onBack = { currentScreen.value = Screen.Home })
                        Screen.TeleoCercaEntry -> TeleoNearbyEntryScreen(
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
                            onConnected = { currentScreen.value = Screen.TeleoCercaChat },
                            onBack = { nearbyManager.stopDiscovery(); currentScreen.value = Screen.TeleoCercaEntry }
                        )
                        Screen.TeleoCercaChat -> NearbyChatScreen(
                            manager = nearbyManager,
                            isListening = isListening,
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
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermission() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
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
            override fun onRmsChanged(rmsdB: Float) {}
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
                    processLiveText(text)
                    if (currentScreen.value == Screen.TeleoCercaChat) {
                        val words = text.trim().split("\\s+".toRegex())
                        nearbyManager.sendMessage(TeleoNearbyMessage(
                            type = "partial",
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
                    if (finalResult.isNotBlank()) {
                        if (currentScreen.value == Screen.PalabraViva) {
                            sentenceHistory.clear()
                            sentenceHistory.add(finalResult)
                        } else if (currentScreen.value == Screen.TeleoCercaChat) {
                            nearbyManager.sendMessage(TeleoNearbyMessage(
                                type = "final",
                                message = finalResult
                            ))
                        }
                    }
                }
                currentSentence.value = ""
                wordQueue.clear()
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

// --- COLORES CYBERPUNK ---
val CyberCyan = Color(0xFF00E5FF)
val CyberTeal = Color(0xFF1DE9B6)
val CyberMagenta = Color(0xFFFF00FF)
val CyberYellow = Color(0xFFFEE715)
val CyberDark = Color(0xFF0A0E14)

// --- COMPOSABLES: NAVEGACIÓN Y HOME ---

@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CyberDark, Color(0xFF1A1F26)))).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(text = "TELEO", color = CyberCyan, fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Spacer(modifier = Modifier.height(40.dp))
        HomeButton(title = "Palabra Viva", description = "Subtítulos en vivo mientras hablás.", onClick = { onNavigate(Screen.PalabraViva) })
        Spacer(modifier = Modifier.height(20.dp))
        HomeButton(title = "Escribir y Mostrar", description = "Escribí una frase y mostrala gigante.", onClick = { onNavigate(Screen.EscribirYMostrar) })
        Spacer(modifier = Modifier.height(20.dp))
        HomeButton(title = "Teleo Cerca", description = "Conectate con alguien de forma local.", onClick = { onNavigate(Screen.TeleoCercaEntry) })
    }
}

@Composable
fun HomeButton(title: String, description: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(90.dp).border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.03f)), shape = RoundedCornerShape(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title.uppercase(), color = CyberCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(text = description, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
        }
    }
}

// --- COMPOSABLES: TELEO CERCA ---

@Composable
fun TeleoNearbyEntryScreen(onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("TELEO CERCA", color = CyberCyan, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(48.dp))
        HomeButton(title = "Crear Charla", description = "Tu teléfono será visible para otros.", onClick = { onNavigate(Screen.TeleoCercaCreate) })
        Spacer(modifier = Modifier.height(24.dp))
        HomeButton(title = "Unirme a Charla", description = "Buscá dispositivos cercanos.", onClick = { onNavigate(Screen.TeleoCercaJoin) })
        Spacer(modifier = Modifier.height(48.dp))
        TextButton(onClick = onBack) { Text("VOLVER AL MENÚ", color = Color.Gray) }
    }
}

@Composable
fun CreateNearbyChatScreen(manager: NearbyConnectionManager, onConnected: () -> Unit, onBack: () -> Unit) {
    val code = remember { (100000..999999).random().toString() }
    
    LaunchedEffect(Unit) { manager.startAdvertising() }
    LaunchedEffect(manager.connectedEndpointId.value) {
        if (manager.connectedEndpointId.value != null) onConnected()
    }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("CÓDIGO DE CONEXIÓN", color = Color.Gray, fontSize = 16.sp)
        Text(code, color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Spacer(modifier = Modifier.height(32.dp))
        CircularProgressIndicator(color = CyberCyan)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Esperando que alguien se una...", color = CyberTeal, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(64.dp))
        TextButton(onClick = onBack) { Text("CANCELAR", color = Color.Red) }
    }
}

@Composable
fun JoinNearbyChatScreen(manager: NearbyConnectionManager, onConnected: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { manager.startDiscovery() }
    LaunchedEffect(manager.connectedEndpointId.value) {
        if (manager.connectedEndpointId.value != null) onConnected()
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("BUSCANDO...", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(manager.discoveredEndpoints) { endpoint ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { manager.requestConnection(endpoint) },
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                ) {
                    Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(endpoint.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("CONECTAR >", color = CyberCyan, fontSize = 14.sp)
                    }
                }
            }
        }
        
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("VOLVER", color = Color.Gray)
        }
    }
}

@Composable
fun NearbyChatScreen(manager: NearbyConnectionManager, isListening: MutableState<Boolean>, onStartVoice: () -> Unit, onPauseVoice: () -> Unit, onBack: () -> Unit) {
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(manager.messages.size) {
        if (manager.messages.isNotEmpty()) listState.animateScrollToItem(manager.messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().background(CyberDark)) {
        // Cabecera: Palabra Viva del OTRO (Ahora dinámica y sin cortes)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp) // Altura mínima, puede crecer
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(16.dp), 
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (manager.remoteWord.value.isNotBlank()) {
                    Text(
                        manager.remoteWord.value.uppercase(), 
                        color = Color.White, 
                        fontSize = 64.sp, 
                        fontWeight = FontWeight.Black,
                        lineHeight = 72.sp
                    )
                }
                if (manager.remoteSentence.value.isNotBlank()) {
                    Text(
                        manager.remoteSentence.value, 
                        color = CyberTeal, 
                        fontSize = 24.sp, 
                        textAlign = TextAlign.Center,
                        lineHeight = 32.sp
                    )
                }
            }
        }

        // Chat
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            items(manager.messages) { msg ->
                ChatMessageBubble(msg, isMe = msg.type == "text" || msg.type == "final")
            }
        }

        // Entrada de texto y controles
        Surface(modifier = Modifier.fillMaxWidth(), color = Color.Black.copy(alpha = 0.5f)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = textInput, onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Escribí algo...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CyberCyan)
                    )
                    IconButton(onClick = {
                        if (textInput.isNotBlank()) {
                            manager.sendMessage(TeleoNearbyMessage(type = "text", message = textInput))
                            textInput = ""
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = CyberCyan) }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("SALIR", color = Color.Red) }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { if (isListening.value) onPauseVoice() else onStartVoice() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isListening.value) CyberMagenta else Color.White)) {
                            Text(if (isListening.value) "DETENER" else "HABLAR", color = if (isListening.value) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(msg: TeleoNearbyMessage, isMe: Boolean) {
    if (msg.type == "system") {
        Text(msg.message, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
        return
    }
    val align = if (isMe) Alignment.End else Alignment.Start
    val color = if (isMe) CyberCyan.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
    val textColor = if (isMe) CyberCyan else Color.White

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
        Surface(color = color, shape = RoundedCornerShape(12.dp), border = if (isMe) BorderStroke(1.dp, CyberCyan.copy(alpha = 0.3f)) else null) {
            Text(msg.message, color = textColor, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

// --- COMPOSABLE: ESCRIBIR Y MOSTRAR ---

@Composable
fun WriteAndShowScreen(onBack: () -> Unit) {
    var textInput by remember { mutableStateOf("") }
    var isShowingFullscreen by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(48f) }
    var errorMessage by remember { mutableStateOf("") }

    if (isShowingFullscreen) {
        FullscreenMessageView(text = textInput, fontSize = fontSize, onClose = { isShowingFullscreen = false }, onFontSizeChange = { fontSize = it }, onClear = { textInput = ""; isShowingFullscreen = false })
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ESCRIBIR Y MOSTRAR", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = textInput, onValueChange = { textInput = it; if (it.isNotBlank()) errorMessage = "" },
                modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Escribí tu mensaje aquí...", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CyberCyan, unfocusedBorderColor = Color.Gray),
                textStyle = LocalTextStyle.current.copy(fontSize = 20.sp)
            )
            if (errorMessage.isNotBlank()) Text(errorMessage, color = Color.Red, modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("VOLVER", color = Color.Gray) }
                Button(onClick = { textInput = "" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("LIMPIAR") }
                Button(onClick = { if (textInput.isBlank()) errorMessage = "Escribí una frase primero" else isShowingFullscreen = true },
                    modifier = Modifier.weight(1.5f), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark)) { Text("MOSTRAR", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun FullscreenMessageView(text: String, fontSize: Float, onClose: () -> Unit, onFontSizeChange: (Float) -> Unit, onClear: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(text = text, color = Color.White, fontSize = fontSize.sp, textAlign = TextAlign.Center, lineHeight = (fontSize * 1.2).sp)
        }
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
    Box(modifier = Modifier.size(48.dp).border(1.dp, CyberCyan, CircleShape).background(Color.White.copy(alpha = 0.1f), CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(text, color = CyberCyan, fontWeight = FontWeight.Bold)
    }
}

// --- COMPOSABLE: PALABRA VIVA ---

@Composable
fun TeleoScreen(
    currentSentence: MutableState<String>, wordQueue: MutableList<String>, sentenceHistory: List<String>,
    isListening: MutableState<Boolean>, isProcessingFinal: MutableState<Boolean>, hasPermission: MutableState<Boolean>,
    onStart: () -> Unit, onPause: () -> Unit, onClear: () -> Unit, onRequestPermission: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeWord by remember { mutableStateOf("") }
    
    // Lógica de escalado dinámico: calculamos cuántos caracteres hay en total
    val totalChars = currentSentence.value.length + sentenceHistory.sumOf { it.length }
    
    // Determinamos el tamaño de fuente ideal para que todo quepa
    val baseFontSize = when {
        totalChars < 50 -> 60.sp
        totalChars < 120 -> 45.sp
        totalChars < 250 -> 32.sp
        totalChars < 500 -> 26.sp
        else -> 22.sp
    }

    LaunchedEffect(wordQueue.size, isProcessingFinal.value) {
        if (isProcessingFinal.value) { activeWord = ""; wordQueue.clear() }
        else if (wordQueue.isNotEmpty()) { 
            while (wordQueue.isNotEmpty()) { 
                activeWord = wordQueue.removeAt(0)
                delay(350) 
            }
            delay(350)
            activeWord = "" 
        }
    }

    Box(modifier = modifier.fillMaxSize().background(CyberDark)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()), 
            verticalArrangement = Arrangement.Center, // Centramos el bloque de texto
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            
            if (currentSentence.value.isNotBlank()) {
                Text(
                    text = currentSentence.value, 
                    color = if (activeWord.isNotBlank()) CyberMagenta.copy(alpha = 0.25f) else CyberMagenta,
                    fontSize = (baseFontSize.value * 0.8f).sp, // Frase en curso un poco más chica
                    fontWeight = FontWeight.ExtraBold, 
                    textAlign = TextAlign.Center, 
                    lineHeight = (baseFontSize.value * 1.0f).sp,
                    modifier = Modifier.fillMaxWidth().animateContentSize()
                )
            }
            
            sentenceHistory.forEach { history ->
                Text(
                    text = history.uppercase(), 
                    color = if (activeWord.isNotBlank()) CyberCyan.copy(alpha = 0.15f) else CyberCyan,
                    fontSize = baseFontSize, 
                    fontWeight = FontWeight.Black, 
                    textAlign = TextAlign.Center, 
                    lineHeight = (baseFontSize.value * 1.2f).sp,
                    modifier = Modifier.fillMaxWidth().animateContentSize()
                )
            }
        }
        
        if (activeWord.isNotBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = activeWord, 
                    transitionSpec = { (fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(animationSpec = tween(100)) }
                ) { target ->
                    Text(text = target, color = Color.White, fontSize = 160.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, fontStyle = FontStyle.Italic)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("< VOLVER", color = Color.Gray) }
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
