package com.nicolas.teleo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    var isHost = mutableStateOf(false)
    var connectedParticipants = mutableStateListOf<Participant>()
    var pendingRequests = mutableStateListOf<Participant>()
    var messages = mutableStateListOf<TeleoNearbyMessage>()
    var discoveredEndpoints = mutableStateListOf<Endpoint>()
    var remoteWord = mutableStateOf(""); var remoteSentence = mutableStateOf(""); var remoteEmotion = mutableStateOf("normal")

    data class Endpoint(val id: String, val name: String)
    data class Participant(val id: String, val name: String)

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
                if (pendingRequests.none { it.id == id }) pendingRequests.add(Participant(id, info.endpointName))
            }
            else connectionsClient.acceptConnection(id, payloadCallback)
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            mainHandler.post {
                if (result.status.isSuccess) {
                    val p = pendingRequests.find { it.id == id } ?: Participant(id, "Usuario")
                    pendingRequests.removeAll { it.id == id }
                    if (connectedParticipants.none { it.id == id }) connectedParticipants.add(p)
                    messages.add(TeleoNearbyMessage(type = "system", message = "${p.name} conectado"))
                } else pendingRequests.removeAll { it.id == id }
            }
        }
        override fun onDisconnected(id: String) {
            mainHandler.post {
                val p = connectedParticipants.find { it.id == id }; connectedParticipants.removeAll { it.id == id }
                p?.let { messages.add(TeleoNearbyMessage(type = "system", message = "${it.name} salió")) }
            }
        }
    }

    fun startAdvertising() { mainHandler.post { isHost.value = true; connectionsClient.startAdvertising(myName, SERVICE_ID, connectionLifecycleCallback, AdvertisingOptions.Builder().setStrategy(STRATEGY).build()) } }
    fun stopAdvertising() { connectionsClient.stopAdvertising() }
    fun startDiscovery() { mainHandler.post { isHost.value = false; discoveredEndpoints.clear(); connectionsClient.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() { override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) { if (discoveredEndpoints.none { it.id == id }) discoveredEndpoints.add(Endpoint(id, info.endpointName)) }; override fun onEndpointLost(id: String) { discoveredEndpoints.removeAll { it.id == id } } }, DiscoveryOptions.Builder().setStrategy(STRATEGY).build()) } }
    fun stopDiscovery() { connectionsClient.stopDiscovery() }
    fun requestConnection(e: Endpoint) { connectionsClient.requestConnection(myName, e.id, connectionLifecycleCallback) }
    fun accept(p: Participant) { connectionsClient.acceptConnection(p.id, payloadCallback) }
    fun reject(p: Participant) { connectionsClient.rejectConnection(p.id); pendingRequests.remove(p) }
    fun kick(p: Participant) { connectionsClient.disconnectFromEndpoint(p.id); connectedParticipants.remove(p) }
    fun sendMessage(msg: TeleoNearbyMessage) { val msgW = msg.copy(senderId = myId, senderName = myName, senderColor = myColor); val bytes = msgW.toJSON().toByteArray(); connectedParticipants.forEach { connectionsClient.sendPayload(it.id, Payload.fromBytes(bytes)) }; if (msgW.type == "text" || msgW.type == "final") messages.add(msgW) }
    fun disconnect() { connectionsClient.stopAllEndpoints(); connectedParticipants.clear(); pendingRequests.clear(); messages.clear(); isHost.value = false }
}

// --- UTILIDADES ---

object TeleoUtils {
    fun decorate(t: String): String { var r = t; mapOf("HOLA" to "👋", "CHAU" to "👋", "GRACIAS" to "🙏", "AMOR" to "❤️", "JAJA" to "😂", "MATE" to "🧉").forEach { (w, e) -> r = r.replace("\\b$w\\b".toRegex(RegexOption.IGNORE_CASE)) { "${it.value} $e" } }; return r }
    fun generateQR(c: String): Bitmap? { try { val m = QRCodeWriter().encode(c, BarcodeFormat.QR_CODE, 512, 512); val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565); for (x in 0 until m.width) for (y in 0 until m.height) b.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE); return b } catch (e: Exception) { return null } }
    fun toB64(b: Bitmap): String { val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out); return Base64.encodeToString(out.toByteArray(), Base64.DEFAULT) }
    fun fromB64(s: String): Bitmap? { try { val b = Base64.decode(s, Base64.DEFAULT); return BitmapFactory.decodeByteArray(b, 0, b.size) } catch (e: Exception) { return null } }
}

// --- ACTIVIDAD PRINCIPAL ---

class MainActivity : ComponentActivity() {
    private lateinit var speechRecognizer: SpeechRecognizer; private lateinit var recognizerIntent: Intent; private lateinit var nearbyManager: NearbyConnectionManager
    private val currentSentence = mutableStateOf(""); private val wordQueue = mutableStateListOf<String>(); private val sentenceHistory = mutableStateListOf<String>()
    private val isListening = mutableStateOf(false); private val isProcessingFinal = mutableStateOf(false); private val hasRecordPermission = mutableStateOf(false)
    private val lastRms = mutableStateOf(0f); private val currentEmotion = mutableStateOf("normal"); private val currentScreen = mutableStateOf(Screen.Home)
    private val useEmojis = mutableStateOf(true); private val useEmotions = mutableStateOf(true); private val userName = mutableStateOf(""); private val userColor = mutableStateOf(0xFF00E5FF.toInt()); private val userAvatar = mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("teleo_prefs", Context.MODE_PRIVATE)
        userName.value = prefs.getString("user_name", Build.MODEL) ?: Build.MODEL; userColor.value = prefs.getInt("user_color", 0xFF00E5FF.toInt())
        prefs.getString("user_avatar", null)?.let { userAvatar.value = TeleoUtils.fromB64(it) }
        nearbyManager = NearbyConnectionManager(this).apply { myName = userName.value; myColor = userColor.value }
        initSpeechRecognizer(); hasRecordPermission.value = checkNearbyPermissions()
        setContent { TeleoTheme { Surface(modifier = Modifier.fillMaxSize(), color = CyberDark) {
            when (currentScreen.value) {
                Screen.Home -> HomeScreen(useEmojis, useEmotions, userName.value, userColor.value, userAvatar.value, onNavigate = { currentScreen.value = it })
                Screen.Profile -> ProfileScreen(userName.value, userColor.value, userAvatar.value, onSave = { n, c -> userName.value = n; userColor.value = c; nearbyManager.myName = n; nearbyManager.myColor = c; prefs.edit().putString("user_name", n).putInt("user_color", c).apply(); currentScreen.value = Screen.Home }, onTakeAvatar = { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) currentScreen.value = Screen.AvatarCamera else requestPermission() }, onBack = { currentScreen.value = Screen.Home })
                Screen.AvatarCamera -> AvatarCameraScreen(onCaptured = { b -> userAvatar.value = b; prefs.edit().putString("user_avatar", TeleoUtils.toB64(b)).apply(); currentScreen.value = Screen.Profile }, onBack = { currentScreen.value = Screen.Profile })
                Screen.PalabraViva -> TeleoScreen(currentSentence, wordQueue, sentenceHistory, isListening, isProcessingFinal, hasRecordPermission, currentEmotion, useEmojis.value, useEmotions.value, onStart = { startListening() }, onPause = { pauseListening() }, onClear = { currentSentence.value = ""; wordQueue.clear(); sentenceHistory.clear() }, onRequestPermission = { requestPermission() }, onBack = { pauseListening(); currentScreen.value = Screen.Home })
                Screen.EscribirYMostrar -> WriteAndShowScreen(ue = useEmojis.value, onBackAction = { currentScreen.value = Screen.Home })
                Screen.TeleoCercaEntry -> TeleoNearbyEntryScreen(useEmojis, useEmotions, onNavigate = { currentScreen.value = it }, onBack = { currentScreen.value = Screen.Home })
                Screen.TeleoCercaCreate -> CreateNearbyChatScreen(nearbyManager, onConnected = { currentScreen.value = Screen.TeleoCercaChat }, onBack = { nearbyManager.stopAdvertising(); currentScreen.value = Screen.TeleoCercaEntry })
                Screen.TeleoCercaJoin -> JoinNearbyChatScreen(nearbyManager, onScan = { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) currentScreen.value = Screen.Scanner else requestPermission() }, onConnected = { currentScreen.value = Screen.TeleoCercaChat }, onBack = { nearbyManager.stopDiscovery(); currentScreen.value = Screen.TeleoCercaEntry })
                Screen.Scanner -> QRScannerScreen(onScanResult = { r -> nearbyManager.discoveredEndpoints.find { it.name == r }?.let { nearbyManager.requestConnection(it) }; currentScreen.value = Screen.TeleoCercaJoin }, onBack = { currentScreen.value = Screen.TeleoCercaJoin })
                Screen.TeleoCercaChat -> NearbyChatScreen(nearbyManager, isListening, useEmojis.value, useEmotions.value, onSV = { startListening() }, onPV = { pauseListening() }, onB = { nearbyManager.disconnect(); currentScreen.value = Screen.Home })
            }
        } } }
    }

    private fun checkNearbyPermissions(): Boolean { val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) p.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.NEARBY_WIFI_DEVICES); return p.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED } }
    private fun requestPermission() { val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) p.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.NEARBY_WIFI_DEVICES); ActivityCompat.requestPermissions(this, p.toTypedArray(), 1001) }
    private fun initSpeechRecognizer() { speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this); recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) }; speechRecognizer.setRecognitionListener(object : RecognitionListener { override fun onReadyForSpeech(p: Bundle?) { isProcessingFinal.value = false }; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(r: Float) { lastRms.value = r; if (isListening.value) currentEmotion.value = determineEmotion(currentSentence.value) }; override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onError(e: Int) { if (isListening.value && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) Handler(Looper.getMainLooper()).postDelayed({ if (isListening.value) startListening() }, 500) else if (e != SpeechRecognizer.ERROR_CLIENT) isListening.value = false }; override fun onPartialResults(pr: Bundle?) { pr?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.uppercase()?.let { t -> currentEmotion.value = determineEmotion(t); processLiveText(t); if (currentScreen.value == Screen.TeleoCercaChat) nearbyManager.sendMessage(TeleoNearbyMessage(type = "partial", emotion = currentEmotion.value, currentWord = t.split("\\s+").lastOrNull() ?: "", currentSentence = t)) } }; override fun onResults(r: Bundle?) { isProcessingFinal.value = true; r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.uppercase()?.let { f -> if (f.isNotBlank()) { if (currentScreen.value == Screen.PalabraViva) { sentenceHistory.clear(); sentenceHistory.add(f) } else if (currentScreen.value == Screen.TeleoCercaChat) nearbyManager.sendMessage(TeleoNearbyMessage(type = "final", emotion = determineEmotion(f), message = f)) } }; currentSentence.value = ""; wordQueue.clear(); currentEmotion.value = "normal"; if (isListening.value) Handler(Looper.getMainLooper()).postDelayed({ if (isListening.value) startListening() }, 400) }; override fun onEvent(ev: Int, p: Bundle?) {} }) }
    private fun processLiveText(t: String) { if (isProcessingFinal.value || t == currentSentence.value) return; val old = currentSentence.value.trim().split("\\s+").filter { it.isNotBlank() }.size; val new = t.trim().split("\\s+").filter { it.isNotBlank() }.size; currentSentence.value = t; if (new > old) for (i in old until new) wordQueue.add(t.trim().split("\\s+").filter { it.isNotBlank() }[i]) }
    private fun determineEmotion(t: String): String { if (!useEmotions.value) return "normal"; val u = t.uppercase(); return when { u.contains("JAJA") || u.contains("HAHA") -> "laughing"; lastRms.value > 10f -> "shouting"; lastRms.value < 1.5f && lastRms.value > -2f -> "whispering"; else -> "normal" } }
    private fun startListening() { if (!checkNearbyPermissions()) { requestPermission(); return }; isListening.value = true; try { speechRecognizer.startListening(recognizerIntent) } catch (e: Exception) { isListening.value = false } }
    private fun pauseListening() { try { speechRecognizer.stopListening() } catch (_: Exception) {}; isListening.value = false }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, gr: IntArray) { super.onRequestPermissionsResult(rc, p, gr); if (rc == 1001) hasRecordPermission.value = gr.isNotEmpty() && gr.all { it == PackageManager.PERMISSION_GRANTED } }
    override fun onDestroy() { try { speechRecognizer.destroy() } catch (_: Exception) {}; nearbyManager.disconnect(); super.onDestroy() }
}

// --- COMPOSABLES ---

@Composable
fun HomeScreen(ue: MutableState<Boolean>, uem: MutableState<Boolean>, un: String, uc: Int, ua: Bitmap?, onNavigate: (Screen) -> Unit) {
    val act = LocalContext.current as? android.app.Activity
    Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CyberDark, Color(0xFF1A1F26)))).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        Row(modifier = Modifier.fillMaxSize().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            HomeCard(modifier = Modifier.weight(1f), t = "Palabra Viva", d = "Subtítulos en vivo.", i = Icons.Default.Mic, c = CyberMagenta) { onNavigate(Screen.PalabraViva) }
            HomeCard(modifier = Modifier.weight(1f), t = "Escribir", d = "Pantalla completa.", i = Icons.Default.Keyboard, c = CyberTeal) { onNavigate(Screen.EscribirYMostrar) }
            HomeCard(modifier = Modifier.weight(1f), t = "Teleo Cerca", d = "Conexión local.", i = Icons.Default.Wifi, c = CyberCyan) { onNavigate(Screen.TeleoCercaEntry) }
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
fun ProfileScreen(cn: String, cc: Int, ca: Bitmap?, onSave: (String, Int) -> Unit, onTakeAvatar: () -> Unit, onBack: () -> Unit) {
    var n by remember { mutableStateOf(cn) }; var c by remember { mutableStateOf(cc) }
    Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CyberDark, Color(0xFF1A1F26)))).padding(32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.Default.Home, null, tint = Color.Gray) }; Text("PERFIL", color = Color(c), fontSize = 24.sp, fontWeight = FontWeight.Black); Spacer(Modifier.size(48.dp)) }
        Spacer(Modifier.height(24.dp))
        Box(modifier = Modifier.size(140.dp).clickable { onTakeAvatar() }, contentAlignment = Alignment.Center) {
            if (ca != null) Image(ca.asImageBitmap(), null, Modifier.fillMaxSize().clip(CircleShape).border(3.dp, Color(c), CircleShape), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.fillMaxSize().background(Color(c).copy(alpha = 0.1f), CircleShape).border(2.dp, Color(c), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.AddAPhoto, null, modifier = Modifier.size(60.dp), tint = Color(c)) }
            Surface(modifier = Modifier.align(Alignment.BottomEnd).size(40.dp), shape = CircleShape, color = Color.DarkGray) { Icon(Icons.Default.Edit, null, modifier = Modifier.padding(8.dp), tint = Color.White) }
        }
        Spacer(Modifier.height(32.dp)); Text("NICKNAME", color = Color.Gray, fontSize = 12.sp); OutlinedTextField(n, { n = it }, modifier = Modifier.width(300.dp), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(c)))
        Spacer(Modifier.height(32.dp)); Text("COLOR", color = Color.Gray, fontSize = 12.sp)
        LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(110.dp).width(240.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ColorPalette) { color -> Box(modifier = Modifier.size(45.dp).background(color, CircleShape).border(if (c == color.value.toLong().toInt()) 3.dp else 0.dp, Color.White, CircleShape).clickable { c = color.value.toLong().toInt() }) }
        }
        Spacer(Modifier.height(48.dp)); Button(onClick = { if (n.isNotBlank()) onSave(n, c) }, modifier = Modifier.height(56.dp).width(200.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(c), contentColor = CyberDark)) { Icon(Icons.Default.Save, null); Text("GUARDAR", fontWeight = FontWeight.Black) }
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
            IconButton(onClick = { ic?.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() { override fun onCaptureSuccess(image: ImageProxy) { val b = image.planes[0].buffer; val bytes = ByteArray(b.remaining()); b.get(bytes); val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size); val m = Math.min(bmp.width, bmp.height); val sq = Bitmap.createBitmap(bmp, (bmp.width - m) / 2, (bmp.height - m) / 2, m, m); onCaptured(Bitmap.createScaledBitmap(sq, 256, 256, true)); image.close() } }) }, modifier = Modifier.size(80.dp).background(Color.White, CircleShape).border(4.dp, CyberCyan, CircleShape)) { Icon(Icons.Default.Camera, null, modifier = Modifier.size(40.dp), tint = Color.Black) }
        }
    }
}

@Composable
fun TeleoScreen(cs: MutableState<String>, wq: MutableList<String>, sh: List<String>, isl: MutableState<Boolean>, ipf: MutableState<Boolean>, hp: MutableState<Boolean>, ce: MutableState<String>, ue: Boolean, uem: Boolean, onStart: () -> Unit, onPause: () -> Unit, onClear: () -> Unit, onRequestPermission: () -> Unit, onBack: () -> Unit) {
    var aw by remember { mutableStateOf("") }; val fs = when { (cs.value.length + sh.sumOf { it.length }) < 50 -> 60.sp; (cs.value.length + sh.sumOf { it.length }) < 120 -> 45.sp; else -> 32.sp }
    LaunchedEffect(wq.size, ipf.value) { if (ipf.value) { aw = ""; wq.clear() } else if (wq.isNotEmpty()) { while (wq.isNotEmpty()) { aw = wq.removeAt(0); delay(350) }; delay(350); aw = "" } }
    Box(modifier = Modifier.fillMaxSize().background(CyberDark)) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(40.dp)); val ei = if (uem) { when(ce.value) { "shouting" -> "📢 "; "whispering" -> "🤫 "; "laughing" -> "😂 "; else -> "" } } else ""
            if (cs.value.isNotBlank()) Text(ei + (if (ue) TeleoUtils.decorate(cs.value) else cs.value), color = if (uem) { when(ce.value) { "shouting" -> Color.Red; "laughing" -> CyberYellow; "whispering" -> Color.Gray; else -> if (aw.isNotBlank()) CyberMagenta.copy(alpha = 0.25f) else CyberMagenta } } else CyberMagenta, fontSize = if (uem && ce.value == "shouting") 60.sp else 48.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            sh.forEach { Text(text = if (ue) TeleoUtils.decorate(it) else it, color = if (aw.isNotBlank()) CyberCyan.copy(alpha = 0.15f) else CyberCyan, fontSize = fs, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
        }
        if (aw.isNotBlank()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AnimatedContent(aw, transitionSpec = { (fadeIn(tween(150)) + scaleIn()) togetherWith fadeOut(tween(100)) }, label = "") { t -> Text(text = if (ue) TeleoUtils.decorate(t) else t, color = Color.White, fontSize = 160.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic) } }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, null, tint = Color.White) }; Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(modifier = Modifier.size(8.dp).background(if (isl.value) CyberTeal else Color.Red, CircleShape)); Text(text = if (isl.value) "ONLINE" else "OFFLINE", color = if (isl.value) CyberTeal else Color.Red, fontSize = 10.sp) } }
        Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.Transparent) { Row(modifier = Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) { if (!hp.value) TextButton(onClick = onRequestPermission) { Text("PEDIR PERMISO", color = Color.Red) } else { TextButton(onClick = onStart, enabled = !isl.value) { Text("HABLAR", color = if (!isl.value) CyberCyan else Color.Gray, fontSize = 20.sp, fontWeight = FontWeight.Black) }; TextButton(onClick = onPause, enabled = isl.value) { Text("PAUSA", color = if (isl.value) CyberMagenta else Color.Gray, fontSize = 20.sp, fontWeight = FontWeight.Black) }; TextButton(onClick = onClear) { Text("RESET", color = CyberYellow) } } } }
    }
}

@Composable
fun WriteAndShowScreen(ue: Boolean, onBackAction: () -> Unit) {
    var t by remember { mutableStateOf("") }; var s by remember { mutableStateOf(false) }; var f by remember { mutableStateOf(48f) }
    if (s) Box(modifier = Modifier.fillMaxSize().background(Color.Black)) { Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(text = if (ue) TeleoUtils.decorate(t) else t, color = Color.White, fontSize = f.sp, textAlign = TextAlign.Center) }; Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { IconButton(onClick = { f -= 8f }, modifier = Modifier.size(48.dp).border(1.dp, CyberCyan, CircleShape)) { Text("A-", color = CyberCyan) }; IconButton(onClick = { f += 8f }, modifier = Modifier.size(48.dp).border(1.dp, CyberCyan, CircleShape)) { Text("A+", color = CyberCyan) } }; Row(modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) { TextButton(onClick = { s = false }) { Text("EDITAR", color = CyberCyan) }; TextButton(onClick = { t = ""; s = false }) { Text("LIMPIAR", color = CyberYellow) } } }
    else Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBackAction, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, null, tint = Color.White) }; Text("ESCRIBIR", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.size(48.dp)) }; OutlinedTextField(value = t, onValueChange = { t = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("Mensaje...") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = CyberCyan)); Row(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { Button(onClick = { t = "" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("LIMPIAR") }; Button(onClick = { if (t.isNotBlank()) s = true }, modifier = Modifier.weight(1.5f), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark)) { Text("MOSTRAR", fontWeight = FontWeight.Bold) } } }
}

@Composable
fun TeleoNearbyEntryScreen(ue: MutableState<Boolean>, uem: MutableState<Boolean>, onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)) { Icon(Icons.Default.Home, null, tint = Color.White) }; Text("TELEO CERCA", color = CyberCyan, fontSize = 32.sp, fontWeight = FontWeight.Black); Surface(modifier = Modifier.padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.05f)) { Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { ConfigToggle("Emojis", ue.value) { ue.value = it } } } }; Spacer(modifier = Modifier.height(32.dp)); Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) { HomeCard(modifier = Modifier.weight(1f), t = "Crear Charla", d = "Modo Host.", i = Icons.Default.Add, c = CyberCyan) { onNavigate(Screen.TeleoCercaCreate) }; HomeCard(modifier = Modifier.weight(1f), t = "Unirme", d = "Buscar Host.", i = Icons.Default.Search, c = CyberTeal) { onNavigate(Screen.TeleoCercaJoin) } } }
}

@Composable
fun CreateNearbyChatScreen(manager: NearbyConnectionManager, onConnected: () -> Unit, onBack: () -> Unit) {
    val qrb = remember { TeleoUtils.generateQR(manager.myName) }
    LaunchedEffect(Unit) { manager.startAdvertising() }
    Column(modifier = Modifier.fillMaxSize().background(CyberDark).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { IconButton(onClick = onBack) { Icon(Icons.Default.Home, null, tint = Color.Gray) } ; Text("HOST: CREAR CHARLA", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.size(48.dp)) }
        Spacer(modifier = Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Surface(modifier = Modifier.size(240.dp).padding(8.dp), shape = RoundedCornerShape(16.dp), color = Color.White) { qrb?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxSize()) } }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("ID DISPOSITIVO", color = Color.Gray, fontSize = 14.sp); Text(text = manager.myName, color = CyberCyan, fontSize = 32.sp, fontWeight = FontWeight.Black); Spacer(modifier = Modifier.height(24.dp)); CircularProgressIndicator(color = CyberCyan); Spacer(modifier = Modifier.height(16.dp)); Text(text = "Esperando solicitudes...", color = Color.White.copy(alpha = 0.6f)) }
        }
        if (manager.pendingRequests.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Surface(modifier = Modifier.fillMaxWidth(0.8f), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "SOLICITUDES", color = CyberCyan, fontWeight = FontWeight.Bold)
                    manager.pendingRequests.forEach { p ->
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = p.name, color = Color.White); Row { TextButton(onClick = { manager.reject(p) }) { Text("RECHAZAR", color = Color.Red) }; Button(onClick = { manager.accept(p) }, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark)) { Text("ACEPTAR") } }
                        }
                    }
                }
            }
        }
        if (manager.connectedParticipants.isNotEmpty()) LaunchedEffect(Unit) { onConnected() }
    }
}

@Composable
fun JoinNearbyChatScreen(manager: NearbyConnectionManager, onScan: () -> Unit, onConnected: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { manager.startDiscovery() }
    if (manager.connectedParticipants.isNotEmpty()) LaunchedEffect(Unit) { onConnected() }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { IconButton(onClick = onBack) { Icon(Icons.Default.Home, null, tint = Color.Gray) }; Text("UNIRSE", color = CyberCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold); Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = CyberDark), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.QrCodeScanner, null); Spacer(modifier = Modifier.width(8.dp)); Text("ESCANEAR") } }
        Spacer(modifier = Modifier.height(16.dp))
        if (manager.discoveredEndpoints.isEmpty()) Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = CyberCyan.copy(alpha = 0.3f)); Text(text = "Buscando...", color = Color.Gray) } }
        else LazyColumn(modifier = Modifier.weight(1f)) { items(manager.discoveredEndpoints) { e -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { manager.requestConnection(e) }, colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))) { Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Text(e.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.weight(1f)); Text("SOLICITAR >", color = CyberCyan, fontSize = 14.sp) } } } }
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
                    if (manager.remoteWord.value.isNotBlank()) Text(text = ei + (if (useEmojis) TeleoUtils.decorate(manager.remoteWord.value) else manager.remoteWord.value), color = if (useEmotions) { when(manager.remoteEmotion.value) { "shouting" -> Color.Red; "laughing" -> CyberYellow; else -> Color.White } } else Color.White, fontSize = if (useEmotions && manager.remoteEmotion.value == "shouting") 60.sp else 48.sp, fontWeight = FontWeight.Black)
                    if (manager.remoteSentence.value.isNotBlank()) Text(text = if (useEmojis) TeleoUtils.decorate(manager.remoteSentence.value) else manager.remoteSentence.value, color = if (useEmotions && manager.remoteEmotion.value == "whispering") Color.Gray else CyberTeal, fontSize = 16.sp, textAlign = TextAlign.Center)
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
