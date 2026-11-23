(ns werwolf.ws
  "Client WebSocket transport: transit framing, a re-frame effect for sending,
   reconnect with exponential backoff, and the seat token in localStorage.

   The token lives here rather than in app-db because it must survive a page
   reload, and because it is the one piece of client state the server actually
   trusts. Everything else the client holds is a copy of what the server sent.

   This namespace never interprets game state. It moves bytes and re-establishes
   identity; the server is authoritative about everything else."
  (:require [cljs.reader]
            [cognitect.transit :as transit]
            [re-frame.core :as rf]))

(def ^:private reader (transit/reader :json))
(def ^:private writer (transit/writer :json))

(defonce ^:private socket (atom nil))
(defonce ^:private url* (atom nil))
(defonce ^:private retry (atom 0))
(defonce ^:private outbox (atom []))
;; When the server last said anything, and when we last asked it to. See check!.
(defonce ^:private heard (atom 0))
(defonce ^:private asked (atom 0))
(defonce ^:private reconnect-timer (atom nil))
(defonce ^:private probe-timer (atom nil))
;; A socket given up on, closed only once its successor is open; see reconnect-now!.
(defonce ^:private retired (atom nil))
(defonce ^:private installed? (atom false))

(def ^:private ping-ms
  "How often an open socket is asked whether it is alive."
  15000)

(def ^:private answer-ms
  "How long a ping may wait for its answer before the socket counts as dead."
  10000)

(def ^:private wake-answer-ms
  "The same, right after the page comes back to the foreground, where the
   player is looking at it and every second of an old screen counts."
  3000)

(defn- now-ms [] (.getTime (js/Date.)))

(def ^:private max-backoff-ms 15000)
(def ^:private storage-key "werwolf/seat")

(defn- backoff-ms [n]
  (min max-backoff-ms (* 500 (js/Math.pow 2 n))))

(defn- open? [] (some-> @socket .-readyState (= js/WebSocket.OPEN)))

;; ------------------------------------------------------------------- seat ---

(defn save-seat!
  "Remember which seat this browser holds. Written on every welcome, because a
   resume hands back a refreshed token and keeping the old one would mean the
   session still expires at the original time."
  [{:keys [room-id token]}]
  (when (and room-id token)
    (.setItem js/localStorage storage-key (pr-str {:room-id room-id :token token}))))

(defn seat
  "The stored seat, or nil. Read defensively: a half-written or stale value must
   land the player on the landing screen, never on a blank page."
  []
  (try
    (some-> (.getItem js/localStorage storage-key)
            cljs.reader/read-string
            (select-keys [:room-id :token])
            not-empty)
    (catch :default _ nil)))

(defn forget-seat! []
  (.removeItem js/localStorage storage-key))

;; --------------------------------------------------------------- transport ---

(defn send!
  "Send a Clojure value. If the socket is not open yet, queue it and flush on
   open — otherwise the first command after a reconnect race is simply lost."
  [value]
  (if (open?)
    (.send @socket (transit/write writer value))
    (swap! outbox conj value)))

(defn- flush-outbox! []
  (let [queued @outbox]
    (reset! outbox [])
    (doseq [v queued] (send! v))))

(declare connect!)

(defn- schedule-reconnect! []
  (let [n @retry
        ms (backoff-ms n)]
    (swap! retry inc)
    (reset! reconnect-timer (js/setTimeout #(do (reset! reconnect-timer nil)
                                                (connect! @url*))
                                           ms))))

(defn- reconnect-now!
  "Give up on the socket there is and dial again at once, without the backoff.

   The old socket's handlers go first: a socket whose network has gone can
   take a minute to report its own close, and that late onclose would dial a
   second time behind this one. It is closed only once the new one is open
   and has re-claimed the seat: one that was merely slow to answer would
   otherwise take the seat away for a moment, and the table would see the
   player leave and come back."
  []
  (when-let [t @reconnect-timer] (js/clearTimeout t) (reset! reconnect-timer nil))
  (when-let [^js old @socket]
    (set! (.-onopen old) nil)
    (set! (.-onmessage old) nil)
    (set! (.-onclose old) nil)
    (set! (.-onerror old) nil)
    (reset! retired old))
  (reset! socket nil)
  (reset! retry 0)
  (rf/dispatch [:werwolf.events/disconnected])
  (connect! @url*))

(defn check!
  "Make sure the connection is alive, and reconnect at once if it is not.

   A phone that is locked, or a laptop that sleeps, keeps a socket that looks
   open and is dead, and nothing noticed: the page showed the state from
   before, and when the close came, the backoff waited up to 15 seconds more
   before it dialled again. Now an open socket is asked every `ping-ms`, and a
   ping that goes unanswered for `answer-ms` replaces it. Waking the page up,
   going online and coming back from the back/forward cache ask at once and
   wait only `wake-answer-ms`, and redial a socket already closed without
   waiting out its backoff; the timer alone never does that."
  [{:keys [wake?]}]
  (when @url*
    (let [^js s @socket
          state (some-> s .-readyState)
          t (now-ms)]
      (cond
        ;; Closed: on a wake-up dial now. On the timer leave it to the
        ;; backoff, which restarting here would cut short every 15 seconds
        ;; through an outage, or against a server that is turning sockets away.
        (or (nil? s) (= state js/WebSocket.CLOSED) (= state js/WebSocket.CLOSING))
        (when wake? (reconnect-now!))

        (= state js/WebSocket.OPEN)
        (if (and (> @asked @heard) (> (- t @asked) answer-ms))
          ;; The last ping is still unanswered: the line is dead.
          (reconnect-now!)
          (do (reset! asked t)
              (.send s (transit/write writer {:type :client/ping}))
              (when wake?
                (when-let [p @probe-timer] (js/clearTimeout p))
                (reset! probe-timer
                        (js/setTimeout #(when (and (= s @socket) (< @heard t))
                                          (reconnect-now!))
                                       wake-answer-ms)))))

        ;; CONNECTING: it is already on its way.
        :else nil))))

(defn install!
  "Watch the connection: a ping on a timer, and a check whenever the page
   comes back. Idempotent across hot reloads."
  []
  (when (compare-and-set! installed? false true)
    (js/setInterval #(check! {}) ping-ms)
    (.addEventListener js/document "visibilitychange"
                       #(when (= "visible" (.-visibilityState js/document)) (check! {:wake? true})))
    (.addEventListener js/window "online" #(check! {:wake? true}))
    (.addEventListener js/window "pageshow" #(when (.-persisted %) (check! {:wake? true})))))

(defn connect! [url]
  (reset! url* url)
  (let [sock (js/WebSocket. url)]
    (reset! socket sock)
    (set! (.-onopen sock)
          (fn [_]
            (reset! retry 0)
            (reset! heard (now-ms))
            (rf/dispatch [:werwolf.events/connected])
            ;; Re-claim the seat BEFORE anything queued goes out: a command sent
            ;; on an unbound socket is answered with :not-in-room.
            (when-let [{:keys [room-id token]} (seat)]
              (.send sock (transit/write writer {:type :client/resume
                                                 :room-id room-id
                                                 :token token})))
            (flush-outbox!)
            ;; The seat is claimed on this socket now; the one it replaces may go.
            (when-let [^js r @retired]
              (reset! retired nil)
              (try (.close r) (catch :default _ nil)))))
    (set! (.-onmessage sock)
          (fn [e]
            (reset! heard (now-ms))
            (rf/dispatch [:werwolf.events/server-message
                          (transit/read reader (.-data e))])))
    (set! (.-onclose sock)
          (fn [_]
            (rf/dispatch [:werwolf.events/disconnected])
            (schedule-reconnect!)))
    (set! (.-onerror sock)
          (fn [_] (js/console.error "ws: error")))
    sock))

;; re-frame effects
(rf/reg-fx ::send (fn [value] (send! value)))
(rf/reg-fx ::save-seat (fn [s] (save-seat! s)))
(rf/reg-fx ::forget-seat (fn [_] (forget-seat!)))
