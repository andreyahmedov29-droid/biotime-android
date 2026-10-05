// Модуль-обработчик создания/настройки маршрутов (/api/drivers/routes POST,
// /api/routes/unlock, /api/drivers/routes/optimize, /api/drivers/route-km,
// /api/drivers/base-km). Вынесен из server.js дословно; зависимости инъекцией (DI).
module.exports = function createRouteCreateHandler({
  getDb,
  persistDb,
  sendJson,
  readBody,
  normalizeRouteClient,
  routeLockReason,
  autoPullWaybillsFrom1c,
  relinkRouteLabels,
  canManageShipment,
  withResolvedBundleNames,
  normalizeRouteProgress,
  routeKmCache,
  routeKmPending,
  ensureClientCoords,
  geocodeAddress,
  gisDurationMatrix,
  tomtomDurationMatrix,
  osrmDurationMatrix,
  nearestByTime,
  nearestNeighbor,
  gisDistanceMatrix,
  haversineKm,
} = {}) {
  return async function handleRouteCreateRoutes(req, res, urlPath, method, user, admin) {
    const db = getDb ? getDb() : {};

    if (urlPath === "/api/drivers/routes" && method === "POST") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const body = await readBody(req);
      if (body.action === "delete") {
        const id = String(body.id || "");
        const idx = (db.driverRoutes || []).findIndex((r) => r.id === id);
        if (idx < 0) return sendJson(res, 404, { error: "Маршрут не найден" });
        const target = db.driverRoutes[idx];
        const p = target.progress || {};
        const occupied = p.status === "done" || p.status === "active" || !!p.shipmentStartedAt;
        if (occupied) {
          const expected = String((db.params && db.params.routeDeleteCode) || "").trim();
          const given = String(body.code || "").trim();
          if (!expected) {
            return sendJson(res, 409, { error: "Код удаления занятого маршрута не задан в «Параметры»" });
          }
          if (!given || given !== expected) {
            return sendJson(res, 403, { error: "Неверный код удаления" });
          }
        }
        db.driverRoutes.splice(idx, 1);
        db.labels = (db.labels || []).filter((l) => String(l.routeId) !== String(id));
        delete routeKmCache[id];
        delete routeKmPending[id];
        await persistDb();
        return sendJson(res, 200, { ok: true, routes: db.driverRoutes });
      }
      // Обновление точек уже созданного маршрута по его id.
      if (body.action === "update") {
        const id = String(body.id || "");
        const found = (db.driverRoutes || []).find((r) => r.id === id);
        if (!found) return sendJson(res, 404, { error: "Маршрут не найден" });
        if (found.progress) {
          const locked = found.progress.status === "done"
            || found.progress.status === "active";
          if (locked) {
            return sendJson(res, 409, { error: routeLockReason(found.progress) });
          }
        }
        const clients = Array.isArray(body.clients)
          ? body.clients.slice(0, 50).map(normalizeRouteClient).filter((c) => c.client || c.address)
          : [];
        if (clients.length === 0) return sendJson(res, 400, { error: "Укажите хотя бы одного клиента" });
        if (body.date) found.date = String(body.date).slice(0, 10);
        if (body.driverId) found.driverId = String(body.driverId).slice(0, 60);
        if (body.driverName !== undefined) found.driverName = String(body.driverName || "").slice(0, 200);
        if (body.routeName !== undefined) {
          found.routeName = String(body.routeName || "").trim().slice(0, 60) || "Маршрут";
        }
        found.clients = clients;
        found.at = Date.now();
        if (Number.isFinite(Number(body.km)) && Number(body.km) >= 0) {
          found.km = Math.round(Number(body.km) * 10) / 10;
        } else {
          delete found.km;
          delete routeKmCache[id];
          delete routeKmPending[id];
        }
        await persistDb();
        return sendJson(res, 200, { ok: true, routes: db.driverRoutes });
      }
      const date = String(body.date || "").slice(0, 10);
      const driverId = String(body.driverId || "").slice(0, 60);
      const driverName = String(body.driverName || "").slice(0, 200);
      const selfPickup = body.selfPickup === true;
      const routeName = String(body.routeName || "").trim().slice(0, 60);
      if (!routeName) {
        return sendJson(res, 400, { error: "Укажите название маршрута" });
      }
      const clients = Array.isArray(body.clients)
        ? body.clients.slice(0, 50).map(normalizeRouteClient).filter((c) => c.client || c.address)
        : [];
      if (!date || clients.length === 0 || (!selfPickup && !driverId)) {
        return sendJson(res, 400, { error: "Укажите дату, водителя и хотя бы одного клиента" });
      }
      const wbOn = db.params && db.params.allowWaybill === true;
      const waybillsArr = [];
      if (Array.isArray(body.waybills)) {
        for (const w of body.waybills) {
          const idx = Number(w && w.clientIndex);
          if (!Number.isInteger(idx) || idx < 0 || idx >= clients.length) continue;
          const items = Array.isArray(w.items)
            ? w.items
                .map((it) => ({
                  art: String((it && it.art) || "").trim(),
                  name: String((it && it.name) || "").trim(),
                  qty: Number(it && it.qty) > 0 ? Number(it.qty) : 1,
                  scanned: 0,
                  missing: false,
                }))
                .filter((it) => it.art)
            : [];
          if (items.length > 0) {
            waybillsArr.push({ clientIndex: idx, items, buyer: String((w && w.buyer) || "").trim() });
          }
        }
      }
      await autoPullWaybillsFrom1c(clients, waybillsArr, db);
      db.driverRoutes = db.driverRoutes || [];
      const existIdx = selfPickup
        ? (body.id
            ? db.driverRoutes.findIndex((r) => String(r.id) === String(body.id))
            : -1)
        : db.driverRoutes.findIndex(
            (r) => r.date === date && r.driverId === driverId && r.routeName === routeName
          );
      if (existIdx >= 0) {
        const existing = db.driverRoutes[existIdx];
        if (existing && existing.progress) {
          const locked = existing.progress.status === "done"
            || existing.progress.status === "active"
            || !!existing.progress.shipmentStartedAt;
          if (locked) {
            return sendJson(res, 409, { error: routeLockReason(existing.progress) });
          }
        }
        db.driverRoutes[existIdx].clients = clients;
        db.driverRoutes[existIdx].selfPickup = body.selfPickup === true;
        relinkRouteLabels(db.driverRoutes[existIdx].id, clients, db.labels);
        db.driverRoutes[existIdx].at = Date.now();
        if (waybillsArr.length) {
          if (!db.driverRoutes[existIdx].waybills) db.driverRoutes[existIdx].waybills = {};
          waybillsArr.forEach((w) => {
            db.driverRoutes[existIdx].waybills[w.clientIndex] = { items: w.items, buyer: String(w.buyer || ""), loadedAt: Date.now() };
          });
        }
        if (Number.isFinite(Number(body.km)) && Number(body.km) >= 0) {
          db.driverRoutes[existIdx].km = Math.round(Number(body.km) * 10) / 10;
        }
      } else {
        db.driverRoutes.push({
          id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`,
          date,
          driverId,
          driverName,
          routeName,
          clients,
          selfPickup: body.selfPickup === true,
          km: (Number.isFinite(Number(body.km)) && Number(body.km) >= 0)
            ? Math.round(Number(body.km) * 10) / 10
            : undefined,
          addedBy: user.id,
          at: Date.now(),
          waybills: waybillsArr.length
            ? Object.fromEntries(waybillsArr.map((w) => [w.clientIndex, { items: w.items, buyer: String(w.buyer || ""), loadedAt: Date.now() }]))
            : undefined,
        });
      }
      if (db.driverRoutes.length > 3000) db.driverRoutes = db.driverRoutes.slice(-3000);
      await persistDb();
      return sendJson(res, 200, { ok: true, routes: db.driverRoutes });
    }

    // Разблокировка «залипшего» маршрута.
    if (urlPath === "/api/routes/unlock" && method === "POST") {
      if (!admin && !canManageShipment(user, db)) {
        return sendJson(res, 403, { error: "forbidden" });
      }
      const body = await readBody(req);
      const routeId = String(body.routeId || "");
      const route = (db.driverRoutes || []).find((r) => String(r.id) === String(routeId));
      if (!route) return sendJson(res, 404, { error: "Маршрут не найден" });
      const p = route.progress || {};
      if (p.status === "done") {
        return sendJson(res, 409, { error: "Завершённый маршрут расфиксировать нельзя" });
      }
      if (p.shippedAt) {
        return sendJson(res, 409, { error: "Отгрузка маршрута завершена — нечего расфиксировать" });
      }
      let releases = 0;
      const before = routeLockReason(p);
      if (p.status === "active") {
        p.status = "idle";
        releases++;
      }
      if (p.shipmentStartedAt) {
        delete p.shipmentStartedAt;
        delete p.shipmentStartedBy;
        releases++;
      }
      (Array.isArray(route.clients) ? route.clients : []).forEach((c) => {
        if (c && typeof c === "object") {
          c.state = "pending";
          c.transitStart = null;
          c.transitEnd = null;
          c.siteStart = null;
          c.siteEnd = null;
        }
      });
      if (releases === 0) {
        return sendJson(res, 200, {
          ok: true,
          note: "Маршрут и так не был заблокирован",
          route: withResolvedBundleNames(normalizeRouteProgress(route), db),
        });
      }
      route.at = Date.now();
      await persistDb();
      return sendJson(res, 200, {
        ok: true,
        released: releases,
        before: before,
        route: withResolvedBundleNames(normalizeRouteProgress(route), db),
      });
    }

    // Автопостроение маршрута.
    if (urlPath === "/api/drivers/routes/optimize" && method === "POST") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const body = await readBody(req);
      const ids = Array.isArray(body.clientIds) ? body.clientIds.map(String).filter(Boolean) : [];
      if (ids.length === 0) return sendJson(res, 400, { error: "Выберите клиентов для маршрута" });
      const points = ids
        .map((id) => db.driverClients.find((c) => c.id === id))
        .filter(Boolean);
      if (points.length === 0) return sendJson(res, 400, { error: "Клиенты не найдены" });

      for (const p of points) {
        try { await ensureClientCoords(p); } catch { /* не критично */ }
      }

      let base = null;
      const baseAddress = String(body.baseAddress || "").trim();
      if (baseAddress) {
        try { base = await geocodeAddress(baseAddress); } catch { base = null; }
      }

      const geo = points.map((p) => Number.isFinite(p.lat) && Number.isFinite(p.lon));
      const geoIdx = points.map((_, i) => i).filter((i) => geo[i]);
      const ungeoIdx = points.map((_, i) => i).filter((i) => !geo[i]);

      let order;
      let withBase = false;
      let method = "straight";
      if (geoIdx.length <= 1) {
        method = "trivial";
        order = geoIdx.concat(ungeoIdx);
      } else {
        const geoPoints = geoIdx.map((i) => points[i]);
        const keyOf = (p) => String(p.bundleAddress || p.address || "").trim().toLowerCase();
        const groups = [];
        const byKey = new Map();
        for (let i = 0; i < geoPoints.length; i++) {
          const key = keyOf(geoPoints[i]) || "__a" + i;
          let g = byKey.get(key);
          if (!g) {
            g = { idxs: [], lat: geoPoints[i].lat, lon: geoPoints[i].lon };
            byKey.set(key, g);
            groups.push(g);
          }
          g.idxs.push(i);
        }
        const reps = groups.map((g) => ({ lat: g.lat, lon: g.lon }));
        withBase = !!(base && Number.isFinite(base.lat) && Number.isFinite(base.lon));
        const osmPoints = withBase ? [base].concat(reps) : reps.slice();

        let nn = null;
        try {
          const matrix = await gisDurationMatrix(osmPoints);
          if (matrix && matrix.length >= osmPoints.length &&
              !matrix.some((row) => row.some((t) => !Number.isFinite(t)))) {
            nn = nearestByTime(reps, matrix, withBase, osmPoints);
            method = "gis";
          }
        } catch { /* запасной */ }
        try {
          if (!nn) {
            const matrix = await tomtomDurationMatrix(osmPoints);
            if (matrix && matrix.length >= osmPoints.length &&
                !matrix.some((row) => row.some((t) => !Number.isFinite(t)))) {
              nn = nearestByTime(reps, matrix, withBase, osmPoints);
              method = "tomtom";
            }
          }
        } catch { /* запасной */ }
        try {
          if (!nn) {
            const matrix = await osrmDurationMatrix(osmPoints);
            nn = (matrix && matrix.length >= osmPoints.length)
              ? nearestByTime(reps, matrix, withBase, osmPoints)
              : null;
            if (nn) method = "osrm";
          }
        } catch { /* запасной */ }
        if (!nn) nn = nearestNeighbor(reps, base);

        const flat = [];
        for (const gIdx of nn) {
          const g = groups[gIdx];
          if (!g) continue;
          for (const ci of g.idxs) flat.push(ci);
        }
        order = flat.map((k) => geoIdx[k]).concat(ungeoIdx);
      }

      await persistDb();
      return sendJson(res, 200, {
        ok: true,
        order: order.map((i) => points[i].id),
        unresolved: ungeoIdx.map((i) => points[i].id),
        method,
        baseUnresolved: withBase === false && !!String(body.baseAddress || "").trim(),
        clients: points.map((p) => ({
          id: p.id,
          lat: Number.isFinite(p.lat) ? p.lat : null,
          lon: Number.isFinite(p.lon) ? p.lon : null,
        })),
      });
    }

    // Километраж между точками маршрута.
    if (urlPath === "/api/drivers/route-km" && method === "POST") {
      const body = await readBody(req);
      const pts = Array.isArray(body.points) ? body.points : [];
      const clean = pts
        .map((p) => ({
          lat: Number(p && p.lat),
          lon: Number(p && p.lon),
        }))
        .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lon));
      if (clean.length < 2) return sendJson(res, 200, { ok: true, method: "empty", segs: [] });

      let matrix = null;
      try { matrix = await gisDistanceMatrix(clean); } catch { matrix = null; }
      const byMatrix = matrix && matrix.length >= clean.length &&
        !matrix.some((row) => row.some((t) => !Number.isFinite(t)));

      const segs = [];
      for (let i = 0; i < clean.length - 1; i++) {
        let km;
        if (byMatrix) {
          km = Number(matrix[i][i + 1]);
        } else {
          km = haversineKm(clean[i], clean[i + 1]);
        }
        segs.push({
          from: i,
          to: i + 1,
          km: Math.round(km * 10) / 10,
        });
      }
      return sendJson(res, 200, { ok: true, method: byMatrix ? "gis" : "straight", segs });
    }

    // Километраж от базы до первой точки.
    if (urlPath === "/api/drivers/base-km" && method === "POST") {
      const body = await readBody(req);
      const baseAddress = String(body.baseAddress || "").trim();
      const firstLat = Number(body.firstLat);
      const firstLon = Number(body.firstLon);
      if (!baseAddress || !Number.isFinite(firstLat) || !Number.isFinite(firstLon)) {
        return sendJson(res, 200, { ok: true, km: null, method: "empty" });
      }
      let base = null;
      try { base = await geocodeAddress(baseAddress); } catch { base = null; }
      if (!base || !Number.isFinite(base.lat) || !Number.isFinite(base.lon)) {
        return sendJson(res, 200, { ok: true, km: null, method: "base_unresolved" });
      }
      let km = null;
      let method = "straight";
      try {
        const matrix = await gisDistanceMatrix([base, { lat: firstLat, lon: firstLon }]);
        if (matrix && matrix.length >= 2 && Number.isFinite(matrix[0][1])) {
          km = Number(matrix[0][1]);
          method = "gis";
        }
      } catch { /* запасной */ }
      if (!Number.isFinite(km)) {
        km = haversineKm(base, { lat: firstLat, lon: firstLon });
        method = "straight";
      }
      return sendJson(res, 200, {
        ok: true,
        km: Math.round(km * 10) / 10,
        method,
        base: { lat: base.lat, lon: base.lon },
      });
    }

    return false;
  };
};
