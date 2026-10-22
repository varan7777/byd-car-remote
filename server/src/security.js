import { timingSafeEqual, createHmac } from "node:crypto";
import session from "express-session";
export const equal = (a, b) =>
  typeof a === "string" &&
  typeof b === "string" &&
  Buffer.byteLength(a) === Buffer.byteLength(b) &&
  timingSafeEqual(Buffer.from(a), Buffer.from(b));
export const digest = (key, value) =>
  createHmac("sha256", key).update(value).digest("hex");
export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
export class SQLiteSessions extends session.Store {
  constructor(db) {
    super();
    this.db = db;
  }
  get(sid, cb) {
    try {
      const r = this.db
        .prepare("SELECT data FROM sessions WHERE sid=? AND expires>?")
        .get(sid, Date.now());
      cb(null, r ? JSON.parse(r.data) : null);
    } catch (e) {
      cb(e);
    }
  }
  set(sid, data, cb = () => {}) {
    try {
      this.db
        .prepare("INSERT OR REPLACE INTO sessions VALUES(?,?,?)")
        .run(sid, JSON.stringify(data), Date.now() + 1800_000);
      cb();
    } catch (e) {
      cb(e);
    }
  }
  destroy(sid, cb = () => {}) {
    try {
      this.db.prepare("DELETE FROM sessions WHERE sid=?").run(sid);
      cb();
    } catch (e) {
      cb(e);
    }
  }
  touch(sid, data, cb) {
    this.set(sid, data, cb);
  }
}
export function sameOrigin(req, origin) {
  if (req.get("origin") !== origin)
    throw new HttpError(403, "Запит з іншого сайту відхилено");
}
export function rate(store, bucket, max, ms) {
  if (!store.limit(bucket, max, ms))
    throw new HttpError(429, "Забагато спроб. Спробуйте пізніше.");
}
export const asyncRoute = (fn) => (req, res, next) =>
  Promise.resolve(fn(req, res, next)).catch(next);
