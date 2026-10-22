import { z } from "zod";
import { HttpError } from "./security.js";
const text = z.string().max(5000);
export const link = z
  .string()
  .max(2048)
  .refine(
    (v) =>
      v === "" || /^https:\/\/[^\s]+$/i.test(v) || /^\/(?!\/)[^\s\\]*$/.test(v),
    "Потрібне HTTPS-посилання або локальний шлях /…",
  );
const image = link.refine((v) => !v || !/[<>"']/.test(v), "Неправильний шлях");
const locale = z
  .object({
    tag: text,
    note: text,
    features: z
      .array(z.object({ icon: z.string().max(20), title: text, text }))
      .max(40),
    footnote: text,
    privacyTitle: text,
    privacy: text,
    steps: z.array(text).max(15),
  })
  .strict();
export const siteSchema = z
  .object({
    home: z
      .object({
        name: text,
        center: z
          .object({
            name: text,
            sub: text,
            mono: z.string().max(8),
            photo: image,
          })
          .strict(),
        services: z
          .array(
            z
              .object({
                id: z.string().regex(/^[a-z0-9-]{1,64}$/),
                name: z.string().max(100),
                sub: z.string().max(300),
                icon: z
                  .enum(["bell", "pdf", "grow", "ai", "phone", "user", "car"])
                  .optional(),
                logo: image.optional(),
                url: link.optional(),
                page: link.optional(),
                ti: z.boolean().optional(),
                visible: z.boolean().default(true),
                q: z.number().int().min(-20).max(20).optional(),
                r: z.number().int().min(-20).max(20).optional(),
              })
              .strict(),
          )
          .max(60),
      })
      .strict(),
    byd: z
      .object({
        title: text,
        version: z.string().max(100),
        apkUrl: link,
        telegramUrl: link,
        githubUrl: link,
        screenshots: z.array(z.object({ src: image, alt: text })).max(20),
        uk: locale,
        en: locale,
      })
      .strict(),
  })
  .strict()
  .superRefine((s, ctx) => {
    const ids = new Set(),
      coords = new Set(["0,0"]);
    s.home.services.forEach((v, i) => {
      if (ids.has(v.id))
        ctx.addIssue({
          code: "custom",
          path: ["home", "services", i, "id"],
          message: "ID повторюється",
        });
      ids.add(v.id);
      if ((v.q === undefined) !== (v.r === undefined))
        ctx.addIssue({
          code: "custom",
          message: "Координати q і r задаються разом",
        });
      if (v.q !== undefined && v.visible) {
        const k = `${v.q},${v.r}`;
        if (coords.has(k))
          ctx.addIssue({ code: "custom", message: "Позиція сервісу зайнята" });
        coords.add(k);
      }
    });
  });
export const campaignSchema = z
  .object({
    enabled: z.boolean(),
    title: z.string().max(100),
    text: z.string().max(240),
    url: link,
    imageUrl: image,
    app: z.boolean(),
    bot: z.boolean(),
    intervalHours: z.number().int().min(6).max(168),
    startsAt: z.number().int().nonnegative(),
    endsAt: z.number().int().nonnegative(),
  })
  .strict()
  .refine(
    (c) => !c.endsAt || c.endsAt > c.startsAt,
    "Завершення має бути після початку",
  );
export const defaultCampaign = {
  enabled: false,
  title: "",
  text: "",
  url: "",
  imageUrl: "",
  app: true,
  bot: true,
  intervalHours: 24,
  startsAt: 0,
  endsAt: 0,
};
export function contentStore(store, seed) {
  if (!store.db.prepare("SELECT 1 FROM site_versions LIMIT 1").get()) {
    const body = JSON.stringify(siteSchema.parse(seed));
    store.db
      .prepare("INSERT INTO site_versions(body,published,ts) VALUES(?,1,?)")
      .run(body, Date.now());
    store.set("siteDraft", JSON.parse(body));
  }
  const published = () => {
    const row = store.db
      .prepare(
        "SELECT * FROM site_versions WHERE published=1 ORDER BY id DESC LIMIT 1",
      )
      .get();
    return { revision: row.id, ...JSON.parse(row.body) };
  };
  return {
    published,
    draft: () => ({
      revision: store.get("draftRevision", 1),
      content: store.get("siteDraft"),
    }),
    save: (body, revision) =>
      store.db.transaction(() => {
        if (revision !== store.get("draftRevision", 1))
          throw new HttpError(
            409,
            "Чернетку змінено в іншій вкладці. Оновіть її.",
          );
        store.set("siteDraft", siteSchema.parse(body));
        store.set("draftRevision", revision + 1);
        store.audit("site.draft");
      })(),
    publish: () =>
      store.db.transaction(() => {
        const body = siteSchema.parse(store.get("siteDraft"));
        store.db.prepare("UPDATE site_versions SET published=0").run();
        const row = store.db
          .prepare("INSERT INTO site_versions(body,published,ts) VALUES(?,1,?)")
          .run(JSON.stringify(body), Date.now());
        store.audit("site.publish", row.lastInsertRowid);
        return row.lastInsertRowid;
      })(),
    restore: (id) => {
      const row = store.db
        .prepare("SELECT body FROM site_versions WHERE id=?")
        .get(id);
      if (!row) throw new HttpError(404, "Версію не знайдено");
      store.set("siteDraft", JSON.parse(row.body));
      store.set("draftRevision", store.get("draftRevision", 1) + 1);
      store.audit("site.restore", id);
    },
  };
}
