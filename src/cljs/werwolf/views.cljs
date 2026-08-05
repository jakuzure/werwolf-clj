(ns werwolf.views
  "The UI. Every affordance is rendered from `:actions` and `:targets`, which the
   server computed with the engine's own `legal-actions`/`legal-targets`.

   That is not a shortcut, it is the design: this file contains no rule about who
   may do what. There is no `(when (= :night phase) ...)` deciding whether the
   Seherin may look at somebody — if the server offered `:seherin-inspect` with a
   list of targets, the buttons appear; if it did not, they cannot. A UI that
   re-derives the rules is a UI that can disagree with the server, and in a
   hidden-role game a disagreement is a leak."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [werwolf.events :as ev]
            [werwolf.subs :as sub]
            [werwolf.text :as text :refer [t]]))

;; ------------------------------------------------------------------ pieces ---

(defn- btn
  ([label on-click] (btn label on-click {}))
  ([label on-click {:keys [tone disabled?]}]
   [:button {:class (str "px-3 py-1.5 rounded text-sm font-medium transition "
                         (case tone
                           :danger  "bg-rose-700 hover:bg-rose-600 text-white "
                           :ghost   "bg-slate-700 hover:bg-slate-600 text-slate-100 "
                           "bg-indigo-600 hover:bg-indigo-500 text-white ")
                         (when disabled? "opacity-40 cursor-not-allowed "))
             :disabled (boolean disabled?)
             :on-click on-click}
    label]))

(defn- field [k placeholder & [{:keys [on-enter maxlength]}]]
  (let [v @(rf/subscribe [::sub/form-field k])]
    [:input {:class "w-full px-3 py-2 rounded bg-slate-800 border border-slate-700
                     focus:border-indigo-500 outline-none text-slate-100"
             :value v
             :placeholder placeholder
             :maxLength (or maxlength 24)
             :on-change #(rf/dispatch [::ev/form-set k (.. % -target -value)])
             :on-key-down #(when (and on-enter (= "Enter" (.-key %)))
                             (rf/dispatch on-enter))}]))

(defn- connection-banner []
  (let [status @(rf/subscribe [::sub/conn-status])]
    (when (not= :connected status)
      [:div {:class "bg-amber-900/70 text-amber-100 px-4 py-2 text-sm text-center"}
       (t (keyword "conn" (name status)))])))

(defn- error-banner []
  (when-let [code @(rf/subscribe [::sub/error])]
    [:div {:class "bg-rose-900/80 text-rose-100 px-4 py-2 text-sm flex justify-between"}
     [:span (t (keyword "error" (name code)))]
     [:button {:class "underline opacity-70 hover:opacity-100"
               :on-click #(rf/dispatch [::ev/dismiss-error])} "×"]]))

;; ----------------------------------------------------------------- landing ---

(defn landing []
  [:div {:class "max-w-sm mx-auto mt-16 space-y-6"}
   [:h1 {:class "text-3xl font-bold text-center"} (t :app/title)]
   [:div {:class "space-y-3"}
    [field :name (t :landing/name)]
    [btn (t :landing/create) #(rf/dispatch [::ev/create-room])]]
   [:div {:class "text-center text-slate-500 text-sm"} (t :landing/or)]
   [:div {:class "space-y-3"}
    [field :code (t :landing/code) {:on-enter [::ev/join-room] :maxlength 4}]
    [btn (t :landing/join) #(rf/dispatch [::ev/join-room]) {:tone :ghost}]]])

;; -------------------------------------------------------------------- chat ---

(defn- chat-channel-hint [channel]
  (case channel
    :wolves [:span {:class "text-rose-400"} (t :game/wolves-channel)]
    :dead   [:span {:class "text-slate-500"} (t :game/dead-channel)]
    nil))

(defn chat-panel []
  (let [msgs @(rf/subscribe [::sub/chat])
        actions @(rf/subscribe [::sub/actions])
        allowed? (contains? actions :chat)]
    [:div {:class "flex flex-col h-72 bg-slate-800/60 rounded"}
     [:div {:class "px-3 py-2 text-xs uppercase tracking-wide text-slate-400
                    border-b border-slate-700"}
      (t :game/chat)]
     [:div {:class "flex-1 overflow-y-auto px-3 py-2 space-y-1 text-sm"}
      (for [[i m] (map-indexed vector msgs)]
        ^{:key i}
        [:div
         [:span {:class (case (:channel m)
                          :wolves "text-rose-400 font-medium"
                          :dead   "text-slate-500 font-medium"
                          "text-indigo-300 font-medium")}
          (:name m) ": "]
         [:span {:class (when (= :dead (:channel m)) "text-slate-500")} (:text m)]])]
     [:div {:class "border-t border-slate-700 p-2 space-y-1"}
      (when allowed?
        ;; Which channel the message will land in comes from the same state the
        ;; server routes on, so the label cannot promise the wrong audience.
        (chat-channel-hint (cond
                            (false? (:alive? @(rf/subscribe [::sub/me-view]))) :dead
                            (= :night @(rf/subscribe [::sub/phase])) :wolves)))
      [:div {:class "flex gap-2"}
       [:div {:class "flex-1"}
        [field :text (t :game/chat-hint) {:on-enter [::ev/send-chat] :maxlength 500}]]
       [btn "→" #(rf/dispatch [::ev/send-chat]) {:disabled? (not allowed?)}]]]]))

;; --------------------------------------------------------------- the table ---

(defn- player-card
  "One seat. `known` is the roles this player is entitled to see — from the
   server's `:known-roles`, never inferred."
  [{:keys [id alive? connected?] pname :name} {:keys [me-id known targets on-target]}]
  (let [selectable? (contains? (set targets) id)]
    [:div {:class (str "rounded border px-3 py-2 flex items-center justify-between gap-2 "
                       (cond
                         (not alive?) "border-slate-800 bg-slate-900/60 text-slate-600 "
                         selectable?  "border-indigo-500 bg-slate-800 "
                         :else        "border-slate-700 bg-slate-800/40 "))}
     [:div
      [:div {:class "font-medium"}
       pname
       (when (= id me-id) [:span {:class "text-slate-500 text-xs ml-1"}
                           (str "(" (t :game/you) ")")])]
      [:div {:class "text-xs space-x-2"}
       (when-not alive? [:span {:class "text-rose-500"} (t :game/dead)])
       (when (false? connected?) [:span {:class "text-amber-500"} (t :game/absent)])
       (when-let [role (get known id)]
         [:span {:class "text-emerald-400"} (text/role-name role)])]]
     (when (and selectable? on-target)
       [btn "✓" #(on-target id)])]))

(defn- action-bar
  "Buttons for everything the server says this player may do.

   Targetless actions are one button; the rest select a player from the card
   list, so the whole thing is driven by `:targets` and needs no per-role code."
  []
  (let [actions @(rf/subscribe [::sub/actions])
        targets @(rf/subscribe [::sub/targets])
        targetless (sort (filter #(empty? (get targets %)) actions))
        targeted (sort (remove #(or (empty? (get targets %)) (= :chat %)) actions))
        pending @(rf/subscribe [::sub/form-field :action])]
    [:div {:class "space-y-2"}
     (when (seq targeted)
       [:div {:class "flex flex-wrap gap-2"}
        (for [a targeted]
          ^{:key (str a)}
          [btn (text/action-name a)
           #(rf/dispatch [::ev/form-set :action (when (not= pending a) a)])
           {:tone (if (= pending a) :primary :ghost)}])])
     (when (seq (remove #{:chat} targetless))
       [:div {:class "flex flex-wrap gap-2"}
        (for [a (remove #{:chat} targetless)]
          ^{:key (str a)}
          [btn (text/action-name a) #(rf/dispatch [::ev/act a])])])
     (when pending
       [:div {:class "text-xs text-indigo-300"}
        (str (text/action-name pending) " → " (t :lobby/players))])]))

(defn table-panel []
  (let [view @(rf/subscribe [::sub/view])
        me @(rf/subscribe [::sub/me-view])
        known @(rf/subscribe [::sub/known-roles])
        targets @(rf/subscribe [::sub/targets])
        pending @(rf/subscribe [::sub/form-field :action])
        selectable (get targets pending)]
    [:div {:class "space-y-3"}
     [action-bar]
     [:div {:class "grid grid-cols-2 gap-2"}
      (for [[id p] (sort-by key (:players view))]
        ^{:key id}
        [player-card (assoc p :id id)
         {:me-id (:id me)
          :known known
          :targets selectable
          :on-target (fn [target]
                       (rf/dispatch [::ev/act pending target])
                       (rf/dispatch [::ev/form-set :action nil]))}])]]))

;; ------------------------------------------------------------------- panels ---

(defn me-panel []
  (let [me @(rf/subscribe [::sub/me-view])
        players @(rf/subscribe [::sub/players])]
    [:div {:class "bg-slate-800/60 rounded p-3 space-y-2 text-sm"}
     [:div
      [:span {:class "text-slate-400"} (str (t :game/your-role) ": ")]
      [:span {:class "font-semibold text-emerald-400"} (text/role-name (:role me))]]
     (when (:lover me)
       [:div [:span {:class "text-slate-400"} (str (t :game/in-love) " ")]
        [:span {:class "text-rose-400"} (get-in players [(:lover me) :name])]])
     (when-let [r (:resources me)]
       [:div {:class "space-x-3"}
        (when-let [h (:heiltrank r)]
          [:span {:class (if (pos? h) "text-sky-300" "text-slate-600")}
           (t :game/heiltrank)])
        (when-let [g (:gifttrank r)]
          [:span {:class (if (pos? g) "text-lime-300" "text-slate-600")}
           (t :game/gifttrank)])])
     (when (seq (:knows me))
       [:div
        [:span {:class "text-slate-400"} (str (t :game/knows) ": ")]
        (str/join ", " (for [[pid role] (sort-by key (:knows me))]
                         (str (get-in players [pid :name]) " = " (text/role-name role))))])]))

(defn log-panel []
  (let [log @(rf/subscribe [::sub/log])
        players @(rf/subscribe [::sub/players])]
    [:div {:class "bg-slate-800/60 rounded p-3"}
     [:div {:class "text-xs uppercase tracking-wide text-slate-400 mb-2"} (t :game/log)]
     [:div {:class "space-y-1 text-sm max-h-64 overflow-y-auto"}
      (for [[i entry] (map-indexed vector (reverse log))]
        ^{:key i}
        [:div {:class "text-slate-300"} (text/log-line players entry)])]]))

(defn phase-header []
  (let [phase @(rf/subscribe [::sub/phase])
        secs @(rf/subscribe [::sub/seconds-left])
        me @(rf/subscribe [::sub/me])
        can-act? @(rf/subscribe [::sub/can-act?])]
    [:div {:class "flex items-center justify-between gap-4"}
     [:div
      [:h2 {:class "text-2xl font-bold"} (text/phase-name phase)]
      [:div {:class "text-xs text-slate-500"} (str (t :lobby/share) " " (:room-id me))]]
     [:div {:class "text-right"}
      (when secs
        [:div {:class (str "text-2xl font-mono "
                           (if (< secs 11) "text-rose-400" "text-slate-300"))}
         (str (quot secs 60) ":" (when (< (mod secs 60) 10) "0") (mod secs 60))])
      [:div {:class "text-xs text-slate-500"}
       (if can-act? "" (t :game/waiting))]]]))

;; -------------------------------------------------------------------- lobby ---

(defn lobby []
  (let [view @(rf/subscribe [::sub/view])
        me @(rf/subscribe [::sub/me])
        actions @(rf/subscribe [::sub/actions])
        players (:players view)]
    [:div {:class "space-y-4"}
     [:div {:class "flex items-baseline justify-between"}
      [:h2 {:class "text-2xl font-bold"} (t :lobby/title)]
      [:div {:class "font-mono text-2xl tracking-widest text-indigo-300"} (:room-id me)]]
     [:div {:class "text-sm text-slate-400"} (t :lobby/share)]
     [:div {:class "grid grid-cols-2 gap-2"}
      (for [[id p] (sort-by key players)]
        ^{:key id}
        [player-card (assoc p :id id) {:me-id (:player-id me) :known {}}])]
     [:div {:class "flex items-center gap-3"}
      (if (contains? actions :start-game)
        [btn (t :lobby/start) #(rf/dispatch [::ev/act :start-game])]
        [:span {:class "text-sm text-slate-500"}
         (if (:host? me) (t :lobby/need-more) (t :game/waiting))])
      [btn (t :lobby/leave) #(rf/dispatch [::ev/leave]) {:tone :ghost}]]
     [chat-panel]]))

;; --------------------------------------------------------------------- game ---

(defn game []
  [:div {:class "space-y-4"}
   [phase-header]
   [:div {:class "grid md:grid-cols-3 gap-4"}
    [:div {:class "md:col-span-2 space-y-4"}
     [table-panel]
     [chat-panel]]
    [:div {:class "space-y-4"}
     [me-panel]
     [log-panel]]]])

;; ---------------------------------------------------------------------- app ---

(defn app []
  (let [screen @(rf/subscribe [::sub/screen])
        phase @(rf/subscribe [::sub/phase])]
    [:div {:class "min-h-screen bg-slate-900 text-slate-100 font-sans"}
     [connection-banner]
     [error-banner]
     [:div {:class "max-w-5xl mx-auto p-6"}
      (case screen
        :landing [landing]
        :room (if (or (nil? phase) (= :lobby phase)) [lobby] [game])
        [landing])]]))
