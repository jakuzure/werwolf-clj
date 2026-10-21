(ns werwolf.core
  "Frontend entry point: connect, mount, tick.

   State lives in `werwolf.events`, projections in `werwolf.subs`, rendering in
   `werwolf.views`. This namespace only starts things."
  (:require [reagent.dom.client :as rdomc]
            [re-frame.core :as rf]
            ;; Required for its side effect of registering the voice effects, and
            ;; required HERE rather than from werwolf.events because it reaches
            ;; livekit-client through `shadow.lazy` — which needs the :app build's
            ;; module split and cannot compile in :test-node. This namespace is the
            ;; browser entry point, so it is the one place a module graph is
            ;; guaranteed to exist. It does NOT pull in the SDK: audio-impl is a
            ;; separate module, fetched on the first tap to join.
            [werwolf.audio]
            [werwolf.chime :as chime]
            [werwolf.config :as config]
            [werwolf.events :as ev]
            [werwolf.subs]
            [werwolf.views :as views]
            [werwolf.wake :as wake]
            [werwolf.ws :as ws]))

(defonce root (atom nil))
(defonce ticker (atom nil))

(defn mount! []
  (when-not @root
    (reset! root (rdomc/create-root (js/document.getElementById "app"))))
  (rdomc/render @root [views/app]))

(defn- invited-code
  "The room code from a shared link, `/?join=ABCD`. Taken off the address at
   once, so a reload or a bookmark does not keep offering a room that is long
   gone."
  []
  (let [code (some-> (js/URLSearchParams. js/location.search) (.get "join") .toUpperCase)]
    (when code (.replaceState js/history nil "" js/location.pathname))
    (when (and code (re-matches #"[A-Z2-9]{4}" code)) code)))

(defn init []
  (rf/dispatch-sync [::ev/init (invited-code)])
  ;; Before anything can be tapped: the tap that creates or joins a room is the
  ;; gesture that lets the first night's chime play at all.
  (chime/install!)
  ;; So a tab coming back from the background re-takes the screen lock the
  ;; browser let go of while it was hidden.
  (wake/install!)
  (ws/connect! (config/websocket-url))
  (when-not @ticker
    ;; One timer for the whole app: countdowns render from :now, so a passing
    ;; second is one re-render rather than one per player card. The landing
    ;; screen's room list rides it too, and decides for itself how often to ask.
    (reset! ticker (js/setInterval #(do (rf/dispatch [::ev/tick])
                                        (rf/dispatch [::ev/poll-rooms]))
                                   500)))
  (mount!))

(defn reload! []
  (rf/clear-subscription-cache!)
  (mount!))
