// Юнит-тесты модуля routes/route-create.js — создание/настройка маршрутов.
const { test } = require("node:test");
const assert = require("node:assert");
const createRouteCreateHandler = require("../routes/route-create");

function make(ctx) {
  return createRouteCreateHandler(Object.assign({
    getDb: () => ({ driverRoutes: [], driverClients: [], labels: [], params: {} }),
    sendJson: (res, status, obj) => { res._json = { status, obj }; },
    readBody: async () => ({}),
    persistDb: async () => {},
    routeKmCache: {},
    routeKmPending: {},
    normalizeRouteClient: (c) => c,
    routeLockReason: (p) => "маршрут занят",
    autoPullWaybillsFrom1c: async () => {},
    relinkRouteLabels: () => {},
    canManageShipment: () => true,
    withResolvedBundleNames: (r) => r,
    normalizeRouteProgress: (r) => r,
    ensureClientCoords: async () => {},
    geocodeAddress: async () => null,
    gisDurationMatrix: async () => null,
    tomtomDurationMatrix: async () => null,
    osrmDurationMatrix: async () => null,
    nearestByTime: () => [],
    nearestNeighbor: () => [],
    gisDistanceMatrix: async () => null,
    haversineKm: () => 5,
  }, ctx || {}));
}

const admin = { id: "a1", name: "Админ" };

test("POST /api/drivers/routes создаёт маршрут", async () => {
  const db = { driverRoutes: [], driverClients: [], labels: [], params: {} };
  const h = make({
    getDb: () => db,
    readBody: async () => ({
      date: "2026-10-03",
      driverId: "d1",
      driverName: "Водитель",
      routeName: "Утренний",
      clients: [{ client: "Клиент А", address: "Ул. 1" }],
    }),
  });
  const res = {};
  await h({ headers: {}, url: "/api/drivers/routes" }, res, "/api/drivers/routes", "POST", admin, true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.routes.length, 1);
  assert.strictEqual(res._json.obj.routes[0].routeName, "Утренний");
});

test("POST /api/drivers/routes без названия -> 400", async () => {
  const h = make({
    readBody: async () => ({ date: "2026-10-03", driverId: "d1", clients: [] }),
  });
  const res = {};
  await h({ headers: {}, url: "/api/drivers/routes" }, res, "/api/drivers/routes", "POST", admin, true);
  assert.strictEqual(res._json.status, 400);
});

test("POST /api/drivers/routes не-админ -> 403", async () => {
  const h = make();
  const res = {};
  await h({ headers: {}, url: "/api/drivers/routes" }, res, "/api/drivers/routes", "POST", { id: "u1" }, false);
  assert.strictEqual(res._json.status, 403);
});

test("POST /api/drivers/routes delete удаляет маршрут", async () => {
  const db = {
    driverRoutes: [{ id: "r1", progress: {}, clients: [] }],
    driverClients: [],
    labels: [],
    params: {},
  };
  const h = make({
    getDb: () => db,
    readBody: async () => ({ action: "delete", id: "r1" }),
  });
  const res = {};
  await h({ headers: {}, url: "/api/drivers/routes" }, res, "/api/drivers/routes", "POST", admin, true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.routes.length, 0);
});

test("POST /api/routes/unlock неизвестный маршрут -> 404", async () => {
  const h = make({ readBody: async () => ({ routeId: "nope" }) });
  const res = {};
  await h({ headers: {}, url: "/api/routes/unlock" }, res, "/api/routes/unlock", "POST", admin, true);
  assert.strictEqual(res._json.status, 404);
});

test("POST /api/drivers/routes/optimize без клиентов -> 400", async () => {
  const h = make({ readBody: async () => ({ clientIds: [] }) });
  const res = {};
  await h({ headers: {}, url: "/api/drivers/routes/optimize" }, res, "/api/drivers/routes/optimize", "POST", admin, true);
  assert.strictEqual(res._json.status, 400);
});

test("POST /api/drivers/route-km с одной точкой -> empty", async () => {
  const h = make({ readBody: async () => ({ points: [{ lat: 55, lon: 37 }] }) });
  const res = {};
  await h({ headers: {}, url: "/api/drivers/route-km" }, res, "/api/drivers/route-km", "POST", admin, true);
  assert.strictEqual(res._json.status, 200);
  assert.strictEqual(res._json.obj.method, "empty");
});

test("неизвестный маршрут -> false", async () => {
  const h = make();
  const r = await h({ headers: {} }, {}, "/api/nope", "GET", admin, true);
  assert.strictEqual(r, false);
});
