# Деплой VEIL relay на VPS (Linux/Ubuntu 22.04+)

Два режима работы — выберите один:

## Режим A. Onion-сервис (рекомендуется для анонимности)
Никаких доменов и TLS-сертификатов не нужно: сервер виден только как
`xxxx.onion`, клиенты ходят через Orbot/Tor. IP сервера скрыт за onion.

```bash
sudo apt update && sudo apt install -y tor golang-go
# 1) Собрать реле
git clone <ваш репозиторий> veil && cd veil/server
go build -o /usr/local/bin/veil-relay .
# 2) Onion-сервис tor: добавить в /etc/tor/torrc
#    HiddenServiceDir /var/lib/tor/veil/
#    HiddenServicePort 8080 127.0.0.1:8080
sudo systemctl restart tor
sudo cat /var/lib/tor/veil/hostname   # <- это адрес onion, вставить в клиент
# 3) Systemd-юнит /etc/systemd/system/veil-relay.service:
#    [Service]
#    ExecStart=/usr/local/bin/veil-relay
#    Environment=VEIL_DOMAIN=          # пусто: слушает 127.0.0.1:8080 за tor
#    User=nobody
#    NoNewPrivileges=true
#    PrivateTmp=true
#    ProtectSystem=strict
sudo systemctl enable --now veil-relay
```

## Режим B. Домен + автоматический TLS (Let's Encrypt)
Если нужен адрес без Tor (fallback-канал). Нужен домен, A-запись → IP сервера.

```bash
sudo apt install -y golang-go
cd veil/server
VEIL_DOMAIN=veil.ваш-домен.ru VEIL_CERT_DIR=/var/lib/veil/certs \
  nohup sudo -E ./veil-relay &   # или systemd-юнит с теми же переменными
```
Сервер сам получит и сам обновляет сертификат Let's Encrypt (порт 80 и 443
должны быть открыты). TLS минимум 1.3. Кэш сертификатов — в VEIL_CERT_DIR.

## Требования
- VPS: 1 ядро, 1 ГБ RAM, 10 ГБ диска — с запасом на сотни пользователей.
- Открытые порты: 8080 только для tor (localhost), 80+443 для режима B.
- СУБД НЕ нужна. Логи НЕ ведутся. Swap не критичен.

## Проверка после запуска
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/register
# ожидаемо 400 (bad request) — реле живо
```
