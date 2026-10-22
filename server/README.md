# Varan Control Room / Car Remote Hub

Адмінка varan.team, один BYD-бот, ізольовані авто, компактна реклама та разова оплата «без реклами назавжди» через MonoPay.

## Можливості

- `/admin/`: вхід лише одноразовим кодом у приватному чаті власника з `@Varanb2b_bot`. Код на 5 хвилин, 5 спроб, обмеження надсилання. Пароля й реєстрації немає. Сесії в SQLite: 30 хв бездіяльності / 12 год загалом, CSRF-захист.
- Редактори головної та BYD UA/EN: фото, сервіси, порядок, видимість, тексти, скриншоти й посилання. Чернетка, попередній перегляд, публікація, відновлення версії. Початкові дані імпортовано з чинного сайту; довільний HTML не приймається.
- Стан бота, пауза, привітання, авто онлайн, власники, блокування й відкликання прив’язок. Журнал команд на 30 днів, аудит змін на 180 днів.
- Рекламна кампанія: заголовок, короткий текст, посилання, період, додаток/бот, інтервал Telegram від 6 годин. Текстовий банер у боті надсилається після відповіді на команду. Заряд, паркування та інші фонові повідомлення без реклами; масових розсилок немає.
- Разова ціна задається в гривнях у панелі, банк отримує копійки. Початкова **0 = оплату вимкнено**, доки власник не задасть ціну. Покупка належить Telegram-власнику й діє в боті та додатку, включно після заміни авто.
- Посилання видають `/noads`, `/start noads` і банер. Воно містить випадковий добовий ключ у URL fragment, без chat ID та секретів у серверних access-логах. `/byd/` перенаправляє на захищену сторінку MonoPay.
- Права видаються тільки за перевіреним ECDSA webhook або серверним статусом invoice. Перевіряються сума, валюта, замовлення та час. Повтори безпечні; повернення платежу відкликає відповідне право. Неоднозначний timeout створення invoice блокує повторну оплату до звірки.
- Місце відвідувача: `/api/location`, приблизна геолокація за IP через HTTPS ipwho.is, кеш 24 год і ліміт 900 запитів/добу. GPS не запитується; при помилці рядок приховується. `TRUSTED_PROXIES` має відповідати фактичній топології.

## Локальний запуск і перевірки

Node.js 24; публічний сайт зберігається окремо в `Varan team`.

```bash
npm ci
cp .env.example .env
# Заповнити секрети; або повторно використати вже авторизовані власником:
python3 scripts/import-b2bell-secrets.py
# Скрипт читає B2Bell без змін і пише приватний .env (0600), без виведення секретів.

NODE_ENV=development PUBLIC_URL=http://127.0.0.1:8080 \
  DB_PATH=./data/hub.db MEDIA_ROOT=./data/media \
  SITE_ROOT="/Users/artemzaika/Varan team/public" npm start
```

Відкрити http://127.0.0.1:8080/admin/. Локальний вхід надсилає реальний код власнику. Звичайний запуск не реєструє webhook і не змінює B2Bell. Головний бот використовується лише через вихідний `sendMessage`. На старті не створюються платежі.

```bash
npm test
node scripts/browser-check.mjs "/Users/artemzaika/Varan team/public"
npm audit --omit=dev
```

Браузерна перевірка потребує Google Chrome; використовує тимчасову БД і тестову доставку кодів без повідомлень користувачам. Скриншоти в `artifacts/`. Для імпорту статичного контенту в seed нової БД: `npm run import-site -- "/Users/artemzaika/Varan team/public"`. Чинна БД не перезаписується.

## Запуск на хостингу — для адміністратора

Перевірений SSH `varan_te1` працює в контейнері без Node.js, Docker socket і доступу до служб хоста. Постійний сервіс встановлює адміністратор хостингу. Архів коду без секретів: `bash scripts/package-release.sh` → `artifacts/varan-hub.tar.gz`.

1. На хості створити системного користувача `varan-hub`, установити Node.js 24, розпакувати архів у `/srv/varan-hub/releases/<version>`, виконати `npm ci --omit=dev`; симлінк `/srv/varan-hub/current` указує на цей release.
2. Установити `/etc/varan-hub.env` (root, 0600), секрети передаються окремо від архіву. Задати абсолютні `DB_PATH=/var/lib/varan-hub/hub.db`, `MEDIA_ROOT=/var/lib/varan-hub/media`, `NODE_ENV=production`, `PUBLIC_URL=https://varan.team` та реальні `TRUSTED_PROXIES`. Локальні шляхи macOS не переносити.
3. Установити `ops/carremote-hub.service`, запустити його, перевірити локальний `/health`. Порт `127.0.0.1:8080` не відкривати в інтернет.
4. У зовнішньому HTTPS-vhost лише **varan.team** застосувати `ops/apache.conf`: `/admin`, `/api/`, `/media/`, `/tg/`, `/car` з WebSocket. Це не `.htaccess`. Якщо Apache ізольований від loopback хоста, налаштувати приватний upstream і firewall. Передавати справжній клієнтський IP; не довіряти довільному X-Forwarded-For з інтернету.
5. Викласти зміни репозиторію сайту його `deploy.sh`. `rsync --delete` зачіпає тільки static public_html; `/var/lib/varan-hub` з покупками, медіа та контентом зберігається.
6. Перевірити вхід кодом, редагування/публікацію та оплату в тестовому режимі банку. Власник задає разову ціну в адмінці. Бойову оплату фіктивним успіхом не підміняти.
7. Перед перемиканням `@Varanbyd_bot` зупинити старе пряме опитування Telegram на авто. Лише після цього з service env виконати `npm run webhook`. Команда реєструє тільки BYD-webhook; головний бот незмінний.
8. На тестовій голові встановити APK 0.22.0, пройти прив’язку кодом, перевірити команди, клавіатури, reconnect та банер. Старий APK 0.21.3 до Hub не підключиться. Новий APK не викладати як production до перевірки на авто.

## Протокол додатка

`wss://varan.team/car`. Одна прив’язка та жива сесія на власника; лише особисті Telegram-чати.

```json
{"type":"pair","code":"123456"}
{"type":"paired","carToken":"server-generated-secret","chatId":123}
{"type":"hello","carToken":"saved-secret"}
{"type":"ready","chatId":123}
{"type":"cmd","text":"/status","requestId":"unique-id"}
{"type":"reply","text":"Status","requestId":"same-id","keyboard":{"keyboard":[["Статус"]],"resize_keyboard":true}}
{"type":"ping"}
{"type":"pong"}
```

Після авторизації та змін реклами/оплати сервер надсилає:

```json
{"type":"config","adFree":false,"purchaseUrl":"https://varan.team/byd/#offer=random-secret","banner":{"enabled":true,"title":"Title","text":"Short text","url":"https://example.com","app":true,"bot":true,"intervalHours":24,"startsAt":0,"endsAt":0,"imageUrl":""}}
```

Для платного власника `adFree:true`, `banner:null`, `purchaseUrl:null`. Не використовувати локальний прапорець як підтвердження оплати. `GET /api/car/config` із `Authorization: Bearer <carToken>` повторно синхронізує стан. Watchdog надсилає завершальний текст на `/api/car/notification` з тим самим Bearer; Telegram-токена в новому APK немає. Сервер зберігає лише хеші секретів авто.

## Резервування та відкат

Щодня з service env: `node scripts/backup.mjs /private/backup/path` (SQLite backup API, сумісний із WAL). Окремо копіювати `MEDIA_ROOT`, зберігати секрети в захищеному сховищі та перевіряти відновлення на окремій БД. Резерви не класти в public_html.

Перед оновленням: backup, новий release, симлінк, перезапуск, health і перевірка входу. Відкат коду — попередній симлінк. Не відновлювати стару production-БД поверх нових платежів; спершу звірити замовлення з банком. Контролювати systemd-журнал, помилки Telegram, платежі `unknown` і резервування. При відмові Hub публічний сайт показує статичну версію, оплата недоступна, команди авто не накопичуються.

Джерела: [Telegram](https://core.telegram.org/bots/api#setwebhook), [Mono API](https://monobank.ua/api-docs/acquiring/methods), [підпис Mono webhook](https://monobank.ua/en/api-docs/acquiring/dev/webhooks/get--api--merchant--pubkey), [IP geolocation](https://ipwhois.io/documentation).
