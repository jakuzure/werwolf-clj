// Voice, in two real browsers, with synthetic microphones.
//
// This is what retires "nobody has heard audio yet" — as far as it can be
// retired without a human. Chrome's `--use-fake-device-for-media-stream` provides
// a microphone that always exists, so join, publish, subscribe, mute and hang up
// can all be exercised without hardware.
//
// What is observed is the DOM, not the SDK's internals: werwolf.audio-impl
// attaches every subscribed remote track to an <audio> element inside
// #werwolf-audio-sink. "Bob can hear Anna" is therefore literally "Bob's page has
// an audio element carrying a live audio track from Anna".
//
// ## What this suite CANNOT prove
//
// That sound is audible. The fake device emits SILENCE in this Chrome build — a
// Web Audio analyser on the local microphone reads an RMS of exactly 0 — so there
// is no signal to follow end to end and an attempt to assert on one was removed
// rather than left passing vacuously. Everything up to "a live track is attached
// and playing" is covered; the last inch needs ears.
//
// Requires a LiveKit, and SKIPS rather than fails without one — unless
// WERWOLF_REQUIRE_LIVEKIT is set, as CI sets it, where a missing LiveKit fails:
//     docker compose -f deploy/livekit.dev.yml up -d
// or, with no Docker at all:
//     nix run nixpkgs#livekit -- --dev --bind 127.0.0.1 --node-ip 127.0.0.1 --udp-port 7882-7892
//
// Note that dev LiveKit must advertise 127.0.0.1 as its node IP or media never
// establishes from a browser on this machine — see deploy/livekit.dev.yml. ICE
// over loopback is also occasionally slow to negotiate, which is why the waits
// here are generous and the config allows a retry.

const { test, expect } = require('@playwright/test');
const { DE, seatTable, closeAll, liveKitUp } = require('./helpers');

const SINK = '#werwolf-audio-sink audio';
const SETTLE = 45_000;

async function joinVoice(page) {
  await page.getByRole('button', { name: DE.voiceJoin }).click();
  // The mute button exists only once the connection is up, so the UI states for
  // itself that it joined — no need to reach into the SDK.
  await expect(page.getByRole('button', { name: DE.mute })).toBeVisible({ timeout: SETTLE });
}

/**
 * Every attached remote audio element, described.
 *
 * Reported per ELEMENT rather than per track, and that distinction cost an
 * afternoon: an earlier version counted `el.srcObject.getAudioTracks()` and polled
 * that count, which read as zero for the first joiner even though a probe of the
 * same page showed a healthy element with one track, `paused: false` and
 * readyState 4. A page can be observed mid-attach, which is why `expectHearing`
 * polls the whole description until it holds rather than reading it once: a
 * single read straight after the element appears can see readyState below 4
 * simply because no packet has arrived YET.
 */
function attached(page) {
  return page.evaluate((sel) => Array.from(document.querySelectorAll(sel)).map((el) => {
    const t = el.srcObject ? el.srcObject.getAudioTracks()[0] : null;
    return {
      paused: el.paused,
      // 4 is HAVE_ENOUGH_DATA — the browser's own statement that audio is
      // arriving, which is as close to "heard" as a test can get with a silent
      // fake device.
      readyState: el.readyState,
      kind: t && t.kind,
      trackState: t && t.readyState,
      enabled: t ? t.enabled : null,
    };
  }), SINK);
}

/** Wait until `n` remote audio elements are attached here. */
async function expectAttached(page, n, message) {
  await expect
    .poll(async () => (await page.locator(SINK).count()), { timeout: SETTLE, message })
    .toBe(n);
}

/**
 * Wait until this page hears exactly one remote speaker: one element, carrying a
 * live, enabled audio track, playing, with data arriving. readyState 4 is
 * HAVE_ENOUGH_DATA — the browser's own statement that audio is arriving, which is
 * as close to "heard" as a silent fake device allows anyone to get.
 */
async function expectHearing(page, message) {
  await expectAttached(page, 1, message);
  await expect
    .poll(async () => attached(page), { timeout: SETTLE, message })
    .toEqual([{ paused: false, readyState: 4, kind: 'audio', trackState: 'live', enabled: true }]);
}

test.describe('voice', () => {
  let pages = [];

  test.beforeEach(async ({ request }) => {
    test.skip(!(await liveKitUp(request)), 'no LiveKit on :7880 — see deploy/livekit.dev.yml');
  });

  test.afterEach(async () => {
    await closeAll(pages);
    pages = [];
  });

  test('the voice SDK is fetched only when somebody joins', async ({ browser }) => {
    // Its own test, and deliberately so. Folded into the two-player case, the
    // waitForResponse consistently destabilised the subscription assertions —
    // one test, one subject.
    //
    // This is what distinguishes a code split that WORKS from one that merely
    // builds: the loader was misconfigured for an entire commit, both modules were
    // emitted, /js/audio.js was served correctly, and voice could not load at all.
    const table = await seatTable(browser, ['Anna']);
    pages = table.pages;
    const [anna] = pages;

    // Nothing should have pulled the SDK in on first paint.
    const before = await anna.evaluate(() =>
      performance.getEntriesByType('resource').filter((e) => /audio\.js$/.test(e.name)).length);
    expect(before, 'the SDK must not load before it is wanted').toBe(0);

    const chunk = anna.waitForResponse((r) => /\/js\/audio\.js$/.test(r.url()), { timeout: SETTLE });
    await joinVoice(anna);
    expect((await chunk).status()).toBe(200);
  });

  // This test and the next were skipped for a long time: two-party media
  // assertions passed in isolation and failed inside the suite, on whichever
  // participant, and nobody could say why. Against a native LiveKit (nix, so no
  // Docker Desktop UDP proxy in the media path) they then passed 5 of 5 with the
  // voice spec alone and 5 of 5 inside the whole suite, retries off, and CI now
  // runs them too. The old failures were never explained; the setup
  // they happened in advertised the LAN address through Docker Desktop, and that
  // is the first suspect if they come back. Read the trace before reaching for
  // test.skip again — a voice regression looks exactly like a flaky voice test.
  test('two players hear each other, and muting silences the far side', async ({ browser }) => {
    const table = await seatTable(browser, ['Anna', 'Bob']);
    pages = table.pages;
    const [anna, bob] = pages;

    // The panel is rendered only when the server said audio is available, so its
    // presence already proves :client/media round-tripped.
    await expect(anna.getByRole('button', { name: DE.voiceJoin })).toBeVisible();

    await joinVoice(anna);
    await joinVoice(bob);

    // ONE direction. Bob joined second, so he picks up Anna's already-published
    // track when he connects — no renegotiation needed. The other direction is
    // its own test below, so a failure says which half broke.
    await expectHearing(bob, 'Bob hears Anna');

    // Muting MUTES the publication rather than unpublishing it: the far side keeps
    // the track and sees it disabled. Asserted as observed, not as assumed — the
    // first version of this test expected the track to disappear and was wrong.
    await anna.getByRole('button', { name: DE.mute }).click();
    await expect(anna.getByRole('button', { name: DE.unmute })).toBeVisible();
    await expect
      .poll(async () => (await attached(bob))[0]?.enabled,
            { timeout: SETTLE, message: "Anna's track goes quiet on Bob's side" })
      .toBe(false);

    // And back, so mute is not a one-way door.
    await anna.getByRole('button', { name: DE.unmute }).click();
    await expect
      .poll(async () => (await attached(bob))[0]?.enabled,
            { timeout: SETTLE, message: 'and live again on unmute' })
      .toBe(true);
  });

  // The player who joins voice FIRST also receives someone who joins AFTER them.
  // That is the direction needing renegotiation — Anna's subscription has to
  // grow a track after her connection is already up — and the test above does
  // not cover it. If voice ever misbehaves for whoever joined first, start here.
  test('the first joiner also hears someone who joins later', async ({ browser }) => {
    const table = await seatTable(browser, ['Anna', 'Bob']);
    pages = table.pages;
    const [anna, bob] = pages;
    await joinVoice(anna);
    await joinVoice(bob);
    await expectHearing(anna, 'Anna hears Bob, who joined after her');
  });

  test('leaving the room hangs up the microphone', async ({ browser }) => {
    const table = await seatTable(browser, ['Anna', 'Bob']);
    pages = table.pages;
    const [anna, bob] = pages;

    // Bob can see Anna is in the call before he joins it himself.
    await joinVoice(anna);
    await expect(bob.getByTestId('in-call')).toContainText('Anna');
    await joinVoice(bob);
    await expectAttached(bob, 1, 'Bob hears Anna before she leaves');

    // The worst bug available in a game about who knows what: a microphone still
    // published into a room the player has walked out of, audible to a table they
    // are no longer at.
    // Two taps: the first only turns the button into its 'really?'.
    await anna.getByRole('button', { name: DE.leave }).click();
    await anna.getByRole('button', { name: DE.leaveConfirm }).click();
    await expect(anna.getByPlaceholder(DE.name)).toBeVisible();
    // And her headset is gone from the table she left.
    await expect(bob.getByTestId('in-call')).not.toContainText('Anna');

    await expectAttached(bob, 0, 'Anna is gone from the call');
    // And Anna's own page holds nothing either — no orphaned audio elements.
    await expectAttached(anna, 0, 'no orphaned elements on the way out');
  });
});
