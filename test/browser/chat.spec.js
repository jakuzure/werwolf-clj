// The chat panel follows the conversation.
//
// It never scrolled by itself: once the panel was full, the newest line
// landed below its edge for everybody who was not scrolling it, which in a
// heated day is exactly when nobody is. It follows now, while the reader is
// at the bottom, and leaves alone whoever has scrolled up to read back,
// until they say something themselves.

const { test, expect } = require('@playwright/test');
const { DE, createRoom, joinRoom, seatTable, closeAll } = require('./helpers');

async function say(page, text) {
  const box = page.getByPlaceholder('Nachricht schreiben…');
  await box.fill(text);
  await box.press('Enter');
  // Well under the socket's rate limit, see werwolf.http/ws-handler.
  await page.waitForTimeout(60);
}

const atEnd = (log) =>
  log.evaluate((el) => el.scrollTop + el.clientHeight >= el.scrollHeight - 2);

test('the chat follows new lines, and lets a reader read back', async ({ browser }) => {
  const anna = await (await browser.newContext()).newPage();
  const bob = await (await browser.newContext()).newPage();
  try {
    const code = await createRoom(anna, 'Anna');
    await joinRoom(bob, 'Bob', code);
    const log = anna.getByTestId('chat-log');

    for (let i = 1; i <= 20; i++) await say(bob, `Zeile ${i}`);
    await expect(log.getByText('Zeile 20')).toHaveCount(1);
    await expect.poll(() => atEnd(log)).toBe(true);

    // Anna scrolls back; Bob's next line must not pull her down.
    await log.evaluate((el) => { el.scrollTop = 0; });
    await say(bob, 'Zeile 21');
    await expect(log.getByText('Zeile 21')).toHaveCount(1);
    expect(await atEnd(log)).toBe(false);

    // Her own line takes her back to the end.
    await say(anna, 'Ich lese wieder mit');
    await expect(log.getByText('Ich lese wieder mit')).toHaveCount(1);
    await expect.poll(() => atEnd(log)).toBe(true);
  } finally {
    await closeAll([anna, bob]);
  }
});

test('a phone opens its Gespräch tab at the newest line', async ({ browser }) => {
  // The suite runs at desktop width, and this is a phone's case: in a game
  // the panel is hidden on the other tabs, a hidden list cannot be scrolled,
  // and the tab used to open wherever the list had been left.
  const table = await seatTable(browser, ['Sophie', 'Anna', 'Bob', 'Cem']);
  const [sl, anna] = table.pages;
  try {
    await sl.getByRole('button', { name: DE.start }).click();
    await expect(anna.getByTestId('my-role')).toBeVisible({ timeout: 20_000 });
    await anna.setViewportSize({ width: 390, height: 844 });
    await expect(anna.getByTestId('tab-chat')).toBeVisible();

    // The Spielleiter may always talk, and everybody hears him.
    for (let i = 1; i <= 30; i++) await say(sl, `Zeile ${i}`);
    await expect(anna.getByTestId('chat-unread')).toBeVisible();

    await anna.getByTestId('tab-chat').click();
    const log = anna.getByTestId('chat-log');
    await expect(log.getByText('Zeile 30')).toBeVisible();
    await expect.poll(() => atEnd(log)).toBe(true);
  } finally {
    await closeAll(table.pages);
  }
});
