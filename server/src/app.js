import express from "express";
import session from "express-session";
import helmet from "helmet";
import proxyaddr from "proxy-addr";
import http from "node:http";
import { mkdirSync, readFileSync, existsSync } from "node:fs";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { isIP } from "node:net";
import { ZodError } from "zod";
import { openStore, hash, secret } from "./db.js";
import {
  SQLiteSessions,
  asyncRoute,
  HttpError,
  equal,
  rate,
  sameOrigin,
} from "./security.js";
import { authRouter, requireAdmin } from "./auth.js";
import { contentStore } from "./content.js";
import { createCommerce } from "./commerce.js";
import { createBot, telegramCall } from "./bot.js";
import { createGateway } from "./gateway.js";
import { adminRouter } from "./admin.js";

const root = fileURLToPath(new URL("..", import.meta.url));
export function configuration(env = process.env) {
  const production = env.NODE_ENV === "production";
  if (production && (!env.SESSION_SECRET || env.SESSION_SECRET.length < 32))
    throw new Error("SESSION_SECRET must have at least 32 characters");
  const publicUrl = (env.PUBLIC_URL || "http://127.0.0.1:8080").replace(
    /\/$/,
    "",
  );
  const parsed = new URL(publicUrl);
  if (
    parsed.origin !== publicUrl ||
    (production && parsed.protocol !== "https:")
  )
    throw new Error("PUBLIC_URL must be an origin (HTTPS in production)");
  return {
    production,
    publicUrl,
    sessionSecret: env.SESSION_SECRET || secret(),
    botToken: env.BOT_TOKEN || "",
    webhookSecret: env.WEBHOOK_SECRET || "",
    authBotToken: env.AUTH_BOT_TOKEN || "",
    adminChatId: env.ADMIN_CHAT_ID || "",
    monoToken: env.MONO_TOKEN || "",
    port: Number(env.PORT || 8080),
    dbPath: env.DB_PATH || "./data/hub.db",
    mediaRoot: resolve(env.MEDIA_ROOT || "./data/media"),
    siteRoot: env.SITE_ROOT ? resolve(env.SITE_ROOT) : null,
    trustedProxies: env.TRUSTED_PROXIES || "loopback",
  };
}
export function createHub(options = {}) {
  const config = { ...configuration(), ...options.config };
  const store = options.store || openStore(config.dbPath);
  const fetcher = options.fetcher || fetch;
  mkdirSync(config.mediaRoot, { recursive: true, mode: 0o700 });
  const seed =
    options.seed ||
    JSON.parse(readFileSync(join(root, "seed-site.json"), "utf8"));
  const content = contentStore(store, seed),
    app = express(),
    server = http.createServer(app);
  app.disable("x-powered-by");
  app.set(
    "trust proxy",
    proxyaddr.compile(config.trustedProxies.split(",").map((v) => v.trim())),
  );
  app.use(helmet({ contentSecurityPolicy: false }));
  let gateway;
  const commerce = createCommerce(store, config, {
    fetcher,
    onChange: (id) => gateway?.refresh(id),
  });
  const bot =
    options.bot === undefined
      ? createBot(config.botToken, store, commerce, (id, text) =>
          gateway.sendCmd(id, text),
        )
      : options.bot;
  gateway = createGateway(server, store, {
    onReply: (id, text, keyboard, advertise) =>
      bot?.send(id, text, keyboard, advertise),
    configFor: commerce.payload,
    clientIp: (req) => proxyaddr(req, app.get("trust proxy fn")),
  });
  app.post(
    "/api/billing/mono/webhook",
    express.raw({ type: "application/json", limit: "64kb" }),
    asyncRoute(async (req, res) => {
      rate(store, `mono:${hash(req.ip)}`, 120, 60_000);
      if (!Buffer.isBuffer(req.body)) throw new HttpError(400, "Потрібен JSON");
      await commerce.webhook(req.body, req.get("x-sign"));
      res.json({ ok: true });
    }),
  );
  app.use(express.json({ limit: "512kb" }));
  app.get("/health", (_req, res) => res.json({ ok: true }));
  app.get("/api/site", (_req, res) =>
    res.set("Cache-Control", "public, max-age=30").json(content.published()),
  );
  app.use(
    "/media",
    express.static(config.mediaRoot, {
      immutable: true,
      maxAge: "1y",
      dotfiles: "deny",
      index: false,
    }),
  );
  const locationCache = new Map();
  let geoCount = 0,
    geoDay = "";
  app.get(
    "/api/location",
    asyncRoute(async (req, res) => {
      res.set("Cache-Control", "private, no-store");
      const ip = req.ip;
      if (
        !isIP(ip) ||
        /^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|::1$|f[cd]|fe80:)/i.test(
          ip,
        )
      )
        return res.json(null);
      const k = hash(ip),
        cached = locationCache.get(k);
      if (cached?.until > Date.now()) return res.json(cached.value);
      const day = new Date().toISOString().slice(0, 10);
      if (day !== geoDay) {
        geoDay = day;
        geoCount = 0;
      }
      if (geoCount >= 900) return res.json(null);
      geoCount++;
      let value = null;
      try {
        const r = await fetcher(
            `https://ipwho.is/${encodeURIComponent(ip)}?fields=success,city,latitude,longitude`,
            { signal: AbortSignal.timeout(2000) },
          ),
          data = await r.json();
        if (
          r.ok &&
          data.success &&
          typeof data.city === "string" &&
          Number.isFinite(data.latitude) &&
          Number.isFinite(data.longitude)
        )
          value = {
            city: data.city.slice(0, 100),
            latitude: data.latitude,
            longitude: data.longitude,
          };
      } catch {}
      locationCache.set(k, {
        value,
        until: Date.now() + (value ? 86400_000 : 300_000),
      });
      if (locationCache.size > 2000)
        locationCache.delete(locationCache.keys().next().value);
      res.json(value);
    }),
  );
  const buyerToken = (req) => req.get("authorization")?.replace(/^Bearer /, "");
  app.use("/api/billing", (_req, res, next) => {
    res.set("Cache-Control", "no-store");
    res.set("Referrer-Policy", "no-referrer");
    next();
  });
  app.get("/api/billing/product", (_req, res) =>
    res.json({
      amount: store.get("adFreePrice", 0),
      currency: "UAH",
      enabled: commerce.enabled(),
    }),
  );
  app.get("/api/billing/offer", (req, res) => {
    rate(store, `offer:${hash(req.ip)}`, 60, 60_000);
    const id = commerce.buyer(buyerToken(req));
    res.json({
      adFree: store.adFree(id),
      amount: store.get("adFreePrice", 0),
      currency: "UAH",
      enabled: commerce.enabled(),
    });
  });
  app.post(
    "/api/billing/checkout",
    asyncRoute(async (req, res) => {
      sameOrigin(req, config.publicUrl);
      rate(store, `checkout:${hash(req.ip)}`, 5, 60_000);
      res.json(await commerce.checkout(buyerToken(req)));
    }),
  );
  app.get(
    "/api/billing/payments/:id",
    asyncRoute(async (req, res) => {
      rate(store, `payment-status:${hash(req.ip)}`, 30, 60_000);
      res.json(await commerce.status(buyerToken(req), req.params.id));
    }),
  );
  app.get("/api/car/config", (req, res) => {
    rate(store, `car-config:${hash(req.ip)}`, 60, 60_000);
    const c = store.carByToken(buyerToken(req));
    if (!store.allowed(c)) throw new HttpError(401, "Авто не авторизовано");
    res.set("Cache-Control", "no-store").json(commerce.payload(c.chat_id));
  });
  app.post(
    "/api/car/notification",
    express.urlencoded({ extended: false, limit: "8kb" }),
    asyncRoute(async (req, res) => {
      const c = store.carByToken(buyerToken(req));
      if (!store.allowed(c)) throw new HttpError(401, "Авто не авторизовано");
      rate(store, `car-notification:${c.id}`, 10, 60_000);
      if (
        typeof req.body.text !== "string" ||
        !req.body.text ||
        req.body.text.length > 4000
      )
        throw new HttpError(400, "Неправильний текст");
      if (!bot) throw new HttpError(503, "Бот не налаштований");
      await bot.send(c.chat_id, req.body.text, undefined, false);
      res.json({ ok: true });
    }),
  );
  app.post(
    "/tg/byd",
    asyncRoute(async (req, res) => {
      if (
        !bot ||
        !config.webhookSecret ||
        !equal(req.get("x-telegram-bot-api-secret-token"), config.webhookSecret)
      )
        throw new HttpError(403, "Запит відхилено");
      const id = req.body?.update_id;
      if (!Number.isSafeInteger(id))
        throw new HttpError(400, "Неправильне оновлення");
      if (
        !store.db
          .prepare("INSERT OR IGNORE INTO updates VALUES(?,?)")
          .run(id, Date.now()).changes
      )
        return res.json({ ok: true });
      // At-most-once processing: a Telegram retry must never execute a car command twice.
      await bot.bot.handleUpdate(req.body);
      res.json({ ok: true });
    }),
  );
  app.get(/^\/admin$/, (_req, res) => res.redirect(308, "/admin/"));
  app.use(
    "/admin",
    helmet({
      contentSecurityPolicy: {
        directives: {
          defaultSrc: ["'self'"],
          scriptSrc: ["'self'"],
          styleSrc: ["'self'"],
          imgSrc: ["'self'", "https:", "blob:"],
          fontSrc: ["'self'"],
          frameSrc: ["'self'"],
          formAction: ["'self'"],
          upgradeInsecureRequests: config.production ? [] : null,
        },
      },
    }),
  );
  app.use(
    "/admin/api",
    session({
      name: "varan.sid",
      secret: config.sessionSecret,
      store: new SQLiteSessions(store.db),
      resave: false,
      saveUninitialized: false,
      rolling: true,
      cookie: {
        path: "/admin",
        secure: config.production,
        httpOnly: true,
        sameSite: "strict",
        maxAge: 1800_000,
      },
    }),
  );
  const sendCode =
    options.sendCode ||
    ((text) =>
      telegramCall(
        config.authBotToken,
        "sendMessage",
        { chat_id: config.adminChatId, text },
        fetcher,
      ));
  app.use("/admin/api/auth", authRouter(store, config, sendCode));
  app.use(
    "/admin/api",
    requireAdmin(config),
    adminRouter(store, content, gateway, commerce, config, () =>
      bot ? bot.bot.telegram.getWebhookInfo() : null,
    ),
  );
  app.use(
    "/admin",
    express.static(join(root, "public"), { index: "index.html", maxAge: 0 }),
  );
  if (config.siteRoot && existsSync(config.siteRoot))
    app.use(express.static(config.siteRoot, { maxAge: 0 }));
  app.use((_req, res) => res.status(404).json({ error: "Не знайдено" }));
  app.use((error, _req, res, _next) => {
    const validation = error instanceof ZodError;
    const status = validation
      ? 400
      : error.code === "LIMIT_FILE_SIZE"
        ? 413
        : (error.status >= 400 && error.status < 500) || error.status === 503
          ? error.status
          : 500;
    res.status(status).json({
      error: validation
        ? error.issues
            .map((i) => `${i.path.join(".")}: ${i.message}`)
            .join("; ")
            .slice(0, 500)
        : status === 500
          ? "Не вдалося виконати дію"
          : error.message,
    });
  });
  const cleanup = setInterval(() => store.cleanup(), 3600_000);
  cleanup.unref();
  return {
    app,
    server,
    store,
    content,
    commerce,
    gateway,
    config,
    close: () => {
      clearInterval(cleanup);
      gateway.close();
      server.close();
      store.db.close();
    },
  };
}
