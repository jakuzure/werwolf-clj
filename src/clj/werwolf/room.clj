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

   `after-commit` is called as (f ctx room-id) at the end of every committed
   change, still holding the room's lock. The clock uses it to keep a room's
   deadline in step with its phase without room.clj having to know that timers
   exist. It may itself commit — Java monitors are reentrant — and must
   converge, i.e. eventually make no further change."
  [{:keys [ds send! max-chat after-commit game]}]
  {:ds           ds
   :send!        send!
   :max-chat     (or max-chat 200)
   :after-commit after-commit
   ;; Table-size limits from config. They live here rather than being read at
   ;; the point of use because they are baked into :game/created — a room keeps
   ;; the limits it was opened with, so changing config cannot retroactively
   ;; make a game in progress unstartable.
   :game         (or game {})
   :rooms        (atom {})
   ;; sink -> [room-id player-id], so a socket closing knows who it was
   ;; without the transport having to remember.
   :index        (atom {})})

(defn- rooms [ctx] @(:rooms ctx))
(defn- room-of [ctx room-id] (get (rooms ctx) room-id))

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
   join it. That is right for a private group on a private host, and would want
   revisiting before the server is opened to the public — see the plan's
   deferred 'public lobby browser'.

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
               (when (and (= :lobby (:phase s))
                          (seq (:players s))
                          (< (count (engine/participant-ids s)) (:max-players s 18)))
                 {:room-id     (:room-id s)
                  :players     (count (engine/participant-ids s))
                  :max-players (:max-players s 18)
                  :host        (get-in s [:players (:host s) :name])})))
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
                        :view (assoc (engine/view-for (:state r) pid)
                                     :voice (in-voice r))})))

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
     (store/upsert-room! (:ds ctx) room-id (:phase state')
                         (= :game-over (:phase state')) now)
     (let [r' (-> r
                  (assoc :state state')
                  (update :seq + (count events)))]
       (swap! (:rooms ctx) update room-id merge
              (cond-> (select-keys r' [:state :seq])
                activity? (assoc :last-activity now)))
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

(defn- next-player-id
  "Seat numbers only ever go up; a departed player's seat is never recycled.

   Recycling would be a security bug, not a tidiness question: reconnect tokens
   name a seat, so handing seat 3 to a newcomer would let the previous
   occupant's token drive them.

   Counted from every seat ever ISSUED, which the public log remembers, rather
   than from the seats still taken. Counting the taken ones recycled exactly the
   seat that mattered: the newest player leaving handed their number — and their
   token — to the next arrival, and an emptied lobby started again at 0. The log
   is part of state, so this survives a restart without a counter of its own."
  [state]
  (let [ids (concat (keys (:players state))
                    (keep #(when (= :player/joined (:type %)) (:player %))
                          (:log state)))]
    (if (seq ids) (inc (apply max ids)) 0)))

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

(defn- reserve-code!
  "Claim an unused room code atomically. Checks memory and disk: a finished
   game stays on disk for recaps, and reusing its code would splice two games
   into one log."
  [ctx]
  (loop [attempt 0]
    (when (> attempt 200)
      (throw (ex-info "could not allocate a free room code" {:attempts attempt})))
    (let [code (random-code)]
      (if (store/room-exists? (:ds ctx) code)
        (recur (inc attempt))
        (let [[old _] (swap-vals! (:rooms ctx)
                                  (fn [m] (if (contains? m code)
                                            m
                                            (assoc m code (fresh-room code)))))]
          (if (contains? old code)
            (recur (inc attempt))
            code))))))

(def ^:private absolute-min-players
  "The smallest table the role composition produces a real game for: one wolf,
   a Seherin and a villager. Below that a 'game' is decided before it starts,
   so a misconfigured MIN_PLAYERS is clamped rather than obeyed.

   Taken from the engine, which is where the deck lives. This was a literal 3
   here and another one in the lobby's start rule, and two copies of a number
   are two places to change it."
  engine/absolute-min-players)

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
  [ctx {:keys [name locale min-players max-players seed narrated?]}]
  (let [room-id (reserve-code! ctx)
        cfg (:game ctx)
        min* (max absolute-min-players
                  (or min-players (:min-players cfg) 5))
        max* (max min* (or max-players (:max-players cfg) 18))
        first-kill? (boolean (:first-night-kill? cfg))
        narrated?* (boolean (if (some? narrated?) narrated? (get cfg :narrated? true)))]
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
            (do (commit! ctx r (into [genesis] (:events joined)) (:state joined))
                {:room-id room-id :player-id 0})))))))

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
      (when-let [heir (first (sort (keys (:players state))))]
        [{:type :host/changed :host heir}]))))

(defn- settle-host!
  "Run `succession` against the room as it now stands. Must run under the lock."
  [ctx room-id]
  (let [r (room-of ctx room-id)]
    (when-let [heir-events (succession (:state r))]
      (apply-server-events! ctx r heir-events))))

(defn join!
  "Seat a new player. Returns {:room-id .. :player-id n} or {:error ..}."
  [ctx room-id {:keys [name locale]}]
  (with-room ctx room-id
    (fn [r]
      (let [pid (next-player-id (:state r))
            res (engine/execute (:state r) {:type :join :player pid
                                            :name name :locale locale})]
        (if (engine/error? res)
          res
          (do (commit! ctx r (:events res) (:state res))
              (settle-host! ctx room-id)
              {:room-id room-id :player-id pid}))))))

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
          (swap! (:rooms ctx) update room-id #(when % (assoc % :voice (:voice r'))))
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
          (swap! (:rooms ctx) update room-id
                 (fn [rm] (when rm (-> rm
                                       (assoc-in [:sinks pid] (get-in r' [:sinks pid]))
                                       (assoc :last-activity (System/currentTimeMillis))))))
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
   deadline. That is what makes a dropped connection survivable."
  [ctx sink]
  (when-let [[room-id pid] (get @(:index ctx) sink)]
    (swap! (:index ctx) dissoc sink)
    (with-room ctx room-id
      (fn [r]
        (let [remaining (disj (get-in r [:sinks pid] #{}) sink)]
          (swap! (:rooms ctx) update room-id
                 (fn [rm] (when rm (-> rm
                                       (assoc-in [:sinks pid] remaining)
                                       (update :voice (fnil disj #{}) sink)
                                       (assoc :last-activity (System/currentTimeMillis))))))
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
   any other."
  [ctx room-id pid]
  (let [sinks (get-in (room-of ctx room-id) [:sinks pid])
        voiced? (contains? (in-voice (room-of ctx room-id)) pid)]
    (swap! (:rooms ctx) update room-id
           (fn [rm] (when rm (-> rm
                                 (update :sinks dissoc pid)
                                 (update :voice #(reduce disj (or % #{}) sinks))
                                 (assoc :last-activity (System/currentTimeMillis))))))
    (swap! (:index ctx)
           (fn [ix] (reduce (fn [m s] (cond-> m (= [room-id pid] (get m s)) (dissoc s)))
                            ix sinks)))
    (doseq [sink sinks]
      (deliver! ctx sink {:type :server/left :room-id room-id}))
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
   impossible rather than merely unlikely."
  [ctx room-id pid cmd]
  (with-room ctx room-id
    (fn [r]
      (if-not (get-in (:state r) [:players pid])
        {:error :unknown-player}
        (let [res (engine/execute (:state r) (assoc cmd :player pid))]
          (if (engine/error? res)
            res
            (do (commit! ctx r (:events res) (:state res))
                (settle-host! ctx room-id)
                (when (= :leave (:type cmd))
                  (release-seat! ctx room-id pid))
                {:ok? true})))))))

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
            (swap! (:rooms ctx) update-in [room-id :chat]
                   (fn [log]
                     (let [log' (conj (vec log) (assoc msg :to to))]
                       (vec (take-last (:max-chat ctx) log')))))
            (doseq [rid to, sink (get-in r [:sinks rid])]
              (deliver! ctx sink msg))
            {:ok? true :channel channel :recipients to}))))))

;; ------------------------------------------------------------- lifecycle ---

(defn rehydrate!
  "Load unfinished games from the log on boot. Returns the room ids restored.

   Everyone comes back marked disconnected, and that fact is appended to the
   log rather than patched into memory: the server really did drop every
   socket, so the log would be lying if it said otherwise — and `(= (replay
   log) state)` has to keep holding."
  [ctx]
  (let [ids (store/unfinished-room-ids (:ds ctx))]
    (doseq [room-id ids]
      (when-let [{:keys [state updated-at] n :seq} (store/load-game (:ds ctx) room-id)]
        (swap! (:rooms ctx) assoc room-id
               (assoc (fresh-room room-id) :state state :seq n))
        ;; Stamped with the boot, not with whenever the old process died: time
        ;; nobody could play through is not time, so an absence is measured
        ;; from the moment they could have come back.
        (let [now   (System/currentTimeMillis)
              drops (for [[pid p] (sort-by key (:players state))
                          :when (:connected? p)]
                      {:type :player/connection :player pid :connected? false :at now})]
          (with-room ctx room-id
            (fn [r] (apply-server-events! ctx r (vec drops)))))
        ;; AFTER the drops, deliberately. `fresh-room` stamps :last-activity with
        ;; now and appending the drops stamps it again, so a room abandoned weeks
        ;; ago would look brand new to `sweep-idle!` and get a fresh five minutes
        ;; on EVERY boot — restart often enough and it is never swept at all. A
        ;; boot is not activity; the last write to the log is.
        ;;
        ;; Read before the drops are appended, since appending moves updated_at.
        ;; A server that restarts repeatedly therefore measures from its previous
        ;; boot rather than from the real last play, which is the right way to be
        ;; wrong: it only ever delays a sweep.
        (when updated-at
          (swap! (:rooms ctx) update room-id assoc :last-activity updated-at))))
    (vec ids)))

;; Defined below; sweep-idle! is stated first because it is the interesting one.
(declare forget-room!)

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

(defn sweep-idle!
  "Drop rooms nobody is connected to and nothing has happened in.

   Returns the room ids dropped. The log stays on disk — `forget-room!` only
   forgets, so a swept game can still be recapped and, if it was unfinished, will
   be rehydrated on the next boot.

   Two conditions, both required. Idle alone is not enough: a table can sit in
   silence for a while and still be sitting there. What makes a room abandoned is
   that NO socket is attached to it."
  ([ctx now-ms] (sweep-idle! ctx now-ms (:game ctx)))
  ([ctx now-ms {:keys [room-idle-ms game-idle-ms]}]
   (let [lobby-limit (or room-idle-ms default-lobby-idle-ms)
         game-limit  (or game-idle-ms default-game-idle-ms)]
     (into []
           (keep (fn [[room-id r]]
                   (let [connected? (boolean (seq (mapcat val (:sinks r))))
                         phase      (:phase (:state r))
                         limit      (if (#{:lobby :game-over nil} phase)
                                      lobby-limit
                                      game-limit)
                         idle       (- now-ms (:last-activity r 0))]
                     (when (and (not connected?) (> idle limit))
                       (log/info "dropping idle room" room-id
                                 (str "(" (quot idle 1000) "s, phase " phase ")"))
                       (forget-room! ctx room-id)
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
