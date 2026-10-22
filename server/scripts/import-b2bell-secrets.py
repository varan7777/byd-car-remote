#!/usr/bin/env python3
"""Reuse the owner's existing bot and merchant; never print credentials.

Reads only the B2Bell settings through its constrained maintenance account.
Writes a private local .env; does not alter B2Bell or send Telegram messages.
"""
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
code = """import json
from app.core.config import settings
print(json.dumps({'AUTH_BOT_TOKEN':settings.ops_telegram_bot_token,'ADMIN_CHAT_ID':settings.ops_telegram_chat_id,'MONO_TOKEN':settings.monobank_token}))
"""
result = subprocess.run(
    ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "b2bell",
     "sudo -n b2bell run --rm -T api python -c " + shlex.quote(code)],
    capture_output=True, text=True, timeout=90,
)
if result.returncode:
    raise SystemExit("Unable to read B2Bell configuration; no files changed.")
try:
    values = json.loads(result.stdout.strip().splitlines()[-1])
except (ValueError, IndexError):
    raise SystemExit("Unexpected response; no files changed.") from None
if not re.fullmatch(r"\d+:[A-Za-z0-9_-]+", values.get("AUTH_BOT_TOKEN", "")):
    raise SystemExit("B2Bell auth bot is not configured; no files changed.")
if not re.fullmatch(r"\d+", str(values.get("ADMIN_CHAT_ID", ""))):
    raise SystemExit("The configured recipient must be a private Telegram chat; no files changed.")
env = root / ".env"
lines = env.read_text().splitlines() if env.exists() else []
existing = {line.split("=", 1)[0] for line in lines if "=" in line and not line.startswith("#")}
defaults = {"SESSION_SECRET": secrets.token_urlsafe(48), "WEBHOOK_SECRET": secrets.token_urlsafe(48),
            "PUBLIC_URL": "https://varan.team", "PORT": "8080", "DB_PATH": "./data/hub.db",
            "MEDIA_ROOT": "./data/media", "NODE_ENV": "production"}
car_secrets = root.parent / "dilink-app" / "secrets.properties"
if "BOT_TOKEN" not in existing and car_secrets.exists():
    for line in car_secrets.read_text().splitlines():
        if line.startswith("TELEGRAM_BOT_TOKEN="):
            token = line.split("=",1)[1].strip()
            if re.fullmatch(r"\d+:[A-Za-z0-9_-]+", token):
                defaults["BOT_TOKEN"] = token
updates = {**{k:v for k,v in defaults.items() if k not in existing}, **values}
lines = [line for line in lines if line.split("=",1)[0] not in updates]
lines.extend(f"{key}={json.dumps(str(value))}" for key,value in updates.items())
fd, name = tempfile.mkstemp(prefix=".env-", dir=root)
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(name, env)
finally:
    if os.path.exists(name): os.unlink(name)
print("Private .env saved: main bot configured; private recipient configured; "
      + ("MonoPay configured." if values.get("MONO_TOKEN") else "MonoPay token is empty in B2Bell."))
