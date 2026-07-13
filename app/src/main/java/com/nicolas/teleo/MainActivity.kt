package com.nicolas.teleo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nicolas.teleo.ui.theme.TeleoTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*

// --- CONFIGURACIÓN DE DISEÑO ---
val CyberCyan = Color(0xFF00E5FF)
val CyberTeal = Color(0xFF1DE9B6)
val CyberMagenta = Color(0xFFFF00FF)
val CyberYellow = Color(0xFFFEE715)
val CyberDark = Color(0xFF0A0E14)
val ColorPalette = listOf(CyberCyan, CyberTeal, CyberMagenta, CyberYellow, Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFFF8A65), Color(0xFFBA68C8))

// --- MODELOS ---

enum class Screen { Home, PalabraViva, EscribirYMostrar, TeleoCercaEntry, TeleoCercaCreate, TeleoCercaJoin, TeleoCercaChat, Scanner, Profile, AvatarCamera }
enum class NearbyFlowState { IDLE, STARTING, SEARCHING, ADVERTISING, REQUESTING, WAITING_APPROVAL, CONNECTED, ERROR }

data class TeleoNearbyMessage(
    val type: String = "",
    val emotion: String = "normal",
    val senderId: String = "",
    val senderName: String = "",
    val senderColor: Int = 0xFF00E5FF.toInt(),
    val currentWord: String = "",
    val currentSentence: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJSON(): String {
        val json = JSONObject()
        json.put("type", type); json.put("emotion", emotion)
        json.put("senderId", senderId); json.put("senderName", senderName)
        json.put("senderColor", senderColor); json.put("currentWord", currentWord)
        json.put("currentSentence", currentSentence); json.put("message", message)
        json.put("timestamp", timestamp)
        return json.toString()
    }
    companion object {
        fun fromJSON(jsonStr: String): TeleoNearbyMessage {
            val json = JSONObject(jsonStr)
            return TeleoNearbyMessage(
                type = json.optString("type"), emotion = json.optString("emotion", "normal"),
                senderId = json.optString("senderId"), senderName = json.optString("senderName"),
                senderColor = json.optInt("senderColor", 0xFF00E5FF.toInt()),
                currentWord = json.optString("currentWord"), currentSentence = json.optString("currentSentence"),
                message = json.optString("message"), timestamp = json.optLong("timestamp")
            )
        }
    }
}

// --- MANAGER DE CONEXIÓN ---

class NearbyConnectionManager(private val context: Context) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_STAR
    private val SERVICE_ID = "com.nicolas.teleo.NEARBY_SERVICE"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    var myName = Build.MODEL; var myId = UUID.randomUUID().toString(); var myColor = 0xFF00E5FF.toInt()
    val sessionId = UUID.randomUUID().toString().substring(0, 8).uppercase()
    var isHost = mutableStateOf(false)
    var flowState = mutableStateOf(NearbyFlowState.IDLE)
    var statusMessage = mutableStateOf("")
    var connectedParticipants = mutableStateListOf<Participant>()
    var pendingRequests = mutableStateListOf<Participant>()
    var messages = mutableStateListOf<TeleoNearbyMessage>()
    var discoveredEndpoints = mutableStateListOf<Endpoint>()
    var remoteWord = mutableStateOf(""); var remoteSentence = mutableStateOf(""); var remoteEmotion = mutableStateOf("normal")

    data class Endpoint(val id: String, val name: String, val sessionId: String)
    data class Participant(val id: String, val name: String)
    private var pendingScannedSession: String? = null

    private fun endpointName(): String = "$sessionId|${myName.take(24)}"
    private fun parseEndpoint(id: String, rawName: String): Endpoint {
        val parts = rawName.split("|", limit = 2)
        return if (parts.size == 2) Endpoint(id, parts[1], parts[0].uppercase()) else Endpoint(id, rawName, rawName.uppercase())
    }

    private fun fail(action: String, error: Exception) {
        mainHandler.post {
            flowState.value = NearbyFlowState.ERROR
            statusMessage.value = "$action: ${error.localizedMessage ?: "servicio no disponible"}"
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            payload.asBytes()?.let { b ->
                try {
                    val m = TeleoNearbyMessage.fromJSON(String(b))
                    mainHandler.post {
                        if (m.type == "partial") { remoteWord.value = m.currentWord; remoteSentence.value = m.currentSentence; remoteEmotion.value = m.emotion }
                        else if (m.type == "final" || m.type == "text") { remoteWord.value = ""; remoteSentence.value = ""; remoteEmotion.value = "normal"; messages.add(m) }
                        else if (m.type == "system") messages.add(m)
                    }
                } catch (e: Exception) {}
            }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (isHost.value) mainHandler.post {
                val endpoint = parseEndpoint(id, info.endpointName)
                if (pendingRequests.none { it.id == id }) pendingRequests.add(Participant(id, endpoint.name))
                flowState.value = NearbyFlowState.WAITING_APPROVAL
                statusMessage.value = "Solicitud de ${endpoint.name}"
            }
            else {
                mainHandler.post { flowState.value = NearbyFlowState.WAITING_APPROVAL; statusMessage.value = "Esperando aceptación del anfitrión…" }
                connectionsClient.acceptConnection(id, payloadCallback).addOnFailureListener { fail("No se pudo preparar la conexión", it) }
            }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            mainHandler.post {
                if (result.status.isSuccess) {
                    val p = pendingRequests.find { it.id == id } ?: Participant(id, "Usuario")
                    pendingRequests.removeAll { it.id == id }
                    if (connectedParticipants.none { it.id == id }) connectedParticipants.add(p)
                    messages.add(TeleoNearbyMessage(type = "system", message = "${p.name} conectado"))
                    flowState.value = NearbyFlowState.CONNECTED
                    statusMessage.value = "Conectado"
                    stopDiscovery()
                } else {
                    pendingRequests.removeAll { it.id == id }
                    flowState.value = NearbyFlowState.ERROR
                    statusMessage.value = "La conexión fue rechazada o no pudo completarse"
                }
            }
        }
        override fun onDisconnected(id: String) {
            mainHandler.post {
                val p = connectedParticipants.find { it.id == id }; connectedParticipants.removeAll { it.id == id }
                p?.let { messages.add(TeleoNearbyMessage(type = "system", message = "${it.name} salió")) }
            }
        }
    }

    fun startAdvertising() {
        connectionsClient.stopAdvertising()
        isHost.value = true; flowState.value = NearbyFlowState.STARTING; statusMessage.value = "Preparando charla…"
        connectionsClient.startAdvertising(endpointName(), SERVICE_ID, connectionLifecycleCallback, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener { mainHandler.post { if (flowState.value == NearbyFlowState.STARTING) { flowState.value = NearbyFlowState.ADVERTISING; statusMessage.value = "Esperando solicitudes…" } } }
            .addOnFailureListener { fail("No se pudo crear la charla", it) }
    }
    fun stopAdvertising() { connectionsClient.stopAdvertising() }
    fun startDiscovery() {
        connectionsClient.stopDiscovery()
        isHost.value = false; discoveredEndpoints.clear(); flowState.value = NearbyFlowState.STARTING; statusMessage.value = "Iniciando búsqueda…"
        connectionsClient.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                mainHandler.post {
                    val endpoint = parseEndpoint(id, info.endpointName)
                    if (discoveredEndpoints.none { it.id == id }) discoveredEndpoints.add(endpoint)
                    if (pendingScannedSession == endpoint.sessionId) requestConnection(endpoint)
                }
            }
            override fun onEndpointLost(id: String) { mainHandler.post { discoveredEndpoints.removeAll { it.id == id } } }
        }, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener { mainHandler.post { if (flowState.value == NearbyFlowState.STARTING) { flowState.value = NearbyFlowState.SEARCHING; statusMessage.value = "Buscando charlas cercanas…" } } }
            .addOnFailureListener { fail("No se pudo buscar dispositivos", it) }
    }
    fun stopDiscovery() { connectionsClient.stopDiscovery() }
    fun requestConnection(e: Endpoint) {
        if (flowState.value == NearbyFlowState.REQUESTING || flowState.value == NearbyFlowState.WAITING_APPROVAL) return
        pendingScannedSession = null; flowState.value = NearbyFlowState.REQUESTING; statusMessage.value = "Solicitando acceso a ${e.name}…"
        connectionsClient.requestConnection(endpointName(), e.id, connectionLifecycleCallback)
            .addOnSuccessListener { stopDiscovery() }
            .addOnFailureListener { fail("No se pudo solicitar la conexión", it) }
    }
    fun requestConnectionBySession(code: String) {
        val normalized = code.trim().uppercase()
        discoveredEndpoints.firstOrNull { it.sessionId == normalized }?.let(::requestConnection) ?: run {
            pendingScannedSession = normalized
            flowState.value = NearbyFlowState.SEARCHING
            statusMessage.value = "QR leído. Buscando esa charla…"
        }
    }
    fun accept(p: Participant) {
        flowState.value = NearbyFlowState.WAITING_APPROVAL; statusMessage.value = "Conectando con ${p.name}…"
        connectionsClient.acceptConnection(p.id, payloadCallback).addOnFailureListener { fail("No se pudo aceptar la solicitud", it) }
    }
    fun reject(p: Participant) { connectionsClient.rejectConnection(p.id); pendingRequests.remove(p); flowState.value = NearbyFlowState.ADVERTISING; statusMessage.value = "Esperando solicitudes…" }
    fun kick(p: Participant) { connectionsClient.disconnectFromEndpoint(p.id); connectedParticipants.remove(p) }
    fun sendMessage(msg: TeleoNearbyMessage) { val msgW = msg.copy(senderId = myId, senderName = myName, senderColor = myColor); val bytes = msgW.toJSON().toByteArray(); connectedParticipants.forEach { connectionsClient.sendPayload(it.id, Payload.fromBytes(bytes)) }; if (msgW.type == "text" || msgW.type == "final") messages.add(msgW) }
    fun disconnect() { connectionsClient.stopDiscovery(); connectionsClient.stopAdvertising(); connectionsClient.stopAllEndpoints(); connectedParticipants.clear(); pendingRequests.clear(); discoveredEndpoints.clear(); messages.clear(); pendingScannedSession = null; isHost.value = false; flowState.value = NearbyFlowState.IDLE; statusMessage.value = "" }
}

// --- UTILIDADES ---

object TeleoUtils {
    fun decorate(t: String): String { var r = t; mapOf("HOLA" to "👋", "CHAU" to "👋", "GRACIAS" to "🙏", "AMOR" to "❤️", "JAJA" to "😂", "MATE" to "🧉").forEach { (w, e) -> r = r.replace("\\b$w\\b".toRegex(RegexOption.IGNORE_CASE)) { "${it.value} $e" } }; return r }
    fun generateQR(c: String): Bitmap? { try { val m = QRCodeWriter().encode(c, BarcodeFormat.QR_CODE, 512, 512); val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565); for (x in 0 until m.width) for (y in 0 until m.height) b.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE); return b } catch (e: Exception) { return null } }
    fun toB64(b: Bitmap): String { val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out); return Base64.encodeToString(out.toByteArray(), Base64.DEFAULT) }
    fun fromB64(s: String): Bitmap? { try { val b = Base64.decode(s, Base64.DEFAULT); return BitmapFactory.decodeByteArray(b, 0, b.size) } catch (e: Exception) { return null } }
    fun prepareAvatar(bitmap: Bitmap, rotationDegrees: Int = 0): Bitmap {
        val rotated = if (rotationDegrees != 0) Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true
        ) else bitmap
        val side = minOf(rotated.width, rotated.height)
        val square = Bitmap.createBitmap(rotated, (rotated.width - side) / 2, (rotated.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, 384, 384, true)
    }
    fun avatarFromUri(context: Context, uri: Uri): Bitmap? = try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
        bitmap?.let(::prepareAvatar)
    } catch (_: Exception) { null }
}

// --- ACTIVIDAD PRINCIPAL ---

class MainActivity : ComponentActivity() {
    private lateinit var speechRecognizer: SpeechRecognizer; private lateinit var recognizerIntent: Intent; private lateinit var nearbyManager: NearbyConnectionManager
    private lateinit var audioManager: AudioManager
    private val currentSentence = mutableStateOf(""); private val wordQueue = mutableStateListOf<String>(); private val sentenceHistory = mutableStateListOf<String>()
    private val isListening = mutableStateOf(false); private val isProcessingFinal = mutableStateOf(false); private val hasRecordPermission = mutableStateOf(false)
    private val lastRms = mutableStateOf(0f); private val currentEmotion = mutableStateOf("normal"); private val currentScreen = mutableStateOf(Screen.Home)
    private val useEmojis = mutableStateOf(true); private val useEmotions = mutableStateOf(true); private val useWalkieTalkie = mutableStateOf(false); private val userName = mutableStateOf(""); private val userColor = mutableStateOf(0xFF00E5FF.toInt()); private val userAvatar = mutableStateOf<Bitmap?>(null)
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var isSpeechAudioConfigured = false
    private var pendingPermissionScreen: Screen? = null
    private val showStartupSplash = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); hideSystemBars()
        val prefs = getSharedPreferences("teleo_prefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        userName.value = prefs.getString("user_name", Build.MODEL) ?: Build.MODEL; userColor.value = prefs.getInt("user_color", 0xFF00E5FF.toInt()); useWalkieTalkie.value = prefs.getBoolean("use_walkie_talkie", false)
        prefs.getString("user_avatar", null)?.let { userAvatar.value = TeleoUtils.fromB64(it) }
        nearbyManager = NearbyConnectionManager(this).apply { myName = userName.value; myColor = userColor.value }
        initSpeechRecognizer(); hasRecordPermission.value = hasRecordPermission()
        setContent { TeleoTheme { Surface(modifier = Modifier.fillMaxSize(), color = CyberDark) {
            if (showStartupSplash.value) {
                LaunchedEffect(Unit) {
                    delay(1400)
                    showStartupSplash.value = false
                }
                StartupSplashScreen()
            } else when (currentScreen.value) {
                Screen.Home -> HomeScreen(useEmojis, useEmotions, userName.value, userColor.value, userAvatar.value, onNavigate = { currentScreen.value = it })
                Screen.Profile -> ProfileScreen(
                    cn = userName.value,
                    cc = userColor.value,
                    ca = userAvatar.value,
                    onSave = { n, c ->
                        userName.value = n
                        userColor.value = c
                        nearbyManager.myName = n
                        nearbyManager.myColor = c
                        prefs.edit().putString("user_name", n).putInt("user_color", c).apply()
                        currentScreen.value = Screen.Home
                    },
                    onAvatarChange = { avatar ->
                        userAvatar.value = avatar
                        prefs.edit().apply {
                            if (avatar == null) remove("user_avatar") else putString("user_avatar", TeleoUtils.toB64(avatar))
                        }.apply()
                    },
                    onTakeAvatar = { currentScreen.value = Screen.AvatarCamera },
                    onBack = { currentScreen.value = Screen.Home }
                )
                Screen.AvatarCamera -> AvatarCameraScreen(onCaptured = { b -> userAvatar.value = b; prefs.edit().putString("user_avatar", TeleoUtils.toB64(b)).apply(); currentScreen.value = Screen.Profile }, onBack = { currentScreen.value = Screen.Profile })
                Screen.PalabraViva -> TeleoScreen(currentSentence, wordQueue, sentenceHistory, isListening, isProcessingFinal, hasRecordPermission, currentEmotion, useEmojis.value, useEmotions.value, useWalkieTalkie.value, onWalkieModeChange = { useWalkieTalkie.value = it; prefs.edit().putBoolean("use_walkie_talkie", it).apply() }, onStart = { startListening() }, onPause = { pauseListening() }, onClear = { currentSentence.value = ""; wordQueue.clear(); sentenceHistory.clear() }, onRequestPermission = { requestPermission() }, onBack = { pauseListening(); currentScreen.value = Screen.Home })
                Screen.EscribirYMostrar -> WriteAndShowScreen(ue = useEmojis.value, onBackAction = { currentScreen.value = Screen.Home })
                Screen.TeleoCercaEntry -> TeleoNearbyEntryScreen(useEmojis, useEmotions, onNavigate = { target ->
                    if (hasNearbyPermissions()) currentScreen.value = target else {
                        pendingPermissionScreen = target
                        requestNearbyPermissions()
                    }
                }, onBack = { currentScreen.value = Screen.Home })
                Screen.TeleoCercaCreate -> CreateNearbyChatScreen(nearbyManager, onConnected = { nearbyManager.stopAdvertising(); currentScreen.value = Screen.TeleoCercaChat }, onBack = { nearbyManager.stopAdvertising(); currentScreen.value = Screen.TeleoCercaEntry })
                Screen.TeleoCercaJoin -> JoinNearbyChatScreen(nearbyManager, onScan = {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) currentScreen.value = Screen.Scanner
                    else { pendingPermissionScreen = Screen.Scanner; ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1003) }
                }, onConnected = { nearbyManager.stopDiscovery(); currentScreen.value = Screen.TeleoCercaChat }, onBack = { nearbyManager.stopDiscovery(); currentScreen.value = Screen.TeleoCercaEntry })
                Screen.Scanner -> QRScannerScreen(onScanResult = { r -> nearbyManager.requestConnectionBySession(r); currentScreen.value = Screen.TeleoCercaJoin }, onBack = { currentScreen.value = Screen.TeleoCercaJoin })
                Screen.TeleoCercaChat -> NearbyChatScreen(nearbyManager, isListening, useEmojis.value, useEmotions.value, onSV = { startListening() }, onPV = { pauseListening() }, onB = { nearbyManager.disconnect(); currentScreen.value = Screen.Home })
            }
        } } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestPermission() { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001) }
    private fun nearbyPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()
    private fun hasNearbyPermissions(): Boolean = nearbyPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun requestNearbyPermissions() { ActivityCompat.requestPermissions(this, nearbyPermissions(), 1002) }
    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { isProcessingFinal.value = false }
            override fun onBeginningOfSpeech() {
                if (currentScreen.value == Screen.PalabraViva) {
                    // La frase final anterior permanece visible hasta recibir texto real.
                    currentSentence.value = ""
                    wordQueue.clear()
                }
            }
            override fun onRmsChanged(r: Float) { lastRms.value = r; if (isListening.value) currentEmotion.value = determineEmotion(currentSentence.value) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                if (isListening.value && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (isListening.value) startListening() }, 500)
                } else if (e != SpeechRecognizer.ERROR_CLIENT) isListening.value = false
            }
            override fun onPartialResults(pr: Bundle?) {
                pr?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.uppercase()?.let { t ->
                    currentEmotion.value = determineEmotion(t)
                    processLiveText(t)
                    if (currentScreen.value == Screen.TeleoCercaChat) nearbyManager.sendMessage(
                        TeleoNearbyMessage(type = "partial", emotion = currentEmotion.value, currentWord = t.split("\\s+").lastOrNull() ?: "", currentSentence = t)
                    )
                }
            }
            override fun onResults(r: Bundle?) {
                // La capa animada se cancela antes de publicar el resultado definitivo.
                isProcessingFinal.value = true
                currentSentence.value = ""
                wordQueue.clear()
                r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.uppercase()?.let { f ->
                    if (f.isNotBlank()) {
                        if (currentScreen.value == Screen.PalabraViva) {
                            sentenceHistory.clear()
                            sentenceHistory.add(f)
                        } else if (currentScreen.value == Screen.TeleoCercaChat) {
                            nearbyManager.sendMessage(TeleoNearbyMessage(type = "final", emotion = determineEmotion(f), message = f))
                        }
                    }
                }
                currentEmotion.value = "normal"
                if (isListening.value) Handler(Looper.getMainLooper()).postDelayed({ if (isListening.value) startListening() }, 400)
            }
            override fun onEvent(ev: Int, p: Bundle?) {}
        })
    }

    private fun processLiveText(t: String) {
        val normalized = t.trim()
        if (isProcessingFinal.value || normalized.isBlank() || normalized == currentSentence.value) return

        if (currentScreen.value == Screen.PalabraViva && currentSentence.value.isBlank()) sentenceHistory.clear()
        currentSentence.value = normalized

        // Los parciales pueden corregirse; la UI siempre anima la última versión reconocida.
        val latestWord = normalized.split("\\s+".toRegex()).last()
        if (wordQueue.lastOrNull() != latestWord) {
            wordQueue.clear()
            wordQueue.add(latestWord)
        }
    }
    private fun determineEmotion(t: String): String { if (!useEmotions.value) return "normal"; val u = t.uppercase(); return when { u.contains("JAJA") || u.contains("HAHA") -> "laughing"; lastRms.value > 10f -> "shouting"; lastRms.value < 1.5f && lastRms.value > -2f -> "whispering"; else -> "normal" } }
    private fun configureAudioForSpeech() {
        if (isSpeechAudioConfigured) return
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        isSpeechAudioConfigured = true
    }
    private fun restoreAudioMode() {
        if (!isSpeechAudioConfigured) return
        audioManager.mode = previousAudioMode
        isSpeechAudioConfigured = false
    }
    private fun startListening() { if (!hasRecordPermission()) { requestPermission(); return }; configureAudioForSpeech(); isListening.value = true; try { speechRecognizer.startListening(recognizerIntent) } catch (e: Exception) { restoreAudioMode(); isListening.value = false } }
    private fun pauseListening() { try { speechRecognizer.stopListening() } catch (_: Exception) {}; restoreAudioMode(); isListening.value = false }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, p, gr)
        val granted = gr.isNotEmpty() && gr.all { it == PackageManager.PERMISSION_GRANTED }
        when (rc) {
            1001 -> hasRecordPermission.value = granted
            1002, 1003 -> {
                if (granted) pendingPermissionScreen?.let { currentScreen.value = it }
                else Toast.makeText(this, if (rc == 1002) "Teleo Cerca necesita permisos de dispositivos cercanos" else "Se necesita la cámara para escanear el QR", Toast.LENGTH_LONG).show()
                pendingPermissionScreen = null
            }
        }
    }
    override fun onDestroy() { restoreAudioMode(); try { speechRecognizer.destroy() } catch (_: Exception) {}; nearbyManager.disconnect(); super.onDestroy() }
}

// --- COMPOSABLES ---

@Composable
fun StartupSplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(CyberDark),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.app_icon_new),
                contentDescription = "Logo Teleo",
                modifier = Modifier.size(142.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(28.dp))
            Box(modifier = Modifier.width(210.dp).height(1.dp).background(CyberCyan.copy(alpha = 0.35f)))
            Spacer(Modifier.height(22.dp))
            Image(
                painter = painterResource(id = R.drawable.logo_vetrabyte),
                contentDescription = "Vetrabyte Software Development",
                modifier = Modifier.width(270.dp).height(78.dp),
                contentScale = ContentScale.Fit,
                alpha = 0.95f
            )
            Spacer(Modifier.height(22.dp))
            Text("SOFTWARE QUE CONECTA", color = Color.White.copy(alpha = 0.42f), fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HomeScreen(ue: MutableState<Boolean>, uem: MutableState<Boolean>, un: String, uc: Int, ua: Bitmap?, onNavigate: (Screen) -> Unit) {
    val act = LocalContext.current as? android.app.Activity
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CyberDark, Color(0xFF1A1F26))))) {
        val compactHeader = maxWidth < 900.dp
        val compactCards = maxWidth < 780.dp
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (compactHeader) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { act?.finish() }, modifier = Modifier.size(48.dp).background(Color.Red.copy(alpha = 0.1f), CircleShape).border(1.dp, Color.Red.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.Red) }
                            Spacer(Modifier.width(16.dp))
                            Column { Text("TELEO", color = Color(uc), fontSize = 32.sp, fontWeight = FontWeight.Black); Text("Hola, $un", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.clickable { onNavigate(Screen.Profile) }) }
                        }
                        IconButton(onClick = { onNavigate(Screen.Profile) }, modifier = Modifier.size(56.dp).background(Color(uc).copy(alpha = 0.1f), CircleShape).border(1.dp, Color(uc).copy(alpha = 0.4f), CircleShape)) { if (ua != null) Image(ua.asImageBitmap(), null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop) else Icon(Icons.Default.AccountCircle, null, tint = Color(uc), modifier = Modifier.size(40.dp)) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(uc).copy(alpha = 0.2f))) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ConfigToggle("Emojis", ue.value) { ue.value = it }
                            Spacer(Modifier.height(8.dp))
                            ConfigToggle("Emociones", uem.value) { uem.value = it }
                        }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { act?.finish() }, modifier = Modifier.size(48.dp).background(Color.Red.copy(alpha = 0.1f), CircleShape).border(1.dp, Color.Red.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.Red) }
                        Spacer(Modifier.width(16.dp))
                        Column { Text("TELEO", color = Color(uc), fontSize = 32.sp, fontWeight = FontWeight.Black); Text("Hola, $un", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.clickable { onNavigate(Screen.Profile) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(uc).copy(alpha = 0.2f))) { Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { ConfigToggle("Emojis", ue.value) { ue.value = it }; Spacer(Modifier.width(20.dp)); ConfigToggle("Emociones", uem.value) { uem.value = it } } }
                        Spacer(Modifier.width(16.dp)); IconButton(onClick = { onNavigate(Screen.Profile) }, modifier = Modifier.size(56.dp).background(Color(uc).copy(alpha = 0.1f), CircleShape).border(1.dp, Color(uc).copy(alpha = 0.4f), CircleShape)) { if (ua != null) Image(ua.asImageBitmap(), null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop) else Icon(Icons.Default.AccountCircle, null, tint = Color(uc), modifier = Modifier.size(40.dp)) }
                    }
                }
            }
            if (compactCards) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeCard(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), t = "Palabra Viva", d = "Subtítulos en vivo.", i = Icons.Default.Mic, c = CyberMagenta) { onNavigate(Screen.PalabraViva) }
                    HomeCard(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), t = "Escribir", d = "Pantalla completa.", i = Icons.Default.Keyboard, c = CyberTeal) { onNavigate(Screen.EscribirYMostrar) }
                    HomeCard(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), t = "Teleo Cerca", d = "Conexión local.", i = Icons.Default.Wifi, c = CyberCyan) { onNavigate(Screen.TeleoCercaEntry) }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    HomeCard(modifier = Modifier.weight(1f), t = "Palabra Viva", d = "Subtítulos en vivo.", i = Icons.Default.Mic, c = CyberMagenta) { onNavigate(Screen.PalabraViva) }
                    HomeCard(modifier = Modifier.weight(1f), t = "Escribir", d = "Pantalla completa.", i = Icons.Default.Keyboard, c = CyberTeal) { onNavigate(Screen.EscribirYMostrar) }
                    HomeCard(modifier = Modifier.weight(1f), t = "Teleo Cerca", d = "Conexión local.", i = Icons.Default.Wifi, c = CyberCyan) { onNavigate(Screen.TeleoCercaEntry) }
                }
            }
        }
    }
}

@Composable
fun ConfigToggle(l: String, v: Boolean, onC: (Boolean) -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Text(l, color = Color.White.copy(0.7f), fontSize = 14.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Switch(v, onC, colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan)) } }

@Composable
fun HomeCard(modifier: Modifier, t: String, d: String, i: ImageVector, c: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.03f), border = BorderStroke(2.dp, c.copy(alpha = 0.4f))) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(modifier = Modifier.size(80.dp).background(c.copy(alpha = 0.1f), CircleShape).border(1.dp, c.copy(0.3f), CircleShape), contentAlignment = Alignment.Center) { Icon(i, null, modifier = Modifier.size(40.dp), tint = c) }
            Spacer(Modifier.height(24.dp)); Text(t.uppercase(), color = c, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center); Text(d, color = Color.White.copy(0.5f), fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ProfileScreen(
    cn: String,
    cc: Int,
    ca: Bitmap?,
    onSave: (String, Int) -> Unit,
    onAvatarChange: (Bitmap?) -> Unit,
    onTakeAvatar: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var name by remember(cn) { mutableStateOf(cn) }
    var colorValue by remember(cc) { mutableStateOf(cc) }
    val accent = Color(colorValue)
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { TeleoUtils.avatarFromUri(context, it) }?.let(onAvatarChange)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onTakeAvatar()
    }
    val openCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onTakeAvatar()
        } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.13f), CyberDark, Color(0xFF05070A)),
                radius = 1200f
            )
        )
    ) {
        val compact = maxWidth < 760.dp
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = 0.05f), CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White.copy(alpha = 0.72f))
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("TU PERFIL", color = accent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Así te verán las personas cerca", color = Color.White.copy(alpha = 0.48f), fontSize = 12.sp)
                }
                Box(modifier = Modifier.size(8.dp).background(accent, CircleShape))
            }

            Spacer(Modifier.height(18.dp))
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = 0.035f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
            ) {
                val contentModifier = Modifier.fillMaxSize().padding(if (compact) 18.dp else 28.dp)
                if (compact) {
                    Column(contentModifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        ProfileAvatarPanel(ca, accent, { galleryLauncher.launch("image/*") }, openCamera, { onAvatarChange(null) })
                        Spacer(Modifier.height(24.dp))
                        ProfileDetails(name, { if (it.length <= 24) name = it }, colorValue, { colorValue = it }, accent, { onSave(name.trim(), colorValue) })
                    }
                } else {
                    Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(34.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(0.9f), contentAlignment = Alignment.Center) {
                            ProfileAvatarPanel(ca, accent, { galleryLauncher.launch("image/*") }, openCamera, { onAvatarChange(null) })
                        }
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight(0.78f).background(Color.White.copy(alpha = 0.08f)))
                        Box(modifier = Modifier.weight(1.25f), contentAlignment = Alignment.Center) {
                            ProfileDetails(name, { if (it.length <= 24) name = it }, colorValue, { colorValue = it }, accent, { onSave(name.trim(), colorValue) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatarPanel(ca: Bitmap?, accent: Color, onGallery: () -> Unit, onCamera: () -> Unit, onRemove: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(176.dp).background(accent.copy(alpha = 0.08f), CircleShape).border(2.dp, accent.copy(alpha = 0.72f), CircleShape).padding(7.dp)) {
                if (ca != null) {
                    Image(ca.asImageBitmap(), "Foto de perfil", Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.035f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, modifier = Modifier.size(82.dp), tint = accent.copy(alpha = 0.72f))
                    }
                }
            }
            Box(modifier = Modifier.align(Alignment.BottomEnd).size(42.dp).background(accent, CircleShape).border(3.dp, CyberDark, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, tint = CyberDark, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("IMAGEN DE PERFIL", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCamera, border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))) {
                Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Cámara", color = Color.White)
            }
            OutlinedButton(onClick = onGallery, border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))) {
                Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Galería", color = Color.White)
            }
        }
        if (ca != null) TextButton(onClick = onRemove) { Text("Quitar imagen", color = Color.White.copy(alpha = 0.48f), fontSize = 12.sp) }
    }
}

@Composable
private fun ProfileDetails(name: String, onNameChange: (String) -> Unit, colorValue: Int, onColorChange: (Int) -> Unit, accent: Color, onSave: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
        Text("NICKNAME", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.AlternateEmail, null, tint = accent) },
            supportingText = { Text("${name.length}/24", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
            placeholder = { Text("Tu nombre visible") },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                cursorColor = accent
            )
        )
        Spacer(Modifier.height(18.dp))
        Text("COLOR DE IDENTIDAD", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            ColorPalette.forEach { color ->
                val selected = colorValue == color.value.toLong().toInt()
                Box(
                    modifier = Modifier.size(if (selected) 40.dp else 34.dp).background(color, CircleShape)
                        .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else color.copy(alpha = 0.45f), CircleShape)
                        .clickable { onColorChange(color.value.toLong().toInt()) },
                    contentAlignment = Alignment.Center
                ) { if (selected) Icon(Icons.Default.Check, null, tint = if (color.luminance() > 0.55f) CyberDark else Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSave,
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = if (accent.luminance() > 0.55f) CyberDark else Color.White)
        ) {
            Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(9.dp)); Text("GUARDAR PERFIL", fontWeight = FontWeight.Black)
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun AvatarCameraScreen(onCaptured: (Bitmap) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current; val lco = androidx.lifecycle.compose.LocalLifecycleOwner.current; val cpf = remember { ProcessCameraProvider.getInstance(ctx) }; var ic: ImageCapture? by remember { mutableStateOf(null) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c -> PreviewView(c).also { pv -> cpf.addListener({ val cp = cpf.get(); val pr = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }; ic = ImageCapture.Builder().build(); try { cp.unbindAll(); cp.bindToLifecycle(lco, CameraSelector.DEFAULT_FRONT_CAMERA, pr, ic) } catch (e: Exception) {} }, ContextCompat.getMainExecutor(c)) } }, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.align(Alignment.Center).size(280.dp).border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape))
        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(64.dp).background(Color.White.copy(alpha = 0.1f), CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.White) }
            IconButton(onClick = { ic?.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                            onCaptured(TeleoUtils.prepareAvatar(bitmap, image.imageInfo.rotationDegrees))
                        }
                    } finally { image.close() }
                }
            }) }, modifier = Modifier.size(80.dp).background(Color.White, CircleShape).border(4.dp, CyberCyan, CircleShape)) { Icon(Icons.Default.Camera, null, modifier = Modifier.size(40.dp), tint = Color.Black) }
        }
    }
}

@Composable
fun TeleoScreen(cs: MutableState<String>, wq: MutableList<String>, sh: List<String>, isl: MutableState<Boolean>, ipf: MutableState<Boolean>, hp: MutableState<Boolean>, ce: MutableState<String>, ue: Boolean, uem: Boolean, uwt: Boolean, onWalkieModeChange: (Boolean) -> Unit, onStart: () -> Unit, onPause: () -> Unit, onClear: () -> Unit, onRequestPermission: () -> Unit, onBack: () -> Unit) {
    val configuration = LocalConfiguration.current
    val compactScreen = configuration.screenWidthDp < 800 || configuration.screenHeightDp < 480
    val livePhrase = cs.value.trim()
    val finalPhrase = sh.lastOrNull()?.trim().orEmpty()
    val showFinalPhrase = finalPhrase.isNotBlank() && (ipf.value || livePhrase.isBlank())
    val animatedWord = if (showFinalPhrase) "" else wq.lastOrNull().orEmpty()
    val rawPhrase = if (showFinalPhrase) finalPhrase else animatedWord
    val decoratedPhrase = if (ue) TeleoUtils.decorate(rawPhrase) else rawPhrase
    val phraseSize = when {
        rawPhrase.length < 35 -> if (compactScreen) 52.sp else 82.sp
        rawPhrase.length < 85 -> if (compactScreen) 40.sp else 64.sp
        rawPhrase.length < 150 -> if (compactScreen) 32.sp else 48.sp
        rawPhrase.length < 240 -> if (compactScreen) 26.sp else 38.sp
        else -> if (compactScreen) 22.sp else 30.sp
    }
    val emotionColor = if (uem) {
        when (ce.value) {
            "shouting" -> Color.Red
            "laughing" -> CyberYellow
            "whispering" -> Color.White.copy(alpha = 0.7f)
            else -> Color.White
        }
    } else Color.White
    Box(modifier = Modifier.fillMaxSize().background(CyberDark)) {
        val ei = if (uem) { when(ce.value) { "shouting" -> "📢 "; "whispering" -> "🤫 "; "laughing" -> "😂 "; else -> "" } } else ""
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (compactScreen) 16.dp else 32.dp,
                    top = if (compactScreen) 58.dp else 70.dp,
                    end = if (compactScreen) 16.dp else 32.dp,
                    bottom = if (compactScreen) 16.dp else 24.dp
                )
                .verticalScroll(rememberScrollState())
                .then(
                    if (uwt && hp.value) {
                        Modifier.pointerInput(uwt, isl.value) {
                            detectTapGestures(
                                onPress = {
                                    val startedByPress = !isl.value
                                    if (startedByPress) onStart()
                                    tryAwaitRelease()
                                    if (startedByPress) onPause()
                                }
                            )
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (showFinalPhrase) {
                Text(
                    text = decoratedPhrase,
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    fontSize = phraseSize,
                    lineHeight = (phraseSize.value * 1.18f).sp,
                    fontWeight = FontWeight.Black,
                    textAlign = if (rawPhrase.length > 55) TextAlign.Justify else TextAlign.Center,
                    softWrap = true
                )
            } else {
                AnimatedContent(
                    targetState = animatedWord,
                    transitionSpec = {
                        (fadeIn(tween(110)) + scaleIn(initialScale = 0.88f, animationSpec = tween(150))) togetherWith
                            fadeOut(tween(80))
                    },
                    label = "palabraDetectada"
                ) { word ->
                    val decoratedWord = if (ue) TeleoUtils.decorate(word) else word
                    Text(
                        text = if (decoratedWord.isBlank()) "" else ei + decoratedWord,
                        modifier = Modifier.fillMaxWidth(),
                        color = emotionColor,
                        fontSize = if (uem && ce.value == "shouting") (phraseSize.value * 1.12f).sp else phraseSize,
                        lineHeight = if (uem && ce.value == "shouting") (phraseSize.value * 1.24f).sp else (phraseSize.value * 1.18f).sp,
                        fontWeight = FontWeight.Black,
                        fontStyle = if (uem && ce.value == "whispering") FontStyle.Italic else FontStyle.Normal,
                        textAlign = TextAlign.Center,
                        softWrap = true
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compactScreen) 8.dp else 12.dp, vertical = if (compactScreen) 6.dp else 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Home, null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(20.dp)) }
                Box(modifier = Modifier.size(6.dp).background(if (isl.value) CyberTeal else Color.Red, CircleShape))
            }
            if (!hp.value) {
                TextButton(onClick = onRequestPermission, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("PERMISO", color = Color.Red.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compactScreen) 2.dp else 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("WT", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Switch(
                            checked = uwt,
                            onCheckedChange = {
                                if (it && isl.value) onPause()
                                onWalkieModeChange(it)
                            },
                            modifier = Modifier.height(28.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberCyan,
                                checkedTrackColor = CyberCyan.copy(alpha = 0.28f),
                                uncheckedThumbColor = Color.White.copy(alpha = 0.45f),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.12f)
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isl.value) CyberCyan.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable(enabled = !isl.value) { onStart() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Mic, "Tocar para activar micrófono", tint = if (isl.value) CyberTeal else CyberCyan.copy(alpha = 0.75f), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onPause, enabled = isl.value, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Pause, "Pausa", tint = if (isl.value) CyberMagenta.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.24f), modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.RestartAlt, "Reset", tint = CyberYellow.copy(alpha = 0.7f), modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
fun WriteAndShowScreen(ue: Boolean, onBackAction: () -> Unit) {
    var t by remember { mutableStateOf("") }; var s by remember { mutableStateOf(false) }; var f by remember { mutableStateOf(48f) }
    val configuration = LocalConfiguration.current
    val compactScreen = configuration.screenWidthDp < 800 || configuration.screenHeightDp < 480
    if (s) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = { f = maxOf(24f, f - 8f) }, modifier = Modifier.size(48.dp).border(1.dp, CyberCyan, CircleShape)) { Text("A-", color = CyberCyan) }
                    IconButton(onClick = { f = minOf(120f, f + 8f) }, modifier = Modifier.size(48.dp).border(1.dp, CyberCyan, CircleShape)) { Text("A+", color = CyberCyan) }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                Text(text = if (ue) TeleoUtils.decorate(t) else t, color = Color.White, fontSize = f.sp, textAlign = TextAlign.Center)
            }
            if (compactScreen) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { s = false }) { Text("EDITAR", color = CyberCyan) }
                    TextButton(onClick = { t = ""; s = false }) { Text("LIMPIAR", color = CyberYellow) }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { s = false }) { Text("EDITAR", color = CyberCyan) }
                    Spacer(modifier = Modifier.width(24.dp))
                    TextButton(onClick = { t = ""; s = false }) { Text("LIMPIAR", color = CyberYellow) }
                }
            }
        }
    } else Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBackAction, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, null, tint = Color.White) }; Text("ESCRIBIR", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.size(48.dp)) }; OutlinedTextField(value = t, onValueChange = { t = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Mensaje...") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CyberCyan)); if (compactScreen) { Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Button(onClick = { t = "" }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("LIMPIAR") }; Button(onClick = { if (t.isNotBlank()) s = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark)) { Text("MOSTRAR", fontWeight = FontWeight.Bold) } } } else { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { Button(onClick = { t = "" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("LIMPIAR") }; Button(onClick = { if (t.isNotBlank()) s = true }, modifier = Modifier.weight(1.5f), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark)) { Text("MOSTRAR", fontWeight = FontWeight.Bold) } } } }
}

@Composable
fun TeleoNearbyEntryScreen(ue: MutableState<Boolean>, uem: MutableState<Boolean>, onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        val compactScreen = maxWidth < 780.dp
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (compactScreen) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, null, tint = Color.White) }; Text("TELEO CERCA", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Black); Spacer(modifier = Modifier.size(48.dp)) }
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(modifier = Modifier.padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.05f)) { Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { ConfigToggle("Emojis", ue.value) { ue.value = it } } }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, null, tint = Color.White) }; Text("TELEO CERCA", color = CyberCyan, fontSize = 32.sp, fontWeight = FontWeight.Black); Surface(modifier = Modifier.padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.05f)) { Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { ConfigToggle("Emojis", ue.value) { ue.value = it } } } }
            }
            Spacer(modifier = Modifier.height(32.dp))
            if (compactScreen) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HomeCard(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), t = "Crear Charla", d = "Modo Host.", i = Icons.Default.Add, c = CyberCyan) { onNavigate(Screen.TeleoCercaCreate) }
                    HomeCard(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), t = "Unirme", d = "Buscar Host.", i = Icons.Default.Search, c = CyberTeal) { onNavigate(Screen.TeleoCercaJoin) }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) { HomeCard(modifier = Modifier.weight(1f), t = "Crear Charla", d = "Modo Host.", i = Icons.Default.Add, c = CyberCyan) { onNavigate(Screen.TeleoCercaCreate) }; HomeCard(modifier = Modifier.weight(1f), t = "Unirme", d = "Buscar Host.", i = Icons.Default.Search, c = CyberTeal) { onNavigate(Screen.TeleoCercaJoin) } }
            }
        }
    }
}

@Composable
fun CreateNearbyChatScreen(manager: NearbyConnectionManager, onConnected: () -> Unit, onBack: () -> Unit) {
    val qrb = remember(manager.sessionId) { TeleoUtils.generateQR(manager.sessionId) }
    LaunchedEffect(Unit) { manager.startAdvertising() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(CyberDark)) {
        val compactScreen = maxWidth < 900.dp
        Column(modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { IconButton(onClick = onBack) { Icon(Icons.Default.Home, null, tint = Color.Gray) } ; Text("HOST: CREAR CHARLA", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.size(48.dp)) }
        Spacer(modifier = Modifier.height(32.dp))
        if (compactScreen) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(1f).padding(8.dp), shape = RoundedCornerShape(16.dp), color = Color.White) { qrb?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxSize()) } }
                Spacer(modifier = Modifier.height(24.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("CHARLA DE", color = Color.Gray, fontSize = 14.sp); Text(text = manager.myName, color = CyberCyan, fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center); Spacer(modifier = Modifier.height(18.dp)); NearbyStatus(manager, onRetry = { manager.startAdvertising() }) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Surface(modifier = Modifier.size(240.dp).padding(8.dp), shape = RoundedCornerShape(16.dp), color = Color.White) { qrb?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxSize()) } }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("CHARLA DE", color = Color.Gray, fontSize = 14.sp); Text(text = manager.myName, color = CyberCyan, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center); Spacer(modifier = Modifier.height(18.dp)); NearbyStatus(manager, onRetry = { manager.startAdvertising() }) }
            }
        }
        if (manager.pendingRequests.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Surface(modifier = Modifier.fillMaxWidth(0.8f), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "SOLICITUDES", color = CyberCyan, fontWeight = FontWeight.Bold)
                    manager.pendingRequests.forEach { p ->
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(text = p.name, color = Color.White)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { manager.reject(p) }) { Text("RECHAZAR", color = Color.Red) }; Button(onClick = { manager.accept(p) }, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark)) { Text("ACEPTAR") } }
                        }
                    }
                }
            }
        }
        if (manager.flowState.value == NearbyFlowState.CONNECTED) LaunchedEffect(Unit) { onConnected() }
    }
    }
}

@Composable
fun JoinNearbyChatScreen(manager: NearbyConnectionManager, onScan: () -> Unit, onConnected: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { manager.startDiscovery() }
    if (manager.flowState.value == NearbyFlowState.CONNECTED) LaunchedEffect(Unit) { onConnected() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        val compactScreen = maxWidth < 780.dp
        Column(modifier = Modifier.fillMaxSize()) {
        if (compactScreen) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { IconButton(onClick = onBack) { Icon(Icons.Default.Home, null, tint = Color.Gray) }; Text("UNIRSE", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.size(48.dp)) }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(modifier = Modifier.width(8.dp)); Text("ESCANEAR") }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { IconButton(onClick = onBack) { Icon(Icons.Default.Home, null, tint = Color.Gray) }; Text("UNIRSE", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(modifier = Modifier.width(8.dp)); Text("ESCANEAR") } }
        }
        Spacer(modifier = Modifier.height(16.dp))
        NearbyStatus(manager, onRetry = { manager.startDiscovery() })
        Spacer(modifier = Modifier.height(10.dp))
        val canSelect = manager.flowState.value == NearbyFlowState.SEARCHING
        if (manager.discoveredEndpoints.isEmpty()) Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(text = if (manager.flowState.value == NearbyFlowState.ERROR) "Revisá los permisos, Bluetooth y Wi‑Fi" else "Las charlas aparecerán aquí", color = Color.Gray) }
        else LazyColumn(modifier = Modifier.weight(1f)) { items(manager.discoveredEndpoints, key = { it.id }) { e -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable(enabled = canSelect) { manager.requestConnection(e) }, colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = if (canSelect) 0.05f else 0.025f))) { Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Column { Text(e.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("Sesión ${e.sessionId}", color = Color.Gray, fontSize = 11.sp) }; Spacer(modifier = Modifier.weight(1f)); Text(if (canSelect) "UNIRME  ›" else "ESPERÁ…", color = CyberCyan.copy(alpha = if (canSelect) 1f else 0.4f), fontSize = 14.sp) } } } }
    }
    }
}

@Composable
private fun NearbyStatus(manager: NearbyConnectionManager, onRetry: () -> Unit) {
    val state = manager.flowState.value
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state) {
            NearbyFlowState.STARTING, NearbyFlowState.SEARCHING, NearbyFlowState.ADVERTISING, NearbyFlowState.REQUESTING, NearbyFlowState.WAITING_APPROVAL -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = CyberCyan)
            NearbyFlowState.CONNECTED -> Icon(Icons.Default.CheckCircle, null, tint = CyberTeal)
            NearbyFlowState.ERROR -> Icon(Icons.Default.ErrorOutline, null, tint = Color.Red)
            NearbyFlowState.IDLE -> Icon(Icons.Default.Wifi, null, tint = Color.Gray)
        }
        Text(manager.statusMessage.value.ifBlank { "Preparando…" }, color = if (state == NearbyFlowState.ERROR) Color.Red.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        if (state == NearbyFlowState.ERROR) TextButton(onClick = onRetry) { Text("REINTENTAR", color = CyberCyan, fontWeight = FontWeight.Bold) }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun QRScannerScreen(onScanResult: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current; val lco = androidx.lifecycle.compose.LocalLifecycleOwner.current; val cpf = remember { ProcessCameraProvider.getInstance(ctx) }; var hs by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c -> PreviewView(c).also { pv -> cpf.addListener({ try { val cp = cpf.get(); val pr = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }; val ia = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build(); val sc = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().build()); ia.setAnalyzer(ContextCompat.getMainExecutor(c)) { ip -> val mi = ip.image; if (mi != null && !hs) sc.process(InputImage.fromMediaImage(mi, ip.imageInfo.rotationDegrees)).addOnSuccessListener { b -> b.firstOrNull()?.rawValue?.let { hs = true; onScanResult(it) } }.addOnCompleteListener { ip.close() } else ip.close() }; cp.unbindAll(); cp.bindToLifecycle(lco, CameraSelector.DEFAULT_BACK_CAMERA, pr, ia) } catch (e: Exception) {} }, ContextCompat.getMainExecutor(c)) } }, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().padding(64.dp).border(2.dp, CyberCyan, RoundedCornerShape(24.dp)))
        IconButton(onClick = onBack, modifier = Modifier.padding(24.dp).align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f), CircleShape)) { Icon(Icons.Default.Close, "Cerrar", tint = Color.White) }
    }
}

@Composable
fun NearbyChatScreen(manager: NearbyConnectionManager, isListening: MutableState<Boolean>, useEmojis: Boolean, useEmotions: Boolean, onSV: () -> Unit, onPV: () -> Unit, onB: () -> Unit) {
    var ti by remember { mutableStateOf("") }; val ls = rememberLazyListState(); val fm = LocalFocusManager.current; val itp = ti.isNotEmpty(); var sp by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val compactScreen = configuration.screenWidthDp < 800 || configuration.screenHeightDp < 480
    LaunchedEffect(manager.messages.size) { if (manager.messages.isNotEmpty()) ls.animateScrollToItem(manager.messages.size - 1) }
    Column(modifier = Modifier.fillMaxSize().background(CyberDark)) {
        AnimatedVisibility(visible = !itp) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onB, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, null, tint = Color.White) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (manager.isHost.value) IconButton(onClick = { sp = !sp }, modifier = Modifier.size(48.dp).background(CyberTeal.copy(alpha = 0.1f), CircleShape)) { Icon(Icons.Default.Group, null, tint = CyberTeal) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(modifier = Modifier, shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.05f), border = BorderStroke(1.dp, Color(manager.myColor).copy(alpha = 0.25f))) { Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(8.dp).background(if (isListening.value) CyberTeal else Color.Red, CircleShape)); Spacer(modifier = Modifier.width(8.dp)); Text(text = if (isListening.value) "ON" else "OFF", color = if (isListening.value) CyberTeal else Color.Red, fontSize = 10.sp); Spacer(modifier = Modifier.width(12.dp)); IconButton(onClick = { if (isListening.value) onPV() else onSV() }, modifier = Modifier.size(36.dp).background(if (isListening.value) CyberMagenta.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f), CircleShape)) { Icon(imageVector = if (isListening.value) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null, tint = if (isListening.value) CyberMagenta else Color(manager.myColor)) } } }
                }
            }
        }
        if (sp && manager.isHost.value) {
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.05f)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(text = "PARTICIPANTES", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    manager.connectedParticipants.forEach { p -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(text = p.name, color = Color.White); IconButton(onClick = { manager.kick(p) }) { Icon(Icons.Default.PersonRemove, null, tint = Color.Red) } } }
                }
            }
        }
        AnimatedVisibility(visible = !itp) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).background(if (useEmotions) { when(manager.remoteEmotion.value) { "shouting" -> Color.Red.copy(alpha = 0.2f); "laughing" -> CyberYellow.copy(alpha = 0.1f); else -> Color.Black.copy(alpha = 0.3f) } } else Color.Black.copy(alpha = 0.3f)).padding(12.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val ei = if (useEmotions) { when(manager.remoteEmotion.value) { "shouting" -> "📢 "; "whispering" -> "🤫 "; "laughing" -> "😂 "; else -> "" } } else ""
                    if (manager.remoteWord.value.isNotBlank()) Text(text = ei + (if (useEmojis) TeleoUtils.decorate(manager.remoteWord.value) else manager.remoteWord.value), color = if (useEmotions) { when(manager.remoteEmotion.value) { "shouting" -> Color.Red; "laughing" -> CyberYellow; else -> Color.White } } else Color.White, fontSize = if (useEmotions && manager.remoteEmotion.value == "shouting") if (compactScreen) 42.sp else 60.sp else if (compactScreen) 36.sp else 48.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (manager.remoteSentence.value.isNotBlank()) Text(text = if (useEmojis) TeleoUtils.decorate(manager.remoteSentence.value) else manager.remoteSentence.value, color = if (useEmotions && manager.remoteEmotion.value == "whispering") Color.Gray else CyberTeal, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = ls) { items(manager.messages) { m -> ChatMessageBubble(m, m.senderId == manager.myId, useEmojis, useEmotions) } }
        Surface(modifier = Modifier.fillMaxWidth().imePadding(), color = Color.Black.copy(alpha = 0.5f)) { Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = ti, onValueChange = { ti = it }, modifier = Modifier.weight(1f), placeholder = { Text("Mensaje...", color = Color.Gray) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(manager.myColor)), trailingIcon = { if (ti.isNotBlank()) IconButton(onClick = { manager.sendMessage(TeleoNearbyMessage(type = "text", message = ti)); ti = ""; fm.clearFocus() }) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color(manager.myColor)) } }) } }
    }
}

@Composable
fun ChatMessageBubble(m: TeleoNearbyMessage, isMe: Boolean, ue: Boolean, uem: Boolean) {
    if (m.type == "system") { Text(text = m.message, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center); return }
    val align = if (isMe) Alignment.End else Alignment.Start; val pc = Color(m.senderColor)
    val bc = if (uem) { when (m.emotion) { "shouting" -> Color.Red.copy(0.2f); "laughing" -> CyberYellow.copy(0.2f); else -> pc.copy(if (isMe) 0.2f else 0.1f) } } else pc.copy(if (isMe) 0.2f else 0.1f)
    val tc = if (uem && m.emotion == "whispering") Color.Gray else if (bc.luminance() > 0.6f) Color.Black else Color.White
    val txt = if (ue) TeleoUtils.decorate(m.message) else m.message; val pr = if (uem) { when(m.emotion) { "shouting" -> "📢 "; "whispering" -> "🤫 "; "laughing" -> "😂 "; else -> "" } } else ""
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
        if (m.senderName.isNotBlank()) Text(text = m.senderName, color = pc.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        Surface(modifier = Modifier, shape = RoundedCornerShape(12.dp), color = bc, border = BorderStroke(1.dp, pc.copy(if (isMe) 0.6f else 0.3f))) { Text(text = pr + txt, color = if (isMe && pc.luminance() < 0.4f) Color.White else tc, fontSize = if (uem && m.emotion == "whispering") 15.sp else 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontStyle = if (uem && m.emotion == "whispering") FontStyle.Italic else FontStyle.Normal, fontWeight = if (uem && m.emotion == "shouting") FontWeight.Bold else FontWeight.Normal) }
    }
}
