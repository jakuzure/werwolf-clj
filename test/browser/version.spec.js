// The start screen shows which build is running, taken from package.json at
// compile time. Checked against the RELEASE bundle, because that is where a
// stale shadow-cljs cache would show an old version after a release bump.
const { test, expect } = require('@playwright/test');
const { version } = require('../../package.json');
const { landOn } = require('./helpers');

test('the start screen shows the package.json version', async ({ page }) => {
  await landOn(page);
  await expect(page.getByTestId('app-version')).toHaveText(`v${version}`);
});
