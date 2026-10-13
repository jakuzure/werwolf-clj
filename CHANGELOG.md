# Changelog

## [0.5.0](https://github.com/jakuzure/werwolf-clj/compare/v0.4.0...v0.5.0) (2026-10-13)


### Features

* preview every PR on lain ([566ec01](https://github.com/jakuzure/werwolf-clj/commit/566ec01bf738306ffcabb24200f49afe26dd108e))
* preview every PR on lain ([4f39663](https://github.com/jakuzure/werwolf-clj/commit/4f39663950835b9126f778488eaa499466157c2d))
* **previews:** preview Renovate's PRs too ([3b037ab](https://github.com/jakuzure/werwolf-clj/commit/3b037abcc710638b223442573f87097202e581f5))
* **previews:** prune preview images no container uses ([4633c7e](https://github.com/jakuzure/werwolf-clj/commit/4633c7ea1aabc7d0e1943d52aef35d0c2ab023b4))
* **previews:** size previews at 200Mi, with the heap capped ([3686752](https://github.com/jakuzure/werwolf-clj/commit/3686752ef1135ccf7b566a202ddcfdb1a380daab))
* **previews:** size previews at 200Mi, with the heap capped to fit ([6a067a4](https://github.com/jakuzure/werwolf-clj/commit/6a067a47dab2d4c147f53213e46f78ef9654d4cb))
* **ui:** show the Spielleiter who still has to act, and a way past them ([b2f0420](https://github.com/jakuzure/werwolf-clj/commit/b2f04204846afca55913fcbe01631e8d8da7b6d8))


### Bug Fixes

* **previews:** keep the image config out of the mounted closure ([cbcc1ee](https://github.com/jakuzure/werwolf-clj/commit/cbcc1eedd20548a6ab96ae2380572d6ad571d943))
* **previews:** keep the image config out of the mounted closure ([1adfcfb](https://github.com/jakuzure/werwolf-clj/commit/1adfcfbe96a75ae12edeb3b736c98d313e6854f1))
* **renovate:** match the shadow-cljs jar by its Maven name ([3005dbe](https://github.com/jakuzure/werwolf-clj/commit/3005dbe084d017d310738d3912ab60432a5f018f))
* **ui:** the Spielleitung powers are never your turn ([215c33f](https://github.com/jakuzure/werwolf-clj/commit/215c33ffbc97b32909648f6fadb163ac594b26d2))

## [0.4.0](https://github.com/jakuzure/werwolf-clj/compare/v0.3.0...v0.4.0) (2026-10-12)


### Features

* install the game on the home screen ([fb437da](https://github.com/jakuzure/werwolf-clj/commit/fb437da7f9df8768d60843cecab246eeb075e476))
* keep the game going when the Spielleiter goes missing ([01212e1](https://github.com/jakuzure/werwolf-clj/commit/01212e11f07f35b28e1f215986e3a6f8978ed67e))
* let the host remove players from the lobby ([16b67fb](https://github.com/jakuzure/werwolf-clj/commit/16b67fbdbdf62bd7d64df5b1d6cc580857dda6ad))
* let the Spielleiter choose the roles ([ac8024a](https://github.com/jakuzure/werwolf-clj/commit/ac8024ab659a06a22d3ba5eb5d2620a32a939c48))
* optional password for rooms ([8d9a4a3](https://github.com/jakuzure/werwolf-clj/commit/8d9a4a36ed1cc0b569b15aba5cdbaead18e84bc1))
* show a recap of the whole game at the end ([628991a](https://github.com/jakuzure/werwolf-clj/commit/628991a69dcf855f4a1b6dff50bc1ee46878f3a7))
* **ui:** chime on every device when night falls or day breaks ([db8786d](https://github.com/jakuzure/werwolf-clj/commit/db8786d4a9ca0f93e576dbb07161000f2db4d4b7))
* **ui:** make the phase readable at a glance ([40bc524](https://github.com/jakuzure/werwolf-clj/commit/40bc524922fbe80ed6f513d836da7080df15656d))
* **ui:** make the phase readable at a glance ([8d3e513](https://github.com/jakuzure/werwolf-clj/commit/8d3e5132c61eca3311535fbb86a33b9b2cae9fd0))
* **voice:** show who is already in the call ([5e70715](https://github.com/jakuzure/werwolf-clj/commit/5e707159674b028c14f97c3991d24c2b4a960c79))


### Bug Fixes

* **deps:** update react monorepo to v19 ([7baf2c7](https://github.com/jakuzure/werwolf-clj/commit/7baf2c74696bc8116c2cf68c614abaa01695e991))
* **deps:** update react monorepo to v19 ([4a4b12c](https://github.com/jakuzure/werwolf-clj/commit/4a4b12c223316d65ec311eb382684e5c073e2ff7))
* **i18n:** say the game is most fun from five players ([083d7ac](https://github.com/jakuzure/werwolf-clj/commit/083d7ac35505d676a72283784c3192861a2afae1))
* **server:** retire abandoned games instead of reloading them forever ([0d1a3a1](https://github.com/jakuzure/werwolf-clj/commit/0d1a3a1d6b33bc118596ca5e1c42c40a0858a143))
* **ui:** fold the Chronik by default and lift the board ([88e70ca](https://github.com/jakuzure/werwolf-clj/commit/88e70ca7294139675540a1a19e8ade7a34eca67b))
* **ui:** tidy the role selection ([edc7321](https://github.com/jakuzure/werwolf-clj/commit/edc73214db082a35ce53da105ba3c1de4a8ea65f))

## [0.3.0](https://github.com/jakuzure/werwolf-clj/compare/v0.2.0...v0.3.0) (2026-10-12)


### Features

* **engine:** narrated mode with a Spielleiter seat, setup step and clock switch ([fb1cd1c](https://github.com/jakuzure/werwolf-clj/commit/fb1cd1cdca7b56d3e98d29752485ec9f32afe9cb))
* narrated mode with a Spielleiter seat, setup step and table switches ([3f80372](https://github.com/jakuzure/werwolf-clj/commit/3f80372125c6495e6e58ee9cf0fab9dd9e194dda))
* show the app version on the start screen ([9f41146](https://github.com/jakuzure/werwolf-clj/commit/9f411467eedfbc4f89d648ddb8e891817c903fc3))
* show the build commit next to the version ([c35cecb](https://github.com/jakuzure/werwolf-clj/commit/c35cecbe51e4ee830ab3202206c2b6f8bf007865))
* **ui:** confirm before leaving a round ([260b7bc](https://github.com/jakuzure/werwolf-clj/commit/260b7bcd29b0b38048f4dc779a0560a0811d4065))
* **ui:** Spielleiter seat, setup step and table switches in the client ([11671b9](https://github.com/jakuzure/werwolf-clj/commit/11671b9ef3ffb24ccc0afc3f9a37feca6385bca4))
* **voice:** play a cue when somebody leaves the call ([c8122b6](https://github.com/jakuzure/werwolf-clj/commit/c8122b613299b4b4b6f440ea333948c574dac3bf))


### Bug Fixes

* **http:** deploys show up without a hard reload ([e4c09a6](https://github.com/jakuzure/werwolf-clj/commit/e4c09a6c7fcd97ae2c7a4a329dca462155ad600b))
* **lobby:** free the page on leave and make joining idempotent ([7f3f25e](https://github.com/jakuzure/werwolf-clj/commit/7f3f25e5596215396645335b72663299abcc981c))
* show the build commit as plain text, not a link ([87420c2](https://github.com/jakuzure/werwolf-clj/commit/87420c291224ed839e7a803bea405fb44f0af7ff))

## [0.2.0](https://github.com/jakuzure/werwolf-clj/compare/v0.1.0...v0.2.0) (2026-10-11)


### Features

* the game is now called Werwölfe von Finsterbach ([0e68316](https://github.com/jakuzure/werwolf-clj/commit/0e68316e662ac956f80cd99fd7fc050db144140f))
