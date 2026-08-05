(ns werwolf.core
  "Frontend entry point: connect, mount, tick.

   State lives in `werwolf.events`, projections in `werwolf.subs`, rendering in
   `werwolf.views`. This namespace only starts things."
  (:require [reagent.dom.client :as rdomc]
            [re-frame.core :as rf]
            [werwolf.config :as config]
            [werwolf.events :as ev]
            [werwolf.subs]
            [werwolf.views :as views]
            [werwolf.ws :as ws]))

(defonce root (atom nil))
(defonce ticker (atom nil))

(defn mount! []
  (when-not @root
    (reset! root (rdomc/create-root (js/document.getElementById "app"))))
  (rdomc/render @root [views/app]))

(defn init []
  (rf/dispatch-sync [::ev/init])
  (ws/connect! (config/websocket-url))
  (when-not @ticker
    ;; One timer for the whole app: countdowns render from :now, so a passing
    ;; second is one re-render rather than one per player card.
    (reset! ticker (js/setInterval #(rf/dispatch [::ev/tick]) 500)))
  (mount!))

(defn reload! []
  (rf/clear-subscription-cache!)
  (mount!))
