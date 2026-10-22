/* All server/user strings are escaped or assigned with textContent. No inline handlers. */
const $ = (s) => document.querySelector(s),
  esc = (v) =>
    String(v ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
const state = {
  csrf: "",
  view: "overview",
  page: 0,
  locale: "uk",
  dirty: false,
};
const names = {
  overview: "Огляд",
  site: "Головна",
  byd: "Сторінка BYD",
  bot: "Telegram-бот",
  cars: "Авто",
  owners: "Власники",
  campaign: "Реклама й оплата",
  payments: "Платежі",
  logs: "Журнал команд",
  audit: "Історія змін",
};
const fmt = (n) =>
  n ? new Date(n).toLocaleString("uk-UA", { timeZone: "Europe/Kyiv" }) : "—";
let noticeTimer;
function notice(text, error = false) {
  const n = $("#notice");
  n.textContent = text;
  n.hidden = false;
  n.classList.toggle("error", error);
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => (n.hidden = true), 7000);
}
async function api(path, method = "GET", body) {
  const isFile = body instanceof FormData;
  const r = await fetch("/admin/api" + path, {
    method,
    headers: {
      ...(!isFile && body ? { "Content-Type": "application/json" } : {}),
      "X-CSRF-Token": state.csrf,
    },
    body: body ? (isFile ? body : JSON.stringify(body)) : undefined,
  });
  let data;
  try {
    data = await r.json();
  } catch {
    throw Error("Сервер адмінки ще не підключено або тимчасово недоступний");
  }
  if (!r.ok) {
    if (r.status === 401 && !path.startsWith("/auth")) showLogin();
    throw Error(data.error || "Не вдалося виконати дію");
  }
  return data;
}
function get(path) {
  return path.split(".").reduce((o, k) => o?.[k], state);
}
function set(path, value) {
  const keys = path.split("."),
    last = keys.pop();
  keys.reduce((o, k) => o[k], state)[last] = value;
}
function field(label, path, type = "text", extra = "") {
  const v = get(path),
    id = path.replaceAll(".", "-");
  if (type === "check")
    return `<label><input type="checkbox" data-path="${path}" ${v ? "checked" : ""}>${esc(label)}</label>`;
  return `<div ${extra}><label for="${id}">${esc(label)}</label>${type === "textarea" ? `<textarea id="${id}" data-path="${path}" rows="3">${esc(v)}</textarea>` : `<input id="${id}" data-path="${path}" type="${type}" value="${esc(v)}" ${type === "number" ? 'step="1"' : ""}>`}</div>`;
}
const act = (name, label, extra = "", cls = "") =>
  `<button type="button" data-act="${name}" ${extra} class="${cls}">${label}</button>`;
function heading(title, sub, actions = "") {
  return `<div class="page-head"><div><h1>${esc(title)}</h1><p>${esc(sub)}</p></div><div class="actions">${actions}</div></div>`;
}
function empty(text) {
  return `<div class="empty">${esc(text)}</div>`;
}
function table(headers, rows) {
  return rows.length
    ? `<div class="card"><table><thead><tr>${headers.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`
    : empty(
        "Тут поки порожньо. Дані з’являться після підключення користувачів.",
      );
}
function pages() {
  return `<div class="table-controls"><span class="muted">Сторінка ${state.page + 1} · до 50 записів</span><div class="actions">${act("prev", "← Попередня", state.page === 0 ? "disabled" : "")}${act("next", "Наступна →", state.rowCount < 50 ? "disabled" : "")}</div></div>`;
}
function showLogin() {
  $("#login").hidden = false;
  $("#shell").hidden = true;
}
function showShell() {
  $("#login").hidden = true;
  $("#shell").hidden = false;
}
async function load(view = state.view) {
  state.view = view;
  $("#breadcrumb").textContent = names[view].toUpperCase();
  document
    .querySelectorAll("nav [data-view]")
    .forEach((b) => b.classList.toggle("active", b.dataset.view === view));
  $("#content").innerHTML = '<p class="muted">Завантаження…</p>';
  try {
    if (view === "overview") {
      const d = await api("/overview");
      state.overview = d;
      $("#content").innerHTML =
        heading(
          "Усе під контролем.",
          "Ваш сайт і підключені сервіси — тут і зараз.",
          `<span class="status ${d.paused ? "offline" : "online"}"><i class="dot"></i>${d.paused ? "Бот на паузі" : "Систему запущено"}</span>`,
        ) +
        `<div class="stats">${[
          ["Авто онлайн", d.online, "Зараз на зв’язку"],
          ["Підключених авто", d.cars, "Прив’язки до власників"],
          ["Власників", d.owners, "Особисті Telegram-чати"],
          ["Без реклами", d.adFree, "Назавжди"],
        ]
          .map(
            ([t, n, s]) =>
              `<div class="stat"><small>${t}</small><strong>${n}</strong><small>${s}</small></div>`,
          )
          .join(
            "",
          )}</div><div class="grid"><div class="card feature"><span class="eyebrow">01 / ВАШ ПРОСТІР</span><h2>Сайт, який змінюється з вами.</h2><p>Редагуйте сервіси, тексти та сторінку BYD. Спочатку перегляньте, потім опублікуйте.</p>${act("navigate", "Редагувати головну ↗", 'data-to="site"')}</div><div class="card feature"><span class="eyebrow">02 / CAR REMOTE</span><h2>Одне авто. Один власник.</h2><p>Перевіряйте з’єднання, керуйте прив’язками та дивіться історію команд.</p>${act("navigate", "Підключені авто ↗", 'data-to="cars"')}</div></div><div class="card"><h2>Підключення</h2>${[
          ["BYD-бот", d.botConfigured],
          ["Коди входу", d.authConfigured],
          ["MonoPay і ціна", d.paymentsEnabled],
        ]
          .map(
            ([t, ok]) =>
              `<p><span class="status ${ok ? "online" : "offline"}"><i class="dot"></i>${t}: ${ok ? "налаштовано" : "очікує налаштування"}</span></p>`,
          )
          .join(
            "",
          )}${d.botError ? `<p class="error">${esc(d.botError)}</p>` : ""}</div>`;
    } else if (view === "site" || view === "byd") {
      const d = await api("/site");
      state.draft = d.content;
      state.draftRevision = d.revision;
      state.versions = d.versions;
      state.dirty = false;
      renderSite();
    } else if (view === "bot") {
      state.bot = await api("/bot");
      const b = state.bot;
      $("#content").innerHTML =
        heading(
          "Telegram-бот.",
          "@Varanbyd_bot · прив’язка та керування авто",
        ) +
        `<div class="card"><h2>Робота бота</h2>${field("Призупинити маршрутизацію команд", "bot.paused", "check")}${field("Привітання для нових користувачів", "bot.welcome", "textarea")}<p class="muted">На паузі команди не накопичуються. Поточні з’єднання авто закриваються.</p>${act("save-bot", "Зберегти", "", "primary")}</div><div class="card"><h2>Webhook</h2>${b.error ? `<p class="error">${esc(b.error)}</p>` : b.webhook ? `<p>${esc(b.webhook.url || "Не зареєстрований")}</p><p>Оновлень у черзі: ${Number(b.webhook.pending_update_count) || 0}</p><p>${esc(b.webhook.last_error_message || "Помилок Telegram немає")}</p>` : "<p>Токен бота ще не налаштований.</p>"}<p class="muted">Перемикання webhook виконується окремою серверною командою після зупинки старого опитування Telegram.</p></div>`;
    } else if (view === "campaign") {
      const d = await api("/campaign");
      state.campaign = d.campaign;
      state.price = d.price;
      $("#content").innerHTML =
        heading(
          "Невеликий банер.",
          "Одна кампанія для додатка й бота. Одна оплата — без реклами назавжди.",
        ) +
        `<div class="grid"><div class="card"><h2>Кампанія</h2>${field("Кампанію ввімкнено", "campaign.enabled", "check")}${field("Заголовок", "campaign.title")}${field("Короткий текст", "campaign.text", "textarea")}${field("Посилання", "campaign.url")}${field("Зображення для додатка", "campaign.imageUrl")}${upload("campaign.imageUrl")}${field("Показувати в додатку", "campaign.app", "check")}${field("Показувати в Telegram", "campaign.bot", "check")}${field("Інтервал у боті, годин (6–168)", "campaign.intervalHours", "number")}<label>Початок</label><input type="datetime-local" data-date="campaign.startsAt" value="${dateInput(state.campaign.startsAt)}"><label>Завершення</label><input type="datetime-local" data-date="campaign.endsAt" value="${dateInput(state.campaign.endsAt)}"></div><div><div class="card"><h2>Без реклами назавжди</h2><p>Оплата закріплюється за Telegram-власником і діє одночасно в додатку та боті.</p><label for="price">Разова ціна, грн</label><input id="price" type="number" min="0" step="0.01" data-money value="${d.price / 100}"><p class="warning">${d.paymentsConfigured ? "Токен MonoPay налаштований. Для запуску оплати задайте ціну більше нуля." : "Потрібен MONO_TOKEN на сервері. До налаштування токена й ціни списання вимкнені."}</p></div><div class="card"><h2>Приклад банера</h2><div class="banner-preview"><small>РЕКЛАМА</small><strong id="banner-title">${esc(d.campaign.title || "Заголовок кампанії")}</strong><p id="banner-text">${esc(d.campaign.text || "Ваш короткий текст")}</p><span class="muted">Без реклами назавжди ↗</span></div><p class="muted">Бот показує банер після відповіді на команду, з установленим інтервалом. Масових розсилок немає.</p></div></div></div><div class="form-footer"><small>Зміни одразу надійдуть підключеним авто.</small>${act("save-campaign", "Зберегти кампанію", "", "primary")}</div>`;
    } else await loadTable(view);
  } catch (e) {
    $("#content").innerHTML =
      heading(names[view], "") +
      `<div class="card"><p class="error">${esc(e.message)}</p>${act("refresh", "Повторити")}</div>`;
  }
}
function dateInput(n) {
  if (!n) return "";
  const d = new Date(n);
  return new Date(n - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
function upload(path) {
  return `<div class="upload"><input type="file" accept="image/jpeg,image/png,image/webp" data-upload="${path}" aria-label="Завантажити зображення"></div>`;
}
function renderSite() {
  const home = state.view === "site",
    b = state.draft.byd,
    l = state.locale;
  let html = heading(
    home ? "Ваші сервіси." : "Car Remote.",
    "Чернетка доступна лише вам. На сайті зміни з’являться після публікації.",
    act("preview", "Переглянути ↗"),
  );
  if (home) {
    html += `<div class="card"><h2>Профіль</h2><div class="field-grid">${field("Ім’я у верхній частині", "draft.home.name")}${field("Назва в центрі", "draft.home.center.name")}${field("Підпис", "draft.home.center.sub")}${field("Ініціали", "draft.home.center.mono")}${field("Фото", "draft.home.center.photo")}</div>${upload("draft.home.center.photo")}</div><div class="page-head"><h2>Сервіси</h2>${act("add-service", "+ Додати сервіс")}</div>`;
    state.draft.home.services.forEach((s, i) => {
      const p = `draft.home.services.${i}`;
      html += `<div class="card"><div class="service-head"><h3>${i + 1}. ${esc(s.name)}</h3><div>${act("move-service", "↑", `data-index="${i}" data-delta="-1"`)}${act("move-service", "↓", `data-index="${i}" data-delta="1"`)}${act("delete-service", "Прибрати", `data-index="${i}"`, "danger")}</div></div><div class="field-grid">${field("Назва", p + ".name")}${field("Підпис", p + ".sub")}${field("Зовнішнє посилання", p + ".url")}${field("Сторінка на сайті (/byd/)", p + ".page")}${field("Логотип", p + ".logo")}<div><label>Іконка</label><select data-path="${p}.icon">${["bell", "pdf", "grow", "ai", "phone", "user", "car"].map((k) => `<option ${s.icon === k ? "selected" : ""}>${k}</option>`).join("")}</select></div></div>${upload(p + ".logo")}${field("Показувати", p + ".visible", "check")}${field("Світлий титановий кружечок", p + ".ti", "check")}</div>`;
    });
  } else {
    html += `<div class="card"><h2>Загальне</h2><div class="field-grid">${field("Заголовок", "draft.byd.title")}${field("Версія й платформа", "draft.byd.version")}${field("Посилання на APK", "draft.byd.apkUrl")}${field("Telegram", "draft.byd.telegramUrl")}${field("GitHub", "draft.byd.githubUrl")}</div></div><div class="locale-tabs">${act("locale", "Українська", 'data-locale="uk"', l === "uk" ? "primary" : "")}${act("locale", "English", 'data-locale="en"', l === "en" ? "primary" : "")}</div><div class="card"><h2>${l === "uk" ? "Українські тексти" : "English content"}</h2>${field("Підзаголовок", `draft.byd.${l}.tag`, "textarea")}${field("Примітка ADB", `draft.byd.${l}.note`, "textarea")}${field("Заголовок приватності", `draft.byd.${l}.privacyTitle`)}${field("Приватність", `draft.byd.${l}.privacy`, "textarea")}${field("Примітка до можливостей", `draft.byd.${l}.footnote`, "textarea")}</div><div class="card"><h2>Можливості</h2>`;
    b[l].features.forEach((f, i) => {
      const p = `draft.byd.${l}.features.${i}`;
      html += `<div class="repeat-row"><div class="field-grid">${field("Значок", p + ".icon")}${field("Назва", p + ".title")}</div>${field("Опис", p + ".text", "textarea")}${act("remove-item", "Прибрати", `data-path="draft.byd.${l}.features" data-index="${i}"`, "danger")}</div>`;
    });
    html += `${act("add-feature", "+ Можливість")}</div><div class="card"><h2>Встановлення</h2>`;
    b[l].steps.forEach(
      (_s, i) =>
        (html +=
          field(`Крок ${i + 1}`, `draft.byd.${l}.steps.${i}`, "textarea") +
          act(
            "remove-item",
            "Прибрати",
            `data-path="draft.byd.${l}.steps" data-index="${i}"`,
            "danger",
          )),
    );
    html += `${act("add-step", "+ Крок")}</div><div class="card"><h2>Скриншоти</h2>`;
    b.screenshots.forEach((s, i) => {
      const p = `draft.byd.screenshots.${i}`;
      html += `<div class="repeat-row"><img class="image-preview" src="${esc(s.src)}" alt="">${field("Зображення", p + ".src")}${upload(p + ".src")}${field("Підпис зображення", p + ".alt")}${act("remove-item", "Прибрати", `data-path="draft.byd.screenshots" data-index="${i}"`, "danger")}</div>`;
    });
    html += `${act("add-shot", "+ Скриншот")}</div>`;
  }
  html += `<div class="card"><h2>Історія публікацій</h2><select id="version">${state.versions.map((v) => `<option value="${v.id}">Версія ${v.id} · ${fmt(v.ts)}${v.published ? " · на сайті" : ""}</option>`).join("")}</select><div class="spacer"></div>${act("restore", "Відновити в чернетку")}</div><div class="form-footer"><small>Чернетка та публікація — окремі кроки.</small><div class="actions">${act("save-site", "Зберегти чернетку")}${act("publish", "Опублікувати", "", "primary")}</div></div>`;
  $("#content").innerHTML = html;
}
async function loadTable(view) {
  const suffix = `?page=${state.page}${view === "cars" ? "&search=" + encodeURIComponent(state.search || "") : view === "logs" && state.carFilter ? "&carId=" + encodeURIComponent(state.carFilter) : ""}`;
  const data = await api("/" + view + suffix);
  state.rowCount = data.length;
  let headers, rows;
  if (view === "cars") {
    headers = ["Авто / власник", "Статус", "Активність", "Керування"];
    rows = data.map((c) => [
      `${esc(c.label || "#" + c.id)}<br><small>${esc(c.owner || "Без імені")} · ${esc(c.phone || "—")}</small>${c.adFree ? '<br><span class="badge">Без реклами назавжди</span>' : ""}`,
      `<span class="status ${c.online ? "online" : "offline"}"><i class="dot"></i>${c.blocked ? "Заблоковано" : c.online ? "Онлайн" : "Офлайн"}</span>`,
      esc(fmt(c.lastSeen)),
      `${act("car", "Назва", `data-id="${c.id}" data-action="label"`)}${act("car", c.blocked ? "Розблокувати" : "Блокувати", `data-id="${c.id}" data-action="block" data-blocked="${!c.blocked}"`)}${act("car", "Від’єднати", `data-id="${c.id}" data-action="disconnect"`)}${act("car", "Відв’язати", `data-id="${c.id}" data-action="unlink"`, "danger")}`,
    ]);
  } else if (view === "owners") {
    headers = ["Власник", "Телефон", "Створено", "Доступ"];
    rows = data.map((o) => [
      esc(o.name || "Без імені"),
      esc(o.phone || "Не підтверджено"),
      esc(fmt(o.createdAt)),
      act(
        "owner",
        o.blocked ? "Розблокувати" : "Заблокувати",
        `data-id="${o.id}" data-blocked="${!o.blocked}"`,
      ),
    ]);
  } else if (view === "payments") {
    headers = ["Замовлення", "Сума", "Статус", "Створено", "Перевірка"];
    rows = data.map((p) => [
      esc(p.id.slice(0, 10)) + "…",
      esc((p.amount / 100).toFixed(2)) + " грн",
      esc(p.status),
      esc(fmt(p.createdAt)),
      act("payment-refresh", "Оновити", `data-id="${esc(p.id)}"`) + (['unknown','creating'].includes(p.status)?act('payment-reconcile','Звірити invoice ID',`data-id="${esc(p.id)}"`):''),
    ]);
  } else if (view === "logs") {
    headers = ["Час", "Авто", "Напрям", "Текст"];
    rows = data.map((l) => [
      esc(fmt(l.ts)),
      "#" + l.car_id,
      l.direction === "in" ? "→ авто" : "← авто",
      `<div class="log-text">${esc(l.text)}</div>`,
    ]);
  } else {
    headers = ["Час", "Дія", "Об’єкт"];
    rows = data.map((l) => [esc(fmt(l.ts)), esc(l.action), esc(l.target)]);
  }
  $("#content").innerHTML =
    heading(
      names[view],
      view === "logs"
        ? "Останні 30 днів · команди не накопичуються для виконання."
        : "",
    ) +
    (view === "cars"
      ? `<div class="table-controls"><input id="search" placeholder="Ім’я або назва авто" value="${esc(state.search || "")}">${act("search", "Знайти")}</div>`
      : view === "logs"
        ? `<div class="table-controls"><input id="car-filter" type="number" placeholder="Номер авто" value="${esc(state.carFilter || "")}">${act("filter-logs", "Фільтрувати")}</div>`
        : "") +
    table(headers, rows) +
    pages();
}
async function saveSite() {
  const result = await api("/site", "PUT", {
    revision: state.draftRevision,
    content: state.draft,
  });
  state.draftRevision = result.revision;
  state.dirty = false;
}
document.addEventListener("input", (e) => {
  const el = e.target;
  if (el.dataset.path) {
    set(
      el.dataset.path,
      el.type === "checkbox"
        ? el.checked
        : el.type === "number"
          ? Number(el.value)
          : el.value,
    );
    state.dirty = true;
  }
  if (el.dataset.date) {
    set(el.dataset.date, el.value ? new Date(el.value).getTime() : 0);
    state.dirty = true;
  }
  if (el.hasAttribute("data-money")) {
    state.price = Math.round(Number(el.value) * 100);
    state.dirty = true;
  }
  if (state.view === "campaign") {
    if ($("#banner-title"))
      $("#banner-title").textContent = state.campaign.title;
    if ($("#banner-text")) $("#banner-text").textContent = state.campaign.text;
  }
});
document.addEventListener("change", async (e) => {
  const path = e.target.dataset.upload;
  if (!path || !e.target.files[0]) return;
  try {
    const f = new FormData();
    f.append("image", e.target.files[0]);
    const result = await api("/media", "POST", f);
    set(path, result.url);
    state.dirty = true;
    const input = document.querySelector(`[data-path="${CSS.escape(path)}"]`);
    if (input) input.value = result.url;
    notice("Зображення завантажено. Збережіть зміни.");
  } catch (err) {
    notice(err.message, true);
  }
});
document.addEventListener("click", async (e) => {
  const nav = e.target.closest("[data-view]");
  if (nav) {
    if (state.dirty && !confirm("Є незбережені зміни. Перейти без збереження?"))
      return;
    state.page = 0;
    state.dirty = false;
    return load(nav.dataset.view);
  }
  const b = e.target.closest("[data-act]");
  if (!b) return;
  const a = b.dataset.act;
  b.disabled = true;
  try {
    if (a === "refresh") await load();
    if (a === "navigate") {
      state.page = 0;
      await load(b.dataset.to);
    }
    if (a === "prev" || a === "next") {
      state.page += a === "next" ? 1 : -1;
      await load();
    }
    if (a === "locale") {
      state.locale = b.dataset.locale;
      renderSite();
    }
    if (a === "save-site") {
      await saveSite();
      notice("Чернетку збережено");
    }
    if (a === "publish") {
      await saveSite();
      await api("/site/publish", "POST", {});
      notice("Зміни опубліковано");
      await load();
    }
    if (a === "restore") {
      if (confirm("Замінити поточну чернетку цією версією?")) {
        await api("/site/restore", "POST", { id: Number($("#version").value) });
        await load();
      }
    }
    if (a === "preview") {
      sessionStorage.setItem("varan.preview", JSON.stringify(state.draft));
      $("#preview-frame").src =
        (state.view === "byd" ? "/byd/" : "/") + "?preview=1";
      $("#preview").showModal();
    }
    if (a === "add-service") {
      state.draft.home.services.push({
        id: "service-" + Date.now(),
        name: "Новий сервіс",
        sub: "",
        icon: "car",
        url: "",
        logo: "",
        page: "",
        ti: false,
        visible: true,
      });
      state.dirty = true;
      renderSite();
    }
    if (a === "delete-service") {
      if (confirm("Прибрати сервіс із чернетки?")) {
        state.draft.home.services.splice(Number(b.dataset.index), 1);
        state.dirty = true;
        renderSite();
      }
    }
    if (a === "move-service") {
      const arr = state.draft.home.services,
        i = Number(b.dataset.index),
        to = i + Number(b.dataset.delta);
      if (to >= 0 && to < arr.length) {
        [arr[i], arr[to]] = [arr[to], arr[i]];
        state.dirty = true;
        renderSite();
      }
    }
    if (a === "add-feature") {
      state.draft.byd[state.locale].features.push({
        icon: "◇",
        title: "Нова можливість",
        text: "",
      });
      state.dirty = true;
      renderSite();
    }
    if (a === "add-step") {
      state.draft.byd[state.locale].steps.push("Новий крок");
      state.dirty = true;
      renderSite();
    }
    if (a === "add-shot") {
      state.draft.byd.screenshots.push({ src: "", alt: "" });
      state.dirty = true;
      renderSite();
    }
    if (a === "remove-item") {
      get(b.dataset.path).splice(Number(b.dataset.index), 1);
      state.dirty = true;
      renderSite();
    }
    if (a === "save-bot") {
      await api("/bot", "PUT", {
        paused: state.bot.paused,
        welcome: state.bot.welcome,
      });
      state.dirty = false;
      notice("Налаштування бота збережено");
    }
    if (a === "save-campaign") {
      await api("/campaign", "PUT", {
        campaign: state.campaign,
        price: state.price,
      });
      state.dirty = false;
      notice("Кампанію збережено");
    }
    if (a === "car") {
      const action = b.dataset.action,
        body = { action };
      if (
        action === "unlink" &&
        !confirm(
          "Відв’язати авто? Його секрет буде відкликано. Потрібна нова прив’язка кодом.",
        )
      )
        return;
      if (action === "label") {
        const label = prompt("Назва авто");
        if (label === null) return;
        body.label = label;
      }
      if (action === "block") body.blocked = b.dataset.blocked === "true";
      await api("/cars/" + b.dataset.id, "POST", body);
      notice("Зміни збережено");
      await load();
    }
    if (a === "owner") {
      await api("/owners/" + b.dataset.id, "POST", {
        blocked: b.dataset.blocked === "true",
      });
      await load();
    }
    if (a === "payment-refresh") {
      await api("/payments/" + b.dataset.id + "/refresh", "POST", {});
      notice("Статус перевірено");
      await load();
    }
    if(a==='payment-reconcile'){
      const invoiceId=prompt('Invoice ID цього платежу з кабінету MonoPay. Сервер перевірить належність замовленню й суму.');
      if(invoiceId){await api('/payments/'+b.dataset.id+'/reconcile','POST',{invoiceId:invoiceId.trim()});notice('Рахунок звірено з банком');await load();}
    }
    if (a === "search") {
      state.search = $("#search").value;
      state.page = 0;
      await load();
    }
    if (a === "filter-logs") {
      state.carFilter = $("#car-filter").value;
      state.page = 0;
      await load();
    }
  } catch (err) {
    notice(err.message, true);
  } finally {
    b.disabled = false;
  }
});
$("#request-code").addEventListener("click", async () => {
  const b = $("#request-code");
  b.disabled = true;
  $("#login-status").textContent = "Надсилаємо…";
  try {
    await api("/auth/request-code", "POST", {});
    $("#login-status").textContent = "Код надіслано. Перевірте @Varanb2b_bot.";
    $("#code").focus();
    setTimeout(() => (b.disabled = false), 60000);
  } catch (e) {
    $("#login-status").textContent = e.message;
    b.disabled = false;
  }
});
$("#login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  try {
    const d = await api("/auth/verify", "POST", { code: $("#code").value });
    state.csrf = d.csrf;
    $("#code").value = "";
    showShell();
    await load();
  } catch (err) {
    $("#login-status").textContent = err.message;
  }
});
$("#logout").addEventListener("click", async () => {
  try {
    await api("/auth/logout", "POST", {});
    state.csrf = "";
    state.dirty = false;
    showLogin();
  } catch (e) {
    notice(e.message, true);
  }
});
$("#close-preview").addEventListener("click", () => {
  $("#preview").close();
  $("#preview-frame").src = "about:blank";
  sessionStorage.removeItem("varan.preview");
});
window.addEventListener("beforeunload", (e) => {
  if (state.dirty) {
    e.preventDefault();
    e.returnValue = "";
  }
});
setInterval(()=>{
  if(!$('#shell').hidden && !document.hidden && !state.dirty && ['overview','cars','logs','payments'].includes(state.view) && !['INPUT','TEXTAREA','SELECT'].includes(document.activeElement.tagName))load();
},15000);
$("#today").textContent = new Date().toLocaleDateString("uk-UA", {
  day: "numeric",
  month: "long",
  year: "numeric",
  timeZone: "Europe/Kyiv",
});
(async () => {
  try {
    const d = await api("/auth/session");
    state.csrf = d.csrf;
    if (d.authenticated) {
      showShell();
      await load();
    }
  } catch (e) {
    $("#login-status").textContent = e.message;
  }
})();
