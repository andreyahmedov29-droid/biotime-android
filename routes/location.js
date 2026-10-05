// Модуль-обработчик геолокации водителей (/api/drivers/location GET+POST).
// Вынесен из server.js дословно; зависимости инъекцией (DI).
module.exports = function createLocationHandler({
  getDb,
  sendJson,
  readBody,
  isDriver,
  isDriversGroupOnly,
  motionDayKey,
  tracksByDay,
  scheduleTracksSave,
} = {}) {
  return async function handleLocationRoutes(req, res, urlPath, method, user, admin) {
    const db = getDb ? getDb() : {};

    if (urlPath === "/api/drivers/location" && method === "POST") {
      if (!isDriver(user, db)) return sendJson(res, 403, { error: "forbidden" });
      const body = await readBody(req);
      const lat = Number(body.lat);
      const lon = Number(body.lon);
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
        return sendJson(res, 422, { error: "bad coordinates" });
      }
      const uid = String(user.id);
      const atNow = Date.now();
      db.liveLocations[uid] = {
        lat, lon, at: atNow,
        name: user.name || "",
        routeId: body.routeId != null ? String(body.routeId) : "",
      };
      const tr = db.tracks[uid] || (db.tracks[uid] = []);
      const last = tr[tr.length - 1];
      const moved = !last
        || Math.abs(last.lat - lat) > 1e-4
        || Math.abs(last.lon - lon) > 1e-4
        || (atNow - last.at) > 30000;
      if (moved) tr.push({ lat, lon, at: atNow });
      const dayK = motionDayKey(atNow);
      const dTrack = (tracksByDay[dayK] || (tracksByDay[dayK] = {}))[uid] ||
        ((tracksByDay[dayK][uid] = []));
      const dLast = dTrack[dTrack.length - 1];
      if (!dLast || (atNow - dLast[2]) >= 20000 || Math.abs(dLast[0] - lat) > 5e-4 || Math.abs(dLast[1] - lon) > 5e-4) {
        dTrack.push([lat, lon, atNow]);
        if (dTrack.length > 4000) dTrack.splice(0, dTrack.length - 4000);
        const cutoff = motionDayKey(atNow - 60 * 24 * 3600000);
        Object.keys(tracksByDay).forEach((k) => { if (k < cutoff) delete tracksByDay[k]; });
        scheduleTracksSave();
      }
      while (tr.length && atNow - tr[0].at > 6 * 3600000) tr.shift();
      if (tr.length > 500) tr.splice(0, tr.length - 500);
      return sendJson(res, 200, { ok: true });
    }

    if (urlPath === "/api/drivers/location" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const now = Date.now();
      const freshWindow = 10 * 60 * 1000;
      const rows = [];
      for (const [id, loc] of Object.entries(db.liveLocations || {})) {
        if (!isDriversGroupOnly({ id }, db)) {
          delete db.liveLocations[id];
          delete db.tracks[id];
          continue;
        }
        if (!loc || !Number.isFinite(loc.lat) || !Number.isFinite(loc.lon)) continue;
        if (now - loc.at > freshWindow) continue;
        rows.push({ id, name: loc.name || "", lat: loc.lat, lon: loc.lon, at: loc.at, routeId: loc.routeId || "" });
      }
      return sendJson(res, 200, { ok: true, rows });
    }
    return false;
  };
};
