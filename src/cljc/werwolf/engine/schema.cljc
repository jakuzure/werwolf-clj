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
(def Team [:enum :village :werewolf :lovers])

(def Phase
  [:enum
   :lobby        ; waiting for players, host may start
   :night        ; roles act in night-order
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
   [:pending-death {:optional true} [:maybe :keyword]]])

(def Reaction
  "An interrupt that must resolve before the phase machine advances."
  [:map
   [:type [:enum :reaction/jaeger-shot :reaction/lover-follows]]
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
   [:lovers {:optional true} [:maybe [:set PlayerId]]]
   [:reactions [:vector Reaction]]            ; stack, drained LIFO
   [:pending-deaths [:vector [:map [:player PlayerId] [:cause :keyword]]]]
   ;; night bookkeeping, cleared at dawn
   [:night-actions {:optional true} [:map-of :keyword :any]]
   [:night-cursor {:optional true} [:maybe RoleId]] ; which role is acting now
   [:votes {:optional true} [:map-of PlayerId [:maybe PlayerId]]]
   [:nominations {:optional true} [:set PlayerId]]
   [:deadline {:optional true} [:maybe :int]] ; epoch ms, server-authoritative
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
