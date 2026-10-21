# VEIL — статус каркаса клиента

## Модули

```
client-android/
├── core/                          — крипто-ядро (Kotlin, без UI)
│   ├── Identity.kt                  ключи из случайности, ID = хеш ключа
│   ├── SecureStorage.kt             SQLCipher + PIN (PBKDF2 600k, AES-GCM обёртка ключа)
│   ├── Session.kt                   Double Ratchet (libsignal SessionCipher)
│   ├── VeilApi.kt                   register/username/lookup/send/fetch/erase + паддинг
│   └── Transport.kt                 Tor-only SOCKS (Orbot 127.0.0.1:9050), TLS 1.3
└── app/                           — UI (Compose): PIN → чаты → стирание
    └── MainActivity.kt
```

## Что реализовано

- Аккаунт = ключевая пара на устройстве; сервер хранит только хеш.
- PIN-разблокировка: ключ БД обёрнут AES-256-GCM ключом из PIN; PIN не хранится.
- Паническое стирание: подписанный запрос /erase + wipe() SQLCipher-файла.
- Tor-only транспорт по умолчанию: без Orbot ничего не отправляется.
- Паддинг сообщений по сетке размеров (256…1M) со случайным добором.

## Зависимости (build.gradle, при сборке проекта)

- `net.zetetic:android-database-sqlcipher:4.5.4` (SQLCipher)
- `org.whispersystems:libsignal-android:2.x` (Double Ratchet)
- Jetpack Compose BOM, kotlinx-coroutines

## Замечания

- В Session.kt используется InMemorySignalProtocolStore как заглушка;
  продакшен-этап — адаптер SessionStore над SQLCipher (ключи ратчета
  перезаписываются в зашифрованной БД).
- PBKDF2 в SecureStorage — временно; при подключении нативного ядра заменить
  на Argon2id (libsodium), константа KDF_ITERS уже заложена.
- До деплоя реле (VPS, режим onion) клиент соберётся, но сеть недоступна —
  это нормальный порядок: сначала протокол локально, потом включаем транспорт.
