import Database from "better-sqlite3";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { createHash, randomBytes, randomInt } from "node:crypto";
export const hash = (v) => createHash("sha256").update(String(v)).digest("hex");
export const secret = () => randomBytes(32).toString("base64url");
export const redact = (value) => String(value)
  .replace(/\b\d{5,}:[A-Za-z0-9_-]+/g, '[секрет]')
  .replace(/[A-Za-z0-9_-]{32,}/g, '[секрет]')
  .replace(/\b\d{6}\b/g, '[код]')
  .replace(/\+?\b\d{10,15}\b/g, '[номер]');

export function openStore(path = "./data/hub.db") {
  if (path !== ":memory:")
    mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const db = new Database(path);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  db.exec(`
    CREATE TABLE IF NOT EXISTS owners(chat_id INTEGER PRIMARY KEY, phone TEXT UNIQUE, name TEXT, created_at INTEGER, blocked INTEGER DEFAULT 0);
    CREATE TABLE IF NOT EXISTS cars(id INTEGER PRIMARY KEY, car_token TEXT UNIQUE, token_hash TEXT UNIQUE, chat_id INTEGER REFERENCES owners(chat_id), label TEXT DEFAULT '', last_seen INTEGER, created_at INTEGER, blocked INTEGER DEFAULT 0);
    CREATE TABLE IF NOT EXISTS pair_codes(code TEXT PRIMARY KEY, chat_id INTEGER REFERENCES owners(chat_id), expires_at INTEGER, used INTEGER DEFAULT 0);
    CREATE TABLE IF NOT EXISTS logs(id INTEGER PRIMARY KEY, car_id INTEGER, direction TEXT, text TEXT, ts INTEGER);
    CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions(sid TEXT PRIMARY KEY, data TEXT NOT NULL, expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS login_codes(id TEXT PRIMARY KEY, digest TEXT NOT NULL, expires INTEGER, attempts INTEGER DEFAULT 0, used INTEGER DEFAULT 0);
    CREATE TABLE IF NOT EXISTS limits(bucket TEXT, ts INTEGER);
    CREATE INDEX IF NOT EXISTS limits_lookup ON limits(bucket,ts);
    CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, action TEXT, target TEXT, ts INTEGER);
    CREATE TABLE IF NOT EXISTS site_versions(id INTEGER PRIMARY KEY, body TEXT NOT NULL, published INTEGER DEFAULT 0, ts INTEGER);
    CREATE TABLE IF NOT EXISTS updates(update_id INTEGER PRIMARY KEY, ts INTEGER);
    CREATE TABLE IF NOT EXISTS offers(id TEXT PRIMARY KEY, chat_id INTEGER REFERENCES owners(chat_id), expires INTEGER);
    CREATE TABLE IF NOT EXISTS payments(id TEXT PRIMARY KEY, chat_id INTEGER REFERENCES owners(chat_id), amount INTEGER NOT NULL, invoice_id TEXT UNIQUE, page_url TEXT, status TEXT NOT NULL, modified INTEGER DEFAULT 0, created_at INTEGER);
    CREATE UNIQUE INDEX IF NOT EXISTS pending_payment ON payments(chat_id) WHERE status IN ('creating','unknown','created','processing','hold');
    CREATE TABLE IF NOT EXISTS entitlements(payment_id TEXT PRIMARY KEY REFERENCES payments(id), chat_id INTEGER REFERENCES owners(chat_id), active INTEGER NOT NULL DEFAULT 1, granted_at INTEGER);
  `);
  const add = (t, n, type) => {
    if (
      !db
        .prepare(`PRAGMA table_info(${t})`)
        .all()
        .some((c) => c.name === n)
    )
      db.exec(`ALTER TABLE ${t} ADD COLUMN ${n} ${type}`);
  };
  add("owners", "blocked", "INTEGER DEFAULT 0");
  add("cars", "blocked", "INTEGER DEFAULT 0");
  add("cars", "token_hash", "TEXT");
  db.transaction(() => {
    for (const c of db
      .prepare("SELECT id,car_token FROM cars WHERE car_token IS NOT NULL")
      .all())
      db.prepare("UPDATE cars SET token_hash=?,car_token=NULL WHERE id=?").run(
        hash(c.car_token),
        c.id,
      );
    db.prepare("DELETE FROM pair_codes WHERE length(code)<>64").run();
    db.exec(
      "CREATE UNIQUE INDEX IF NOT EXISTS cars_token_hash ON cars(token_hash); CREATE UNIQUE INDEX IF NOT EXISTS cars_owner ON cars(chat_id) WHERE chat_id IS NOT NULL;",
    );
  })();
  const get = (k, fallback = null) => {
    const r = db.prepare("SELECT value FROM settings WHERE key=?").get(k);
    return r ? JSON.parse(r.value) : fallback;
  };
  const set = (k, v) =>
    db
      .prepare(
        "INSERT INTO settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
      )
      .run(k, JSON.stringify(v));
  const audit = (a, t = "") =>
    db
      .prepare("INSERT INTO audit(action,target,ts) VALUES(?,?,?)")
      .run(a, String(t), Date.now());
  const limit = (bucket, max, ms) =>
    db.transaction(() => {
      if (
        db
          .prepare("SELECT count(*) n FROM limits WHERE bucket=? AND ts>?")
          .get(bucket, Date.now() - ms).n >= max
      )
        return false;
      db.prepare("INSERT INTO limits VALUES(?,?)").run(bucket, Date.now());
      return true;
    })();
  const owner = (id) =>
    db.prepare("SELECT * FROM owners WHERE chat_id=?").get(id);
  const carByOwner = (id) =>
    db.prepare("SELECT * FROM cars WHERE chat_id=?").get(id);
  const carByToken = (token) =>
    typeof token === "string" && token.length >= 32 && token.length <= 256
      ? db.prepare("SELECT * FROM cars WHERE token_hash=?").get(hash(token))
      : null;
  const allowed = (c) =>
    !!(
      c?.chat_id &&
      !c.blocked &&
      owner(c.chat_id) &&
      !owner(c.chat_id).blocked &&
      !get("botPaused", false)
    );
  const code = (chatId) =>
    db.transaction(() => {
      if (owner(chatId)?.blocked) throw new Error("Власника заблоковано");
      const value = String(randomInt(100000, 1000000));
      db.prepare("UPDATE pair_codes SET used=1 WHERE chat_id=?").run(chatId);
      db.prepare("INSERT OR REPLACE INTO pair_codes VALUES(?,?,?,0)").run(
        hash(value),
        chatId,
        Date.now() + 600_000,
      );
      return value;
    })();
  const pair = (value) =>
    db.transaction(() => {
      const r = db
        .prepare(
          "SELECT * FROM pair_codes WHERE code=? AND used=0 AND expires_at>?",
        )
        .get(hash(value), Date.now());
      if (!r || owner(r.chat_id)?.blocked || carByOwner(r.chat_id)) return null;
      const token = secret();
      db.prepare("UPDATE pair_codes SET used=1 WHERE code=?").run(r.code);
      const info = db
        .prepare(
          "INSERT INTO cars(token_hash,chat_id,created_at,last_seen) VALUES(?,?,?,?)",
        )
        .run(hash(token), r.chat_id, Date.now(), Date.now());
      return {
        car: db
          .prepare("SELECT * FROM cars WHERE id=?")
          .get(info.lastInsertRowid),
        token,
      };
    })();
  const adFree = (id) =>
    !!db
      .prepare("SELECT 1 FROM entitlements WHERE chat_id=? AND active=1")
      .get(id);
  const cleanup = () =>
    db.transaction(() => {
      const n = Date.now();
      for (const [table, col, age] of [
        ["sessions", "expires", 0],
        ["login_codes", "expires", 0],
        ["offers", "expires", 0],
        ["pair_codes", "expires_at", 0],
        ["limits", "ts", 86400_000],
        ["updates", "ts", 7 * 86400_000],
        ["logs", "ts", 30 * 86400_000],
        ["audit", "ts", 180 * 86400_000],
      ])
        db.prepare(`DELETE FROM ${table} WHERE ${col}<?`).run(n - age);
    })();
  return {
    db,
    get,
    set,
    audit,
    limit,
    owner,
    carByOwner,
    carByToken,
    allowed,
    code,
    pair,
    adFree,
    cleanup,
  };
}
