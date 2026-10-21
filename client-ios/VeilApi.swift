// VEIL iOS: идентичность и API-клиент. Аккаунт = ключи на устройстве,
// сервер хранит только хеш. CryptoKit: Ed25519 (подписи), X25519 (согласование).

import Foundation
import CryptoKit

final class VeilIdentity {
    let signing: Curve25519.Signing.PrivateKey       // Ed25519
    let agreement: Curve25519.KeyAgreement.PrivateKey // X25519
    let idHash: String

    init() {
        signing = Curve25519.Signing.PrivateKey()
        agreement = Curve25519.KeyAgreement.PrivateKey()
        idHash = SHA256.hash(data: agreement.publicKey.rawRepresentation)
            .map { String(format: "%02x", $0) }.joined()
    }

    func restore(signingSeed: Data, agreementPriv: Data) throws {
        // восстановление из зашифрованного хранилища
        _ = try Curve25519.Signing.PrivateKey(rawRepresentation: signingSeed)
        _ = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: agreementPriv)
    }

    func b64(_ d: Data) -> String { d.base64EncodedString() }

    func registerBody() throws -> [String: String] {
        let bodyHash = SHA256.hash(data: Data())
        let msg = Data("POST/register".utf8) + Data(bodyHash)
        let sig = try signing.signature(for: msg)
        return [
            "id_hash": idHash,
            "pubkey": b64(agreement.publicKey.rawRepresentation),
            "spk": b64(Data(repeating: 0x53, count: 32)),
            "spk_sig": b64(Data(repeating: 0x53, count: 64)),
            "otks": "[]",
            "erase_pub": b64(Data(repeating: 0x45, count: 32)),
            "erase_token": Data(repeating: 0x54, count: 32).map { String(format: "%02x", $0) }.joined()
        ]
    }
}

final class VeilApi {
    let identity: VeilIdentity
    let transport: Transport

    init(identity: VeilIdentity, transport: Transport = .shared) {
        self.identity = identity
        self.transport = transport
    }

    func register() async -> Bool {
        guard let body = try? identity.registerBody(),
              let data = try? JSONSerialization.data(withJSONObject: body)
        else { return false }
        let (code, _) = (try? await transport.request(
            method: "POST", path: "/register", body: data)) ?? (0, Data())
        return code == 201
    }

    func setUsername(_ username: String) async -> Bool {
        let body = try! JSONSerialization.data(withJSONObject: ["username": username])
        let (code, _) = (try? await signed(method: "POST", path: "/username", body: body)) ?? (0, Data())
        return code == 204
    }

    func lookup(_ username: String) async -> String? {
        guard let (code, data) = try? await signed(
            method: "GET", path: "/lookup/\(username)", body: nil),
              code == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return json["id_hash"] as? String
    }

    func send(to idHash: String, payload: Data) async -> Bool {
        let padded = Self.pad(payload)
        let env: [String: String] = [
            "to": idHash,
            "from": identity.idHash,
            "payload": padded.base64EncodedString()
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: env),
              let (code, _) = try? await signed(method: "POST", path: "/send", body: body)
        else { return false }
        return code == 202
    }

    // Паддинг по сетке §4: 256…1М, добор случайными байтами.
    static func pad(_ data: Data) -> Data {
        let sizes = [256, 1024, 4096, 16384, 65536, 262144, 1048576]
        let target = sizes.first { $0 >= data.count } ?? data.count
        var out = data
        var noise = Data(count: target - data.count)
        _ = noise.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, target - data.count, $0.baseAddress!) }
        out.append(noise)
        return out
    }

    func erase() async -> Bool {
        let (code, _) = (try? await signed(method: "POST", path: "/erase", body: nil)) ?? (0, Data())
        return code == 204
    }

    // Очередь конвертов: [(from, payload)] — /fetch, 200 → массив {from, payload}.
    func fetchAll() async throws -> [(String, Data)] {
        let (code, data) = try await signed(method: "GET", path: "/fetch", body: nil)
        guard code == 200,
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }
        return arr.compactMap { o in
            guard let from = o["from"] as? String,
                  let b64 = o["payload"] as? String,
                  let payload = Data(base64Encoded: b64) else { return nil }
            return (from, payload)
        }
    }

    private func signed(method: String, path: String, body: Data?) async throws -> (Int, Data) {
        let bh = SHA256.hash(data: body ?? Data())
        let msg = Data(method.utf8) + Data(path.utf8) + Data(bh)
        let sig = try identity.signing.signature(for: msg)
        return try await transport.request(
            method: method, path: path, body: body,
            idHash: identity.idHash,
            seed: nil, sig: sig)
    }
}

extension Transport {
    func request(method: String, path: String, body: Data?, idHash: String, seed: CryptoKit.SymmetricKey?, sig: Data? = nil) async throws -> (Int, Data) {
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
}
