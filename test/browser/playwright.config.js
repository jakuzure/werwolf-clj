// Browser tests. The layer with no coverage at all until now: `client_test`
// COMPILES views.cljs but cannot render it, so nothing has ever exercised the
// DOM, reagent's reconciliation, or a real WebSocket in a real browser.
//
// Run with `bb test:browser`, which builds the frontend first.
//
// Deliberately against the RELEASE build, not the dev build. Two of the bugs this
// suite exists to catch only exist under `:advanced` optimisation — Closure
// renaming a property it cannot prove external is how audio ends up working in
// development and silently dead in production — so testing the dev build would
// test the one artifact nobody ships.

const { defineConfig, devices } = require('@playwright/test');
const os = require('os');
const path = require('path');

// A fresh database per run. Rooms are persistent, and a suite that inherited
// yesterday's lobbies would be reading somebody else's state.
const DB = path.join(os.tmpdir(), `werwolf-browser-${process.pid}.db`);
const PORT = process.env.WERWOLF_TEST_PORT || '3999';

module.exports = defineConfig({
  testDir: __dirname,
  // Serial. These tests share one server and one SQLite file, and the landing
  // screen lists every open room — parallel workers would see each other's games
  // and race on the room list.
  workers: 1,
  fullyParallel: false,
  // A failing browser test is far more often a timing assumption than a real
  // regression, so retry once locally and let a second failure mean something.
  retries: process.env.CI ? 2 : 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],

  use: {
    baseURL: `http://localhost:${PORT}`,
    // localhost is a secure context, which is what makes getUserMedia available
    // at all — a phone reaching a dev server by LAN IP over plain HTTP cannot do
    // this, which is why voice testing on real devices needs the deployed TLS.
    permissions: ['microphone'],
    launchOptions: {
      // Slow the driver down so a human can follow a game playing itself:
      //   WERWOLF_TEST_SLOWMO=300 npx playwright test ... --headed
      // Zero by default, so the suite stays as fast as it ever was.
      slowMo: Number(process.env.WERWOLF_TEST_SLOWMO || 0),
      args: [
        // Synthetic audio in place of a microphone: a device that always exists
        // and always produces a signal, so voice can be verified without
        // hardware and without a human listening.
        '--use-fake-device-for-media-stream',
        '--use-fake-ui-for-media-stream',
        // Without this the fake device emits silence, and "a track exists" would
        // pass while carrying nothing.
        '--autoplay-policy=no-user-gesture-required',
      ],
    },
    trace: 'retain-on-failure',
    // Off by default: a video per test is slow and nobody watches a green run.
    // WERWOLF_TEST_VIDEO=1 records every test to test-results/, which is how you
    // get a game playing itself as a file you can send somebody.
    video: process.env.WERWOLF_TEST_VIDEO ? 'on' : 'off',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  webServer: {
    command: 'clojure -M -m werwolf.main',
    // Playwright runs webServer from the CONFIG's directory, not the repo root,
    // so without this `clojure` starts in test/browser where there is no deps.edn
    // — it silently downloads a default Clojure and then cannot find werwolf.main.
    cwd: path.resolve(__dirname, '../..'),
    url: `http://localhost:${PORT}/health`,
    reuseExistingServer: false,
    timeout: 180_000,
    stdout: 'pipe',
    stderr: 'pipe',
    env: {
      PORT,
      DB_PATH: DB,
      TOKEN_SECRET: 'browser-suite-secret-long-enough-to-pass',
      // Three is the deck's floor, and a browser test that needed five tabs to
      // reach a night would be slow and hard to read.
      MIN_PLAYERS: '3',
      // Classic rule, so night one actually has a werewolf turn. Otherwise every
      // test about the night has to walk through a whole day first.
      FIRST_NIGHT_KILL: 'true',
      // And the classic first day, with a trial, so a test about the lynch
      // does not have to sit through a day and a night to reach one.
      FIRST_DAY_TRIAL: 'true',
      // Long enough that a phase never expires while a test is mid-assertion.
      DISCUSSION_SECS: '3600',
      NIGHT_SECS: '3600',
      REACTION_SECS: '3600',
      VERDICT_SECS: '3600',
      // Bots move almost at once and wait for nobody: the bots spec watches a
      // game play itself, and nothing else seats a bot.
      BOT_DELAY_MS: '200',
      BOT_FLOOR_SECS: '0',
      BOT_PATIENCE_SECS: '0',
      // Voice. The suite skips its audio tests when this is unreachable rather
      // than failing, so `bb test:browser` is useful without LiveKit running —
      // except under WERWOLF_REQUIRE_LIVEKIT, which CI sets; see liveKitUp.
      MEDIA_PROVIDER: 'livekit',
      LIVEKIT_URL: process.env.LIVEKIT_URL || 'ws://localhost:7880',
      LIVEKIT_API_KEY: 'devkey',
      LIVEKIT_API_SECRET: 'secret',
    },
  },
});
