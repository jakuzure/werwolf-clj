(ns werwolf.ws
  "Client WebSocket transport: transit framing, a re-frame effect for sending,
   and reconnect with exponential backoff.

   Messages arriving from the server are dispatched as [::werwolf.core/server-message msg].
   The server is authoritative — this namespace never interprets game state."
  (:require [cognitect.transit :as transit]
            [re-frame.core :as rf]))

(def ^:private reader (transit/reader :json))
(def ^:private writer (transit/writer :json))

(defonce ^:private socket (atom nil))
(defonce ^:private url* (atom nil))
(defonce ^:private retry (atom 0))
(defonce ^:private outbox (atom []))

(def ^:private max-backoff-ms 15000)

(defn- backoff-ms [n]
  (min max-backoff-ms (* 500 (js/Math.pow 2 n))))

(defn- open? [] (some-> @socket .-readyState (= js/WebSocket.OPEN)))

(defn send!
  "Send a Clojure value. If the socket isn't open yet, queue it and flush on open —
   avoids losing the initial join command during a reconnect race."
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
    (js/console.warn (str "ws: reconnecting in " ms "ms (attempt " (inc n) ")"))
    (js/setTimeout #(connect! @url*) ms)))

(defn connect! [url]
  (reset! url* url)
  (let [sock (js/WebSocket. url)]
    (reset! socket sock)
    (set! (.-onopen sock)
          (fn [_]
            (reset! retry 0)
            (rf/dispatch [:werwolf.core/connected])
            (flush-outbox!)))
    (set! (.-onmessage sock)
          (fn [e]
            (let [msg (transit/read reader (.-data e))]
              (rf/dispatch [:werwolf.core/server-message msg]))))
    (set! (.-onclose sock)
          (fn [_]
            (rf/dispatch [:werwolf.core/disconnected])
            (schedule-reconnect!)))
    (set! (.-onerror sock)
          (fn [_] (js/console.error "ws: error")))
    sock))

;; re-frame effect: {::send {...}}
(rf/reg-fx ::send (fn [value] (send! value)))
