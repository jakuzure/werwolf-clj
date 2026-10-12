(ns werwolf.engine.core
  "Public facade for the engine. Server and client code should require THIS
   namespace, not the internals, so the internal layering stays free to change.

   The whole engine is pure: no I/O, no clock, no randomness beyond the seed
   carried in state. Timers and persistence live in the server layer, which is
   what makes the simulation harness possible."
  (:require [werwolf.engine.commands :as commands]
            [werwolf.engine.events :as events]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]
            [werwolf.engine.view :as view]))

(def absolute-min-players
  "The smallest table the deck composes a real game for. A rule, so it lives in
   the engine and the room layer reads it from here rather than keeping its own
   three — which it did, and which would have drifted the moment either moved."
  roles/absolute-min-players)

;; ---- construction -------------------------------------------------------

(defn new-game
  "Create a fresh game. `seed` makes the whole game reproducible.

   Rule variants and table limits are passed through verbatim rather than
   defaulted here, so there is exactly one place that decides what a missing
   value means: `apply-event :game/created`."
  [{:keys [room-id host seed min-players max-players first-night-kill?
           narrated? timer?]}]
  (events/apply-event nil (cond-> {:type        :game/created
                                   :room-id     room-id
                                   :host        host
                                   :seed        (or seed 0)
                                   :min-players (or min-players 5)
                                   :max-players max-players
                                   :first-night-kill? first-night-kill?}
                            (some? narrated?) (assoc :narrated? narrated?)
                            (some? timer?)    (assoc :timer? timer?))))

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

(def timed-phases
  "Which phases the clock owns -> the config key holding their length."
  phases/timed-phases)

(def timeout-events
  "What the clock running out means here. The server decides *when*; this
   decides *what*."
  phases/timeout-events)
(def host-absent-events
  "What the host going missing means here. The server decides *when* — a grace
   period on the clock — and this decides *what*."
  phases/host-absent-events)

(def living-ids events/living-ids)

(def narrator
  "The Spielleiter's seat, or nil when the host plays."
  events/narrator)

(def participant-ids
  "Every seat that plays the game — all of them but the narrator's."
  events/participant-ids)

(def timed?
  "Does this room run a clock at all? The timer reads it; the rule is here."
  phases/timed?)

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
