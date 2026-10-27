// Bombadil: one browser clicking at random through whatever the screen offers,
// at a table filled with bots, while the properties below are checked after
// every single step.
//
// The Playwright suite walks the paths somebody thought of. This walks the
// rest — the button pressed mid-render, the reload in the middle of a night,
// the language switched while a vote is open — and needs no updating when a
// screen changes, because it only ever asks what the screen offers right now.
//
// Run with `bb test:bombadil`, which builds the release frontend and starts a
// server with short phase clocks; see its docstring.
//
// Elements are found by test id or by structure wherever the copy could be in
// either language: a random walk reaches the language switch within minutes,
// and a spec that only read German would stop recognising the game.

import { always, eventually, now } from "@antithesishq/bombadil";
import {
  actions,
  extract,
  registerCustomAction,
  weighted,
} from "@antithesishq/bombadil/browser";
import { defaultActions } from "@antithesishq/bombadil/browser/defaults";

// The defaults' properties: no uncaught exception, no unhandled rejection, no
// console error, no HTTP 4xx/5xx. The first is the one this suite exists for —
// two of the browser suite's bugs lived only under :advanced, where a renamed
// property is a TypeError and nothing else.
export {
  noConsoleErrors,
  noHttpErrorCodes,
  noUncaughtExceptions,
  noUnhandledPromiseRejections,
} from "@antithesishq/bombadil/browser/defaults/properties";

// Copy the spec has to recognise, in both languages, from i18n/de.cljc and
// i18n/en.cljc. Only where no test id or structure says the same thing.
const TEXT = {
  yourTurn: ["Du bist dran", "Your turn"],
  narrating: ["Du leitest das Spiel", "You are running the game"],
  start: ["Spiel starten", "Start the game"],
  connLost: [
    "verbinde…",
    "getrennt — versuche erneut zu verbinden…",
    "connecting…",
    "disconnected — trying again…",
  ],
};

// A table of six: big enough that the deck deals every kind of night, small
// enough that a game ends inside one run.
const SEATS = 6;

// How long the screen may show the same phase before the game counts as stuck.
// The longest clock `bb test:bombadil` sets is 30 s, and a night is one clock
// for the human's own call plus a moment per bot; three times the longest
// clock leaves room for both without hiding a game that has stopped.
const STALL_SECS = 90;

const screen = extract((state) => {
  const d = state.document;
  const createPassword = d.querySelector('[data-testid="create-password"]');
  const nowCard = d.querySelector('[data-testid="now"]');
  const top = d.querySelector('[data-testid="top-bar"]');
  const title = top?.querySelector("h2") ?? null;

  // tongue renders an unknown key as "{Missing key :ns/k}" and a missing
  // argument as "{Missing index n}". Text nodes and the attributes a screen
  // reader speaks, never <script>: the compiled tongue carries that very
  // string as a literal.
  let missing: string | null = null;
  const walker = d.createTreeWalker(d.body, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n && !missing; n = walker.nextNode()) {
    const tag = n.parentElement?.tagName;
    if (tag !== "SCRIPT" && tag !== "STYLE" && n.textContent?.includes("{Missing ")) {
      missing = n.textContent.trim();
    }
  }
  d.querySelectorAll("[placeholder], [title], [aria-label]").forEach((el) => {
    for (const a of ["placeholder", "title", "aria-label"]) {
      const v = el.getAttribute(a);
      if (!missing && v?.includes("{Missing ")) missing = `${a}: ${v}`;
    }
  });

  const banner = Array.from(d.querySelectorAll("div")).find((el) =>
    TEXT.connLost.includes(el.textContent?.trim() ?? ""),
  );

  return {
    landing: createPassword !== null,
    lobby: d.querySelector('[data-testid="table-settings"]') !== null,
    seats: Array.from(d.querySelectorAll("[data-testid]")).filter((el) =>
      /^seat-\d+$/.test(el.getAttribute("data-testid") ?? ""),
    ).length,
    canAddBot: d.querySelector('[data-testid="add-bot"]') !== null,
    canStart: Array.from(d.querySelectorAll("button")).some((b) =>
      TEXT.start.includes(b.textContent?.trim() ?? ""),
    ),
    // The Jetzt card is drawn for every phase between the lobby and the end,
    // and for nothing else.
    inGame: nowCard !== null,
    headline: nowCard?.querySelector(".font-bold")?.textContent?.trim() ?? null,
    hasTask: nowCard?.querySelector('[data-testid="my-task"]') !== null,
    // Phase and round from the bar: the phase by its icon, which is one per
    // phase, and the round by its digits. Not by name — a stall that the
    // language switch kept renaming would never count as one.
    stage: title
      ? `${top?.querySelector(".text-2xl")?.textContent ?? ""} ${
          title.nextElementSibling?.textContent?.replace(/\D/g, "") ?? ""
        }`
      : null,
    clock: d.querySelector('[data-testid="clock"]') !== null,
    // "m:ss" in the bar, as seconds.
    secs:
      d
        .querySelector('[data-testid="clock"]')
        ?.textContent?.split(":")
        .reduce((acc, part) => acc * 60 + Number(part), 0) ?? null,
    paused: top?.textContent?.includes("⏸") ?? false,
    connLost: banner !== undefined,
    missing,
  };
});

// ------------------------------------------------------------- the table ---

// Typing the name key by key would leave the landing to chance: the walk
// wanders off between the field and the button. One action instead, so the
// random part is spent where the game is.
const openTable = registerCustomAction(
  "openTable",
  async (document, window, name: string) => {
    const box = document.querySelector('[data-testid="create-password"]')?.parentElement;
    const input = box?.querySelector("input");
    const create = box?.querySelector("button");
    if (!input || !create) throw new Error("no landing form");
    // The field is uncontrolled and reports through React's onChange. React
    // remembers the value it last saw and ignores an input event that does not
    // change it; the prototype's setter goes around that memory.
    const setValue = Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      "value",
    )!.set!;
    setValue.call(input, name);
    input.dispatchEvent(new window.Event("input", { bubbles: true }));
    create.click();
  },
);

// Clicks by element rather than by point: the bot button and the start sit
// below the fold of a 768 px window, where a point is not on the page.
const press = registerCustomAction(
  "press",
  async (document, _window, testId: string | null, texts: string[]) => {
    const el = testId
      ? document.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
      : Array.from(document.querySelectorAll("button")).find((b) =>
          texts.includes(b.textContent?.trim() ?? ""),
        );
    if (!el) throw new Error(`nothing to press: ${testId ?? texts.join("/")}`);
    el.scrollIntoView({ block: "center" });
    el.click();
  },
);

// Toward a game: a room, bots until the table is full, the start. Offered next
// to the random actions rather than instead of them, so the lobby still gets
// its share of chaos — a bot kicked, the deck changed, the room left.
const toward = actions(() => {
  const s = screen.current;
  if (s.landing) return [openTable("Bombadil")];
  if (s.lobby && s.canAddBot && s.seats < SEATS) return [press("add-bot", [])];
  if (s.lobby && s.canStart) return [press(null, TEXT.start)];
  return [];
});

export const explore = weighted([
  [1, defaultActions],
  [1, toward],
]);

// ------------------------------------------------------------ properties ---

export const noRawTranslationKey = always(() => screen.current.missing === null);

// Told it is their turn, the player is shown what to do. The other way round —
// a move on offer while the card says to wait — is the narrator's console and
// a cast vote, both deliberate.
export const yourTurnOffersAMove = always(() => {
  const s = screen.current;
  return !TEXT.yourTurn.includes(s.headline ?? "") || s.hasTask;
});

// A timed game never stands still: whatever the table is waiting for, the
// clock ends it. Armed only where that holds — not for the Spielleiter, who
// paces his game himself, nor for a clock somebody paused.
//
// The host of a room without a Spielleiter has the game-master panel too, and
// the walk finds it: the clock switched off mid-phase, or time added. Either
// lets a phase outlast the window honestly, so the clock vanishing or showing
// more than it did lets the obligation go. What that gives up — a deadline
// lost to a bug — is left to the clock's own tests in timer_test.
//
// A reload does not excuse it. The server stops the clock only when nobody at
// all is connected, and bots never disconnect, so the walk reloading every few
// seconds (which it does) leaves the deadline where it was.
export const gameKeepsMoving = always(() => {
  const s = screen.current;
  if (!s.inGame || !s.clock || s.paused || TEXT.narrating.includes(s.headline ?? "")) {
    return true;
  }
  const { stage, secs } = s;
  return eventually(() => {
    const t = screen.current;
    return (
      t.stage !== stage ||
      !t.inGame ||
      !t.clock ||
      t.paused ||
      (t.secs ?? 0) > (secs ?? 0) ||
      TEXT.narrating.includes(t.headline ?? "")
    );
  }).within(STALL_SECS, "seconds");
});

// The server is on this machine. A banner saying the connection is lost may
// flash across a reload, and must not stay.
export const connectionRecovers = always(
  now(() => screen.current.connLost).implies(
    eventually(() => !screen.current.connLost).within(15, "seconds"),
  ),
);
