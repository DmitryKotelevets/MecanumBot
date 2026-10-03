// MecanumBot pilot (stage 5 spec §4.4, §7). No build step, no libraries.
// Safety: drive frames go out only while the deadman (Shift or Hold) is down; anything odd releases it.
'use strict';

const $ = (id) => document.getElementById(id);

const SEND_MS = 25;              // 40 Hz while the deadman is held
const PING_MS = 1000;
const RTT_LAG_MS = 200;          // red border
const VIDEO_LAG_MS = 1000;       // red border
const VIDEO_STALE_MS = 3000;     // reload <img>
const DEFAULT_LIMIT = 30;        // %; reset on every page load

const state = {
  ws: null,
  backoff: 500,
  connected: false,
  role: null,            // 'driver' | 'watcher'
  mode: 'AUTO',
  deadman: false,
  keys: new Set(),
  stick: { vx: 0, vy: 0, w: 0 },
  lastInput: 'keys',     // the last input touched wins
  limit: DEFAULT_LIMIT / 100,
  stops: null,
  rtt: null,
  telemetry: null,
  lastStreamLoad: 0,
};

// ---------- WebSocket ----------

function connect() {
  const ws = new WebSocket(`ws://${location.host}/ws`);
  state.ws = ws;
  ws.onopen = () => {
    state.connected = true;
    state.backoff = 500;
    render();
  };
  ws.onmessage = (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch { return; }
    if (m.t === 'status') onStatus(m);
    else if (m.t === 'telemetry') onTelemetry(m);
    else if (m.t === 'pong') state.rtt = Date.now() - m.ts;
    render();
  };
  ws.onclose = () => {
    if (state.ws !== ws) return;
    state.connected = false;
    state.role = null;
    state.stops = null;
    releaseDeadman();
    render();
    setTimeout(connect, state.backoff);
    state.backoff = Math.min(state.backoff * 2, 5000);
  };
}

function send(msg) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(msg));
}

function onStatus(m) {
  if (m.role !== state.role) releaseDeadman(); // a new role never inherits a held deadman
  state.role = m.role;
  state.mode = m.mode;
}

function onTelemetry(m) {
  // A STOP from anywhere (another tab, the robot's own screen) needs a new press to drive again.
  // Any change counts: a new session on the phone starts again from 0.
  if (state.stops !== null && m.stops !== state.stops) releaseDeadman();
  if (m.phase !== 'READY') releaseDeadman();
  state.stops = m.stops;
  state.telemetry = m;
  checkVideo(m);
}

// ---------- Driving ----------

const isDriver = () => state.connected && state.role === 'driver';

function pressDeadman() {
  if (!isDriver()) return;
  state.deadman = true;
  render();
}

function releaseDeadman() {
  const was = state.deadman;
  state.deadman = false;
  // Nothing held survives a release: a key whose keyup got lost must not drive on the next press.
  state.keys.clear();
  state.stick.vx = 0; state.stick.vy = 0; state.stick.w = 0;
  document.querySelectorAll('.stick .knob').forEach((k) => { k.style.transform = ''; });
  if (was && isDriver()) send({ t: 'drive', vx: 0, vy: 0, w: 0, en: false });
  render();
}

function stop() {
  state.deadman = false;
  send({ t: 'stop' });
  render();
}

function axis(plus, minus) {
  return (state.keys.has(plus) ? 1 : 0) - (state.keys.has(minus) ? 1 : 0);
}

function command() {
  const raw = state.lastInput === 'keys'
    ? { vx: axis('KeyD', 'KeyA'), vy: axis('KeyW', 'KeyS'), w: axis('KeyE', 'KeyQ') }
    : state.stick;
  const k = state.limit;
  return { vx: raw.vx * k, vy: raw.vy * k, w: raw.w * k };
}

setInterval(() => {
  if (!state.deadman) return;
  if (!isDriver()) { state.deadman = false; render(); return; }
  const c = command();
  send({ t: 'drive', vx: round(c.vx), vy: round(c.vy), w: round(c.w), en: true });
}, SEND_MS);

setInterval(() => send({ t: 'ping', ts: Date.now() }), PING_MS);

const round = (v) => Math.round(v * 1000) / 1000;

// ---------- Keyboard ----------

const MOVE_KEYS = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'KeyQ', 'KeyE']);

window.addEventListener('keydown', (e) => {
  if (e.code === 'Space') { e.preventDefault(); stop(); return; }
  if (e.metaKey || e.ctrlKey || e.altKey) return; // macOS swallows keyup under Cmd: the key would stick
  if (e.key === 'Shift') { if (!e.repeat) pressDeadman(); return; }
  if (MOVE_KEYS.has(e.code)) {
    e.preventDefault();
    state.keys.add(e.code);
    state.lastInput = 'keys';
  }
});

window.addEventListener('keyup', (e) => {
  if (e.key === 'Shift') { releaseDeadman(); return; }
  state.keys.delete(e.code);
});

function dropEverything() {
  state.keys.clear();
  releaseDeadman();
}
window.addEventListener('blur', dropEverything);
document.addEventListener('visibilitychange', () => { if (document.hidden) dropEverything(); });

// ---------- Buttons ----------

const hold = $('hold');
hold.addEventListener('pointerdown', (e) => { hold.setPointerCapture(e.pointerId); pressDeadman(); });
for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) hold.addEventListener(ev, releaseDeadman);

$('stop').addEventListener('pointerdown', stop); // not click: that waits for the release

const limit = $('limit');
limit.value = DEFAULT_LIMIT; // browsers may restore the old value; the spec wants 30 % on every load
limit.addEventListener('input', () => { state.limit = Number(limit.value) / 100; render(); });

// ---------- Sticks (mouse and touch) ----------

function setupStick(el) {
  const knob = el.querySelector('.knob');
  const both = el.dataset.axes === 'xy';
  const move = (e) => {
    const r = el.getBoundingClientRect();
    const radius = r.width / 2;
    let x = (e.clientX - r.left - radius) / radius;
    let y = (e.clientY - r.top - radius) / radius;
    const len = Math.hypot(x, y);
    if (len > 1) { x /= len; y /= len; }
    if (!both) y = 0;
    knob.style.transform = `translate(${x * radius * 0.7}px, ${y * radius * 0.7}px)`;
    if (both) { state.stick.vx = x; state.stick.vy = -y; } else { state.stick.w = x; }
    state.lastInput = 'sticks';
  };
  const end = () => {
    knob.style.transform = '';
    if (both) { state.stick.vx = 0; state.stick.vy = 0; } else { state.stick.w = 0; }
  };
  el.addEventListener('pointerdown', (e) => { el.setPointerCapture(e.pointerId); move(e); });
  el.addEventListener('pointermove', (e) => { if (el.hasPointerCapture(e.pointerId)) move(e); });
  for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) el.addEventListener(ev, end);
}
setupStick($('left-stick'));
setupStick($('right-stick'));

// ---------- Video ----------

const stream = $('stream');

function loadStream() {
  state.lastStreamLoad = Date.now();
  stream.src = `/stream?ts=${state.lastStreamLoad}`;
}

stream.addEventListener('error', () => setTimeout(loadStream, 1000));

function checkVideo(m) {
  const stale = m.video !== 'off' && (m.video_age_ms === null || m.video_age_ms > VIDEO_STALE_MS);
  if (stale && Date.now() - state.lastStreamLoad > 5000) loadStream();
}

// ---------- Rendering ----------

function text(id, value) { $(id).textContent = value; }

function render() {
  const t = state.telemetry;
  document.body.classList.toggle('watcher', state.role === 'watcher');

  const conn = $('conn');
  conn.textContent = state.connected ? 'online' : 'offline';
  conn.className = 'pill ' + (state.connected ? 'ok' : 'bad');
  const role = $('role');
  role.textContent = state.role ?? '—';
  role.className = 'pill' + (state.role === 'driver' ? ' driver' : '');

  text('rtt', state.rtt === null ? '—' : `${state.rtt} ms`);
  text('vm', t && t.vm !== null ? `${t.vm.toFixed(2)} V` : '—');
  text('active', t && t.active ? t.active : '—');
  text('temp', t && t.temp_c !== null ? `${t.temp_c.toFixed(1)} °C` : '—');
  text('video', t ? t.video : '—');
  text('limit-value', `${Math.round(state.limit * 100)} %`);
  hold.classList.toggle('on', state.deadman);

  const lagging = (state.rtt !== null && state.rtt > RTT_LAG_MS) ||
    (t && t.video !== 'off' && t.video_age_ms !== null && t.video_age_ms > VIDEO_LAG_MS);
  $('view').classList.toggle('lagging', Boolean(lagging));

  renderBanners(t);
}

function renderBanners(t) {
  const list = [];
  if (!state.connected) list.push(['Disconnected — reconnecting…', true]);
  else {
    if (state.role === 'watcher') list.push(['Watching — another pilot is driving', false]);
    if (state.mode === 'LOCAL_ONLY') list.push(['Remote control is off on the robot (LOCAL_ONLY)', true]);
    if (t) {
      if (t.phase !== 'READY') list.push([`Robot not ready: ${t.phase}`, true]);
      if (t.failsafe) list.push(['FAILSAFE — motors stopped', true]);
      if (t.fault) list.push(['Motor driver FAULT', true]);
      const temp = t.temp_c === null ? '' : ` — phone at ${t.temp_c.toFixed(1)} °C`;
      if (t.video === 'reduced') list.push([`Video reduced${temp}`, false]);
      if (t.video === 'off') list.push([`Video off${temp}`, true]);
    }
  }
  const box = $('banners');
  box.replaceChildren(...list.map(([msg, danger]) => {
    const div = document.createElement('div');
    div.className = 'banner' + (danger ? ' danger' : '');
    div.textContent = msg;
    return div;
  }));
}

loadStream();
connect();
render();
