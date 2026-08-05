(ns werwolf.main
  "Integrant system definition and production entry point."
  (:require [integrant.core :as ig]
            [org.httpkit.server :as hk]
            [werwolf.config :as config]
            [werwolf.http :as http])
  (:gen-class))

(defn system-config
  "Build the integrant config from the loaded application config."
  [{:keys [server] :as cfg}]
  {::handler {:config cfg}
   ::server  {:handler (ig/ref ::handler)
              :port    (:port server)
              :host    (:host server)}})

(defmethod ig/init-key ::handler [_ {:keys [config]}]
  (http/handler {:config config}))

(defmethod ig/init-key ::server [_ {:keys [handler port host]}]
  (println (str "werwolf-clj listening on http://" host ":" port))
  ;; :legacy-return-value? false gives us a stoppable server object rather
  ;; than a bare stop function.
  (hk/run-server handler {:port port
                          :ip host
                          :legacy-return-value? false}))

(defmethod ig/halt-key! ::server [_ server]
  (hk/server-stop! server {:timeout 1000}))

(defn -main [& _args]
  (let [cfg (config/load-config :prod)
        sys (ig/init (system-config cfg))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(ig/halt! sys)))
    @(promise)))
