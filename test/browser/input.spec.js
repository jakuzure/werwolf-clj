// The text input, in a real browser.
//
// This exists for one reported bug: on Android, typing "Greta" and accepting the
// keyboard's suggestion produced "GrGrGreta". The cause was not Android — it was
// that `rf/dispatch` is ASYNCHRONOUS, so a fully controlled input's :value lagged
// the DOM by at least a tick and React wrote the stale value back. On a desktop
// that is a cursor jump; inside an IME composition it corrupts the composing
// region and the commit appends instead of replacing.
//
// Which of these actually guards the bug, stated precisely, because a test that
// passes either way guards nothing and should not be mistaken for protection:
//
//   * The IME test DISCRIMINATES. It fails with `GGrGreGreta` against the original
//     controlled input AND against the first attempted fix (a synchronous local
//     draft), and passes only against the uncontrolled input that shipped. It is
//     the regression test.
//
//   * The rapid-typing test does NOT discriminate — it passes against all three
//     implementations. Kept anyway as a smoke test: it is the only thing anywhere
//     that proves a keystroke reaches app-db and back out through a real browser.
//
// The distinction that made the IME test possible: a composing region is INTERNAL
// browser state. Dispatching `new CompositionEvent(...)` from JS does not create
// one, and a version of this test written that way passed against the broken code.
// Driving it through the DevTools Protocol does create one.

const { test, expect } = require('@playwright/test');
const { landOn, DE } = require('./helpers');

test.describe('text input', () => {
  test('keeps every character when they arrive faster than a dispatch tick', async ({ page }) => {
    await landOn(page);
    const field = page.getByPlaceholder(DE.name);
    await field.click();

    // Fire the input events synchronously, with no await between them, so they
    // all land inside a single task — faster than re-frame can process its
    // queue. This is the condition the bug needed: under the old controlled
    // input, React re-rendered from an app-db that was several characters
    // behind and reset the element's value to match.
    await page.evaluate(() => {
      const el = document.querySelector('input');
      el.focus();
      for (const s of ['G', 'Gr', 'Gre', 'Gret', 'Greta']) {
        el.value = s;
        el.dispatchEvent(new Event('input', { bubbles: true }));
      }
    });

    // Let every queued dispatch and re-render settle before looking. If the
    // value survives THIS, it survived the round trip through app-db.
    await page.waitForTimeout(300);
    await expect(field).toHaveValue('Greta');
  });

  test('an IME suggestion replaces the composing text instead of appending', async ({ page }) => {
    await landOn(page);
    const field = page.getByPlaceholder(DE.name);
    await field.click();

    // Driven through the DevTools Protocol, not by dispatching CompositionEvents
    // from JS. That distinction is the whole test: a composing region is INTERNAL
    // browser state, and synthetic events do not create one — a version of this
    // test written with `new CompositionEvent(...)` passed against the broken
    // implementation too, which is to say it tested nothing.
    //
    // `Input.imeSetComposition` puts the input into a real composition;
    // `Input.insertText` commits over it, which is what accepting a keyboard
    // suggestion does.
    const cdp = await page.context().newCDPSession(page);

    for (const text of ['T', 'Ta', 'Tan']) {
      await cdp.send('Input.imeSetComposition', {
        text,
        selectionStart: text.length,
        selectionEnd: text.length,
      });
      // A beat between keystrokes, so app-db is genuinely a tick behind — the
      // condition the bug needed.
      await page.waitForTimeout(20);
    }

    // Accepting the suggestion: the composing region is replaced wholesale.
    await cdp.send('Input.insertText', { text: 'Greta' });
    await page.waitForTimeout(300);

    // "GrGrGreta" is named because it is the exact string that was reported, and
    // a future reader should be able to grep for it.
    const got = await field.inputValue();
    expect(got, `IME commit produced ${JSON.stringify(got)}`).toBe('Greta');
  });

  test('a programmatic write still beats what you were typing', async ({ page }) => {
    // The other half of the fix: the input keeps a local draft, so app-db must be
    // able to overrule it or tapping a room in the list could never fill the code
    // field. That is what the generation counter is for.
    await landOn(page);
    await page.getByPlaceholder(DE.name).fill('Anna');
    const codeField = page.getByPlaceholder(DE.code);
    await codeField.click();
    await codeField.fill('ZZ');

    // Create a room in another context so the list has something to tap.
    const other = await page.context().browser().newContext();
    const otherPage = await other.newPage();
    await landOn(otherPage);
    await otherPage.getByPlaceholder(DE.name).fill('Bob');
    await otherPage.getByRole('button', { name: DE.create }).click();
    const code = (await otherPage.locator('.font-mono').first().innerText()).trim();

    // Refresh the list, then tap the room.
    await page.getByRole('button', { name: 'Neu laden' }).click();
    await page.getByRole('button', { name: new RegExp(code) }).click();

    // Having tapped a listed room, we are in it — which is the observable proof
    // that the programmatic write won over the half-typed "ZZ".
    await expect(page.getByRole('button', { name: DE.leave })).toBeVisible();
    await other.close();
  });
});
