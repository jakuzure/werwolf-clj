// The production Content-Security-Policy, and the path it once broke.
//
// Every spec already runs behind csp-proxy.js. This one checks that the proxy
// is really there, and walks the one path that went dark: the voice module.
// shadow.loader fetched it by XHR and ran it with eval, which `script-src
// 'self'` forbids, so behind Caddy a tap on "Sprachchat beitreten" sat on
// "Verbinde…" for about 25 s and then failed, for everyone. werwolf.audio now
// has Closure load it with a <script> tag instead.
//
// Needs no LiveKit. Without one the call fails to connect, but only after the
// module has run, which is what is being checked.

const { test, expect } = require('@playwright/test');
const { DE, createRoom, closeAll } = require('./helpers');
const { productionHeaders } = require('./csp-proxy');

test('pages carry the production security headers', async ({ request }) => {
  const res = await request.get('/');
  expect(res.headers()['content-security-policy']).toBe(productionHeaders()['Content-Security-Policy']);
});

test('the voice module loads as a script, with no policy violation', async ({ browser }) => {
  const ctx = await browser.newContext({ permissions: ['microphone'] });
  // Before the first navigation, so a violation during first paint counts too.
  await ctx.addInitScript(() => {
    window.__violations = [];
    document.addEventListener('securitypolicyviolation', (e) =>
      window.__violations.push(`${e.violatedDirective} ${e.blockedURI}`));
  });
  const page = await ctx.newPage();
  try {
    await createRoom(page, 'Anna');
    const chunk = page.waitForRequest((r) => /\/js\/audio\.js$/.test(r.url()));
    await page.getByRole('button', { name: DE.voiceJoin }).click();
    // 'xhr' here is the eval path, which the policy blocks.
    expect((await chunk).resourceType()).toBe('script');
    // The module ran: the call either connects or fails on its own, and either
    // way leaves "Verbinde…". Blocked, it stayed there for ~25 s of retries.
    await expect(page.getByText('Verbinde…')).toBeHidden({ timeout: 15_000 });
    expect(await page.evaluate(() => window.__violations)).toEqual([]);
  } finally {
    await closeAll([page]);
  }
});

// The price of the script tag. A module that throws as it runs never reports
// itself loaded, and the loader would wait for it forever: eval used to catch
// the throw. A tab opened before a deploy is in exactly this state, its old
// main.js running the new audio.js.
test('a voice module that throws fails the join at once, every time', async ({ browser }) => {
  const ctx = await browser.newContext({ permissions: ['microphone'] });
  const page = await ctx.newPage();
  await page.route(/\/js\/audio\.js$/, (route) => route.fulfill({
    contentType: 'application/javascript',
    body: 'throw new Error("not the module this page was built with")',
  }));
  try {
    await createRoom(page, 'Anna');
    for (const tap of ['first', 'second']) {
      await page.getByRole('button', { name: DE.voiceJoin }).click();
      await expect(page.getByText('Sprachverbindung fehlgeschlagen'), `${tap} tap`)
        .toBeVisible({ timeout: 5_000 });
      // The notice fades on its own; the next tap needs the button back.
      await expect(page.getByRole('button', { name: DE.voiceJoin })).toBeVisible({ timeout: 15_000 });
    }
  } finally {
    await closeAll([page]);
  }
});
