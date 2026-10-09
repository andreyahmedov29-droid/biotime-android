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
  resolvePublicIp,
  pushOnecPullLog,
  getOnecPullLog,
  deleteOnecPullLog,
} = {}) {
  return async function handleAppRoutes(req, res, urlPath, method, admin) {
    if (urlPath === "/api/maps/config" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      return sendJson(res, 200, { ok: true, yandexKey: yandexMapsKey });
    }

    // Диагностический «пинг» в 1С: делает POST к ONEC_API_URL.../shipment с базовой
    // авторизацией. Основной забор работает только POST (GET сервер 1С отвечает 405),
    // поэтому проверяем единственный рабочий метод и сразу даём вывод.
    if (urlPath === "/api/1c/ping" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const base = String(process.env.ONEC_API_URL || "").trim().replace(/\/+$/, "");
      const user = String(process.env.ONEC_API_USER || "").trim();
      const pass = String(process.env.ONEC_API_PASS || "").trim();
      if (!base) return sendJson(res, 200, { ok: false, error: "ONEC_API_URL не настроен" });
      const url = /\/shipment$/.test(base) ? base : base + "/shipment";
      const auth = (user || pass) ? "Basic " + Buffer.from(user + ":" + pass).toString("base64") : "";
      let ok1c = false;
      let detail = "";
      try {
        const ctrl = new AbortController();
        const to = setTimeout(() => ctrl.abort(), 8000);
        let res2;
        try {
          res2 = await fetch(url, {
            method: "POST",
            headers: Object.assign(
              { Accept: "application/json", "Content-Type": "application/json" },
              auth ? { Authorization: auth } : {}
            ),
            body: JSON.stringify({ inn: "", login: "" }),
            signal: ctrl.signal,
          });
        } finally {
          clearTimeout(to);
        }
        const text = await res2.text().catch(() => "");
        detail = "POST → HTTP " + res2.status + (text.length > 140 ? " · " + text.replace(/\s+/g, " ").slice(0, 140) : "");
        ok1c = res2.ok;
      } catch (e) {
        detail = "POST → error: " + e.message;
      }
      return sendJson(res, 200, {
        ok: true,
        ok1c,
        summary: ok1c ? "1С доступна (POST): " + url : "1С недоступна — " + detail,
        results: [detail],
      });
    }

    // Журнал заборов из 1С (админ): последние операции, что и когда забрали.
    if (urlPath === "/api/1c/log" && method === "GET") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const rows = getOnecPullLog ? getOnecPullLog() : [];
      return sendJson(res, 200, { ok: true, rows });
    }

    // Удалить запись из журнала заборов 1С (админ) — чтобы накладную можно было
    // забрать из 1С повторно (защита от дублей снимается для этого номера).
    if (urlPath === "/api/1c/log/delete" && method === "POST") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const body = await readBody(req);
      const id = String((body && body.id) || "");
      if (!id) return sendJson(res, 422, { ok: false, error: "no id" });
      const ok = deleteOnecPullLog ? deleteOnecPullLog(id) : false;
      return sendJson(res, ok ? 200 : 404, ok ? { ok: true } : { ok: false, error: "не найдено" });
    }

    // Заполнить накладные выбранных клиентов из 1С (до сохранения маршрута).
    // Принимает { clients: [{ inn, login }] } (в порядке выбранных), для каждой
    // пары Inn+login запрашивает реализацию и возвращает накладные по индексам.
    if (urlPath === "/api/waybills/from-1c" && method === "POST") {
      if (!admin) return sendJson(res, 403, { error: "forbidden" });
      const body = await readBody(req);
      const clients = Array.isArray(body && body.clients) ? body.clients : [];
      const base = String(process.env.ONEC_API_URL || "").trim().replace(/\/+$/, "");
      if (!base) return sendJson(res, 200, { ok: false, error: "ONEC_API_URL не настроен" });
      const user = String(process.env.ONEC_API_USER || "").trim();
      const pass = String(process.env.ONEC_API_PASS || "").trim();
      const target = /\/shipment$/.test(base) ? base : base + "/shipment";
      const auth = (user || pass) ? "Basic " + Buffer.from(user + ":" + pass).toString("base64") : "";
      const header = Object.assign(
        { Accept: "application/json", "Content-Type": "application/json" },
        auth ? { Authorization: auth } : {}
      );
      // Параллельно (Promise.all) с коротким таймаутом: если 1С медленная/недоступна,
      // суммарно ответ не «висит» минутами (последовательно N×6с, а раньше ~N×20с —
      // это превышало таймаут шлюза, и платформа перезапускала контейнер → BH_APP_STARTING).
      const fetchOne = async (c) => {
        const inn = String((c && c.inn) || "").trim();
        const login = String((c && c.login) || "").trim();
        const clientName = String((c && (c.client || c.address || c.bundleName)) || "").trim();
        const logEvt = (partial) => {
          if (pushOnecPullLog) pushOnecPullLog(Object.assign(
            { source: "button", inn, login, clientName }, partial
          ));
        };
        if (!inn) {
          logEvt({ ok: false, reason: "no_inn", message: "у контрагента не заполнен ИНН" });
          return { ok: false, reason: "no_inn" };
        }
        try {
          const ctrl = new AbortController();
          const to = setTimeout(() => ctrl.abort(), 6000);
          let r;
          try {
            r = await fetch(target, {
              method: "POST",
              headers: header,
              body: JSON.stringify({ inn, login }),
              signal: ctrl.signal,
            });
          } finally { clearTimeout(to); }
          if (!r || !r.ok) {
            const st = r ? r.status : "?";
            logEvt({ ok: false, reason: "http_" + st, message: "1С вернула HTTP " + st });
            return { ok: false, reason: "http_" + st };
          }
          const data = await r.json().catch(() => null);
          if (data == null) {
            logEvt({ ok: false, reason: "bad_json", message: "1С вернула не-JSON ответ" });
            return { ok: false, reason: "bad_json" };
          }
          const arr = Array.isArray(data) ? data : (data ? [data] : []);
          const alreadyLog = getOnecPullLog ? getOnecPullLog() : [];
          const shipments = [];
          for (const d of arr) {
            const list = Array.isArray(d && d.id_partstiker_list) ? d.id_partstiker_list : [];
            if (!list.length) continue;
            const items = list
              .map((it) => {
                const part = String((it && (it.id_partstiker || it.partsticker)) || "").trim();
                const art = String((it && (it.articul_number || it.article || it.art)) || "").trim() || part;
                const shipmentQty = Number(it && it.shipment_quantity != null ? it.shipment_quantity : (it.quantity != null ? it.quantity : it.qty));
                const partQty = Number(it && it.quantity != null ? it.quantity : shipmentQty);
                const rawName = String((it && (it.name || it.наименование || it.id_partstiker)) || "").trim();
                const name = String(rawName || "").trim();
                const nm = (() => {
                  let n = name;
                  if (art) {
                    const esc = String(art).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
                    n = n.replace(new RegExp(esc, "gi"), " ");
                    n = n.replace(/\s+/g, " ").trim();
                  }
                  const m2 = n.match(/^(.*?)\s*\[[^\]]*\]\s*$/);
                  if (m2 && m2[1]) n = m2[1].trim();
                  return n || name;
                })();
                return {
                  art, name: nm,
                  qty: partQty > 0 ? partQty : 1,
                  scanned: 0, missing: false,
                  partsticker: part, partQty: partQty > 0 ? partQty : 1,
                  shipmentQty: shipmentQty > 0 ? shipmentQty : 1,
                };
              })
              .filter((it) => it.art);
            if (!items.length) continue;
            const buyer = String((d && (d.shipment_number || d.number)) || "").trim();
            const dup = alreadyLog.some((e) => e && e.ok && String(e.number) === buyer);
            if (dup) continue;
            shipments.push({ buyer, items });
            if (pushOnecPullLog) pushOnecPullLog({ source: "button", inn, login, ok: true, number: buyer, posCount: items.length, items });
          }
          if (shipments.length) {
            logEvt({
              ok: true,
              number: shipments.map((s) => s.buyer).join(" | "),
              posCount: shipments.reduce((n, s) => n + s.items.length, 0),
              message: "забрано накладных: " + shipments.length,
            });
            return {
              ok: true,
              buyer: shipments.map((s) => s.buyer).join(" | "),
              items: shipments.reduce((acc, s) => acc.concat(s.items), []),
            };
          }
          logEvt({ ok: false, reason: "empty", message: "1С не вернула накладных по этому ИНН/логину" });
          return { ok: false, reason: "empty" };
        } catch (e) {
          logEvt({ ok: false, reason: "err", message: "ошибка запроса к 1С: " + (e && e.message ? e.message : String(e)) });
          return { ok: false, reason: "err" };
        }
      };
      // Ограничиваем параллельность (по 3 за раз): тяжёлые ответы 1С по всем клиентам
      // одновременно могли съедать память контейнера и ронять его (OOM → рестарт →
      // экран «Приложение запускается»). Достаточно быстро, но безопасно для памяти.
      const CONCURRENT = 3;
      const out = [];
      for (let i = 0; i < clients.length; i += CONCURRENT) {
        const chunk = clients.slice(i, i + CONCURRENT);
        const res = await Promise.all(chunk.map(fetchOne));
        out.push(...res);
      }
      const filled = out.filter((o) => o.ok).length;
      const noInn = out.filter((o) => o.reason === "no_inn").length;
      return sendJson(res, 200, { ok: true, filled, noInn, waybills: out });
    }

    // Реальный публичный IP сервера (определяет сервер; снаружи в поле /api/ip).
    if (urlPath === "/api/ip" && method === "GET") {
      let ip = null;
      try {
        ip = await (resolvePublicIp ? resolvePublicIp() : Promise.resolve(null));
      } catch { ip = null; }
      return sendJson(res, 200, { ok: true, ip: ip || null });
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
