(ns werwolf.ws
  "WebSocket transport.

   Two things worth knowing here:

   1. We use the STANDARD Ring WebSocket API (a response containing
      :ring.websocket/listener), which http-kit 2.8+ supports. That keeps us
      portable to Jetty later without touching call sites.

   2. Ring ships NO map->Listener adapter — the listener must satisfy the
      ring.websocket.protocols/Listener protocol. `listener` below is that
      adapter, so the rest of the codebase gets to stay data-shaped."
  (:require [cognitect.transit :as transit]
            [ring.websocket :as ws]
            [ring.websocket.protocols :as wsp])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

;; ---------------------------------------------------------------- transit ---

(defn write-transit
  "Encode a Clojure value as a transit-json string for the wire."
  ^String [value]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out :json) value)
    (.toString out "UTF-8")))

(defn read-transit
  "Decode a transit-json string from a client. Returns nil on malformed input
   rather than throwing — a client must never be able to kill the read loop."
  [^String s]
  (try
    (transit/read (transit/reader (ByteArrayInputStream. (.getBytes s "UTF-8")) :json))
    (catch Exception _ nil)))

;; --------------------------------------------------------------- listener ---

(defn listener
  "Adapt a map of handler fns to ring.websocket.protocols/Listener.

   Keys (all optional): :on-open :on-message :on-pong :on-error :on-close
   Each receives the socket as its first argument."
  [{:keys [on-open on-message on-pong on-error on-close]}]
  (reify wsp/Listener
    (on-open    [_ sock]             (when on-open (on-open sock)))
    (on-message [_ sock msg]         (when on-message (on-message sock msg)))
    (on-pong    [_ sock buf]         (when on-pong (on-pong sock buf)))
    (on-error   [_ sock err]         (when on-error (on-error sock err)))
    (on-close   [_ sock code reason] (when on-close (on-close sock code reason)))))

(defn send!
  "Send a Clojure value to a socket as transit. Swallows send-after-close,
   which is normal and racy: a player can disconnect mid-broadcast."
  [sock value]
  (try
    (ws/send sock (write-transit value))
    true
    (catch Exception _ false)))

(defn open? [sock] (ws/open? sock))

(defn close!
  ([sock] (try (ws/close sock) (catch Exception _ nil)))
  ([sock code reason] (try (ws/close sock code reason) (catch Exception _ nil))))

(defn upgrade-request? [req] (ws/upgrade-request? req))

(defn websocket-response
  "Build the Ring response that upgrades this request to a WebSocket."
  [handlers]
  {:ring.websocket/listener (listener handlers)})
