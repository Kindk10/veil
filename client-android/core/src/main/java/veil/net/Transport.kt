package veil.net

// Транспортный слой VEIL. Все запросы идут через SOCKS5-прокси
// (Orbot/Tor на 127.0.0.1:9050) в режиме torOnly = true, либо напрямую
// поверх TLS 1.3 в режиме torOnly = false (fallback).

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

// Мосты obfs4, вшитые в приложение: стартовый набор для Orbot.
// Уязвимы со временем — обновляются релизами и через fetchBridges().
object EmbeddedBridges {
    // obfs4-сертификаты мостов (заполняются при первом релизе из
    // актуальных публичных списков + собственные узлы на этапе 3).
    val obfs4Bridges: List<String> = listOf(
        // "obfs4 <ip>:<port> <fingerprint> cert=<...> iat-mode=<0|1|2>",
        // ...
    )

    // Свежий список мостов с реле — без привязки к ID, через общий канал.
    fun fetchBridges(api: (String) -> ByteArray): List<String> {
        val raw = api("/bridges")
        return String(raw).lines().filter { it.isNotBlank() }
    }
}


object Transport {

    const val ORBOT_SOCKS_HOST = "127.0.0.1"
    const val ORBOT_SOCKS_PORT = 9050

    var baseUrl: String = "https://188.127.237.61"

    // SHA-256 отпечаток сертификата реле (пиннинг). Задаётся один раз,
    // после этого клиент отвергает ЛЮБОЙ другой сертификат — подмена
    // соединения (MITM с доверенным CA) невозможна.
    var relayFingerprint: String =
        "49a78d1e58cf2d0043535be3de3f18a5a314657048939be8f81db6967879302f"
    var torOnly: Boolean = true  // по умолчанию: без Tor не работаем вообще.
                                 // ПРЯМЫХ СОЕДИНЕНИЙ НЕ СУЩЕСТВУЕТ: если Tor недоступен,
                                 // приложение не отправляет ничего (см. PROTOCOL.md §5.1).

    // Кастомный SOCKS-прокси (например, локальный VLESS-клиент),
    // необязательный: при включённом системном VPN Orbot сам пойдёт через него.
    var customProxyHost: String? = null
    var customProxyPort: Int? = null

    private fun proxy(): Proxy {
        val host = customProxyHost
        val port = customProxyPort
        return if (host != null && port != null) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        } else {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(ORBOT_SOCKS_HOST, ORBOT_SOCKS_PORT))
        }
    }

    enum class TransportState { TOR_OK, TOR_UNREACHABLE, READY }

    // Диагностика транспорта: пытаемся открыть туннель через SOCKS.
    // TOR_UNREACHABLE — Tor не поднялся: пользователю показывается экран
    // «включите мосты в Orbot или свой VLESS».
    fun probe(): TransportState {
        return try {
            val h = customProxyHost ?: ORBOT_SOCKS_HOST
            val p = customProxyPort ?: ORBOT_SOCKS_PORT
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(h, p))
            java.net.Socket(proxy).use { s ->
                s.connect(InetSocketAddress(h, p), 10_000)
                TransportState.READY
            }
        } catch (e: Exception) {
            TransportState.TOR_UNREACHABLE
        }
    }

    private fun openConnection(path: String): HttpsURLConnection {
        val url = java.net.URL(baseUrl + path)
        val host: String
        val port: Int
        if (torOnly) {
            val h = customProxyHost ?: ORBOT_SOCKS_HOST
            val p = customProxyPort ?: ORBOT_SOCKS_PORT
            host = h; port = p
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            url.openConnection(proxy) as HttpsURLConnection
        } else {
            url.openConnection() as HttpsURLConnection
        }.also { conn ->
            // TLS 1.3 only + пиннинг отпечатка сертификата реле.
            conn.sslSocketFactory = PinnedTlsFactory.socketFactory(relayFingerprint)
            conn.setHostnameVerifier { _, session ->
                val cert = session.peerCertificates?.firstOrNull() ?: return@setHostnameVerifier false
                val fp = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it) }
                fp == relayFingerprint
            }
            conn.requestProperties.remove("User-Agent")
            conn.useCaches = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            return conn
        }
    }

    // Без авторизации: только для /erase по токену стирания.
    fun plainPost(path: String): HttpsURLConnection {
        val conn = openConnection(path)
        conn.requestMethod = "POST"
        conn.doOutput = true
        return conn
    }

    // Подпись каждого запроса приватным ключом идентичности.
    // Паролей и сессийных токенов нет: method|path|sha256(body) подписывается Ed25519.
    fun signedRequest(
        method: String,
        path: String,
        body: ByteArray?,
        idHash: String,
        signer: (ByteArray) -> ByteArray
    ): HttpResponse {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body ?: ByteArray(0))
        val sigBase = ByteArrayOutputStream().apply {
            write(method.toByteArray()); write(path.toByteArray()); write(bodyHash)
        }.toByteArray()
        val sig = signer(sigBase)

        val conn = openConnection(path)
        conn.requestMethod = method
        conn.setRequestProperty("X-ID-Hash", idHash)
        conn.setRequestProperty("X-Auth-Sig", Base64.getEncoder().encodeToString(sig))
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return HttpResponse(code, stream?.readBytes() ?: ByteArray(0))
    }
}

data class HttpResponse(val code: Int, val body: ByteArray)
