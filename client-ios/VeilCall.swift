import Foundation
import WebRTC
import AVFoundation
import UIKit

// Звонки iOS: WebRTC (Opus, DTLS-SRTP), медиа только через наш TURN-TCP.
// Сигналинг — тем же зашифрованным конвертом через реле.

let VeilTurnServer = RTCIceServer(urlStrings: ["turn:188.127.237.61:3478?transport=tcp"],
                                  username: "veilturn", credential: "CvR9mQ2f")

final class VeilCall: NSObject, RTCPeerConnectionDelegate {
    var api: VeilApi
    var peerHash: String
    var pc: RTCPeerConnection?
    var factory: RTCPeerConnectionFactory
    var localVideoTrack: RTCVideoTrack?
    var remoteVideoTrack: RTCVideoTrack?
    var onStateChange: ((String) -> Void)?

    init(api: VeilApi, peerHash: String) {
        self.api = api
        self.peerHash = peerHash
        RTCInitializeSSL()
        let enc = RTCDefaultVideoEncoderFactory()
        let dec = RTCDefaultVideoDecoderFactory()
        self.factory = RTCPeerConnectionFactory(encoderFactory: enc, decoderFactory: dec)
        super.init()
    }

    func connect(config: RTCConfiguration) -> Bool {
        config.iceTransportPolicy = .relay           // TURN-only (§5.1)
        config.iceServers = [VeilTurnServer]
        config.sdpSemantics = .unifiedPlan
        guard let pc = factory.peerConnection(with: config, constraints: RTCMediaConstraints(
            mandatoryConstraints: nil, optionalConstraints: nil), delegate: self) else { return false }
        self.pc = pc
        let audioSource = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        pc.add(factory.audioTrack(with: audioSource, trackId: "a0"), streamIds: ["s0"])
        return true
    }

    func startVideo() -> RTCVideoTrack? {
        let videoSource = factory.videoSource()
        let capturer = RTCCameraVideoCapturer(delegate: videoSource)
        guard let device = RTCCameraVideoCapturer.captureDevices()
                .first(where: { $0.position == .front }) ?? RTCCameraVideoCapturer.captureDevices().first
        else { return nil }
        let format = RTCCameraVideoCapturer.supportedFormats(for: device)
            .last { f in
                f.videoSupportedFrameRateRanges.first.map {
                    CMTimeGetSeconds($0.minFrameDuration) <= 1.0 / 15.0
                } ?? false
            }
        guard let fmt = format else { return nil }
        let fps = fmt.videoSupportedFrameRateRanges.first.map { Float($0.maxFrameRate) } ?? 15.0
        capturer.startCapture(with: device, format: fmt, fps: Int(min(fps, 15)))
        let track = factory.videoTrack(with: videoSource, trackId: "v0")
        pc?.add(track, streamIds: ["s0"])
        localVideoTrack = track
        return track
    }

    func createOffer() {
        let c = RTCMediaConstraints(mandatoryConstraints: ["OfferToReceiveAudio": "true"],
                                    optionalConstraints: nil)
        pc?.offer(for: c) { [weak self] sdp, _ in
            guard let sdp = sdp else { return }
            self?.pc?.setLocalDescription(sdp) { [weak self] _ in
                Task { _ = await self?.api.send(to: self!.peerHash, payload: VeilEnvelope.callOffer(sdp.sdp)) }
                self?.onStateChange?("Вызов…")
            }
        }
    }

    func acceptOffer(_ sdpText: String) {
        let remote = RTCSessionDescription(type: .offer, sdp: sdpText)
        pc?.setRemoteDescription(remote) { [weak self] _ in
            let c = RTCMediaConstraints(mandatoryConstraints: ["OfferToReceiveAudio": "true"],
                                        optionalConstraints: nil)
            self?.pc?.answer(for: c) { [weak self] answer, _ in
                guard let answer = answer else { return }
                self?.pc?.setLocalDescription(answer) { [weak self] _ in
                    Task { _ = await self?.api.send(to: self!.peerHash, payload: VeilEnvelope.callAnswer(answer.sdp)) }
                    self?.onStateChange?("Соединение…")
                }
            }
        }
    }

    func acceptAnswer(_ sdpText: String) {
        let remote = RTCSessionDescription(type: .answer, sdp: sdpText)
        pc?.setRemoteDescription(remote) { [weak self] _ in self?.onStateChange?("Соединение…") }
    }

    func addIce(_ cand: String, mid: String) {
        let c = RTCIceCandidate(sdp: cand, sdpMLineIndex: 0, sdpMid: mid)
        pc?.add(c) { _ in }
    }

    func hangup(reason: String) {
        Task { _ = await api.send(to: peerHash, payload: VeilEnvelope.callEnd(reason)) }
        pc?.close()
    }

    // MARK: RTCPeerConnectionDelegate
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        let text: String
        switch newState {
        case .connected, .completed: text = "Соединено"
        case .failed: text = "Не удалось соединиться"
        case .disconnected: text = "Связь потеряна"
        default: text = "Соединение…"
        }
        onStateChange?(text)
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard let mid = candidate.sdpMid else { return }
        Task { _ = await api.send(to: peerHash, payload: VeilEnvelope.callIce(candidate.sdp, mid)) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        if let video = stream.videoTracks.first { remoteVideoTrack = video }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange state: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
