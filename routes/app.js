// Модуль-обработчик «версия/состояние приложения и карта» (/api/maps/config,
// /api/app/*). Вынесен из server.js (handleAppRoutes): логика дословно.
//
// Зависимости через фабрику (DI): getDb; persistDb; sendJson; readBody;
// writeVersionSource/readVersionSource/fetchRemoteApkVersion (работа с version.json
// и GitHub-версией); yandexMapsKey — ключ API карт (из server.js/окружения).
//
// ВАЖНО: WEB_BUILD — текущая сборка ВЕБ-версии, которую клиент сверяет для
// авто-перезагрузки. Единственное место: /api/app/web-version и <meta
// app-version> в index.html должны совпадать и обновляться при каждом деплое.
const WEB_BUILD = "20260927c201";

module.exports = function createAppHandler({
  getDb,
  persistDb,
  sendJson,
  readBody,
  writeVersionSource,
  readVersionSource,
  fetchRemoteApkVersion,
  yandexMapsKey,
} = {}) {
  return async function handleAppRoutes(req, res, urlPath, method, admin) {
    if (urlPath === "/api/maps/config" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      return sendJson(res, 200, { ok: true, yandexKey: yandexMapsKey });
    }

    if (urlPath === "/api/app/update" && method === "POST") {
      const expected = String(process.env.SERVER_UPDATE_TOKEN || "");
      const got = String((req.headers["x-update-token"] || "").toString());
      if (!expected || got !== expected) {
        return sendJson(res, 401, { ok: false, error: "invalid_token" });
      }
      try {
        const body = await readBody(req);
        const vc = Number(body.versionCode);
        const vn = String(body.versionName || "").trim();
        const url = String(body.apkUrl || "").trim();
        const notes = String(body.notes || "").trim();
        if (!Number.isFinite(vc) || vc <= 0 || !vn) {
          return sendJson(res, 400, { ok: false, error: "bad_payload" });
        }
        const db = getDb ? getDb() : {};
        db.params = db.params || {};
        db.params.updateVersionCode = vc;
        db.params.updateVersionName = vn;
        if (url) db.params.updateApkUrl = url;
        if (notes) db.params.updateNotes = notes;
        await persistDb();
        writeVersionSource(vc, vn, notes);
        console.log(`[update] Версия обновлена: versionCode=${vc} versionName=${vn}`);
        return sendJson(res, 200, { ok: true, versionCode: vc, versionName: vn, apkUrl: url });
      } catch (err) {
        return sendJson(res, 400, { ok: false, error: "bad_json" });
      }
    }

    if (urlPath === "/api/app/web-version" && method === "GET") {
      return sendJson(res, 200, { ok: true, version: WEB_BUILD });
    }

    if (urlPath === "/api/app/update-info" && method === "GET") {
      const db = getDb ? getDb() : {};
      const p = db.params || {};
      const src = readVersionSource();
      let remote = null;
      try { remote = await fetchRemoteApkVersion(); } catch { remote = null; }
      const vc = remote && remote.versionCode
        ? remote.versionCode
        : (p.updateVersionCode != null
            ? p.updateVersionCode
            : (src.versionCode || Number(process.env.APP_UPDATE_VERSION_CODE || 4)));
      const vn = remote && remote.versionName
        ? String(remote.versionName)
        : (p.updateVersionName
            ? String(p.updateVersionName)
            : (src.versionName || String(process.env.APP_UPDATE_VERSION_NAME || "1.0.3")));
      const url = p.updateApkUrl
        ? String(p.updateApkUrl)
        : String(
            process.env.APP_UPDATE_APK_URL ||
              "https://github.com/andreyahmedov29-droid/biotime-android/releases/download/biotime-apk-latest/app-release.apk"
          );
      const notes = remote && remote.notes
        ? String(remote.notes)
        : (p.updateNotes
            ? String(p.updateNotes)
            : (src.notes || String(process.env.APP_UPDATE_NOTES || "Обновление: исправления и улучшения")));
      return sendJson(res, 200, {
        ok: true,
        versionCode: vc,
        versionName: vn,
        apkUrl: url,
        notes: notes,
        updatedAt: new Date().toISOString(),
      });
    }
    return false;
  };
};
