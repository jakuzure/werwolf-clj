(ns werwolf.store
  "Append-only event persistence on SQLite.

   The log is the truth: in-memory game state is only ever a fold over it. That
   buys three things for one table — a server restart rehydrates games in
   progress, a reconnecting player can be handed a fresh snapshot, and any
   finished game can be replayed for a recap.

   Events are stored as EDN rather than JSON because the engine's vocabulary is
   keywords, namespaced keywords and sets, none of which survive a JSON round
   trip without lossy encoding. EDN reads back as exactly what was written,
   which is what makes `(= (replay log) state)` hold.

   Reads use `clojure.edn/read-string`, never `read-string`: the latter evaluates
   reader tags and would turn the database into a code-execution path."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [werwolf.engine.core :as engine]))

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

;; ------------------------------------------------------------- migrate ---

(defn- ensure-parent-dir! [dbname]
  (when-let [parent (some-> (io/file dbname) .getParentFile)]
    (.mkdirs parent)))

(defn split-statements
  "Split a SQL script into individual statements.

   Line comments are stripped BEFORE splitting on `;`, because a comment may
   itself contain a semicolon — prose like 'the log is the truth; state is a
   fold over it' otherwise cuts a CREATE TABLE in half and produces gibberish
   fragments. (This is fine for our own schema files; it is not a general SQL
   parser and would mangle a `;` inside a string literal.)"
  [sql]
  (->> (str/replace sql #"--[^\n]*" "")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn migrate!
  "Apply the schema. Idempotent — every statement is CREATE ... IF NOT EXISTS."
  [ds]
  (doseq [stmt (split-statements (slurp (io/resource "migrations/001-events.sql")))]
    (jdbc/execute-one! ds [stmt]))
  ds)

(defn open
  "Create a datasource and migrate. `cfg` is the :db map from config."
  [{:keys [dbname] :as cfg}]
  (ensure-parent-dir! dbname)
  (let [ds (jdbc/get-datasource cfg)]
    ;; WAL lets a reader (a recap request) run while the game is being written.
    (jdbc/execute-one! ds ["PRAGMA journal_mode=WAL"])
    ;; NORMAL is the right trade here: a lost tail event on a hard power cut
    ;; costs at most one action of a party game, and full fsync per event is a
    ;; needless write amplification.
    (jdbc/execute-one! ds ["PRAGMA synchronous=NORMAL"])
    (jdbc/execute-one! ds ["PRAGMA foreign_keys=ON"])
    (migrate! ds)
    ds))

;; -------------------------------------------------------------- writes ---

(defn append-events!
  "Append events for a room starting at `from-seq`, in one transaction.

   Atomic on purpose: a command can produce several events (a death, its
   reaction, the resulting phase change) and a partial write would replay into a
   state the engine never actually held."
  [ds room-id from-seq events now-ms]
  (when (seq events)
    (jdbc/with-transaction [tx ds]
      (doseq [[i ev] (map-indexed vector events)]
        (jdbc/execute-one!
         tx
         ["INSERT INTO events (room_id, seq, type, payload, at) VALUES (?,?,?,?,?)"
          room-id (+ from-seq i) (str (:type ev)) (pr-str ev) now-ms]))
      (count events))))

(defn upsert-room!
  [ds room-id phase finished? now-ms]
  (jdbc/execute-one!
   ds
   ["INSERT INTO rooms (room_id, created_at, updated_at, phase, finished)
     VALUES (?,?,?,?,?)
     ON CONFLICT(room_id) DO UPDATE SET updated_at=?, phase=?, finished=?"
    room-id now-ms now-ms (str phase) (if finished? 1 0)
    now-ms (str phase) (if finished? 1 0)]))

;; --------------------------------------------------------------- reads ---

(defn load-events
  "Every event for a room, in order."
  [ds room-id]
  (->> (jdbc/execute! ds ["SELECT payload FROM events WHERE room_id = ? ORDER BY seq ASC"
                          room-id]
                      opts)
       (mapv #(edn/read-string (:payload %)))))

(defn load-game
  "Rehydrate a room's game state, or nil if the room has no events.

   `:updated-at` is when the room last saw a write, which is what tells a
   rebooting server how long the room has ALREADY been abandoned — without it
   every restart hands a dead room a fresh grace period."
  [ds room-id]
  (let [events (load-events ds room-id)]
    (when (seq events)
      {:state      (engine/replay events)
       :seq        (count events)
       :updated-at (:updated_at (jdbc/execute-one!
                                 ds ["SELECT updated_at FROM rooms WHERE room_id = ?"
                                     room-id]
                                 opts))})))

(defn room-exists? [ds room-id]
  (some? (jdbc/execute-one! ds ["SELECT 1 FROM rooms WHERE room_id = ?" room-id] opts)))

(defn unfinished-room-ids
  "Rooms to rehydrate on boot. Finished games stay on disk for recaps but are
   not resurrected into memory."
  [ds]
  (->> (jdbc/execute! ds ["SELECT room_id FROM rooms WHERE finished = 0 ORDER BY updated_at DESC"]
                      opts)
       (mapv :room_id)))

(defn purge-room!
  "Remove a room and its events outright. Used by tests and by expiry."
  [ds room-id]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute-one! tx ["DELETE FROM events WHERE room_id = ?" room-id])
    (jdbc/execute-one! tx ["DELETE FROM rooms WHERE room_id = ?" room-id])))

(defn purge-finished-before!
  "Housekeeping: drop finished games older than `cutoff-ms`."
  [ds cutoff-ms]
  (jdbc/with-transaction [tx ds]
    (let [ids (->> (jdbc/execute! tx ["SELECT room_id FROM rooms WHERE finished = 1 AND updated_at < ?"
                                      cutoff-ms] opts)
                   (mapv :room_id))]
      (doseq [id ids]
        (jdbc/execute-one! tx ["DELETE FROM events WHERE room_id = ?" id])
        (jdbc/execute-one! tx ["DELETE FROM rooms WHERE room_id = ?" id]))
      (count ids))))
