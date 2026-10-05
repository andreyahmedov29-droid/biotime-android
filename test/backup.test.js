// Юнит-тесты модуля routes/backup.js — резервное копирование.
const { test } = require("node:test");
const assert = require("node:assert");
const createBackupHandler = require("../routes/backup");

function make(ctx) {
  return createBackupHandler(Object.assign({
    getDb: () => ({ staff: [{ id: "1" }] }),
    setDb: () => {},
    persistDb: async () => {},
    sendJson: (res, status, obj) => { res._json = { status, obj }; },
    readBody: async () => ({}),
    DATA_DIR: ".",
    BACKUP_DIR: ".",
    BACKUP_KEEP: 5,
    BACKUP_EVERY_MS: 3600000,
    dayKey: () => "2026-10-03",
    listAutoBackups: () => [],
    migrateDays: () => {},
    normalizeGroup: (g) => g,
  }, ctx || {}));
}

test("GET /api/admin/backup/auto не-админ -> 403", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/admin/backup/auto", "GET", false);
  assert.strictEqual(res._json.status, 403);
});

test("GET /api/admin/backup/auto админ -> параметры", async () => {
  const h = make({ listAutoBackups: () => ["a.json"] });
  const res = {};
  await h({ headers: {} }, res, "/api/admin/backup/auto", "GET", true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.backups[0], "a.json");
});

test("POST /api/admin/backup/restore заменяет БД через setDb", async () => {
  let current = { staff: [{ id: "1" }] };
  let setCalled = 0;
  const h = make({
    getDb: () => current,
    setDb: (nd) => { current = nd; setCalled += 1; },
    readBody: async () => ({ data: { staff: [{ id: "9" }], groups: [] } }),
  });
  const res = {};
  await h({ headers: {} }, res, "/api/admin/backup/restore", "POST", true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(current.staff[0].id, "9");
  assert.ok(setCalled >= 1);
});

test("POST /api/admin/backup/restore c мусором -> 422", async () => {
  const h = make({ readBody: async () => ({ data: { foo: "bar" } }) });
  const res = {};
  await h({ headers: {} }, res, "/api/admin/backup/restore", "POST", true);
  assert.strictEqual(res._json.status, 422);
});

test("неизвестный маршрут -> false", async () => {
  const h = make();
  const r = await h({ headers: {} }, {}, "/api/nope", "GET", true);
  assert.strictEqual(r, false);
});
