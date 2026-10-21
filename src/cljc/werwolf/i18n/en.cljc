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
   :app/title         "Werewolves of Finsterbach"

   :conn/connecting   "connecting…"
   :conn/connected    "connected"
   :conn/disconnected "disconnected — trying again…"

   :locale/de         "Deutsch"
   :locale/en         "English"

   ;; ----------------------------------------------------------- landing ---
   :landing/name      "Your name"
   :guide/title       "New here? How to play"
   :guide/intro       "Werewolves have crept in among the villagers of Finsterbach. Every night they take a victim, every day the village tries to unmask them. Everyone gets a secret role — only you know yours. The werewolves know who else is in the pack; everybody else has to work it out."
   :guide/night-title "The night"
   :guide/night       "Everyone is asleep. The roles are called one after another, and whoever is called acts in secret on their own screen: the Seherin looks at a card, the werewolves agree on a victim, the Hexe may save them."
   :guide/day-title   "The day"
   :guide/day         "In the morning you learn who did not survive the night, and see their card. On the first day you elect a mayor first. Then you talk and vote at the same time on who is condemned — every day, the first one included. That takes a majority of the living; until everyone has voted, you can change your vote."
   :guide/win-title   "Who wins"
   :guide/win         "The village wins once no werewolf is left alive. The werewolves win when nobody but them is left. Two lovers win together if they are the last two alive — whichever side they were on. The dead talk only to the dead."
   :guide/roles-title "The roles"
   :guide/team-werewolf "The werewolves"
   :guide/team-village "The village"
   :guide/mayor-title "Mayor"
   :guide/mayor       "Not a card but an office: elected on the first day, their vote counts twice. When they die, they name a successor."
   :guide/table       "Three players can play; it is most fun from five. Whoever opens the game runs it as the narrator, sees every card and does not play — or plays along, and then the clock keeps the game moving. Talk in the voice chat or around the same table."
   :landing/create    "Start a new game"
   :landing/join      "Join a game"
   :landing/invited "You're invited to room"
   :landing/not-invited "Another room instead"
   :landing/invite-not-open "This game is not open right now — it is under way or over."
   :landing/join-anyway "Try anyway"
   :notice/left "You left the game."
   :landing/code      "Room code"
   :landing/or        "or"
   ;; The open rooms sit above the code field: typing a code is now the fallback
   ;; for a room the list cannot show, not the normal way in.
   :landing/open-rooms "Open games"
   :landing/no-rooms   "No open game right now"
   :landing/refresh    "Refresh"
   :landing/password      "Password (optional)"
   :landing/join-password "Room password"
   :landing/locked        "Password protected"

   ;; ------------------------------------------------------------- lobby ---
   :lobby/title       "Lobby"
   :lobby/players     "Players"
   :lobby/count       (fn [n mx mn] (str n " / " mx " players" (when (< n mn) (str " · it is most fun with " mn " or more"))))
   :lobby/start       "Start the game"
   :lobby/need-more   "Waiting for more players"
   ;; The host may start below the recommended size. A nudge, not a gate: four
   ;; people who want to play now should not be stopped by a number set for a
   ;; bigger evening.
   :lobby/below-recommended (fn [n] (str "You can start — it plays better with "
                                         n " or more"))
   :lobby/share       "Share this code"
   :lobby/leave       "Leave the game"
   :lobby/leave-confirm "Really leave?"
   :lobby/leave-cancel  "Stay"
   :lobby/mode               "Narrator"
   :lobby/mode-narrated      "The narrator gets no card, sees every role and guides the table through the game."
   :lobby/mode-players       "The narrator plays along; their switches sit on the side."
   :lobby/narrator-is        (fn [n] (str "Narrator: " n))
   :lobby/you-narrate        "You are the narrator — you are dealt no role."
   :lobby/narrate            "I will narrate"
   :lobby/play-along         "I will play along"
   :lobby/timer              "Time limit"
   :lobby/bots               "Bots"
   :lobby/bots-hint          "Bots play along, so you can test on your own."
   :lobby/timer-switch-on    "Turn the time limit on"
   :lobby/timer-switch-off   "Turn the time limit off"
   :lobby/password           "Password"
   :lobby/mode-short-narrated "does not play"
   :lobby/mode-short-players "plays along"
   :lobby/timer-short-on "on"
   :lobby/timer-short-off "off"
   :lobby/password-short-on "set"
   :lobby/password-short-off "none"
   :lobby/invite-text (fn [code] (str "Play Werewolf with us — room " code))
   :lobby/link-copied "Link copied"
   :lobby/share-link "Share link"
   :lobby/qr "QR code to join"
   :lobby/you-are-in "You're in"
   :lobby/host-starts (fn [n] (str n " starts the game once everybody is here."))
   :lobby/fix-deck "The roles don't fit the table yet — see Roles."
   :lobby/password-new       "New password"
   :lobby/password-set       "Set password"
   :lobby/password-change    "Change password"
   :lobby/password-clear     "Remove password"
   :lobby/make-narrator      "Make narrator"
   :lobby/make-host          "Make narrator"
   :lobby/seat-menu "Options for this seat"

   ;; The roles. Only the special cards are chosen — villagers fill whatever
   ;; is left, so they are not a decision anybody makes.
   :deck/title          "Roles"
   :deck/auto           "Automatic"
   :deck/custom         "Custom"
   :deck/auto-hint      "The roles follow the number of players."
   :deck/custom-hint    "The narrator has chosen the roles."
   :deck/custom-hint-own "You have chosen the roles."
   :deck/fill           "fill the remaining seats"
   :deck/centre         "Middle (for the Dieb)"
   :deck/in-game        "In play"
   :deck/more           (fn [r] (str "Add " r))
   :deck/less           (fn [r] (str "Remove " r))
   :deck/only-villagers "villagers only"
   :deck/problem-too-many-roles (fn [roles n] (str "Too many roles (" roles ") for " n " players"))
   :deck/problem-no-werewolf    "At least one Werwolf has to play"
   :deck/problem-no-villager    "At least one card has to belong to the village"
   :deck/problem-too-many-werewolves "The werewolves must be fewer than everybody else together"
   :setup/problem               (fn [why] (str "The night cannot begin like this: " why))

   ;; ------------------------------------------------------------ phases ---
   :phase/lobby       "Lobby"
   :phase/setup       "Dealing roles"
   :phase/night       "Night"
   :phase/election    "Mayoral election"
   :phase/day-reveal  "Dawn"
   :phase/discussion  "Discussion"
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

   :role-desc/werwolf      "Each night, you and the pack take a victim."
   :role-desc/dorfbewohner "You have no night action — only your voice by day."
   :role-desc/seherin      "The seer. Each night you may learn one player's role."
   :role-desc/hexe         "At night you learn whom the werewolves chose. Once per game you may heal, once poison — both in the same night, if you like."
   :role-desc/jaeger       "The hunter. When you die, you take someone with you."
   :role-desc/weisser-werwolf "You hunt with the wolves — and every second night you tear one of them apart. You win alone."
   :role-desc/beschuetzer  "Each night you guard one player from the werewolves — never the same one twice running."
   :role-desc/dieb         "On the first night you see two leftover cards and may take one — or stay a Dieb."
   :role-desc/amor         "On the first night you choose two players to fall in love."

   ;; ----------------------------------------------------------- actions ---
   :action/start-game      "Start the game"
   :action/play-again      "Again with everyone"
   :action/leave           "Leave"
   :action/set-locale      "Language"
   :action/chat            "Say"
   :action/ready           "I'm done"
   :action/amor-link       "Choose the lovers"
   :action/seherin-inspect "Look at a role"
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
   :action/elect           "Elect as mayor"
   :action/elect-abstain   "Abstain"
   :action/mayor-appoint   "Name a successor"
   :action/mayor-decline   "Name nobody"
   :action/vote            "Condemn"
   :action/vote-abstain    "Abstain"
   :action/begin-night     "Begin the first night"
   :action/advance-phase   "Next phase"
   :action/claim-host      "Become narrator"
   :action/gm-toggle-narrated "Narrator mode"
   :action/gm-toggle-timer "Time limit on/off"
   :action/gm-transfer-host "Hand over the narrator role"
   :action/gm-set-deck     "Choose roles"
   :action/gm-auto-deck    "Automatic roles"
   :action/gm-force-kill   "Kill"
   :action/gm-revive       "Revive"
   :action/gm-skip-role    "Skip role"
   :action/gm-set-phase    "Set phase"
   :action/gm-reassign-role "Reassign role"
   :action/gm-pause        "Pause the clock"
   :action/gm-resume       "Resume the clock"
   :action/gm-add-time     "+60 s"

   ;; ---------------------------------------------------------- removing ---
   ;; The host takes somebody out of the lobby: a ghost seat, a typo, a host
   ;; who was replaced while away. Two taps, like leaving.
   :action/gm-kick    "Remove"
   :action/gm-add-bot "Add a bot"
   :kick/button       "Remove"
   :kick/confirm      "Really remove?"
   :log/kicked        (fn [n] (str n " was removed from the game"))
   :error/kicked      "You were removed from the game"

   ;; ----------------------------------------------------- game master ---
   :gm/panel          "Game master"
   :gm/who "Who?"
   :gm/which-role "Which role?"
   :gm/which-phase "Which phase?"
   :gm/panel-hint     "Only you can see these. Every override goes in the chronicle."
   :gm/open           "Open"
   :gm/close          "Close"
   :gm/pick-player    "Player"
   :gm/pick-role      "Role"
   :gm/pick-phase     "Phase"
   :gm/confirm        "Sure?"
   :gm/clock          "Clock"
   :gm/nothing        "Nothing to control right now."
   :gm/timer-on       "Turn the time limit on"
   :gm/timer-off      "Turn the time limit off"

   ;; ---------------------------------------------------------- narrator ---
   :narrator/title    "Narrator"
   :narrator/desc     "You are not playing. You see every role, call the night and move the phases along."
   :narrator/owing        "Still waiting for"
   :narrator/owing-none   "Nobody owes a move right now."
   :narrator/skip-role    (fn [role] (str "Skip " role))
   :narrator/advance      "Move on without waiting"
   :narrator/advance-free "Move on"
   :narrator/really       (fn [n] (str "Really? " n " still to act"))
   :narrator/keep-waiting "Keep waiting"
   :narrator/night    "Tonight"
   :narrator/wolf-votes "Wolf votes"
   :narrator/dying    "Dying tonight"
   :narrator/protected "Protected"
   :narrator/lovers   "Lovers"
   :narrator/centre   "Centre (Dieb)"
   :narrator/nobody   "—"
   :narrator/new      "New narrator"
   :setup/title       "Dealing roles"
   :setup/hint        "Everyone has their card. You can still change any role — then begin the first night. None of it appears in the Chronicle."
   :setup/waiting     "The narrator is dealing the roles …"
   :setup/your-card   "Your card may still change until the first night."

   ;; -------------------------------------------------------------- game ---
   :game/dead          "dead"
   :game/absent        "away"
   :game/left          "left"

   ;; ---------------------------------------------------------- absence ---
   :absence/title      (fn [n] (str "The narrator (" n ") is away"))
   :absence/claim-hint "Anyone at the table can become narrator."
   :absence/clock-hint "The clock has taken over: every phase ends on its time limit until the narrator is back."
   :game/your-role     "Your role"
   :bar/menu           "Menu"
   :bar/room           "Room"
   :tab/now            "Now"
   :tab/table          "Players"
   :game/in-love       "You are in love with"
   :game/heiltrank     "Healing potion"
   :game/gifttrank     "Poison"
   :game/knows         "Known"
   :game/log           "Chronicle"
   :game/chat          "Talk"
   :game/chat-hint     "Write a message…"
   :game/chat-closed   "You cannot write right now"
   :game/waiting       "Waiting for the others…"
   :game/wolves-channel "Only the werewolves can hear you"
   :game/dead-channel   "Only the dead can hear you"
   :game/narrator-channel "Everyone hears you — you are the narrator"
   :chat/tag-wolves "Wolves"
   :chat/tag-dead "Dead"
   :chat/tag-narrator "Narrator"
   :game/paused         "Clock paused"
   :game/new-round      "Back to the start"
   :game/play-again-wait "The narrator can start another game with all of you."
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
   :turn/hint    "Choose your move"
   :turn/urgent  "Everyone is waiting for you"
   :turn/calling (fn [role] (str "The Spielleiter calls: " role))
   :turn/narrating "You are running the game"

   ;; ------------------------------------------------------------ stage ---
   ;;
   ;; One sentence per phase saying what is happening, for first-time players.
   ;; Kept free of "the village" and "werewolves" for the same reason as in
   ;; German: those words belong to outcomes.
   :stage/round       (fn [n] (str "Round " n))
   :stage/called-label "Tonight calls"
   :stage/call-label "Call, in order"
   :turn/asleep "You are asleep"
   :turn/asleep-next (fn [role] (str "Next up: " role))
   :board/show (fn [n] (str "Show the board (" n ")"))
   :stage/latest      "Latest"
   :stage/progress    "Round progress"
   :stage/interrupted "interrupted"
   :sound/turn-off    "Turn off the nightfall and daybreak chime"
   :sound/turn-on     "Turn on the nightfall and daybreak chime"
   :explain/setup       "The cards are dealt. Look at your role — the night begins shortly."
   :explain/setup-narrator "The cards are dealt. Check the deal, then begin the night."
   :explain/night       "Everyone is asleep. Whoever is called wakes up and acts without giving themselves away."
   :explain/day-reveal  "Dawn breaks. You are about to learn what the night has done."
   :explain/election    "Elect a mayor. Their vote counts twice in every ballot."
   :explain/discussion  "Talk it over: who do you not trust? Once you have said enough, tap “I'm done”."
   :explain/trial       (fn [_] "Talk it over and vote on who is condemned. You can change your vote until everyone has voted.")
   :explain/first-discussion "Nobody votes today. Talk about the night and about whom you trust. Once you have said enough, tap “I'm done” – when everyone is, night falls."
   :explain/day-resolve "The verdict falls."
   :explain/reaction    "Someone who just died has one last move. Everyone waits for them."
   :explain/reaction-jaeger "The Jäger has died and takes one last shot. Everyone waits for it."
   :explain/reaction-mayor "The mayor has died and names who takes over the office."
   :step/night       "Night"
   :step/day-reveal  "Dawn"
   :step/election    "Election"
   :step/discussion  "Discussion"
   :step/day-resolve "Verdict"

   ;; ------------------------------------------------------------ voice ---
   ;;
   ;; Joining is an explicit tap: browsers refuse a microphone without a user
   ;; gesture, and opening one because somebody loaded a page is the wrong
   ;; default anyway. `:voice/everyone-hears` is deliberately plain about the
   ;; rule — the server enforces no phase muting, so the UI must not imply it.
   :voice/in-call        "in call"
   :voice/in-call-list   "In the call"
   :voice/nobody-in-call "Nobody is in the call yet."
   :voice/title          "Voice chat"
   :voice/join           "🎧 Join voice chat"
   :voice/joining        "Connecting…"
   :voice/leave          "Leave"
   :voice/mute           "Mute"
   :voice/unmute         "Unmute"
   :voice/everyone-hears "Everyone at the table hears you — at night too, and when dead."

   ;; --------------------------------------------------------- targeting ---
   :target/pick-one   "Choose a target"
   :target/pick-two   "Choose two targets"
   :target/chosen-one "First one chosen — now the second"
   :target/confirm (fn [action who] (str action ": " who "?"))
   :target/repick "Choose again"
   :target/yes "Yes, do it"
   :target/cancel     "Cancel"
   :target/hint       "Click a player below"
   :target/direct (fn [action who] (str action ": " who))
   :target/tap "Or just tap a player"

   ;; ------------------------------------------------------------ badges ---
   :dawn/title "Day breaks"
   :dawn/ok "Continue"
   :record/title (fn [n] (str "This table, after " n " games"))
   :record/won "won"
   :hexe/victim (fn [who] (str "🐺 The werewolves chose " who "."))
   :hexe/victim-you "🐺 The werewolves chose you."
   :hexe/no-victim "🐺 Nobody is dying of the werewolves tonight."
   :hexe/healed (fn [who] (str "✓ You saved " who "."))
   :hexe/healed-you "✓ You saved yourself."
   :hexe/done "Done"
   :ballot/title     "The vote"
   :ballot/needed    (fn [n] (str "Condemned at " n " votes"))
   :ballot/none      "Nobody has a vote yet."
   :ballot/mayor     "The mayor's vote counts twice"
   :ballot/abstained (fn [n] (str n (if (= 1 n) " abstention" " abstentions")))
   :badge/votes       "votes"
   :badge/vote        "vote"
   :badge/your-vote   "your vote"
   :badge/ready       "done"
   :badge/decided     "has chosen"
   :badge/mayor       "Mayor"
   :badge/host        "Narrator"
   :badge/lover       "in love"
   :badge/waiting-for "Still waiting on"

   ;; --------------------------------------------------------------- log ---
   :log/show-all      "Show everything"
   :log/show-less     "Only what matters"
   :log/open          (fn [n] (str "Show Chronicle (" n ")"))
   :log/close         "Hide Chronicle"
   :log/hidden        "more entries"

   :log/joined        (fn [n] (str n " joined"))
   :log/left          (fn [n] (str n " left the game"))
   :log/disconnected  (fn [n] (str n " is away"))
   :log/reconnected   (fn [n] (str n " is back"))
   :log/host-changed  (fn [n] (str n " is now the narrator"))
   :log/host-claimed  (fn [n] (str n " becomes the narrator"))
   :log/host-absent   (fn [n] (str "The narrator (" n ") is away"))
   :log/host-returned (fn [n] (str n " is back and running the game again"))
   :log/game-started  "The first night falls"
   :log/game-started-setup "The cards are dealt — the narrator is preparing the first night"
   :log/narrated-on   "The narrator guides the game and does not play"
   :log/narrated-off  "The narrator plays along"
   :log/timer-on      "The clock is running — every phase has a time limit"
   :log/timer-off     "The clock is off — the narrator sets the pace"
   :log/timer-on-absent "The clock takes over — without a narrator every phase ends on its time limit"
   :log/room-locked   "The room is now password protected"
   :log/room-unlocked "The room no longer needs a password"
   :log/deck-set      (fn [d] (str "The narrator chooses the roles: " d))
   :log/deck-auto     "The roles follow the number of players again"
   :log/amor-linked   "Amor has chosen two lovers"
   :log/night-began   "Night falls"
   :log/night-quiet   "Nobody died during the night"
   :log/night-ended   "Dawn breaks"
   :log/day-began     "The village gathers"
   :log/ready         (fn [n] (str n " is done"))
   :log/nominate-passed (fn [n] (str n " accuses nobody"))
   :log/no-accusation "The village accuses nobody — nobody dies today"
   :log/no-trial      "Nobody is accused on the first day"
   :log/jaeger-held-fire (fn [n] (str n " lowers the rifle"))
   :log/vote-cast     (fn [v t] (str v " → " t))
   :log/vote-abstained (fn [v] (str v " abstains"))

   :log/died-werwolf  (fn [n] (str n " was torn apart by the werewolves"))
   :log/died-gift     (fn [n] (str n " was poisoned"))
   :log/died-liebe    (fn [n] (str n " follows their love into death"))
   :log/died-lynch    (fn [n] (str n " was condemned by the village"))
   :log/died-jaeger   (fn [n] (str n " was shot by the Jäger"))
   :log/died-weisser  (fn [n] (str n " was torn apart by the Weißer Werwolf"))
   :log/died-gm       (fn [n] (str n " was removed by the narrator"))
   :log/died-left (fn [n] (str n " left the game and is out"))
   :log/died          (fn [n] (str n " died"))

   :log/revived       (fn [n] (str n " lives again"))
   :log/role-revealed (fn [n r] (str n " was the " r))
   :log/jaeger-shot   (fn [n t] (str n " shoots " t))
   :log/lynched       (fn [n] (str "The village condemns " n))
   :log/tie           "The village cannot agree — nobody dies"
   :vote/yours        (fn [n] (str "Your vote: " n " — you can still change it"))
   :vote/yours-abstained "You abstained — you can still change your vote"
   :log/no-majority   "No majority — the village condemns nobody"
   ;; The election. Public, because the entire point of the office is that the
   ;; table knows whose vote counts twice.
   :log/mayor-elected   (fn [n] (str n " was elected mayor"))
   :log/mayor-none      "The village could not agree on a mayor"
   :log/mayor-appointed (fn [n] (str n " takes over as mayor"))
   :log/mayor-vacated   "The office of mayor is left empty"
   :log/nominated     (fn [n] (str n " stands accused"))
   :log/timed-out     (fn [p] (str "Time ran out for " p))
   ;; What the game master actually did. Only for the overrides that would
   ;; otherwise leave no trace: killing and reviving already write their own
   ;; line, and saying the same thing twice reads as a bug.
   ;;
   ;; The new role is deliberately NOT here. The whole table reads the
   ;; chronicle — that an override happened concerns everyone, what the card now
   ;; says concerns one player. See `handle :gm/reassign-role`.
   :log/gm-forced     "The narrator intervenes"
   :log/gm-skip-role  (fn [r] (str "The narrator skips the " r))
   :log/gm-set-phase  (fn [p] (str "The narrator sets the phase to " p))
   :log/gm-reassign-role (fn [n] (str n " was given a new role by the narrator"))
   :log/gm-advance-phase (fn [p] (str "The narrator ends: " p))
   :log/clock-paused  "The narrator pauses the clock"
   :log/clock-resumed "The clock is running again"
   :log/win-village   "The village wins"
   :log/win-werewolf  "The werewolves win"
   :log/win-lovers    "The lovers win"
   :log/win-weisser   "The Weißer Werwolf wins alone"
   :log/game-abandoned "Nobody came back — the game is closed"
   :log/game-reset     "Another round — the cards are shuffled again"

   :log/group-ready        (fn [names] (str names " are done"))
   :log/group-passed       (fn [names] (str names " accuse nobody"))
   :log/group-voted        (fn [names] (str names " have voted"))
   :log/group-joined       (fn [names] (str names " joined"))
   :log/group-disconnected (fn [names] (str names " are away"))
   :log/group-reconnected  (fn [names] (str names " are back"))

   ;; ------------------------------------------------------------ recap ---
   ;; The recap at the end: what really happened. Whole sentences as in the
   ;; chronicle; where the chronicle already has one (a death, a shot, a
   ;; verdict) it is reused — see werwolf.i18n.recap.
   :recap/title        "Recap"
   :recap/hint         "Everything is out now: who was who, and what really happened each night."
   :recap/cast         "Who was who"
   :recap/night        (fn [n] (str "Night " n))
   :recap/day          (fn [n] (str "Day " n))
   :recap/survived     "survived"
   :recap/cause-werwolf "torn apart by the werewolves"
   :recap/cause-gift   "poisoned"
   :recap/cause-liebe  "died of love"
   :recap/cause-lynch  "condemned by the village"
   :recap/cause-jaeger "shot by the Jäger"
   :recap/cause-weisser "torn apart by the Weißer Werwolf"
   :recap/cause-gm     "removed by the narrator"
   :recap/cause-left "left the game"
   :recap/cause-other  "died"
   :recap/dealt        (fn [r] (str "dealt " r))
   :recap/lover        "in love"
   :recap/mayor        "Mayor"
   :recap/dieb-took    (fn [n r] (str n " takes the " r " card"))
   :recap/dieb-kept    (fn [n] (str n " stays the Dieb"))
   :recap/amor-linked  (fn [a b] (str "Amor makes " a " and " b " fall in love"))
   :recap/seherin      (fn [n t r] (str n " looks: " t " is the " r))
   :recap/beschuetzer  (fn [n t] (str n " protects " t))
   :recap/wolves-chose (fn [t] (str "The werewolves choose " t))
   :recap/wolves-guarded (fn [t] (str "The werewolves choose " t " — but the Beschützer stands guard"))
   :recap/wolves-none  "The werewolves agree on no victim"
   :recap/weisser-killed (fn [n t] (str n " tears " t " from his own pack"))
   :recap/weisser-passed (fn [n] (str n " spares the pack"))
   :recap/hexe-healed  (fn [n t] (str n " saves " t " with the healing potion"))
   :recap/hexe-poisoned (fn [n t] (str n " poisons " t))
   :recap/nominated    (fn [by t] (str by " accuses " t))
   :recap/gm-reassigned (fn [n from to] (str "The narrator swaps " n "'s card: " from " → " to))
   :recap/votes        (fn [v] (str "Votes: " v))
   :recap/abandoned    "Nobody came back — the game was ended without a winner"

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
   :error/name-taken       "Somebody in this game already has that name — pick another"
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
   :error/not-host         "Only the narrator can do that"
   :error/password-required "This room has a password — please enter it"
   :error/wrong-password    "Wrong password"
   :error/too-many-attempts "Too many attempts — please wait a minute"
   :error/already-dead     "That player is already dead"
   :error/already-alive    "That player is still alive"
   :error/already-paused   "The clock is already paused"
   :error/not-paused       "The clock is already running"
   :error/no-deadline      "No clock is running"
   :error/not-night        "That only works at night"
   :error/cannot-advance   "This phase cannot be advanced"
   :error/no-game-in-progress "The game has to be running for that"
   :error/already-that-role "That player already has this role"
   :error/invalid-choice   "That choice is not allowed"
   :error/no-such-player   "There is no such player"
   :error/unknown-role     "There is no such role"
   :error/invalid-deck     "That is not a choice of roles"
   :error/invalid-count    "That is not a number of cards"
   :error/too-many-of-role "There are not that many cards of this role"
   :error/deck-too-large   "No table holds that many cards"
   :error/deck-unplayable  "The game cannot start with these roles"
   :error/deal-unplayable  "The night cannot begin with this deal"
   :error/distinct-targets-required "Please choose two different players"
   :error/unsupported-locale "No such language"
   :error/unknown-command  "Unknown command"
   :error/internal-error   "Something went wrong"})

(def missing-keys
  "Keys German has and English does not. Zero by contract — the test in
   i18n_test.cljc fails on anything here, and this is exposed so the failure
   message can say which ones rather than 'the sets differ'."
  (sort (remove (set (keys dict)) (keys de/dict))))
