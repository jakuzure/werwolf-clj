// Shared helpers. Kept small and explicit: a browser suite that hides its
// interactions behind a page-object layer becomes very hard to read when it
// fails, and these tests fail for timing reasons often enough that legibility
// matters more than abstraction.

const { expect } = require('@playwright/test');

// The UI opens in German unconditionally — see werwolf.events/default-locale — so
// selectors use the German copy. Centralised here so a wording change is one edit,
// and named after the dictionary keys they mirror.
const DE = {
  name: 'Dein Name',
  create: 'Neue Runde eröffnen',
  code: 'Raumcode',
  join: 'Runde beitreten',
  start: 'Spiel starten',
  leave: 'Runde verlassen',
  voiceJoin: 'Sprache beitreten',
  mute: 'Stumm schalten',
  unmute: 'Stummschaltung aufheben',
  voiceLeave: 'Verlassen',
};

/** Every frame the server sent this page, decoded far enough to inspect. */
function captureFrames(page) {
  const frames = [];
  page.on('websocket', (ws) => {
    ws.on('framereceived', (d) => {
      const payload = typeof d.payload === 'string' ? d.payload : d.payload.toString('utf8');
      frames.push(payload);
    });
  });
  return frames;
}

async function landOn(page) {
  await page.goto('/');
  await expect(page.getByPlaceholder(DE.name)).toBeVisible();
}

/** Create a room and return its code, read off the lobby rather than guessed. */
async function createRoom(page, name) {
  await landOn(page);
  await page.getByPlaceholder(DE.name).fill(name);
  await page.getByRole('button', { name: DE.create }).click();
  // The lobby shows the code in a tracking-wide monospace block; it is the only
  // four-character A-Z2-9 string on the screen.
  const code = await page.locator('.font-mono').first().innerText();
  const trimmed = code.trim();
  expect(trimmed).toMatch(/^[A-Z2-9]{4}$/);
  return trimmed;
}

async function joinRoom(page, name, code) {
  await landOn(page);
  await page.getByPlaceholder(DE.name).fill(name);
  await page.getByPlaceholder(DE.code).fill(code);
  await page.getByRole('button', { name: DE.join }).click();
  await expect(page.getByRole('button', { name: DE.leave })).toBeVisible();
}

/** Seat `names.length` players in one room; returns {code, pages}. */
async function seatTable(browser, names) {
  const pages = [];
  for (const _ of names) {
    const ctx = await browser.newContext({ permissions: ['microphone'] });
    pages.push(await ctx.newPage());
  }
  const code = await createRoom(pages[0], names[0]);
  for (let i = 1; i < names.length; i++) {
    await joinRoom(pages[i], names[i], code);
  }
  return { code, pages };
}

async function closeAll(pages) {
  for (const p of pages) {
    await p.context().close().catch(() => {});
  }
}

/**
 * Is a LiveKit reachable? The voice tests skip rather than fail without one, so
 * `bb test:browser` stays useful when only the game is being worked on.
 */
async function liveKitUp(request, url = 'http://localhost:7880') {
  try {
    // 401 is a healthy LiveKit refusing an unauthenticated request; anything that
    // answers at all is up.
    const res = await request.get(`${url}/rtc/validate`, { timeout: 2000 });
    return res.status() === 401 || res.status() === 200;
  } catch (_) {
    return false;
  }
}

module.exports = {
  DE, captureFrames, landOn, createRoom, joinRoom, seatTable, closeAll, liveKitUp,
};
