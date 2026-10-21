// The single Telegram bot (@Varanbyd_bot). It never touches a car directly — it only:
//   1) pairs an owner (verify phone via "share contact", issue a 6-digit code), and
//   2) relays the owner's text to THEIR paired car and posts the car's replies back.
import { Telegraf, Markup } from 'telegraf';
import { owners, codes, cars } from './db.js';

export function createBot(relay) {
  const bot = new Telegraf(process.env.BOT_TOKEN);

  bot.start((ctx) =>
    ctx.reply(
      'Привіт! Це бот керування твоїм авто.\n\nЩоб привʼязати авто — поділись номером телефону (кнопка нижче), ' +
        'і я дам код, який треба ввести в додатку на голові авто.',
      Markup.keyboard([Markup.button.contactRequest('📱 Поділитися номером')]).resize().oneTime(),
    ),
  );

  // Owner shared their phone → remember them and hand out a pairing code.
  bot.on('contact', (ctx) => {
    const c = ctx.message.contact;
    if (c.user_id !== ctx.from.id) return ctx.reply('Будь ласка, поділись *своїм* номером.');
    owners.upsert(ctx.from.id, c.phone_number, [ctx.from.first_name, ctx.from.last_name].filter(Boolean).join(' '));
    const code = codes.create(ctx.from.id);
    ctx.reply(
      `Твій код привʼязки: *${code}*\n\nВведи його в додатку на голові авто (діє 10 хв).`,
      { parse_mode: 'Markdown', ...Markup.removeKeyboard() },
    );
  });

  // Any other text = a command for the owner's car.
  bot.on('text', (ctx) => {
    if (!cars.byChat(ctx.from.id)) {
      return ctx.reply('Авто ще не привʼязане. Натисни /start і поділись номером.');
    }
    const delivered = relay(ctx.from.id, ctx.message.text);
    if (!delivered) ctx.reply('🚗 Авто зараз офлайн — команду не доставлено.');
  });

  return bot;
}

// Used by the gateway to post a car's reply back to its owner.
export function sendToChat(bot) {
  return (chatId, text, keyboard) => {
    const extra = keyboard ? Markup.inlineKeyboard(keyboard) : undefined;
    bot.telegram.sendMessage(chatId, text || ' ', extra).catch(() => {});
  };
}
