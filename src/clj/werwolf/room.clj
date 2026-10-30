(ns werwolf.room
  "Rooms: where the pure engine meets concurrent sockets.

   The engine is a function. Two dozen WebSocket threads are not. This
   namespace is the entire adaptation between the two, and it holds exactly
   four responsibilities:

   1. **Serialise commands per room.** Every mutation of a room runs under that
      room's own lock, so `execute` never interleaves with itself and the event
      log is a total order. Locks are per room, not global — a room is an
      independent little world, and two games must never be able to slow each
      other down.

   2. **Persist before believing.** Events go to SQLite inside the lock, then
      into memory. A crash between the two loses nothing; a crash the other way
      round would leave clients holding a state the log cannot reproduce.

   3. **Fan out redacted views.** Never raw state, never one shared payload —
      `view-for` per player, every time, no exceptions. This is the security
      boundary the whole engine was shaped around, and it lives here in exactly
      one function (`broadcast!`).

   4. **Route chat.** Chat is *not* game state and is deliberately absent from
      the engine and the event log: it decides nothing and replaying it would
      change no outcome. But its audience is decided by game state — the wolves
      whisper only to wolves, the dead only to the dead — so the routing has to
      happen where state lives, and the client is never asked who should
      receive its message.

   Sockets are opaque here. The room layer holds 'sinks' and calls an injected
   `:send!`, which keeps it testable without a browser and keeps WebSocket
   specifics in ws.clj where they belong."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [werwolf.engine.core :as engine]
            [werwolf.engine.recap :as recap]
            [werwolf.engine.stats :as stats]
            [werwolf.password :as password]
            [werwolf.store :as store])
  (:import (java.security SecureRandom)))

;; -------------------------------------------------------------- registry ---

(def ^:private code-alphabet
  ;; No I, O, 0 or 1: these codes get read aloud over a voice call and typed by
  ;; someone who is half paying attention.
  "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

(def ^:private code-length 4)

(def ^:private max-chat-text 500)

(defonce ^:private ^SecureRandom rnd (SecureRandom.))

(defn- random-code []
  (apply str (repeatedly code-length #(nth code-alphabet (.nextInt rnd (count code-alphabet))))))

(defn- random-seed []
  ;; The engine's LCG works modulo 2^32; any non-negative int is a valid seed.
  ;; The seed is chosen HERE, not in the engine, because picking it is the one
  ;; irreducibly non-deterministic act in starting a game — and once it is in
  ;; the log the game is reproducible forever after.
  (.nextInt rnd Integer/MAX_VALUE))

(defn registry
  "Create a room registry. `ds` is a store datasource; `send!` is a 2-arity
   fn of [sink message] that the transport supplies.

   `max-chat` bounds the per-room chat backlog kept for reconnecting players.
   `max-rooms` bounds how many rooms are open at once; nil is no bound.

   `after-commit` is called as (f ctx room-id) at the end of every committed
   change, still holding the room's lock. The clock uses it to keep a room's
   deadline in step with its phase without room.clj having to know that timers
   exist. It may itself commit — Java monitors are reentrant — and must
   converge, i.e. eventually make no further change.

   `on-events` is called as (f events) with every batch as it is committed —
   not on replay — which is how the metrics count games without room.clj
   knowing they exist."
  [{:keys [ds send! max-chat max-rooms after-commit on-events game]}]
  {:ds           ds
   :send!        send!
   :max-chat     (or max-chat 200)
   ;; A room is a few MB of heap and a thread's worth of locking, and a client
   ;; needs no seat to ask for one: without a ceiling, one script could open
   ;; rooms until the JVM died. Only `create!` honours it. `rehydrate!` does
   ;; not, since a room on disk is already paid for and refusing it would
   ;; lose a game in progress to a limit that was meant for strangers.
   :max-rooms    max-rooms
   :after-commit after-commit
   :on-events    on-events
   ;; Table-size limits from config. They live here rather than being read at
   ;; the point of use because they are baked into :game/created — a room keeps
   ;; the limits it was opened with, so changing config cannot retroactively
   ;; make a game in progress unstartable.
   :game         (or game {})
   :rooms        (atom {})
   ;; sink -> [room-id player-id], so a socket closing knows who it was
   ;; without the transport having to remember.
   :index        (atom {})
   ;; sink -> epoch-ms of its recent wrong passwords. See `throttled?`.
   :attempts     (atom {})})

(defn- rooms [ctx] @(:rooms ctx))
(defn- room-of [ctx room-id] (get (rooms ctx) room-id))

(defn- update-room!
  "`update` one room, but only while it is still there.

   A write can arrive for a room that is already gone: the sweep forgets rooms
   without taking their lock, and the bots' tick writes its schedule without it
   too. A plain `update` would put the id back with whatever `f` makes of nil —
   nil itself, which nothing ever removes (`forget-room!` finds no room to
   forget, so the sweep 'drops' it again every second), or a room without a
   `:lock` for the next `with-room` to hold."
  [ctx room-id f & args]
  (swap! (:rooms ctx)
         (fn [m] (if (get m room-id) (apply update m room-id f args) m))))

(defn state-of
  "The authoritative game state for a room, or nil. Read-only; callers outside
   this namespace must not attempt to write it back."
  [ctx room-id]
  (:state (room-of ctx room-id)))

(defn room-ids [ctx] (sort (keys (rooms ctx))))

(defn open-rooms
  "The rooms a newcomer could actually join, for the landing screen.

   Only lobbies with a free seat. A game in progress cannot be joined — the
   engine refuses it — and listing one would be an invitation to a door that is
   already locked.

   What it discloses is deliberate and small: the code, how many are waiting,
   and the host's name, which is how a friend group tells 'Anna's game' from
   'Bob's game'. No roles, obviously, but also no player list — the code alone
   is what admits you, and until you are in the room the others are not your
   business.

   Note this trades a little privacy for not having to read a code off someone
   else's phone: anyone who can reach the server can see that a room exists and
   join it — unless its host gave it a password, in which case it is listed
   with `:locked? true` and `join!` asks for the word. That is right for a
   private group on a private host, and would want revisiting before the server
   is opened to the public — see the plan's deferred 'public lobby browser'.

   Not an EMPTY lobby, though it can still be joined by its code: with nobody
   left there is no host to recognise it by and nobody to play with, and an
   entry reading '0 / 18' with no name is noise on a phone screen. The code may
   already be on its way to a friend, so the room itself stays until the idle
   sweep retires it."
  [ctx]
  (->> (vals (rooms ctx))
       (keep (fn [{s :state}]
               ;; Players, not seats: a Spielleiter takes no card, so he neither
               ;; fills the table nor counts towards it — the same count
               ;; `handle :join` refuses on.
               ;; Somebody CONNECTED, not merely seated: a lobby whose last
               ;; tab closed kept its seats until the sweep, and sat in the
               ;; list for minutes as a room nobody would ever answer in.
               (when (and (= :lobby (:phase s))
                          (some :connected? (vals (:players s)))
                          (< (count (engine/participant-ids s)) (:max-players s 18)))
                 (cond-> {:room-id     (:room-id s)
                          :players     (count (engine/participant-ids s))
                          :max-players (:max-players s 18)
                          :host        (get-in s [:players (:host s) :name])}
                   ;; Still listed when locked: the password keeps strangers
                   ;; out, and hiding the room would mostly keep friends from
                   ;; finding it. The 🔒 tells them to ask before tapping.
                   (:password-hash s) (assoc :locked? true)))))
       (sort-by :room-id)
       vec))

(defn- with-room
  "Run `f` against the room, holding that room's lock. Re-reads the room inside
   the lock so `f` never sees a stale snapshot.

   `f` is expected to have effects (that is the point) — persistence and
   broadcast both happen under this lock. Fan-out is a handful of non-blocking
   queue pushes, and paying for them here buys strict per-room ordering: no
   client can ever receive view N+1 before view N."
  [ctx room-id f]
  (if-let [r0 (room-of ctx room-id)]
    (locking (:lock r0)
      (if-let [r (room-of ctx room-id)]
        (f r)
        {:error :no-such-room}))
    {:error :no-such-room}))

;; ------------------------------------------------------------- broadcast ---

(defn- deliver! [ctx sink msg]
  ((:send! ctx) sink msg))

(defn- in-voice
  "The seats with at least one socket in the call. Per socket underneath, so
   hanging up in one of two tabs does not take the player out of the call they
   are still in from the other."
  [r]
  (into (sorted-set)
        (keep (fn [[pid sinks]] (when (some (:voice r #{}) sinks) pid)))
        (:sinks r)))

;; The end-of-game recap rides along with the view like `:voice`; see the recap
;; section below for when it exists and who gets it.
(declare with-recap remember-recap!)

(defn- send-view! [ctx r pid]
  (doseq [sink (get-in r [:sinks pid])]
    (deliver! ctx sink {:type :room/view
                        :seq  (:seq r)
                        ;; Who is in the voice call rides along with the view.
                        ;; It is not game state — the engine never sees it and
                        ;; the log never records it — but it is public: everyone
                        ;; shares one call, so being in it says nothing about a
                        ;; role, and seeing who is already there is what tells
                        ;; a newcomer the call is where the game is.
                        :view (-> (engine/view-for (:state r) pid)
                                  (assoc :voice (in-voice r))
                                  (with-recap r))})))

(defn- broadcast!
  "Give every SEATED connected player their own view of the room.

   The ONLY place game state reaches a socket. One `view-for` per player id —
   never a shared payload, never raw state.

   Seated, because a socket leaves `:sinks` when it CLOSES and not when its
   player leaves the room. Without that filter the commit recording
   `:player/left` sent the departing player one last view of the room they had
   just walked out of — pointless, mildly wrong (it is a snapshot of a table they
   are no longer at), and the reason leaving took two clicks: the client treated
   an arriving view as proof it was in a room. The client now refuses views it
   has no seat for as well; a bug reachable from both ends is worth closing at
   both, since either fix alone leaves the other half latent."
  [ctx r]
  (let [seated (:players (:state r))]
    (doseq [pid (keys (:sinks r))
            :when (contains? seated pid)]
      (send-view! ctx r pid))))

;; ---------------------------------------------------------------- commit ---

(defn- commit!
  "Persist `events`, install `state'`, and broadcast. Must run under the lock.

   `activity?` says whether this counts as somebody doing something, which is
   what `sweep-idle!` measures. A player's command does; what the server writes
   on its own — the clock firing, a seat marked absent — does not. Counting the
   clock as activity kept an abandoned timed game alive forever: every timeout
   was a commit, every commit pushed the sweep back, and one room was found at
   night 1384 with nobody in it since August. Sockets coming and going stamp
   activity themselves, in attach!/detach!."
  ([ctx r events state'] (commit! ctx r events state' true))
  ([ctx r events state' activity?]
   (let [room-id (:room-id r)
         from    (:seq r)
         now     (System/currentTimeMillis)]
     (store/append-events! (:ds ctx) room-id from events now)
     (when-let [f (:on-events ctx)] (f events))
     ;; :last-activity goes to disk as well, so that a boot can tell how long a
     ;; room has really been abandoned — see `abandoned?`.
     (store/upsert-room! (:ds ctx) room-id (:phase state')
                         (= :game-over (:phase state')) now
                         (if activity? now (:last-activity r)))
     (let [r' (-> r
                  (assoc :state state')
                  (update :seq + (count events)))]
       (update-room! ctx room-id merge
                     (cond-> (select-keys r' [:state :seq])
                       activity? (assoc :last-activity now)
                       ;; A rematch starts with an empty chat: the last game's wolves'
                       ;; whispers were still on the screen of somebody who is a
                       ;; villager now, and a reconnect would replay them.
                       (some #(= :game/reset (:type %)) events) (assoc :chat [])))
       (remember-recap! ctx room-id)
       (broadcast! ctx (room-of ctx room-id))
       ;; Last, and still under the lock: the clock gets to react to the new
       ;; phase. Doing it here rather than folding the deadline into this same
       ;; batch costs one extra broadcast per phase change — a few times a game —
       ;; and in exchange room.clj needs to know nothing about timers.
       (when-let [f (:after-commit ctx)]
         (f ctx room-id))
       r'))))

(defn- apply-server-events!
  "Append events the SERVER authors — connection changes, host succession, the
   clock running out — rather than a player commanding them, then settle.

   These bypass `handle` deliberately: they are not player intent and there is
   nothing to validate. The socket really did close; the clock really did
   expire. They are still logged, because they are part of state and a log that
   omitted them would replay into a state the server never held."
  [ctx r events]
  (if (seq events)
    (let [after (engine/apply-all (:state r) events)
          [settled auto] (engine/settle after)]
      ;; Not activity — see commit!. Every caller that IS somebody doing
      ;; something (a socket, a join, a command) has already stamped it.
      (commit! ctx r (into (vec events) auto) settled false))
    r))

(defn narrate!
  "Apply server-authored events computed from the state as seen UNDER the lock.

   `events-fn` is a function of state rather than a ready-made vector on
   purpose: the clock decides what to do based on a deadline that another thread
   may have just moved, so reading state outside the lock and acting on it
   inside would be a race."
  [ctx room-id events-fn]
  (with-room ctx room-id
    (fn [r]
      (if-let [events (seq (events-fn (:state r)))]
        (do (apply-server-events! ctx r (vec events))
            {:ok? true :events (count events)})
        {:ok? true :noop? true}))))

;; ------------------------------------------------------------ create/join ---

(defn- fresh-room [room-id]
  {:room-id    room-id
   :state      nil
   :seq        0
   :sinks      {}
   ;; Sockets whose page says it is in the voice call. Reported by the client,
   ;; because the server only mints call tokens and never asks the SFU who came.
   :voice      #{}
   :chat       []
   :lock       (Object.)
   :created-at (System/currentTimeMillis)
   ;; Bumped by every commit and every socket coming or going. What `sweep-idle!`
   ;; reads to decide a room has been abandoned rather than merely gone quiet.
   :last-activity (System/currentTimeMillis)})

(defn- full?
  "Are there already as many rooms as the registry may hold?"
  [ctx]
  (when-let [cap (:max-rooms ctx)]
    (>= (count (rooms ctx)) cap)))

(defn- reserve-code!
  "Claim an unused room code atomically, or nil when the registry is `full?`.
   Checks memory and disk: a finished game stays on disk for recaps, and
   reusing its code would splice two games into one log.

   The cap is checked inside the swap, with the code: two creates racing for
   the last free room would otherwise both get one."
  [ctx]
  (let [cap (:max-rooms ctx)]
    (loop [attempt 0]
      (when (> attempt 200)
        (throw (ex-info "could not allocate a free room code" {:attempts attempt})))
      (let [code (random-code)]
        (if (store/room-exists? (:ds ctx) code)
          (recur (inc attempt))
          (let [[old new] (swap-vals! (:rooms ctx)
                                      (fn [m] (if (or (contains? m code)
                                                      (and cap (>= (count m) cap)))
                                                m
                                                (assoc m code (fresh-room code)))))]
            (cond
              (contains? old code) (recur (inc attempt))
              (contains? new code) code
              :else nil)))))))

(def ^:private absolute-min-players
  "The smallest table the role composition produces a real game for: one wolf,
   a Seherin and a villager. Below that a 'game' is decided before it starts,
   so a misconfigured MIN_PLAYERS is clamped rather than obeyed.

   Taken from the engine, which is where the deck lives. This was a literal 3
   here and another one in the lobby's start rule, and two copies of a number
   are two places to change it."
  engine/absolute-min-players)

(defn- create*
  "`create!` past its cheap refusal: hash, reserve a code, open the room."
  [ctx name locale min-players max-players seed narrated? password]
  (let [;; Before the code is reserved and well before the lock: hashing is slow
        ;; on purpose, and nothing about it needs the room.
        pw-hash (when-not (password/blank? password) (password/hash-password password))
        room-id (reserve-code! ctx)
        cfg (:game ctx)
        min* (max absolute-min-players
                  (or min-players (:min-players cfg) 5))
        max* (max min* (or max-players (:max-players cfg) 18))
        first-kill? (boolean (:first-night-kill? cfg))
        first-trial? (boolean (:first-day-trial? cfg))
        narrated?* (boolean (if (some? narrated?) narrated? (get cfg :narrated? true)))]
    (if-not room-id
      ;; Full after all: another create took the last room while this one
      ;; was hashing its password.
      {:error :too-many-rooms}
     (with-room ctx room-id
      (fn [r]
        ;; The genesis event is built once and both applied and persisted, so
        ;; the live state and the log cannot disagree about the seed.
        (let [genesis {:type        :game/created
                       :room-id     room-id
                       :host        0
                       :seed        (or seed (random-seed))
                       :min-players min*
                       :max-players max*
                       :first-night-kill? first-kill?
                       :first-day-trial? first-trial?
                       :narrated?   narrated?*
                       ;; The clock follows the mode, as it does when the host
                       ;; flips it later — see :game/narrated-set.
                       :timer?      (not narrated?*)}
              joined  (engine/execute (engine/apply-event nil genesis)
                                      {:type :join :player 0
                                       :name name :locale locale})]
          (if (engine/error? joined)
            (do (swap! (:rooms ctx) dissoc room-id)
                joined)
            ;; Locked in the same commit that opens it, so there is no moment
            ;; in which the room is listed and open.
            (let [lock (when pw-hash [{:type :room/password-set :hash pw-hash}])]
              (commit! ctx r (into [genesis] cat [(:events joined) lock])
                       (engine/apply-all (:state joined) lock))
              {:room-id room-id :player-id 0}))))))))

(defn create!
  "Open a room and seat its creator as host (player 0).
   Returns {:room-id .. :player-id 0} or {:error ..}.

   `min-players`/`max-players` default to the registry's config, which is what
   makes MIN_PLAYERS actually mean something — it was read into config and then
   never plumbed here, so the limit was silently always 5.

   `narrated?` likewise, and it defaults to TRUE when config says nothing: the
   creator is the Spielleiter, dealt no card, and the room has no clock until
   somebody switches one on. That is how the game is played at a real table, and
   the host can change it in the lobby. Whoever needs the old room — the creator
   playing, the clock running — passes `:narrated? false`, here or in config."
  [ctx {:keys [name locale min-players max-players seed narrated? password]}]
  (if (full? ctx)
    ;; Asked here as well as in `reserve-code!`, where it is atomic: the hash
    ;; below is slow on purpose, and a registry that is full should not be
    ;; made to pay it for a room it is about to refuse.
    {:error :too-many-rooms}
    (create* ctx name locale min-players max-players seed narrated? password)))

(defn- succession
  "If the host has walked out of the lobby, hand the powers to the lowest
   remaining seat. Without this the room is unstartable and no command can fix
   it — nobody but the host may start a game.

   Asked after a join as well as after a command. When the LAST player leaves
   there is no heir, so the room keeps a host who is no longer in it; the next
   person to walk in is the first heir there is, and an empty room has nobody
   left to issue the command that used to be the only trigger."
  [state]
  (let [host (:host state)]
    (when-not (get-in state [:players host])
      ;; Never a bot: a bot host could not start the game or advance a phase,
      ;; and the room would be waiting on a seat with nobody behind it.
      (when-let [heir (first (sort (remove #(get-in state [:players % :bot?])
                                           (keys (:players state)))))]
        [{:type :host/changed :host heir}]))))

(defn- settle-host!
  "Run `succession` against the room as it now stands. Must run under the lock."
  [ctx room-id]
  (let [r (room-of ctx room-id)]
    (when-let [heir-events (succession (:state r))]
      (apply-server-events! ctx r heir-events))))

(def ^:private max-wrong-passwords 5)
(def ^:private attempt-window-ms 60000)

(defn- recent [stamps now]
  (filterv #(< (- now %) attempt-window-ms) stamps))

(defn- throttled?
  "Has this socket guessed wrong too often lately to be heard at all?

   Friction, not a fortress. A socket is cheap to replace, so this stops a
   tap-happy guesser and the casual script, not a determined attacker — against
   whom the real defence is that this server is for a group of friends. Per
   socket rather than per room on purpose: a per-room limit would let anybody
   lock the actual guests out by guessing badly on purpose."
  [ctx sink now]
  (>= (count (recent (get @(:attempts ctx) sink) now)) max-wrong-passwords))

(defn- note-wrong-password!
  "Count one wrong guess, and drop every stamp that has aged out while at it, so
   the map holds a minute's worth of failures and never grows past that."
  [ctx sink now]
  (swap! (:attempts ctx)
         (fn [m]
           (-> (into {} (keep (fn [[k v]] (let [v' (recent v now)] (when (seq v') [k v'])))) m)
               (update sink (fnil conj []) now)))))

(defn forget-attempts!
  "A socket closed; nothing it guessed is worth remembering."
  [ctx sink]
  (swap! (:attempts ctx) dissoc sink))

(defn- seat!
  "The engine's half of joining. Must run under the lock."
  [ctx r room-id name locale]
  (let [;; The engine's rule, not a copy: see events/next-seat for why a seat
        ;; number is never recycled.
        pid (engine/next-seat (:state r))
        res (engine/execute (:state r) {:type :join :player pid
                                        :name name :locale locale})]
    (if (engine/error? res)
      res
      (do (commit! ctx r (:events res) (:state res))
          (settle-host! ctx room-id)
          {:room-id room-id :player-id pid}))))

(defn join!
  "Seat a new player. Returns {:room-id .. :player-id n} or {:error ..}.

   A locked room wants its password first, before anything else is said about
   it — whether it is full or already playing is the business of people who
   know the word. `:password-required` when none came, `:wrong-password` when
   it did not match, and `:too-many-attempts` once `sink` has guessed wrong too
   often in the last minute. Neither error carries a detail: nothing the client
   sent is ever echoed back.

   The guess is checked OUTSIDE the lock, against the hash as it was a moment
   ago. A check costs tens of milliseconds by design, and holding the room's
   lock through it would let one guesser stall every other join, command and
   chat line at that table. If the host changed the password in between, the
   guess is checked again against the new one under the lock — rare enough not
   to matter, and it keeps the answer exact.

   Resuming a seat never comes through here: the reconnect token proves the
   seat, so a player who was let in once is not asked again."
  [ctx room-id {:keys [name locale password sink now-ms]}]
  (let [now   (or now-ms (System/currentTimeMillis))
        h0    (:password-hash (state-of ctx room-id))
        ok0?  (and h0 (not (throttled? ctx sink now)) (password/verify password h0))]
    (with-room ctx room-id
      (fn [r]
        (let [h (:password-hash (:state r))]
          (cond
            (nil? h) (seat! ctx r room-id name locale)
            ;; Before the guess is even looked at, or a socket past its limit
            ;; could still learn which of its guesses was right.
            (throttled? ctx sink now) {:error :too-many-attempts}
            ;; Not counted: this is the first step of joining a locked room,
            ;; not a guess — the client learns here that it needs to ask.
            (password/blank? password) {:error :password-required}
            (not (if (= h h0) ok0? (password/verify password h)))
            (do (note-wrong-password! ctx sink now)
                {:error :wrong-password})
            :else (seat! ctx r room-id name locale)))))))

(defn set-password!
  "Lock the room, change its password, or open it again (a blank or nil
   `password`). Returns {:ok? true} or {:error ..}.

   The host's alone, and only in the lobby: nobody can join a game in progress,
   so a lock there could only ever be noise in the Chronik. Asked of the room
   layer rather than the engine because the engine must never see a plaintext
   password — this hashes it and records the result as a server-authored event,
   the same path succession takes.

   Hashed outside the lock for the same reason `join!` checks outside it, and
   only after a cheap look says the asker is the host, so a guest tapping the
   button cannot make the server do the slow part at all. The look is repeated
   under the lock, where it counts."
  [ctx room-id pid password]
  (let [host? #(= pid (:host %))]
    (if-not (some-> (state-of ctx room-id) host?)
      (if (state-of ctx room-id) {:error :not-host} {:error :no-such-room})
      (let [h (when-not (password/blank? password) (password/hash-password password))]
        (with-room ctx room-id
          (fn [r]
            (let [st (:state r)]
              (cond
                (not (host? st))                {:error :not-host}
                (not= :lobby (:phase st))       {:error :not-allowed}
                ;; Opening an open door: nothing happened, so nothing is logged.
                (and (nil? h) (not (contains? st :password-hash))) {:ok? true}
                :else
                (do (apply-server-events! ctx r [(if h
                                                   {:type :room/password-set :hash h}
                                                   {:type :room/password-cleared})])
                    {:ok? true})))))))))

(defn set-voice!
  "Record that a socket's page joined or left the voice call, and repaint the
   room if that changed who is in it. Returns {:ok? true} or {:error ..}.

   Taken on the client's word, and that is fine: it decides nothing. The worst a
   lying client can do is show a headset on its own card."
  [ctx sink joined?]
  (if-let [[room-id _] (get @(:index ctx) sink)]
    (with-room ctx room-id
      (fn [r]
        (let [r' (update r :voice (fnil (if joined? conj disj) #{}) sink)]
          (update-room! ctx room-id assoc :voice (:voice r'))
          (when (not= (in-voice r) (in-voice r'))
            (broadcast! ctx (room-of ctx room-id)))
          {:ok? true})))
    {:error :not-in-room}))

(defn seated?
  "Is `pid` a player in this room? The question a reconnect token answers."
  [ctx room-id pid]
  (boolean (get-in (state-of ctx room-id) [:players pid])))

(defn whereis
  "Which [room-id player-id] a socket is bound to, or nil.

   The registry's own index is the single source of truth for this, so the
   transport keeps no per-connection state of its own and the two cannot drift."
  [ctx sink]
  (get @(:index ctx) sink))

;; ------------------------------------------------------------ attach/detach ---

(defn- visible-chat
  "The backlog `pid` was entitled to see, evaluated as of when each message was
   sent. Recipients are recorded at send time rather than recomputed now, so
   dying does not retroactively grant a player the wolves' whispers and does not
   retroactively take away their own."
  [r pid]
  (into [] (comp (filter #(contains? (:to %) pid))
                 (map #(dissoc % :to)))
        (:chat r)))

(defn attach!
  "Bind a socket to a seated player. Idempotent per sink; a player may have two
   tabs open and both get their own copy of every view."
  [ctx room-id pid sink]
  (with-room ctx room-id
    (fn [r]
      (if-not (get-in (:state r) [:players pid])
        {:error :unknown-player}
        (let [first-socket? (empty? (get-in r [:sinks pid]))
              r' (update-in r [:sinks pid] (fnil conj #{}) sink)]
          (update-room! ctx room-id
                        #(-> %
                             (assoc-in [:sinks pid] (get-in r' [:sinks pid]))
                             (assoc :last-activity (System/currentTimeMillis))))
          (swap! (:index ctx) assoc sink [room-id pid])
          (deliver! ctx sink {:type :room/chat-history :messages (visible-chat r' pid)})
          (if (and first-socket? (not (get-in (:state r) [:players pid :connected?])))
            ;; Reconnection is public: everyone else has been staring at an
            ;; "abwesend" marker waiting for this player.
            ;; `:at` on both directions, for the record; the engine keeps only
            ;; a disconnect's, to measure the absence by.
            (apply-server-events! ctx (room-of ctx room-id)
                                  [{:type :player/connection :player pid :connected? true
                                    :at (System/currentTimeMillis)}])
            ;; Otherwise nothing changed for anyone else — just paint this tab.
            (send-view! ctx (room-of ctx room-id) pid))
          {:room-id room-id :player-id pid})))))

(defn detach!
  "A socket closed. Marks the player disconnected once their LAST socket is
   gone — closing one of two tabs is not an absence.

   Disconnected is never dead: the engine keeps the seat, the role and the vote
   deadline. That is what makes a dropped connection survivable.

   The socket's index entry is claimed in one step, so of two calls for the
   same socket only one goes on. There are two: `on-error` and `on-close` both
   fire, and the protocol detaches a socket it finds closed right after
   attaching it, which can meet the close handler doing the same (see
   werwolf.protocol/attach!). Read-then-remove let both through, and the table
   was told twice that the player had gone."
  [ctx sink]
  (when-let [[room-id pid] (get (first (swap-vals! (:index ctx) dissoc sink)) sink)]
    (with-room ctx room-id
      (fn [r]
        (let [remaining (disj (get-in r [:sinks pid] #{}) sink)]
          (update-room! ctx room-id
                        #(-> %
                             (assoc-in [:sinks pid] remaining)
                             (update :voice (fnil disj #{}) sink)
                             (assoc :last-activity (System/currentTimeMillis))))
          (cond
            (empty? remaining)
            ;; Stamped, so the clock can tell a phone that blinked from a seat
            ;; nobody is coming back to — see timer/absence-events.
            (apply-server-events! ctx (room-of ctx room-id)
                                  [{:type :player/connection :player pid :connected? false
                                    :at (System/currentTimeMillis)}])

            ;; A closed tab that was the one in the call.
            (not= (in-voice r) (in-voice (room-of ctx room-id)))
            (broadcast! ctx (room-of ctx room-id)))
          {:room-id room-id :player-id pid})))))

(defn- release-seat!
  "Unbind every socket of a player who has just LEFT. Must run under the lock.

   `detach!` is the socket closing; this is the player walking out with the page
   still open, and until it existed nothing on that path touched the index. The
   page that had just left stayed bound to the seat it gave up, so the server
   refused its next create or join as :already-in-room until it was reloaded —
   the 'it says I'm already in a round' report.

   Every socket of the seat, not just the one that asked: a second tab at the
   same seat would otherwise stay bound to a seat that, in the lobby, no longer
   exists. Each is told with `:server/left` so that tab does not sit on a frozen
   room; the tab that asked has already gone, and ignores it.

   No connection event. Mid-game `:player/left` already marks the seat absent —
   the role still matters, so it is kept — and an absent seat holding sockets
   would be a contradiction that the eventual close would then announce a second
   time. With none left, taking the seat back with its token is a reconnect like
   any other.

   `msg-type` is what the sockets are told: `:server/left`, or `:server/kicked`
   when the host removed the seat — the release is the same either way, but
   only one of them is news to the player."
  [ctx room-id pid msg-type]
  (let [sinks (get-in (room-of ctx room-id) [:sinks pid])
        voiced? (contains? (in-voice (room-of ctx room-id)) pid)]
    (update-room! ctx room-id
                  (fn [rm] (-> rm
                               (update :sinks dissoc pid)
                               (update :voice #(reduce disj (or % #{}) sinks))
                               (assoc :last-activity (System/currentTimeMillis)))))
    (swap! (:index ctx)
           (fn [ix] (reduce (fn [m s] (cond-> m (= [room-id pid] (get m s)) (dissoc s)))
                            ix sinks)))
    (doseq [sink sinks]
      (deliver! ctx sink {:type msg-type :room-id room-id}))
    ;; The commit announcing the departure went out while these sockets still
    ;; counted as in the call. Mid-game the seat stays on the board, so without
    ;; a repaint it would keep a headset nobody is behind.
    (when voiced?
      (broadcast! ctx (room-of ctx room-id)))))

;; ---------------------------------------------------------------- commands ---

(defn submit!
  "Run a player command. Returns {:ok? true} or {:error code :detail d}.

   `pid` comes from the verified token, never from the payload: the `:player`
   key is overwritten before the engine sees it, so a hand-crafted frame cannot
   act as somebody else. This is the one line that makes impersonation
   impossible rather than merely unlikely.

   `:activity? false` is for the bots: a bot moving is not somebody being
   there, and counting it would keep a table of bots alive forever — see
   `commit!` for the clock that once did the same."
  ([ctx room-id pid cmd] (submit! ctx room-id pid cmd {}))
  ([ctx room-id pid cmd {:keys [activity?] :or {activity? true}}]
  (with-room ctx room-id
    (fn [r]
      (if-not (get-in (:state r) [:players pid])
        {:error :unknown-player}
        (let [res (engine/execute (:state r) (assoc cmd :player pid))]
          (if (engine/error? res)
            res
            (do (commit! ctx r (:events res) (:state res) activity?)
                (settle-host! ctx room-id)
                (when (= :leave (:type cmd))
                  (release-seat! ctx room-id pid :server/left))
                ;; The seat the host removed, not the host's own: its pages are
                ;; holding a seat that no longer exists, and in the call with it.
                ;; The commit above already left them out of the broadcast, since
                ;; it only reaches seated players.
                (when (= :gm/kick (:type cmd))
                  (release-seat! ctx room-id (:target cmd) :server/kicked))
                {:ok? true}))))))))

;; -------------------------------------------------------------- scheduling ---
;;
;; Notes the bots' scheduler keeps on the room map: when the phase began and
;; when each bot is due. On the room map and never in state or the log,
;; because when a bot moves is this server's business and what it does is the
;; game's. See werwolf.bots.

(defn scheduling
  "What the scheduler keeps and needs: which phase it last saw and when that
   began, which bot is due when, and the event sequence the next decision is
   seeded from."
  [ctx room-id]
  (select-keys (room-of ctx room-id) [:phase :phase-since :bots :seq]))

(defn schedule!
  "Replace the scheduler's notes: the phase it saw, when that began, and the
   bots' turns (pid -> {:offers set :at epoch-ms})."
  [ctx room-id notes]
  (update-room! ctx room-id merge (select-keys notes [:phase :phase-since :bots])))

(defn reschedule!
  "Move one bot's turn. One seat's entry rather than the whole map, because
   this is written from the tick while a socket's commit may be re-arming the
   others under the lock.

   A seat that is no longer armed is left alone. That commit is usually why
   the bot's move was refused in the first place, and returning nil for it
   once replaced the whole room with nil mid-game."
  [ctx room-id pid at]
  (update-room! ctx room-id
                #(cond-> % (get-in % [:bots pid]) (assoc-in [:bots pid :at] at))))

;; ------------------------------------------------------------------- recap ---
;;
;; The Rückblick: every card and every night's doings, for the whole table, once
;; the game is decided. The engine builds it (werwolf.engine.recap); this only
;; decides when, and keeps it.
;;
;; From the store rather than from memory, because the room holds state and not
;; events — and the private half of the story (the Seherin's looks, the pack's
;; ballots) was never in state's public `:log` to begin with. Read once, inside
;; the lock and after the append, so the log it reads ends with the game's end.

(defn- remember-recap!
  "Build the recap the first time the room is found at :game-over, and forget it
   whenever it is not — a Spielleiter who sets the phase back has a game in
   progress again, and an old recap must not outlive the ending it told.

   Cached even when the builder came back empty, so a log that will not fold
   into a finished game is read once rather than on every commit. And never
   allowed to stop the commit it hangs off: the game has already ended, and a
   bug in telling the story must not keep the table from seeing that it did."
  [ctx room-id]
  (let [rm (room-of ctx room-id)]
    (cond
      (not= :game-over (:phase (:state rm)))
      (when (contains? rm :recap)
        (update-room! ctx room-id dissoc :recap :stats))

      ;; The table's record across its games rides along: the same log, read
      ;; the same once, at the same moment.
      (not (contains? rm :recap))
      (let [evs (store/load-events (:ds ctx) room-id)
            safe (fn [f what]
                   (try (f evs)
                        (catch Exception ex
                          (log/warn ex "could not build the" what "for" room-id)
                          nil)))
            rc (safe recap/recap "recap")
            st (safe stats/stats "stats")]
        (update-room! ctx room-id assoc :recap rc :stats st)))))

(defn- with-recap
  "Attach the cached recap to a view — at :game-over and never before. The
   phase is checked here as well as when caching: this is the line that puts it
   on a socket, so it is the one that has to be right."
  [view r]
  (let [over? (= :game-over (:phase (:state r)))]
    (cond-> view
      (and over? (:recap r)) (assoc :recap (:recap r))
      ;; From the second game on: after one, the recap already says it all.
      (and over? (< 1 (get-in r [:stats :games] 0))) (assoc :stats (:stats r)))))

;; -------------------------------------------------------------------- chat ---

(defn chat-audience
  "Who may hear `pid` right now — derived from state, never from the client.

   * dead players talk only to the dead, so a ghost cannot feed the living;
   * at night only the wolves have a channel at all, and only to each other;
   * otherwise chat is public, and the dead hear it too (they are spectators,
     not exiles).

   The Spielleiter is outside all three. He hears every channel — the wolves'
   whispers and the dead's asides are exactly what he is narrating — and
   whatever he says reaches the whole table, at any hour, as a narrator's voice
   does. Neither direction can leak: he already knows every card, and what he
   says is his choice to say."
  [state pid]
  (let [players  (:players state)
        narrator (engine/narrator state)
        alive?   #(get-in players [% :alive?])
        ;; Added to the private channels' audiences, never to a public one,
        ;; which already includes him.
        +sl      #(cond-> % narrator (conj narrator))]
    (cond
      ;; Outside the lobby only: before the game there are no channels to stand
      ;; outside of, and his lines should read like everybody else's.
      (and narrator (= pid narrator) (not= :lobby (:phase state)))
      {:channel :narrator :to (set (keys players))}

      (not (alive? pid))
      {:channel :dead
       :to (+sl (set (remove alive? (keys players))))}

      (= :night (:phase state))
      (if (= :werewolf (engine/team-of-player state pid))
        {:channel :wolves
         :to (+sl (set (filter #(and (alive? %) (= :werewolf (engine/team-of-player state %)))
                               (keys players))))}
        ;; Unreachable while `legal-actions` gates chat, and deliberately a dead
        ;; drop rather than a fallback to :all if that ever changes.
        {:channel :none :to #{}})

      :else
      {:channel :all :to (set (keys players))})))

(defn chat!
  "Relay a chat message to its audience. Not logged: chat is not game state.

   Permission is the engine's answer (`:chat` in `legal-actions`), so the
   phases where silence is part of the game stay silent."
  [ctx room-id pid text]
  (with-room ctx room-id
    (fn [r]
      (let [state (:state r)
            text' (some-> text str str/trim)]
        (cond
          (not (get-in state [:players pid])) {:error :unknown-player}
          (str/blank? (str text'))            {:error :empty-message}
          (not (contains? (engine/legal-actions state pid) :chat))
          {:error :chat-not-allowed :detail {:phase (:phase state)}}

          :else
          (let [{:keys [channel to]} (chat-audience state pid)
                msg {:type    :room/chat
                     :channel channel
                     :from    pid
                     :name    (get-in state [:players pid :name])
                     :text    (subs text' 0 (min (count text') max-chat-text))
                     :at      (System/currentTimeMillis)}]
            (update-room! ctx room-id update :chat
                          (fn [log]
                            (let [log' (conj (vec log) (assoc msg :to to))]
                              (vec (take-last (:max-chat ctx) log')))))
            (doseq [rid to, sink (get-in r [:sinks rid])]
              (deliver! ctx sink msg))
            {:ok? true :channel channel :recipients to}))))))

;; ------------------------------------------------------------- lifecycle ---

(def ^:const default-lobby-idle-ms
  "How long a room with NOBODY CONNECTED may sit before it is dropped.

   Aimed at the lobby: a room somebody opened, never filled and closed the tab on
   is otherwise listed to every newcomer forever, and each one holds a timer and a
   slice of memory. Five minutes is long enough to make a coffee between opening a
   room and telling people the code."
  (* 5 60 1000))

(def ^:const default-game-idle-ms
  "The same, for a game already in progress — deliberately much longer.

   A phone that locks its screen drops the WebSocket, so a table that all put
   their phones down for a few minutes looks exactly like an abandoned game. The
   log means such a room is genuinely resumable, and destroying one mid-evening is
   far worse than keeping a dead one in memory for half an hour."
  (* 30 60 1000))

(def ^:const default-abandon-after-hours
  "How long an unfinished game may go without a player before it is closed for
   good. Dropping it from memory is not enough: it comes back on every boot, and
   one was found still being reloaded two months after anybody last played it.

   A day: long past any evening that might yet be resumed, and comfortably
   longer than a server that was down overnight."
  24)

(defn abandoned?
  "Has nobody been back to this unfinished room for so long that nobody will?

   `last-active` is the last thing a PLAYER did — the server's own writes, the
   clock above all, say nothing about whether anyone is coming back. Callers
   check first that nobody is there now.

   A lobby goes at the same threshold the sweep drops it at. Nothing has been
   dealt, so nothing is lost, and once swept it cannot be joined anyway: bringing
   it back on the next boot only resurrected a room for the sweep to drop again."
  [game-cfg now-ms phase last-active]
  (let [{:keys [room-idle-ms abandon-after-hours]} game-cfg
        limit (if (= :lobby phase)
                (or room-idle-ms default-lobby-idle-ms)
                (* 60 60 1000 (or abandon-after-hours default-abandon-after-hours)))]
    (> (- now-ms last-active) limit)))

(declare forget-room!)

(defn- retire!
  "Close an abandoned room for good, and forget it.

   Ended in the log rather than only marked finished in the rooms table: the
   log is the truth, and a game that replayed to a night nobody ever finished
   would contradict the table that says it is over. Ending it is what marks it
   finished, through the ordinary commit — and keeps it on disk for a recap."
  [ctx room-id]
  (with-room ctx room-id
    (fn [r] (apply-server-events! ctx r [{:type :game/abandoned}])))
  (forget-room! ctx room-id))

(defn- idle-text [now-ms last-active phase]
  (str "(idle " (quot (- now-ms last-active) 60000) " min, phase " phase ")"))

(defn rehydrate!
  "Load unfinished games from the log on boot. Returns the room ids restored.

   Everyone comes back marked disconnected, and that fact is appended to the
   log rather than patched into memory: the server really did drop every
   socket, so the log would be lying if it said otherwise — and `(= (replay
   log) state)` has to keep holding.

   A room nobody was in and nobody has touched for too long is retired instead
   — see `abandoned?`. Merely sweeping it was not enough: every boot loaded it
   again."
  ([ctx] (rehydrate! ctx (System/currentTimeMillis)))
  ([ctx now-ms]
   (into []
         (keep
          (fn [room-id]
            (when-let [{:keys [state active-at] n :seq} (store/load-game (:ds ctx) room-id)]
              (let [phase    (:phase state)
                    ;; Somebody still seated when the process died was left by
                    ;; the server, not the other way round: the boot is their
                    ;; last activity, so a deploy cannot close the room they were
                    ;; sitting in. Otherwise a boot is NOT activity — a server
                    ;; restarting more often than the threshold would never
                    ;; retire, or even sweep, anything.
                    present? (some :connected? (vals (:players state)))
                    last-active (if present? now-ms (or active-at now-ms))]
                (swap! (:rooms ctx) assoc room-id
                       (assoc (fresh-room room-id)
                              :state state :seq n :last-activity last-active))
                (if (and (not present?)
                         (abandoned? (:game ctx) now-ms phase last-active))
                  (do (log/info "retiring abandoned room" room-id
                                (idle-text now-ms last-active phase))
                      (retire! ctx room-id)
                      nil)
                  ;; Stamped with the boot, not with whenever the old process
                  ;; died: time nobody could play through is not time, so an
                  ;; absence is measured from the moment they could have come back.
                  (let [drops (for [[pid p] (sort-by key (:players state))
                                    :when (:connected? p)]
                                {:type :player/connection :player pid
                                 :connected? false :at now-ms})]
                    (with-room ctx room-id
                      (fn [r] (apply-server-events! ctx r (vec drops))))
                    room-id))))))
         (store/unfinished-room-ids (:ds ctx)))))

(defn sweep-idle!
  "Drop rooms nobody is connected to and nothing has happened in.

   Returns the room ids dropped. The log stays on disk — `forget-room!` only
   forgets, so a swept game can still be recapped and, if it is unfinished, will
   be rehydrated on the next boot. Unless it has been `abandoned?`: then it is
   retired, which closes it for good.

   Two conditions, both required. Idle alone is not enough: a table can sit in
   silence for a while and still be sitting there. What makes a room abandoned is
   that NO socket is attached to it."
  ([ctx now-ms] (sweep-idle! ctx now-ms (:game ctx)))
  ([ctx now-ms {:keys [room-idle-ms game-idle-ms] :as game-cfg}]
   (let [lobby-limit (or room-idle-ms default-lobby-idle-ms)
         game-limit  (or game-idle-ms default-game-idle-ms)]
     (into []
           (keep (fn [[room-id r]]
                   (let [connected? (boolean (seq (mapcat val (:sinks r))))
                         phase      (:phase (:state r))
                         limit      (if (#{:lobby :game-over nil} phase)
                                      lobby-limit
                                      game-limit)
                         last-active (:last-activity r 0)
                         idle       (- now-ms last-active)]
                     (when (and (not connected?) (> idle limit))
                       (if (and phase (not= :game-over phase)
                                (abandoned? game-cfg now-ms phase last-active))
                         (do (log/info "retiring abandoned room" room-id
                                       (idle-text now-ms last-active phase))
                             (retire! ctx room-id))
                         (do (log/info "dropping idle room" room-id
                                       (idle-text now-ms last-active phase))
                             (forget-room! ctx room-id)))
                       room-id))))
           (rooms ctx)))))

(defn forget-room!
  "Drop a room from memory. The log stays on disk — a finished game is still
   worth a recap."
  [ctx room-id]
  (when-let [r (room-of ctx room-id)]
    (swap! (:index ctx) #(apply dissoc % (mapcat val (:sinks r))))
    (swap! (:rooms ctx) dissoc room-id)
    true))
