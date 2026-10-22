import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import request from "supertest";
import { WebSocket } from "ws";
import { createHub } from "../src/app.js";
import { hash } from "../src/db.js";
import { siteSchema } from "../src/content.js";

const origin = "http://127.0.0.1:8080";
function setup(t, extra = {}) {
  let code = "";
  const dir = mkdtempSync(join(tmpdir(), "varan-test-"));
  const h = createHub({
    config: {
      publicUrl: origin,
      production: false,
      sessionSecret: "test-secret-with-at-least-32-characters",
      authBotToken: "test-auth-bot",
      adminChatId: "123",
      dbPath: ":memory:",
      mediaRoot: join(dir, "media"),
      botToken: "",
      ...extra.config,
    },
    bot: null,
    sendCode: async (text) => {
      code = text.match(/: (\d{6})/)[1];
    },
    ...extra,
  });
  t.after(() => {
    h.close();
    rmSync(dir, { recursive: true, force: true });
  });
  const agent = request.agent(h.app);
  const login = async () => {
    await agent.get("/admin/api/auth/session").expect(200);
    await agent
      .post("/admin/api/auth/request-code")
      .set("Origin", origin)
      .send({})
      .expect(200);
    const r = await agent
      .post("/admin/api/auth/verify")
      .set("Origin", origin)
      .send({ code })
      .expect(200);
    return r.body.csrf;
  };
  return { h, agent, login, code: () => code };
}
function owner(h, id) {
  h.store.db
    .prepare("INSERT INTO owners(chat_id,name,created_at) VALUES(?,?,?)")
    .run(id, `Owner ${id}`, Date.now());
}

test("OTP is browser-bound, single-use, hashed, CSRF-protected; logout revokes session", async (t) => {
  const { h, agent, login, code } = setup(t);
  const csrf = await login();
  const row = h.store.db.prepare("SELECT * FROM login_codes").get();
  assert.notEqual(row.digest, code());
  assert.equal(row.used, 1);
  await request(h.app)
    .post("/admin/api/auth/verify")
    .set("Origin", origin)
    .send({ code: code() })
    .expect(401);
  await agent.get("/admin/api/overview").expect(200);
  await agent
    .put("/admin/api/bot")
    .set("Origin", origin)
    .send({ paused: true, welcome: "hello" })
    .expect(403);
  await agent
    .put("/admin/api/bot")
    .set("Origin", "https://evil.example")
    .set("X-CSRF-Token", csrf)
    .send({ paused: true, welcome: "hello" })
    .expect(403);
  await agent
    .put("/admin/api/bot")
    .set("Origin", origin)
    .set("X-CSRF-Token", csrf)
    .send({ paused: true, welcome: "hello" })
    .expect(200);
  await agent
    .post("/admin/api/auth/logout")
    .set("Origin", origin)
    .set("X-CSRF-Token", csrf)
    .send({})
    .expect(200);
  await agent.get("/admin/api/overview").expect(401);
});
test("OTP expires and locks after five guesses; send failures never authenticate", async (t) => {
  const { h, agent, code } = setup(t);
  await agent
    .post("/admin/api/auth/request-code")
    .set("Origin", origin)
    .send({})
    .expect(200);
  for (let i = 0; i < 5; i++)
    await agent
      .post("/admin/api/auth/verify")
      .set("Origin", origin)
      .send({ code: "abcdef" })
      .expect(401);
  await agent
    .post("/admin/api/auth/verify")
    .set("Origin", origin)
    .send({ code: code() })
    .expect(401);
  h.store.db
    .prepare("UPDATE login_codes SET attempts=0,expires=?")
    .run(Date.now() - 1);
  await agent
    .post("/admin/api/auth/verify")
    .set("Origin", origin)
    .send({ code: code() })
    .expect(401);
});
test("OTP cooldown and failed delivery cannot produce a valid challenge", async (t) => {
  const { h, agent } = setup(t, {
    sendCode: async () => {
      throw new Error("Network failure");
    },
  });
  await agent
    .post("/admin/api/auth/request-code")
    .set("Origin", origin)
    .send({})
    .expect(503);
  assert.equal(
    h.store.db.prepare("SELECT used FROM login_codes").get().used,
    1,
  );
  await agent
    .post("/admin/api/auth/request-code")
    .set("Origin", origin)
    .send({})
    .expect(429);
});
test("CMS draft isolation, conflict detection, publication and restoration", async (t) => {
  const { h, agent, login } = setup(t),
    csrf = await login();
  const original = h.content.published(),
    draft = h.content.draft();
  draft.content.home.name = "Changed <script>";
  await agent
    .put("/admin/api/site")
    .set("Origin", origin)
    .set("X-CSRF-Token", csrf)
    .send(draft)
    .expect(200);
  assert.equal(h.content.published().home.name, original.home.name);
  await agent
    .put("/admin/api/site")
    .set("Origin", origin)
    .set("X-CSRF-Token", csrf)
    .send(draft)
    .expect(409);
  await agent
    .post("/admin/api/site/publish")
    .set("Origin", origin)
    .set("X-CSRF-Token", csrf)
    .send({})
    .expect(200);
  assert.equal(h.content.published().home.name, "Changed <script>");
  await agent
    .post("/admin/api/site/restore")
    .set("Origin", origin)
    .set("X-CSRF-Token", csrf)
    .send({ id: original.revision })
    .expect(200);
  assert.equal(h.content.draft().content.home.name, original.home.name);
  assert.equal(h.content.published().home.name, "Changed <script>");
});
test("CMS rejects executable links, coordinate collisions and duplicate IDs", (t) => {
  const { h } = setup(t),
    s = h.content.draft().content;
  s.home.services[0].url = "javascript:alert(1)";
  assert.equal(siteSchema.safeParse(s).success, false);
  s.home.services[0].url = "https://example.com";
  s.home.services[0].q = 0;
  s.home.services[0].r = 0;
  assert.equal(siteSchema.safeParse(s).success, false);
  delete s.home.services[0].q;
  delete s.home.services[0].r;
  s.home.services[1].id = s.home.services[0].id;
  assert.equal(siteSchema.safeParse(s).success, false);
});
test("pair codes cannot be reused and one owner has at most one car; tokens never persist in plaintext", (t) => {
  const { h } = setup(t);
  owner(h, 101);
  owner(h, 202);
  const code = h.store.code(101),
    result = h.store.pair(code);
  assert.ok(result);
  assert.equal(h.store.pair(code), null);
  assert.equal(h.store.pair(h.store.code(101)), null);
  assert.ok(h.store.pair(h.store.code(202)));
  assert.equal(h.store.carByToken(result.token).chat_id, 101);
  assert.equal(h.store.carByOwner(101).car_token, null);
  assert.equal(h.store.carByOwner(101).token_hash, hash(result.token));
});
async function socket(h) {
  if (!h.server.listening)
    await new Promise((r) => h.server.listen(0, "127.0.0.1", r));
  const ws = new WebSocket(`ws://127.0.0.1:${h.server.address().port}/car`),
    queue = [],
    waiters = [];
  ws.on("message", (raw) => {
    const m = JSON.parse(raw);
    if (waiters.length) waiters.shift()(m);
    else queue.push(m);
  });
  await new Promise((r, j) => {
    ws.once("open", r);
    ws.once("error", j);
  });
  return {
    ws,
    send: (m) => ws.send(JSON.stringify(m)),
    next: () =>
      queue.length
        ? Promise.resolve(queue.shift())
        : new Promise((r, j) => {
            const timer = setTimeout(
              () => j(Error("Socket message timed out")),
              2000,
            );
            waiters.push((m) => {
              clearTimeout(timer);
              r(m);
            });
          }),
  };
}
test("two cars are isolated; replacement sockets and unlinking revoke access immediately", async (t) => {
  const { h, agent, login } = setup(t);
  owner(h, 101);
  owner(h, 202);
  const a = h.store.pair(h.store.code(101)),
    b = h.store.pair(h.store.code(202));
  const sa = await socket(h),
    sb = await socket(h);
  t.after(() => {
    sa.ws.terminate();
    sb.ws.terminate();
  });
  sa.send({ type: "hello", carToken: a.token });
  assert.equal((await sa.next()).chatId, 101);
  await sa.next();
  sb.send({ type: "hello", carToken: b.token });
  await sb.next();
  await sb.next();
  assert.equal(h.gateway.sendCmd(101, "/status"), true);
  assert.equal((await sa.next()).text, "/status");
  const replacement = await socket(h);
  t.after(() => replacement.ws.terminate());
  replacement.send({ type: "hello", carToken: a.token });
  await replacement.next();
  await replacement.next();
  const csrf = await login();
  await agent
    .post("/admin/api/cars/" + a.car.id)
    .set("Origin", origin)
    .set("X-CSRF-Token", csrf)
    .send({ action: "unlink" })
    .expect(200);
  assert.equal(h.gateway.sendCmd(101, "unlock"), false);
  assert.equal(h.store.carByToken(a.token), undefined);
  assert.equal(h.gateway.sendCmd(202, "/ping"), true);
  assert.equal((await sb.next()).text, "/ping");
});
test("missing or forged Telegram webhook secrets are refused and duplicates ignored", async (t) => {
  let calls = 0;
  const fake = {
    bot: {
      handleUpdate: async () => {
        calls++;
      },
    },
    send: async () => {},
  };
  const { h } = setup(t, {
    config: {
      publicUrl: origin,
      production: false,
      sessionSecret: "x".repeat(40),
      authBotToken: "test",
      adminChatId: "123",
      dbPath: ":memory:",
      mediaRoot: join(tmpdir(), "varan-hook-test"),
      webhookSecret: "webhook-secret",
    },
    bot: fake,
  });
  await request(h.app).post("/tg/byd").send({ update_id: 1 }).expect(403);
  for (let i = 0; i < 2; i++)
    await request(h.app)
      .post("/tg/byd")
      .set("X-Telegram-Bot-Api-Secret-Token", "webhook-secret")
      .send({ update_id: 1 })
      .expect(200);
  assert.equal(calls, 1);
});

test("MonoPay: signed success grants once; bad signatures, wrong amounts, foreign orders and stale statuses do not", async (t) => {
  const { publicKey, privateKey } = generateKeyPairSync("ec", {
    namedCurve: "prime256v1",
  });
  let invoices = 0,
    invoiceBody;
  const fetcher = async (url, opts) => {
    if (url.endsWith("/pubkey"))
      return {
        ok: true,
        json: async () => ({
          key: Buffer.from(
            publicKey.export({ type: "spki", format: "pem" }),
          ).toString("base64"),
        }),
      };
    if (url.endsWith("/invoice/create")) {
      invoices++;
      invoiceBody = JSON.parse(opts.body);
      return {
        ok: true,
        json: async () => ({
          invoiceId: "invoice-1",
          pageUrl: "https://pay.monobank.ua/invoice-1",
        }),
      };
    }
    throw Error("Unexpected network call");
  };
  const { h } = setup(t, {
    config: {
      publicUrl: origin,
      production: false,
      sessionSecret: "x".repeat(40),
      dbPath: ":memory:",
      mediaRoot: join(tmpdir(), "varan-pay-test"),
      monoToken: "test-mono",
    },
    fetcher,
  });
  owner(h, 101);
  owner(h, 202);
  h.store.set("adFreePrice", 29900);
  const token = h.commerce.offer(101),
    other = h.commerce.offer(202),
    p = await h.commerce.checkout(token);
  assert.equal(invoices, 1);
  assert.equal(invoiceBody.amount, 29900);
  assert.equal(invoiceBody.ccy, 980);
  assert.equal(h.store.adFree(101), false);
  const payload = {
    invoiceId: "invoice-1",
    reference: p.id,
    amount: 29900,
    ccy: 980,
    status: "success",
    modifiedDate: "2026-10-22T10:00:00Z",
  };
  const send = async (data, bad = false) => {
    const body = Buffer.from(JSON.stringify(data)),
      signature = bad
        ? "invalid"
        : sign("sha256", body, privateKey).toString("base64");
    return request(h.app)
      .post("/api/billing/mono/webhook")
      .set("Content-Type", "application/json")
      .set("X-Sign", signature)
      .send(body.toString());
  };
  assert.equal((await send(payload, true)).status, 403);
  assert.equal(h.store.adFree(101), false);
  const mismatch = await send({ ...payload, amount: 100 });
  assert.equal(mismatch.status, 400);
  assert.equal(h.store.adFree(101), false);
  assert.equal((await send(payload)).status, 200);
  assert.equal((await send(payload)).status, 200);
  assert.equal(h.store.adFree(101), true);
  assert.equal(h.store.adFree(202), false);
  assert.equal(
    h.store.db.prepare("SELECT count(*) n FROM entitlements").get().n,
    1,
  );
  assert.equal((await h.commerce.checkout(token)).adFree, true);
  assert.equal(invoices, 1);
  await request(h.app)
    .get("/api/billing/payments/" + p.id)
    .set("Authorization", "Bearer " + other)
    .expect(404);
  await send({
    ...payload,
    status: "failure",
    modifiedDate: "2026-10-22T09:00:00Z",
  });
  assert.equal(h.store.adFree(101), true);
  await send({
    ...payload,
    status: "reversed",
    modifiedDate: "2026-10-22T11:00:00Z",
  });
  assert.equal(h.store.adFree(101), false);
});
test("paid owners receive no app banner; offline and blocked cars cannot read config", async (t) => {
  const { h } = setup(t);
  owner(h, 101);
  const p = h.store.pair(h.store.code(101));
  h.store.set("campaign", {
    enabled: true,
    title: "Banner",
    text: "Small",
    url: "https://example.com",
    imageUrl: "",
    app: true,
    bot: true,
    intervalHours: 24,
    startsAt: 0,
    endsAt: 0,
  });
  const r = await request(h.app)
    .get("/api/car/config")
    .set("Authorization", "Bearer " + p.token)
    .expect(200);
  assert.equal(r.body.banner.title, "Banner");
  h.store.db
    .prepare(
      "INSERT INTO payments(id,chat_id,amount,status,created_at) VALUES('paid',101,100,'success',?)",
    )
    .run(Date.now());
  h.store.db
    .prepare("INSERT INTO entitlements VALUES('paid',101,1,?)")
    .run(Date.now());
  const paid = await request(h.app)
    .get("/api/car/config")
    .set("Authorization", "Bearer " + p.token)
    .expect(200);
  assert.equal(paid.body.banner, null);
  assert.equal(paid.body.adFree, true);
  h.store.db.prepare("UPDATE owners SET blocked=1 WHERE chat_id=101").run();
  await request(h.app)
    .get("/api/car/config")
    .set("Authorization", "Bearer " + p.token)
    .expect(401);
});
test("unauthenticated uploads are blocked; executable SVG is not an allowed image", async (t) => {
  const { h, agent, login } = setup(t);
  await request(h.app)
    .post("/admin/api/media")
    .attach("image", Buffer.from("<svg/>"), "x.svg")
    .expect(401);
  const csrf = await login();
  await agent
    .post("/admin/api/media")
    .set("Origin", origin)
    .set("X-CSRF-Token", csrf)
    .attach(
      "image",
      Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>'),
      "x.svg",
    )
    .expect(400);
});
test('published content, purchases and authenticated sessions survive reopening SQLite',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'varan-persistence-'));
  const config={publicUrl:origin,production:false,sessionSecret:'persistent-session-secret-longer-than-32',authBotToken:'fake',adminChatId:'123',dbPath:join(dir,'hub.db'),mediaRoot:join(dir,'media')};
  let value='',h=createHub({config,bot:null,sendCode:async text=>{value=text.match(/: (\d{6})/)[1];}});
  t.after(()=>{h.close();rmSync(dir,{recursive:true,force:true});});
  const agent=request.agent(h.app);await agent.post('/admin/api/auth/request-code').set('Origin',origin).send({}).expect(200);
  const result=await agent.post('/admin/api/auth/verify').set('Origin',origin).send({code:value}).expect(200);
  const cookie=result.headers['set-cookie'].map(c=>c.split(';')[0]).join('; ');
  const draft=h.content.draft();draft.content.home.name='Persisted';h.content.save(draft.content,draft.revision);h.content.publish();
  owner(h,101);h.store.db.prepare("INSERT INTO payments(id,chat_id,amount,status) VALUES('p',101,100,'success')").run();h.store.db.prepare("INSERT INTO entitlements VALUES('p',101,1,?)").run(Date.now());
  h.close();h=createHub({config,bot:null,sendCode:async()=>{}});
  assert.equal(h.content.published().home.name,'Persisted');assert.equal(h.store.adFree(101),true);
  await request(h.app).get('/admin/api/overview').set('Cookie',cookie).expect(200);
  const session=h.store.db.prepare('SELECT * FROM sessions').get(),data=JSON.parse(session.data);data.authenticatedAt=Date.now()-13*3600_000;
  h.store.db.prepare('UPDATE sessions SET data=? WHERE sid=?').run(JSON.stringify(data),session.sid);
  await request(h.app).get('/admin/api/overview').set('Cookie',cookie).expect(401);
});
test('simultaneous checkout requests create exactly one bank invoice',async t=>{
  let release,entered;const waiting=new Promise(r=>entered=r),gate=new Promise(r=>release=r);let invoices=0;
  const fetcher=async()=>{invoices++;entered();await gate;return{ok:true,json:async()=>({invoiceId:'one',pageUrl:'https://pay.monobank.ua/one'})};};
  const {h}=setup(t,{config:{publicUrl:origin,production:false,sessionSecret:'x'.repeat(40),dbPath:':memory:',mediaRoot:join(tmpdir(),'varan-concurrency-test'),monoToken:'test'},fetcher});
  owner(h,101);h.store.set('adFreePrice',100);const token=h.commerce.offer(101),first=h.commerce.checkout(token);await waiting;
  await assert.rejects(h.commerce.checkout(token),e=>e.status===409);release();await first;
  assert.equal(invoices,1);assert.equal(h.store.db.prepare('SELECT count(*) n FROM payments').get().n,1);
});
test('ambiguous bank timeout prevents repeat charges and can be reconciled against the bank',async t=>{
  let payment;
  const fetcher=async url=>{
    if(url.endsWith('/invoice/create'))throw Error('Timeout after bank may have created invoice');
    return{ok:true,json:async()=>({invoiceId:'recover',reference:payment.id,amount:100,ccy:980,status:'success',modifiedDate:new Date().toISOString()})};
  };
  const {h}=setup(t,{config:{publicUrl:origin,production:false,sessionSecret:'x'.repeat(40),dbPath:':memory:',mediaRoot:join(tmpdir(),'varan-recovery-test'),monoToken:'test'},fetcher});
  owner(h,101);h.store.set('adFreePrice',100);const token=h.commerce.offer(101);
  await assert.rejects(h.commerce.checkout(token),e=>e.status===503);
  payment=h.store.db.prepare('SELECT * FROM payments').get();assert.equal(payment.status,'unknown');
  await assert.rejects(h.commerce.checkout(token),e=>e.status===409);
  await h.commerce.reconcile(payment.id,'recover');assert.equal(h.store.adFree(101),true);
});
