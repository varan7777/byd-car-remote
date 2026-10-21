// SQLite store for pairings, cars and a small command log.
// One file, synchronous (better-sqlite3) — plenty for this scale.
import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const path = process.env.DB_PATH || './data/hub.db';
mkdirSync(dirname(path), { recursive: true });

export const db = new Database(path);
db.pragma('journal_mode = WAL');

db.exec(`
  -- A Telegram user who shared their phone and owns one car.
  CREATE TABLE IF NOT EXISTS owners (
    chat_id    INTEGER PRIMARY KEY,
    phone      TEXT UNIQUE,            -- verified via Telegram "share contact"
    name       TEXT,
    created_at INTEGER
  );

  -- A head unit. Bound to exactly one owner once paired.
  CREATE TABLE IF NOT EXISTS cars (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    car_token  TEXT UNIQUE,            -- secret the APK stores and authenticates with
    chat_id    INTEGER,                -- owner; NULL until paired
    label      TEXT,
    last_seen  INTEGER,
    created_at INTEGER,
    FOREIGN KEY (chat_id) REFERENCES owners(chat_id)
  );

  -- Short-lived pairing codes shown in the bot, typed on the car.
  CREATE TABLE IF NOT EXISTS pair_codes (
    code       TEXT PRIMARY KEY,
    chat_id    INTEGER,
    expires_at INTEGER,
    used       INTEGER DEFAULT 0
  );

  -- Light audit log (who sent what to which car).
  CREATE TABLE IF NOT EXISTS logs (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    car_id    INTEGER,
    direction TEXT,                    -- 'in' (user->car) | 'out' (car->user)
    text      TEXT,
    ts        INTEGER
  );
`);

const now = () => Date.now();

export const owners = {
  upsert(chatId, phone, name) {
    db.prepare(`INSERT INTO owners(chat_id,phone,name,created_at) VALUES(?,?,?,?)
                ON CONFLICT(chat_id) DO UPDATE SET phone=excluded.phone, name=excluded.name`)
      .run(chatId, phone, name, now());
  },
};

export const codes = {
  create(chatId, ttlMs = 10 * 60_000) {
    const code = String(Math.floor(100000 + Math.random() * 900000)); // 6 digits
    db.prepare(`INSERT INTO pair_codes(code,chat_id,expires_at) VALUES(?,?,?)`)
      .run(code, chatId, now() + ttlMs);
    return code;
  },
  // Consume a code → returns chat_id or null.
  redeem(code) {
    const row = db.prepare(`SELECT * FROM pair_codes WHERE code=? AND used=0 AND expires_at>?`)
      .get(code, now());
    if (!row) return null;
    db.prepare(`UPDATE pair_codes SET used=1 WHERE code=?`).run(code);
    return row.chat_id;
  },
};

export const cars = {
  // Bind a (new or existing) car to an owner during pairing; returns the car row.
  bind(carToken, chatId) {
    const existing = db.prepare(`SELECT * FROM cars WHERE car_token=?`).get(carToken);
    if (existing) {
      db.prepare(`UPDATE cars SET chat_id=?, last_seen=? WHERE id=?`).run(chatId, now(), existing.id);
      return { ...existing, chat_id: chatId };
    }
    const info = db.prepare(`INSERT INTO cars(car_token,chat_id,created_at,last_seen) VALUES(?,?,?,?)`)
      .run(carToken, chatId, now(), now());
    return db.prepare(`SELECT * FROM cars WHERE id=?`).get(info.lastInsertRowid);
  },
  byToken: (t) => db.prepare(`SELECT * FROM cars WHERE car_token=?`).get(t),
  byChat: (c) => db.prepare(`SELECT * FROM cars WHERE chat_id=?`).get(c),
  seen: (id) => db.prepare(`UPDATE cars SET last_seen=? WHERE id=?`).run(now(), id),
  all: () => db.prepare(`SELECT * FROM cars ORDER BY last_seen DESC`).all(),
};

export const logs = {
  add: (carId, direction, text) =>
    db.prepare(`INSERT INTO logs(car_id,direction,text,ts) VALUES(?,?,?,?)`).run(carId, direction, text, now()),
  recent: (limit = 100) => db.prepare(`SELECT * FROM logs ORDER BY id DESC LIMIT ?`).all(limit),
};
