// Car Remote hub — entry point.
// Wires the single Telegram bot, the car WebSocket gateway and the admin panel together behind
// one HTTP server. Apache on varan.team reverse-proxies to PORT (keep it on 127.0.0.1).
import 'dotenv/config';
import http from 'node:http';
import express from 'express';
import { createBot, sendToChat } from './bot.js';
import { createGateway } from './gateway.js';
import { adminRouter } from './admin.js';

const PORT = Number(process.env.PORT || 8080);
const app = express();

// Forward-declared so the gateway can post replies through the bot and the bot can relay to cars.
let gateway;
const bot = createBot((chatId, text) => gateway.sendCmd(chatId, text));
const server = http.createServer(app);
gateway = createGateway(server, { onReply: sendToChat(bot) });

// Telegram webhook (Apache proxies https://varan.team/tg/<secret> here).
const hookPath = `/tg/${process.env.BOT_TOKEN?.split(':')[0] || 'hook'}`;
app.use(bot.webhookCallback(hookPath));

app.get('/health', (_req, res) => res.json({ ok: true, online: true }));
app.use('/admin', adminRouter((chatId) => gateway.isOnline(chatId)));

server.listen(PORT, '127.0.0.1', async () => {
  if (process.env.PUBLIC_URL) {
    await bot.telegram.setWebhook(`${process.env.PUBLIC_URL}${hookPath}`).catch((e) => console.error('setWebhook', e.message));
  }
  console.log(`hub on 127.0.0.1:${PORT} (webhook ${hookPath}, ws /car, admin /admin)`);
});

process.once('SIGINT', () => server.close());
process.once('SIGTERM', () => server.close());
