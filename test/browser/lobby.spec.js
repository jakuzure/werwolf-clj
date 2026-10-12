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
    await page.getByRole('button', { name: DE.leaveConfirm }).click();
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
    await anna.getByRole('button', { name: DE.leaveConfirm }).click();
    await bob.getByRole('button', { name: DE.leave }).click();
    await bob.getByRole('button', { name: DE.leaveConfirm }).click();
    await expect(anna.getByRole('button', { name: new RegExp(code) }))
      .toHaveCount(0, { timeout: 10_000 });
  });
});

test.describe('a room with a password', () => {
  let pages = [];
  test.afterEach(async () => { await closeAll(pages); pages = []; });

  test('is listed with a lock, refuses a wrong password and admits the right one', async ({ browser }) => {
    const anna = await (await browser.newContext()).newPage();
    const bob = await (await browser.newContext()).newPage();
    const cem = await (await browser.newContext()).newPage();
    pages = [anna, bob, cem];

    await landOn(anna);
    await anna.getByPlaceholder(DE.name).fill('Anna');
    await anna.getByTestId('create-password').fill('Vollmond');
    await anna.getByRole('button', { name: DE.create }).click();
    const code = await lobbyCode(anna);
    await expect(anna.getByTestId('lobby-locked')).toBeVisible();

    // Still listed — friends have to find it — but marked.
    await landOn(bob);
    const listed = bob.getByRole('button', { name: new RegExp(code) });
    await expect(listed).toBeVisible({ timeout: 10_000 });
    await expect(listed.getByTestId('room-locked')).toBeVisible();

    // The tap asks for the password instead of sending a join doomed to fail.
    await bob.getByPlaceholder(DE.name).fill('Bob');
    await listed.click();
    const pw = bob.getByTestId('join-password');
    await expect(pw).toBeVisible();
    await expect(bob.getByPlaceholder(DE.code)).toHaveValue(code);

    await pw.fill('Neumond');
    await bob.getByRole('button', { name: DE.join }).click();
    await expect(bob.getByText('Falsches Passwort')).toBeVisible();

    // The refusal freed the button, so the retry goes out. Capitalisation is
    // forgiven: a phone keyboard decides that, not the player.
    await pw.fill('vollmond');
    await bob.getByRole('button', { name: DE.join }).click();
    await expect(bob.getByRole('button', { name: DE.leave })).toBeVisible();
    await expect(bob.getByTestId('lobby-locked')).toBeVisible();

    // Typing the code by hand is told what is missing, and gets the field.
    await landOn(cem);
    await cem.getByPlaceholder(DE.name).fill('Cem');
    await cem.getByPlaceholder(DE.code).fill(code);
    await cem.getByRole('button', { name: DE.join }).click();
    await expect(cem.getByText('Diese Runde hat ein Passwort')).toBeVisible();
    await expect(cem.getByTestId('join-password')).toBeVisible();

    // Only the host has the switch, and opening the door shows at once.
    await expect(bob.getByTestId('clear-password')).toHaveCount(0);
    await anna.getByTestId('clear-password').click();
    await expect(bob.getByTestId('lobby-locked')).toHaveCount(0);
    await cem.getByRole('button', { name: DE.join }).click();
    await expect(cem.getByRole('button', { name: DE.leave })).toBeVisible();
  });
});
