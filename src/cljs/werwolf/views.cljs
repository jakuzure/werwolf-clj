(ns werwolf.views
  "The UI. Every affordance is rendered from `:actions`, `:targets` and `:arity`,
   which the server computed with the engine's own legal-actions/legal-targets.

   That is not a shortcut, it is the design: this file contains no rule about who
   may do what. There is no `(when (= :night phase) ...)` deciding whether the
   Seherin may look at somebody — if the server offered `:seherin-inspect` with a
   list of targets, the buttons appear; if it did not, they cannot. Amor's pair is
   handled by reading `:arity` rather than by naming Amor, so the next
   two-target role needs no change here either.

   Three things this file is deliberately loud about, because a first playtest
   found all three:

   * **Who you are.** Your role, what it does, and your own card are the first
     thing on screen — not a line of small text in a sidebar.
   * **What clicking does.** Choosing a target is an explicit mode with a prompt
     and a cancel, not a small tick that appears somewhere.
   * **What just happened.** The Chronik is a column of its own, newest first,
     colour-coded by consequence. A log where a death and a reconnect look the
     same is a log nobody reads."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [werwolf.events :as ev]
            [werwolf.i18n.core :as i18n]
            [werwolf.proto :as proto]
            [werwolf.subs :as sub]))

;; --------------------------------------------------------------- language ---
;;
;; Each of these derefs the locale subscription, so a component that renders text
;; re-renders when the player switches language. That is the whole reason they
;; are functions rather than a value threaded down from `app`.

(defn- lc [] @(rf/subscribe [::sub/locale]))
(defn- t [k & args] (apply i18n/t (lc) k args))
(defn- phase-name [p] (i18n/phase-name (lc) p))
(defn- role-name [r] (i18n/role-name (lc) r))
(defn- role-desc [r] (i18n/role-desc (lc) r))
(defn- action-name [a] (i18n/action-name (lc) a))

;; ------------------------------------------------------------------ pieces ---

(defn- btn
  ([label on-click] (btn label on-click {}))
  ([label on-click {:keys [tone disabled?]}]
   [:button {:class (str "px-3 py-2 rounded-lg text-sm font-semibold transition "
                         "disabled:opacity-40 disabled:cursor-not-allowed "
                         (case tone
                           :danger "bg-rose-700 hover:bg-rose-600 text-white "
                           :ghost  "bg-slate-700 hover:bg-slate-600 text-slate-100 "
                           :armed  "bg-amber-500 hover:bg-amber-400 text-slate-900 ring-2 ring-amber-300 "
                           :quiet  "bg-transparent border border-slate-600 hover:bg-slate-800 text-slate-300 "
                           "bg-indigo-600 hover:bg-indigo-500 text-white "))
             :disabled (boolean disabled?)
             :on-click on-click}
    label]))

(defn- field [k placeholder & [{:keys [on-enter maxlength]}]]
  [:input {:class "w-full px-3 py-2 rounded-lg bg-slate-800 border border-slate-700
                   focus:border-indigo-500 outline-none text-slate-100"
           :value @(rf/subscribe [::sub/form-field k])
           :placeholder placeholder
           :maxLength (or maxlength 24)
           :on-change #(rf/dispatch [::ev/form-set k (.. % -target -value)])
           :on-key-down #(when (and on-enter (= "Enter" (.-key %)))
                           (rf/dispatch on-enter))}])

(defn- pill [label tone]
  [:span {:class (str "px-1.5 py-0.5 rounded text-[10px] font-bold uppercase tracking-wide "
                      (case tone
                        :dead    "bg-rose-950 text-rose-300 "
                        :absent  "bg-amber-950 text-amber-300 "
                        :you     "bg-indigo-600 text-white "
                        :host    "bg-violet-800 text-violet-100 "
                        :role    "bg-emerald-900 text-emerald-200 "
                        :accused "bg-orange-900 text-orange-200 "
                        :votes   "bg-slate-600 text-slate-100 "
                        :lover   "bg-pink-900 text-pink-200 "
                        "bg-slate-700 text-slate-200 "))}
   label])

(defn- language-switch
  "Switch language at any time, in or out of a game.

   Sent as a command once seated, so the locale is per-player game state on the
   server — it survives a reload, follows you to another device, and is what
   makes a mixed-locale room work: two people reading the same room in different
   languages, which is the entire reason the wire carries structured events
   instead of sentences."
  []
  (let [current (lc)]
    [:div {:class "flex gap-1"}
     (for [loc i18n/locales]
       ^{:key (str loc)}
       [:button {:class (str "px-2 py-1 rounded text-xs font-semibold transition "
                             (if (= loc current)
                               "bg-indigo-600 text-white "
                               "bg-slate-800 text-slate-400 hover:text-slate-100 "))
                 :on-click #(rf/dispatch [::ev/set-locale loc])}
        (i18n/locale-name loc)])]))

(defn- connection-banner []
  (let [status @(rf/subscribe [::sub/conn-status])]
    (when (not= :connected status)
      [:div {:class "bg-amber-900/80 text-amber-100 px-4 py-2 text-sm text-center"}
       (t (keyword "conn" (name status)))])))

(defn- error-banner []
  (when-let [code @(rf/subscribe [::sub/error])]
    [:div {:class "bg-rose-900/90 text-rose-50 px-4 py-2 text-sm flex justify-between"}
     [:span (t (keyword "error" (name code)))]
     [:button {:class "px-2 opacity-70 hover:opacity-100"
               :on-click #(rf/dispatch [::ev/dismiss-error])} "×"]]))

;; ----------------------------------------------------------------- landing ---

(defn- open-rooms
  "The lobbies you could walk into, so nobody has to read a code off someone
   else's phone. Only rooms with a free seat are listed — the server decides
   that, since a game in progress cannot be joined at all."
  []
  (let [rooms @(rf/subscribe [::sub/rooms])]
    [:div {:class "space-y-2"}
     [:div {:class "flex items-baseline justify-between"}
      [:span {:class "text-sm text-slate-400"} (t :landing/open-rooms)]
      [:button {:class "text-xs text-slate-500 hover:text-slate-200 underline"
                :on-click #(rf/dispatch [::ev/refresh-rooms])}
       (t :landing/refresh)]]
     (if (empty? rooms)
       [:p {:class "text-sm text-slate-600"} (t :landing/no-rooms)]
       [:div {:class "space-y-2"}
        (for [{:keys [room-id host players max-players]} rooms]
          ^{:key room-id}
          [:button {:class "w-full flex items-center justify-between gap-3 px-3 py-3
                            rounded-lg bg-slate-800 border border-slate-700
                            hover:border-indigo-500 hover:bg-slate-700 transition text-left"
                    :on-click #(rf/dispatch [::ev/join-listed room-id])}
           [:div {:class "min-w-0"}
            [:div {:class "font-mono text-lg tracking-[0.2em] text-indigo-300"} room-id]
            (when host
              [:div {:class "text-xs text-slate-400 truncate"} host])]
           [:span {:class "shrink-0 text-xs text-slate-400"}
            (str players " / " max-players)]])])]))

(defn landing []
  [:div {:class "max-w-sm mx-auto mt-16 space-y-8"}
   [:div {:class "flex justify-center"} [language-switch]]
   [:h1 {:class "text-3xl font-bold text-center"} (t :app/title)]
   [:div {:class "space-y-3"}
    [field :name (t :landing/name)]
    [btn (t :landing/create) #(rf/dispatch [::ev/create-room])]]
   [:div {:class "text-center text-slate-500 text-sm"} (t :landing/or)]
   ;; The list first, the code field under it: typing a code is now the
   ;; fallback for a room the list cannot show, not the normal way in.
   [open-rooms]
   [:div {:class "space-y-3"}
    [field :code (t :landing/code) {:on-enter [::ev/join-room] :maxlength 4}]
    [btn (t :landing/join) #(rf/dispatch [::ev/join-room]) {:tone :ghost}]]])

;; ---------------------------------------------------------------- targeting ---
;;
;; A pending action lives in the form map as {:action a :picks [ids]}. Arity
;; comes from the view, so this code never mentions a role by name.

(defn- pending [] @(rf/subscribe [::sub/pending]))

(defn- arm! [action]
  (rf/dispatch [::ev/form-set :pending {:action action :picks []}]))

(defn- disarm! []
  (rf/dispatch [::ev/form-set :pending nil]))

(defn- pick!
  "Add a target to the pending action, and fire once enough are chosen.

   For a two-target action the command carries :a and :b, which is the shape the
   engine has always wanted — it was the wire schema that lacked them, and
   because that schema is closed, Amor was not degraded but unplayable."
  [id]
  (let [{:keys [action picks]} (pending)
        arity (get @(rf/subscribe [::sub/arity]) action 1)
        picks' (conj (vec picks) id)]
    (if (< (count picks') arity)
      (rf/dispatch [::ev/form-set :pending {:action action :picks picks'}])
      (do (rf/dispatch (if (= 2 arity)
                         [::ev/act-pair action (first picks') (second picks')]
                         [::ev/act action (first picks')]))
          (disarm!)))))

(defn- target-prompt
  "The mode indicator. Without this, arming an action was invisible and the
   player was left wondering why the board had stopped responding."
  []
  (when-let [{:keys [action picks]} (pending)]
    (let [arity (get @(rf/subscribe [::sub/arity]) action 1)]
      [:div {:class "sticky top-0 z-10 flex items-center justify-between gap-3
                     rounded-lg bg-amber-500 text-slate-900 px-4 py-3 shadow-lg"}
       [:div
        [:div {:class "font-bold"} (action-name action)]
        [:div {:class "text-xs"}
         (cond
           (and (= 2 arity) (= 1 (count picks))) (t :target/chosen-one)
           (= 2 arity) (t :target/pick-two)
           :else (t :target/pick-one))
         " — " (t :target/hint)]]
       [btn (t :target/cancel) disarm! {:tone :quiet}]])))

;; ------------------------------------------------------------- player cards ---

(defn- vote-count [votes id]
  (count (filter #(= id (val %)) votes)))

(defn- player-card
  "One seat, rendered large enough to actually read at a glance."
  [{:keys [id alive? connected?] pname :name}]
  (let [me @(rf/subscribe [::sub/me-view])
        view @(rf/subscribe [::sub/view])
        known @(rf/subscribe [::sub/known-roles])
        votes @(rf/subscribe [::sub/votes])
        {:keys [action picks]} (pending)
        targets (when action (set (get @(rf/subscribe [::sub/targets]) action)))
        selectable? (and targets (contains? targets id) (not (some #{id} picks)))
        picked? (boolean (some #{id} picks))
        me? (= id (:id me))
        role (get known id)
        n-votes (vote-count votes id)]
    [:div {:class (str "rounded-xl border-2 p-3 transition select-none "
                       (cond
                         picked?      "border-amber-400 bg-amber-500/20 "
                         selectable?  "border-amber-400 bg-slate-800 cursor-pointer hover:bg-slate-700 "
                         (not alive?) "border-slate-800 bg-slate-900/70 opacity-60 "
                         me?          "border-indigo-500 bg-slate-800 "
                         :else        "border-slate-700 bg-slate-800/50 ")
                       (when (and targets (not selectable?) (not picked?)) "opacity-40 "))
           :on-click (when selectable? #(pick! id))}
     [:div {:class "flex items-start gap-2"}
      ;; Seat number: the one label that never changes and never lies.
      [:div {:class (str "shrink-0 w-8 h-8 rounded-lg grid place-items-center font-bold "
                         (if alive? "bg-slate-700 text-slate-100" "bg-slate-800 text-slate-500"))}
       id]
      [:div {:class "min-w-0 flex-1"}
       [:div {:class (str "font-semibold truncate "
                          (when-not alive? "line-through text-slate-500"))}
        pname]
       [:div {:class "flex flex-wrap gap-1 mt-1"}
        (when me? [pill (t :game/you) :you])
        (when (= id (:host view)) [pill (t :badge/host) :host])
        (when-not alive? [pill (t :game/dead) :dead])
        (when (false? connected?) [pill (t :game/absent) :absent])
        (when role [pill (str (i18n/icon-of role) " " (role-name role)) :role])
        (when (= id (:lover me)) [pill (t :badge/lover) :lover])
        (when (contains? (set (:nominations view)) id) [pill (t :badge/accused) :accused])
        (when (pos? n-votes)
          [pill (str n-votes " " (t (if (= 1 n-votes) :badge/vote :badge/votes))) :votes])
        (when (contains? (set (:ready view)) id) [pill (t :badge/ready) :ready])
        (when (contains? (set (:nomination-done view)) id) [pill (t :badge/decided) :ready])
        (when (contains? (set (keys (:votes view))) id) [pill (t :badge/decided) :ready])]]]]))

(defn- player-grid []
  (let [view @(rf/subscribe [::sub/view])]
    [:div {:class "grid sm:grid-cols-2 gap-2"}
     (for [[id p] (sort-by key (:players view))]
       ^{:key id} [player-card (assoc p :id id)])]))

;; ------------------------------------------------------------------ actions ---

(def ^:private declines
  "The 'no' answers. Grouped apart from the rest so saying no is never hidden
   among the things that kill somebody."
  #{:ready :hexe-pass :nominate-pass :vote-abstain :jaeger-pass})

(defn- action-bar []
  (let [actions @(rf/subscribe [::sub/actions])
        targets @(rf/subscribe [::sub/targets])
        armed (:action (pending))
        needs-target? (fn [a] (seq (get targets a)))
        ;; GM powers are not player actions and have their own console below.
        ;; :advance-phase is one of them despite its plain name — `host-actions`
        ;; grants it, and its label has said "(SL)" all along.
        plain (sort (remove #(or (= "gm" (namespace %))
                                 (#{:chat :leave :set-locale :advance-phase} %))
                            actions))]
    (if (empty? plain)
      [:div {:class "text-sm text-slate-500"} (t :game/nothing-to-do)]
      [:div {:class "flex flex-wrap gap-2"}
       (for [a plain]
         ^{:key (str a)}
         [btn (action-name a)
          (if (needs-target? a)
            #(if (= armed a) (disarm!) (arm! a))
            #(rf/dispatch [::ev/act a]))
          {:tone (cond (= armed a) :armed
                       (declines a) :quiet
                       :else :primary)}])])))

;; ---------------------------------------------------------------- GM console ---
;;
;; Deliberately NOT the action bar. GM powers were rendered as more buttons in
;; the same row as "I'm done" and "Accuse", which was wrong twice over: it put
;; a kill switch a thumb's width from an ordinary move, and it assumed every
;; affordance points at a player. Two of these do not — `:gm/set-phase` and
;; `:gm/skip-role` name a phase and a role — so arming them dropped the host
;; into pick-a-player mode with no card to click. Both controls appeared to
;; exist and neither worked.
;;
;; So: a console of its own, closed by default, with a select for whatever kind
;; of thing each override actually names.

(def ^:private gm-confirm-first
  "Overrides that take two clicks.

   Not the irreversible ones — none of these are, the GM can revive and reassign
   back. The ones whose FIRST click is already public: both write a line the
   whole table reads, and 'sorry, wrong player' is not a thing the Chronik can
   unsay."
  #{:gm/force-kill :gm/reassign-role})

(defn- gm-select
  "A dropdown of options, rendering each through `label`. Values cross the DOM
   as strings, so `parse` puts them back into the type the protocol wants."
  [{:keys [value options label parse on-change]}]
  [:select {:class "px-2 py-1.5 rounded-lg bg-slate-800 border border-slate-600
                    text-sm text-slate-100 outline-none focus:border-violet-500"
            :value (str value)
            :on-change #(on-change (parse (.. % -target -value)))}
   (for [o options]
     ^{:key (str o)} [:option {:value (str o)} (label o)])])

(defn- gm-row
  "One override: its selects, then its button.

   The selects are built from `:targets` and `:choices` as the server sent them,
   so this function knows that an override may name a seat and/or a word, and
   nothing at all about which overrides exist."
  [action]
  (let [gm @(rf/subscribe [::sub/gm])
        players @(rf/subscribe [::sub/players])
        targets (get @(rf/subscribe [::sub/targets]) action)
        choices (get @(rf/subscribe [::sub/choices]) action)
        ;; Falls back to the first option so the button is never a no-op: an
        ;; untouched select still reads as its visible value.
        target (or (get-in gm [:target action]) (first targets))
        choice (or (get-in gm [:choice action]) (first choices))
        confirming? (= action (:confirm gm))
        ;; Not `set!` — that is a special form in ClojureScript, and shadowing
        ;; it fails to compile rather than merely reading badly.
        put! (fn [path v] (rf/dispatch [::ev/form-set :gm (assoc-in gm path v)]))
        fire! (fn []
                (rf/dispatch [::ev/act-with action
                              (cond-> {}
                                (seq targets) (assoc :target target)
                                (seq choices) (assoc (proto/choice-key action) choice))])
                (put! [:confirm] nil))]
    [:div {:class "flex flex-wrap items-center gap-2"}
     (when (seq targets)
       [gm-select {:value target
                   :options targets
                   :parse js/parseInt
                   :label #(or (get-in players [% :name]) (str "#" %))
                   :on-change #(put! [:target action] %)}])
     (when (seq choices)
       [gm-select {:value (some-> choice name)
                   :options (map name choices)
                   :parse keyword
                   :label #(let [k (keyword %)]
                             (if (= :gm/set-phase action) (phase-name k) (role-name k)))
                   :on-change #(put! [:choice action] %)}])
     [btn (if confirming? (t :gm/confirm) (action-name action))
      (if (and (gm-confirm-first action) (not confirming?))
        #(put! [:confirm] action)
        fire!)
      {:tone (cond confirming? :danger
                   (gm-confirm-first action) :quiet
                   :else :ghost)}]]))

(defn gm-panel
  "The narrator's console. Renders only for whoever holds the GM powers, because
   it is built from `:actions` and nobody else is offered any."
  []
  (let [actions @(rf/subscribe [::sub/actions])
        gm @(rf/subscribe [::sub/gm])
        open? (boolean (:open? gm))
        rows (sort-by str (filter #(or (= "gm" (namespace %)) (= :advance-phase %))
                                  actions))]
    (when (seq rows)
      [:div {:class "rounded-xl border border-violet-800/60 bg-violet-950/20 overflow-hidden"}
       [:button {:class "w-full px-4 py-2 bg-violet-900/40 flex items-center justify-between
                         text-left hover:bg-violet-900/60 transition"
                 :on-click #(rf/dispatch [::ev/form-set :gm (assoc gm :open? (not open?)
                                                                   :confirm nil)])}
        [:span {:class "text-sm font-bold uppercase tracking-wide text-violet-200"}
         (str "🛠 " (t :gm/panel))]
        [:span {:class "text-xs text-violet-300"} (t (if open? :gm/close :gm/open))]]
       (when open?
         [:div {:class "p-4 space-y-3"}
          ;; Said out loud, because a host who thinks an override is private
          ;; will use it as though it were.
          [:p {:class "text-xs text-violet-300/80"} (t :gm/panel-hint)]
          (for [a rows]
            ^{:key (str a)} [gm-row a])])])))

;; -------------------------------------------------------------------- panels ---

(defn me-panel
  "Who you are, first thing on screen. The single biggest gap in the first
   playtest: a role name in small text on the right tells a new player nothing
   about what they are supposed to do."
  []
  (let [me @(rf/subscribe [::sub/me-view])
        players @(rf/subscribe [::sub/players])
        role (:role me)]
    [:div {:class (str "rounded-xl p-4 border-2 "
                       (if (:alive? me)
                         "border-emerald-700 bg-emerald-950/40"
                         "border-rose-800 bg-rose-950/30"))}
     [:div {:class "flex items-center gap-3"}
      [:div {:class "text-4xl"} (i18n/icon-of role)]
      [:div {:class "min-w-0"}
       [:div {:class "text-xs uppercase tracking-wide text-slate-400"} (t :game/your-role)]
       [:div {:class "text-2xl font-bold leading-tight"} (role-name role)]]
      (when-not (:alive? me)
        [:div {:class "ml-auto"} [pill (t :game/dead) :dead]])]
     [:p {:class "text-sm text-slate-300 mt-2"} (role-desc role)]

     (when (:lover me)
       [:div {:class "mt-2 text-sm"}
        [:span {:class "text-slate-400"} (str (t :game/in-love) " ")]
        [:span {:class "text-pink-300 font-semibold"}
         (get-in players [(:lover me) :name])]])

     (when-let [r (:resources me)]
       [:div {:class "mt-3 flex gap-2"}
        (for [[k label] [[:heiltrank (t :game/heiltrank)] [:gifttrank (t :game/gifttrank)]]
              :when (contains? r k)]
          ^{:key (str k)}
          [:span {:class (str "px-2 py-1 rounded text-xs font-semibold "
                              (if (pos? (get r k))
                                "bg-sky-900 text-sky-200"
                                "bg-slate-800 text-slate-600 line-through"))}
           label])])

     (when (seq (:knows me))
       [:div {:class "mt-3 text-sm"}
        [:div {:class "text-xs uppercase tracking-wide text-slate-400"} (t :game/knows)]
        [:div {:class "flex flex-wrap gap-1 mt-1"}
         (for [[pid role] (sort-by key (:knows me))]
           ^{:key pid}
           [pill (str (get-in players [pid :name]) " · "
                      (i18n/icon-of role) " " (role-name role))
            :role])]])]))

(defn- log-icon [tone]
  (case tone
    :death "☠" :verdict "⚖" :reveal "👁" :night "🌙" :day "☀"
    :vote "🗳" :gm "🛠" "·"))

(defn- log-classes [tone]
  (case tone
    :death   "text-rose-300 font-semibold "
    :verdict "text-amber-300 font-semibold "
    :reveal  "text-emerald-300 "
    :night   "text-indigo-300 "
    :day     "text-sky-300 "
    :vote    "text-slate-200 "
    :gm      "text-violet-300 "
    :muted   "text-slate-500 "
    "text-slate-300 "))

(defn log-panel
  "The Chronik. Newest first, colour-coded by consequence, and given a column of
   its own — it is the only record of what the night did.

   Filtered to consequences by default. Every ready/pass/vote is in the log for
   auditability, and showing all of them by default buried the one line that
   said somebody died."
  []
  (let [log @(rf/subscribe [::sub/log])
        players @(rf/subscribe [::sub/players])
        show-all? @(rf/subscribe [::sub/show-all-log?])
        rows (i18n/chronicle (lc) players log {:show-all? show-all?})
        hidden (count (remove #(i18n/loud? (i18n/log-tone %)) log))]
    [:div {:class "rounded-xl border border-slate-700 bg-slate-900/80 overflow-hidden"}
     [:div {:class "px-4 py-2 bg-slate-800 flex items-center justify-between gap-2"}
      [:span {:class "text-sm font-bold uppercase tracking-wide"} (t :game/log)]
      (when (or show-all? (pos? hidden))
        [:button {:class "text-xs text-slate-400 hover:text-slate-100 underline"
                  :on-click #(rf/dispatch [::ev/toggle-log])}
         (if show-all?
           (t :log/show-less)
           (str "+" hidden " " (t :log/hidden)))])]
     [:div {:class "max-h-[28rem] overflow-y-auto divide-y divide-slate-800"}
      (for [[i {:keys [text tone]}] (map-indexed vector rows)]
        ^{:key i}
        [:div {:class (str "px-4 py-2 text-sm flex gap-2 "
                           (when (zero? i) "bg-slate-800/60 ")
                           (log-classes tone))}
         [:span {:class "shrink-0 opacity-60"} (log-icon tone)]
         [:span text]])]]))

(defn phase-header []
  (let [phase @(rf/subscribe [::sub/phase])
        view @(rf/subscribe [::sub/view])
        secs @(rf/subscribe [::sub/seconds-left])
        me @(rf/subscribe [::sub/me])
        waiting @(rf/subscribe [::sub/waiting-for])]
    [:div {:class "flex flex-wrap items-center justify-between gap-3"}
     [:div
      [:div {:class "flex items-baseline gap-2"}
       [:h2 {:class "text-2xl font-bold"} (phase-name phase)]
       (when (pos? (:night view 0))
         [:span {:class "text-slate-500"} (str "· " (t :phase/night) " " (:night view))])]
      [:div {:class "flex items-center gap-2"}
       [:span {:class "text-xs text-slate-500 font-mono"} (:room-id me)]
       [language-switch]]]
     [:div {:class "text-right"}
      (cond
        ;; A paused clock must SAY it is paused. Simply freezing the digits, or
        ;; hiding them, both read as a broken client.
        (:paused? view)
        [:div {:class "text-lg font-bold text-amber-400"} (str "⏸ " (t :game/paused))]

        secs
        [:div {:class (str "text-3xl font-mono leading-none "
                           (if (< secs 11) "text-rose-400" "text-slate-300"))}
         (str (quot secs 60) ":" (when (< (mod secs 60) 10) "0") (mod secs 60))])
      (when (seq waiting)
        [:div {:class "text-xs text-slate-500 mt-1"}
         (str (t :badge/waiting-for) ": " (str/join ", " waiting))])]]))

;; -------------------------------------------------------------------- chat ---

(defn chat-panel []
  (let [msgs @(rf/subscribe [::sub/chat])
        actions @(rf/subscribe [::sub/actions])
        me @(rf/subscribe [::sub/me-view])
        phase @(rf/subscribe [::sub/phase])
        allowed? (contains? actions :chat)
        channel (cond (false? (:alive? me)) :dead
                      (= :night phase) :wolves)]
    [:div {:class "flex flex-col h-64 rounded-xl border border-slate-700 bg-slate-900/80 overflow-hidden"}
     [:div {:class "px-4 py-2 bg-slate-800 text-sm font-bold uppercase tracking-wide"}
      (t :game/chat)]
     [:div {:class "flex-1 overflow-y-auto px-4 py-2 space-y-1 text-sm"}
      (for [[i m] (map-indexed vector msgs)]
        ^{:key i}
        [:div
         [:span {:class (case (:channel m)
                          :wolves "text-rose-400 font-semibold"
                          :dead   "text-slate-500 font-semibold"
                          "text-indigo-300 font-semibold")}
          (:name m) ": "]
         [:span {:class (when (= :dead (:channel m)) "text-slate-500")} (:text m)]])]
     [:div {:class "border-t border-slate-700 p-2 space-y-1"}
      (when (and allowed? channel)
        ;; The label is derived from the same state the server routes on, so it
        ;; cannot promise the wrong audience.
        [:div {:class (if (= :wolves channel) "text-xs text-rose-400" "text-xs text-slate-500")}
         (t (if (= :wolves channel) :game/wolves-channel :game/dead-channel))])
      [:div {:class "flex gap-2"}
       [:div {:class "flex-1"}
        [field :text (t :game/chat-hint) {:on-enter [::ev/send-chat] :maxlength 500}]]
       [btn "→" #(rf/dispatch [::ev/send-chat]) {:disabled? (not allowed?)}]]]]))

;; -------------------------------------------------------------------- lobby ---

(defn lobby []
  (let [view @(rf/subscribe [::sub/view])
        me @(rf/subscribe [::sub/me])
        actions @(rf/subscribe [::sub/actions])]
    [:div {:class "space-y-4"}
     [:div {:class "flex flex-wrap items-baseline justify-between gap-2"}
      [:h2 {:class "text-2xl font-bold"} (t :lobby/title)]
      [:div {:class "text-right"}
       [:div {:class "text-xs uppercase tracking-wide text-slate-400"} (t :lobby/share)]
       [:div {:class "font-mono text-3xl tracking-[0.3em] text-indigo-300"} (:room-id me)]
       [:div {:class "flex justify-end mt-1"} [language-switch]]]]
     [:div {:class "text-sm text-slate-400"}
      (str (count (:players view)) " / " (:min-players view) " " (t :lobby/players))]
     [player-grid]
     [:div {:class "space-y-2"}
      [:div {:class "flex flex-wrap items-center gap-3"}
       (if (contains? actions :start-game)
         [btn (t :lobby/start) #(rf/dispatch [::ev/act :start-game])]
         [:span {:class "text-sm text-slate-500"}
          (if (:host? me) (t :lobby/need-more) (t :game/waiting))])
       [btn (t :lobby/leave) #(rf/dispatch [::ev/leave]) {:tone :quiet}]]
      ;; The host may start under the recommended size, so say so rather than
      ;; letting a start button next to "3 / 5" look like a bug. Derived from
      ;; the offer, not from a rule of its own: if the server did not offer
      ;; :start-game, the table is below the deck's floor and there is nothing
      ;; to recommend.
      (when (and (contains? actions :start-game)
                 (< (count (:players view)) (:min-players view 5)))
        [:p {:class "text-xs text-amber-400/90"}
         (t :lobby/below-recommended (:min-players view))])]
     [chat-panel]]))

;; --------------------------------------------------------------------- game ---

(defn- game-over-panel
  "The end of the game, and the way out of it.

   There was no way out: at :game-over the engine offered no actions, so the
   final screen was a board nobody could leave except by closing the tab. The
   button is rendered from `:actions` like everything else — the engine now
   offers :leave once the game is decided."
  []
  (let [view @(rf/subscribe [::sub/view])
        actions @(rf/subscribe [::sub/actions])]
    (when (= :game-over (:phase view))
      [:div {:class "rounded-xl border-2 border-amber-600 bg-amber-950/30 p-4 space-y-3"}
       [:div {:class "text-2xl font-bold text-amber-300"}
        (t (case (:winner view)
             :werewolf :log/win-werewolf
             :lovers   :log/win-lovers
             :log/win-village))]
       ;; Every role is public now — view-for reveals them all once the game is
       ;; decided — so the table can finally read who everyone was.
       [:p {:class "text-sm text-amber-100/80"} (t :game/over-hint)]
       (when (contains? actions :leave)
         [btn (t :game/new-round) #(rf/dispatch [::ev/leave])])])))

(defn game []
  [:div {:class "space-y-4"}
   [phase-header]
   [target-prompt]
   [:div {:class "grid lg:grid-cols-5 gap-4"}
    [:div {:class "lg:col-span-3 space-y-4"}
     [game-over-panel]
     [me-panel]
     [action-bar]
     [gm-panel]
     [player-grid]]
    [:div {:class "lg:col-span-2 space-y-4"}
     [log-panel]
     [chat-panel]]]])

;; ---------------------------------------------------------------------- app ---

(defn app []
  (let [screen @(rf/subscribe [::sub/screen])
        phase @(rf/subscribe [::sub/phase])]
    [:div {:class "min-h-screen bg-slate-900 text-slate-100 font-sans"}
     [connection-banner]
     [error-banner]
     [:div {:class "max-w-6xl mx-auto p-4 sm:p-6"}
      (case screen
        :landing [landing]
        :room (if (or (nil? phase) (= :lobby phase)) [lobby] [game])
        [landing])]]))
