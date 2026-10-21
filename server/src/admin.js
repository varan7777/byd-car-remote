// Minimal admin API + a static page at /admin: list cars, owners and recent command logs.
// Protected by a single password (Basic auth) from ADMIN_PASSWORD.
import express from 'express';
import { cars, logs, db } from './db.js';

export function adminRouter(isOnline) {
  const r = express.Router();

  r.use((req, res, next) => {
    const pass = (req.headers.authorization || '').split(' ')[1];
    const ok = pass && Buffer.from(pass, 'base64').toString().split(':')[1] === process.env.ADMIN_PASSWORD;
    if (!ok) { res.set('WWW-Authenticate', 'Basic realm="admin"'); return res.status(401).end('Auth required'); }
    next();
  });

  r.get('/api/cars', (_req, res) => {
    const rows = cars.all().map((c) => {
      const owner = db.prepare('SELECT phone,name FROM owners WHERE chat_id=?').get(c.chat_id) || {};
      return {
        id: c.id, label: c.label, chatId: c.chat_id, phone: owner.phone, owner: owner.name,
        online: isOnline(c.chat_id), lastSeen: c.last_seen,
      };
    });
    res.json(rows);
  });

  r.get('/api/logs', (_req, res) => res.json(logs.recent(200)));

  r.use('/', express.static(new URL('../public', import.meta.url).pathname));
  return r;
}
