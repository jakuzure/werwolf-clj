(ns werwolf.bots
  "The server playing the bots.

   A bot has no socket. Its turn is armed on the room map after every commit
   (`rearm!`, from the clock's hook) and fired from the clock's tick (`step!`),
   through the same `room/submit!` a socket uses: same validation, same lock,
   same persistence, same broadcast. Nothing bypasses the engine, and the
   policy (werwolf.engine.bot) sees nothing but the seat's own redacted view.

   Scheduling lives on the room map, never in state or the log: when a bot
   moves is this server's business, what it does is the game's. A restart
   loses the due times and nothing else: the offer is in the state, and the
   first human back re-arms it through the ordinary commit.

   When a bot moves is still a function of the game, not of the wall clock:
   every instant below is the phase's start or the commit's instant plus a
   share drawn from the game's seed, the day and the seat (`bot/share`,
   `bot/day-shares`). Two bots never draw the same share, so the bots move one
   after another: a table where every bot answered in the same tick, the
   first at random and the rest behind it, read as a stampede, and the human
   had no time to read the screen."
  (:require [clojure.tools.logging :as log]
            [werwolf.engine.bot :as bot]
            [werwolf.engine.core :as engine]
            [werwolf.room :as room]))

(def default-delay-ms 1500)
(def default-spread-ms 6500)
(def default-floor-secs 5)
(def default-patience-secs 20)

(def ^:private day-actions
  "What a bot answers by day, and so waits for the humans on. `:ready` closes
   a discussion without a trial once every living player has pressed it; five
   bots pressing it at once would close the first day's talk before the human
   has read the screen, the same failure the vote rule exists to prevent."
  #{:vote :vote-abstain :elect :elect-abstain :ready})

(defn- answered?
  "Has pid answered what the day asks: balloted, voted, or said it is done?"
  [state pid]
  (case (:phase state)
    :election   (contains? (:election-votes state {}) pid)
    :discussion (or (contains? (:votes state {}) pid)
                    (contains? (:ready state #{}) pid))
    true))

(defn- humans-done?
  "Every living, connected human has answered. Vacuous when there is none: a
   Spielleiter holds no vote, a dead human none either, and an absent one is
   not waited for, which is why the floor exists."
  [state]
  (every? (fn [pid]
            (let [p (get-in state [:players pid])]
              (or (:bot? p) (not (:connected? p)) (answered? state pid))))
          (engine/living-ids state)))

(defn- due-at
  "When a bot offered `offs` should move. `day-share` is the seat's place in
   the day's window (bot/day-shares), `night-share` its draw for the night.

   At night and in reactions: the delay, so a human can see the move land
   rather than the night passing in a blink, plus the seat's share of the
   spread. The same 1.5 s after every offer told the table which seats were
   bots; and the pack voting all in one tick was the stampede again, in the
   dark. Never past the patience: a table that waits for nobody, as the
   browser suites set it, gets no spread either.

   By day: the seat's slot in the window between the floor and the patience
   after the phase began, so nobody moves before the floor and everybody has
   by the patience, and the ballots land one by one. Once every living human
   has answered, sooner: from the later of the floor and the delay after the
   answer, each seat its share of the spread after the other, still never
   all at once. Five bots are a majority, and a majority that votes two
   seconds into the day condemns somebody before the human has read the
   screen.

   A seat that has voted and is offered the ballot again is changing its
   mind (bot/moves): the delay and its share of the spread after the commit
   that made its accusation hopeless, as at night. Its day slot is behind
   it, and every switch landing in the tick after that commit was the
   stampede once more, one pile emptying into another all at once."
  [game-cfg phase-since state offs day-share night-share now-ms revote?]
  (let [delay    (or (:bot-delay-ms game-cfg) default-delay-ms)
        spread   (or (:bot-spread-ms game-cfg) default-spread-ms)
        floor    (* 1000 (or (:bot-floor-secs game-cfg) default-floor-secs))
        patience (* 1000 (or (:bot-patience-secs game-cfg) default-patience-secs))
        since    (or phase-since now-ms)]
    (if (and (some day-actions offs) (not revote?))
      (let [slot (+ since floor (long (* day-share (max 0 (- patience floor)))))]
        (if (humans-done? state)
          (min slot (+ (max (+ now-ms delay) (+ since floor))
                       (long (* day-share spread))))
          slot))
      (+ now-ms delay (long (* night-share (max 0 (min spread (- patience delay)))))))))

(defn rearm!
  "Arm or disarm every bot's turn to match the room as it stands. Called from
   the clock's hook after every commit, under the room's lock, like the clock.

   Nothing is armed while nobody is here: the clock's own rule is that time
   nobody could play through is not time, and a table of bots that played on
   through a one-minute outage would hand whoever came back a finished game.
   The phase's start is forgotten with them, so the floor counts from the
   human's return: the clock gives a phase a full fresh clock after an
   absence for the same reason, and a watcher coming back has read nothing.

   A turn already armed keeps its instant only for the SAME offer. A seat's
   turn can take two moves, the Hexe's heal and then her pass, a dead
   Jäger's shot and then, as Bürgermeister, his successor, and the second
   must get its own delay, not the first move's instant, already past. Day
   turns are recomputed on every commit, because the humans answering is
   what moves them earlier; of the armed instant and the recomputed one the
   earlier stands, because the bots' own commits recompute too, and each
   would otherwise push the next bot's turn a little further out."
  [ctx game-cfg room-id now-ms]
  (when-let [state (room/state-of ctx room-id)]
    (let [notes  (room/scheduling ctx room-id)
          bots   (:bots notes)
          empty? (engine/nobody-here? state)
          ;; When the phase began, by THIS clock: the commit stamps the wall
          ;; clock, and a test ticks simulated instants, the same reason the
          ;; clock's hook takes one. Still under the room's lock, right after
          ;; the commit that changed the phase, so no later commit can slip
          ;; in between. nil while nobody is here, and so stamped afresh by
          ;; the commit that brings somebody back.
          same-phase? (and (not empty?)
                           (= (:phase notes) (:phase state))
                           (some? (:phase-since notes)))
          phase-since (cond
                        empty?      nil
                        same-phase? (:phase-since notes)
                        :else       now-ms)
          turn   (:night state 0)
          views  (into {}
                       (keep (fn [[pid p]] (when (:bot? p) [pid (engine/view-for state pid)])))
                       (:players state))
          living (set (engine/living-ids state))
          shares (bot/day-shares (:seed state) turn
                                 (into {}
                                       (keep (fn [[pid v]] (when (living pid) [pid (bot/informed? v)])))
                                       views))
          due (if empty?
                {}
                (into {}
                      (keep (fn [[pid v]]
                              (let [offs  (bot/offers v)
                                    prev  (get bots pid)
                                    same? (and prev (= (:offers prev) offs))
                                    day?  (boolean (some day-actions offs))]
                                (when (seq offs)
                                  [pid {:offers offs
                                        :at (let [at (due-at game-cfg phase-since state offs
                                                             (get shares pid 0)
                                                             (bot/share (:seed state) turn pid)
                                                             now-ms
                                                             (and (contains? offs :vote)
                                                                  (contains? (:votes state {}) pid)))]
                                              (cond
                                                (and same? (not day?))   (:at prev)
                                                (and same? same-phase?)  (min (:at prev) at)
                                                :else                    at))}]))))
                      views))]
      (room/schedule! ctx room-id {:phase (:phase state) :phase-since phase-since :bots due}))))

(defn step!
  "Fire every bot whose turn is due. Returns true if any command was submitted.

   The decision is taken from the state as read here and validated by the
   engine under the lock in `submit!`; a socket landing in between gets the
   command refused, which is logged and retried once the patience has passed.
   A refusal that is not a race is a policy bug; the all-bot games in
   werwolf.engine.bot-test are the guard against that ever being systematic."
  [ctx room-id now-ms]
  (let [{:keys [bots]} (room/scheduling ctx room-id)
        due (sort (keep (fn [[pid {:keys [at]}]] (when (<= at now-ms) pid)) bots))]
    (boolean
     (seq
      (doall
       (for [pid due
             :let [state (room/state-of ctx room-id)
                   {:keys [seq] :as notes} (room/scheduling ctx room-id)]
             ;; Read again per seat: the previous seat's move may have closed
             ;; the phase and re-armed this one for a new offer, a delay away.
             :when (<= (get-in notes [:bots pid :at] ##Inf) now-ms)
             :when (and state
                        (not (engine/nobody-here? state))
                        (get-in state [:players pid :bot?]))
             :let [cmd (room/timed ctx :bot
                         (bot/decide (engine/view-for state pid)
                                     (bot/seed-for (:seed state) seq pid)))]
             :when cmd
             :let [res (room/submit! ctx room-id pid cmd {:activity? false})]]
         (do
           (when-let [err (:error res)]
             (log/warn "bot" pid "in" room-id "had its move refused:" (pr-str cmd) err)
             (room/reschedule! ctx room-id pid
                               (+ now-ms (* 1000 (or (:bot-patience-secs (:game ctx))
                                                     default-patience-secs)))))
           pid)))))))
