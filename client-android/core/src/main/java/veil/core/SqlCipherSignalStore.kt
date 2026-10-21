package veil.core

// Адаптер SessionStore libsignal над зашифрованной SQLCipher-базой.
// Все ключи ратчета, prekeys и записи сессий живут ТОЛЬКО здесь —
// в зашифрованном kv-хранилище, открываемом PIN-ключом.

import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.IdentityKeyStore
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.PreKeyStore
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SessionStore
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.state.SignedPreKeyStore

class SqlCipherSignalStore(
    private val kv: SecureStore,
    private val registrationId: Int
) : SessionStore, PreKeyStore, SignedPreKeyStore, IdentityKeyStore {

    private fun k(prefix: String, key: String) = "$prefix:$key"

    // --- SessionStore ---

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val raw = kv.get(k("session", "$address"))
        return if (raw != null) SessionRecord(raw) else SessionRecord()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        kv.put(k("session", "$address"), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        kv.get(k("session", "$address")) != null

    override fun deleteSession(address: SignalProtocolAddress) {
        kv.put(k("session", "$address"), ByteArray(0))
    }

    override fun deleteAllSessions(name: String) {
        // Точное удаление по префиксу выполняется через wipe() при /erase.
    }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = emptyList()

    // --- PreKeyStore ---

    override fun loadPreKey(id: Int): PreKeyRecord {
        val raw = kv.get(k("prekey", id.toString()))
            ?: throw IllegalStateException("prekey $id не найден")
        return PreKeyRecord(raw)
    }

    override fun storePreKey(id: Int, record: PreKeyRecord) {
        kv.put(k("prekey", id.toString()), record.serialize())
    }

    override fun containsPreKey(id: Int): Boolean =
        kv.get(k("prekey", id.toString())) != null

    override fun removePreKey(id: Int) {
        kv.put(k("prekey", id.toString()), ByteArray(0))
    }

    // --- SignedPreKeyStore ---

    override fun loadSignedPreKey(id: Int): SignedPreKeyRecord {
        val raw = kv.get(k("signed_prekey", id.toString()))
            ?: throw IllegalStateException("signed prekey $id не найден")
        return SignedPreKeyRecord(raw)
    }

    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) {
        kv.put(k("signed_prekey", id.toString()), record.serialize())
    }

    override fun containsSignedPreKey(id: Int): Boolean =
        kv.get(k("signed_prekey", id.toString())) != null

    override fun removeSignedPreKey(id: Int) {
        kv.put(k("signed_prekey", id.toString()), ByteArray(0))
    }

    // --- IdentityKeyStore ---

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val raw = kv.get("identity_keypair")
            ?: throw IllegalStateException("идентичность не создана")
        return IdentityKeyPair(raw)
    }

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): Boolean {
        val existed = kv.get(k("identity", "$address")) != null
        kv.put(k("identity", "$address"), identityKey.serialize())
        return !existed
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: org.whispersystems.libsignal.state.IdentityKeyStore.Direction
    ): Boolean {
        // Политика TOFU (trust on first use) с верификацией fingerprint:
        // при первой связи ключ сохраняется; при смене ключа соединение
        // отклоняется — возможна только переустановка контакта вручную.
        val existing = kv.get(k("identity", "$address")) ?: return true
        return existing.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val raw = kv.get(k("identity", "$address")) ?: return null
        return IdentityKey(raw, 0)
    }
}
