// The Spielleiter chooses the roles, and the deal is the deck he chose.
//
// The engine tests prove the deal follows a custom deck; this proves the whole
// path a host actually takes — the switch, the steppers, the server's answer
// about whether the deck fits, and the cards arriving on five phones — and that
// a player who is not the host sees the same deck without being able to touch it.

const { test, expect } = require('@playwright/test');
const { DE, seatTable, closeAll, playAlong } = require('./helpers');

async function myRole(page) {
  return (await page.getByTestId('my-role').innerText()).trim();
}

test.describe('choosing the roles', () => {
  let pages = [];
  test.afterEach(async () => { await closeAll(pages); pages = []; });

  test('two wolves and a Seherin, dealt as chosen', async ({ browser }) => {
    const table = await seatTable(browser, ['Anna', 'Bob', 'Cem', 'Dana', 'Eve']);
    pages = table.pages;
    const [host, guest] = pages;
    await playAlong(host);

    // Everyone reads the deck; only the host gets the switch.
    await expect(guest.getByTestId('deck-panel')).toBeVisible();
    await expect(guest.getByTestId('deck-custom')).toHaveCount(0);

    // "Eigene Auswahl" starts from the automatic deck for five: a wolf, the
    // Seherin, the Hexe and the Jäger.
    await host.getByTestId('deck-custom').click();
    await expect(host.getByTestId('deck-count-werwolf')).toHaveText('1');
    await expect(host.getByTestId('deck-count-hexe')).toHaveText('1');

    // Take the only wolf out on the way: the server says why the game cannot
    // start, and the start button goes.
    await host.getByTestId('deck-minus-werwolf').click();
    await expect(host.getByTestId('deck-problem')).toContainText('Werwolf');
    await expect(host.getByRole('button', { name: DE.start })).toHaveCount(0);

    await host.getByTestId('deck-plus-werwolf').click();
    await host.getByTestId('deck-plus-werwolf').click();
    await host.getByTestId('deck-minus-hexe').click();
    await host.getByTestId('deck-minus-jaeger').click();
    await expect(host.getByTestId('deck-count-werwolf')).toHaveText('2');
    await expect(host.getByTestId('deck-problem')).toHaveCount(0);
    await expect(host.getByTestId('deck-fill')).toContainText('×2');

    // The guest's read-only composition follows, villagers included.
    const wolves = guest.locator('[data-testid="deck-dealt"] [data-role="werwolf"]');
    await expect(wolves).toHaveAttribute('data-count', '2');
    await expect(guest.locator('[data-testid="deck-dealt"] [data-role="hexe"]')).toHaveCount(0);

    await host.getByRole('button', { name: DE.start }).click();
    for (const p of pages) {
      await expect(p.getByTestId('my-role')).toBeVisible({ timeout: 15_000 });
    }
    const roles = [];
    for (const p of pages) roles.push(await myRole(p));
    expect(roles.sort()).toEqual(
      ['Dorfbewohner', 'Dorfbewohner', 'Seherin', 'Werwolf', 'Werwolf']);

    // And the table keeps the composition in view during the game.
    await expect(guest.getByTestId('deck-strip')).toContainText('2× Werwolf');
  });
});
