(ns werwolf.i18n.en
  "English. Role names stay German on purpose — Seherin, Hexe, Jäger, Amor.

   That is not laziness. Mixed-locale rooms are the point of this whole layer,
   and the table has to be able to say a role out loud and be understood by
   everyone at it. 'Seer' and 'Seherin' in the same conversation is a bug in the
   game, not a translation. The descriptions are what carry the meaning, and
   those are fully translated.

   Key set must match werwolf.i18n.de exactly — there is a test."
  (:require [werwolf.i18n.de :as de]))

(def dict
  {;; ------------------------------------------------------------- shell ---
   :app/title         "Werewolves of Millers Hollow"

   :conn/connecting   "connecting…"
   :conn/connected    "connected"
   :conn/disconnected "disconnected — trying again…"

   :locale/de         "Deutsch"
   :locale/en         "English"

   ;; ----------------------------------------------------------- landing ---
   :landing/name      "Your name"
   :landing/create    "Start a new game"
   :landing/join      "Join a game"
   :landing/code      "Room code"
   :landing/or        "or"
   ;; The open rooms sit above the code field: typing a code is now the fallback
   ;; for a room the list cannot show, not the normal way in.
   :landing/open-rooms "Open games"
   :landing/no-rooms   "No open game right now"
   :landing/refresh    "Refresh"

   ;; ------------------------------------------------------------- lobby ---
   :lobby/title       "Lobby"
   :lobby/players     "Players"
   :lobby/start       "Start the game"
   :lobby/need-more   "Waiting for more players"
   ;; The host may start below the recommended size. A nudge, not a gate: four
   ;; people who want to play now should not be stopped by a number set for a
   ;; bigger evening.
   :lobby/below-recommended (fn [n] (str "You can start — it plays better with "
                                         n " or more"))
   :lobby/share       "Share this code"
   :lobby/leave       "Leave the game"

   ;; ------------------------------------------------------------ phases ---
   :phase/lobby       "Lobby"
   :phase/night       "Night"
   :phase/election    "Mayoral election"
   :phase/day-reveal  "Dawn"
   :phase/discussion  "Discussion"
   :phase/nomination  "Accusations"
   :phase/vote        "Vote"
   :phase/day-resolve "Verdict"
   :phase/reaction    "Last act"
   :phase/game-over   "Game over"

   ;; ------------------------------------------------------------- roles ---
   ;; Names deliberately untranslated — see the namespace docstring.
   :role/werwolf      "Werwolf"
   :role/dorfbewohner "Dorfbewohner"
   :role/seherin      "Seherin"
   :role/hexe         "Hexe"
   :role/jaeger       "Jäger"
   :role/weisser-werwolf "Weißer Werwolf"
   :role/beschuetzer  "Beschützer"
   :role/dieb         "Dieb"
   :role/amor         "Amor"

   :role-desc/werwolf      "Each night from the second on, you and the pack take a victim."
   :role-desc/dorfbewohner "You have no night action — only your voice by day."
   :role-desc/seherin      "The seer. Each night you may learn one player's role."
   :role-desc/hexe         "The witch. One healing potion, one poison, once each per game."
   :role-desc/jaeger       "The hunter. When you die, you take someone with you."
   :role-desc/weisser-werwolf "You hunt with the wolves — and every second night you tear one of them apart. You win alone."
   :role-desc/beschuetzer  "Each night you guard one player from the werewolves — never the same one twice running."
   :role-desc/dieb         "On the first night you see two leftover cards and may take one — or stay a Dieb."
   :role-desc/amor         "On the first night you choose two players to fall in love."

   ;; ----------------------------------------------------------- actions ---
   :action/start-game      "Start the game"
   :action/leave           "Leave"
   :action/set-locale      "Language"
   :action/chat            "Say"
   :action/ready           "I'm done"
   :action/amor-link       "Choose the lovers"
   :action/seherin-inspect "Reveal a role"
   :action/werewolf-vote   "Choose the victim"
   :action/beschuetzer-protect "Guard"
   :action/weisser-kill    "Tear apart a wolf"
   :action/weisser-pass    "Spare them tonight"
   :action/dieb-take       "Take a card"
   :action/dieb-keep       "Keep your own card"
   :action/hexe-heal       "Use the healing potion"
   :action/hexe-poison     "Use the poison"
   :action/hexe-pass       "Use neither potion"
   :action/jaeger-shoot    "Take the shot"
   :action/jaeger-pass     "Hold your fire"
   :action/elect           "Elect as Bürgermeister"
   :action/elect-abstain   "Abstain"
   :action/mayor-appoint   "Name a successor"
   :action/mayor-decline   "Name nobody"
   :action/nominate        "Accuse"
   :action/nominate-pass   "Accuse nobody"
   :action/vote            "Condemn"
   :action/vote-abstain    "Abstain"
   :action/advance-phase   "Next phase (GM)"
   :action/gm-force-kill   "Kill (GM)"
   :action/gm-revive       "Revive (GM)"
   :action/gm-skip-role    "Skip role (GM)"
   :action/gm-set-phase    "Set phase (GM)"
   :action/gm-reassign-role "Reassign role (GM)"
   :action/gm-pause        "Pause the clock (GM)"
   :action/gm-resume       "Resume the clock (GM)"
   :action/gm-add-time     "+60 s (GM)"

   ;; ----------------------------------------------------- game master ---
   :gm/panel          "Game master"
   :gm/panel-hint     "Only you can see these. Every override goes in the chronicle."
   :gm/open           "Open"
   :gm/close          "Close"
   :gm/pick-player    "Player"
   :gm/pick-role      "Role"
   :gm/pick-phase     "Phase"
   :gm/confirm        "Sure?"
   :gm/clock          "Clock"
   :gm/nothing        "Nothing to control right now."

   ;; -------------------------------------------------------------- game ---
   :game/you           "you"
   :game/dead          "dead"
   :game/absent        "away"
   :game/your-role     "Your role"
   :game/in-love       "You are in love with"
   :game/heiltrank     "Healing potion"
   :game/gifttrank     "Poison"
   :game/knows         "Known"
   :game/log           "Chronicle"
   :game/chat          "Talk"
   :game/chat-hint     "Write a message…"
   :game/waiting       "Waiting for the others…"
   :game/nothing-to-do "Nothing for you to do right now."
   :game/wolves-channel "Only the werewolves can hear you"
   :game/dead-channel   "Only the dead can hear you"
   :game/paused         "Clock paused"
   :game/new-round      "Back — new game"
   :game/over-hint      "Every role is revealed now."
   :game/dead-hint      "You are dead — you are only watching now."

   ;; ------------------------------------------------------------- turn ---
   ;;
   ;; The banner that says whether the game is waiting for YOU. Its own group
   ;; because "waiting for the others" already exists as :game/waiting and these
   ;; are the other half of that sentence.
   ;;
   ;; "Spielleiter" stays German for the same reason the role names do: a mixed
   ;; table has to be able to say it out loud and be understood by everyone at it.
   :turn/yours   "Your turn"
   :turn/hint    "Choose an action below"
   :turn/urgent  "Everyone is waiting for you"
   :turn/calling (fn [role] (str "The Spielleiter calls: " role))

   ;; ------------------------------------------------------------ voice ---
   ;;
   ;; Joining is an explicit tap: browsers refuse a microphone without a user
   ;; gesture, and opening one because somebody loaded a page is the wrong
   ;; default anyway. `:voice/everyone-hears` is deliberately plain about the
   ;; rule — the server enforces no phase muting, so the UI must not imply it.
   :voice/title          "Voice"
   :voice/join           "🎧 Join voice"
   :voice/joining        "Connecting…"
   :voice/leave          "Leave"
   :voice/mute           "Mute"
   :voice/unmute         "Unmute"
   :voice/everyone-hears "Everyone at the table hears you — at night too, and when dead."

   ;; --------------------------------------------------------- targeting ---
   :target/pick-one   "Choose a target"
   :target/pick-two   "Choose two targets"
   :target/chosen-one "First one chosen — now the second"
   :target/cancel     "Cancel"
   :target/hint       "Click a player below"

   ;; ------------------------------------------------------------ badges ---
   :badge/accused     "accused"
   :badge/votes       "votes"
   :badge/vote        "vote"
   :badge/ready       "done"
   :badge/decided     "has chosen"
   :badge/mayor       "BM"
   :badge/host        "GM"
   :badge/lover       "in love"
   :badge/waiting-for "Still waiting on"

   ;; --------------------------------------------------------------- log ---
   :log/show-all      "Show everything"
   :log/show-less     "Only what matters"
   :log/hidden        "more entries"

   :log/joined        (fn [n] (str n " joined"))
   :log/left          (fn [n] (str n " left the game"))
   :log/disconnected  (fn [n] (str n " is away"))
   :log/reconnected   (fn [n] (str n " is back"))
   :log/host-changed  (fn [n] (str n " is now the game master"))
   :log/game-started  "The first night falls"
   :log/amor-linked   "Amor has chosen two lovers"
   :log/night-began   "Night falls"
   :log/night-quiet-first "The first night passes quietly — nobody dies"
   :log/night-quiet   "Nobody died during the night"
   :log/night-ended   "Dawn breaks"
   :log/day-began     "The village gathers"
   :log/ready         (fn [n] (str n " is done"))
   :log/nominate-passed (fn [n] (str n " accuses nobody"))
   :log/no-accusation "The village accuses nobody — nobody dies today"
   :log/jaeger-held-fire (fn [n] (str n " lowers the rifle"))
   :log/vote-cast     (fn [v t] (str v " → " t))
   :log/vote-abstained (fn [v] (str v " abstains"))

   :log/died-werwolf  (fn [n] (str n " was torn apart by the werewolves"))
   :log/died-gift     (fn [n] (str n " was poisoned"))
   :log/died-liebe    (fn [n] (str n " follows their love into death"))
   :log/died-lynch    (fn [n] (str n " was condemned by the village"))
   :log/died-jaeger   (fn [n] (str n " was shot by the Jäger"))
   :log/died-weisser  (fn [n] (str n " was torn apart by the Weißer Werwolf"))
   :log/died-gm       (fn [n] (str n " was removed by the game master"))
   :log/died          (fn [n] (str n " died"))

   :log/revived       (fn [n] (str n " lives again"))
   :log/role-revealed (fn [n r] (str n " was the " r))
   :log/jaeger-shot   (fn [n t] (str n " shoots " t))
   :log/lynched       (fn [n] (str "The village condemns " n))
   :log/tie           "The village cannot agree — nobody dies"
   ;; The election. Public, because the entire point of the office is that the
   ;; table knows whose vote counts twice.
   :log/mayor-elected   (fn [n] (str n " was elected Bürgermeister"))
   :log/mayor-none      "The village could not agree on a Bürgermeister"
   :log/mayor-appointed (fn [n] (str n " takes over as Bürgermeister"))
   :log/mayor-vacated   "The office of Bürgermeister is left empty"
   :log/nominated     (fn [n] (str n " stands accused"))
   :log/timed-out     (fn [p] (str "Time ran out for " p))
   ;; What the game master actually did. Only for the overrides that would
   ;; otherwise leave no trace: killing and reviving already write their own
   ;; line, and saying the same thing twice reads as a bug.
   ;;
   ;; The new role is deliberately NOT here. The whole table reads the
   ;; chronicle — that an override happened concerns everyone, what the card now
   ;; says concerns one player. See `handle :gm/reassign-role`.
   :log/gm-forced     "The game master intervenes"
   :log/gm-skip-role  (fn [r] (str "The game master skips the " r))
   :log/gm-set-phase  (fn [p] (str "The game master sets the phase to " p))
   :log/gm-reassign-role (fn [n] (str n " was given a new role by the game master"))
   :log/clock-paused  "The game master pauses the clock"
   :log/clock-resumed "The clock is running again"
   :log/win-village   "The village wins"
   :log/win-werewolf  "The werewolves win"
   :log/win-lovers    "The lovers win"
   :log/win-weisser   "The Weißer Werwolf wins alone"

   :log/group-ready        (fn [names] (str names " are done"))
   :log/group-passed       (fn [names] (str names " accuse nobody"))
   :log/group-voted        (fn [names] (str names " have voted"))
   :log/group-joined       (fn [names] (str names " joined"))
   :log/group-disconnected (fn [names] (str names " are away"))
   :log/group-reconnected  (fn [names] (str names " are back"))

   ;; ------------------------------------------------------------ errors ---
   ;; Voice failures. A refused microphone gets its own line because it is fixed
   ;; in browser settings, not by tapping again — and it is by far the common one.
   :error/mic-denied       "No access to the microphone — please allow it in your browser settings"
   ;; Not something the app can fix: without HTTPS the browser exposes no
   ;; microphone at all. Mostly bites phones opening the machine by IP.
   :error/insecure-context "Voice needs HTTPS — over a plain IP address the browser will not release a microphone"
   :error/join-failed      "Could not connect to voice"
   :error/no-such-room     "There is no such room"
   :error/game-in-progress "That game has already started"
   :error/already-joined   "You are already in this game"
   :error/room-full        "The room is full"
   :error/name-required    "Please enter a name"
   :error/code-required    "Please enter the room code"
   :error/not-in-room      "You are not in this game"
   :error/unknown-player   "No such seat"
   :error/seat-gone        "Your seat no longer exists"
   :error/expired          "Your session has expired"
   :error/wrong-room       "That pass belongs to a different game"
   :error/bad-signature    "Invalid pass"
   :error/malformed        "Invalid request"
   :error/chat-not-allowed "You cannot speak right now"
   :error/empty-message    "The message is empty"
   :error/not-allowed      "You cannot do that right now"
   :error/invalid-target   "That target is not allowed"
   :error/already-in-room  "You are already in a game"
   :error/not-host         "Only the game master can do that"
   :error/already-dead     "That player is already dead"
   :error/already-alive    "That player is still alive"
   :error/already-paused   "The clock is already paused"
   :error/not-paused       "The clock is already running"
   :error/no-deadline      "No clock is running"
   :error/not-night        "That only works at night"
   :error/cannot-advance   "This phase cannot be advanced"
   :error/distinct-targets-required "Please choose two different players"
   :error/unsupported-locale "No such language"
   :error/unknown-command  "Unknown command"
   :error/internal-error   "Something went wrong"})

(def missing-keys
  "Keys German has and English does not. Zero by contract — the test in
   i18n_test.cljc fails on anything here, and this is exposed so the failure
   message can say which ones rather than 'the sets differ'."
  (sort (remove (set (keys dict)) (keys de/dict))))
