// A real game, in real browsers, over real WebSockets.
//
// This is the automated half of the plan's manual verification list. The prize is
// step 3 — "as a Dorfbewohner, inspect the raw WebSocket frames; confirm no other
// player's role is present anywhere" — which is the property the whole
// architecture exists to guarantee and which nothing else checks END TO END. The
// engine's own redaction property runs in-process against `view-for`; this runs
// against the bytes a browser actually received, after transit encoding, over a
// socket.
//
// The tests are omniscient by AGGREGATION: each page reads its own role from its
// own screen, and the test collects them. No single page ever learns another's,
// which is exactly the point.

const { test, expect } = require('@playwright/test');
const { DE, seatTable, closeAll, foreignRoles, myIdFrom } = require('./helpers');

/** The role this page was dealt, read off its own screen. */
async function myRole(page) {
  return (await page.getByTestId('my-role').innerText()).trim();
}

/**
 * Start, and wait until the deal has actually reached every screen.
 *
 * The game opens at NIGHT — the Bürgermeister election is on the first DAY, once
 * the night has been played — so the thing to wait for is a role appearing, not
 * a phase.
 */
async function startGame(pages) {
  await pages[0].getByRole('button', { name: DE.start }).click();
  for (const p of pages) {
    await expect(p.getByTestId('my-role')).toBeVisible({ timeout: 15_000 });
  }
}

test.describe('a real game', () => {
  let pages = [];
  test.afterEach(async () => { await closeAll(pages); pages = []; });

  test('no player is ever told another player\'s role', async ({ browser }) => {
    // Frames are captured from before each page connects — see seatTable — so
    // this covers the deal, the single most dangerous moment: the server knows
    // every role and must tell each player exactly one.
    const table = await seatTable(browser, ['Anna', 'Bob', 'Cem', 'Dana', 'Eve']);
    pages = table.pages;
    const frames = table.frames;

    await startGame(pages);

    const roles = [];
    for (const p of pages) roles.push(await myRole(p));
    // Sanity: a five-player deck is one wolf, Seherin, Hexe, Jäger, villager. If
    // this ever fails the test below is checking something other than a real game.
    expect(roles.slice().sort()).toEqual(
      ['Dorfbewohner', 'Hexe', 'Jäger', 'Seherin', 'Werwolf']);

    // Nobody has died and the Seherin has not looked at anybody, so NO player is
    // entitled to know any role but their own.
    for (let i = 0; i < pages.length; i++) {
      const myId = myIdFrom(frames[i]);
      expect(myId, `page ${i} never saw its own welcome`).not.toBeNull();
      const leaks = foreignRoles(frames[i], myId);
      expect(leaks, `${roles[i]} was told: ${JSON.stringify(leaks)}`).toEqual([]);
    }
  });

  test('the Seherin learns exactly one role, and only she does', async ({ browser }) => {
    const table = await seatTable(browser, ['Anna', 'Bob', 'Cem', 'Dana', 'Eve']);
    pages = table.pages;
    const frames = table.frames;
    await startGame(pages);

    const roles = [];
    for (const p of pages) roles.push(await myRole(p));
    const seer = roles.indexOf('Seherin');
    const seerPage = pages[seer];

    // She acts first at night — order 100, ahead of the wolves.
    await seerPage.getByRole('button', { name: 'Rolle aufdecken', exact: false }).click();
    // Arming shows the pick-a-player bar; then any other seat will do.
    const target = (seer + 1) % pages.length;
    await seerPage.getByText(`${target}`, { exact: true }).first().click();

    await expect
      .poll(() => foreignRoles(frames[seer], myIdFrom(frames[seer])).length,
            { timeout: 15_000, message: 'the Seherin learns the role she looked at' })
      .toBe(1);

    const learned = foreignRoles(frames[seer], myIdFrom(frames[seer]))[0];
    expect(learned.via).toBe('known-roles');
    expect(learned.pid).toBe(target);

    // And NOBODY else gained anything from her looking.
    for (let i = 0; i < pages.length; i++) {
      if (i === seer) continue;
      const leaks = foreignRoles(frames[i], myIdFrom(frames[i]));
      expect(leaks, `${roles[i]} was told: ${JSON.stringify(leaks)}`).toEqual([]);
    }
  });

  test('two players read the same game in different languages', async ({ browser }) => {
    const table = await seatTable(browser, ['Anna', 'Bob', 'Cem']);
    pages = table.pages;
    const [de, en] = pages;

    // Bob switches to English; everything he sees is rendered from the same
    // structured events, because the wire carries no prose.
    await en.getByRole('button', { name: 'English' }).click();
    await expect(en.getByRole('button', { name: 'Leave the game' })).toBeVisible();

    // The German page is unaffected — locale is per-player state, not a room
    // setting.
    await expect(de.getByRole('button', { name: DE.leave })).toBeVisible();

    await de.getByRole('button', { name: DE.start }).click();

    // The same phase, named in each reader's language — one structured event
    // through two dictionaries, which is the entire reason the wire carries no
    // prose.
    await expect(de.getByRole('heading', { name: 'Nacht' })).toBeVisible({ timeout: 15_000 });
    await expect(en.getByRole('heading', { name: 'Night' })).toBeVisible({ timeout: 15_000 });

    await expect(de.getByText('Chronik').first()).toBeVisible();
    await expect(en.getByText('Chronicle').first()).toBeVisible();

    // And names are DATA, not language: Anna is Anna in both.
    await expect(de.getByText('Anna').first()).toBeVisible();
    await expect(en.getByText('Anna').first()).toBeVisible();
  });
});
