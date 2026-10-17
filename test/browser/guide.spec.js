// The start screen explains the game to somebody who has never played: folded
// away under the two fields the regulars came for, and listing every card the
// registry knows, so a new role cannot be dealt that the guide never mentions.
const { test, expect } = require('@playwright/test');
const { landOn } = require('./helpers');

const CARDS = ['Werwolf', 'Weißer Werwolf', 'Dorfbewohner', 'Seherin', 'Hexe',
               'Jäger', 'Amor', 'Beschützer', 'Dieb'];

test('the start screen explains the game and every card', async ({ page }) => {
  await landOn(page);
  const guide = page.getByTestId('how-to-play');
  const night = guide.getByRole('heading', { name: /Die Nacht/ });
  await expect(night).toBeHidden();

  await guide.getByText('So wird gespielt').click();
  await expect(night).toBeVisible();
  for (const card of CARDS) {
    await expect(guide.getByText(card, { exact: true }), card).toBeVisible();
  }
});
