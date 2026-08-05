(ns werwolf.core
  "Frontend entry point. M0: proves the transit roundtrip over the socket.
   Real lobby/game views arrive in M3."
  (:require [reagent.dom.client :as rdomc]
            [re-frame.core :as rf]
            [werwolf.config :as config]
            [werwolf.ws :as ws]))

;; ------------------------------------------------------------------ events ---

(rf/reg-event-db
 ::init
 (fn [_ _]
   {:conn/status :connecting
    :locale      :de
    :log         []}))

(rf/reg-event-db
 ::connected
 (fn [db _] (assoc db :conn/status :connected)))

(rf/reg-event-db
 ::disconnected
 (fn [db _] (assoc db :conn/status :disconnected)))

(rf/reg-event-db
 ::server-message
 (fn [db [_ msg]]
   (update db :log conj msg)))

(rf/reg-event-fx
 ::send-ping
 (fn [_ _]
   {::ws/send {:type :client/ping :at (.getTime (js/Date.))}}))

;; --------------------------------------------------------------- subs ---

(rf/reg-sub ::conn-status (fn [db _] (:conn/status db)))
(rf/reg-sub ::log (fn [db _] (:log db)))

;; -------------------------------------------------------------- views ---

(defn app []
  (let [status @(rf/subscribe [::conn-status])
        log    @(rf/subscribe [::log])]
    [:div {:class "min-h-screen bg-slate-900 text-slate-100 p-8 font-sans"}
     [:h1 {:class "text-3xl font-bold mb-2"} "Werwolf"]
     [:p {:class "text-slate-400 mb-6"}
      "Verbindung: "
      [:span {:class (case status
                       :connected "text-green-400"
                       :connecting "text-amber-400"
                       "text-red-400")}
       (name status)]]
     [:button {:class "px-4 py-2 rounded bg-indigo-600 hover:bg-indigo-500 mb-6"
               :on-click #(rf/dispatch [::send-ping])}
      "Ping senden"]
     [:pre {:class "bg-slate-800 rounded p-4 text-xs overflow-x-auto"}
      (pr-str log)]]))

;; --------------------------------------------------------------- mount ---

(defonce root (atom nil))

(defn mount! []
  (when-not @root
    (reset! root (rdomc/create-root (js/document.getElementById "app"))))
  (rdomc/render @root [app]))

(defn init []
  (rf/dispatch-sync [::init])
  (ws/connect! (config/websocket-url))
  (mount!))

(defn reload! []
  (rf/clear-subscription-cache!)
  (mount!))
