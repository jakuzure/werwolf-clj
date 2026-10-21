// The screen wake lock, in the RELEASE bundle. The unit test covers the
// decision and the effect against a stub; what only a browser can show is that
// the advanced build still reaches `navigator.wakeLock.request` by that name
// once a game starts — Closure renaming a property it cannot prove external is
// exactly how a feature works in development and is silently dead in
// production, which is why this suite runs against the release build at all.
//
// The API is stubbed from an init script, so each page's requests and releases
// can be read back, and so headless Chromium's own answer (which may be a
// refusal) does not decide the test.

const { test, expect } = require('@playwright/test');
const { DE, createRoom, joinRoom, closeAll, startNarrated } = require('./helpers');

/** Replace navigator.wakeLock before the app loads, and keep a tally. */
function stubWakeLock() {
  window.__wake = { requests: [], releases: 0 };
  Object.defineProperty(navigator, 'wakeLock', {
    configurable: true,
    value: {
      request: async (kind) => {
        window.__wake.requests.push(kind);
        const listeners = [];
        return {
          released: false,
          addEventListener: (ev, f) => { if (ev === 'release') listeners.push(f); },
          release: async function release() {
            this.released = true;
            window.__wake.releases += 1;
            listeners.forEach((f) => f());
          },
        };
      },
    },
  });
}

async function pageWithStub(browser) {
  const ctx = await browser.newContext();
  await ctx.addInitScript(stubWakeLock);
  return ctx.newPage();
}

const tally = (page) => page.evaluate(() => window.__wake);

test.describe('the screen wake lock', () => {
  let pages = [];
  test.afterEach(async () => { await closeAll(pages); pages = []; });

  test('is taken once the game starts, and let go on leaving', async ({ browser }) => {
    // Five players, so that one of them leaving does not END the game. At three
    // any departure decides it — the wolf gone, or the wolf a majority — and the
    // table that stayed let go for the right reason, which is not the one under
    // test.
    const names = ['SL', 'Anna', 'Bob', 'Cem', 'Dana', 'Eve'];
    pages = await Promise.all(names.map(() => pageWithStub(browser)));
    const [sl, ...players] = pages;
    const code = await createRoom(sl, names[0]);
    for (let i = 0; i < players.length; i++) await joinRoom(players[i], names[i + 1], code);

    // Not in the lobby: the view that drew it has been through the fx layer
    // by the time it is on screen, and asked for nothing.
    for (const p of pages) expect((await tally(p)).requests).toEqual([]);

    // The deal starts the game, for the Spielleiter's phone as much as for the
    // players'. One request each — the night that follows is another view, and
    // a lock already held is not asked for again.
    await startNarrated(sl, players);
    for (const p of pages) {
      await expect.poll(() => tally(p).then((t) => t.requests)).toEqual(['screen']);
    }
    await sl.waitForTimeout(300);
    for (const p of pages) expect((await tally(p)).requests).toEqual(['screen']);

    // Leaving lets go; the table that stays keeps it. Somebody other than the
    // wolf leaves — the wolf walking out ends the game, and then everyone lets go.
    const roles = await Promise.all(players.map((p) => p.getByTestId('my-role').innerText()));
    const leaver = players[roles.findIndex((r) => !/Werwolf/.test(r))];
    await leaver.getByRole('button', { name: DE.leave }).click();
    await leaver.getByRole('button', { name: DE.leaveConfirm }).click();
    await expect(leaver.getByPlaceholder(DE.name)).toBeVisible();
    await expect.poll(() => tally(leaver).then((t) => t.releases)).toBe(1);
    for (const p of pages.filter((q) => q !== leaver)) {
      const t = await tally(p);
      expect(t.releases).toBe(0);
      expect(t.requests).toEqual(['screen']);
    }
  });
});
