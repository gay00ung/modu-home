"use strict";

const byId = (id) => document.getElementById(id);
const roomIdPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const placeSearchUrl = document.querySelector('meta[name="place-search"]').content;
const tileUrl = document.querySelector('meta[name="map-tiles"]').content;
const sections = ["pick", "review-panel", "sent"];
const searchCache = new Map();

let pairing;
let expiresAt = 0;
let map;
let marker;
let point;
let pending;
let delivery;
let busy = false;
let searching = false;
let lastSearch = 0;

class RequestError extends Error {
  constructor(status) {
    super("Request failed");
    this.status = status;
  }
}

function readPairing() {
  const fields = new URLSearchParams(location.hash.slice(1));
  const room = fields.get("room");
  const token = fields.get("token");
  if (location.hash) history.replaceState(null, "", location.pathname);
  if (Array.from(fields).length !== 3 || fields.get("v") !== "1" ||
      !roomIdPattern.test(room ?? "") || !tokenPattern.test(token ?? "")) return null;
  return { room, token };
}

function announce(id, message) {
  const notice = byId(id);
  notice.textContent = message;
  notice.hidden = !message;
}

function show(id) {
  sections.forEach((section) => { byId(section).hidden = section !== id; });
  if (id === "pick" && map) requestAnimationFrame(() => map.invalidateSize({ animate: false }));
}

async function jsonRequest(url, options) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 6_000);
  try {
    const response = await fetch(url, {
      ...options, signal: controller.signal, credentials: "omit", redirect: "error",
    });
    if (!response.ok) throw new RequestError(response.status);
    return await response.json();
  } finally {
    clearTimeout(timeout);
  }
}

function relay(suffix = "", body) {
  return jsonRequest("/v1/rooms/" + pairing.room + suffix, {
    method: body === undefined ? "GET" : "POST",
    mode: "same-origin",
    cache: "no-store",
    headers: {
      Authorization: "Bearer " + pairing.token,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

function available() { return Date.now() < expiresAt; }

function expire() {
  expiresAt = 0;
  if (delivery === "stored") return;
  show(null);
  announce("connection", "연결이 끝났어요. 차량에서 새 QR을 열어주세요");
}

function cleanText(value, maximum) {
  return typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f-\u009f]/g, "").trim().slice(0, maximum) : "";
}

function pointDetail() {
  return point.address || point.lat.toFixed(6) + ", " + point.lon.toFixed(6);
}

function choosePoint(position, place) {
  const { lat, lng } = map.wrapLatLng(position);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 85 || Math.abs(lng) > 180 ||
      (lat === 0 && lng === 0)) return;
  point = {
    lat, lon: lng,
    name: cleanText(place?.name, 120) || "동승자가 찍은 하차지",
    address: cleanText(place?.address, 300),
  };
  if (!marker) {
    marker = L.marker([lat, lng], {
      icon: L.divIcon({
        className: "dropoff-pin", html: '<span aria-hidden="true">●</span>',
        iconSize: [34, 34], iconAnchor: [5, 32],
      }),
      title: "선택한 하차지", alt: "선택한 하차지",
    }).addTo(map);
  } else {
    marker.setLatLng([lat, lng]);
  }
  byId("picked-name").textContent = point.name;
  byId("picked-detail").textContent = pointDetail();
  byId("review").disabled = false;
  announce("map-status", "");
}

function pickFromMap(position) {
  if (map.getZoom() < 14) {
    map.setView(position, 16);
    announce("map-status", "확대한 지도에서 실제로 내릴 지점을 눌러주세요");
    return;
  }
  choosePoint(position);
}

function openMap() {
  map = L.map("map", { minZoom: 5, maxZoom: 19, worldCopyJump: true }).setView([36.4, 127.8], 7);
  map.attributionControl.setPrefix(false);
  const tiles = L.tileLayer(tileUrl, {
    maxZoom: 19,
    attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  });
  tiles.on("tileerror", () => announce("map-status", "일부 지도를 불러오지 못했어요. 이동하거나 장소를 검색해보세요"));
  tiles.on("load", () => {
    if (byId("map-status").textContent.startsWith("일부 지도")) announce("map-status", "");
  });
  tiles.addTo(map);
  map.on("click", (event) => pickFromMap(event.latlng));
}

function resultsFrom(data) {
  if (!Array.isArray(data?.features)) return [];
  return data.features.slice(0, 5).flatMap((feature) => {
    const coordinates = feature?.geometry?.coordinates;
    if (feature?.geometry?.type !== "Point" || !Array.isArray(coordinates)) return [];
    const [lon, lat] = coordinates;
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 85 || Math.abs(lon) > 180) return [];
    const props = feature.properties ?? {};
    const street = [props.street, props.housenumber].filter(Boolean).join(" ");
    const name = cleanText(props.name || street, 120);
    const address = [...new Set([props.state, props.city, props.district, street].filter(Boolean))].join(" ");
    return name ? [{ name, address: cleanText(address, 300), lat, lon }] : [];
  });
}

function showResults(places) {
  const list = byId("results");
  list.replaceChildren();
  for (const place of places) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "quiet";
    const name = document.createElement("strong");
    name.textContent = place.name;
    const address = document.createElement("span");
    address.textContent = place.address;
    button.append(name, address);
    button.addEventListener("click", () => {
      map.setView([place.lat, place.lon], 17);
      choosePoint(L.latLng(place.lat, place.lon), place);
      list.hidden = true;
    });
    item.append(button);
    list.append(item);
  }
  list.hidden = places.length === 0;
  announce("search-status", places.length ? "" : "검색 결과가 없어요. 도로명이나 가까운 장소를 찾아보세요");
}

async function search(event) {
  event.preventDefault();
  const query = cleanText(byId("query").value, 80);
  if (searching || !available() || query.length < 2) return;
  const key = query.toLocaleLowerCase("ko");
  if (searchCache.has(key)) {
    showResults(searchCache.get(key));
    return;
  }
  if (Date.now() - lastSearch < 1_000) return;
  lastSearch = Date.now();
  searching = true;
  byId("search-button").disabled = true;
  byId("results").hidden = true;
  announce("search-status", "장소를 찾고 있어요");
  try {
    const url = new URL(placeSearchUrl);
    url.search = new URLSearchParams({ q: query, limit: "5", bbox: "124,33,132,39" });
    const places = resultsFrom(await jsonRequest(url));
    if (searchCache.size >= 20) searchCache.delete(searchCache.keys().next().value);
    searchCache.set(key, places);
    showResults(places);
  } catch {
    announce("search-status", "검색을 마치지 못했어요. 다시 검색하거나 지도에서 직접 찍어주세요");
  } finally {
    searching = false;
    byId("search-button").disabled = false;
  }
}

function locate() {
  if (!navigator.geolocation) {
    announce("map-status", "이 브라우저에서는 현재 위치를 사용할 수 없어요. 장소를 검색해주세요");
    return;
  }
  byId("locate").disabled = true;
  const done = (message) => {
    byId("locate").disabled = false;
    announce("map-status", message);
  };
  navigator.geolocation.getCurrentPosition(({ coords }) => {
    map.setView([coords.latitude, coords.longitude], 17);
    done("지도에서 실제로 내릴 지점을 눌러주세요");
  }, () => done("현재 위치를 확인하지 못했어요. 장소를 검색하거나 지도를 움직여주세요"),
  { timeout: 7_000, maximumAge: 10_000 });
}

function uuid() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

function makeDropoff(passenger) {
  const messageId = uuid();
  const created = Date.now();
  const name = cleanText(byId("place-label").value, 60) || point.name;
  const fields = new URLSearchParams({
    v: "2", id: "web-" + messageId, r: "1", created, expires: created + 24 * 60 * 60 * 1_000,
    kind: "DROPOFF", name, lat: point.lat.toFixed(6), lon: point.lon.toFixed(6),
    address: point.address, placeId: "", subId: "", provider: "",
    passenger, arrival: "",
  });
  return { messageId, name, passenger, link: "modu-home://proposal?" + fields.toString() };
}

function updateSendControls() {
  byId("send-button").disabled = busy || !available();
  byId("send-button").textContent = busy ? "차량에 전달 중" : delivery === "queued" ? "수신 상태 확인"
    : pending ? "같은 제안 다시 보내기" : "차량으로 보내기";
  for (const id of ["back", "place-label", "passenger"]) byId(id).disabled = pending != null;
}

function applyDelivery(result) {
  if (!["queued", "stored", "rejected"].includes(result?.status)) throw new Error("Invalid delivery");
  delivery = result.status;
  if (delivery === "stored") {
    byId("sent-passenger").textContent = pending.passenger;
    byId("sent-place").textContent = pending.name;
    announce("connection", "");
    show("sent");
  } else if (delivery === "rejected") {
    pending = null;
    delivery = undefined;
    announce("send-status", "차량에 보관하지 못했어요. 하차지를 확인하고 다시 보내주세요");
  }
}

async function send(event) {
  event.preventDefault();
  if (busy || !point || !available() || delivery === "stored") return;
  if (!pending) {
    const nameInput = byId("passenger");
    const passenger = cleanText(nameInput.value, 32);
    if (!passenger) {
      nameInput.setAttribute("aria-invalid", "true");
      announce("send-status", "이름이나 닉네임을 입력해주세요");
      nameInput.focus();
      return;
    }
    pending = makeDropoff(passenger);
  }
  busy = true;
  updateSendControls();
  announce("send-status", "차량 수신함에 저장됐는지 확인하고 있어요");
  try {
    if (delivery !== "queued") {
      const { messageId, link } = pending;
      applyDelivery(await relay("/messages", { messageId, link }));
    }
    const pollUntil = Date.now() + 18_000;
    while (delivery === "queued" && available() && Date.now() < pollUntil) {
      await new Promise((resolve) => setTimeout(resolve, 1_500));
      if (!available()) break;
      applyDelivery(await relay("/messages/" + pending.messageId));
    }
    if (delivery === "queued") announce("send-status", "차량 수신을 기다리고 있어요. 차량 화면을 열고 수신 상태를 확인해주세요");
  } catch (error) {
    if (error.status === 401 || error.status === 410) expire();
    else if (error.status === 400 || error.status === 409) {
      pending = null;
      delivery = undefined;
      announce("send-status", "보낼 내용을 확인한 뒤 다시 보내주세요");
    } else {
      announce("send-status", "응답을 확인하지 못했어요. 같은 제안으로 다시 확인할 수 있어요");
    }
  } finally {
    busy = false;
    updateSendControls();
  }
}

async function start() {
  pairing = readPairing();
  if (!pairing) {
    announce("connection", "차량 화면의 하차지 QR을 찍어주세요");
    return;
  }
  try {
    const welcome = await relay();
    if (!Number.isSafeInteger(welcome?.expiresAtMillis) || welcome.expiresAtMillis <= Date.now()) {
      expire();
      return;
    }
    expiresAt = welcome.expiresAtMillis;
    setTimeout(expire, Math.min(expiresAt - Date.now(), 20 * 60 * 1_000));
    announce("connection", "");
    show("pick");
    openMap();
  } catch (error) {
    if (error.status === 401 || error.status === 410) expire();
    else {
      show(null);
      announce("connection", "차량 연결을 확인하지 못했어요. QR을 다시 열어주세요");
    }
  }
}

byId("search").addEventListener("submit", search);
byId("send").addEventListener("submit", send);
byId("passenger").addEventListener("input", (event) => {
  if (event.target.getAttribute("aria-invalid") !== "true") return;
  event.target.removeAttribute("aria-invalid");
  announce("send-status", "");
});
byId("center").addEventListener("click", () => pickFromMap(map.getCenter()));
byId("locate").addEventListener("click", locate);
byId("back").addEventListener("click", () => show("pick"));
byId("review").addEventListener("click", () => {
  if (!point || !available()) return;
  byId("review-name").textContent = point.name;
  byId("review-detail").textContent = pointDetail();
  byId("place-label").value = "";
  show("review-panel");
});
window.addEventListener("hashchange", () => { if (location.hash) location.reload(); });
start();
