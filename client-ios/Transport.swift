// VEIL iOS: транспортный слой. TLS с пиннингом отпечатка реле,
// запросы подписываются Ed25519 (CryptoKit). Перенос Kotlin-логики 1:1.

import Foundation
import CryptoKit

enum TransportError: Error { case pinMismatch, badResponse }

final class Transport {
    static let shared = Transport()

    var baseUrl = "https://188.127.237.61"
    // Отпечаток реле (SHA-256 сертификата) — жёсткий пиннинг.
    var relayFingerprint = "49a78d1e58cf2d0043535be3de3f18a5a314657048939be8f81db6967879302f"

    private var cachedTrust: SecTrust?

    func request(method: String, path: String, body: Data?, idHash: String? = nil, sig: Data? = nil) async throws -> (Int, Data) {
        let url = URL(string: baseUrl + path)!
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 30
        if let body { req.httpBody = body; req.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if let sig {
            req.setValue(idHash, forHTTPHeaderField: "X-ID-Hash")
            req.setValue(sig.base64EncodedString(), forHTTPHeaderField: "X-Auth-Sig")
        }
        let (data, resp) = try await pinnedSession().data(for: req)
        return ((resp as? HTTPURLResponse)?.statusCode ?? 0, data)
    }

    private func pinnedSession() -> URLSession {
        let delegate = PinDelegate(fp: relayFingerprint)
        let conf = URLSessionConfiguration.default
        conf.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: conf, delegate: delegate, delegateQueue: nil)
    }
}

final class PinDelegate: NSObject, URLSessionDelegate {
    let fp: String
    init(fp: String) { self.fp = fp }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard let trust = challenge.protectionSpace.serverTrust,
              let cert = SecTrustGetCertificateAtIndex(trust, 0),
              let der = SecCertificateCopyData(cert) as Data?
        else { completionHandler(.cancelAuthenticationChallenge, nil); return }
        let digest = SHA256.hash(data: der)
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        if hex == fp { completionHandler(.useCredential, URLCredential(trust: trust)) }
        else { completionHandler(.cancelAuthenticationChallenge, nil) } // подмена → разрыв
    }
}
