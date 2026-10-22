import { WebSocketServer, WebSocket } from "ws";
import { hash, redact } from "./db.js";
import { z } from "zod";
const button = z
  .object({
    text: z.string().max(100),
    callback_data: z.string().min(1).max(64),
  })
  .strict();
export const keyboardSchema = z.union([
  z
    .object({ inline_keyboard: z.array(z.array(button).max(8)).max(12) })
    .strict(),
  z
    .object({
      keyboard: z.array(z.array(z.string().max(100)).max(8)).max(12),
      resize_keyboard: z.boolean().optional(),
      is_persistent: z.boolean().optional(),
    })
    .strict(),
]);
const message = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("pair"),
    code: z.string().regex(/^\d{6}$/),
    carToken: z.string().max(256).optional(),
  }),
  z.object({ type: z.literal("hello"), carToken: z.string().min(32).max(256) }),
  z.object({
    type: z.literal("reply"),
    text: z.string().max(4000),
    keyboard: keyboardSchema.nullable().optional(),
    requestId: z.string().max(100).optional(),
  }),
  z.object({ type: z.literal("ping") }),
]);
export function createGateway(
  server,
  store,
  {
    onReply = () => {},
    configFor = () => ({}),
    clientIp = (req) => req.socket.remoteAddress,
  } = {},
) {
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: 32 * 1024,
    perMessageDeflate: false,
  });
  const live = new Map();
  server.on("upgrade", (req, socket, head) => {
    let path;
    try {
      path = new URL(req.url, "http://localhost").pathname;
    } catch {
      socket.destroy();
      return;
    }
    const ip = hash(clientIp(req) || "unknown");
    if (path !== "/car" || !store.limit(`ws-connect:${ip}`, 60, 60_000)) {
      socket.end("HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n");
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) =>
      wss.emit("connection", ws, ip),
    );
  });
  const send = (ws, data) => {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data));
  };
  const current = (ws) => {
    const car =
      ws.carId &&
      store.db.prepare("SELECT * FROM cars WHERE id=?").get(ws.carId);
    return car &&
      car.chat_id === ws.chatId &&
      car.token_hash === ws.tokenHash &&
      store.allowed(car) &&
      live.get(ws.chatId) === ws
      ? car
      : null;
  };
  function attach(ws, car) {
    live.get(car.chat_id)?.close(4001, "Session replaced");
    ws.carId = car.id;
    ws.chatId = car.chat_id;
    ws.tokenHash = car.token_hash;
    live.set(car.chat_id, ws);
    ws.lastConfig = Date.now();
    clearTimeout(ws.authTimer);
    store.db
      .prepare("UPDATE cars SET last_seen=? WHERE id=?")
      .run(Date.now(), car.id);
  }
  wss.on("connection", (ws, ip) => {
    ws.alive = true;
    ws.authTimer = setTimeout(
      () => ws.close(4003, "Authentication timeout"),
      15000,
    );
    ws.authTimer.unref();
    ws.on("error", () => {});
    ws.on("pong", () => {
      ws.alive = true;
    });
    ws.on("message", (raw) => {
      try {
        if (!store.limit(`ws-message:${ws.carId || ip}`, 120, 60_000)) {
          ws.close(4008, "Rate limited");
          return;
        }
        const parsed = message.safeParse(JSON.parse(raw.toString()));
        if (!parsed.success) {
          ws.close(4002, "Invalid message");
          return;
        }
        const m = parsed.data;
        if (m.type === "pair") {
          if (ws.carId || store.get("botPaused", false)) {
            ws.close(4003, "Pairing unavailable");
            return;
          }
          if (
            !store.limit(`pair:${ip}`, 10, 900_000) ||
            !store.limit("pair-global", 300, 3600_000)
          ) {
            ws.close(4008, "Rate limited");
            return;
          }
          const result = store.pair(m.code);
          if (!result) {
            send(ws, { type: "pair_failed", reason: "bad_or_expired_code" });
            return;
          }
          attach(ws, result.car);
          send(ws, {
            type: "paired",
            carToken: result.token,
            chatId: result.car.chat_id,
          });
          send(ws, configFor(ws.chatId));
          Promise.resolve(
            onReply(ws.chatId, "✅ Авто прив’язано до цього чату."),
          ).catch(() => {});
          return;
        }
        if (m.type === "hello") {
          if (ws.carId) {
            ws.close(4003, "Already authenticated");
            return;
          }
          const car = store.carByToken(m.carToken);
          if (!store.allowed(car)) {
            send(ws, { type: "unauthorized" });
            ws.close(4003, "Unauthorized");
            return;
          }
          attach(ws, car);
          send(ws, { type: "ready", chatId: car.chat_id });
          send(ws, configFor(ws.chatId));
          return;
        }
        const car = current(ws);
        if (!car) {
          ws.close(4003, "Unauthorized");
          return;
        }
        store.db
          .prepare("UPDATE cars SET last_seen=? WHERE id=?")
          .run(Date.now(), car.id);
        if (m.type === "reply") {
          store.db
            .prepare(
              "INSERT INTO logs(car_id,direction,text,ts) VALUES(?,?,?,?)",
            )
            .run(car.id, "out", redact(m.text), Date.now());
          Promise.resolve(
            onReply(ws.chatId, m.text, m.keyboard, !!m.requestId),
          ).catch(() => {});
        } else {
          send(ws, { type: "pong" });
          if (Date.now() - ws.lastConfig > 3600_000) {
            send(ws, configFor(ws.chatId));
            ws.lastConfig = Date.now();
          }
        }
      } catch {
        ws.close(4002, "Invalid message");
      }
    });
    ws.on("close", () => {
      clearTimeout(ws.authTimer);
      if (live.get(ws.chatId) === ws) live.delete(ws.chatId);
    });
  });
  const beat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.alive) {
        ws.terminate();
        continue;
      }
      ws.alive = false;
      ws.ping();
      if (ws.carId && !current(ws)) ws.close(4003, "Access revoked");
    }
  }, 30000);
  beat.unref();
  return {
    sendCmd(chatId, text) {
      const ws = live.get(chatId);
      if (!ws || !current(ws) || ws.readyState !== WebSocket.OPEN) return false;
      store.db
        .prepare("INSERT INTO logs(car_id,direction,text,ts) VALUES(?,?,?,?)")
        .run(ws.carId, "in", redact(text), Date.now());
      send(ws, { type: "cmd", text, requestId: crypto.randomUUID() });
      return true;
    },
    isOnline: (id) => {
      const ws = live.get(id);
      return !!(ws && ws.readyState === WebSocket.OPEN && current(ws));
    },
    disconnect(id) {
      const ws = live.get(id);
      live.delete(id);
      ws?.close(4003, "Access revoked");
    },
    refresh(id) {
      const ws = live.get(id);
      if (ws && current(ws)) send(ws, configFor(id));
    },
    disconnectAll() {
      for (const id of [...live.keys()]) this.disconnect(id);
    },
    close() {
      clearInterval(beat);
      for (const ws of wss.clients) ws.terminate();
      wss.close();
    },
  };
}
