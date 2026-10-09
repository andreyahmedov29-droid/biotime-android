// Юнит-тесты модуля routes/app.js — версия приложения и карта.
const { test } = require("node:test");
const assert = require("node:assert");
const http = require("node:http");
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

test("GET /api/1c/ping не-админ -> 403", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/1c/ping", "GET", false);
  assert.strictEqual(res._json.status, 403);
});

test("GET /api/1c/log админ -> rows", async () => {
  const h = make({ getOnecPullLog: () => [{ ts: Date.now(), source: "button", inn: "7733379914", login: "GLAV", ok: true, number: "00УТ-0004385", posCount: 2 }] });
  const res = {};
  await h({ headers: {} }, res, "/api/1c/log", "GET", { id: "1" }, true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.rows.length, 1);
  assert.strictEqual(res._json.obj.rows[0].inn, "7733379914");
});

test("POST /api/1c/log/delete удаляет запись (возвращает 200) и 404 при отсутствии", async () => {
  const h = make({
    readBody: async () => ({ id: "A1" }),
    deleteOnecPullLog: (id) => id === "A1",
  });
  const res1 = {};
  await h({ headers: {}, url: "/api/1c/log/delete" }, res1, "/api/1c/log/delete", "POST", { id: "1" }, true);
  assert.strictEqual(res1._json.status, 200);

  const h2 = make({
    readBody: async () => ({ id: "NOPE" }),
    deleteOnecPullLog: (id) => id === "A1",
  });
  const res2 = {};
  await h2({ headers: {}, url: "/api/1c/log/delete" }, res2, "/api/1c/log/delete", "POST", { id: "1" }, true);
  assert.strictEqual(res2._json.status, 404);
});

test("POST /api/waybills/from-1c без настроенного URL -> не падает (noInn/filled=0)", async () => {
  const h = make({ readBody: async () => ({ clients: [] }) });
  const res = {};
  await h({ headers: {} }, res, "/api/waybills/from-1c", "POST", { id: "1" }, true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.ok, false);
  assert.match(String(res._json.obj.error), /ONEC_API_URL/);
});

test("POST /api/waybills/from-1c: пустой ответ 1С фиксируется в журнале (ok:false + inn/clientName)", async () => {
  // Мок 1С, который всегда возвращает пустой JSON — контрагент «не найден».
  const srv = http.createServer((req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end("[]");
  });
  await new Promise((r) => srv.listen(0, "127.0.0.1", r));
  const port = srv.address().port;
  const oldBase = process.env.ONEC_API_URL;
  const oldUser = process.env.ONEC_API_USER;
  const oldPass = process.env.ONEC_API_PASS;
  process.env.ONEC_API_URL = `http://127.0.0.1:${port}/shipment`;
  delete process.env.ONEC_API_USER;
  delete process.env.ONEC_API_PASS;
  const logged = [];
  const h = make({
    getOnecPullLog: () => logged,
    pushOnecPullLog: (e) => { logged.push(e); },
    readBody: async () => ({
      clients: [
        { inn: "5047254063", login: "SEV", client: "Рсервис Север" },
        { inn: "7714345645", login: "DOC", client: "Автодок" },
      ],
    }),
  });
  const res = {};
  try {
    await h({ headers: {} }, res, "/api/waybills/from-1c", "POST", { id: "1" }, true);
    assert.strictEqual(res._json.status, 200);
    // Обе записи — пустые (1С вернула []), обе должны попасть в журнал с ИНН/логином.
    assert.strictEqual(logged.length, 2, "в журнал попали оба обращения (включая пустые)");
    const evs = logged.filter((e) => e.reason === "empty");
    assert.strictEqual(evs.length, 2);
    assert.ok(evs.some((e) => e.inn === "5047254063" && e.clientName === "Рсервис Север"));
    assert.ok(evs.some((e) => e.inn === "7714345645" && e.login === "DOC"));
  } finally {
    if (oldBase === undefined) delete process.env.ONEC_API_URL; else process.env.ONEC_API_URL = oldBase;
    if (oldUser === undefined) delete process.env.ONEC_API_USER; else process.env.ONEC_API_USER = oldUser;
    if (oldPass === undefined) delete process.env.ONEC_API_PASS; else process.env.ONEC_API_PASS = oldPass;
    await new Promise((r) => srv.close(r));
  }
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
