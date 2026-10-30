(ns werwolf.engine.commands
  "Client intent -> validated events.

   `handle` NEVER trusts its input. Every command is checked against
   `phases/legal-actions` and `phases/legal-targets` before any event is emitted,
   which means a hand-crafted WebSocket frame cannot make an illegal move. The
   client's own validation is a convenience, not a control.

   Chat is deliberately absent: it is not game state, and its routing (werewolves
   at night, dead players among themselves) is a transport concern handled by the
   room layer. The engine only reports whether chatting is currently allowed."
  (:require [clojure.string :as str]
            [werwolf.engine.bot :as bot]
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]
            [werwolf.engine.rng :as rng]))

(defn- err [code detail] {:error code :detail detail})

(declare error?)

(defn- allowed? [state pid action]
  (contains? (phases/legal-actions state pid) action))

(defn- valid-target? [state pid action target]
  (contains? (set (phases/legal-targets state pid action)) target))

;; The day progression map lives in phases.cljc so legal-actions can use it.
(def next-phase phases/next-phase)

;; ------------------------------------------------------------- dealing ---

(defn deal
  "Assign roles deterministically from the game's seed.
   Returns {:assignments {pid role} :centre [..] :seed advanced-seed}.

   Only the players' cards are shuffled. The centre is fixed by composition rather
   than being whatever fell out of the shuffle — see `roles/centre-extras` for why
   a random middle produces tables nobody asked for.

   The deck is the Spielleiter's when he chose one, filled up with villagers to
   the table; otherwise the automatic one. Both reach the shuffle the same way,
   so a custom game is exactly as reproducible from its seed as any other."
  [state]
  ;; Participants only: the Spielleiter is dealt nothing, and the deck is
  ;; composed for the table that actually plays.
  (let [ids  (e/participant-ids state)
        {:keys [deck centre]} (roles/compose-deck (count ids) (:deck state))
        [seed' shuffled] (rng/shuffle (:seed state) deck)]
    {:assignments (zipmap ids shuffled)
     :centre      centre
     :seed        seed'}))

;; -------------------------------------------------------------- handle ---

(defmulti handle
  "Validate a command and return a vector of events, or {:error ...}.
   Never mutates state."
  (fn [_state cmd] (:type cmd)))

(defmethod handle :default
  [_ cmd]
  (err :unknown-command {:type (:type cmd)}))

;; ---- lobby ----

(defmethod handle :join
  [state {:keys [player name locale]}]
  (cond
    (not= :lobby (:phase state))          (err :game-in-progress {})
    (contains? (:players state) player)   (err :already-joined {:player player})
    ;; From state, not a literal: the room keeps the limit it was opened with.
    ;; Counts the people who will PLAY — a Spielleiter does not take a card, so
    ;; he does not take a seat at the table either.
    (>= (count (e/participant-ids state))
        (:max-players state 18))          (err :room-full {})
    (str/blank? (str name))               (err :name-required {})
    ;; Two "Anna"s at one table make every vote, every Chronik line and every
    ;; card ambiguous. Case and spaces do not make a name different.
    (some #(= (str/lower-case (str/trim (str name))) (str/lower-case (str (:name %))))
          (vals (:players state)))
    (err :name-taken {})
    :else [{:type :player/joined
            :player player
            :name (str/trim (str name))
            :locale (or locale :de)}]))

(defmethod handle :leave
  ;; A host who walks out of a game under way is absent at once, with none of
  ;; the grace a dropped connection gets: a deliberate leave is not a flaky
  ;; phone, and there is nothing to wait for. Same consequences as the clock
  ;; noticing — see `phases/host-absent-events`. In the lobby the seat simply
  ;; passes on (room/succession), and at :game-over nobody needs him.
  ;;
  ;; A PLAYER who walks out of a game under way leaves it: dead, card turned
  ;; over, cause :left. Kept alive, he was waited on for moves he would never
  ;; make, counted towards every majority, and once condemned in absentia; a
  ;; wolf who walked out would have kept the game open for ever. He gets no
  ;; last move — he is not there to make it — but a lover still follows him,
  ;; as after any death. Not the Spielleiter, who holds no card and no life.
  [state {:keys [player]}]
  (if (contains? (:players state) player)
    (-> [{:type :player/left :player player}]
        (into (when (and (not (#{:lobby :game-over} (:phase state)))
                         (get-in state [:players player :alive?])
                         (not= player (e/narrator state)))
                [{:type :player/died :player player :cause :left}]))
        (into (when (and (= player (:host state))
                         (not (#{:lobby :game-over} (:phase state))))
                (phases/host-absent-events state))))
    (err :not-in-room {:player player})))

(defmethod handle :set-locale
  [state {:keys [player locale]}]
  (cond
    (not (contains? (:players state) player)) (err :not-in-room {})
    (not (#{:de :en} locale))                 (err :unsupported-locale {:locale locale})
    ;; The language the player already has is nothing to log: the security
    ;; review's spam put 19,675 of these in one room's log in a minute, and
    ;; nothing ever deletes a log line.
    (= locale (get-in state [:players player :locale])) []
    :else [{:type :player/locale-set :player player :locale locale}]))

(defmethod handle :start-game
  [state {:keys [player]}]
  (cond
    ;; Named before the generic refusal, so a host whose click raced a player
    ;; leaving is told what is wrong with his deck rather than "not allowed".
    (and (= player (:host state)) (= :lobby (:phase state))
         (phases/deck-problem state))
    (err :deck-unplayable {:problem (phases/deck-problem state)})

    (not (allowed? state player :start-game))
    (err :not-allowed {:action :start-game})

    :else
    (let [{:keys [assignments centre seed]} (deal state)]
      [{:type :game/started :assignments assignments :centre centre :seed seed}
       ;; With a Spielleiter the cards are dealt face down to the table and
       ;; face up to him, and the night falls when he says so — he may want to
       ;; rearrange the deal first. Without one there is nobody to wait for.
       (if (e/narrator state)
         {:type :phase/changed :phase :setup :deadline nil}
         {:type :night/began :night 1})])))

(defmethod handle :play-again
  ;; The host's one-tap rematch: the same table, dealt again. Through the
  ;; lobby's own start rule rather than around it, so a table that no longer
  ;; makes a game — the deck no longer fits, somebody kicked out of it — ends
  ;; up in the lobby, where the host can see why and fix it.
  [state {:keys [player]}]
  (if-not (allowed? state player :play-again)
    (err :not-allowed {:action :play-again})
    (let [reset [{:type :game/reset}]
          start (handle (e/apply-all state reset) {:type :start-game :player player})]
      (if (error? start) reset (into reset start)))))

(defmethod handle :begin-night
  ;; The end of :setup. Its own command rather than :advance-phase, because it
  ;; is not "the next phase" in the sense the day uses — it is the moment the
  ;; deal becomes final, and the button saying so should not look like the one
  ;; that hurries a discussion along.
  [state {:keys [player]}]
  (cond
    ;; Named before the generic refusal, like the lobby's deck: the
    ;; Spielleiter is told what is wrong with his deal, not "not allowed".
    (and (= player (:host state)) (phases/deal-problem state))
    (err :deal-unplayable {:problem (phases/deal-problem state)})

    (not (allowed? state player :begin-night))
    (err :not-allowed {:action :begin-night})

    :else [{:type :night/began :night 1}]))

;; ---- night actions ----

(defmethod handle :amor-link
  [state {:keys [player a b]}]
  (cond
    (not (allowed? state player :amor-link)) (err :not-allowed {:action :amor-link})
    (= a b)                                  (err :distinct-targets-required {})
    (not (and (valid-target? state player :amor-link a)
              (valid-target? state player :amor-link b)))
    (err :invalid-target {:a a :b b})
    :else [{:type :amor/linked :a a :b b}
           {:type :night/role-completed :role :amor}]))

(defmethod handle :seherin-inspect
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :seherin-inspect)) (err :not-allowed {:action :seherin-inspect})
    (not (valid-target? state player :seherin-inspect target)) (err :invalid-target {:target target})
    :else [{:type :seherin/inspected :player player :target target}
           {:type :night/role-completed :role :seherin}]))

(defmethod handle :werewolf-vote
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :werewolf-vote)) (err :not-allowed {:action :werewolf-vote})
    (not (valid-target? state player :werewolf-vote target)) (err :invalid-target {:target target})
    ;; Resolution happens in phases/auto-events once every wolf has voted.
    :else [{:type :werewolf/voted :player player :target target}]))

(defmethod handle :elect
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :elect))                 (err :not-allowed {:action :elect})
    (not (valid-target? state player :elect target))     (err :invalid-target {:target target})
    :else [{:type :election/cast :voter player :target target}]))

(defmethod handle :elect-abstain
  [state {:keys [player]}]
  (if-not (allowed? state player :elect-abstain)
    (err :not-allowed {:action :elect-abstain})
    [{:type :election/cast :voter player :target nil}]))

;; The outgoing Bürgermeister names his successor, while already dead — the same
;; shape as the Jäger's shot, and drained by the same reaction stack.
(defmethod handle :mayor-appoint
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :mayor-appoint))
    (err :not-allowed {:action :mayor-appoint})
    (not (valid-target? state player :mayor-appoint target))
    (err :invalid-target {:target target})
    :else [{:type :reaction/popped}
           {:type :mayor/appointed :player target :by player}]))

(defmethod handle :mayor-decline
  [state {:keys [player]}]
  (if-not (allowed? state player :mayor-decline)
    (err :not-allowed {:action :mayor-decline})
    [{:type :reaction/popped}
     {:type :mayor/vacated :by player}]))

(defmethod handle :beschuetzer-protect
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :beschuetzer-protect))
    (err :not-allowed {:action :beschuetzer-protect})
    (not (valid-target? state player :beschuetzer-protect target))
    (err :invalid-target {:target target})
    ;; The completion event is not optional bookkeeping: without it the cursor
    ;; never leaves this role and the night livelocks. The simulation caught it
    ;; on every table large enough to deal a Beschützer.
    :else [{:type :beschuetzer/protected :player player :target target}
           {:type :night/role-completed :role :beschuetzer}]))

(defmethod handle :dieb-take
  [state {:keys [player role]}]
  (cond
    (not (allowed? state player :dieb-take)) (err :not-allowed {:action :dieb-take})
    ;; Validated against `legal-choices`, not against the centre directly: the
    ;; engine already has one place that decides what a choice may be, and a
    ;; second copy here is a second thing to keep in step.
    (not (contains? (set (phases/legal-choices state :dieb-take)) role))
    (err :invalid-choice {:role role})
    :else [{:type :dieb/took :player player :role role}
           {:type :night/role-completed :role :dieb}]))

(defmethod handle :dieb-keep
  [state {:keys [player]}]
  (if-not (allowed? state player :dieb-keep)
    (err :not-allowed {:action :dieb-keep})
    [{:type :dieb/kept :player player}
     {:type :night/role-completed :role :dieb}]))

(defmethod handle :weisser-kill
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :weisser-kill)) (err :not-allowed {:action :weisser-kill})
    (not (valid-target? state player :weisser-kill target)) (err :invalid-target {:target target})
    :else [{:type :weisser/killed :player player :target target}
           {:type :night/role-completed :role :weisser-werwolf}]))

(defmethod handle :weisser-pass
  [state {:keys [player]}]
  (if-not (allowed? state player :weisser-pass)
    (err :not-allowed {:action :weisser-pass})
    [{:type :weisser/passed :player player}
     {:type :night/role-completed :role :weisser-werwolf}]))

(defmethod handle :hexe-heal
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :hexe-heal)) (err :not-allowed {:action :hexe-heal})
    (not (valid-target? state player :hexe-heal target)) (err :invalid-target {:target target})
    :else [{:type :hexe/healed :player player :target target}]))

(defmethod handle :hexe-poison
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :hexe-poison)) (err :not-allowed {:action :hexe-poison})
    (not (valid-target? state player :hexe-poison target)) (err :invalid-target {:target target})
    :else [{:type :hexe/poisoned :player player :target target}]))

(defmethod handle :hexe-pass
  [state {:keys [player]}]
  (if-not (allowed? state player :hexe-pass)
    (err :not-allowed {:action :hexe-pass})
    [{:type :hexe/passed :player player}
     {:type :night/role-completed :role :hexe}]))

;; ---- reactions ----

(defmethod handle :jaeger-shoot
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :jaeger-shoot)) (err :not-allowed {:action :jaeger-shoot})
    (not (valid-target? state player :jaeger-shoot target)) (err :invalid-target {:target target})
    :else [{:type :reaction/popped}
           {:type :jaeger/shot :player player :target target}]))

(defmethod handle :jaeger-pass
  [state {:keys [player]}]
  (if-not (allowed? state player :jaeger-pass)
    (err :not-allowed {:action :jaeger-pass})
    [{:type :reaction/popped}
     {:type :jaeger/held-fire :player player}]))

;; ---- day ----

(defmethod handle :ready
  [state {:keys [player]}]
  (if-not (allowed? state player :ready)
    (err :not-allowed {:action :ready})
    [{:type :player/ready :player player}]))

(defmethod handle :vote
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :vote)) (err :not-allowed {:action :vote})
    ;; A nil target reaching here is an abstention and is allowed, but
    ;; :vote-abstain is the honest way to say it — see that handler.
    (and (some? target)
         (not (valid-target? state player :vote target))) (err :invalid-target {:target target})
    ;; The ballot stays open to change; the same vote again changes nothing
    ;; and is not worth a line in the Chronik.
    (and (contains? (:votes state) player) (= target (get-in state [:votes player]))) []
    :else [{:type :vote/cast :voter player :target target}]))

(defmethod handle :vote-abstain
  ;; A separate command rather than "send :vote with no target", so that
  ;; abstaining is something `legal-actions` can advertise. The client renders
  ;; the affordances it is given and invents none — an abstain button the UI
  ;; synthesised itself would be the client deciding a rule.
  [state {:keys [player]}]
  (cond
    (not (allowed? state player :vote-abstain)) (err :not-allowed {:action :vote-abstain})
    (and (contains? (:votes state) player) (nil? (get-in state [:votes player]))) []
    :else [{:type :vote/cast :voter player :target nil}]))

(defmethod handle :advance-phase
  ;; What it does is `phases/advance-events`' business: the next day phase, or,
  ;; in a room without a clock, whatever the clock would have done. The second
  ;; kind is logged as an override, because it ends a turn somebody else was
  ;; owed — a Jäger's shot, a wolf's vote — and the table must be able to see
  ;; that it was the Spielleiter and not the player who closed it. Letting the
  ;; night fall on a verdict closes nobody's turn, so it is not one.
  [state {:keys [player]}]
  (if-not (allowed? state player :advance-phase)
    (err :not-allowed {:action :advance-phase})
    (if-let [evs (phases/advance-events state)]
      (if (or (next-phase (:phase state)) (phases/verdict-held? state))
        evs
        (into [{:type :gm/forced :actor player :action :advance-phase
                :detail {:phase (:phase state)}}]
              evs))
      (err :cannot-advance {:phase (:phase state)}))))

;; ---- GM overrides -------------------------------------------------------
;; Every override is logged with its actor, so it is auditable in the public
;; log rather than something the host can do quietly.

(defn- host? [state pid] (= pid (:host state)))

(defn- before-play?
  "No game to override yet. These handlers used to check only that the caller
   was the host, which was harmless while the lobby was the only such phase —
   nobody had a role to lose. :setup has dealt cards and no night, and a forced
   death or a jump to the vote there would start a game the phase machine never
   began."
  [state]
  (contains? #{:lobby :setup} (:phase state)))

(defmethod handle :gm/force-kill
  [state {:keys [player target]}]
  (cond
    (not (host? state player)) (err :not-host {})
    (before-play? state)       (err :no-game-in-progress {})
    (not (get-in state [:players target :alive?])) (err :already-dead {:target target})
    ;; The narrator is "alive" only in the sense that he is in the room. Killing
    ;; him would put a Spielleiter in the dead players' channel and on nobody's
    ;; ballot — not a game state anything downstream expects.
    (= target (e/narrator state))  (err :invalid-target {:target target})
    :else [{:type :gm/forced :actor player :action :force-kill :detail {:target target}}
           {:type :player/died :player target :cause :gm}]))

(defmethod handle :gm/revive
  [state {:keys [player target]}]
  (cond
    (not (host? state player)) (err :not-host {})
    (before-play? state)       (err :no-game-in-progress {})
    (get-in state [:players target :alive?]) (err :already-alive {:target target})
    :else [{:type :gm/forced :actor player :action :revive :detail {:target target}}
           {:type :player/revived :player target}]))

(defmethod handle :gm/skip-role
  [state {:keys [player role]}]
  (cond
    (not (host? state player)) (err :not-host {})
    (not= :night (:phase state)) (err :not-night {})
    :else [{:type :gm/forced :actor player :action :skip-role :detail {:role role}}
           {:type :night/role-completed :role (or role (:night-cursor state))}]))

(defmethod handle :gm/reassign-role
  ;; The deal was wrong, or somebody has to be written out of one role and into
  ;; another. The only override that changes who a player IS.
  ;;
  ;; The :gm/forced entry deliberately carries the target and NOT the role,
  ;; because that entry is published to the public log. Everyone learns that the
  ;; GM rewrote P3's card, which is the auditable fact; nobody learns what it now
  ;; says, which is P3's alone. The role travels in :player/role-changed, which
  ;; is never published.
  ;;
  ;; :max-count is NOT enforced. A registry limit says how a deal is composed,
  ;; and this command exists precisely to override a composition — refusing a
  ;; second Seherin here would block the correction the GM reached for. Two
  ;; Seherinnen is a table's problem, and it is in the log.
  ;;
  ;; During :setup it is something else: the Spielleiter arranging the deal
  ;; before anybody has played a card. Then there is no public line at all —
  ;; nothing has happened yet that the table could audit, and a Chronik naming
  ;; every seat he touched would be a map of where he thought the deal needed
  ;; fixing. The spare cards follow the Dieb in or out of the deal; see
  ;; `roles/centre-for`.
  [state {:keys [player target role]}]
  (cond
    (not (host? state player))                (err :not-host {})
    (#{:lobby :game-over} (:phase state))     (err :no-game-in-progress {})
    (not (get-in state [:players target]))    (err :no-such-player {:target target})
    (= target (e/narrator state))             (err :invalid-target {:target target})
    (not (contains? roles/all-ids role))      (err :unknown-role {:role role})
    (= role (get-in state [:players target :role]))
    (err :already-that-role {:target target})

    (= :setup (:phase state))
    (let [dealt (vals (assoc (into {} (map (fn [id] [id (get-in state [:players id :role])]))
                                   (e/participant-ids state))
                             target role))]
      [{:type :player/role-changed :player target :role role
        :centre (roles/centre-for dealt)}])

    :else [{:type :gm/forced :actor player :action :reassign-role
            :detail {:target target}}
           {:type :player/role-changed :player target :role role}]))

;; ---- the table itself: who narrates, the clock, whose seat this is ----

(defmethod handle :gm/toggle-narrated
  [state {:keys [player]}]
  (if-not (allowed? state player :gm/toggle-narrated)
    (err :not-allowed {:action :gm/toggle-narrated})
    [{:type :game/narrated-set :narrated? (not (:narrated? state))}]))

(defmethod handle :gm/toggle-timer
  ;; In the lobby and during the game alike. A table that finds the clock
  ;; rushing it, or a Spielleiter who wants it back to keep a slow table
  ;; moving, should not have to start over to change its mind.
  [state {:keys [player]}]
  (if-not (allowed? state player :gm/toggle-timer)
    (err :not-allowed {:action :gm/toggle-timer})
    [{:type :clock/timer-set :on? (not (phases/timed? state))}]))

(defmethod handle :gm/set-deck
  ;; The Spielleiter chooses the roles: special role -> count, the whole deck
  ;; every time. Lobby only, like every other switch about how the table plays.
  ;;
  ;; Validated against the registry and nothing else. Whether the deck FITS is
  ;; deliberately not a reason to refuse it: the host builds a deck one stepper
  ;; at a time, and every intermediate state — no wolf yet, one role too many
  ;; for the five people seated so far — is a deck on its way somewhere. Refusing
  ;; those would make him add roles in exactly the order the rule wants. The fit
  ;; is checked where it matters, at the start, and published in the meantime.
  ;;
  ;; `:max-count` IS enforced here, unlike in `:gm/reassign-role`. That command
  ;; corrects a game in progress, and refusing the correction would block the
  ;; one thing the GM reached for; this one composes a deck, which is precisely
  ;; what the registry's limits describe.
  [state {:keys [player deck]}]
  (cond
    (not (allowed? state player :gm/set-deck)) (err :not-allowed {:action :gm/set-deck})
    (not (map? deck))                          (err :invalid-deck {})
    :else
    (if-let [[code detail] (roles/deck-errors deck (:max-players state 18))]
      (err code detail)
      (let [deck (roles/normalise-deck deck)]
        ;; Re-sending the deck the room already has is a no-op, not a second
        ;; line in the Chronik: a double tap should not read as two decisions.
        (if (= deck (:deck state))
          []
          [{:type :deck/set :deck deck}])))))

(defmethod handle :gm/auto-deck
  [state {:keys [player]}]
  (if-not (allowed? state player :gm/auto-deck)
    (err :not-allowed {:action :gm/auto-deck})
    [{:type :deck/auto}]))

(defmethod handle :gm/transfer-host
  ;; Hand the host's seat — and in a narrated room, the Spielleitung with it —
  ;; to somebody else. Lobby only: see `phases/host-actions`. The same event
  ;; succession uses, so the Chronik and every reader of `:host` already know
  ;; what it means.
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :gm/transfer-host))
    (err :not-allowed {:action :gm/transfer-host})
    (not (valid-target? state player :gm/transfer-host target))
    (err :invalid-target {:target target})
    :else [{:type :host/changed :host target}]))

(defmethod handle :gm/kick
  ;; The host removes somebody from the lobby. Lobby only and never himself —
  ;; see `phases/host-actions` and `legal-targets`. Its own event rather than a
  ;; `:player/left` on somebody else's behalf: the Chronik has to say that the
  ;; host did this, and the room has to tell the removed page something other
  ;; than "you left", which it did not.
  ;;
  ;; No ban. It is a friend group, the room's password is the lock on the door,
  ;; and a typo or a ghost seat is the usual reason — so the kicked page may
  ;; join again like anybody, and gets a new seat number when it does.
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :gm/kick))
    (err :not-allowed {:action :gm/kick})
    (not (valid-target? state player :gm/kick target))
    (err :invalid-target {:target target})
    :else [{:type :player/kicked :player target}]))

(defmethod handle :gm/add-bot
  ;; The host seats a bot — a seat the server plays, so one person can test a
  ;; whole game alone. Lobby only, and only while there is room; see
  ;; `phases/host-actions`. The seat number is the engine's own rule
  ;; (events/next-seat), never a seat still taken; the name comes from a fixed
  ;; list, skipping whatever is already at the table the same way `:join`
  ;; would refuse it, so a bot can never trip the duplicate-name rule.
  [state {:keys [player]}]
  (if-not (allowed? state player :gm/add-bot)
    (err :not-allowed {:action :gm/add-bot})
    (let [taken (into #{} (map #(str/lower-case (str/trim (str (:name %))))) (vals (:players state)))
          name  (bot/next-name taken)]
      (if (nil? name)
        (err :room-full {})
        [{:type :player/joined
          :player (e/next-seat state)
          :name name
          :locale (get-in state [:players player :locale] :de)
          :bot? true}]))))

(defmethod handle :claim-host
  ;; Taking the seat of a host who has gone missing. Who may, and when, is
  ;; `phases/claim-actions`; the event is the one every other change of host
  ;; uses, marked as claimed so the Chronik can tell it from a hand-over.
  [state {:keys [player]}]
  (if-not (allowed? state player :claim-host)
    (err :not-allowed {:action :claim-host})
    [{:type :host/changed :host player :claimed? true}]))

;; ---- GM control of the clock ----
;;
;; The narrator's oldest privilege: "hang on, let him finish". A server-owned
;; timer is right for keeping a game moving and wrong for overriding a room full
;; of people, so the host can stop it, restart it, and buy more time.

(defmethod handle :gm/pause
  [state {:keys [player]}]
  (cond
    (not (host? state player)) (err :not-host {})
    (:paused? state)           (err :already-paused {})
    :else [{:type :gm/forced :actor player :action :pause :detail {}}
           {:type :clock/paused}
           {:type :phase/deadline :at nil}]))

(defmethod handle :gm/resume
  [state {:keys [player]}]
  (cond
    (not (host? state player))   (err :not-host {})
    (not (:paused? state))       (err :not-paused {})
    ;; No deadline is set here: clearing :paused? is enough, because the timer
    ;; re-arms any timed phase with no deadline on its next reconcile — which is
    ;; also the only place that knows what time it is.
    :else [{:type :gm/forced :actor player :action :resume :detail {}}
           {:type :clock/resumed}]))

(def ^:const max-added-secs
  "Cap on a single extension. A GM slip that adds an hour to a night is a worse
   outcome than one that has to be clicked twice."
  600)

(defmethod handle :gm/add-time
  [state {:keys [player secs]}]
  (let [secs (min max-added-secs (max 5 (or secs 60)))]
    (cond
      (not (host? state player))  (err :not-host {})
      (nil? (:deadline state))    (err :no-deadline {})
      ;; Computable without a clock: the deadline is already an absolute instant,
      ;; so extending it is arithmetic on state and stays inside the pure engine.
      :else [{:type :gm/forced :actor player :action :add-time :detail {:secs secs}}
             {:type :phase/deadline :at (+ (:deadline state) (* 1000 secs))}])))

(defmethod handle :gm/set-phase
  [state {:keys [player phase]}]
  (cond
    (not (host? state player)) (err :not-host {})
    (before-play? state)       (err :no-game-in-progress {})
    :else
    [{:type :gm/forced :actor player :action :set-phase :detail {:phase phase}}
     {:type :phase/changed :phase phase :deadline nil}]))

;; --------------------------------------------------------------- apply ---

(defn error? [x] (and (map? x) (contains? x :error)))

(defn execute
  "Validate a command, apply its events, then settle automatic transitions.
   Returns {:state s :events evs} or {:error ...}.

   This is THE entry point the server uses for every client message."
  [state cmd]
  (let [result (handle state cmd)]
    (if (error? result)
      result
      (let [after (e/apply-all state result)
            [settled auto] (phases/settle after)]
        {:state  settled
         :events (into (vec result) auto)}))))
