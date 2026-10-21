// CoverTraffic (шумовой трафик) для Windows — §5.2 спецификации.
// На десктопе фоновых ограничений нет: постоянный шум работает всегда.

using System;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace Veil.Core
{
    public enum NoiseMode { Constant, ActiveOnly }

    public sealed class CoverTraffic : IDisposable
    {
        public NoiseMode Mode { get; set; } = NoiseMode.Constant;

        // Интервал настраивается пользователем (10–60 сек).
        // Пресеты: 10 (макс. скрытность), 30 (баланс), 60 (батарея).
        public int IntervalSeconds { get; private set; } = 30;

        private const int NoiseSize = 256;
        private readonly RandomNumberGenerator _rng = RandomNumberGenerator.Create();
        private Timer? _timer;

        public byte[] RandomNoise()
        {
            var buf = new byte[NoiseSize];
            _rng.GetBytes(buf);
            return buf;
        }

        public void SetInterval(int seconds)
        {
            if (seconds is < 10 or > 60) throw new ArgumentOutOfRangeException(nameof(seconds));
            IntervalSeconds = seconds;
        }

        public void Start(Func<byte[], Task> send)
        {
            Stop();
            _timer = new Timer(async _ =>
            {
                try { await send(RandomNoise()); } catch { /* транспорт недоступен */ }
            }, null, TimeSpan.FromSeconds(IntervalSeconds), TimeSpan.FromSeconds(IntervalSeconds));
        }

        public void Stop() => _timer?.Dispose();

        public void Dispose()
        {
            Stop();
            _rng.Dispose();
        }
    }
}
