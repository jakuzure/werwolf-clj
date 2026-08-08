// Driving a whole game through five browsers.
//
// Every helper here works the way a player does: look at what the interface is
// offering, click it. Nothing reaches into app-db or the socket. If the engine
// stops offering an action the UI stops showing it and these stop working, which
// is the coupling we want — a test that bypassed the affordances would keep
// passing after the buttons stopped appearing.
//
// Action names are matched with anchored regexes because several labels are
// prefixes of others ("Anklagen …" against "Niemanden anklagen"), and because
// actions that need a target carry a trailing ellipsis — see `action-bar`.

const { expect } = require('@playwright/test');

const ACT = {
  start:        /^Spiel starten/,
  inspect:      /^Rolle aufdecken/,
  eat:          /^Opfer wählen/,
  guard:        /^Schützen/,
  hexePass:     /^Keinen Trank benutzen/,
  electAbstain: /^Enthalten/,
  ready:        /^Ich bin fertig/,
  nominate:     /^Anklagen/,
  nominatePass: /^Niemanden anklagen/,
  vote:         /^Verurteilen/,
  voteAbstain:  /^Enthalten/,
  shoot:        /^Schuss abgeben/,
  holdFire:     /^Nicht schießen/,
  appoint:      /^Nachfolge bestimmen/,
  nameNobody:   /^Niemanden bestimmen/,
};

// The amber bar that appears once an action is armed and the board is waiting
// for a seat. Its presence is the UI's own statement that picking will register.
const TARGET_PROMPT = 'Klicke unten auf einen Mitspieler';

/**
 * Pick a seat for an action that has just been armed.
 *
 * Waits for the prompt first, and that wait is load-bearing: arming dispatches
 * through re-frame, which is ASYNCHRONOUS, so a click that lands before the board
 * is armed hits a card that is not yet selectable and is silently dropped. The
 * symptom is the whole night stalling one turn later, which is exactly how this
 * file first failed. The same asynchrony caused the Android keyboard bug.
 */
async function pickTarget(page, seat) {
  await page.getByText(TARGET_PROMPT).first()
    .waitFor({ state: 'visible', timeout: 10_000 });
  await page.getByTestId(`seat-${seat}`).click();
}

/** Click an action if this page is offering it RIGHT NOW. */
async function tryAct(page, name, targetSeat) {
  const btn = page.getByRole('button', { name });
  if (!(await btn.count()) || !(await btn.first().isVisible())) return false;
  await btn.first().click();
  if (targetSeat != null) await pickTarget(page, targetSeat);
  return true;
}

/** Wait until SOMEBODY is offered this action, then let the broadcast settle. */
async function awaitOffered(pages, name) {
  await Promise.race(pages.map((p) =>
    p.getByRole('button', { name }).first()
      .waitFor({ state: 'visible', timeout: 20_000 }).catch(() => {})));
  await pages[0].waitForTimeout(300);
}

/**
 * Wait for a specific ROLE to be offered an action, then take it.
 *
 * Waiting is the point. The night is sequential — Seherin, then Beschützer, then
 * the wolves, then the Hexe — and each turn only appears once the previous one
 * has been committed and broadcast. Checking instantly finds nothing and silently
 * skips the turn, which is how the first version of this file left every game
 * stuck in night one.
 */
async function actAs(roles, roleName, name, targetSeat, { optional = false } = {}) {
  const who = roles.find((r) => r.role === roleName);
  if (!who) {
    expect(optional, `no ${roleName} at this table`).toBe(true);
    return false;
  }
  const btn = who.page.getByRole('button', { name });
  try {
    await btn.first().waitFor({ state: 'visible', timeout: 20_000 });
  } catch (_) {
    if (optional) return false;
    throw new Error(`${roleName} was never offered ${name}`);
  }
  await btn.first().click();
  if (targetSeat != null) await pickTarget(who.page, targetSeat);
  return true;
}

/**
 * Everyone still holding this action takes it.
 *
 * Waits for the phase to be live on ANY page first, then sweeps. The alternative
 * — waiting per page — costs a full timeout for every dead player, who correctly
 * never gets the button.
 */
async function everyone(pages, name, targetSeat) {
  await awaitOffered(pages, name);
  let n = 0;
  for (const p of pages) if (await tryAct(p, name, targetSeat)) n++;
  return n;
}

/** Play one night: each role acts in registry order. */
async function playNight(roles, { eat, inspect, guard }) {
  if (inspect != null) await actAs(roles, 'Seherin', ACT.inspect, inspect, { optional: true });
  if (guard != null) await actAs(roles, 'Beschützer', ACT.guard, guard, { optional: true });
  if (eat != null) await actAs(roles, 'Werwolf', ACT.eat, eat);
  await actAs(roles, 'Hexe', ACT.hexePass, null, { optional: true });
}

/**
 * Dawn through the end of the vote.
 *
 * `lynch` is the seat the village condemns, or null for a day that kills nobody.
 * The condemned player cannot accuse himself — `legal-targets` for :nominate
 * excludes yourself — so he accuses somebody else and then abstains.
 */
async function playDay(pages, { lynch, roles, election = false }) {
  // ONLY the first day holds an election, so waiting for one on later days burns
  // the full timeout against a button that will never appear — which is what
  // pushed the three-night game past its limit. Abstaining elects nobody, keeping
  // the double vote out of tests that are not about it.
  if (election) await everyone(pages, ACT.electAbstain);
  await everyone(pages, ACT.ready);

  if (lynch == null) {
    await everyone(pages, ACT.nominatePass);
    await everyone(pages, ACT.voteAbstain);
    return;
  }

  await awaitOffered(pages, ACT.nominate);
  for (const r of roles) {
    const at = r.seat === lynch ? (lynch === 0 ? 1 : 0) : lynch;
    await tryAct(r.page, ACT.nominate, at);
  }
  await everyone(pages, ACT.nominatePass);

  await awaitOffered(pages, ACT.vote);
  for (const r of roles) {
    if (r.seat === lynch) await tryAct(r.page, ACT.voteAbstain);
    else await tryAct(r.page, ACT.vote, lynch);
  }
  await everyone(pages, ACT.voteAbstain);
}

module.exports = { ACT, tryAct, actAs, everyone, playNight, playDay };
