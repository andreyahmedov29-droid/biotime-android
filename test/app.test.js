// Юнит-тесты модуля routes/app.js — версия приложения и карта.
const { test } = require("node:test");
const assert = require("node:assert");
const createAppHandler = require("../routes/app");

function make(overrides) {
  return createAppHandler(Object.assign({
    getDb: () => ({ params: {} }),
    persistDb: async () => {},
    sendJson: (res, status, obj) => { res._json = { status, obj }; },
    readBody: async () => ({}),
    writeVersionSource: () => {},
    readVersionSource: () => ({}),
    fetchRemoteApkVersion: async () => null,
    yandexMapsKey: "KEY",
  }, overrides || {}));
}

test("GET /api/app/web-version отдаёт версию", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/app/web-version", "GET", false);
  assert.strictEqual(res._json.status, 200);
  assert.match(res._json.obj.version, /^\d{8}c\d{3}$/);
});

test("GET /api/maps/config не-админ -> 403", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/maps/config", "GET", false);
  assert.strictEqual(res._json.status, 403);
});

test("GET /api/maps/config админ -> ключ карты", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/maps/config", "GET", true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.yandexKey, "KEY");
});

test("GET /api/ip отдаёт реальный IP сервера", async () => {
  const h = make({ resolvePublicIp: async () => "203.0.113.7" });
  const res = {};
  await h({ headers: {} }, res, "/api/ip", "GET", false);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.ip, "203.0.113.7");
});

test("GET /api/ip при недоступном резолвере -> ip:null (не падает)", async () => {
  const h = make({ resolvePublicIp: async () => { throw new Error("net"); } });
  const res = {};
  await h({ headers: {} }, res, "/api/ip", "GET", false);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.ip, null);
});

test("POST /api/app/update c неверным токеном -> 401", async () => {
  const h = make();
  const res = {};
  await h({ headers: { "x-update-token": "wrong" } }, res, "/api/app/update", "POST", true);
  assert.strictEqual(res._json.status, 401);
});

test("GET /api/app/update-info использует фолбэки", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/app/update-info", "GET", false);
  assert.strictEqual(res._json.status, 200);
  assert.ok(res._json.obj.versionCode >= 1);
  assert.ok(typeof res._json.obj.apkUrl === "string");
});

test("неизвестный маршрут -> false", async () => {
  const h = make();
  const r = await h({ headers: {} }, {}, "/api/nope", "GET", true);
  assert.strictEqual(r, false);
});
