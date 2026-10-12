// The host removing a player from the lobby, end to end: the two taps on the
// card, the removed page landing on the start screen with the reason, and that
// page not finding its way back in on a reload.

const { test, expect } = require('@playwright/test');
const { DE, createRoom, joinRoom, closeAll } = require('./helpers');

test.describe('removing a player from the lobby', () => {
  let pages = [];
  test.afterEach(async () => { await closeAll(pages); pages = []; });

  test('two taps, and the removed page is told why', async ({ browser }) => {
    const anna = await (await browser.newContext()).newPage();
    const bob = await (await browser.newContext()).newPage();
    pages = [anna, bob];
    const code = await createRoom(anna, 'Anna');
    await joinRoom(bob, 'Bob', code);

    // Seat 1 is Bob's. Only the host's page draws the control, and only on
    // somebody else's card.
    await expect(bob.getByTestId('kick-0')).toHaveCount(0);
    await expect(anna.getByTestId('kick-0')).toHaveCount(0);
    const kick = anna.getByTestId('kick-1');
    await expect(kick).toHaveText('Entfernen');

    // The first tap only arms it.
    await kick.click();
    await expect(kick).toHaveText('Wirklich entfernen?');
    await expect(bob.getByRole('button', { name: DE.leave })).toBeVisible();

    await kick.click();
    await expect(bob.getByText('Du wurdest aus der Runde entfernt')).toBeVisible();
    await expect(bob.getByPlaceholder(DE.name)).toBeVisible();
    await expect(anna.getByTestId('seat-1')).toHaveCount(0);

    // The stored seat went with it: a reload starts on the landing screen,
    // not in the lobby and not on a "seat gone" error.
    await bob.reload();
    await expect(bob.getByPlaceholder(DE.name)).toBeVisible();
    await expect(bob.getByRole('button', { name: DE.leave })).toHaveCount(0);

    // No ban — the same page may sit down again, on a seat number of its own:
    // seat 1 is never handed out twice, since its old token still names it.
    await joinRoom(bob, 'Bob', code);
    await expect(anna.getByTestId('seat-2')).toBeVisible();
    await expect(anna.getByTestId('seat-1')).toHaveCount(0);
  });
});
