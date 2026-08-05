(ns werwolf.config
  "Configuration loading. Single source of truth is resources/config.edn,
   which reads everything overridable from the environment (12-factor), so the
   same artifact runs locally, in Docker Compose and on k3s."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(defn load-config
  ([] (load-config :prod))
  ([profile]
   (aero/read-config (io/resource "config.edn") {:profile profile})))
