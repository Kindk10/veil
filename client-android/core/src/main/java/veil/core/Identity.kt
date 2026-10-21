package veil.core

// Идентичность пользователя. Аккаунт = случайная ключевая пара,
// создаваемая локально при первом запуске. Никаких телефонов, email, имён.

import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.util.KeyHelper
import java.security.MessageDigest
import java.security.SecureRandom

class Identity(val db: SecureStore) {

    data class PublicBundle(
        val idHash: String,      // hex(SHA-256(identityKey)) — публичный ID
        val identityKey: ByteArray,
        val signedPreKey: ByteArray,
        val signedPreKeySig: ByteArray,
        val oneTimePreKeys: List<ByteArray>
    )

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    // ID = Base58(SHA-256(public identity key))[:16] — генерируется из ключа,
    // не выдаётся сервером. Сервер хранит только хеш.
    fun generateIdHash(identityKey: ByteArray): String {
        val h = sha256(identityKey)
        return h.joinToString("") { "%02x".format(it) }.take(32)
    }

    // Полная генерация при первом запуске. Вызывается ровно один раз;
    // приватные части пишутся ТОЛЬКО в зашифрованный SecureStore (SQLCipher).
    fun createNew(): PublicBundle {
        val identityPair: IdentityKeyPair = KeyHelper.generateIdentityKeyPair()
        val registrationId = KeyHelper.generateRegistrationId(false)
        val signedPreKeyId = 1
        val oneTimeIds = (2..100).toList()

        val bundle = PublicBundle(
            idHash = generateIdHash(identityPair.publicKey.serialize()),
            identityKey = identityPair.publicKey.serialize(),
            signedPreKey = ByteArray(0), // заполняется на этапе сборки протокола
            signedPreKeySig = ByteArray(0),
            oneTimePreKeys = emptyList()
        )

        db.put("identity_priv", identityPair.privateKey.serialize())
        db.put("identity_pub", identityPair.publicKey.serialize())
        db.put("reg_id", registrationId.toString().toByteArray())
        db.put("bundle_idhash", bundle.idHash.toByteArray())
        return bundle
    }

    fun existing(): PublicBundle? {
        val pub = db.get("identity_pub") ?: return null
        val idHash = db.get("bundle_idhash")?.decodeToString() ?: return null
        return PublicBundle(idHash, pub, ByteArray(0), ByteArray(0), emptyList())
    }
}

// Абстракция зашифрованного локального хранилища (SQLCipher под капотом).
// Ключ БД шифруется ключом из PIN пользователя (Argon2id) и живёт только в памяти.
interface SecureStore {
    fun put(key: String, value: ByteArray)
    fun get(key: String): ByteArray?
    fun wipe() // перезапись + удаление файла БД
}
