(ns werwolf.engine.core
  "Public facade for the engine. Server and client code should require THIS
   namespace, not the internals, so the internal layering stays free to change.

   The whole engine is pure: no I/O, no clock, no randomness beyond the seed
   carried in state. Timers and persistence live in the server layer, which is
   what makes the simulation harness possible."
  (:require [werwolf.engine.commands :as commands]
            [werwolf.engine.events :as events]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.view :as view]))

;; ---- construction -------------------------------------------------------

(defn new-game
  "Create a fresh game. `seed` makes the whole game reproducible."
  [{:keys [room-id host seed min-players]}]
  (events/apply-event nil {:type        :game/created
                           :room-id     room-id
                           :host        host
                           :seed        (or seed 0)
                           :min-players (or min-players 5)}))

;; ---- driving the game ---------------------------------------------------

(def execute
  "Validate a command, apply it, and settle automatic transitions.
   Returns {:state s :events evs} or {:error code :detail d}."
  commands/execute)

(def error? commands/error?)

(def settle
  "Run automatic (narrator) transitions until the game needs human input."
  phases/settle)

;; ---- reading the game ---------------------------------------------------

(def view-for
  "The redacted state a given player may receive. NEVER send raw state."
  view/view-for)

(def legal-actions phases/legal-actions)

(def team-of-player
  "A player's dealt team. SERVER-SIDE ONLY — this is exactly the information
   `view-for` exists to withhold. The room layer needs it to route werewolf
   chat; nothing that touches a socket payload may use it."
  events/team-of-player)

(def legal-targets phases/legal-targets)
(def winner phases/winner)
(def living-ids events/living-ids)

;; ---- persistence --------------------------------------------------------

(def replay
  "Fold a stored event log back into state."
  events/replay)

(def apply-event events/apply-event)
(def apply-all events/apply-all)

;; ---- test/ops helpers ---------------------------------------------------

(def redaction-leaks?
  "Returns offending [player role] pairs if a view exposes something it must
   not. Drives the redaction property test."
  view/redaction-leaks?)

(defn over? [state] (= :game-over (:phase state)))
