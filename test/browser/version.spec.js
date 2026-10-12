// The start screen shows which build is running, taken from package.json at
// compile time. Checked against the RELEASE bundle, because that is where a
// stale shadow-cljs cache would show an old version after a release bump.
const { test, expect } = require('@playwright/test');
const { version } = require('../../package.json');
const { landOn } = require('./helpers');

// Resolved exactly as werwolf.build-info/build-commit resolves it, so the test
// expects what the build actually baked in: WERWOLF_COMMIT first, then git, then
// "dev". A bare `git rev-parse` threw at load time in a checkout without a .git
// (a non-colocated jj workspace), and Playwright collects every file before it
// filters, so that one throw took the whole browser suite down with it.
function buildCommit() {
  if (process.env.WERWOLF_COMMIT) return process.env.WERWOLF_COMMIT;
  try {
    const out = require('child_process')
      .execSync('git rev-parse --short=7 HEAD', { stdio: ['ignore', 'pipe', 'ignore'] })
      .toString().trim();
    if (/^[0-9a-f]{7,40}$/.test(out)) return out;
  } catch (_) { /* no git here: the build fell back the same way */ }
  return 'dev';
}
const commit = buildCommit();

test('the start screen shows the version and the commit it was built from', async ({ page }) => {
  await landOn(page);
  await expect(page.getByTestId('app-version')).toHaveText(`v${version} · ${commit}`);
  await expect(page.getByTestId('app-version').getByRole('link')).toHaveCount(0);
});

// The headers that make a deploy visible without a hard reload. Without them,
// nix's 1970 file timestamps made browsers cache the bundle for years.
test('the bundle is revalidated on every load', async ({ request }) => {
  for (const path of ['/', '/js/main.js', '/css/main.css']) {
    const res = await request.get(path);
    expect(res.status(), path).toBe(200);
    expect(res.headers()['cache-control'], path).toBe('no-cache');
    expect(res.headers()['etag'], path).toMatch(/^"[0-9a-f]{32}"$/);
    expect(res.headers()['last-modified'], path).toBeUndefined();
    const again = await request.get(path, { headers: { 'if-none-match': res.headers()['etag'] } });
    expect(again.status(), `${path} unchanged`).toBe(304);
  }
});
