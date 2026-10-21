package veil.app

// Звонки: WebRTC (Opus, DTLS-SRTP). Сигналинг (offer/answer/ICE) идёт тем же
// зашифрованным конвертом §4 — сервер реле видит только непрозрачный payload.
// Медиа всегда через наш TURN (turn:188.127.237.61:3478?transport=tcp) —
// собеседник никогда не видит IP; UDP-кандидаты отключены (TURN-only, §5.1).

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
import androidx.compose.ui.viewinterop.AndroidView
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
fun CallScreen(peer: String, video: Boolean = false, onEnd: () -> Unit) {
    val t = VeilThemes.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("Инициализация…") }
    var granted by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        (!video || ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = (r[Manifest.permission.RECORD_AUDIO] == true) &&
            (!video || r[Manifest.permission.CAMERA] == true) }
    var factory by remember { mutableStateOf<PeerConnectionFactory?>(null) }
    var pc by remember { mutableStateOf<PeerConnection?>(null) }
    var videoCapturer by remember { mutableStateOf<VideoCapturer?>(null) }
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var speakerOn by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            videoCapturer?.stopCapture(); videoCapturer?.dispose()
            pc?.close()
            factory?.dispose()
            localRenderer?.release(); remoteRenderer?.release()
            CallState.activePeer = null
        }
    }

    if (!granted) {
        Column(Modifier.fillMaxSize().background(t.bg), verticalArrangement = Arrangement.Center) {
            Text("Для звонка нужен микрофон", color = t.text)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA))
            }) { Text("Разрешить") }
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
                // Только наш TURN по TCP: прямых соединений не существует (§5.1)
                PeerConnection.IceServer.builder("turn:188.127.237.61:3478?transport=tcp")
                    .setUsername("veilturn").setPassword("CvR9mQ2f").createIceServer()))
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Запрещаем не-релейных кандидатов: только через TURN
            cfg.iceTransportsType = PeerConnection.IceTransportsType.RELAY
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
                override fun onTrack(tr: RtpTransceiver?) {
                    val track = tr?.receiver?.track() ?: return
                    if (track is VideoTrack && remoteRenderer != null) track.addSink(remoteRenderer)
                }
            })!!
            pc = conn
            val audio = f.createAudioSource(MediaConstraints())
            conn.addTrack(f.createAudioTrack("a0", audio), listOf("stream0"))
            if (video) {
                val egl = EglBase.create()
                val lr = SurfaceViewRenderer(ctx); lr.init(egl.eglBaseContext, null)
                val rr = SurfaceViewRenderer(ctx); rr.init(egl.eglBaseContext, null)
                localRenderer = lr; remoteRenderer = rr
                val enumerator = Camera2Enumerator(ctx)
                val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull()
                name?.let {
                    val cap = enumerator.createCapturer(it, null)
                    videoCapturer = cap
                    val src = f.createVideoSource(false)
                    cap.initialize(SurfaceTextureHelper.create("vcap", egl.eglBaseContext), ctx, src.capturerObserver)
                    cap.startCapture(640, 480, 15)
                    val vt = f.createVideoTrack("v0", src)
                    vt.addSink(lr)
                    conn.addTrack(vt, listOf("stream0"))
                }
            }
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
        if (video) {
            // Удалённый поток на весь экран, своё — маленьким окошком сверху
            Box(Modifier.fillMaxSize()) {
                remoteRenderer?.let { r ->
                    AndroidView(factory = { r }, modifier = Modifier.fillMaxSize())
                }
                localRenderer?.let { r ->
                    AndroidView(factory = { r }, modifier = Modifier
                        .align(Alignment.TopEnd).padding(12.dp).size(width = 110.dp, height = 160.dp))
                }
                Column(Modifier.align(Alignment.Center)) {
                    Text(state, color = t.accent, fontSize = 16.sp)
                    Text(peer, color = t.text, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        } else {
            Avatar(peer)
            Text(peer, color = t.text, fontSize = 24.sp, modifier = Modifier.padding(top = 12.dp))
            Text(state, color = t.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Text("DTLS-SRTP · медиа через наш TURN", color = t.textDim, fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(30.dp))
        Row {
            Button(onClick = { speakerOn = !speakerOn },
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
