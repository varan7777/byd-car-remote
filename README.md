# Car Remote — керування BYD Song Plus через Telegram

Власний застосунок для екрана авто (DiLink), який читає дані машини й відповідає
в Telegram. Без BYD-акаунта і без хмари BYD: застосунок говорить із системним
сервісом `autoservice` через ADB на самому екрані, а Telegram служить каналом зв'язку.

```
Telegram на iPhone  →  сервери Telegram  ←  Car Remote на DiLink  →  autoservice  →  авто
```

## Структура

| Папка | Що це |
|---|---|
| `dilink-app/` | Android-застосунок для екрана авто (Kotlin) |
| `reference/` | чужі проєкти для довідки, тільки читання (у git не йде) |

## Фаза 1 — що вміє зараз

Лише **читання**, жодних команд керування:

- `/status` — заряд, здоров'я батареї, 12V, зарядка, потужність, пробіг
- `/ping` — чи авто на зв'язку
- `/probe <device> <fid>` — прочитати будь-яку адресу (для пошуку ID на Song Plus)

Керування (клімат, замки) додамо після того, як читання запрацює саме на цьому авто.

## Підготовка

### 1. Інструменти на Mac (один раз)

JDK 21 уже є (`/opt/homebrew/opt/openjdk@21`). Потрібен Android SDK:

```bash
brew install --cask android-commandlinetools
```

```bash
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

`sdkmanager` попросить прийняти ліцензії Android SDK — це робиш ти.

### 2. Telegram-бот

1. У Telegram напиши [@BotFather](https://t.me/BotFather) → `/newbot` → отримаєш токен.
2. Напиши [@userinfobot](https://t.me/userinfobot) → він покаже твій числовий id.
3. Скопіюй `dilink-app/secrets.properties.example` у `dilink-app/secrets.properties` і впиши обидва значення.

Токен нікому не показуй: хто має токен, той може читати повідомлення бота.

### 3. ADB на екрані авто

Без ADB застосунок не прочитає дані. Чи можна його увімкнути, залежить від версії DiLink:

- **DiLink 3 / 4** — вмикається в меню розробника.
- **DiLink 5.0** — заблоковано заводом, розблоковують віддалено з Китаю.

## Збірка

```bash
cd dilink-app && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug
```

APK: `dilink-app/app/build/outputs/apk/debug/app-debug.apk`

## Встановлення на авто

Mac і авто в одній Wi-Fi мережі, IP екрана — у налаштуваннях Wi-Fi на DiLink:

```bash
adb connect <IP-екрана>:5555
```

```bash
adb install -r dilink-app/app/build/outputs/apk/debug/app-debug.apk
```

Після першого запуску:

1. На екрані з'явиться «Дозволити налагодження USB?» з ключем застосунку → **Завжди дозволяти** → **OK**.
2. Дозволь застосунку автозапуск, якщо прошивка питає, інакше система може його вбивати.
3. У Telegram прийде «🚗 Авто онлайн». Надішли `/status`.

## Безпека

- Бот відповідає **тільки** на `OWNER_CHAT_ID`, усіх інших ігнорує мовчки.
- Команди, надіслані поки авто було офлайн, при підключенні відкидаються.
- Клієнт `autoservice` технічно не вміє писати (тільки транзакції читання 5 і 7).
- Wake lock не тримаємо, щоб не посадити 12V на стоянці. Стеж за рядком 12V у `/status`.
