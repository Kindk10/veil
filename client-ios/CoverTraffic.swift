// CoverTraffic (шумовой трафик) для iOS — §5.2 спецификации.
// На iOS фоновая работа ограничена Apple: постоянный шум работает только
// в переднем плане; в фоне — профиль ACTIVE_ONLY (BGTaskScheduler-окна).
// Это ограничение платформы, задокументировано в docs/IOS.md.

import Foundation
import CryptoKit

enum NoiseMode { case constant, activeOnly }

final class CoverTraffic {
    var mode: NoiseMode = .constant

    // Интервал настраивается пользователем (10–60 сек).
    // Пресеты: 10 (макс. скрытность), 30 (баланс), 60 (батарея).
    var intervalSeconds: TimeInterval = 30

    private var timer: Timer?
    private let noiseSize = 256

    func randomNoise() -> Data {
        var bytes = [UInt8](repeating: 0, count: noiseSize)
        _ = SecRandomCopyBytes(kSecRandomDefault, noiseSize, &bytes)
        return Data(bytes)
    }

    func start(onSend: @escaping (Data) -> Void) {
        stop()
        timer = Timer.scheduledTimer(withTimeInterval: intervalSeconds, repeats: true) { [weak self] _ in
            guard let self else { return }
            onSend(self.randomNoise())
        }
    }

    func setInterval(seconds: Int) {
        precondition((10...60).contains(seconds), "интервал 10–60 сек")
        intervalSeconds = TimeInterval(seconds)
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    deinit { stop() }
}
