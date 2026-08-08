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
            [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]
            [werwolf.engine.rng :as rng]))

(defn- err [code detail] {:error code :detail detail})

(defn- allowed? [state pid action]
  (contains? (phases/legal-actions state pid) action))

(defn- valid-target? [state pid action target]
  (contains? (set (phases/legal-targets state pid action)) target))

;; The day progression map lives in phases.cljc so legal-actions can use it.
(def next-phase phases/next-phase)

;; ------------------------------------------------------------- dealing ---

(defn deal
  "Assign roles deterministically from the game's seed.
   Returns {:assignments {pid role} :seed advanced-seed}."
  [state]
  (let [ids  (vec (sort (keys (:players state))))
        deck (roles/compose-roles (count ids))
        [seed' shuffled] (rng/shuffle (:seed state) deck)]
    {:assignments (zipmap ids shuffled)
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
    (>= (count (:players state))
        (:max-players state 18))          (err :room-full {})
    (str/blank? (str name))               (err :name-required {})
    :else [{:type :player/joined
            :player player
            :name (str/trim (str name))
            :locale (or locale :de)}]))

(defmethod handle :leave
  [state {:keys [player]}]
  (if (contains? (:players state) player)
    [{:type :player/left :player player}]
    (err :not-in-room {:player player})))

(defmethod handle :set-locale
  [state {:keys [player locale]}]
  (cond
    (not (contains? (:players state) player)) (err :not-in-room {})
    (not (#{:de :en} locale))                 (err :unsupported-locale {:locale locale})
    :else [{:type :player/locale-set :player player :locale locale}]))

(defmethod handle :start-game
  [state {:keys [player]}]
  (if-not (allowed? state player :start-game)
    (err :not-allowed {:action :start-game})
    (let [{:keys [assignments seed]} (deal state)]
      [{:type :game/started :assignments assignments :seed seed}
       {:type :night/began :night 1}])))

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

(defmethod handle :nominate
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :nominate)) (err :not-allowed {:action :nominate})
    (not (valid-target? state player :nominate target)) (err :invalid-target {:target target})
    :else [{:type :player/nominated :by player :target target}]))

(defmethod handle :ready
  [state {:keys [player]}]
  (if-not (allowed? state player :ready)
    (err :not-allowed {:action :ready})
    [{:type :player/ready :player player}]))

(defmethod handle :nominate-pass
  [state {:keys [player]}]
  (if-not (allowed? state player :nominate-pass)
    (err :not-allowed {:action :nominate-pass})
    [{:type :nominate/passed :player player}]))

(defmethod handle :vote
  [state {:keys [player target]}]
  (cond
    (not (allowed? state player :vote)) (err :not-allowed {:action :vote})
    ;; A nil target reaching here is an abstention and is allowed, but
    ;; :vote-abstain is the honest way to say it — see that handler.
    (and (some? target)
         (not (valid-target? state player :vote target))) (err :invalid-target {:target target})
    :else [{:type :vote/cast :voter player :target target}]))

(defmethod handle :vote-abstain
  ;; A separate command rather than "send :vote with no target", so that
  ;; abstaining is something `legal-actions` can advertise. The client renders
  ;; the affordances it is given and invents none — an abstain button the UI
  ;; synthesised itself would be the client deciding a rule.
  [state {:keys [player]}]
  (if-not (allowed? state player :vote-abstain)
    (err :not-allowed {:action :vote-abstain})
    [{:type :vote/cast :voter player :target nil}]))

(defmethod handle :advance-phase
  [state {:keys [player]}]
  (if-not (allowed? state player :advance-phase)
    (err :not-allowed {:action :advance-phase})
    (if-let [nxt (next-phase (:phase state))]
      [{:type :phase/changed :phase nxt :deadline nil}]
      (err :cannot-advance {:phase (:phase state)}))))

;; ---- GM overrides -------------------------------------------------------
;; Every override is logged with its actor, so it is auditable in the public
;; log rather than something the host can do quietly.

(defn- host? [state pid] (= pid (:host state)))

(defmethod handle :gm/force-kill
  [state {:keys [player target]}]
  (cond
    (not (host? state player)) (err :not-host {})
    (not (get-in state [:players target :alive?])) (err :already-dead {:target target})
    :else [{:type :gm/forced :actor player :action :force-kill :detail {:target target}}
           {:type :player/died :player target :cause :gm}]))

(defmethod handle :gm/revive
  [state {:keys [player target]}]
  (cond
    (not (host? state player)) (err :not-host {})
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
  [state {:keys [player target role]}]
  (cond
    (not (host? state player))                (err :not-host {})
    (#{:lobby :game-over} (:phase state))     (err :no-game-in-progress {})
    (not (get-in state [:players target]))    (err :no-such-player {:target target})
    (not (contains? roles/all-ids role))      (err :unknown-role {:role role})
    (= role (get-in state [:players target :role]))
    (err :already-that-role {:target target})
    :else [{:type :gm/forced :actor player :action :reassign-role
            :detail {:target target}}
           {:type :player/role-changed :player target :role role}]))

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
  (if-not (host? state player)
    (err :not-host {})
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
