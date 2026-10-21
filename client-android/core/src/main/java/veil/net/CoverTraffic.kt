package veil.net

// Шумовой трафик (cover traffic) — §5.2 спецификации.
// Пакеты фиксированного размера с фиксированной периодичностью: реальное
// сообщение или случайные байты — сервер и наблюдатель не различают.

import java.security.SecureRandom
import kotlinx.coroutines.*

object CoverTraffic {

    enum class Mode { CONSTANT, ACTIVE_ONLY } // профиль шума: постоянный / при активности
    // По решению владельца проекта: постоянный шум по умолчанию —
    // скрытность приоритетнее батареи.
    var mode: Mode = Mode.CONSTANT

    // Интервал настраивается пользователем в настройках приложения
    // (10–60 сек). Чем больше интервал — тем меньше расход батареи;
    // скрытность сохраняется: пакеты неразличимы при любом интервале.
    // Пресеты: 10 (макс. скрытность), 30 (баланс), 60 (батарея).
    var intervalMs: Long = 30_000L

    val minIntervalMs: Long = 10_000L
    val maxIntervalMs: Long = 60_000L

    fun setInterval(seconds: Int) {
        require(seconds * 1000L >= minIntervalMs && seconds * 1000L <= maxIntervalMs)
        intervalMs = seconds * 1000L
    }

    private const val NOISE_SIZE = 256 // минимальная ячейка сетки паддинга
    private val rnd = SecureRandom()

    fun randomNoise(): ByteArray =
        ByteArray(NOISE_SIZE).also { rnd.nextBytes(it) }

    // Запускается на весь период активной сессии; останавливается при закрытии.
    fun start(scope: CoroutineScope, send: suspend (ByteArray) -> Unit): Job =
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                send(randomNoise())
            }
        }
}
