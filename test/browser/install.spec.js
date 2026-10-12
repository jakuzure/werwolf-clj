// "Zum Startbildschirm hinzufügen". There is no service worker on purpose (see
// index.html), and Chrome has not needed one to offer the install since 2023.
// What it still needs is a manifest it can parse, a name, a start URL, a
// standalone-ish display and 192 and 512 px icons, so that is what this checks,
// on Chrome's own parse of the file rather than on ours.
//
// CDP's Page.getInstallabilityErrors would be the direct question, but it
// answers [] for everything now, even about:blank, so it proves nothing.
const { test, expect } = require('@playwright/test');

test('Chrome parses the manifest into an installable app', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#app')).not.toBeEmpty();
  const cdp = await page.context().newCDPSession(page);

  const { url, errors, manifest } = await cdp.send('Page.getAppManifest');
  expect(url).toMatch(/\/manifest\.webmanifest$/);
  expect(errors).toEqual([]);
  expect(manifest.name).toBe('Werwölfe von Finsterbach');
  expect(manifest.display).toBe('kStandalone');
  expect(new URL(manifest.startUrl).pathname).toBe('/');
  expect(manifest.preferRelatedApplications).toBe(false);
  expect(manifest.icons.map(i => i.sizes)).toEqual(expect.arrayContaining(['192x192', '512x512']));

  // Every icon decodes, the SVG included, so a launcher never falls back to a
  // letter on a grey square.
  for (const { url: src } of manifest.icons) {
    const width = await page.evaluate(async (src) => {
      const img = new Image();
      img.src = src;
      await img.decode();
      return img.naturalWidth;
    }, src);
    expect(width, src).toBeGreaterThan(0);
  }
});

// The width and height a PNG claims for itself, from its IHDR chunk.
const pngSize = (buf) => `${buf.readUInt32BE(16)}x${buf.readUInt32BE(20)}`;

test('the manifest and every icon it names are served with their real type', async ({ request }) => {
  const res = await request.get('/manifest.webmanifest');
  expect(res.status()).toBe(200);
  expect(res.headers()['content-type']).toBe('application/manifest+json');
  // Same rule as the bundle: a new icon or name should not wait out a cache.
  expect(res.headers()['cache-control']).toBe('no-cache');
  expect(res.headers()['etag']).toMatch(/^"[0-9a-f]{32}"$/);

  const { icons } = await res.json();
  expect(icons.map(i => i.purpose)).toEqual(expect.arrayContaining(['any', 'maskable']));
  for (const { src, type, sizes } of [...icons, { src: '/icons/apple-touch-icon.png', type: 'image/png', sizes: '180x180' }]) {
    const icon = await request.get(src);
    expect(icon.status(), src).toBe(200);
    expect(icon.headers()['content-type'], src).toBe(type);
    expect(icon.headers()['cache-control'], src).toBe('no-cache');
    if (type === 'image/png') expect(pngSize(await icon.body()), src).toBe(sizes);
  }
});

test('the page links the manifest, the favicon and the iOS icon', async ({ page }) => {
  await page.goto('/');
  for (const [rel, href] of [['manifest', '/manifest.webmanifest'],
                             ['icon', '/icons/icon.svg'],
                             ['apple-touch-icon', '/icons/apple-touch-icon.png']]) {
    await expect(page.locator(`link[rel="${rel}"]`), rel).toHaveAttribute('href', href);
  }
});
