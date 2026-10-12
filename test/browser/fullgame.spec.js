// Verification step 2: a full game played to a werewolf win AND a village win,
// entirely through the interface, across five browsers.
//
// Everything is driven by clicking what the UI offers — see play.js. That
// coupling is deliberate: a test that reached past the affordances would keep
// passing after the buttons stopped appearing, which is precisely the failure the
// last playtest reported.
//
// The tests know who holds which role because each page reads its OWN role off
// its own screen and the suite collects them. That is aggregation, not a leak,
// and game.spec.js asserts separately that no single page can do it.
//
// Played the way a new room is played: the creator is the Spielleiter, with no
// card and no clock. Everything below is the players' own moves — nothing waits
// for a timer — so the game reaches its end exactly as it did when the creator
// played; the Spielleiter's part is starting it and ending the setup step.

const { test, expect } = require('@playwright/test');
const { DE, seatTable, closeAll, myIdFrom, startNarrated } = require('./helpers');
const { ACT, actAs, everyone, playNight, playDay } = require('./play');

/** Who is who: [{seat, role, page}], indexed as `pages`. */
async function identify(pages, frames) {
  const out = [];
  for (let i = 0; i < pages.length; i++) {
    out.push({
      seat: myIdFrom(frames[i]),
      role: (await pages[i].getByTestId('my-role').innerText()).trim(),
      page: pages[i],
    });
  }
  return out;
}

const seatOf = (roles, role) => roles.find((r) => r.role === role)?.seat;

async function startAndDeal(browser) {
  const table = await seatTable(browser, ['Sophie', 'Anna', 'Bob', 'Cem', 'Dana', 'Eve']);
  const [sl, ...players] = table.pages;
  await startNarrated(sl, players);
  const roles = await identify(players, table.frames.slice(1));
  // A five-player deck: one wolf, Seherin, Hexe, Jäger, one villager.
  expect(roles.map((r) => r.role).sort())
    .toEqual(['Dorfbewohner', 'Hexe', 'Jäger', 'Seherin', 'Werwolf']);
  // `pages` keeps the Spielleiter: the sweeps in play.js offer every page every
  // action, and his simply never shows a player's button.
  return { ...table, roles };
}

test.describe('a game played to the end', () => {
  let pages = [];
  test.afterEach(async () => { await closeAll(pages); pages = []; });

  test('the village wins by hanging the wolf', async ({ browser }) => {
    const g = await startAndDeal(browser);
    pages = g.pages;
    const { roles } = g;
    const wolf = seatOf(roles, 'Werwolf');
    const villager = seatOf(roles, 'Dorfbewohner');

    // Night one. The wolves eat the plain villager — deliberately not the Jäger,
    // whose dying shot would be a second thing happening at once.
    await playNight(roles, { inspect: wolf, eat: villager });

    // The first day: election, discussion, then the village hangs the wolf.
    await playDay(pages, { lynch: wolf, roles, election: true });

    // The engine reveals every role once the game is decided.
    for (const { page } of roles) {
      await expect(page.getByText('Alle Rollen sind jetzt aufgedeckt.'))
        .toBeVisible({ timeout: 20_000 });
    }
    // And it says who won, in the players' own language.
    await expect(roles[0].page.getByText(/Das Dorf/).first())
      .toBeVisible({ timeout: 20_000 });

    // And the Rückblick tells the whole story — to the Spielleiter as well, who
    // narrated it: every card, and what the night really held.
    for (const page of pages) {
      const recap = page.getByTestId('recap');
      await expect(recap).toBeVisible({ timeout: 20_000 });
      await expect(recap.getByTestId('recap-player')).toHaveCount(5);
    }
    const recap = roles[0].page.getByTestId('recap');
    await recap.locator('summary', { hasText: '1. Nacht' }).click();
    await expect(recap.getByText(/sieht nach: .+ ist Werwolf/)).toBeVisible();
  });

  test('the wolf wins by eating everybody', async ({ browser }) => {
    // Three nights and two days across five browsers, so it needs longer than the
    // default. A lone wolf cannot get there faster: he takes one player a night.
    test.setTimeout(180_000);
    const g = await startAndDeal(browser);
    pages = g.pages;
    const { roles } = g;
    const wolf = seatOf(roles, 'Werwolf');
    const seer = seatOf(roles, 'Seherin');
    const witch = seatOf(roles, 'Hexe');
    const hunter = seatOf(roles, 'Jäger');
    const villager = seatOf(roles, 'Dorfbewohner');

    // Night one: eat the Jäger, and aim his dying shot at the villager. Two of
    // the four fall in one night, which is the only way a lone wolf gets there
    // before the Jäger takes HIM with him.
    await playNight(roles, { inspect: wolf, eat: hunter });
    // His shot is a reaction, and the whole table waits on it.
    await actAs(roles, 'Jäger', ACT.shoot, villager);

    // Day one decides nothing.
    await playDay(pages, { lynch: null, roles, election: true });

    // Night two: the Seherin looks at the HEXE, not the wolf again — a target she
    // already knows is excluded from her list, so re-picking it would silently do
    // nothing and stall the night on her turn.
    await playNight(roles, { inspect: witch, eat: seer });
    await playDay(pages, { lynch: null, roles });

    // Night three: the Hexe, and the wolf is alone.
    await playNight(roles, { eat: witch });

    for (const { page } of roles) {
      await expect(page.getByText('Alle Rollen sind jetzt aufgedeckt.'))
        .toBeVisible({ timeout: 20_000 });
    }
    await expect(roles[0].page.getByText(/Werwölfe/).first())
      .toBeVisible({ timeout: 20_000 });
  });
});
