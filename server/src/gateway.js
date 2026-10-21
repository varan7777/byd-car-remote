// WebSocket gateway for the head units.
//
// A car connects once, authenticates with its car_token (or pairs with a code), and then this
// hub relays the owner's Telegram text to it and forwards the car's replies back. The car keeps
// ALL its logic (the existing CommandRouter talks to autoservice locally) — the hub is only a
// secure transport + the pairing/isolation layer. A chat only ever reaches its own paired car.
import { WebSocketServer } from 'ws';
import { codes, cars, logs } from './db.js';

// chat_id -> live ws (one car per owner for now)
const live = new Map();

export function createGateway(httpServer, { onReply }) {
  const wss = new WebSocketServer({ server: httpServer, path: '/car' });

  wss.on('connection', (ws) => {
    ws.carId = null;
    ws.chatId = null;

    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch { return; }

      switch (msg.type) {
        // First-time pairing: car generated a secret carToken and the owner typed the code.
        case 'pair': {
          const chatId = codes.redeem(String(msg.code || ''));
          if (!chatId) return ws.send(JSON.stringify({ type: 'pair_failed', reason: 'bad_or_expired_code' }));
          const car = cars.bind(msg.carToken, chatId);
          attach(ws, car);
          ws.send(JSON.stringify({ type: 'paired', carToken: car.car_token, chatId }));
          onReply(chatId, '✅ Авто привʼязано до цього чату.');
          break;
        }
        // Reconnect: already-paired car authenticates.
        case 'hello': {
          const car = cars.byToken(String(msg.carToken || ''));
          if (!car || !car.chat_id) return ws.send(JSON.stringify({ type: 'unauthorized' }));
          attach(ws, car);
          ws.send(JSON.stringify({ type: 'ready' }));
          break;
        }
        // Car answering a command (or pushing a notification).
        case 'reply': {
          if (!ws.chatId) return;
          cars.seen(ws.carId);
          logs.add(ws.carId, 'out', msg.text || '');
          onReply(ws.chatId, msg.text || '', msg.keyboard);
          break;
        }
        case 'ping': cars.seen(ws.carId); break;
      }
    });

    ws.on('close', () => { if (ws.chatId && live.get(ws.chatId) === ws) live.delete(ws.chatId); });
  });

  function attach(ws, car) {
    ws.carId = car.id; ws.chatId = car.chat_id;
    live.set(car.chat_id, ws);
    cars.seen(car.id);
  }

  return {
    // Relay an owner's text to their paired car. Returns false if the car is offline.
    sendCmd(chatId, text) {
      const ws = live.get(chatId);
      if (!ws || ws.readyState !== ws.OPEN) return false;
      const car = cars.byChat(chatId);
      if (car) logs.add(car.id, 'in', text);
      ws.send(JSON.stringify({ type: 'cmd', text }));
      return true;
    },
    isOnline: (chatId) => live.has(chatId),
  };
}
