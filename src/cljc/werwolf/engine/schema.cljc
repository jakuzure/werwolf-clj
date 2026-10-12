(ns werwolf.engine.schema
  "Malli schemas for game state, commands and events.

   These serve three jobs at once:
     1. documentation of the agreed state shape,
     2. runtime validation of anything arriving from a client,
     3. generators for the simulation harness."
  (:require [malli.core :as m]
            [werwolf.engine.roles :as roles]))

(def PlayerId :int)
(def RoleId (into [:enum] (sort roles/all-ids)))
(def Team
  "Win conditions, not only dealt teams. `:lovers` and `:weisser-werwolf` are
   never dealt — the first is created at runtime by Amor, the second is a solo
   victory available to a player dealt as a werewolf."
  [:enum :village :werewolf :lovers :weisser-werwolf])

(def Phase
  [:enum
   :lobby        ; waiting for players, host may start
   :setup        ; narrated rooms only: cards dealt, the Spielleiter may rearrange them
   :night        ; roles act in night-order
   :election     ; day 1 only: the village elects a Bürgermeister
   :day-reveal   ; announce who died overnight
   :discussion   ; free talk, timer running
   :nomination   ; nominate candidates for the lynch
   :vote         ; cast votes
   :day-resolve  ; apply the lynch
   :reaction     ; draining the reaction stack (Jäger shot, lover follows)
   :game-over])

(def Player
  [:map
   [:id PlayerId]
   [:name :string]
   [:role {:optional true} [:maybe RoleId]]
   [:alive? :boolean]
   [:connected? :boolean]
   [:locale [:enum :de :en]]
   ;; one-shot resources, e.g. {:heiltrank 1 :gifttrank 1}
   [:resources {:optional true} [:map-of :keyword :int]]
   ;; private knowledge this player has accumulated: {target-id role-id}
   [:knows {:optional true} [:map-of PlayerId RoleId]]
   ;; set when a player is killed but the death has not yet been announced
   [:pending-death {:optional true} [:maybe :keyword]]
   ;; Who this player guarded last night. On the PLAYER rather than in
   ;; :night-actions, which is cleared at dawn — the no-repeat rule has to
   ;; remember across nights.
   [:last-protected {:optional true} [:maybe PlayerId]]])

(def Reaction
  "An interrupt that must resolve before the phase machine advances."
  [:map
   ;; :reaction/mayor-succession was missing here from the day the office was
   ;; added; the schema walk over a narrated simulated game was the first to
   ;; reach a dying Bürgermeister.
   [:type [:enum :reaction/jaeger-shot :reaction/lover-follows
           :reaction/mayor-succession]]
   [:player PlayerId]
   [:cause {:optional true} :keyword]])

(def GameState
  "DELIBERATELY :closed. An open map would silently accept any new key, which
   defeats the point — the schema exists to catch state drift, and every key the
   engine writes should have to be declared here on purpose."
  [:map {:closed true}
   [:room-id :string]
   [:phase Phase]
   [:night :int]                              ; 0 in lobby, 1-based thereafter
   [:players [:map-of PlayerId Player]]
   [:host PlayerId]
   [:seed :int]                               ; RNG seed — replay determinism
   [:min-players :int]
   [:max-players {:optional true} :int]
   ;; Rule variant: false (default) means no werewolf turn on night 1.
   [:first-night-kill? {:optional true} :boolean]
   ;; The host is the Spielleiter and does not play: no card, no vote, never a
   ;; target. Absent means the host plays, which is how every log written before
   ;; this existed replays. See events/narrator.
   [:narrated? {:optional true} :boolean]
   ;; Whether the room has a clock at all. Absent means it does — again, the old
   ;; behaviour. A narrated room opens with it off.
   [:timer? {:optional true} :boolean]
   [:lovers {:optional true} [:maybe [:set PlayerId]]]
   ;; The two cards nobody was dealt, which the Dieb chooses between on the first
   ;; night. Empty in games without one. PRIVATE to him — `view-for` publishes it
   ;; only through his own `:choices`, never as room state.
   [:centre {:optional true} [:vector :keyword]]
   [:reactions [:vector Reaction]]            ; stack, drained LIFO
   [:pending-deaths [:vector [:map [:player PlayerId] [:cause :keyword]]]]
   ;; night bookkeeping, cleared at dawn
   [:night-actions {:optional true} [:map-of :keyword :any]]
   [:night-cursor {:optional true} [:maybe RoleId]] ; which role is acting now
   ;; A nil vote is a recorded ABSTENTION, which is not the same thing as an
   ;; absent key: one is a decision, the other is a player we are still waiting
   ;; for. The phase machine needs to tell them apart to know when a vote is done.
   [:votes {:optional true} [:map-of PlayerId [:maybe PlayerId]]]
   ;; The Bürgermeister: a TITLE, not a role. Nobody is dealt it — the village
   ;; elects one on the first day and it passes by appointment when he dies. His
   ;; vote counts double, which is the whole of its power.
   [:mayor {:optional true} [:maybe PlayerId]]
   ;; Ballots for that election, same shape as :votes. A nil target is a
   ;; recorded abstention, for the same reason it is in the lynch.
   [:election-votes {:optional true} [:map-of PlayerId [:maybe PlayerId]]]
   ;; So the election happens once and only once, even if day 1 is revisited by
   ;; a GM override.
   [:election-held? {:optional true} :boolean]
   [:nominations {:optional true} [:set PlayerId]]
   ;; Players who have finished nominating — whether they named someone or
   ;; explicitly declined. Same distinction as above: "I accuse nobody" is an
   ;; answer, silence is not. Holds ACCUSERS, where :nominations holds accused.
   [:nomination-done {:optional true} [:set PlayerId]]
   ;; Players who have finished talking, so the discussion can end early.
   [:ready {:optional true} [:set PlayerId]]
   [:deadline {:optional true} [:maybe :int]] ; epoch ms, server-authoritative
   ;; Set by the GM. Suppresses the clock entirely rather than merely clearing
   ;; the deadline, which the timer would re-arm on its next reconcile.
   [:paused? {:optional true} :boolean]
   [:winner {:optional true} [:maybe Team]]
   ;; set by :vote/resolved so :day-resolve knows the lynch has been counted
   [:vote-resolved? {:optional true} :boolean]
   ;; where to return to once the reaction stack drains
   [:resume-phase {:optional true} [:maybe Phase]]
   ;; opt-in ghost view for the dead — see werwolf.engine.view
   [:reveal-to-dead? {:optional true} :boolean]
   ;; public, localisable event log — structured, never prose
   [:log [:vector [:map [:type :keyword]]]]])

(def valid-state? (m/validator GameState))
(def explain-state (m/explainer GameState))

(defn check-state!
  "Throw with a readable explanation if state drifts from the schema.
   Used in tests and dev, not on the hot path."
  [state]
  (if (valid-state? state)
    state
    (throw (ex-info "invalid game state" {:explain (explain-state state)}))))
