package veil.core

// EraseKeystore: 32-байтовый токен стирания, обёрнутый ключом Android
// Keystore (аппаратное хранилище, извлекаемого ключа не существует).
// Работает даже при неверном PIN: 5 неудачных попыток → /erase по токену.

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

object EraseKeystore {

    private const val KS_ALIAS = "veil_erase_wrap"
    private const val PREFS = "veil_erase"

    fun context() {}

    fun generate(ctx: android.content.Context): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KS_ALIAS)) {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        saveWrapped(ctx, raw)
        return raw
    }

    fun get(ctx: android.content.Context): ByteArray? = try { unwrap(ctx) } catch (e: Exception) { null }

    private fun saveWrapped(ctx: android.content.Context, raw: ByteArray) {
        val key = keystoreKey()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val blob = c.iv + c.doFinal(raw)
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString("blob", blob.joinToString("") { "%02x".format(it) }).apply()
    }

    private fun unwrap(ctx: android.content.Context): ByteArray {
        val hex = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("blob", "") ?: return ByteArray(0)
        val blob = ByteArray(hex.length / 2) {
            ((Character.digit(hex[2 * it], 16) shl 4) or Character.digit(hex[2 * it + 1], 16)).toByte()
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getEntry(KS_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
}
