import { chromium } from "playwright";
import { createHub } from "../src/app.js";
import { mkdtempSync, mkdirSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import assert from "node:assert/strict";

const dir = mkdtempSync(join(tmpdir(), "varan-browser-")),
  out = resolve(process.env.QA_OUTPUT || "./artifacts");
mkdirSync(out, { recursive: true });
let code = "";
const hub = createHub({
  config: {
    dbPath: ":memory:",
    mediaRoot: join(dir, "media"),
    siteRoot: resolve(process.argv[2] || "../../Varan team/public"),
    publicUrl: "http://127.0.0.1:18080",
    sessionSecret: "browser-test-only-secret-with-32-characters",
    authBotToken: "fake",
    adminChatId: "123",
    production: false,
  },
  bot: null,
  sendCode: async (text) => {
    code = text.match(/: (\d{6})/)[1];
  },
});
await new Promise((r) => hub.server.listen(18080, "127.0.0.1", r));
const browser = await chromium.launch({ channel: "chrome", headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }),
  errors = [];
page.on("pageerror", (e) => errors.push(e.message));
const base = "http://127.0.0.1:18080";
try {
  await page.goto(base + "/admin/");
  await page.screenshot({ path: join(out, "admin-login.png"), fullPage: true });
  await page.getByRole("button", { name: "Отримати код" }).click();
  await page.locator("#code").fill(code);
  await page.getByRole("button", { name: "Увійти" }).click();
  await page.getByRole("heading", { name: "Усе під контролем." }).waitFor();
  await page.screenshot({
    path: join(out, "admin-overview.png"),
    fullPage: true,
  });
  await page.locator('nav [data-view="site"]').click();
  await page.locator('[data-path="draft.home.name"]').fill("Перевірка CMS");
  await page
    .getByRole("button", { name: "Зберегти чернетку", exact: true })
    .click();
  const publicPage = await browser.newPage();
  await publicPage.goto(base);
  await publicPage.waitForFunction(
    () => document.querySelectorAll("#stage .app").length > 0,
  );
  assert.notEqual(
    await publicPage.locator(".name").textContent(),
    "Перевірка CMS",
  );
  await page.getByRole("button", { name: "Опублікувати", exact: true }).click();
  await page.getByText("Зміни опубліковано", { exact: true }).waitFor();
  await publicPage.reload();
  await publicPage.waitForFunction(
    () => document.querySelector(".name").textContent === "Перевірка CMS",
  );
  await page.getByRole("button", { name: "Переглянути ↗" }).click();
  await page
    .frameLocator("#preview-frame")
    .locator("#stage .app")
    .first()
    .waitFor();
  await page.locator("#close-preview").click();
  await page.locator('nav [data-view="byd"]').click();
  await page
    .getByRole("heading", { name: "Car Remote.", exact: true })
    .waitFor();
  await page.screenshot({ path: join(out, "admin-byd.png"), fullPage: true });
  await page.locator('nav [data-view="campaign"]').click();
  await page.locator('[data-path="campaign.title"]').fill("Тестова кампанія");
  await page
    .locator('[data-path="campaign.text"]')
    .fill("Компактний банер для ваших сервісів.");
  await page.getByRole("button", { name: "Зберегти кампанію" }).click();
  await page.getByText("Кампанію збережено", { exact: true }).waitFor();
  await page.screenshot({
    path: join(out, "admin-campaign.png"),
    fullPage: true,
  });
  for (const view of ["cars", "owners", "bot", "payments", "logs", "audit"]) {
    await page.locator(`nav [data-view="${view}"]`).click();
    await page.waitForTimeout(100);
    assert.equal(await page.locator("#content .error").count(), 0);
  }
  await publicPage.goto(base + "/byd/");
  await publicPage.locator("#adfree").waitFor();
  await publicPage.screenshot({
    path: join(out, "byd-payment.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('nav [data-view="overview"]').click();
  await page.getByRole("heading", { name: "Усе під контролем." }).waitFor();
  await page.screenshot({
    path: join(out, "admin-mobile.png"),
    fullPage: true,
  });
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
    false,
    "Admin must not overflow mobile viewport",
  );
  await publicPage.setViewportSize({ width: 390, height: 844 });
  await publicPage.goto(base + "/byd/");
  await publicPage.waitForTimeout(200);
  assert.equal(
    await publicPage.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
    false,
    "BYD page must not overflow",
  );
  await page.locator("#logout").click();
  await page.locator("#login").waitFor({ state: "visible" });
  assert.deepEqual(errors, []);
  console.log(
    JSON.stringify({ browserChecks: "passed", screenshots: out, errors }),
  );
} finally {
  await browser.close();
  hub.close();
  rmSync(dir, { recursive: true, force: true });
}
