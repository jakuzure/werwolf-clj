// One person, a table of bots, a whole game.
//
// The creator is the Spielleiter — a fresh room is narrated by default — so
// the only human holds no card and nothing here depends on the deal. The bots
// play every seat; the Spielleiter calls the phases his buttons offer, and
// only when the table has gone still, because a "Phase weiter" pressed while
// the bots are mid-move would end a day before its verdict.

const { test, expect } = require('@playwright/test');
const { createRoom, startNarrated, DE } = require('./helpers');

test('one Spielleiter and five bots play to the end', async ({ page }) => {
  test.setTimeout(300_000);
  await createRoom(page, 'Spielleitung');

  for (let i = 0; i < 5; i++) {
    await page.getByTestId('add-bot').click();
    await expect(page.getByTestId(`seat-${i + 1}`)).toBeVisible();
  }
  await expect(page.getByText('Agnes (Bot)')).toBeVisible();
  // Not connected on the wire, and still not "abwesend": the card says nothing.
  await expect(page.getByTestId('absent-1')).toHaveCount(0);
  await expect(page.getByTestId('add-bot')).toBeVisible();

  await startNarrated(page, []);

  // The game plays itself; the Spielleiter moves it along wherever it waits on
  // him. "Still" means the board's text has not changed for a while, which is
  // longer than a bot's delay plus the clock's tick.
  const board = page.locator('#app');
  const over = page.getByText(DE.recap);
  let last = '';
  for (let i = 0; i < 400 && !(await over.isVisible().catch(() => false)); i++) {
    await page.waitForTimeout(1500);
    const now = await board.innerText().catch(() => '');
    const still = now === last;
    last = now;
    if (!still) continue;
    const next = page.getByRole('button', { name: DE.advance }).first();
    if (await next.isVisible().catch(() => false)) await next.click().catch(() => {});
  }
  await expect(over).toBeVisible();
});
