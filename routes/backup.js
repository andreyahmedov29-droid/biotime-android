// Модуль-обработчик резервного копирования (админ). Вынесен из server.js
// (handleBackupRoutes): GET /api/admin/backup, /backup/app, /backup/auto,
// /backup/auto/download и POST /backup/restore.
//
// Зависимости через фабрику (DI). БД читается через getDb; в restore БД
// переустанавливается целиком, поэтому здесь нужен setDb(newDb).
const fs = require("node:fs");
const path = require("node:path");

module.exports = function createBackupHandler({
  getDb,
  setDb,
  persistDb,
  sendJson,
  readBody,
  DATA_DIR,
  BACKUP_DIR,
  BACKUP_KEEP,
  BACKUP_EVERY_MS,
  dayKey,
  listAutoBackups,
  migrateDays,
  normalizeGroup,
} = {}) {
  return async function handleBackupRoutes(req, res, urlPath, method, admin) {
    const db = getDb ? getDb() : {};

    if (urlPath === "/api/admin/backup" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const payload = JSON.stringify({
        app: "biotime",
        version: 1,
        exportedAt: new Date().toISOString(),
        data: db,
      }, null, 2);
      const stamp = dayKey(Date.now());
      const fname = `biotime-backup-${stamp}.json`;
      res.writeHead(200, {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(fname)}`,
        "Content-Length": Buffer.byteLength(payload),
        "Cache-Control": "no-store",
      });
      return res.end(payload);
    }

    if (urlPath === "/api/admin/backup/app" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const EXCLUDE_DIRS = new Set([".opencode", "node_modules", ".git", ".idea", ".vscode", "android", "ios", ".venv"]);
      const files = {};
      const BINARY_EXT = /\.(png|jpe?g|gif|webp|ico)$/i;
      const SKIP_ANY = /\.(log|err)$/i;
      const SKIP_SPECIAL = /(^|[\\/])(srv.*|t2?_.*|check-.*\.png|example-.*|logo-preview\.html|test-.*\.html|.*_before_design\.png|export_test\.xlsx)$/i;
      const walk = (dir) => {
        let entries;
        try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
        for (const e of entries) {
          if (e.name === "." || e.name === "..") continue;
          const full = path.join(dir, e.name);
          if (e.isDirectory()) {
            if (EXCLUDE_DIRS.has(e.name)) continue;
            walk(full);
          } else if (e.isFile()) {
            const rel = path.relative(process.cwd(), full).split(path.sep).join("/");
            if (rel.startsWith(".") || rel.includes("node_modules")) continue;
            if (SKIP_ANY.test(e.name) || SKIP_SPECIAL.test(rel) || SKIP_SPECIAL.test(e.name)) continue;
            try {
              const buf = fs.readFileSync(full);
              files[rel] = BINARY_EXT.test(e.name) ? buf.toString("base64") : buf.toString("utf8");
            } catch { /* skip unreadable file */ }
          }
        }
      };
      walk(process.cwd());
      const payload = JSON.stringify({
        archive: "biotime-project",
        app: "biotime",
        version: 3,
        generatedAt: new Date().toISOString(),
        note: "Полная резервная копия приложения: исходный код + база данных. Храните в надёжном месте.",
        files,
        data: db,
      }, null, 2);
      const stamp = dayKey(Date.now());
      const fname = `biotime-app-${stamp}.json`;
      res.writeHead(200, {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(fname)}`,
        "Content-Length": Buffer.byteLength(payload),
        "Cache-Control": "no-store",
      });
      return res.end(payload);
    }

    if (urlPath === "/api/admin/backup/restore" && method === "POST") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const body = await readBody(req);
      const incoming = body && body.data && typeof body.data === "object" ? body.data : body;
      if (!incoming || typeof incoming !== "object") {
        return sendJson(res, 422, { error: "invalid backup" });
      }
      const looksLikeDb =
        Array.isArray(incoming.staff) ||
        Array.isArray(incoming.groups) ||
        (incoming.days && typeof incoming.days === "object");
      if (!looksLikeDb) return sendJson(res, 422, { error: "not a biotime backup" });

      try {
        if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
        const bk = path.join(DATA_DIR, `before-restore-${Date.now()}.json`);
        fs.writeFileSync(bk, JSON.stringify(db));
      } catch (e) {
        console.error("restore snapshot failed:", e);
      }

      const prev = db;
      const next = {
        staff: Array.isArray(incoming.staff) ? incoming.staff : [],
        admins: Array.isArray(incoming.admins) ? incoming.admins : (prev ? prev.admins : []),
        blocked: Array.isArray(incoming.blocked) ? incoming.blocked : (prev ? prev.blocked : []),
        groups: Array.isArray(incoming.groups) ? incoming.groups : (prev ? prev.groups : []),
        days: incoming.days && typeof incoming.days === "object" ? incoming.days : {},
        log: Array.isArray(incoming.log) ? incoming.log : [],
        driverClients: Array.isArray(incoming.driverClients) ? incoming.driverClients : [],
        driverRoutes: Array.isArray(incoming.driverRoutes) ? incoming.driverRoutes : [],
        labels: Array.isArray(incoming.labels) ? incoming.labels : [],
        lastSeen: {},
        liveLocations: {},
        tracks: {},
        params: incoming.params && typeof incoming.params === "object" ? incoming.params : (prev ? prev.params : {}),
        norm: Number.isFinite(incoming.norm) ? incoming.norm : (prev && Number.isFinite(prev.norm) ? prev.norm : 9),
      };
      if (setDb) setDb(next);
      try {
        migrateDays(next);
        next.groups = next.groups.map((g) => normalizeGroup(g, next.staff));
        await persistDb();
        return sendJson(res, 200, {
          ok: true,
          restored: {
            staff: next.staff.length,
            days: Object.keys(next.days).length,
            groups: next.groups.length,
            clients: next.driverClients.length,
            routes: next.driverRoutes.length,
            log: next.log.length,
          },
        });
      } catch (err) {
        console.error("restore failed:", err);
        return sendJson(res, 500, { error: "Ошибка восстановления: " + (err && err.message ? err.message : String(err)) });
      }
    }

    if (urlPath === "/api/admin/backup/auto" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      return sendJson(res, 200, {
        ok: true,
        everyHours: BACKUP_EVERY_MS / (60 * 60 * 1000),
        keep: BACKUP_KEEP,
        backups: listAutoBackups(),
      });
    }

    if (urlPath === "/api/admin/backup/auto/download" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const name = String(new URL(req.url, `http://${req.headers.host}`).searchParams.get("name") || "");
      if (!/^biotime-backup-.*\.json$/.test(name)) return sendJson(res, 422, { error: "bad name" });
      const full = path.join(BACKUP_DIR, path.basename(name));
      if (!fs.existsSync(full) || !fs.statSync(full).isFile()) return sendJson(res, 404, { error: "not found" });
      const data = fs.readFileSync(full);
      res.writeHead(200, {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(path.basename(name))}`,
        "Content-Length": data.length,
        "Cache-Control": "no-store",
      });
      return res.end(data);
    }
    return false;
  };
};
