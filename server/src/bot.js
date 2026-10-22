import { Telegraf, Markup } from "telegraf";
import { hash } from "./db.js";

export async function telegramCall(token, method, body, fetcher = fetch) {
  let response;
  try {
    response = await fetcher(`https://api.telegram.org/bot${token}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    throw new Error("Telegram недоступний");
  }
  let result;
  try {
    result = await response.json();
  } catch {
    throw new Error("Помилка відповіді Telegram");
  }
  if (!response.ok || !result.ok) throw new Error("Telegram відхилив запит");
  return result.result;
}
export function createBot(token, store, commerce, relay) {
  if (!token) return null;
  const bot = new Telegraf(token);
  bot.use(async (ctx, next) => {
    if (
      ctx.chat?.type !== "private" ||
      !ctx.from ||
      ctx.chat.id !== ctx.from.id
    )
      return;
    if (store.owner(ctx.from.id)?.blocked) return;
    if (!store.limit(`tg:${ctx.from.id}`, 60, 60_000)) return;
    store.db
      .prepare(
        "INSERT OR IGNORE INTO owners(chat_id,name,created_at) VALUES(?,?,?)",
      )
      .run(
        ctx.from.id,
        [ctx.from.first_name, ctx.from.last_name].filter(Boolean).join(" "),
        Date.now(),
      );
    await next();
  });
  bot.start((ctx) => {
    if (ctx.startPayload === "noads")
      return ctx.reply(
        store.adFree(ctx.from.id)
          ? "✅ Рекламу вже вимкнено назавжди."
          : "Одна оплата — без реклами назавжди в боті та додатку.",
        store.adFree(ctx.from.id)
          ? undefined
          : Markup.inlineKeyboard([
              [
                Markup.button.url(
                  "Без реклами назавжди",
                  commerce.purchaseUrl(ctx.from.id),
                ),
              ],
            ]),
      );
    if (store.carByOwner(ctx.from.id)) {
      if (!relay(ctx.from.id, "/help"))
        return ctx.reply("Авто зараз офлайн. /noads — без реклами назавжди.");
      return;
    }
    return ctx.reply(
      store.get(
        "welcome",
        "Привіт! Поділись своїм номером, щоб отримати код прив’язки авто.",
      ),
      Markup.keyboard([Markup.button.contactRequest("📱 Поділитися номером")])
        .resize()
        .oneTime(),
    );
  });
  bot.command("noads", (ctx) =>
    ctx.reply(
      store.adFree(ctx.from.id)
        ? "✅ Рекламу для вашого облікового запису вимкнено назавжди."
        : "Одна оплата вимикає рекламу назавжди і в боті, і в додатку.",
      store.adFree(ctx.from.id)
        ? undefined
        : Markup.inlineKeyboard([
            [
              Markup.button.url(
                "Без реклами назавжди",
                commerce.purchaseUrl(ctx.from.id),
              ),
            ],
          ]),
    ),
  );
  bot.on("contact", (ctx) => {
    const c = ctx.message.contact;
    if (c.user_id !== ctx.from.id)
      return ctx.reply("Поділись саме своїм номером кнопкою нижче.");
    if (store.get("botPaused", false))
      return ctx.reply("Прив’язку тимчасово призупинено.");
    if (store.carByOwner(ctx.from.id))
      return ctx.reply(
        "Авто вже прив’язано. Для заміни зверніться до підтримки.",
      );
    if (!store.limit(`code:${ctx.from.id}`, 5, 3600_000))
      return ctx.reply("Забагато кодів. Спробуйте пізніше.");
    store.db
      .prepare(
        "INSERT INTO owners(chat_id,phone,name,created_at) VALUES(?,?,?,?) ON CONFLICT(chat_id) DO UPDATE SET phone=excluded.phone,name=excluded.name",
      )
      .run(
        ctx.from.id,
        c.phone_number,
        [ctx.from.first_name, ctx.from.last_name].filter(Boolean).join(" "),
        Date.now(),
      );
    return ctx.reply(
      `Код прив’язки: ${store.code(ctx.from.id)}\nВведи в додатку на голові авто. Діє 10 хвилин.`,
      Markup.removeKeyboard(),
    );
  });
  bot.on("callback_query", async (ctx) => {
    await ctx.answerCbQuery();
    const data = ctx.callbackQuery.data;
    if (typeof data === "string") await command(ctx, data);
  });
  bot.on("text", (ctx) => command(ctx, ctx.message.text));
  async function command(ctx, text) {
    if (!store.carByOwner(ctx.from.id))
      return ctx.reply("Натисни /start, щоб прив’язати авто.");
    if (!relay(ctx.from.id, text.slice(0, 1000)))
      return ctx.reply(
        "Авто офлайн або керування призупинено. Команду не збережено.",
      );
  }
  bot.catch(() => {
    store.set("botError", "Помилка обробки Telegram. Перевірте з’єднання.");
  });
  async function send(chatId, text, keyboard, advertise = false) {
    await bot.telegram.sendMessage(
      chatId,
      text || " ",
      keyboard ? { reply_markup: keyboard } : {},
    );
    // Contextual small banner, never unsolicited bulk delivery or attached to critical alerts.
    const c = commerce.campaign();
    if (
      !advertise ||
      !c?.bot ||
      store.adFree(chatId) ||
      Date.now() - store.get(`adLast:${chatId}`, 0) < c.intervalHours * 3600_000
    )
      return;
    store.set(`adLast:${chatId}`, Date.now());
    const buttons = [];
    if (c.url)
      buttons.push([
        Markup.button.url(
          c.title || "Детальніше",
          new URL(c.url, commerce.purchaseUrl(chatId)).href,
        ),
      ]);
    buttons.push([
      Markup.button.url("Без реклами назавжди", commerce.purchaseUrl(chatId)),
    ]);
    try {
      await bot.telegram.sendMessage(
        chatId,
        `Реклама · ${c.title}\n${c.text}`,
        Markup.inlineKeyboard(buttons),
      );
    } catch {
      store.set(`adLast:${chatId}`, 0);
    }
  }
  return { bot, send };
}
