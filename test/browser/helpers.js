// Shared helpers. Kept small and explicit: a browser suite that hides its
// interactions behind a page-object layer becomes very hard to read when it
// fails, and these tests fail for timing reasons often enough that legibility
// matters more than abstraction.

const { expect } = require('@playwright/test');
const transit = require('transit-js');

const reader = transit.reader('json');
const kw = (s) => transit.keyword(s);

/**
 * Parse a server frame, or null if it is not readable transit.
 *
 * Frames are inspected STRUCTURALLY rather than by matching role words in the
 * raw text. A regex cannot tell a role from a role BELONGING to somebody: an
 * earlier version flagged the LiveKit room name `werwolf-ply3` and the public
 * `:night-cursor`, neither of which is a leak. This mirrors what the engine's own
 * `foreign-roles-in-view` does — walk the structure and ask whose role it is.
 */
function readFrame(raw) {
  try { return reader.read(raw); } catch (_) { return null; }
}

const get = (m, k) => (m && m.get ? m.get(kw(k)) : undefined);

/**
 * Every (player-id, role) pair a page was told about that belongs to SOMEBODY
 * ELSE, across all its frames. Empty is the property the architecture exists to
 * guarantee.
 *
 * `:known-roles` is the one sanctioned channel — a Seherin's inspections, and
 * every role once the game is over — so a leak is anything in it naming another
 * player, plus any `:role` hanging off another player's entry in `:players`.
 */
function foreignRoles(frames, myId) {
  const found = [];
  for (const raw of frames) {
    const msg = readFrame(raw);
    if (!msg || String(get(msg, 'type')) !== ':room/view') continue;
    const view = get(msg, 'view');
    if (!view) continue;

    const known = get(view, 'known-roles');
    if (known && known.forEach) {
      known.forEach((role, pid) => {
        if (pid !== myId) found.push({ pid, role: String(role), via: 'known-roles' });
      });
    }
    const players = get(view, 'players');
    if (players && players.forEach) {
      players.forEach((p, pid) => {
        const r = get(p, 'role');
        if (r != null && pid !== myId) found.push({ pid, role: String(r), via: 'players' });
      });
    }
  }
  return found;
}

/** This page's own seat, read from :server/welcome. */
function myIdFrom(frames) {
  for (const raw of frames) {
    const msg = readFrame(raw);
    if (msg && String(get(msg, 'type')) === ':server/welcome') return get(msg, 'player-id');
  }
  return null;
}

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
  leaveConfirm: 'Wirklich verlassen?',
  voiceJoin: 'Sprachchat beitreten',
  mute: 'Stumm schalten',
  unmute: 'Stummschaltung aufheben',
  voiceLeave: 'Verlassen',
  // A new room is NARRATED by default: its creator is the Spielleiter, dealt no
  // card, and the game passes through a setup step he ends himself.
  beginNight: /^Erste Nacht beginnen/,
  playAlong: 'Ich spiele mit',
  narrator: 'Spielleiter',
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
  // Wait for the lobby before reading. The landing screen lists open rooms in
  // the same monospace, so reading too early returns some OTHER room's code —
  // which, now that the list fills itself, is whatever the last test left open.
  await expect(page.getByRole('button', { name: DE.leave })).toBeVisible();
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

/**
 * Seat `names.length` players in one room; returns {code, pages, frames}.
 *
 * `frames[i]` collects every frame the server sent page i, and the listener is
 * attached BEFORE the page navigates — `page.on('websocket')` only fires for
 * sockets opened afterwards, so capturing later silently yields nothing. That
 * cost a debugging round: the redaction assertions passed against an empty array
 * and looked like clean frames.
 */
async function seatTable(browser, names) {
  const pages = [];
  const frames = [];
  for (const _ of names) {
    // `use.video` in the config only reaches contexts Playwright makes for the
    // `page` fixture. These are ours, so recording has to be asked for here or
    // WERWOLF_TEST_VIDEO=1 silently produces nothing.
    const ctx = await browser.newContext({
      permissions: ['microphone'],
      ...(process.env.WERWOLF_TEST_VIDEO
        ? { recordVideo: { dir: 'test-results/videos' } }
        : {}),
    });
    const page = await ctx.newPage();
    frames.push(captureFrames(page));
    pages.push(page);
  }
  const code = await createRoom(pages[0], names[0]);
  for (let i = 1; i < names.length; i++) {
    await joinRoom(pages[i], names[i], code);
  }
  return { code, pages, frames };
}

/**
 * Switch a fresh room to the old table, where the creator plays like everyone
 * else and the clock runs. For specs that are about something other than the
 * Spielleiter — and need every page to be dealt a card.
 */
async function playAlong(hostPage) {
  await hostPage.getByTestId('toggle-narrated').click();
  await expect(hostPage.getByRole('button', { name: 'Ich leite das Spiel' })).toBeVisible();
}

/**
 * Start a NARRATED game and end its setup step: the Spielleiter deals, every
 * player sees a card, and he begins the first night. `slPage` is the creator;
 * `playerPages` are everyone else.
 */
async function startNarrated(slPage, playerPages) {
  await slPage.getByRole('button', { name: DE.start }).click();
  for (const p of playerPages) {
    await expect(p.getByTestId('my-role')).toBeVisible({ timeout: 20_000 });
  }
  await expect(slPage.getByTestId('my-role')).toHaveText(DE.narrator, { timeout: 20_000 });
  await slPage.getByRole('button', { name: DE.beginNight }).click();
  await expect(slPage.getByRole('heading', { name: 'Nacht' })).toBeVisible({ timeout: 20_000 });
}

async function closeAll(pages) {
  for (const p of pages) {
    await p.context().close().catch(() => {});
  }
}

/**
 * Is a LiveKit reachable? The voice tests skip rather than fail without one, so
 * `bb test:browser` stays useful when only the game is being worked on.
 *
 * Unless WERWOLF_REQUIRE_LIVEKIT is set, which CI does: there a LiveKit was
 * started on purpose, and one that is down is a broken job, not a reason to skip.
 * Without it a dead service would turn every voice test into a quiet skip and the
 * run would still go green.
 */
async function liveKitUp(request, url = 'http://localhost:7880') {
  let up = false;
  try {
    // 401 is a healthy LiveKit refusing an unauthenticated request; anything that
    // answers at all is up.
    const res = await request.get(`${url}/rtc/validate`, { timeout: 2000 });
    up = res.status() === 401 || res.status() === 200;
  } catch (_) {
    up = false;
  }
  if (!up && process.env.WERWOLF_REQUIRE_LIVEKIT) {
    throw new Error(`WERWOLF_REQUIRE_LIVEKIT is set, but no LiveKit answers at ${url}`);
  }
  return up;
}

module.exports = {
  DE, captureFrames, landOn, createRoom, joinRoom, seatTable, closeAll, liveKitUp,
  readFrame, foreignRoles, myIdFrom, playAlong, startNarrated,
};
