(ns dev
  "REPL entry point. Usage:

     (dev/go)      ; start the system
     (dev/reset)   ; reload changed namespaces and restart
     (dev/halt)    ; stop"
  (:require [integrant.core :as ig]
            [integrant.repl :as ig-repl :refer [go halt reset reset-all]]
            [integrant.repl.state :as state]
            [werwolf.config :as config]
            [werwolf.main :as main]))

(ig-repl/set-prep! #(main/system-config (config/load-config :dev)))

(defn system [] state/system)

(comment
  (go)
  (reset)
  (halt)
  ;; Sanity check the HTTP layer without a browser:
  (slurp "http://localhost:3000/health"))
