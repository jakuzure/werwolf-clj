(ns werwolf.replays
  "A corpus of event logs, written once by the engine of the day and replayed
   by every engine after it.

   The log is the game: SQLite keeps nothing else, and a room is its log folded
   through `apply-event`. A reducer that folds an old log differently rewrites
   a stored game, and that has been weighed by hand each time (ee352fe: a
   filter in the death reducer would have rewritten the votes of 128 of 142
   stored logs). The stored logs are not in the repo, they hold names and chat,
   so this corpus is played by the simulation instead and covers the same
   ground: every role, narrated and not, a chosen deck, an absent host, players
   walking out and the Spielleiter overruling.

   Only facts that make a game what it was are pinned: who won, the phase it
   ended in, every seat's card and whether it lived, the seat numbers a
   rematch has issued, and the public log the table read. A new key in state is no reason to re-pin; a log that reads
   differently is.

   `(pin!)` re-pins: it replays each checked-in log as it stands and rewrites
   only `:pinned`. Do that only when a change to the replay is meant, and say
   so in the commit: the diff of the pins is the change to every stored game.
   The logs themselves are never rewritten, or a re-pin after a reducer change
   would swap the old engine's logs for the new one's and test nothing.
   `(add!)` plays the games of `corpus` that have no file yet."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [werwolf.engine.core :as engine]
            [werwolf.sim.harness :as h]))

(def dir "test/werwolf/replays")

(def corpus
  "[name players seed harness-opts]. The seeds were picked so that between
   them the games deal every role and the overrule games see a player walk
   out and the Spielleiter kill and revive."
  [["classic-7"            7  101 {}]
   ["classic-16"           16 808 {}]
   ["narrated-9"           9  202 {:narrated? true}]
   ["deck-10"              10 303 {:random-deck? true}]
   ["deck-narrated-8"      8  404 {:random-deck? true :narrated? true}]
   ["deck-12"              12 909 {:random-deck? true}]
   ["host-absent-8"        8  707 {:host-absent? true}]
   ["overrule-11"          11 505 {:overrule? true}]
   ["overrule-narrated-9"  9  606 {:overrule? true :narrated? true}]
   ;; Not the harness's: the table pressing "Nochmal" once the game is over.
   ;; A rematch folds the finished game into a fresh lobby.
   ["rematch-7"            7  101 {:rematch? true}]])

(defn pinned
  "What a replay of `log` must keep saying."
  [log]
  (let [s (engine/replay log)]
    {:winner (:winner s)
     :phase  (:phase s)
     :seats-issued (:seats-issued s)
     :seats  (into (sorted-map)
                   (map (fn [[pid p]] [pid [(:role p) (:alive? p)]]))
                   (:players s))
     :public (:log s)}))

(defn- write! [file m]
  (with-open [w (io/writer file)]
    (binding [*out* w]
      (println ";; Written by (werwolf.replays/pin!). The log is replayed by")
      (println ";; werwolf.replays-test, and :pinned is what it must still say.")
      (println "{:log")
      (println " [")
      (doseq [ev (:log m)] (println (str "  " (pr-str ev))))
      (println " ]")
      (println " :pinned")
      (pp/pprint (:pinned m))
      (println "}"))))

(defn add!
  "Play the games of `corpus` that have no file yet and write them. Returns
   the names written."
  []
  (io/make-parents (io/file dir "x"))
  (vec (for [[nm n seed opts] corpus
             :let [f (io/file dir (str nm ".edn"))]
             :when (not (.exists f))
             :let [r   (h/play-game n seed (dissoc opts :rematch?))
                   log (cond-> (:log r) (:rematch? opts) (conj {:type :game/reset}))]]
         (do (when-not (:ok? r)
               (throw (ex-info (str nm " did not finish") (select-keys r [:reason :violations]))))
             (write! f {:log log :pinned (pinned log)})
             nm))))

(declare load-corpus)

(defn pin!
  "Replay every checked-in log and rewrite its `:pinned`, leaving the log as
   it is. Returns the names whose pins changed."
  []
  (vec (for [[nm {:keys [log] :as m}] (load-corpus)
             :let [now (pinned log)]
             :when (not= now (:pinned m))]
         (do (write! (io/file dir (str nm ".edn")) {:log log :pinned now})
             nm))))

(defn load-corpus
  "{name {:log [...] :pinned {...}}} as checked in."
  []
  (into (sorted-map)
        (for [f (.listFiles (io/file dir))
              :when (str/ends-with? (.getName f) ".edn")]
          [(str/replace (.getName f) #"\.edn$" "")
           (edn/read-string (slurp f))])))
