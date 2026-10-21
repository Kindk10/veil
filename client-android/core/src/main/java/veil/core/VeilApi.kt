package veil.core

// Клиентский API VEIL. Все вызовы идут через Transport (Tor-only).
// Каждый запрос подписан Ed25519 приватным ключом идентичности.

import org.json.JSONArray
import org.json.JSONObject
import veil.net.Transport
import java.security.MessageDigest
import java.util.Base64

class VeilApi(
    private val store: SecureStore,
    private val idHash: String,
    private val signer: (ByteArray) -> ByteArray
) {

    fun register(identityPub: ByteArray, spk: ByteArray, spkSig: ByteArray, otks: List<ByteArray>): Boolean {
        val body = JSONObject().apply {
            put("id_hash", idHash)
            put("pubkey", b64(identityPub))
            put("spk", b64(spk))
            put("spk_sig", b64(spkSig))
            put("otks", JSONArray(otks.map { b64(it) }))
        }.toString().toByteArray()
        return Transport.signedRequest("POST", "/register", body, idHash, signer).code == 201
    }

    fun setUsername(username: String): Boolean {
        val body = JSONObject().put("username", username).toString().toByteArray()
        return Transport.signedRequest("POST", "/username", body, idHash, signer).code == 204
    }

    fun lookup(username: String): LookupResult? {
        val resp = Transport.signedRequest(
            "GET", "/lookup/$username", null, idHash, signer
        )
        if (resp.code != 200) return null
        val o = JSONObject(resp.body.decodeToString())
        return LookupResult(
            idHash = o.getString("id_hash"),
            identityPub = ub64(o.getString("pubkey")),
            spk = ub64(o.getString("spk"))
        )
    }

    data class LookupResult(val idHash: String, val identityPub: ByteArray, val spk: ByteArray)

    fun send(toIdHash: String, ratchetCiphertext: ByteArray): Boolean {
        // Паддинг до сетки размеров — чтобы объём не выдавал длину сообщения.
        val padded = pad(ratchetCiphertext)
        val body = JSONObject().apply {
            put("to", toIdHash)
            put("from", idHash)
            put("payload", b64(padded))
        }.toString().toByteArray()
        return Transport.signedRequest("POST", "/send", body, idHash, signer).code == 202
    }

    fun fetch(): List<Pair<String, ByteArray>> {
        val resp = Transport.signedRequest("GET", "/fetch", null, idHash, signer)
        if (resp.code != 200) return emptyList()
        val arr = JSONArray(resp.body.decodeToString())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("from") to ub64(o.getString("payload"))
        }
    }

    // СЕРТИФИКАТ СТИРАНИЯ. Подписано приватным ключом; сервер удаляет всё:
    // ключи, очередь, юзернейм. Приватные ключи существуют только на этом
    // устройстве — после wipe() восстановление невозможно никому, включая админа.
    fun eraseAccount(): Boolean {
        val code = Transport.signedRequest("POST", "/erase", null, idHash, signer).code
        if (code == 204) {
            store.wipe() // локальная БД SQLCipher уничтожена
            return true
        }
        return false
    }

    // Паддинг по сетке: 256/1K/4K/16K/64K/256K/1M, добор случайными байтами.
    fun pad(data: ByteArray): ByteArray {
        val sizes = intArrayOf(256, 1024, 4096, 16384, 65536, 262144, 1048576)
        val target = sizes.firstOrNull { it >= data.size + 4 } ?: (data.size + 4)
        val rnd = ByteArray(target - data.size)
        java.security.SecureRandom().nextBytes(rnd)
        return data + rnd
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun ub64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        fun idHashOf(identityPub: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(identityPub)
                .joinToString("") { "%02x".format(it) } // полный hex, 64 символа
    }
}
