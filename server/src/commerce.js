import { verify } from "node:crypto";
import { secret, hash } from "./db.js";
import { defaultCampaign } from "./content.js";
import { HttpError } from "./security.js";

export function createCommerce(
  store,
  config,
  { fetcher = fetch, onChange = () => {} } = {},
) {
  let publicKey = null;
  const offers = new Map();
  const enabled = () =>
    !!config.monoToken &&
    Number.isSafeInteger(store.get("adFreePrice", 0)) &&
    store.get("adFreePrice", 0) > 0;
  const request = async (path, body) => {
    let r;
    try {
      r = await fetcher(`https://api.monobank.ua${path}`, {
        method: body ? "POST" : "GET",
        headers: {
          "X-Token": config.monoToken,
          "Content-Type": "application/json",
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(12000),
      });
    } catch {
      throw new HttpError(503, "MonoPay тимчасово недоступний");
    }
    if (!r.ok) throw new HttpError(503, "MonoPay тимчасово недоступний");
    return r.json();
  };
  const offer = (chatId) => {
    const prior = offers.get(chatId);
    if (prior && prior.expires > Date.now() + 2 * 3600_000) return prior.token;
    const token = secret(),
      expires = Date.now() + 86400_000;
    store.db
      .prepare("INSERT INTO offers VALUES(?,?,?)")
      .run(hash(token), chatId, expires);
    offers.set(chatId, { token, expires });
    if (offers.size > 10000) offers.delete(offers.keys().next().value);
    return token;
  };
  const buyer = (token) => {
    if (typeof token !== "string" || token.length > 256)
      throw new HttpError(401, "Відкрийте посилання з бота або додатка");
    const row = store.db
      .prepare("SELECT chat_id FROM offers WHERE id=? AND expires>?")
      .get(hash(token), Date.now());
    if (!row || store.owner(row.chat_id)?.blocked)
      throw new HttpError(401, "Посилання прострочене. Отримайте нове в боті.");
    return row.chat_id;
  };
  const purchaseUrl = (chatId) =>
    `${config.publicUrl}/byd/#offer=${offer(chatId)}`;
  const campaign = () => {
    const c = store.get("campaign", defaultCampaign),
      now = Date.now();
    return c.enabled &&
      (!c.startsAt || c.startsAt <= now) &&
      (!c.endsAt || c.endsAt > now)
      ? c
      : null;
  };
  const payload = (chatId) => ({
    type: "config",
    adFree: store.adFree(chatId),
    purchaseUrl: store.adFree(chatId) ? null : purchaseUrl(chatId),
    banner: store.adFree(chatId) ? null : campaign()?.app ? campaign() : null,
  });
  const apply = (state) =>
    store.db.transaction(() => {
      let p = store.db
        .prepare("SELECT * FROM payments WHERE invoice_id=?")
        .get(state.invoiceId);
      // Recover a timed-out invoice/create response using the signed order reference.
      if (!p && typeof state.reference === "string") {
        p = store.db
          .prepare(
            "SELECT * FROM payments WHERE id=? AND invoice_id IS NULL AND status IN ('creating','unknown')",
          )
          .get(state.reference);
        if (p && state.amount === p.amount && state.ccy === 980)
          store.db
            .prepare("UPDATE payments SET invoice_id=? WHERE id=?")
            .run(state.invoiceId, p.id);
        else p = null;
      }
      if (!p) return null;
      if (
        state.amount !== p.amount ||
        state.ccy !== 980 ||
        (state.reference && state.reference !== p.id)
      )
        throw new HttpError(400, "Сума або замовлення не збігаються");
      const modified = Date.parse(
        state.modifiedDate || state.createdDate || "",
      );
      if (!Number.isFinite(modified))
        throw new HttpError(400, "Відсутній час статусу оплати");
      if (modified < p.modified) return p.chat_id;
      if (
        ![
          "created",
          "processing",
          "hold",
          "success",
          "failure",
          "reversed",
          "expired",
        ].includes(state.status)
      )
        return p.chat_id;
      if (
        p.status === "success" &&
        !["success", "reversed"].includes(state.status)
      )
        return p.chat_id;
      if (p.status === "reversed" && state.status !== "reversed")
        return p.chat_id;
      store.db
        .prepare("UPDATE payments SET status=?,modified=? WHERE id=?")
        .run(state.status, modified, p.id);
      if (state.status === "success")
        store.db
          .prepare(
            "INSERT INTO entitlements VALUES(?,?,1,?) ON CONFLICT(payment_id) DO UPDATE SET active=1",
          )
          .run(p.id, p.chat_id, Date.now());
      if (state.status === "reversed")
        store.db
          .prepare("UPDATE entitlements SET active=0 WHERE payment_id=?")
          .run(p.id);
      return p.chat_id;
    })();
  const refresh = async (p, force = false) => {
    if (
      p.invoice_id &&
      (force || !["success", "reversed", "failure", "expired"].includes(p.status))
    ) {
      const state = await request(
        `/api/merchant/invoice/status?invoiceId=${encodeURIComponent(p.invoice_id)}`,
      );
      const id = apply(state);
      if (id) onChange(id);
    }
    return store.db
      .prepare("SELECT id,status FROM payments WHERE id=?")
      .get(p.id);
  };
  const checkout = async (token) => {
    const chatId = buyer(token);
    if (store.adFree(chatId)) return { adFree: true };
    if (!enabled()) throw new HttpError(503, "Оплату ще не налаштовано");
    const pending = store.db
      .prepare(
        "SELECT * FROM payments WHERE chat_id=? AND status IN ('creating','unknown','created','processing','hold') ORDER BY created_at DESC LIMIT 1",
      )
      .get(chatId);
    if (pending) {
      await refresh(pending);
      const current = store.db
        .prepare("SELECT * FROM payments WHERE id=?")
        .get(pending.id);
      if (store.adFree(chatId)) return { adFree: true };
      if (
        current.page_url &&
        ["created", "processing", "hold"].includes(current.status)
      )
        return { id: current.id, pageUrl: current.page_url };
      if (["creating", "unknown"].includes(current.status))
        throw new HttpError(
          409,
          "Попередній платіж перевіряється. Не оплачуйте повторно; зверніться до підтримки.",
        );
    }
    const id = secret(),
      amount = store.get("adFreePrice", 0);
    store.db
      .prepare(
        "INSERT INTO payments(id,chat_id,amount,status,created_at) VALUES(?,?,?,'creating',?)",
      )
      .run(id, chatId, amount, Date.now());
    try {
      const result = await request("/api/merchant/invoice/create", {
        amount,
        ccy: 980,
        merchantPaymInfo: {
          reference: id,
          destination: "Car Remote — без реклами назавжди",
        },
        redirectUrl: `${config.publicUrl}/byd/?payment=${id}#offer=${token}`,
        webHookUrl: `${config.publicUrl}/api/billing/mono/webhook`,
        validity: 3600,
        paymentType: "debit",
      });
      if (
        typeof result.invoiceId !== "string" ||
        !/^https:\/\/pay\.monobank\.ua\//.test(result.pageUrl || "")
      )
        throw new Error("Invalid invoice response");
      store.db
        .prepare(
          "UPDATE payments SET invoice_id=?,page_url=?,status=CASE WHEN status='creating' THEN 'created' ELSE status END WHERE id=?",
        )
        .run(result.invoiceId, result.pageUrl, id);
      return { id, pageUrl: result.pageUrl };
    } catch {
      store.db
        .prepare(
          "UPDATE payments SET status='unknown' WHERE id=? AND status='creating'",
        )
        .run(id);
      throw new HttpError(
        503,
        "Не вдалося підтвердити створення платежу. Не повторюйте оплату до перевірки.",
      );
    }
  };
  const webhook = async (raw, signature) => {
    if (!config.monoToken) throw new HttpError(503, "Оплату ще не налаштовано");
    if (typeof signature !== "string" || signature.length > 512)
      throw new HttpError(403, "Неправильний підпис");
    const valid = () => {
      try {
        return verify(
          "sha256",
          raw,
          publicKey,
          Buffer.from(signature, "base64"),
        );
      } catch {
        return false;
      }
    };
    if (!publicKey) {
      const r = await request("/api/merchant/pubkey");
      publicKey = Buffer.from(r.key, "base64").toString();
    }
    if (!valid()) {
      const r = await request("/api/merchant/pubkey");
      publicKey = Buffer.from(r.key, "base64").toString();
      if (!valid()) throw new HttpError(403, "Неправильний підпис");
    }
    let state;
    try {
      state = JSON.parse(raw.toString());
    } catch {
      throw new HttpError(400, "Неправильний JSON");
    }
    const id = apply(state);
    if (id) onChange(id);
  };
  const status = async (token, id) => {
    const chatId = buyer(token),
      p = store.db
        .prepare("SELECT * FROM payments WHERE id=? AND chat_id=?")
        .get(id, chatId);
    if (!p) throw new HttpError(404, "Платіж не знайдено");
    const state = await refresh(p);
    return { ...state, adFree: store.adFree(chatId) };
  };
  const reconcile = async (id, invoiceId) => {
    const p=store.db.prepare('SELECT * FROM payments WHERE id=?').get(id);
    if(!p)throw new HttpError(404,'Платіж не знайдено');
    const state=await request(`/api/merchant/invoice/status?invoiceId=${encodeURIComponent(invoiceId)}`);
    if(state.invoiceId!==invoiceId || state.reference!==id)throw new HttpError(400,'Рахунок банку належить іншому замовленню');
    const chatId=apply(state);if(chatId)onChange(chatId);
    store.audit('payment.reconcile',id);
    return store.db.prepare('SELECT id,status FROM payments WHERE id=?').get(id);
  };
  return {
    enabled,
    offer,
    buyer,
    purchaseUrl,
    campaign,
    payload,
    checkout,
    webhook,
    status,
    apply,
    refresh,
    reconcile,
  };
}
