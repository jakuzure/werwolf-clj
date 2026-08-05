(ns werwolf.http
  "HTTP + WebSocket routing. Thin by design: this namespace knows about frames
   and bytes, and nothing at all about Werwolf.

   The socket object is passed straight through to the protocol layer as the
   player's 'sink'. There is deliberately no per-connection map here — the room
   registry's index already knows which player a socket belongs to, and a second
   copy of that fact is a second thing that can be wrong."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [reitit.ring :as ring]
            [werwolf.protocol :as protocol]
            [werwolf.ws :as ws]))

(defn health [_req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "ok"})

(defn index [_req]
  (if-let [r (io/resource "public/index.html")]
    {:status 200 :headers {"content-type" "text/html; charset=utf-8"} :body (io/input-stream r)}
    {:status 404 :body "index.html not built — run: bb build:cljs"}))

(defn ws-handler
  "Upgrade to a WebSocket and hand every frame to the protocol layer.

   `read-transit` returns nil on malformed input and `dispatch` answers nil with
   an error, so a client cannot kill its own read loop — let alone the server —
   by sending garbage."
  [deps]
  (fn [req]
    (if (ws/upgrade-request? req)
      (ws/websocket-response
       {:on-open    (fn [sock] (protocol/on-open deps sock))
        :on-message (fn [sock raw] (protocol/dispatch deps sock (ws/read-transit raw)))
        :on-close   (fn [sock _code _reason] (protocol/on-close deps sock))
        :on-error   (fn [sock e]
                      (log/warn "websocket error:" (ex-message e))
                      (protocol/on-close deps sock))})
      {:status 400 :body "expected a websocket upgrade request"})))

(defn router [deps]
  (ring/router
   [["/health" {:get health}]
    ["/api/ws" {:get (ws-handler deps)}]
    ["/" {:get index}]]))

(defn handler [deps]
  (ring/ring-handler
   (router deps)
   (ring/routes
    (ring/create-resource-handler {:path "/" :root "public"})
    (ring/create-default-handler))))
