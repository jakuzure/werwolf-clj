(ns werwolf.http
  "HTTP + WebSocket routing. Thin: real work lives in the engine and room layer."
  (:require [reitit.ring :as ring]
            [werwolf.ws :as ws]
            [clojure.java.io :as io]))

(defn health [_req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "ok"})

(defn index [_req]
  (if-let [r (io/resource "public/index.html")]
    {:status 200 :headers {"content-type" "text/html; charset=utf-8"} :body (io/input-stream r)}
    {:status 404 :body "index.html not built — run: bb build:cljs"}))

(defn ws-echo-handler
  "M0 placeholder proving the transit roundtrip end to end.
   Replaced by werwolf.room command intake in M2."
  [req]
  (if (ws/upgrade-request? req)
    (ws/websocket-response
     {:on-open    (fn [sock]
                    (ws/send! sock {:type :server/hello
                                    :msg  "werwolf-clj M0"}))
      :on-message (fn [sock raw]
                    (let [msg (ws/read-transit raw)]
                      (ws/send! sock {:type :server/echo :received msg})))
      :on-error   (fn [_ e] (println "ws error:" (ex-message e)))})
    {:status 400 :body "expected a websocket upgrade request"}))

(defn router [_deps]
  (ring/router
   [["/health" {:get health}]
    ["/api/ws" {:get ws-echo-handler}]
    ["/" {:get index}]]))

(defn handler [deps]
  (ring/ring-handler
   (router deps)
   (ring/routes
    (ring/create-resource-handler {:path "/" :root "public"})
    (ring/create-default-handler))))
