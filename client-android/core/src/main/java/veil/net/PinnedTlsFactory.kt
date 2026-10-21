package veil.net

// TLS-сокеты с пиннингом отпечатка сертификата реле. Сертификат
// самоподписанный (домен не нужен), клиент доверяет только точному
// совпадению SHA-256 — CA-системы и подменённые сертификаты отклоняются.

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object PinnedTlsFactory {

    fun socketFactory(pinnedFingerprint: String): SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: throw java.security.cert.CertificateException("нет сертификата")
                val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (fp != pinnedFingerprint) {
                    throw java.security.cert.CertificateException("отпечаток реле не совпадает — возможна подмена")
                }
            }
        }
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return ctx.socketFactory
    }
}
