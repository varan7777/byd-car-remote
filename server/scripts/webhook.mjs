import "dotenv/config";
import { configuration } from "../src/app.js";
import { telegramCall } from "../src/bot.js";
const c = configuration();
if (!c.botToken || !c.webhookSecret)
  throw new Error("BOT_TOKEN and WEBHOOK_SECRET are required");
const remove = process.argv.includes("--remove");
await telegramCall(
  c.botToken,
  remove ? "deleteWebhook" : "setWebhook",
  remove
    ? { drop_pending_updates: true }
    : {
        url: `${c.publicUrl}/tg/byd`,
        secret_token: c.webhookSecret,
        allowed_updates: ["message", "callback_query"],
        drop_pending_updates: true,
      },
);
console.log(
  remove
    ? "BYD webhook removed."
    : "BYD webhook registered. Stop old APK polling before switching. Main Varan bot unchanged.",
);
