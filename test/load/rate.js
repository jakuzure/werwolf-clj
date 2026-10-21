// Pure connection-rate test: WebSocket upgrades at R per second for T seconds,
// as raw http(s) requests rather than through a WebSocket library, so that a
// refused upgrade shows the server's actual HTTP status and the first bytes of
// its body instead of a bare "error". The sockets stay open until the end, so
// the server also carries every connection it accepted. The upgrade is all it
// does: no hello is read, no room is joined.
//
//   node test/load/rate.js <ws-url> [rate per second] [seconds]
//
// This is how the join-wave failures were pinned on the proxy rather than the
// app: at fifteen or more new connections per second the status was a 503 with
// the proxy's body, while the app behind it was idle. The event-loop lag printed
// at the end says whether this process kept up with the rate it was asked for;
// if it did not, the upgrade times are partly its own. Exits 1 when an upgrade
// was refused.

const http = require('http');
const https = require('https');
const crypto = require('crypto');

const [, , TARGET, RATE = '20', SECS = '10'] = process.argv;
if (!TARGET) {
  console.error('usage: node test/load/rate.js <ws-url> [rate per second] [seconds]');
  process.exit(2);
}
const url = new URL(TARGET);
const secure = url.protocol === 'wss:' || url.protocol === 'https:';
const client = secure ? https : http;
const port = url.port || (secure ? 443 : 80);
const path = url.pathname + url.search;

const res = { attempted: 0, upgraded: 0, status: {}, errors: {}, connectMs: [], lagMax: 0, sample: null };
const socks = [];
const count = (table, key) => { table[key] = (table[key] || 0) + 1; };
{
  let last = Date.now();
  setInterval(() => {
    const now = Date.now();
    const lag = now - last - 50;
    last = now;
    if (lag > res.lagMax) res.lagMax = lag;
  }, 50);
}

function one() {
  res.attempted++;
  const t0 = Date.now();
  const req = client.request({
    host: url.hostname, port, path, method: 'GET', timeout: 15000,
    headers: {
      Connection: 'Upgrade',
      Upgrade: 'websocket',
      'Sec-WebSocket-Version': '13',
      'Sec-WebSocket-Key': crypto.randomBytes(16).toString('base64'),
    },
  });
  req.on('upgrade', (_r, socket) => {
    res.upgraded++;
    res.connectMs.push(Date.now() - t0);
    socks.push(socket);
    socket.on('error', () => {});
  });
  req.on('response', (r) => {
    const k = String(r.statusCode);
    count(res.status, k);
    let body = '';
    r.on('data', (d) => { if (body.length < 120) body += d; });
    r.on('end', () => { if (!res.sample) res.sample = `${k} ${body.slice(0, 120).replace(/\s+/g, ' ')}`; });
  });
  req.on('error', (e) => count(res.errors, e.code || e.message));
  req.on('timeout', () => { count(res.errors, 'timeout'); req.destroy(); });
  req.end();
}

// Ten ticks a second, a tenth of the rate on each, so the wave is as even as
// a timer makes it rather than one burst per second.
const iv = setInterval(() => { for (let i = 0; i < Number(RATE) / 10; i++) one(); }, 100);
setTimeout(() => {
  clearInterval(iv);
  // Long enough for the last requests to time out rather than be cut off.
  setTimeout(() => {
    const s = [...res.connectMs].sort((a, b) => a - b);
    const p = (q) => (s.length ? s[Math.min(s.length - 1, Math.floor(q * s.length))] : null);
    console.log(`rate=${RATE}/s x ${SECS}s attempted=${res.attempted} upgraded=${res.upgraded}`
      + ` status=${JSON.stringify(res.status)} errors=${JSON.stringify(res.errors)}`
      + ` upgradeMs p50=${p(0.5)} p95=${p(0.95)} max=${p(1)} looplagMax=${res.lagMax}ms`
      + ` sample=${res.sample || '-'}`);
    socks.forEach((x) => x.destroy());
    process.exit(res.upgraded === res.attempted ? 0 : 1);
  }, 16000);
}, Number(SECS) * 1000);
