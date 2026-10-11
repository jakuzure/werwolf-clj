// The start screen shows which build is running, taken from package.json at
// compile time. Checked against the RELEASE bundle, because that is where a
// stale shadow-cljs cache would show an old version after a release bump.
const { test, expect } = require('@playwright/test');
const { version } = require('../../package.json');
const { landOn } = require('./helpers');

const commit = require('child_process')
  .execSync('git rev-parse --short=7 HEAD').toString().trim();

test('the start screen shows the version and the commit it was built from', async ({ page }) => {
  await landOn(page);
  await expect(page.getByTestId('app-version')).toHaveText(`v${version} · ${commit}`);
  await expect(page.getByTestId('app-version').getByRole('link', { name: commit }))
    .toHaveAttribute('href', `https://github.com/jakuzure/werwolf-clj/commit/${commit}`);
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
