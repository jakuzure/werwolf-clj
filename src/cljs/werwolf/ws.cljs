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
    (js/setTimeout #(connect! @url*) ms)))

(defn connect! [url]
  (reset! url* url)
  (let [sock (js/WebSocket. url)]
    (reset! socket sock)
    (set! (.-onopen sock)
          (fn [_]
            (reset! retry 0)
            (rf/dispatch [:werwolf.events/connected])
            ;; Re-claim the seat BEFORE anything queued goes out: a command sent
            ;; on an unbound socket is answered with :not-in-room.
            (when-let [{:keys [room-id token]} (seat)]
              (.send sock (transit/write writer {:type :client/resume
                                                 :room-id room-id
                                                 :token token})))
            (flush-outbox!)))
    (set! (.-onmessage sock)
          (fn [e]
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
