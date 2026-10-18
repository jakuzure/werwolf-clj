(ns werwolf.main
  "Integrant system definition and production entry point.

   The dependency order is the whole design in miniature:

     db -> rooms -> timer
                 -> handler -> server

   The log comes first because it is the truth; rooms are a fold over it; the
   clock and the sockets are both merely things that poke at rooms."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [org.httpkit.server :as hk]
            [werwolf.build-info :refer [package-version running-commit]]
            [werwolf.config :as config]
            [werwolf.http :as http]
            [werwolf.livekit :as livekit]
            [werwolf.room :as room]
            [werwolf.store :as store]
            [werwolf.timer :as timer]
            [werwolf.token :as token]
            [werwolf.ws :as ws])
  (:gen-class))

(defn system-config
  "Build the integrant config from the loaded application config."
  [{:keys [server db tokens game media]}]
  {::db      {:config db}
   ::rooms   {:db (ig/ref ::db) :game game}
   ::timer   {:rooms (ig/ref ::rooms)}
   ::media   {:config media}
   ::handler {:rooms (ig/ref ::rooms) :tokens tokens :media (ig/ref ::media)}
   ::server  {:handler (ig/ref ::handler)
              :port    (:port server)
              :host    (:host server)}})

(defmethod ig/init-key ::db [_ {:keys [config]}]
  (log/info "opening event log at" (:dbname config))
  (store/open config))

(defmethod ig/init-key ::rooms [_ {:keys [db game]}]
  (let [ctx (room/registry {:ds db
                            :send! ws/send!
                            ;; Table-size limits for newly opened rooms.
                            :game game
                            ;; Installing the clock's hook here is what makes
                            ;; deadlines exist at all — see werwolf.timer.
                            :after-commit (timer/hook game)})
        restored (room/rehydrate! ctx)]
    (when (seq restored)
      (log/info "rehydrated" (count restored) "unfinished room(s):" restored)
      ;; A game that was mid-phase when the process died gets a full fresh clock
      ;; rather than an instantly-expired one.
      (timer/rearm-all! ctx game (System/currentTimeMillis)))
    ctx))

(defmethod ig/init-key ::timer [_ {:keys [rooms]}]
  (timer/start! rooms {}))

(defmethod ig/halt-key! ::timer [_ handle]
  (timer/stop! handle))

(defmethod ig/init-key ::media [_ {:keys [config]}]
  ;; A misconfigured voice provider must not stop a game that can be played
  ;; without it, so this falls back to NoopMedia and says why at a volume matched
  ;; to the mistake: asking for LiveKit and forgetting the keys is a warning,
  ;; because the operator clearly wanted audio and will not otherwise find out.
  (let [{:keys [provider reason]} (livekit/provider config)]
    (case reason
      :ok (log/info "media: livekit at" (:url config))
      :disabled (log/info "media: none (text only)")
      :incomplete-config
      (log/warn (str "media: MEDIA_PROVIDER=livekit but LIVEKIT_URL / "
                     "LIVEKIT_API_KEY / LIVEKIT_API_SECRET are incomplete — "
                     "audio is OFF")))
    provider))

(defmethod ig/init-key ::handler [_ {:keys [rooms tokens media]}]
  (when (token/insecure-secret? tokens)
    ;; Loud, but not fatal: refusing to boot would mean a typo'd env var locks
    ;; you out of your own server, and the threat model here is a friend group,
    ;; not the internet. This becomes fatal the day the room list goes public.
    (log/error (str "TOKEN_SECRET is unset or insecure — reconnect tokens are "
                    "forgeable. Set TOKEN_SECRET before exposing this server.")))
  (http/handler {:rooms rooms
                 :tokens tokens
                 :media media
                 :send! ws/send!}))

(defmethod ig/init-key ::server [_ {:keys [handler port host]}]
  (let [srv (hk/run-server handler {:port port
                                    :ip host
                                    ;; A stoppable server object rather than a
                                    ;; bare stop function.
                                    :legacy-return-value? false})]
    (log/info (str "werwolf-clj " (package-version) " (" (running-commit) ") listening on http://" host ":" (hk/server-port srv)))
    srv))

(defmethod ig/halt-key! ::server [_ server]
  (hk/server-stop! server {:timeout 1000}))

(defn -main [& _args]
  (let [cfg (config/load-config :prod)
        sys (ig/init (system-config cfg))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(ig/halt! sys)))
    @(promise)))
