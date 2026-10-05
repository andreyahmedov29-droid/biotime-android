// Юнит-тесты модуля routes/notfound.js — «Проблемы со склада».
const { test } = require("node:test");
const assert = require("node:assert");
const createNotfoundHandler = require("../routes/notfound");

function make(ctx) {
  return createNotfoundHandler(Object.assign({
    getDb: () => ({ driverRoutes: [], scanLog: [], notFound: {} }),
    persistDb: async () => {},
    sendJson: (res, status, obj) => { res._json = { status, obj }; },
    readBody: async () => ({}),
    canSeeNotfound: (u) => !!(u && u.see),
    alignWaybillsToClients: () => {},
    persistNotFoundStatuses: async () => {},
  }, ctx || {}));
}

test("GET /api/notfound без доступа -> 403", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/notfound", "GET", { see: false });
  assert.strictEqual(res._json.status, 403);
});

test("GET /api/notfound пустой отчёт -> 200, 0 строк", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/notfound", "GET", { see: true });
  assert.strictEqual(res._json.status, 200);
  assert.deepStrictEqual(res._json.obj.rows, []);
});

test("GET /api/notfound собирает строки из накладных (missing)", async () => {
  const route = {
    id: "r1", at: Date.parse("2026-10-02T10:00:00"),
    clients: [{ client: "АвтоМ", address: "ул. Т" }],
    waybills: [{ items: [{ art: "A1", name: "Деталь", missing: true, missingQty: 1, qty: 1 }] }],
  };
  const h = make({ getDb: () => ({ driverRoutes: [route], scanLog: [], notFound: {} }) });
  const res = {};
  await h({ headers: {} }, res, "/api/notfound", "GET", { see: true });
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.rows.length, 1);
  assert.strictEqual(res._json.obj.rows[0].client, "АвтоМ");
  assert.strictEqual(res._json.obj.rows[0].art, "A1");
});

test("POST /api/notfound сохраняет статус и комментарий", async () => {
  const db = { driverRoutes: [], scanLog: [], notFound: {} };
  let nfSaved = 0;
  const h = make({
    getDb: () => db,
    persistNotFoundStatuses: async () => { nfSaved += 1; },
    readBody: async () => ({ key: "АвтоМ|A1", status: "Выполнено", comment: "ок" }),
  });
  const res = {};
  await h({ headers: {} }, res, "/api/notfound", "POST", { see: true });
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(db.notFound["АвтоМ|A1"].status, "Выполнено");
  assert.ok(nfSaved >= 1);
});

test("неизвестный маршрут -> false", async () => {
  const h = make();
  const r = await h({ headers: {} }, {}, "/api/nope", "GET", { see: true });
  assert.strictEqual(r, false);
});
