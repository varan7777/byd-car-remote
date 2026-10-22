import { readFileSync, writeFileSync } from "node:fs";
import { resolve, join } from "node:path";
import vm from "node:vm";
import { load } from "cheerio";
const site = resolve(process.argv[2] || "../../Varan team/public");
const box = { window: {} };
vm.runInNewContext(readFileSync(join(site, "js/apps.js"), "utf8"), box, {
  timeout: 1000,
});
const { center, APPS } = box.window.SITE;
const $ = load(readFileSync(join(site, "byd/index.html"), "utf8"));
const local = (p) =>
  p ? (p.startsWith("https:") || p.startsWith("/") ? p : `/${p}`) : "";
const lang = (l) => {
  const sections = $(`section[lang=${l}]`);
  return {
    tag: $(`.tag[lang=${l}]`).text(),
    note: $(`.note[lang=${l}]`).text(),
    features: sections
      .eq(0)
      .find(".feats li")
      .map((_i, el) => {
        const li = $(el),
          d = li.find("div").clone(),
          title = d.find("b").text();
        d.find("b").remove();
        return { icon: li.find(".ic").text(), title, text: d.text() };
      })
      .get(),
    footnote: sections.eq(0).find(".small").text(),
    privacyTitle: sections.eq(1).find("h2").text(),
    privacy: sections.eq(1).find(".txt").text(),
    steps: sections
      .eq(2)
      .find("li")
      .map((_i, el) => $(el).text())
      .get(),
  };
};
const seed = {
  home: {
    name: load(readFileSync(join(site, "index.html"), "utf8"))(".name").text(),
    center: { ...center, photo: local(center.photo) },
    services: APPS.map((s, i) => ({
      ...s,
      id: `service-${i + 1}`,
      visible: true,
      ...(s.logo ? { logo: local(s.logo) } : {}),
      ...(s.page ? { page: local(s.page) } : {}),
    })),
  },
  byd: {
    title: $(".hero h1").text(),
    version: $(".hero .meta").text(),
    apkUrl: "/byd/" + $(".actions a").eq(0).attr("href"),
    telegramUrl: $(".actions a").eq(1).attr("href"),
    githubUrl: $(".actions a").eq(2).attr("href"),
    screenshots: $(".shots img")
      .map((_i, el) => ({
        src: "/byd/" + $(el).attr("src"),
        alt: $(el).attr("alt"),
      }))
      .get(),
    uk: lang("uk"),
    en: lang("en"),
  },
};
writeFileSync(
  new URL("../seed-site.json", import.meta.url),
  JSON.stringify(seed, null, 2) + "\n",
);
console.log(
  "Imported existing homepage and BYD page; no production state changed.",
);
