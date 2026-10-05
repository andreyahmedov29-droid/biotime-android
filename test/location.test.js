// Юнит-тесты модуля routes/location.js — геолокация водителей.
const { test } = require("node:test");
const assert = require("node:assert");
const createLocationHandler = require("../routes/location");

function make(ctx) {
  return createLocationHandler(Object.assign({
    getDb: () => ({ liveLocations: {}, tracks: {} }),
    sendJson: (res, status, obj) => { res._json = { status, obj }; },
    readBody: async () => ({}),
    isDriver: () => true,
    isDriversGroupOnly: () => true,
    motionDayKey: () => "2026-10-03",
    tracksByDay: {},
    scheduleTracksSave: () => {},
  }, ctx || {}));
}

test("POST /api/drivers/location не-водитель -> 403", async () => {
  const h = make({ isDriver: () => false });
  const res = {};
  await h({ headers: {} }, res, "/api/drivers/location", "POST", { id: "1" }, true);
  assert.strictEqual(res._json.status, 403);
});

test("POST /api/drivers/location сохраняет координаты и трек", async () => {
  const db = { liveLocations: {}, tracks: {} };
  const h = make({
    getDb: () => db,
    tracksByDay: {},
    readBody: async () => ({ lat: 55.7, lon: 37.6, routeId: "r1" }),
  });
  const res = {};
  await h({ headers: {} }, res, "/api/drivers/location", "POST", { id: "7", name: "Иван" }, true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(db.liveLocations["7"].lat, 55.7);
  assert.strictEqual(db.tracks["7"].length, 1);
});

test("POST /api/drivers/location с невалидными координатами -> 422", async () => {
  const h = make({ readBody: async () => ({ lat: "x", lon: "y" }) });
  const res = {};
  await h({ headers: {} }, res, "/api/drivers/location", "POST", { id: "1" }, true);
  assert.strictEqual(res._json.status, 422);
});

test("GET /api/drivers/location не-админ -> 403", async () => {
  const h = make();
  const res = {};
  await h({ headers: {} }, res, "/api/drivers/location", "GET", { id: "1" }, false);
  assert.strictEqual(res._json.status, 403);
});

test("GET /api/drivers/location возвращает свежие позиции", async () => {
  const db = { liveLocations: { "7": { lat: 55.7, lon: 37.6, at: Date.now(), name: "Иван", routeId: "r1" } }, tracks: {} };
  const h = make({ getDb: () => db });
  const res = {};
  await h({ headers: {} }, res, "/api/drivers/location", "GET", { id: "1" }, true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.rows.length, 1);
  assert.strictEqual(res._json.obj.rows[0].lat, 55.7);
});

test("неизвестный маршрут -> false", async () => {
  const h = make();
  const r = await h({ headers: {} }, {}, "/api/nope", "GET", { id: "1" }, true);
  assert.strictEqual(r, false);
});
