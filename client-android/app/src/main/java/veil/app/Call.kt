package veil.app

// Звонки: WebRTC (Opus, DTLS-SRTP) поверх реле.
// Сигналинг (offer/answer/ICE) идёт тем же зашифрованным конвертом §4 —
// сервер видит только непрозрачный payload, SRTP-ключи у сторон.

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.*
import veil.core.Envelope

// Одиночный глобальный_PEER для входящих ICE.
object CallState {
    @Volatile var activePeer: String? = null
    @Volatile var answerAction: ((String) -> Unit)? = null // колбэк из чата/списка при входящем
}

@Composable
fun CallScreen(peer: String, onEnd: () -> Unit) {
    val t = VeilThemes.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("Инициализация…") }
    var granted by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted = it }

    var factory by remember { mutableStateOf<PeerConnectionFactory?>(null) }
    var pc by remember { mutableStateOf<PeerConnection?>(null) }
    var speakerOn by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            pc?.close()
            factory?.dispose()
            CallState.activePeer = null
        }
    }

    if (!granted) {
        Column(Modifier.fillMaxSize().background(t.bg), verticalArrangement = Arrangement.Center) {
            Text("Для звонка нужен микрофон", color = t.text)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Разрешить") }
        }
        return
    }

    LaunchedEffect(peer) {
        CallState.activePeer = peer
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx)
                    .setEnableInternalTracer(false).createInitializationOptions())
            val f = PeerConnectionFactory.builder().createPeerConnectionFactory()
            factory = f
            val cfg = PeerConnection.RTCConfiguration(listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()))
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            val obs = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, sdp)
                    // Отправляем offer адресату через реле.
                    scope.launch {
                        VeilApp.api()?.send(VeilApp.resolveId(peer),
                            veil.core.CallSignal.offer(sdp.description).toByteArray())
                    }
                    state = "Вызов…"
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) { state = "Ошибка SDP" }
                override fun onSetFailure(p0: String?) { state = "Ошибка SDP" }
            }
            val conn = f.createPeerConnection(cfg, object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {
                    scope.launch { VeilApp.api()?.send(VeilApp.resolveId(peer),
                        veil.core.CallSignal.ice(c.sdp, c.sdpMid ?: "").toByteArray()) }
                }
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    state = when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> "Соединено"
                        PeerConnection.PeerConnectionState.FAILED -> "Не удалось соединиться"
                        PeerConnection.PeerConnectionState.DISCONNECTED -> "Связь потеряна"
                        else -> "Соединение…"
                    }
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onTrack(p0: RtpTransceiver?) {}
            })!!
            pc = conn
            val audio = f.createAudioSource(MediaConstraints())
            conn.addTrack(f.createAudioTrack("a0", audio), listOf("stream0"))
            conn.createOffer(obs, MediaConstraints())
        }
        // Ответ и ICE от собеседника приходят через polling VeilApi — хук ниже.
        CallState.answerAction = { sdp ->
            pc?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onSetSuccess() { state = "Соединение…" }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) { state = "Ошибка ответа" }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }
    }

    Column(Modifier.fillMaxSize().background(t.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Avatar(peer)
        Text(peer, color = t.text, fontSize = 24.sp, modifier = Modifier.padding(top = 12.dp))
        Text(state, color = t.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Text("DTLS-SRTP · сигналинг через реле", color = t.textDim, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(40.dp))
        Row {
            Button(onClick = { speakerOn = !speakerOn; state = if (speakerOn) "Динамик" else "В трубке" },
                shape = CircleShape) { Text(if (speakerOn) "🔊" else "📞") }
            Spacer(Modifier.width(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        VeilApp.api()?.send(VeilApp.resolveId(peer),
                            veil.core.CallSignal.end("hangup").toByteArray())
                    }
                    pc?.close(); onEnd()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2574A)),
                shape = CircleShape) {
                Text("Завершить")
            }
        }
    }
}
