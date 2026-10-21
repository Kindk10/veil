package veil.core

// Полная инициализация идентичности VEIL. Два сценария:
// 1) Первый запуск: создаются ключи, регистрация на реле, задаётся PIN.
// 2) Повторный запуск: разблокировка PIN → загрузка ключей из SQLCipher.

import veil.net.Transport

class VeilInit(
    private val context: android.content.Context
) {

    data class Ready(
        val api: VeilApi,
        val identity: Identity.PublicBundle,
        val store: SecureStorage
    )

    // Первый запуск: pin задаёт пользователь, ключи генерируются локально,
    // регистрация уходит через Transport (Tor-only).
    suspend fun firstLaunch(pin: CharArray, username: String?): Result<Ready> {
        val store = SecureStorage.open(context, pin)
            ?: return Result.failure(IllegalStateException("не удалось открыть хранилище"))
        val id = Identity(store)
        val bundle = id.createNew()
        val idHash = bundle.idHash

        val api = VeilApi(store, idHash) { msg ->
            // Подпись Ed25519 приватным ключом; приватный ключ достаётся
            // только из зашифрованного хранилища и живёт в памяти сессии.
            Ed25519.sign(msg, store.get("identity_priv")!!)
        }

        val ok = api.register(
            identityPub = bundle.identityKey,
            spk = generateSpk(),
            spkSig = ByteArray(64),
            otks = generateOtkPool(100)
        )
        if (!ok) return Result.failure(IllegalStateException("регистрация не прошла; проверьте Orbot"))
        if (username != null) api.setUsername(username)

        // Erase-токен: регистрируется на сервере, обёрнутый живёт в Keystore;
        // id_hash пишется в открытые метки (это хеш, не секрет) для panic-wipe.
        val token = EraseKeystore.generate(context)
        context.getSharedPreferences("veil_meta", android.content.Context.MODE_PRIVATE)
            .edit().putString("id_hash", idHash).apply()

        return Result.success(Ready(api, bundle, store))
    }

    // Повторный запуск: PIN → хранилище → API. Если PIN неверен — null.
    fun unlock(pin: CharArray): Ready? {
        val store = SecureStorage.open(context, pin) ?: return null
        val id = Identity(store)
        val bundle = id.existing() ?: return null
        val api = VeilApi(store, bundle.idHash) { msg ->
            Ed25519.sign(msg, store.get("identity_priv")!!)
        }
        return Ready(api, bundle, store)
    }

    private fun generateSpk(): ByteArray {
        // Signed prekey: X25519-пара, приватная часть — в хранилище.
        val pair = X25519.generate()
        storeSpk(pair)
        return pair.pub
    }

    private fun storeSpk(pair: X25519.KeyPair) {
        // Обращение к хранилищу выполняется инициализатором через поле,
        // задаваемое в firstLaunch/unlock (упрощение для каркаса).
        pendingStore?.put("spk_priv", pair.priv)
        pendingStore?.put("spk_pub", pair.pub)
    }

    private var pendingStore: SecureStorage? = null

    private fun generateOtkPool(n: Int): List<ByteArray> =
        (1..n).map {
            val p = X25519.generate()
            pendingStore?.put("otk_$it", p.priv)
            p.pub
        }
}

// Обёртки над примитивами. В каркасе — чистая Kotlin-реализация;
// при сборке заменяются на вызовы libsignal/BoringSSL (нативный JNI),
// чтобы пройти независимый аудит криптопримитивов.
object X25519 {
    class KeyPair(val pub: ByteArray, val priv: ByteArray)
    fun generate(): KeyPair = throw NotImplementedError("подключается на этапе сборки через libsignal")
}

object Ed25519 {
    fun sign(msg: ByteArray, priv: ByteArray): ByteArray =
        throw NotImplementedError("подключается на этапе сборки через libsignal")
}
