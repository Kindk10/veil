package veil.core

// POST /erase по Keystore-токену (без подписи identity): вызывается при
// 5 неверных попытках PIN. Заголовки: X-ID-Hash + X-Erase-Token.

object EraseRequest {

    fun send(idHash: String, token: ByteArray): Boolean {
        val conn = veil.net.Transport.plainPost("/erase")
        conn.setRequestProperty("X-ID-Hash", idHash)
        conn.setRequestProperty("X-Erase-Token", token.joinToString("") { "%02x".format(it) })
        return conn.responseCode == 204
    }
}
