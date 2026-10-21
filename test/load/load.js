// Load generator for werwolf-clj: N rooms of M bots over real WebSockets,
// speaking the transit protocol exactly as the browser client does
// (:client/create with a password, :client/join, :client/command, and
// :client/resume with the welcome token when a socket closes under a seated
// bot), playing whole games to the end.
//
//   bb load [url] [rooms] [players] [label]
//   node test/load/load.js <ws-url> [rooms] [players] [label]
//
// Each bot plays by the rules of test/werwolf/sim/harness.cljc: one legal move
// drawn from its own view's :actions, :targets, :arity and :choices, never a
// GM override, and every vote for the lowest living seat so that days end in a
// verdict instead of running on for hundreds of turns. The host switches the
// narrator off and starts the game once the table is full. The client holds no
// game rules — everything a bot may do arrives in its view — which is what
// makes a bot this small.
//
// Measured: time to :server/hello (connect), create or join to :server/welcome
// (welcome), a command to the view it produced (cmd->view), ping RTT through a
// separate probe socket before and under load, errors by code, sockets that
// closed under a seated bot and whether :client/resume got the seat back, and
// this process's own event-loop lag, so that a stall in the generator is not
// blamed on the server. A progress line every five seconds, then one SUMMARY
// line of JSON. Exits 1 when a game did not finish. deploy/README.md says how
// to read the numbers, and what the first runs measured.
//
// Environment:
//   ACT_MIN, ACT_MAX  ms a bot "thinks" before each move (50..300). Human pace
//                     is about 500..2500; the default is a stress test.
//   STAGGER           ms between room creations (200)
//   MAX_GAME_MS       give a room up after this long (900000)
//   DEBUG             print the first few rejected commands with the view that
//                     led to them
//
// Node 22 is what nix builds with, so nothing newer than ES2023 in here.

const transit = require('transit-js');
// The `ws` library rather than Node's built-in WebSocket (undici): during a
// ramp the built-in one dropped a third of the connections with an EMPTY error
// while raw https upgrades to the same server succeeded at a hundred per
// second. `ws` reports a refused upgrade with the server's status code, which
// is how the 503s from the proxy's health check became visible at all.
const WS = require('ws');

const writer = transit.writer('json');
const reader = transit.reader('json');
const kw = transit.keyword;

const [, , TARGET, ROOMS = '2', PLAYERS = '6', LABEL = 'run'] = process.argv;
if (!TARGET) {
  console.error('usage: node test/load/load.js <ws-url> [rooms] [players] [label]');
  process.exit(2);
}
const N_ROOMS = Number(ROOMS);
const N_PLAYERS = Number(PLAYERS);
const env = (name, dflt) => Number(process.env[name] || dflt);
const ACT_MIN = env('ACT_MIN', 50);
const ACT_MAX = env('ACT_MAX', 300);
const STAGGER = env('STAGGER', 200);
const MAX_GAME_MS = env('MAX_GAME_MS', 900000);
// Nobody who opens the landing page during a run should land at a bot table.
const PASSWORD = 'lasttest';
// A refused upgrade, a hello or a welcome that never comes: waiting longer
// than this is a failure in its own right, and a bot that waited forever would
// hold its room, and the whole run, open.
const WAIT_MS = 20000;

// ------------------------------------------------------------------ transit --

// Server messages arrive as transit maps keyed by keywords. The bot reads them
// as plain objects keyed by ":keyword" strings, with keyword values turned into
// the same strings, so that a view's :actions compare with string literals.
function plain(v) {
  if (v == null) return v;
  if (transit.isKeyword(v)) return String(v);
  if (Array.isArray(v)) return v.map(plain);
  if (typeof v === 'object') {
    if (typeof v.get === 'function' && typeof v.keys === 'function') {
      const o = {};
      for (const k of v.keys()) o[String(k)] = plain(v.get(k));
      return o;
    }
    if (typeof v.forEach === 'function') {
      const a = [];
      v.forEach((x) => a.push(plain(x)));
      return a;
    }
  }
  return v;
}

// The other direction. Strings that look like keywords (":de") become
// keywords, and it recurses: the :cmd inside a :client/command must become a
// transit map with KEYWORD keys as well, or the closed schema rejects the
// whole message as :malformed — which is exactly what the first dry run
// produced, for every command.
function tval(v) {
  if (typeof v === 'string' && v.startsWith(':')) return kw(v.slice(1));
  if (Array.isArray(v)) return v.map(tval);
  if (v && typeof v === 'object' && !transit.isKeyword(v)) return tmap(v);
  return v;
}
function tmap(obj) {
  const arr = [];
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined) continue;
    arr.push(kw(k), tval(v));
  }
  return transit.map(arr);
}
const encode = (obj) => writer.write(tmap(obj));
const decode = (data) => reader.read(typeof data === 'string' ? data : data.toString('utf8'));

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
// What went wrong with a socket, in words. A refused connect on Node 20+ is an
// AggregateError (one attempt per address family) whose message is EMPTY;
// the code is the only thing it says.
function describe(err) {
  if (!err) return 'error';
  const inner = err.cause ? (err.cause.code || err.cause.message || '') : '';
  return `${err.code || ''} ${err.message || ''} ${inner}`.trim() || 'error';
}
const rand = (a, b) => a + Math.floor(Math.random() * (b - a + 1));
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

// -------------------------------------------------------------------- stats --

const stats = {
  connects: [], welcomes: [], cmdLatency: [], pingRtt: [], pingBaseline: null,
  errors: {}, unexpectedCloses: 0, reconnects: 0, resumeFailures: 0, probeCloses: 0,
  stalls: 0, aborted: 0, gamesDone: 0, gameMs: [], turns: [], winners: {},
  socketsNow: 0, socketsPeak: 0, debugged: 0, startedAt: Date.now(),
};
const count = (table, key) => { table[key] = (table[key] || 0) + 1; };
function pct(arr, p) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(p * s.length))];
}
function summ(arr) {
  return arr.length ? { n: arr.length, p50: pct(arr, 0.5), p95: pct(arr, 0.95), max: Math.max(...arr) } : null;
}

// ---------------------------------------------------------------------- bot --

// Never issued, as in the harness: they would end or distort the game rather
// than play it. :start-game and :vote-abstain are handled on their own below,
// and every :gm/ override is excluded by namespace.
const EXCLUDE = new Set([':leave', ':set-locale', ':chat', ':claim-host', ':play-again',
  ':start-game', ':begin-night', ':vote-abstain']);
const isGm = (a) => a.startsWith(':gm/');
// Actions that name a choice rather than a seat, and the key the choice
// travels under (werwolf.proto/choice-key).
const CHOICE_KEY = { ':dieb-take': 'role' };

class Bot {
  constructor(room, name) {
    this.room = room;
    this.name = name;
    this.ws = null;
    this.me = null;
    this.host = false;
    this.token = null;
    this.roomId = null;
    this.view = null;
    this.viewSeq = 0;
    this.lastViewAt = Date.now();
    this.pending = null;      // when the command awaiting its view was sent
    this.acting = false;      // a move is being "thought about"
    this.ready = false;       // seated on the current socket (welcomed)
    this.done = false;
    this.resumes = 0;
    this.resuming = false;
    this.toggled = false;
    this.autoDecked = false;
    this.started = false;
  }

  // Open a socket and wait for :server/hello. Resolves once the server has
  // greeted the socket, rejects with the reason when it has not within WAIT_MS.
  open() {
    return new Promise((resolve, reject) => {
      const t0 = Date.now();
      const ws = new WS(TARGET, { perMessageDeflate: false, handshakeTimeout: WAIT_MS });
      this.ws = ws;
      this.ready = false;
      let opened = false;
      let helloed = false;
      // A listener here keeps `ws` from turning the response into a bare
      // error, so the status code, the one fact that tells the proxy's
      // refusals from the app's, is recorded.
      ws.on('unexpected-response', (req, res) => {
        count(stats.errors, `http ${res.statusCode}`);
        reject(new Error(`connect after ${Date.now() - t0}ms: server answered ${res.statusCode}`));
        req.destroy();
      });
      ws.onopen = () => {
        opened = true;
        stats.socketsNow++;
        stats.socketsPeak = Math.max(stats.socketsPeak, stats.socketsNow);
      };
      ws.onerror = (e) => {
        reject(new Error(`connect after ${Date.now() - t0}ms: ${describe(e && e.error)}`));
      };
      ws.onclose = () => {
        if (opened) stats.socketsNow--;
        this.ready = false;
        // Only a socket that was in use counts as dropped; one that never got
        // its hello has already failed through the promise, and its caller
        // decides what to do about that.
        if (helloed && !this.done && this.roomId) {
          stats.unexpectedCloses++;
          this.resume();
        }
      };
      ws.onmessage = (ev) => {
        let m;
        try { m = decode(ev.data); } catch (_) { return; }
        const type = String(m.get(kw('type')));
        if (type === ':server/hello') {
          helloed = true;
          stats.connects.push(Date.now() - t0);
          resolve();
          return;
        }
        this.handle(type, m);
      };
      setTimeout(() => {
        if (helloed) return;
        reject(new Error('hello timeout'));
        ws.terminate();
      }, WAIT_MS);
    });
  }

  send(obj) {
    if (this.ws && this.ws.readyState === WS.OPEN) this.ws.send(encode(obj));
  }

  handle(type, m) {
    if (type === ':server/welcome') {
      this.me = m.get(kw('player-id'));
      this.host = !!m.get(kw('host?'));
      this.token = m.get(kw('token'));
      this.roomId = m.get(kw('room-id'));
      // A command sent on the old socket is not coming back; the view that
      // follows this welcome is the one to act on.
      this.ready = true;
      this.pending = null;
      if (this.welcomeResolve) {
        stats.welcomes.push(Date.now() - this.welcomeSent);
        this.settleWelcome(null);
      }
      return;
    }
    if (type === ':server/error') {
      const code = String(m.get(kw('code')));
      count(stats.errors, code);
      if (process.env.DEBUG && stats.debugged++ < 8) this.debug(code, m);
      // A create, join or resume refused. For a resume that means the seat is
      // gone or the token was refused: nothing to retry.
      if (this.welcomeReject) {
        this.settleWelcome(new Error(code));
        if (this.resuming) {
          stats.resumeFailures++;
          this.finish('resume-failed');
        }
        return;
      }
      // A rejected command is almost always a bot acting on a view that went
      // stale while it was thinking: the vote already counted, the phase over.
      // Nothing to repair — look at the current view again.
      this.pending = null;
      setTimeout(() => this.act(), 100);
      return;
    }
    if (type === ':room/view') {
      const now = Date.now();
      if (this.pending) {
        stats.cmdLatency.push(now - this.pending);
        this.pending = null;
      }
      this.view = plain(m.get(kw('view')));
      this.viewSeq++;
      this.lastViewAt = now;
      this.act();
      return;
    }
    if (type === ':server/left' || type === ':server/kicked') this.finish(type);
  }

  debug(code, m) {
    const v = this.view || {};
    const a = this.lastCmd && this.lastCmd.type;
    console.log(`DEBUG ${this.name} me=${this.me} ${code}`
      + ` detail=${JSON.stringify(plain(m.get(kw('detail'))))}`
      + ` cmd=${JSON.stringify(this.lastCmd)} phase=${v[':phase']}`
      + ` actions=${JSON.stringify(v[':actions'])}`
      + ` targets[${a}]=${JSON.stringify((v[':targets'] || {})[a])}`
      + ` arity=${JSON.stringify(v[':arity'])}`);
  }

  // The welcome that must answer a create, join or resume. One at a time.
  expectWelcome() {
    this.welcomeSent = Date.now();
    return new Promise((resolve, reject) => {
      this.welcomeResolve = resolve;
      this.welcomeReject = reject;
      setTimeout(() => { if (this.welcomeReject) this.settleWelcome(new Error('welcome timeout')); }, WAIT_MS);
    });
  }
  settleWelcome(err) {
    const resolve = this.welcomeResolve;
    const reject = this.welcomeReject;
    this.welcomeResolve = null;
    this.welcomeReject = null;
    if (err) reject(err); else resolve();
  }

  async create() {
    const welcome = this.expectWelcome();
    this.send({ type: ':client/create', name: this.name, locale: ':de', password: PASSWORD });
    await welcome;
  }
  async join(roomId) {
    const welcome = this.expectWelcome();
    this.send({ type: ':client/join', 'room-id': roomId, name: this.name, locale: ':de', password: PASSWORD });
    await welcome;
  }
  // Back on a fresh socket with the token from the last welcome, as the
  // browser does from localStorage, with the browser's backoff (werwolf.ws:
  // doubling from 500 ms, capped at 15 s). Ten tries is about a minute and a
  // half, enough for the server to restart; then the bot gives up, because a
  // server that keeps closing sockets is a finding, not something to hammer.
  async resume() {
    if (this.done) return;
    if (this.resumes >= 10) { this.finish('gave-up-resume'); return; }
    await sleep(Math.min(15000, 500 * 2 ** this.resumes));
    if (this.done) return;
    this.resumes++;
    stats.reconnects++;
    try {
      await this.open();
      this.resuming = true;
      const welcome = this.expectWelcome();
      this.send({ type: ':client/resume', 'room-id': this.roomId, token: this.token });
      await welcome;
      this.resuming = false;
    } catch (e) {
      this.resuming = false;
      this.resume();
    }
  }

  finish(why) {
    if (this.done) return;
    this.done = true;
    this.why = why;
    try { if (this.ws) this.ws.close(); } catch (_) { /* already gone */ }
    this.room.botDone(this);
  }

  // One legal move for the current view, or null when there is nothing to do.
  decide() {
    const v = this.view;
    if (!v) return null;
    const phase = v[':phase'];
    if (phase === ':game-over') {
      this.room.gameOver(v);
      this.finish('game-over');
      return null;
    }
    const all = v[':actions'] || [];
    if (phase === ':lobby') {
      // The host alone acts in the lobby, and only once everybody is seated:
      // a narrated room starts with a Spielleiter, which a table of bots does
      // not want, and a deck the automatic composition cannot deal is
      // replaced before the start the engine would refuse.
      if (!this.host || !this.room.allJoined) return null;
      if (v[':narrator'] != null && !this.toggled && all.includes(':gm/toggle-narrated')) {
        this.toggled = true;
        return { type: ':gm/toggle-narrated' };
      }
      if (v[':deal-problem'] != null && !this.autoDecked && all.includes(':gm/auto-deck')) {
        this.autoDecked = true;
        return { type: ':gm/auto-deck' };
      }
      if (!this.started && v[':deal-problem'] == null && all.includes(':start-game')) {
        this.started = true;
        this.room.startedAt = Date.now();
        return { type: ':start-game' };
      }
      return null;
    }
    const actions = all.filter((a) => !EXCLUDE.has(a) && !isGm(a));
    const targets = v[':targets'] || {};
    const arity = v[':arity'] || {};
    const choices = v[':choices'] || {};
    const usable = [];
    for (const a of actions) {
      // Only when nothing else is possible: the host advancing a phase while
      // others still have moves would skip their turns.
      if (a === ':advance-phase') continue;
      if (a === ':vote' || a === ':elect') {
        const t = (targets[a] || []).filter((x) => x !== this.me);
        if (t.length) usable.push({ type: a, target: Math.min(...t) });
        continue;
      }
      if (CHOICE_KEY[a]) {
        const c = choices[a] || [];
        if (c.length) usable.push({ type: a, [CHOICE_KEY[a]]: pick(c) });
        continue;
      }
      // :arity names only the two-target actions (Amor). Every other action
      // takes one target exactly when the view offers targets for it, and
      // none otherwise: :targets has a key for every action, empty for the
      // untargeted ones.
      const t = targets[a] || [];
      const n = arity[a] || (t.length ? 1 : 0);
      if (n === 0) usable.push({ type: a });
      else if (n === 1 && t.length) usable.push({ type: a, target: pick(t) });
      else if (n === 2 && t.length >= 2) {
        const x = pick(t);
        const y = pick(t.filter((z) => z !== x));
        usable.push({ type: a, a: x, b: y });
      }
    }
    if (usable.length) return pick(usable);
    if (actions.includes(':advance-phase')) return { type: ':advance-phase' };
    return null;
  }

  // Nothing goes out on a socket that has not been welcomed. A command sent
  // between a reconnect's open and its welcome is answered :not-in-room, which
  // would arrive while the resume is pending and be mistaken for its refusal.
  async act() {
    if (this.done || this.acting || this.pending || !this.ready) return;
    const cmd = this.decide();
    if (!cmd) return;
    this.acting = true;
    const seq = this.viewSeq;
    await sleep(rand(ACT_MIN, ACT_MAX));
    this.acting = false;
    if (this.done || !this.ready) return;
    // The world moved on while this bot was thinking: decide again on the
    // view that is current now, as a player looking up from their phone does.
    if (seq !== this.viewSeq) { this.act(); return; }
    this.room.turns++;
    this.pending = Date.now();
    this.lastCmd = cmd;
    this.send({ type: ':client/command', cmd });
    // A command the server accepts always answers with a view, and a rejected
    // one with an error; a silent one is a bug, and is not waited on forever.
    setTimeout(() => {
      if (this.pending && Date.now() - this.pending > 5000) {
        this.pending = null;
        this.act();
      }
    }, 5500);
  }
}

// --------------------------------------------------------------------- room --

class Room {
  constructor(i) {
    this.i = i;
    this.bots = [];
    this.allJoined = false;
    this.turns = 0;
    this.doneCount = 0;
    this.finished = false;
    this.startedAt = null;
  }

  async run() {
    const host = new Bot(this, `L${this.i}-Host`);
    this.bots.push(host);
    await host.open();
    await host.create();
    for (let p = 1; p < N_PLAYERS; p++) {
      const b = new Bot(this, `L${this.i}-P${p}`);
      this.bots.push(b);
      await sleep(rand(50, 150));
      try {
        await b.open();
        await b.join(host.roomId);
      } catch (e) {
        console.log(`room ${this.i}: join failed: ${e.message}`);
        b.finish('join-failed');
      }
    }
    this.allJoined = true;
    host.act();
    this.watch();
  }

  fail() {
    this.finished = true;
    this.bots.forEach((b) => b.finish('room-failed'));
  }

  gameOver(v) {
    if (this.finished) return;
    this.finished = true;
    stats.gamesDone++;
    if (this.startedAt) stats.gameMs.push(Date.now() - this.startedAt);
    stats.turns.push(this.turns);
    count(stats.winners, v[':winner'] || 'none');
    // Everybody else leaves too, as a table closing its tabs does.
    for (const b of this.bots) if (!b.done) b.finish('game-over');
  }

  botDone() { this.doneCount++; }
  get allDone() { return this.doneCount >= this.bots.length; }

  // A game whose bots all go quiet is stuck, whatever the reason: a bot that
  // lost a view, a phase waiting on a deadline, a rule the bots cannot act
  // on. Report it, nudge every bot to look at its view again, and give the
  // room up for good after MAX_GAME_MS.
  watch() {
    const t0 = Date.now();
    const iv = setInterval(() => {
      if (this.finished || this.allDone) { clearInterval(iv); return; }
      const alive = this.bots.filter((b) => !b.done);
      const quiet = Math.min(...alive.map((b) => Date.now() - b.lastViewAt));
      const v = alive[0] && alive[0].view;
      if (quiet > 90000) {
        stats.stalls++;
        const deadline = v && v[':deadline'] ? `${Math.round((v[':deadline'] - Date.now()) / 1000)}s` : 'none';
        console.log(`room ${this.i}: no view for ${Math.round(quiet / 1000)}s, phase ${v && v[':phase']}, deadline in ${deadline}`);
        alive.forEach((b) => { b.lastViewAt = Date.now(); b.pending = null; b.act(); });
      }
      if (Date.now() - t0 > MAX_GAME_MS) {
        stats.aborted++;
        console.log(`room ${this.i}: aborted after ${MAX_GAME_MS / 1000}s in phase ${v && v[':phase']}`);
        alive.forEach((b) => b.finish('aborted'));
        clearInterval(iv);
      }
    }, 5000);
  }
}

// --------------------------------------------------------------- ping probe --

// One extra socket that only pings, once a second, so the RTT it sees is the
// server's responsiveness to an idle player rather than the cost of the moves
// the bots are making. Three seconds of it before the rooms start are the
// baseline; the rest is the run. Reopened if it closes, and the closes counted.
async function pingProbe(stop) {
  let ws = null;
  let sent = null;
  const open = () => new Promise((resolve, reject) => {
    ws = new WS(TARGET, { perMessageDeflate: false, handshakeTimeout: WAIT_MS });
    ws.onopen = resolve;
    ws.onerror = (e) => reject(new Error(describe(e && e.error)));
    ws.onclose = () => { ws = null; };
    ws.onmessage = (ev) => {
      const m = decode(ev.data);
      if (String(m.get(kw('type'))) === ':server/pong' && sent) {
        stats.pingRtt.push(Date.now() - sent);
        sent = null;
      }
    };
  });
  await open();
  while (!stop.v) {
    if (!ws) {
      stats.probeCloses++;
      try { await open(); } catch (_) { await sleep(1000); continue; }
    }
    sent = Date.now();
    ws.send(encode({ type: ':client/ping' }));
    await sleep(1000);
  }
  if (ws) ws.close();
}

// ----------------------------------------------------------- event-loop lag --

// If this process's own event loop stalls — hundreds of TLS handshakes in a
// ramp will do it — every latency it measures (ping, connect, cmd->view) is
// inflated by the stall, and the server is innocent. So the stall is measured
// and reported beside them: the max over the run, and the max per progress
// line.
let lagMax = 0;
let lagWindow = 0;
{
  let last = Date.now();
  setInterval(() => {
    const now = Date.now();
    const lag = now - last - 100;
    last = now;
    if (lag > lagMax) lagMax = lag;
    if (lag > lagWindow) lagWindow = lag;
  }, 100);
}

// --------------------------------------------------------------------- main --

(async () => {
  const stop = { v: false };
  const probe = pingProbe(stop).catch((e) => console.log(`ping probe failed: ${e.message}`));
  await sleep(3000);
  stats.pingBaseline = summ(stats.pingRtt);
  stats.pingRtt = [];
  console.log(`[${LABEL}] baseline ping p50=${stats.pingBaseline ? stats.pingBaseline.p50 : '?'}ms,`
    + ` starting ${N_ROOMS} rooms x ${N_PLAYERS} against ${TARGET}`);

  const rooms = [];
  for (let i = 0; i < N_ROOMS; i++) {
    const room = new Room(i);
    rooms.push(room);
    room.run().catch((e) => { console.log(`room ${i} failed: ${e.message}`); room.fail(); });
    await sleep(STAGGER);
  }

  const t0 = Date.now();
  while (rooms.some((rm) => !(rm.finished || rm.allDone))) {
    await sleep(5000);
    const phases = {};
    for (const rm of rooms) {
      if (rm.finished) continue;
      const b = rm.bots.find((x) => !x.done && x.view);
      count(phases, b ? b.view[':phase'] : 'joining');
    }
    console.log(`[${LABEL}] +${Math.round((Date.now() - t0) / 1000)}s sockets=${stats.socketsNow}`
      + ` games done=${stats.gamesDone}/${N_ROOMS}`
      + ` ping p50=${pct(stats.pingRtt.slice(-10), 0.5)}ms`
      + ` cmd p95=${pct(stats.cmdLatency.slice(-200), 0.95)}ms`
      + ` looplag max=${lagWindow}ms errors=${JSON.stringify(stats.errors)}`
      + ` phases=${JSON.stringify(phases)}`);
    lagWindow = 0;
  }
  stop.v = true;
  await probe;

  const out = {
    label: LABEL, url: TARGET, rooms: N_ROOMS, players: N_PLAYERS,
    durationS: Math.round((Date.now() - stats.startedAt) / 1000),
    socketsPeak: stats.socketsPeak, gamesDone: stats.gamesDone,
    aborted: stats.aborted, stalls: stats.stalls, winners: stats.winners,
    turns: summ(stats.turns), gameS: summ(stats.gameMs.map((x) => Math.round(x / 1000))),
    connectMs: summ(stats.connects), welcomeMs: summ(stats.welcomes),
    cmdToViewMs: summ(stats.cmdLatency),
    pingBaselineMs: stats.pingBaseline, pingUnderLoadMs: summ(stats.pingRtt),
    probeCloses: stats.probeCloses, errors: stats.errors,
    unexpectedCloses: stats.unexpectedCloses, reconnects: stats.reconnects,
    resumeFailures: stats.resumeFailures, eventLoopLagMaxMs: lagMax,
  };
  console.log(`SUMMARY ${JSON.stringify(out)}`);
  process.exit(stats.gamesDone === N_ROOMS ? 0 : 1);
})();
