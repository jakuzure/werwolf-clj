# Changelog

## [0.10.0](https://github.com/jakuzure/werwolf-clj/compare/v0.9.0...v0.10.0) (2026-10-21)


### Features

* **engine:** a bot decides from its own view ([431dd25](https://github.com/jakuzure/werwolf-clj/commit/431dd250ba5caf675b92189767e51d513ce6cfbe))
* **engine:** a seat can be a bot, and the engine issues seat numbers ([8794b99](https://github.com/jakuzure/werwolf-clj/commit/8794b99e827aa379dc711d9e79ce7879fae9b97f))
* **engine:** the host seats a bot from the lobby ([42ed8fb](https://github.com/jakuzure/werwolf-clj/commit/42ed8fbd607151c55c43ed69d574cf4a01ae175c))
* **server:** the clock plays the bots ([6aa6820](https://github.com/jakuzure/werwolf-clj/commit/6aa682010edb63cffb8ab4651660fc5f416f53c1))
* **ui:** add bots from the lobby ([878a658](https://github.com/jakuzure/werwolf-clj/commit/878a6587bcc8565dada83dfdccd453b4a3edf546))


### Bug Fixes

* **server:** a bot's floor counts from the human's return, and each offer gets its own delay ([949ebd1](https://github.com/jakuzure/werwolf-clj/commit/949ebd17f03ab5106b81b964e44ad4511c8a90f7))

## [0.9.0](https://github.com/jakuzure/werwolf-clj/compare/v0.8.0...v0.9.0) (2026-10-21)


### Features

* **client:** keep the screen awake during a game ([a6bfe01](https://github.com/jakuzure/werwolf-clj/commit/a6bfe01502f89e7a15a3bfb2c817b3d6cad4d347))
* **engine:** two wolves from six players ([2bea8f5](https://github.com/jakuzure/werwolf-clj/commit/2bea8f5dbaf05a0790b74d88301abf898c6fbfad))


### Bug Fixes

* **nix:** let Caddy wait for a slow app instead of answering 503 ([910b9b0](https://github.com/jakuzure/werwolf-clj/commit/910b9b07ea257ddb2f216ecf74de7fad669671d6))
* **previews:** answer 404 for closed PRs instead of logging a failed upstream lookup ([a69097b](https://github.com/jakuzure/werwolf-clj/commit/a69097b57bc78f85ca7436736339c18265b0a25b))

## [0.8.0](https://github.com/jakuzure/werwolf-clj/compare/v0.7.0...v0.8.0) (2026-10-19)


### Features

* Prometheus metrics, a NixOS option and a Grafana dashboard ([84ed4b7](https://github.com/jakuzure/werwolf-clj/commit/84ed4b7edf277fde604ed030215abef0289b96c3))
* the village votes on the first day too, as Düsterwald does ([ba45504](https://github.com/jakuzure/werwolf-clj/commit/ba45504066cf49c070eea0ceaec391b87e733f07))
* **ui:** a QR code of the invitation link in the lobby ([a977089](https://github.com/jakuzure/werwolf-clj/commit/a977089b715f202c0a59cd37dc40b37bd7d19707))
* **ui:** a top bar with the phase, your card and the clock ([e4cc532](https://github.com/jakuzure/werwolf-clj/commit/e4cc532307fc0d343dda348d60383cb4924ed36f))
* **ui:** make a move where the question is asked, with fewer taps ([cd71cb5](https://github.com/jakuzure/werwolf-clj/commit/cd71cb52096fad08115ecda55d3a960179772b44))
* **ui:** tabs on a phone, three columns on a wide screen ([41137b7](https://github.com/jakuzure/werwolf-clj/commit/41137b776baee80c77969aad851a7730b233ff09))
* **ui:** the lobby in the game's frame, with a link to invite people ([b7a127b](https://github.com/jakuzure/werwolf-clj/commit/b7a127bbc2d80ca65caec65b4e41274c4107cdf1))
* **ui:** what the QA game suggested beyond its bugs ([0a0b03e](https://github.com/jakuzure/werwolf-clj/commit/0a0b03ea8eff7b82ef89743e65a7fa34db8a64c1))


### Bug Fixes

* allow sqlite-jdbc's native access on JDK 25 ([5641e1d](https://github.com/jakuzure/werwolf-clj/commit/5641e1da5ebaf2d6c5902571dd8133b74b286f12))
* **build:** regenerate deps-lock.json with the pinned clj-nix ([5f28f5a](https://github.com/jakuzure/werwolf-clj/commit/5f28f5abf321dcfdc5a93b9ffceca889f68fe4d8))
* **client:** a seat from an earlier visit that is gone goes quietly ([e018655](https://github.com/jakuzure/werwolf-clj/commit/e018655586e5eb98633ef45dce956516bae6ffac))
* give the first day's talk its own clock, not the election's ([47e35ce](https://github.com/jakuzure/werwolf-clj/commit/47e35ce34144d41b38b9b5d16b3f2db70d2e6982))
* **i18n:** one word for the Spielleiter, and no sentence said twice ([f4d85d8](https://github.com/jakuzure/werwolf-clj/commit/f4d85d834945e7c92fc96557fcb472c729f0ab5c))
* **ui:** confirm a killing move where you made it ([fc8c93c](https://github.com/jakuzure/werwolf-clj/commit/fc8c93c1a92c6e39f94fb17499aecd8829ae34f3))
* **ui:** every death told once, and day one reads plainly ([6e3b744](https://github.com/jakuzure/werwolf-clj/commit/6e3b744d3aa4f5d348c9edcec77db3f98b408195))
* **ui:** tell the Hexe the wolves' victim up front, and reword day one ([b7bc833](https://github.com/jakuzure/werwolf-clj/commit/b7bc833a7916ac1e246042e1efd65d285f9811fe))
* what the QA games with every role found broken ([07eaab6](https://github.com/jakuzure/werwolf-clj/commit/07eaab6ee46c8d15b1341b80b687b7282fcea16a))

## [0.7.0](https://github.com/jakuzure/werwolf-clj/compare/v0.6.0...v0.7.0) (2026-10-18)


### Features

* keep the table's record across the games of a room ([547161d](https://github.com/jakuzure/werwolf-clj/commit/547161d51e71b9f385e4d9f787a76a950591921c))
* **ui:** ask before a move that kills ([098c375](https://github.com/jakuzure/werwolf-clj/commit/098c37523c78560a16bff2a9aafd1d161f26a70f))
* **ui:** show the day's vote as bars towards the majority ([13cf752](https://github.com/jakuzure/werwolf-clj/commit/13cf7523945dc9609315a2545b89c6d1a07e8638))
* **ui:** show the night's order, and let the sleeping sleep ([b9e1538](https://github.com/jakuzure/werwolf-clj/commit/b9e1538d8cd20285b55b2cdafad692f5636a24b6))
* **ui:** tell the morning once, and tell a player when it is their turn ([ed808dc](https://github.com/jakuzure/werwolf-clj/commit/ed808dceae43477b10f6087714fcb7409cf50968))


### Bug Fixes

* **ui:** keep only what a glance cannot tell on the player cards ([41e7761](https://github.com/jakuzure/werwolf-clj/commit/41e776186eb4145daf1da4ad4fa1700870a47d31))

## [0.6.0](https://github.com/jakuzure/werwolf-clj/compare/v0.5.0...v0.6.0) (2026-10-17)


### Features

* explain the game and its roles on the start page ([05dec7c](https://github.com/jakuzure/werwolf-clj/commit/05dec7ca8d74697205f799e8d4181b12a59f744f))
* let the host deal the same table again after a game ([6a6b214](https://github.com/jakuzure/werwolf-clj/commit/6a6b2144fde50ac4bc5a3f89d6836fb1e16bc2d9))
* make the day one open ballot that needs a majority ([299df26](https://github.com/jakuzure/werwolf-clj/commit/299df264fdd47d444ceed985f360c6c397e47896))
* the first round as in Düsterwald, without a guess on day 1 ([e64e97f](https://github.com/jakuzure/werwolf-clj/commit/e64e97f2abba99d1f2b0ee74bc677969920484c6))


### Bug Fixes

* refuse to begin the night on a deal that is no game ([30136c9](https://github.com/jakuzure/werwolf-clj/commit/30136c97f200b1f3e88fb4dd424e3c11c9202122))
* the Hexe sleeps through a first night without a kill ([780284b](https://github.com/jakuzure/werwolf-clj/commit/780284b1a0227ea557c9b768f6d470a6190fd673))
* **ui:** keep the Spielleiter on the board once the game has begun ([b3e17c4](https://github.com/jakuzure/werwolf-clj/commit/b3e17c44015adc1fdb3a141e2dd4a658bcaeede2))
* **ui:** show a player's initial, not their seat number ([286f1c6](https://github.com/jakuzure/werwolf-clj/commit/286f1c6ccdb7101bb44b004e3988030c45dcdd09))
* **ui:** switch the language on the landing page without an error ([c3821fc](https://github.com/jakuzure/werwolf-clj/commit/c3821fc7133568311c81c657b8cabed897f50a73))

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
