# Car Remote Hub (varan.team)

One Telegram bot (**@Varanbyd_bot**), many cars, each **isolated** — a car is paired to exactly
one Telegram owner and nobody can reach another person's car. No per‑user bot, no token in the APK.

```
Telegram ──webhook──▶  /tg/<id>  ┐
                                  ├─ Node hub (this)  ── SQLite (owners, cars, pair_codes, logs)
Head unit (APK) ──wss──▶ /car    ┘        │
                                   /admin  ──▶ admin panel (cars online, logs)
```

### Pairing flow
1. Owner opens **@Varanbyd_bot** → **/start** → taps **“Поділитися номером”** (Telegram verifies the phone).
2. Bot stores the owner and shows a **6‑digit code** (valid 10 min).
3. In the APK on the head unit the owner enters the code. The car (holding a secret `carToken`)
   sends `{type:"pair", code, carToken}` over `wss://varan.team/car` → the hub binds car ↔ owner.
4. From then on: owner's Telegram text → hub → **their** car (ws) → the car's existing
   `CommandRouter` runs it against `autoservice` → reply → hub → owner's chat.

The car keeps all its logic locally; the hub is only the secure transport + pairing/isolation.

### Run locally
```bash
cp .env.example .env     # fill BOT_TOKEN, ADMIN_PASSWORD, PUBLIC_URL
npm install
npm start
```

### Deploy on varan.team (Apache + systemd)
Run the hub on `127.0.0.1:8080` as a service and let Apache terminate TLS and proxy to it.

**systemd** `/etc/systemd/system/carremote-hub.service`:
```ini
[Service]
WorkingDirectory=/home/varan_te1/carremote-hub
ExecStart=/usr/bin/node src/index.js
EnvironmentFile=/home/varan_te1/carremote-hub/.env
Restart=always
[Install]
WantedBy=multi-user.target
```

**Apache vhost** (proxy the webhook, the car WebSocket and the admin page):
```apache
ProxyPass        /tg/   http://127.0.0.1:8080/tg/
ProxyPassReverse /tg/   http://127.0.0.1:8080/tg/
ProxyPass        /admin http://127.0.0.1:8080/admin
ProxyPassReverse /admin http://127.0.0.1:8080/admin
# WebSocket for cars:
RewriteEngine On
RewriteCond %{HTTP:Upgrade} =websocket [NC]
RewriteRule ^/car$ ws://127.0.0.1:8080/car [P,L]
```
(needs `a2enmod proxy proxy_http proxy_wstunnel rewrite`). The static site in `public_html` is untouched.

### App side (next APK)
Add a “Hub mode”: instead of polling Telegram, connect to `wss://varan.team/car`, enter the pairing
code once, then receive `cmd` text (run it through the existing `CommandRouter`) and send back `reply`.

> ⚠️ Scaffold — not yet deployed or load‑tested. `.env` and `data/` are gitignored.
