// Getting into a room, and back out, on one page without reloading.
//
// Written for one report: "joining rounds is somewhat buggy, often others can't
// be found, sometimes it says you're already in a round". Each test below is one
// of the causes, driven the way a player hits it — every unit test passed while
// all three were live, because each half was right on its own and the bug was in
// the hand-off between them.

const { test, expect } = require('@playwright/test');
const { DE, landOn, createRoom, closeAll } = require('./helpers');

const ALREADY = 'Du bist schon in einer Runde';

/** The four-character code the lobby shows. */
async function lobbyCode(page) {
  await expect(page.getByRole('button', { name: DE.leave })).toBeVisible();
  return (await page.locator('.font-mono').first().innerText()).trim();
}

test.describe('getting in and out of a room', () => {
  let pages = [];
  test.afterEach(async () => { await closeAll(pages); pages = []; });

  test('a page that has left can open another room straight away', async ({ browser }) => {
    // Leaving used to leave the socket bound to the seat it gave up, so the
    // server refused the next create from this page as "already in a round"
    // until it was reloaded.
    const page = await (await browser.newContext()).newPage();
    pages = [page];
    const first = await createRoom(page, 'Anna');

    await page.getByRole('button', { name: DE.leave }).click();
    await expect(page.getByPlaceholder(DE.name)).toBeVisible();

    await page.getByRole('button', { name: DE.create }).click();
    const second = await lobbyCode(page);
    expect(second).not.toBe(first);
    await expect(page.getByText(ALREADY)).toHaveCount(0);
  });

  test('a double tap on create opens one room and no error', async ({ browser }) => {
    const page = await (await browser.newContext()).newPage();
    pages = [page];
    await landOn(page);
    await page.getByPlaceholder(DE.name).fill('Anna');
    await page.getByRole('button', { name: DE.create }).dblclick();
    await lobbyCode(page);
    await expect(page.getByText(ALREADY)).toHaveCount(0);
  });

  test('a room opened after you arrived shows up without a refresh', async ({ browser }) => {
    // The list was only asked for on connect, after leaving and on a tap of
    // refresh, so a friend's room opened a minute later was simply not there.
    const bob = await (await browser.newContext()).newPage();
    const anna = await (await browser.newContext()).newPage();
    pages = [bob, anna];
    await landOn(bob);

    const code = await createRoom(anna, 'Anna');
    await expect(bob.getByRole('button', { name: new RegExp(code) }))
      .toBeVisible({ timeout: 10_000 });

    await bob.getByPlaceholder(DE.name).fill('Bob');
    await bob.getByRole('button', { name: new RegExp(code) }).click();
    await expect(bob.getByRole('button', { name: DE.leave })).toBeVisible();

    // And it goes again once there is nobody left in it to play with.
    await anna.getByRole('button', { name: DE.leave }).click();
    await bob.getByRole('button', { name: DE.leave }).click();
    await expect(anna.getByRole('button', { name: new RegExp(code) }))
      .toHaveCount(0, { timeout: 10_000 });
  });
});
