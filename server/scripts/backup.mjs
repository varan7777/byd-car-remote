import "dotenv/config";
import Database from "better-sqlite3";
import { mkdirSync, chmodSync } from "node:fs";
import { resolve } from "node:path";
const output = resolve(process.argv[2] || "./backups");
mkdirSync(output, { recursive: true, mode: 0o700 });
const db = new Database(process.env.DB_PATH || "./data/hub.db", {
  readonly: true,
});
const filename = `${output}/hub-${new Date().toISOString().replaceAll(":", "-")}.db`;
await db.backup(filename);
db.close();
chmodSync(filename, 0o600);
console.log("Consistent SQLite backup saved. Back up MEDIA_ROOT separately.");
