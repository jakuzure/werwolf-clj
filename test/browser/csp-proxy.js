// The production security headers, in front of the test server.
//
// Caddy sends them in production (nix/module.nix), so a browser suite that
// talks to the bare server never meets them. That is how a Content-Security-
// Policy without 'unsafe-eval' shipped while the voice module was still loaded
// by eval: every spec was green, and voice never loaded behind Caddy. Every
// spec now goes through this proxy instead, with the headers read out of the
// module itself, so a change to either side is tested against the other.
//
// Run by playwright.config.js as a second webServer:
//   PORT=<server> PROXY_PORT=<listen> LIVEKIT_URL=<ws url> node test/browser/csp-proxy.js

const fs = require('fs');
const http = require('http');
const net = require('net');
const path = require('path');

const LIVEKIT_URL = process.env.LIVEKIT_URL || 'ws://localhost:7880';

/**
 * The headers of the Caddy `header { ... }` block in nix/module.nix.
 *
 * Throws rather than returning fewer: a guard that quietly lost its policy
 * would pass every test while testing nothing.
 *
 * One substitution. connect-src names the site's own wss: origin, and in
 * production LiveKit sits behind the same origin (/livekit). Here it is a
 * separate port, so its origin takes the place of the site's. script-src and
 * every other directive are the module's, unchanged.
 */
function productionHeaders(liveKitUrl = LIVEKIT_URL) {
  const nix = fs.readFileSync(path.resolve(__dirname, '../../nix/module.nix'), 'utf8');
  const block = nix.match(/\n\s*header \{\n([\s\S]*?)\n\s*\}/);
  if (!block) throw new Error('no `header { ... }` block in nix/module.nix');
  const headers = {};
  for (const [, name, value] of block[1].matchAll(/^\s*([A-Za-z-]+) "([^"]*)"\s*$/gm)) {
    headers[name] = value.replace('wss://${cfg.domain}', new URL(liveKitUrl).origin);
  }
  const csp = headers['Content-Security-Policy'];
  if (!csp) throw new Error('nix/module.nix sends no Content-Security-Policy');
  if (csp.includes('${')) throw new Error(`unsubstituted Nix in the policy: ${csp}`);
  return headers;
}

function serve(listen, upstream, headers) {
  const server = http.createServer((req, res) => {
    const up = http.request(
      { host: '127.0.0.1', port: upstream, path: req.url, method: req.method, headers: req.headers },
      (r) => {
        // Set on every response, as Caddy's `header` directive does: the
        // scripts' own responses are what script-src is checked against.
        res.writeHead(r.statusCode, { ...r.headers, ...headers });
        r.pipe(res);
      });
    // Until the server is up, 502, which keeps Playwright polling /health here.
    up.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
    req.pipe(up);
  });
  // WebSockets pass through untouched: the game socket goes this way.
  server.on('upgrade', (req, sock, head) => {
    const up = net.connect(upstream, '127.0.0.1', () => {
      up.write(`${req.method} ${req.url} HTTP/1.1\r\n`
        + Object.entries(req.headers).map(([k, v]) => `${k}: ${v}`).join('\r\n')
        + '\r\n\r\n');
      up.write(head);
      up.pipe(sock);
      sock.pipe(up);
    });
    up.on('error', () => sock.destroy());
    sock.on('error', () => up.destroy());
  });
  server.listen(listen, '127.0.0.1');
  return server;
}

module.exports = { LIVEKIT_URL, productionHeaders };

if (require.main === module) {
  serve(Number(process.env.PROXY_PORT), Number(process.env.PORT), productionHeaders());
}
