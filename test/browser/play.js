// Driving a whole game through five browsers.
//
// Every helper here works the way a player does: look at what the interface is
// offering, click it. Nothing reaches into app-db or the socket. If the engine
// stops offering an action the UI stops showing it and these stop working, which
// is the coupling we want — a test that bypassed the affordances would keep
// passing after the buttons stopped appearing.
//
// Action names are matched with anchored regexes because some labels are
// prefixes of others ("Nachfolge bestimmen …" against "Niemanden bestimmen"),
// and because actions that need a target carry a trailing ellipsis — see
// `action-bar`.

const { expect } = require('@playwright/test');

const ACT = {
  start:        /^Spiel starten/,
  inspect:      /^Rolle ansehen/,
  eat:          /^Opfer wählen/,
  guard:        /^Schützen/,
  hexePass:     /^Keinen Trank benutzen/,
  electAbstain: /^Enthalten/,
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
  // A move that kills — the wolves' vote, poison, a shot — asks for a second
  // tap before it is sent. Anything else has already been sent by now.
  const yes = page.getByTestId('confirm-yes');
  if (await yes.waitFor({ state: 'visible', timeout: 1_500 }).then(() => true, () => false)) {
    await yes.click();
  }
}

/** Click an action if this page is offering it RIGHT NOW. */
async function tryAct(page, name, targetSeat) {
  const btn = page.getByRole('button', { name });
  if (!(await btn.count()) || !(await btn.first().isVisible())) return false;
  await clickMove(page, btn.first(), targetSeat);
  return true;
}

/**
 * Click a move's button, then pick its target if it asks for one.
 *
 * A move with exactly one possible target names that player on its button and
 * is made by the click itself — the Seherin on night two, say, once the only
 * stranger left is the Hexe. There is nothing to pick then; the button says
 * who it went to, so check that instead.
 */
async function clickMove(page, button, targetSeat) {
  const direct = ((await button.getAttribute('data-testid')) || '').startsWith('direct-');
  if (direct && targetSeat != null) {
    const target = await page.getByTestId(`seat-${targetSeat}`).locator('.font-semibold').first().innerText();
    await expect(button).toContainText(target.trim());
  }
  await button.click();
  if (targetSeat != null && !direct) await pickTarget(page, targetSeat);
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
  await clickMove(who.page, btn.first(), targetSeat);
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

// The Spielleiter's own steps in a room without a clock: each role is called
// by him, the village wakes on his word, the ballot closes when he says so and
// the night falls on the verdict when he lets it. Each is the one button on
// his Jetzt card, labelled with the step it takes; the labels that cut a
// player's turn short ("… überspringen", "Weiter, ohne zu warten") are never
// matched here, so a race can at worst press nothing.
const STEP = {
  call:      / rufen$/,
  morning:   /^Das Dorf wecken$/,
  closeVote: /^Abstimmung schließen$/,
  nightfall: /^Weiter$/,
};

/**
 * Press the Spielleiter's button once it offers `step`, then wait for its label
 * to change: pressing the same label twice would take the NEXT step before
 * anybody had acted. False when the step never came, which is how a role not
 * at the table is passed over.
 */
async function pace(sl, step) {
  const btn = sl.getByTestId('narrator-skip');
  const offered = await expect(btn).toHaveText(step, { timeout: 10_000 }).then(() => true, () => false);
  if (!offered) return false;
  const label = (await btn.innerText()).trim();
  await btn.click();
  await expect(btn).not.toHaveText(label, { timeout: 10_000 }).catch(() => {});
  return true;
}

/**
 * Play one night: the Spielleiter (`sl`) calls each role in registry order and
 * the role acts, then he wakes the village. A role that is not at the table is
 * not called, so its act is optional and the call for it simply finds the
 * next role's label instead.
 */
async function playNight(roles, { sl, eat, inspect, guard }) {
  if (inspect != null) {
    await pace(sl, STEP.call);
    await actAs(roles, 'Seherin', ACT.inspect, inspect, { optional: true });
  }
  if (guard != null) {
    await pace(sl, STEP.call);
    await actAs(roles, 'Beschützer', ACT.guard, guard, { optional: true });
  }
  if (eat != null) {
    await pace(sl, STEP.call);
    await actAs(roles, 'Werwolf', ACT.eat, eat);
  }
  await pace(sl, STEP.call);
  await actAs(roles, 'Hexe', ACT.hexePass, null, { optional: true });
  await pace(sl, STEP.morning);
}

/**
 * Dawn through the end of the vote.
 *
 * The day is one open ballot: the table talks and votes at once, and the last
 * vote closes it. `lynch` is the seat the village condemns, or null for a day
 * that kills nobody. Everybody else votes against him, which is a majority at
 * any table; he cannot vote for himself, so he abstains.
 *
 * Each page votes ONCE. The buttons stay after a vote, so a sweep with
 * `everyone` afterwards would change votes rather than cast missing ones.
 *
 * The last vote no longer closes the ballot: the Spielleiter, `pages[0]`,
 * does, once everybody's is in. The verdict then rests on the table until he
 * lets the night fall, which is the caller's to do (or to look at first).
 */
async function playDay(pages, { lynch, roles, election = false }) {
  const [sl] = pages;
  // ONLY the first day holds an election, so waiting for one on later days burns
  // the full timeout against a button that will never appear — which is what
  // pushed the three-night game past its limit. Abstaining elects nobody, keeping
  // the double vote out of tests that are not about it.
  if (election) await everyone(pages, ACT.electAbstain);

  if (lynch == null) {
    await everyone(pages, ACT.voteAbstain);
    await pace(sl, STEP.closeVote);
    return;
  }

  await awaitOffered(pages, ACT.vote);
  for (const r of roles) {
    if (r.seat === lynch) await tryAct(r.page, ACT.voteAbstain);
    else await tryAct(r.page, ACT.vote, lynch);
  }
  await pace(sl, STEP.closeVote);
}

module.exports = { ACT, STEP, pace, tryAct, actAs, everyone, playNight, playDay };
