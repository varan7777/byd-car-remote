import express from "express";
import multer from "multer";
import sharp from "sharp";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { z } from "zod";
import { secret } from "./db.js";
import { asyncRoute, HttpError } from "./security.js";
import { campaignSchema, defaultCampaign } from "./content.js";

export function adminRouter(
  store,
  content,
  gateway,
  commerce,
  config,
  botInfo,
) {
  const r = express.Router();
  r.use((_req, res, next) => {
    res.set("Cache-Control", "no-store");
    next();
  });
  const page = (req) =>
    Math.max(0, Math.min(100000, Number(req.query.page) || 0)) * 50;
  r.get("/overview", (_req, res) => {
    const cars = store.db
      .prepare("SELECT id,chat_id FROM cars WHERE chat_id IS NOT NULL")
      .all();
    res.json({
      cars: cars.length,
      online: cars.filter((c) => gateway.isOnline(c.chat_id)).length,
      owners: store.db.prepare("SELECT count(*) n FROM owners").get().n,
      adFree: store.db
        .prepare(
          "SELECT count(DISTINCT chat_id) n FROM entitlements WHERE active=1",
        )
        .get().n,
      paused: store.get("botPaused", false),
      botConfigured: !!config.botToken,
      authConfigured: !!config.authBotToken && !!config.adminChatId,
      paymentsEnabled: commerce.enabled(),
      botError: store.get("botError", ""),
    });
  });
  r.get("/cars", (req, res) => {
    const search = `%${String(req.query.search || "").slice(0, 100)}%`;
    const rows = store.db
      .prepare(
        "SELECT c.id,c.chat_id chatId,c.label,c.last_seen lastSeen,c.blocked,o.name owner,o.phone FROM cars c LEFT JOIN owners o ON o.chat_id=c.chat_id WHERE c.chat_id IS NOT NULL AND (coalesce(o.name,'') LIKE ? OR coalesce(c.label,'') LIKE ?) ORDER BY c.last_seen DESC LIMIT 50 OFFSET ?",
      )
      .all(search, search, page(req));
    res.json(
      rows.map((c) => ({
        ...c,
        online: gateway.isOnline(c.chatId),
        adFree: store.adFree(c.chatId),
      })),
    );
  });
  r.get("/owners", (req, res) =>
    res.json(
      store.db
        .prepare(
          "SELECT chat_id id,name,phone,blocked,created_at createdAt FROM owners ORDER BY created_at DESC LIMIT 50 OFFSET ?",
        )
        .all(page(req)),
    ),
  );
  r.post(
    "/owners/:id",
    asyncRoute(async (req, res) => {
      const id = z.coerce.number().int().positive().parse(req.params.id),
        body = z.object({ blocked: z.boolean() }).strict().parse(req.body);
      if (!store.owner(id)) throw new HttpError(404, "Власника не знайдено");
      store.db
        .prepare("UPDATE owners SET blocked=? WHERE chat_id=?")
        .run(Number(body.blocked), id);
      gateway.disconnect(id);
      store.audit("owner.block", id);
      res.json({ ok: true });
    }),
  );
  r.post(
    "/cars/:id",
    asyncRoute(async (req, res) => {
      const id = z.coerce.number().int().positive().parse(req.params.id);
      const c = store.db.prepare("SELECT * FROM cars WHERE id=?").get(id);
      if (!c?.chat_id) throw new HttpError(404, "Авто не знайдено");
      const b = z
        .object({
          action: z.enum(["label", "block", "disconnect", "unlink"]),
          label: z.string().max(100).optional(),
          blocked: z.boolean().optional(),
        })
        .strict()
        .parse(req.body);
      if (b.action === "label")
        store.db
          .prepare("UPDATE cars SET label=? WHERE id=?")
          .run(b.label || "", id);
      if (b.action === "block") {
        if (typeof b.blocked !== "boolean")
          throw new HttpError(400, "Потрібен стан блокування");
        store.db
          .prepare("UPDATE cars SET blocked=? WHERE id=?")
          .run(Number(b.blocked), id);
        gateway.disconnect(c.chat_id);
      }
      if (b.action === "disconnect") gateway.disconnect(c.chat_id);
      if (b.action === "unlink")
        store.db.transaction(() => {
          store.db
            .prepare("UPDATE cars SET chat_id=NULL,token_hash=NULL WHERE id=?")
            .run(id);
          store.db
            .prepare("UPDATE pair_codes SET used=1 WHERE chat_id=?")
            .run(c.chat_id);
          gateway.disconnect(c.chat_id);
        })();
      store.audit(`car.${b.action}`, id);
      res.json({ ok: true });
    }),
  );
  r.get("/logs", (req, res) => {
    const car = Number(req.query.carId) || null;
    res.json(
      store.db
        .prepare(
          "SELECT * FROM logs WHERE (? IS NULL OR car_id=?) ORDER BY id DESC LIMIT 50 OFFSET ?",
        )
        .all(car, car, page(req)),
    );
  });
  r.get("/audit", (req, res) =>
    res.json(
      store.db
        .prepare("SELECT * FROM audit ORDER BY id DESC LIMIT 50 OFFSET ?")
        .all(page(req)),
    ),
  );
  r.get("/site", (_req, res) =>
    res.json({
      ...content.draft(),
      publishedRevision: content.published().revision,
      versions: store.db
        .prepare(
          "SELECT id,published,ts FROM site_versions ORDER BY id DESC LIMIT 30",
        )
        .all(),
    }),
  );
  r.put("/site", (req, res) => {
    content.save(req.body.content, req.body.revision);
    res.json(content.draft());
  });
  r.post("/site/publish", (_req, res) =>
    res.json({ revision: content.publish() }),
  );
  r.post("/site/restore", (req, res) => {
    content.restore(z.number().int().positive().parse(req.body.id));
    res.json(content.draft());
  });
  const upload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 5 * 1024 * 1024, files: 1, fields: 0 },
  });
  r.post(
    "/media",
    upload.single("image"),
    asyncRoute(async (req, res) => {
      if (!req.file) throw new HttpError(400, "Оберіть зображення");
      const img = sharp(req.file.buffer, {
        limitInputPixels: 20_000_000,
        animated: false,
      });
      let meta;
      try {
        meta = await img.metadata();
      } catch {
        throw new HttpError(400, "Неправильне або завелике зображення");
      }
      if (!["jpeg", "png", "webp"].includes(meta.format))
        throw new HttpError(400, "Дозволено JPEG, PNG та WebP");
      const name = `${secret()}.webp`;
      await writeFile(
        join(config.mediaRoot, name),
        await img
          .rotate()
          .resize({
            width: 2560,
            height: 2560,
            fit: "inside",
            withoutEnlargement: true,
          })
          .webp({ quality: 85 })
          .toBuffer(),
        { mode: 0o600 },
      );
      store.audit("media.upload", name);
      res.json({ url: `/media/${name}` });
    }),
  );
  r.get(
    "/bot",
    asyncRoute(async (_req, res) => {
      let info = null,
        error = "";
      try {
        info = await botInfo();
      } catch {
        error = "Не вдалося отримати стан Telegram";
      }
      res.json({
        paused: store.get("botPaused", false),
        welcome: store.get(
          "welcome",
          "Привіт! Поділись своїм номером, щоб отримати код прив’язки авто.",
        ),
        webhook: info,
        error,
      });
    }),
  );
  r.put("/bot", (req, res) => {
    const b = z
      .object({ paused: z.boolean(), welcome: z.string().min(1).max(2000) })
      .strict()
      .parse(req.body);
    store.set("botPaused", b.paused);
    store.set("welcome", b.welcome);
    if (b.paused) gateway.disconnectAll();
    store.audit("bot.settings");
    res.json({ ok: true });
  });
  r.get("/campaign", (_req, res) =>
    res.json({
      campaign: store.get("campaign", defaultCampaign),
      price: store.get("adFreePrice", 0),
      paymentsConfigured: !!config.monoToken,
    }),
  );
  r.put("/campaign", (req, res) => {
    const campaign = campaignSchema.parse(req.body.campaign),
      price = z.number().int().min(0).max(10000000).parse(req.body.price);
    store.set("campaign", campaign);
    store.set("adFreePrice", price);
    store.audit("campaign.save");
    for (const c of store.db
      .prepare("SELECT chat_id FROM cars WHERE chat_id IS NOT NULL")
      .all())
      gateway.refresh(c.chat_id);
    res.json({ ok: true });
  });
  r.get("/payments", (req, res) =>
    res.json(
      store.db
        .prepare(
          "SELECT id,chat_id chatId,amount,status,created_at createdAt FROM payments ORDER BY created_at DESC LIMIT 50 OFFSET ?",
        )
        .all(page(req)),
    ),
  );
  r.post(
    "/payments/:id/refresh",
    asyncRoute(async (req, res) => {
      const p = store.db
        .prepare("SELECT * FROM payments WHERE id=?")
        .get(req.params.id);
      if (!p) throw new HttpError(404, "Платіж не знайдено");
      res.json(await commerce.refresh(p,true));
    }),
  );
  r.post('/payments/:id/reconcile',asyncRoute(async(req,res)=>{
    const invoiceId=z.string().min(1).max(128).regex(/^[A-Za-z0-9_-]+$/).parse(req.body.invoiceId);
    res.json(await commerce.reconcile(req.params.id,invoiceId));
  }));
  return r;
}
