import express from "express";
import { randomInt } from "node:crypto";
import { secret, hash } from "./db.js";
import {
  asyncRoute,
  digest,
  equal,
  HttpError,
  rate,
  sameOrigin,
} from "./security.js";
export function authRouter(store, config, sendCode) {
  const r = express.Router();
  r.use((_req, res, next) => {
    res.set("Cache-Control", "no-store");
    next();
  });
  r.get("/session", (req, res) => {
    if (
      req.session.authenticatedAt &&
      Date.now() - req.session.authenticatedAt > 12 * 3600_000
    )
      req.session.authenticatedAt = null;
    req.session.csrf ||= secret();
    res.json({
      authenticated: !!req.session.authenticatedAt,
      csrf: req.session.csrf,
      bot: "Varanb2b_bot",
    });
  });
  r.post(
    "/request-code",
    asyncRoute(async (req, res) => {
      sameOrigin(req, config.publicUrl);
      if (!config.authBotToken || !/^\d+$/.test(config.adminChatId || ""))
        throw new HttpError(503, "Доставку кодів ще не налаштовано");
      rate(store, `auth-ip:${hash(req.ip)}`, 10, 3600_000);
      rate(store, "auth-cooldown", 1, 60_000);
      rate(store, "auth-owner-hour", 5, 3600_000);
      const id = secret(),
        value = String(randomInt(100000, 1000000));
      store.db
        .prepare(
          "INSERT INTO login_codes(id,digest,expires,used) VALUES(?,?,?,1)",
        )
        .run(
          id,
          digest(config.sessionSecret, `${id}:${value}`),
          Date.now() + 300_000,
        );
      try {
        await sendCode(
          `Код входу на varan.team: ${value}\nДіє 5 хвилин. Нікому не повідомляйте цей код.\n${new Date().toLocaleString("uk-UA", { timeZone: "Europe/Kyiv" })}`,
        );
      } catch {
        throw new HttpError(
          503,
          "Не вдалося доставити код. Перевірте бота та спробуйте пізніше.",
        );
      }
      store.db.prepare("UPDATE login_codes SET used=1 WHERE used=0").run();
      store.db.prepare("UPDATE login_codes SET used=0 WHERE id=?").run(id);
      req.session.challenge = id;
      res.json({ sent: true, expiresIn: 300, retryAfter: 60 });
    }),
  );
  r.post(
    "/verify",
    asyncRoute(async (req, res) => {
      sameOrigin(req, config.publicUrl);
      rate(store, `auth-verify:${hash(req.ip)}`, 30, 900_000);
      const id = req.session.challenge;
      const ok = store.db.transaction(() => {
        const row =
          id &&
          store.db.prepare("SELECT * FROM login_codes WHERE id=?").get(id);
        if (!row || row.used || row.expires <= Date.now() || row.attempts >= 5)
          return false;
        store.db
          .prepare("UPDATE login_codes SET attempts=attempts+1 WHERE id=?")
          .run(id);
        if (
          !/^\d{6}$/.test(String(req.body.code || "")) ||
          !equal(
            row.digest,
            digest(config.sessionSecret, `${id}:${req.body.code}`),
          )
        )
          return false;
        store.db.prepare("UPDATE login_codes SET used=1 WHERE id=?").run(id);
        return true;
      })();
      if (!ok)
        throw new HttpError(
          401,
          "Код неправильний, прострочений або вичерпано спроби",
        );
      await new Promise((resolve, reject) =>
        req.session.regenerate((e) => (e ? reject(e) : resolve())),
      );
      req.session.authenticatedAt = Date.now();
      req.session.csrf = secret();
      store.audit("login");
      res.json({ authenticated: true, csrf: req.session.csrf });
    }),
  );
  r.post("/logout", (req, res, next) => {
    try {
      sameOrigin(req, config.publicUrl);
      if (!equal(req.session.csrf, req.get("x-csrf-token")))
        throw new HttpError(403, "Оновіть сторінку");
    } catch (e) {
      return next(e);
    }
    req.session.destroy((e) => {
      if (e) return next(e);
      res.clearCookie("varan.sid", {
        path: "/admin",
        sameSite: "strict",
        secure: config.production,
      });
      res.json({ ok: true });
    });
  });
  return r;
}
export function requireAdmin(config) {
  return (req, _res, next) => {
    try {
      if (
        !req.session.authenticatedAt ||
        Date.now() - req.session.authenticatedAt > 12 * 3600_000
      )
        throw new HttpError(401, "Увійдіть в адмінку");
      if (!["GET", "HEAD"].includes(req.method)) {
        sameOrigin(req, config.publicUrl);
        if (!equal(req.session.csrf, req.get("x-csrf-token")))
          throw new HttpError(403, "Оновіть сторінку");
      }
      next();
    } catch (e) {
      next(e);
    }
  };
}
