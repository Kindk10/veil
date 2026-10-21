package veil.core

// Double Ratchet-сессия поверх libsignal. Один класс = один чат 1:1.
// Каждый конверт непрозрачен для сервера; компрометация текущего ключа
// не раскрывает прошлые сообщения (forward secrecy) и не даёт
// подделывать будущие после следующего DH-шага (post-compromise security).

import org.whispersystems.libsignal.*
import org.whispersystems.libsignal.protocol.CiphertextMessage
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.state.SignalProtocolStore
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore
import java.security.SecureRandom

class Session(
    private val store: SignalProtocolStore,
    peerAddress: SignalProtocolAddress
) {
    private val sessionCipher = org.whispersystems.libsignal.SessionCipher(store, peerAddress)

    fun encrypt(plaintext: ByteArray): ByteArray =
        sessionCipher.encrypt(plaintext).serialize()

    fun decrypt(envelope: ByteArray): ByteArray {
        return try {
            val pre = PreKeySignalMessage(envelope)
            sessionCipher.decrypt(pre)
        } catch (e: Exception) {
            val msg = org.whispersystems.libsignal.protocol.SignalMessage(envelope)
            sessionCipher.decrypt(msg)
        }
    }

    companion object {
        // Создание локального протокол-стора (в реальном приложении
        // бэкендится SQLCipher через SessionStore-адаптер — этап ниже).
        fun createLocalStore(identityKeyPair: IdentityKeyPair): SignalProtocolStore =
            InMemorySignalProtocolStore(identityKeyPair, SecureRandom().nextInt())

        fun newPeer(idHash: String): SignalProtocolAddress =
            SignalProtocolAddress(idHash, 1)
    }
}
