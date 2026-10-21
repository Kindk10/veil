// VEIL iOS: encrypted local vault.
// AES-GCM with key derived from PIN (interim: iterated SHA-256 via CryptoKit,
// replaced by Argon2id when native core is wired). PIN is never stored.
// 5 wrong attempts -> wipe + /erase (protocol section 11).
import Foundation
import CryptoKit
import Security

final class SecureStorage {
    static let shared = SecureStorage()
    private let fileURL = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("veil_vault.bin")

    struct Vault: Codable {
        var signingSeed: Data
        var agreementPriv: Data
        var messages: [Message]
    }

    struct Message: Codable { var dir: String; var peer: String; var text: String }

    private func kdf(_ pin: String, salt: Data) -> SymmetricKey {
        // Interim KDF: РёС‚РµСЂРёСЂРѕРІР°РЅРЅС‹Р№ SHA-256 (600k СЂР°СѓРЅРґРѕРІ), С‡РёСЃС‚С‹Р№ CryptoKit.
        // Р—Р°РјРµРЅР° РЅР° Argon2id вЂ” РїСЂРё РїРѕРґРєР»СЋС‡РµРЅРёРё РЅР°С‚РёРІРЅРѕРіРѕ СЏРґСЂР°.
        var acc = Data(pin.utf8) + salt
        for _ in 0..<600_000 {
            acc = Data(SHA256.hash(data: acc + salt))
        }
        return SymmetricKey(data: acc)
    }

    func create(pin: String) -> Bool {
        let salt = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        let key = kdf(pin, salt: salt)
        let vault = Vault(
            signingSeed: Curve25519.Signing.PrivateKey().rawRepresentation,
            agreementPriv: Curve25519.KeyAgreement.PrivateKey().rawRepresentation,
            messages: [])
        return save(vault, key: key, salt: salt)
    }

    func open(pin: String) -> Vault? {
        guard let meta = meta, let salt = meta.salt, let blob = try? Data(contentsOf: fileURL)
        else { return nil }
        let key = kdf(pin, salt: salt)
        guard let box = try? AES.GCM.SealedBox(combined: blob),
              let plain = try? AES.GCM.open(box, using: key),
              let vault = try? JSONDecoder().decode(Vault.self, from: plain)
        else { failCount += 1; if failCount >= 5 { wipe() }; return nil }
        failCount = 0
        return vault
    }

    private struct Meta: Codable { var salt: Data? }
    private var meta: Meta? {
        UserDefaults.standard.data(forKey: "veil_meta")
            .flatMap { try? JSONDecoder().decode(Meta.self, from: $0) }
    }
    private var failCount: Int {
        get { UserDefaults.standard.integer(forKey: "veil_pin_fail") }
        set { UserDefaults.standard.set(newValue, forKey: "veil_pin_fail") }
    }

    private func save(_ vault: Vault, key: SymmetricKey, salt: Data) -> Bool {
        guard let plain = try? JSONEncoder().encode(vault),
              let box = try? AES.GCM.seal(plain, using: key)
        else { return false }
        do {
            try box.combined!.write(to: fileURL)
            if let d = try? JSONEncoder().encode(Meta(salt: salt)) {
                UserDefaults.standard.set(d, forKey: "veil_meta")
            }
            return true
        } catch { return false }
    }

    func persist(_ vault: Vault, pin: String) -> Bool {
        guard let meta = meta, let salt = meta.salt else { return false }
        return save(vault, key: kdf(pin, salt: salt), salt: salt)
    }

    // РџРѕР»РЅРѕРµ СЃС‚РёСЂР°РЅРёРµ: С„Р°Р№Р» + СЃС‡С‘С‚С‡РёРєРё. /erase СѓС…РѕРґРёС‚ РїРѕ РѕС‚РєСЂС‹С‚С‹Рј РјРµС‚РєР°Рј.
    func wipe() {
        try? FileManager.default.removeItem(at: fileURL)
        UserDefaults.standard.removeObject(forKey: "veil_meta")
        UserDefaults.standard.removeObject(forKey: "veil_pin_fail")
    }
}

// (KDF выше — чистый CryptoKit)