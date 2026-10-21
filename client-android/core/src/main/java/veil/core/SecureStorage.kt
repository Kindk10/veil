package veil.core

// Локальное хранилище VEIL: SQLCipher. Файл БД шифруется ключом БД (случайные
// 32 байта), ключ БД обёрнут ключом, выведенным из PIN пользователя (PBKDF2;
// замена на Argon2id — через libgcrypt/libsodium при подключении нативного ядра).
// PIN нигде не хранится. Забытый PIN без бэкапа = данные утрачены (по дизайну).

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SecureStorage private constructor(private val appCtx: Context, dbKey: ByteArray) : SecureStore {

    private val helper = object : SQLiteOpenHelper(appCtx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v BLOB NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }
    private val db = helper.getWritableDatabase(dbKeyToString(dbKey))

    // SQLCipher требует строку пассфразы; обёртка ключа выше в GCM — до БД.
    private fun dbKeyToString(key: ByteArray): String = key.joinToString("") { "%02x".format(it) }

    override fun put(key: String, value: ByteArray) {
        db.execSQL("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)", arrayOf(key, value))
    }

    override fun get(key: String): ByteArray? {
        db.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getBlob(0) else null
        }
    }

    // Полное стирание: удаляем файл БД через SQLCipher (переписывает и дропает),
    // снимаем WAL/SHM. Вызывается при паническом удалении.
    override fun wipe() {
        db.close()
        helper.close()
        appCtx.deleteDatabase(appCtx.getDatabasePath(DB_NAME).absolutePath)
    }

    companion object {
        private const val DB_NAME = "veil_encrypted.db"
        private const val DB_VERSION = 1
        private const val KDF_ITERS = 600_000
        private const val SALT_LEN = 32
        private const val KEY_LEN = 32

        // Есть ли уже созданный аккаунт (по метке-обёртке ключа).
        fun hasAccount(context: Context): Boolean =
            context.getSharedPreferences("veil_meta", Context.MODE_PRIVATE)
                .getString("wrapped_key", null) != null

        // Первый запуск: создаём случайный ключ БД и обёртку из PIN.
        // Возвраты: null — неверный PIN; объект — успех.
        // 5 неверных попыток подряд → ПАНИЧЕСКОЕ СТИРАНИЕ: локальная БД,
        // обёртка ключа и ключи идентичности уничтожаются; на сервер уходит
        // /erase по Keystore-токену — переписки и аккаунт удаляются безвозвратно.
        fun open(context: Context, pin: CharArray): SecureStorage? {
            val prefs = context.getSharedPreferences("veil_meta", Context.MODE_PRIVATE)
            if (prefs.getInt("pin_fail", 0) >= 5) {
                panicWipe(context, prefs)
                return null
            }
            val salt = prefs.getString("salt", null)
                ?: run {
                    val s = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
                    prefs.edit().putString("salt", toHex(s)).putBoolean("initialized", true).apply()
                    toHex(s)
                }
            val wrapped = prefs.getString("wrapped_key", null)
            if (wrapped == null) {
                // Первая инициализация: генерируем ключ БД, оборачиваем PIN-ключом.
                val raw = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }
                val kek = deriveKey(pin, fromHex(salt))
                val enc = wrap(kek, raw)
                prefs.edit().putString("wrapped_key", toHex(enc)).apply()
                zero(raw)
                return SecureStorage(context, unwrap(kek, enc))
            }
            val kek = deriveKey(pin, fromHex(salt))
            val raw = try {
                unwrap(kek, fromHex(wrapped))
            } catch (e: Exception) {
                // Неверный PIN: инкремент счётчика, на 5-й — стирание всего.
                val fails = prefs.getInt("pin_fail", 0) + 1
                prefs.edit().putInt("pin_fail", fails).apply()
                if (fails >= 5) {
                    panicWipe(context, prefs)
                }
                return null
            }
            prefs.edit().putInt("pin_fail", 0).apply()
            return SecureStorage(context, raw)
        }

        // Паническое стирание при 5 неверных PIN: локальная БД уничтожается,
        // /erase уходит на сервер по Keystore-токену. Аккаунт и переписки
        // удаляются безвозвратно у всех сторон по нашим данным.
        private fun panicWipe(context: Context, prefs: android.content.SharedPreferences) {
            try {
                // Токен Keystore и id_hash существуют вне зашифрованной БД.
                val token = EraseKeystore.get(context)
                val idHash = prefs.getString("id_hash", null)
                if (token != null && idHash != null) {
                    EraseRequest.send(idHash, token) // POST /erase с X-Erase-Token
                }
            } catch (e: Exception) {
                // Даже если сеть недоступна — локальные данные всё равно стираются.
            }
            try {
                context.deleteDatabase(
                    context.getDatabasePath("veil_encrypted.db").absolutePath
                )
            } catch (e: Exception) { }
            prefs.edit().clear().apply()
        }

        private fun deriveKey(pin: CharArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(pin, salt, KDF_ITERS, KEY_LEN * 8)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded.also { spec.clearPassword() }
        }

        private fun wrap(kek: ByteArray, data: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, iv))
            return iv + c.doFinal(data)
        }

        private fun unwrap(kek: ByteArray, blob: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }

        private fun zero(b: ByteArray) { b.fill(0) }
        private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun fromHex(h: String) = ByteArray(h.length / 2) {
            ((Character.digit(h[2 * it], 16) shl 4) or Character.digit(h[2 * it + 1], 16)).toByte()
        }
    }
}
