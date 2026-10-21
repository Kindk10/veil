import SwiftUI
import WebRTC

// Экран звонка iOS: аудио + видео (включаемая кнопка), TURN-only.

struct CallView: View {
    var api: VeilApi
    var peer: String
    var answerSdp: String?
    var onEnd: () -> Void

    @State private var call: VeilCall?
    @State private var state = "Инициализация…"
    @State private var videoOn = false
    @State private var localView = RTCMTLVideoView(frame: .zero)
    @State private var remoteView = RTCMTLVideoView(frame: .zero)

    var body: some View {
        VStack(spacing: 16) {
            if videoOn {
                ZStack(alignment: .topTrailing) {
                    Group { remoteView }.frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color.black)
                    Group { localView }.frame(width: 110, height: 160).padding(12)
                }
            } else {
                Spacer()
                Text(peer).font(.largeTitle).foregroundColor(.white)
            }
            Text(state).foregroundColor(VeilGreen)
            Text("DTLS-SRTP · медиа через наш TURN").font(.caption).foregroundColor(.gray)
            Spacer()
            HStack(spacing: 24) {
                Button {
                    videoOn.toggle()
                    if videoOn { attachVideo() }
                } label: { Text(videoOn ? "📹" : "🎙").font(.title2) }
                Button(role: .destructive) {
                    call?.hangup(reason: "hangup")
                    onEnd()
                } label: { Text("Завершить").foregroundColor(.red) }
            }
        }
        .padding()
        .onAppear {
            try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .voiceChat)
            try? AVAudioSession.sharedInstance().setActive(true)
            let call = VeilCall(api: api, peerHash: peer)
            self.call = call
            call.onStateChange = { st in DispatchQueue.main.async { state = st } }
            var cfg = RTCConfiguration()
            if call.connect(config: cfg) {
                if let answer = answerSdp {
                    call.acceptOffer(answer)
                } else {
                    call.createOffer()
                }
            } else {
                state = "Не удалось создать сессию"
            }
        }
        .onDisappear { call?.pc?.close() }
    }

    private func attachVideo() {
        guard let call = call else { return }
        if let track = call.startVideo() {
            track.add(localView)
        }
        if let remote = call.remoteVideoTrack {
            remote.add(remoteView)
        }
    }
}
