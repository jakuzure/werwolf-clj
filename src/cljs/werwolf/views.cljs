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
            [reagent.core :as r]
            [werwolf.build-info :as build-info]
            [werwolf.engine.roles :as roles]
            [werwolf.events :as ev]
            [werwolf.i18n.core :as i18n]
            [werwolf.i18n.recap :as recap]
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
  ([label on-click {:keys [tone disabled? testid]}]
   [:button {:class (str "px-3 py-2 rounded-lg text-sm font-semibold transition "
                         "disabled:opacity-40 disabled:cursor-not-allowed "
                         (case tone
                           :danger "bg-rose-700 hover:bg-rose-600 text-white "
                           ;; On a red banner, where a red button would vanish.
                           :inverse "bg-white hover:bg-rose-50 text-rose-700 "
                           :ghost  "bg-slate-700 hover:bg-slate-600 text-slate-100 "
                           :armed  "bg-amber-500 hover:bg-amber-400 text-slate-900 ring-2 ring-amber-300 "
                           :quiet  "bg-transparent border border-slate-600 hover:bg-slate-800 text-slate-300 "
                           "bg-indigo-600 hover:bg-indigo-500 text-white "))
             :disabled (boolean disabled?)
             :data-testid testid
             :on-click on-click}
    label]))

(defn- field
  "A text input that is UNCONTROLLED: React is never given a `:value`, so it never
   writes one back into the element.

   That is the fix for the Android bug, and the third attempt at it. The reported
   symptom was typing `Greta`, accepting the keyboard's suggestion, and getting
   `GrGrGreta`.

   The mechanism, finally pinned down with a DevTools-Protocol test that drives a
   REAL composing region (`Input.imeSetComposition`) rather than dispatching
   synthetic CompositionEvents, which cannot reproduce it:

   Writing `node.value` while a composition is active COMMITS the composing text
   early. The next composition then starts after it and appends. So it does not
   matter whether the value React writes is stale or perfectly correct — the write
   itself is the bug. An earlier version of this component kept a synchronous local
   draft so the value would always match, and it still failed: the test produced
   `GGrGreGreta`.

   And the write is not even provoked by typing. `::ev/tick` re-renders the whole
   app every 500 ms to move the countdown, which is enough on its own to land a
   reconciliation in the middle of a word.

   So the DOM owns the text. app-db is fed one way, from `:on-change`, and stays
   the thing Enter and the send buttons read. The only writes back into the element
   are PROGRAMMATIC ones — `::ev/form-put` filling in a tapped room code, or
   clearing the chat box after a send — applied imperatively in
   `:component-did-update` when the generation counter moves. Comparing generations
   rather than values is what keeps ordinary typing from counting as a write: the
   value legitimately differs from app-db for a tick, which is where this started."
  [_k _placeholder & _opts]
  (let [node  (atom nil)
        ;; Plain atoms, not ratoms: these must not make the component reactive.
        ;; `:want` is recorded during render and applied after it.
        want  (atom {:gen nil :value ""})
        seen  (atom {:gen nil})]
    (r/create-class
     {:display-name "werwolf-field"

      :component-did-update
      (fn [_this _]
        (let [{:keys [gen value]} @want]
          (when (and @node (not= gen (:gen @seen)))
            (reset! seen {:gen gen})
            ;; Only reached for a programmatic write, so clobbering whatever is in
            ;; the element is the intent rather than a side effect.
            (set! (.-value @node) (or value "")))))

      :reagent-render
      (fn [k placeholder & [{:keys [on-enter maxlength testid]}]]
        (let [committed @(rf/subscribe [::sub/form-field k])
              gen       @(rf/subscribe [::sub/form-gen k])]
          (reset! want {:gen gen :value committed})
          ;; Seed `seen` on the first render so mounting is not mistaken for a
          ;; programmatic write on the next update.
          (when (nil? (:gen @seen)) (reset! seen {:gen gen}))
          [:input {:class "w-full px-3 py-2 rounded-lg bg-slate-800 border border-slate-700
                           focus:border-indigo-500 outline-none text-slate-100"
                   ;; :default-value, never :value — React applies it on mount and
                   ;; then leaves the element alone, which is the entire point.
                   :default-value committed
                   :ref (fn [el] (reset! node el))
                   :placeholder placeholder
                   :data-testid testid
                   :maxLength (or maxlength 24)
                   :on-change (fn [e] (rf/dispatch [::ev/form-set k (.. e -target -value)]))
                   :on-key-down #(when (and on-enter (= "Enter" (.-key %)))
                                   (rf/dispatch on-enter))}]))})))

(defn- pill [label tone]
  [:span {:class (str "px-1.5 py-0.5 rounded text-[10px] font-bold uppercase tracking-wide "
                      (case tone
                        :dead    "bg-rose-950 text-rose-300 "
                        :absent  "bg-amber-950 text-amber-300 "
                        :host    "bg-violet-800 text-violet-100 "
                        :role    "bg-emerald-900 text-emerald-200 "
                        :votes   "bg-slate-600 text-slate-100 "
                        :lover   "bg-pink-900 text-pink-200 "
                        :mayor   "bg-amber-700 text-amber-50 "
                        :voice   "bg-sky-900 text-sky-200 "
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

(defn- sound-switch
  "The phase chime, on or off. Beside the language because it is the same kind
   of thing — how this device presents the game, not part of it — and small,
   because it is set once and forgotten."
  []
  (let [on? @(rf/subscribe [::sub/sound-on?])
        label (t (if on? :sound/turn-off :sound/turn-on))]
    [:button {:class "px-2 py-1 rounded text-xs bg-slate-800 hover:bg-slate-700 transition"
              :title label
              :aria-label label
              :aria-pressed (boolean on?)
              :data-testid "sound-switch"
              :on-click #(rf/dispatch [::ev/toggle-sound])}
     (if on? "🔔" "🔕")]))

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
  (let [rooms @(rf/subscribe [::sub/rooms])
        pending? @(rf/subscribe [::sub/seat-pending?])]
    [:div {:class "space-y-2"}
     [:div {:class "flex items-baseline justify-between"}
      [:span {:class "text-sm text-slate-400"} (t :landing/open-rooms)]
      [:button {:class "text-xs text-slate-500 hover:text-slate-200 underline"
                :on-click #(rf/dispatch [::ev/refresh-rooms])}
       (t :landing/refresh)]]
     (if (empty? rooms)
       [:p {:class "text-sm text-slate-600"} (t :landing/no-rooms)]
       [:div {:class "space-y-2"}
        (for [{:keys [room-id host players max-players locked?]} rooms]
          ^{:key room-id}
          [:button {:class "w-full flex items-center justify-between gap-3 px-3 py-3
                            rounded-lg bg-slate-800 border border-slate-700
                            hover:border-indigo-500 hover:bg-slate-700 transition text-left
                            disabled:opacity-40 disabled:cursor-not-allowed"
                    :disabled pending?
                    :on-click #(rf/dispatch [::ev/join-listed room-id locked?])}
           [:div {:class "min-w-0"}
            [:div {:class "flex items-center gap-2"}
             [:span {:class "font-mono text-lg tracking-[0.2em] text-indigo-300"} room-id]
             ;; Listed all the same: the lock keeps strangers out, and hiding
             ;; the room would mostly keep friends from finding it.
             (when locked?
               [:span {:title (t :landing/locked) :aria-label (t :landing/locked)
                       :data-testid "room-locked"}
                "🔒"])]
            (when host
              [:div {:class "text-xs text-slate-400 truncate"} host])]
           [:span {:class "shrink-0 text-xs text-slate-400"}
            (str players " / " max-players)]])])]))

(def ^:private guide-teams
  "The roles as a newcomer meets them: the pack, then the village. From the
   registry, so a new role turns up here without anybody remembering to add
   it, in the order the lobby's menu lists them."
  (let [ids (conj roles/special-ids roles/villager)]
    [[:werewolf (filterv #(= :werewolf (roles/team-of %)) ids)]
     [:village  (filterv #(= :village (roles/team-of %)) ids)]]))

(defn- how-to-play
  "For somebody who has never played: what the game is, how a round goes, who
   wins, and every card. Folded away, so the two fields the regulars came for
   stay at the top; the page's job is still getting people into a room."
  []
  [:details {:class "rounded-xl border border-slate-700 bg-slate-900/60 group"
             :data-testid "how-to-play"}
   [:summary {:class "cursor-pointer select-none px-4 py-3 font-semibold text-slate-200
                      list-none flex items-center justify-between"}
    [:span (str "📖 " (t :guide/title))]
    [:span {:class "text-slate-500 transition group-open:rotate-90" :aria-hidden true} "›"]]
   [:div {:class "px-4 pb-4 space-y-4 text-sm text-slate-300 leading-relaxed"}
    [:p (t :guide/intro)]
    (for [[k icon] [[:night "🌙"] [:day "☀️"] [:win "🏆"]]]
      ^{:key k}
      [:div
       [:h3 {:class "font-semibold text-slate-100"} (str icon " " (t (keyword "guide" (str (name k) "-title"))))]
       [:p (t (keyword "guide" (name k)))]])
    [:div {:class "space-y-3"}
     [:h3 {:class "font-semibold text-slate-100"} (str "🃏 " (t :guide/roles-title))]
     (for [[team ids] guide-teams]
       ^{:key (str team)}
       [:div {:class "space-y-2"}
        [:div {:class "text-xs uppercase tracking-wide text-slate-500"}
         (t (keyword "guide" (str "team-" (name team))))]
        [:ul {:class "space-y-2"}
         (for [r ids]
           ^{:key (str r)}
           [:li {:class "flex gap-2"}
            [:span {:class "text-lg leading-none shrink-0" :aria-hidden true} (i18n/icon-of r)]
            [:div
             [:span {:class "font-semibold text-slate-100"} (role-name r)]
             [:div {:class "text-slate-400"} (role-desc r)]]])]])
     [:div {:class "flex gap-2"}
      [:span {:class "text-lg leading-none shrink-0" :aria-hidden true} "🎖"]
      [:div
       [:span {:class "font-semibold text-slate-100"} (t :guide/mayor-title)]
       [:div {:class "text-slate-400"} (t :guide/mayor)]]]]
    [:p {:class "text-slate-400"} (t :guide/table)]]])

(defn landing []
  ;; Greyed out while a create or join is unanswered. The events already drop a
  ;; second tap; this is so the first one visibly took.
  (let [pending? @(rf/subscribe [::sub/seat-pending?])
        ask-password? @(rf/subscribe [::sub/join-password?])]
    [:div {:class "max-w-sm mx-auto mt-16 space-y-8"}
     [:div {:class "flex justify-center"} [language-switch]]
     [:h1 {:class "text-3xl font-bold text-center"} (t :app/title)]
     [:div {:class "space-y-3"}
      [field :name (t :landing/name)]
      ;; A plain text field, not type=password. The word is read out at the
      ;; table like the room code, so hiding it from the person typing protects
      ;; nothing and turns a typo on a phone keyboard into a mystery.
      [field :room-password (t :landing/password)
       {:maxlength 64 :testid "create-password" :on-enter [::ev/create-room]}]
      [btn (t :landing/create) #(rf/dispatch [::ev/create-room]) {:disabled? pending?}]]
     [:div {:class "text-center text-slate-500 text-sm"} (t :landing/or)]
     ;; The list first, the code field under it: typing a code is now the
     ;; fallback for a room the list cannot show, not the normal way in.
     [open-rooms]
     [:div {:class "space-y-3"}
      [field :code (t :landing/code) {:on-enter [::ev/join-room] :maxlength 4}]
      ;; Only once a room has turned out to want one — tapped as locked in the
      ;; list, or refused for the want of it — so the ordinary join stays two
      ;; fields long.
      (when ask-password?
        [field :join-password (t :landing/join-password)
         {:on-enter [::ev/join-room] :maxlength 64 :testid "join-password"}])
      [btn (t :landing/join) #(rf/dispatch [::ev/join-room])
       {:tone :ghost :disabled? pending?}]]
     [how-to-play]
     ;; Which build is running: the first question when a friend reports a bug.
     [:div {:class "text-center text-xs text-slate-600" :data-testid "app-version"}
      (str "v" build-info/version " · " build-info/commit)]]))

;; ---------------------------------------------------------------- targeting ---
;;
;; A pending action lives in the form map as {:action a :picks [ids]}. Arity
;; comes from the view, so this code never mentions a role by name.

(defn- pending [] @(rf/subscribe [::sub/pending]))

(defn- arm! [action]
  (rf/dispatch [::ev/form-set :pending {:action action :picks []}]))

(defn- disarm! []
  (rf/dispatch [::ev/form-set :pending nil]))

(def ^:private confirm-first
  "Moves that cost somebody their life and cannot be taken back: they wait
   for a second tap after the pick. One mis-tap on a crowded board used to
   poison the wrong player. The day's vote is not here — it can be changed
   until everybody has voted."
  #{:hexe-poison :jaeger-shoot :weisser-kill :werewolf-vote})

(defn- fire! [action picks]
  (rf/dispatch (if (= 2 (count picks))
                 [::ev/act-pair action (first picks) (second picks)]
                 [::ev/act action (first picks)]))
  (disarm!))

(defn- pick!
  "Add a target to the pending action, and fire once enough are chosen.

   For a two-target action the command carries :a and :b, which is the shape the
   engine has always wanted — it was the wire schema that lacked them, and
   because that schema is closed, Amor was not degraded but unplayable."
  [id]
  (let [{:keys [action picks confirm?]} (pending)
        arity (get @(rf/subscribe [::sub/arity]) action 1)
        ;; Tapping another card while asked to confirm is choosing again.
        picks' (conj (if confirm? [] (vec picks)) id)]
    (cond
      (< (count picks') arity)
      (rf/dispatch [::ev/form-set :pending {:action action :picks picks'}])

      (confirm-first action)
      (rf/dispatch [::ev/form-set :pending {:action action :picks picks' :confirm? true}])

      :else (fire! action picks'))))

(defn- target-prompt
  "The mode indicator. Without this, arming an action was invisible and the
   player was left wondering why the board had stopped responding."
  []
  (when-let [{:keys [action picks confirm?]} (pending)]
    (let [arity (get @(rf/subscribe [::sub/arity]) action 1)
          players @(rf/subscribe [::sub/players])]
      (if confirm?
        ;; The second tap. Red, and it names the player, so what is about to
        ;; happen is read before it happens.
        [:div {:class "sticky top-0 z-10 flex flex-wrap items-center justify-between gap-3
                       rounded-lg bg-rose-600 text-white px-4 py-3 shadow-lg"
               :data-testid "confirm-pick"}
         [:div {:class "font-bold"}
          (t :target/confirm (action-name action) (get-in players [(first picks) :name]))]
         [:div {:class "flex gap-2"}
          [btn (t :target/repick) #(rf/dispatch [::ev/form-set :pending {:action action :picks []}])
           {:tone :ghost}]
          [btn (t :target/yes) #(fire! action picks) {:tone :inverse :testid "confirm-yes"}]]]
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
       [btn (t :target/cancel) disarm! {:tone :quiet}]]))))

(defn- narrator-waits
  "Who the game is still waiting on, by name and card, and a way past them.

   The Spielleiter used to learn that the Seherin had not looked yet only from
   the silence, and the override that ends her turn sat in a closed console under
   the board. Now the names are on the stage, with the skip right beside them:
   the role being called at night, the phase by day.

   Two taps while somebody is still owed — skipping a player who is about to act
   takes their move away — and one when nobody is."
  []
  (let [owing   @(rf/subscribe [::sub/owing])
        players @(rf/subscribe [::sub/players])
        known   @(rf/subscribe [::sub/known-roles])
        actions @(rf/subscribe [::sub/actions])
        cursor  @(rf/subscribe [::sub/night-cursor])
        view    @(rf/subscribe [::sub/view])
        armed   (:skip-confirm @(rf/subscribe [::sub/form]))
        ;; Keyed on where the table is, so a confirm left armed cannot fire on
        ;; the next role or phase.
        here    [(:phase view) (:night view) cursor]
        skip    (cond (and cursor (contains? actions :gm/skip-role)) :gm/skip-role
                      (contains? actions :advance-phase)              :advance-phase)
        fire!   (fn []
                  (rf/dispatch (if (= :gm/skip-role skip)
                                 [::ev/act-with :gm/skip-role {:role cursor}]
                                 [::ev/act :advance-phase]))
                  (rf/dispatch [::ev/form-set :skip-confirm nil]))
        label   (if (= :gm/skip-role skip)
                  (t :narrator/skip-role (str (i18n/icon-of cursor) " " (role-name cursor)))
                  (t (if (seq owing) :narrator/advance :narrator/advance-free)))]
    [:div {:class "mt-2 space-y-2"}
     (if (seq owing)
       [:div {:class "flex flex-wrap items-center gap-1.5 text-sm" :data-testid "owing"}
        [:span {:class "opacity-80"} (str (t :narrator/owing) ":")]
        (for [pid owing]
          ^{:key pid}
          [:span {:class "rounded-md bg-amber-500/90 text-slate-900 px-2 py-0.5 font-semibold"}
           (str (some-> (get known pid) i18n/icon-of (str " "))
                (get-in players [pid :name] (str "#" pid)))])]
       [:div {:class "text-sm opacity-80"} (t :narrator/owing-none)])
     (when skip
       (if (and (seq owing) (= here armed))
         [:div {:class "flex flex-wrap gap-2"}
          [btn (t :narrator/really (count owing)) fire! {:tone :danger}]
          [btn (t :narrator/keep-waiting) #(rf/dispatch [::ev/form-set :skip-confirm nil])
           {:tone :ghost}]]
         [btn label (if (seq owing) #(rf/dispatch [::ev/form-set :skip-confirm here]) fire!)
          {:tone :quiet :testid "narrator-skip"}]))]))

(defn- turn-banner
  "One line, high on the screen, answering 'is the game waiting for ME?'.

   The most common complaint from the playtest was not knowing whose turn it was.
   Everything needed was already on the client — `::can-act?`, `:night-cursor`,
   `:awaiting-reaction` — and none of it was rendered: the phase header said
   \"Nacht\" and left every player to guess whether that meant them.

   Three states, in order of who is being waited on:

   * You owe a reaction. The whole table is stopped on you, so this is red.
   * You have something you may do. Green, and it says so in words rather than
     leaving you to infer it from a button being enabled.
   * You are waiting. Say what for — the role being called, or the names still
     to answer — because unexplained silence is what players read as a bug.

   Deliberately says nothing at :game-over and in the lobby: there is no turn to
   take, and a banner that is always present is a banner nobody reads."
  []
  (let [phase    @(rf/subscribe [::sub/phase])
        can-act? @(rf/subscribe [::sub/can-act?])
        reaction @(rf/subscribe [::sub/awaiting-reaction])
        waiting  @(rf/subscribe [::sub/waiting-for])
        me       @(rf/subscribe [::sub/me-view])
        narrator? @(rf/subscribe [::sub/narrator?])
        asleep?  @(rf/subscribe [::sub/asleep?])
        plan     @(rf/subscribe [::sub/night-plan])
        deal-problem (:deal-problem @(rf/subscribe [::sub/narrator-info]))]
    (when-not (#{:lobby :game-over} phase)
      (let [[tone headline detail]
            (cond
              ;; The Spielleiter is never "dran" and never waiting: his console
              ;; always offers something, so `can-act?` would call every moment
              ;; his turn. Tell him instead what the table is doing.
              ;; Who he is waiting on is drawn below the headline, by
              ;; `narrator-waits`, with the way past them.
              narrator?
              ["border-violet-500 bg-violet-950/50 text-violet-100"
               (t :turn/narrating)
               (when (= :setup phase)
                 ;; Instead of the hint, not under it: the button the hint
                 ;; points at is not there until the deal is fixed.
                 (if deal-problem
                   [:span {:class "text-amber-300 font-semibold" :data-testid "deal-problem"}
                    (str "⚠ " (t :setup/problem (i18n/deck-problem-text (lc) deal-problem nil nil)))]
                   (t :setup/hint)))]

              reaction
              ["border-rose-500 bg-rose-950/60 text-rose-100"
               (t :turn/yours) (t :turn/urgent)]

              can-act?
              ["border-emerald-500 bg-emerald-950/50 text-emerald-100"
               (t :turn/yours) (t :turn/hint)]

              ;; Asleep: what the night is doing and what comes next, which is
              ;; all a sleeping player can use.
              asleep?
              ["border-indigo-800 bg-indigo-950/40 text-indigo-100"
               (str "😴 " (t :turn/asleep))
               (when-let [r (sub/next-up plan)]
                 (t :turn/asleep-next (role-name r)))]

              :else
              ["border-slate-700 bg-slate-900/60 text-slate-400"
               (t :game/waiting)
               (cond
                 ;; Not the role being called at night: the stage shows that as a
                 ;; chip directly above. Naming the players still to act would leak
                 ;; which roles are alive, which is why ::waiting-for skips night.
                 (= :setup phase) (t :setup/waiting)
                 (seq waiting) (str (t :badge/waiting-for) ": " (str/join ", " waiting))
                 ;; A dead player is not being waited on and never will be, so do
                 ;; not imply their input is pending.
                 (false? (:alive? me)) (t :game/dead-hint))])]
        [:div {:class (str "rounded-xl border-2 px-4 py-3 " tone)}
         [:div {:class "font-bold"} headline]
         (when detail [:div {:class "text-sm opacity-90"} detail])
         (when (and narrator? (not= :setup phase)) [narrator-waits])]))))

(defn- voice-panel
  "Join the call, and mute yourself.

   Absent entirely when the server carries no audio, because a text-only server is
   a supported configuration and a disabled button would invite people to keep
   tapping it.

   Muting is local and voluntary — it is your own microphone, not a rule. The
   server enforces nothing about who may speak, so this button is the honest
   extent of it: it says what it does and does not pretend to be a game
   mechanic."
  []
  (when @(rf/subscribe [::sub/audio-available?])
    (let [state  @(rf/subscribe [::sub/audio-state])
          muted? @(rf/subscribe [::sub/audio-muted?])
          ;; The engine never offers an action with no legal target; the same
          ;; honesty applies here. Outside a secure context there is no
          ;; microphone to open, so say that instead of showing a button whose
          ;; only outcome is an error.
          secure? (and (exists? js/isSecureContext) js/isSecureContext)
          players @(rf/subscribe [::sub/players])
          in-call (sort (keep #(get-in players [% :name]) @(rf/subscribe [::sub/in-voice])))]
      [:div {:class "rounded-xl border border-slate-700 bg-slate-900/80 px-4 py-3
                     flex flex-wrap items-center gap-3"}
       [:span {:class "text-sm font-bold uppercase tracking-wide text-slate-400"}
        (t :voice/title)]
       (case (if secure? state :insecure)
         :insecure [:span {:class "text-sm text-amber-400/90"}
                    (t :error/insecure-context)]
         :idle    [btn (t :voice/join) #(rf/dispatch [::ev/join-audio])]
         :joining [:span {:class "text-sm text-slate-400"} (t :voice/joining)]
         :joined  [:<>
                   [btn (if muted? (str "🔇 " (t :voice/unmute))
                                   (str "🎙 " (t :voice/mute)))
                    #(rf/dispatch [::ev/toggle-mute])
                    {:tone (if muted? :quiet :armed)}]
                   [btn (t :voice/leave) #(rf/dispatch [::ev/leave-audio]) {:tone :quiet}]]
         nil)
       (when (= :joined state)
         [:span {:class "text-xs text-slate-500"} (t :voice/everyone-hears)])
       ;; Who is already there, so joining is an answer to "are the others in
       ;; the call?" rather than a leap.
       [:div {:class "basis-full text-sm" :data-testid "in-call"}
        (if (seq in-call)
          [:<> [:span {:class "text-slate-400"} (str (t :voice/in-call-list) ": ")]
           [:span {:class "text-sky-200"} (str/join ", " in-call)]]
          [:span {:class "text-slate-500"} (t :voice/nobody-in-call)])]])))

;; ------------------------------------------------------------- player cards ---

(defn- vote-count [votes id]
  (count (filter #(= id (val %)) votes)))

(defn- card-controls
  "The seat's own buttons, for whoever holds the host's powers. Both are drawn
   from what the server offered for THIS seat, so a card nobody may act on has
   none:

   * in the lobby, handing the host's seat over — the Spielleitung, in a
     narrated room — to this player. Two clicks: the first one is not undoable
     by the person who made it, since it is no longer theirs to undo;
   * in the lobby, removing this player. Two clicks as well: the first one
     already writes a line in the Chronik and sends somebody back to the start,
     and 'sorry, wrong card' undoes neither. Arming one of the two disarms the
     other, so there is never more than one red button on the board;
   * in :setup, the Spielleiter's card select. Every change is silent and
     immediate, which is what makes a select the right control: arranging a deal
     is a dozen small corrections, not one decision."
  [id]
  (let [targets @(rf/subscribe [::sub/targets])
        choices @(rf/subscribe [::sub/choices])
        phase   @(rf/subscribe [::sub/phase])
        known   @(rf/subscribe [::sub/known-roles])
        narrated? @(rf/subscribe [::sub/narrated?])
        gm      @(rf/subscribe [::sub/gm])
        transfer? (some #{id} (get targets :gm/transfer-host))
        kick?     (some #{id} (get targets :gm/kick))
        assign?   (and (= :setup phase) (some #{id} (get targets :gm/reassign-role)))
        confirming? (= id (:confirm-transfer gm))
        confirming-kick? (= id (:confirm-kick gm))]
    (when (or transfer? kick? assign?)
      [:div {:class "mt-2 flex flex-wrap gap-2"
             ;; A control inside a card must not count as clicking the card,
             ;; which in targeting mode would pick the seat.
             :on-click #(.stopPropagation %)}
       (when transfer?
         [btn (if confirming?
                (t :gm/confirm)
                (t (if narrated? :lobby/make-narrator :lobby/make-host)))
          (if confirming?
            #(do (rf/dispatch [::ev/act :gm/transfer-host id])
                 (rf/dispatch [::ev/form-set :gm (dissoc gm :confirm-transfer)]))
            #(rf/dispatch [::ev/form-set :gm (-> gm (dissoc :confirm-kick)
                                                 (assoc :confirm-transfer id))]))
          {:tone (if confirming? :danger :quiet)
           :testid (str "make-host-" id)}])
       (when kick?
         [btn (t (if confirming-kick? :kick/confirm :kick/button))
          (if confirming-kick?
            #(do (rf/dispatch [::ev/act :gm/kick id])
                 (rf/dispatch [::ev/form-set :gm (dissoc gm :confirm-kick)]))
            #(rf/dispatch [::ev/form-set :gm (-> gm (dissoc :confirm-transfer)
                                                 (assoc :confirm-kick id))]))
          {:tone (if confirming-kick? :danger :quiet)
           :testid (str "kick-" id)}])
       (when assign?
         [:select {:class "px-2 py-1.5 rounded-lg bg-slate-800 border border-violet-600
                           text-sm text-slate-100 outline-none focus:border-violet-400"
                   :data-testid (str "assign-" id)
                   :value (some-> (get known id) name)
                   :on-change #(rf/dispatch [::ev/act-with :gm/reassign-role
                                             {:target id
                                              :role (keyword (.. % -target -value))}])}
          (for [r (get choices :gm/reassign-role)]
            ^{:key (str r)}
            [:option {:value (name r)} (str (i18n/icon-of r) " " (role-name r))])])])))

(defn- initial
  "The first letter of a name, for the round badge on a player's card. The
   first code point rather than the first char, so a name starting with an
   emoji shows the emoji and not half of it."
  [pname]
  (let [s (str/trim (str pname))]
    (if (seq s)
      (str/upper-case (.. js/Array (from s) (at 0)))
      "?")))

(defn- player-card
  "One seat, rendered large enough to actually read at a glance."
  [{:keys [id alive? connected?] pname :name}]
  (let [me @(rf/subscribe [::sub/me-view])
        view @(rf/subscribe [::sub/view])
        narrator-info @(rf/subscribe [::sub/narrator-info])
        known @(rf/subscribe [::sub/known-roles])
        votes @(rf/subscribe [::sub/votes])
        {:keys [action picks]} (pending)
        targets (when action (set (get @(rf/subscribe [::sub/targets]) action)))
        selectable? (and targets (contains? targets id) (not (some #{id} picks)))
        picked? (boolean (some #{id} picks))
        me? (= id (:id me))
        ;; The Spielleiter's seat. Drawn first and dashed — see `player-grid` —
        ;; so it reads as what it is, not as one more player who happens to be
        ;; alive.
        sl? (= id (:narrator view))
        role (get known id)
        n-votes (vote-count votes id)
        ;; Safe to show because everyone shares one media room and nobody is
        ;; muted by the server — so who is talking reveals nothing. It would NOT
        ;; be safe under the plan's permission table, where the wolves' absence
        ;; from the main room identifies them. See werwolf.media.
        in-voice @(rf/subscribe [::sub/in-voice])
        talking? (sub/speaking? @(rf/subscribe [::sub/speaking]) id)
        lobby?   (= :lobby (:phase view))
        ballot?  (and (= :discussion (:phase view)) (:trial? view))
        ;; Each "done" mark only in the phase it answers. The sets outlive their
        ;; phase in the view, so a card at the end of a day read "Fertig · Hat
        ;; gewählt · Hat gewählt" — three stale ticks saying nothing about now.
        done?    (cond
                   (and ballot? (contains? (:votes view) id)) (t :badge/decided)
                   (and (= :discussion (:phase view)) (not (:trial? view))
                        (contains? (set (:ready view)) id))   (t :badge/ready))]
    [:div {:class (str "rounded-xl border-2 p-3 transition select-none "
                       (cond
                         picked?      "border-amber-400 bg-amber-500/20 "
                         selectable?  "border-amber-400 bg-slate-800 cursor-pointer hover:bg-slate-700 "
                         (not alive?) "border-slate-800 bg-slate-900/70 opacity-60 "
                         sl?          "border-dashed border-violet-700 bg-violet-950/30 "
                         me?          "border-indigo-500 bg-slate-800 "
                         :else        "border-slate-700 bg-slate-800/50 ")
                       (when (and targets (not selectable?) (not picked?)) "opacity-40 "))
           ;; The second and last test hook. A browser test picking a target has
           ;; to click a specific seat, and nothing on the card identifies one
           ;; uniquely — two players may share a name.
           ;; Reveals nothing: seat ids are public and already in every view.
           :data-testid (str "seat-" id)
           :on-click (when selectable? #(pick! id))}
     [:div {:class "flex items-start gap-2"}
      ;; The player's initial. It carries the speaking ring, because it is the
      ;; one element on the card whose position never moves as pills wrap.
      ;; Not the seat number, which it used to be: 0, 1, 2 is the server's
      ;; bookkeeping, and on the table it read like a ranking or a turn order.
      ;;
      ;; The small states sit on its rim rather than in the badge row: a ✓
      ;; once this player has answered what the phase asks, and 📴 while they
      ;; are away. Words for those made every card a row of tags.
      [:div {:class "relative shrink-0"}
       [:div {:class (str "w-8 h-8 rounded-full grid place-items-center font-bold "
                          (if alive? "bg-slate-700 text-slate-100" "bg-slate-800 text-slate-500")
                          (when talking? " ring-2 ring-emerald-400 "))
              :aria-hidden true}
        (initial pname)]
       (when done?
         [:span {:class "absolute -bottom-1 -right-1 w-4 h-4 rounded-full bg-emerald-600
                         text-white text-[10px] leading-none grid place-items-center"
                 :title done? :data-testid (str "done-" id)}
          "✓" [:span {:class "sr-only"} done?]])
       ;; 📴 rather than a plain dot, which nobody could read without asking.
       (when (false? connected?)
         [:span {:class "absolute -top-1.5 -right-1.5 text-xs leading-none"
                 :title (t :game/absent) :data-testid (str "absent-" id)}
          [:span {:aria-hidden true} "📴"]
          [:span {:class "sr-only"} (t :game/absent)]])]
      [:div {:class "min-w-0 flex-1"}
       [:div {:class (str "font-semibold truncate "
                          (when-not alive? "line-through text-slate-500"))}
        pname]
       ;; Only what a glance at the card cannot already tell. No "du" (the
       ;; border says it) and no "tot" (the grey card and the struck name do).
       [:div {:class "flex flex-wrap gap-1 mt-1 empty:hidden"}
        (cond
          sl?  [pill (t :narrator/title) :host]
          ;; A host who plays has a seat like anyone's and no say in the game,
          ;; only the room's switches — which matter in the lobby alone.
          (and lobby? (= id (:host view))) [pill (t :badge/host) :host])
        ;; The office is public by design — the entire point of it is that the
        ;; table knows whose vote counts twice.
        (when (= id (:mayor view)) [pill (str "👑 " (t :badge/mayor)) :mayor])
        (when (contains? in-voice id)
          [:span {:title (t :voice/in-call)} [pill "🎧" :voice]])
        (when role [pill (str (i18n/icon-of role) " " (role-name role)) :role])
        ;; His own lover, or — for the Spielleiter, whose notes name both — either.
        (when (or (= id (:lover me)) (some #{id} (:lovers narrator-info)))
          [:span {:title (t :badge/lover)} [pill "♥" :lover]])
        ;; Only while the ballot is open. The votes stay in the state until the
        ;; next day begins, so the condemned carried "3 Stimmen" through the
        ;; whole night after.
        (when (and ballot? (pos? n-votes))
          [pill (str n-votes " " (t (if (= 1 n-votes) :badge/vote :badge/votes))) :votes])]
       [card-controls id]]]]))

(defn- player-grid
  "The table, the Spielleiter included. He has a seat but no card, no vote and
   no life to lose, so his card comes first and is marked as his, rather than
   sitting among the others as one more suspect.

   He used to leave the board once the cards were dealt. But he is still in the
   voice call, still talking, and his speaking ring lives on this card: without
   it the table heard a voice with no seat to put it to."
  []
  (let [view @(rf/subscribe [::sub/view])
        narrator (:narrator view)
        asleep? @(rf/subscribe [::sub/asleep?])
        grid [:div {:class "grid sm:grid-cols-2 gap-2"}
              (for [[id p] (sort-by (fn [[id]] [(if (= id narrator) 0 1) id]) (:players view))]
                ^{:key id} [player-card (assoc p :id id)])]]
    ;; Folded while you sleep: a whole board nobody may touch is noise at
    ;; night. One tap opens it, for whoever wants to brood over it anyway.
    (if asleep?
      [:details {:class "group" :data-testid "board-folded"}
       [:summary {:class "cursor-pointer select-none text-sm text-slate-400 hover:text-slate-200 py-1"}
        (t :board/show (count (:players view)))]
       [:div {:class "mt-2"} grid]]
      grid)))

;; ------------------------------------------------------------------ actions ---

(def ^:private declines
  "The 'no' answers. Grouped apart from the rest so saying no is never hidden
   among the things that kill somebody."
  #{:ready :hexe-pass :vote-abstain :jaeger-pass
    :elect-abstain :mayor-decline})

(defn- action-bar []
  (let [actions @(rf/subscribe [::sub/actions])
        targets @(rf/subscribe [::sub/targets])
        armed (:action (pending))
        needs-target? (fn [a] (seq (get targets a)))
        ;; GM powers have their own console below and :claim-host a banner of
        ;; its own; see `sub/player-moves`, which the turn banner reads too.
        plain (sub/player-moves actions)
        narrator? @(rf/subscribe [::sub/narrator?])
        voted?  @(rf/subscribe [::sub/voted?])
        view    @(rf/subscribe [::sub/view])]
    (cond
      ;; The Spielleiter's work is in his console; "nothing to do" under it
      ;; would be false.
      (and (empty? plain) narrator?) nil

      ;; At the end there is nothing left to do for anyone, and the stage says
      ;; so with the winner; repeating it as a grey shrug is noise.
      (and (empty? plain) (= :game-over @(rf/subscribe [::sub/phase]))) nil

      ;; At night the turn banner already says "you are asleep".
      (and (empty? plain) (= :night @(rf/subscribe [::sub/phase]))) nil

      (empty? plain)
      [:div {:class "text-sm text-slate-500"} (t :game/nothing-to-do)]

      ;; Framed and titled, because this is what "Du bist dran" points at: a row
      ;; of ordinary buttons under the role card was easy to read past.
      :else
      [:div {:class (if voted?
                      ;; Cast, and still open to change: the buttons stay, but
                      ;; this is no longer what the table is waiting for.
                      "rounded-xl border-2 border-slate-600 bg-slate-800/40 p-3 space-y-2"
                      "rounded-xl border-2 border-emerald-500 bg-emerald-950/40 p-3 space-y-2
                       ring-4 ring-emerald-500/20")
             :data-testid "my-task"}
       [:div {:class (str "text-xs font-bold uppercase tracking-wide "
                          (if voted? "text-slate-300" "text-emerald-300"))}
        (if voted?
          (let [target (get-in view [:votes (get-in view [:me :id])])]
            (str "🗳 " (if target
                         (t :vote/yours (get-in view [:players target :name]))
                         (t :vote/yours-abstained))))
          (str "👉 " (t :turn/task)))]
       [:div {:class "flex flex-wrap gap-2"}
       (for [a plain]
         ^{:key (str a)}
         ;; The ellipsis marks the buttons that do NOT act yet — they arm a
         ;; choice and wait for a player to be picked. Without it an immediate
         ;; "I'm done" and a two-step "Accuse…" looked identical, so a tap that
         ;; only changed the board's mode read as a tap that did nothing.
         [btn (str (action-name a) (when (needs-target? a) " …"))
          (if (needs-target? a)
            #(if (= armed a) (disarm!) (arm! a))
            #(rf/dispatch [::ev/act a]))
          {:tone (cond (= armed a) :armed
                       (declines a) :quiet
                       :else :primary)}])]])))

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
     [btn (cond
            confirming? (t :gm/confirm)
            ;; A switch says which way it will flip, not merely that it is one.
            (= :gm/toggle-timer action)
            (t (if @(rf/subscribe [::sub/timer?]) :gm/timer-off :gm/timer-on))
            :else (action-name action))
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
        narrator? @(rf/subscribe [::sub/narrator?])
        phase @(rf/subscribe [::sub/phase])
        ;; Open by default for the Spielleiter: for him this console is the
        ;; game, not an escape hatch. Closed for a playing host, as before.
        open? (if (contains? gm :open?) (boolean (:open? gm)) narrator?)
        rows (sort-by str (filter #(and (or (= "gm" (namespace %)) (= :advance-phase %))
                                        ;; During setup the cards carry a select
                                        ;; each; a second, two-click way to do
                                        ;; the same thing is clutter.
                                        (not (and (= :setup phase)
                                                  (= :gm/reassign-role %))))
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
        phase @(rf/subscribe [::sub/phase])
        role (:role me)]
    (if (:narrator? me)
      ;; No card to show, so say what the seat IS. Same place and weight as a
      ;; role card: "who am I in this game" is still the first question.
      [:div {:class "rounded-xl p-4 border-2 border-violet-700 bg-violet-950/40"}
       [:div {:class "flex items-center gap-3"}
        [:div {:class "text-4xl"} "📜"]
        [:div {:class "min-w-0"}
         [:div {:class "text-xs uppercase tracking-wide text-slate-400"} (t :game/your-role)]
         [:div {:data-testid "my-role"
                :class "text-2xl font-bold leading-tight"} (t :narrator/title)]]]
       [:p {:class "text-sm text-slate-300 mt-2"} (t :narrator/desc)]]
    [:div {:class (str "rounded-xl p-4 border-2 "
                       (if (:alive? me)
                         "border-emerald-700 bg-emerald-950/40"
                         "border-rose-800 bg-rose-950/30"))}
     [:div {:class "flex items-center gap-3"}
      [:div {:class "text-4xl"} (i18n/icon-of role)]
      [:div {:class "min-w-0"}
       [:div {:class "text-xs uppercase tracking-wide text-slate-400"} (t :game/your-role)]
       ;; The one test hook in the client. A browser test has to learn which role
       ;; it was dealt in order to drive the game at all, and finding it by DOM
       ;; position would break on the next layout change. Reading it here is not
       ;; a redaction hole: this is the player's OWN role, which is the one thing
       ;; their view is guaranteed to contain.
       [:div {:data-testid "my-role"
              :class "text-2xl font-bold leading-tight"} (role-name role)]]
      (when-not (:alive? me)
        [:div {:class "ml-auto"} [pill (t :game/dead) :dead]])]
     [:p {:class "text-sm text-slate-300 mt-2"} (role-desc role)]
     ;; The Spielleiter may still change it, and a card that silently becomes a
     ;; different card would look like a bug.
     (when (= :setup phase)
       [:p {:class "text-xs text-violet-300/90 mt-1"} (t :setup/your-card)])

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
            :role])]])])))

(defn narrator-panel
  "The Spielleiter's notes on the night: whom the pack is leaning towards, who
   is dying, whom the Beschützer stood in front of — and, all game long, who is
   in love and which two cards lie in the middle for the Dieb.

   Renders only what the server built, and it builds it for the narrator alone
   (see view/night-view), so this needs no check of its own."
  []
  (let [info @(rf/subscribe [::sub/night-info])
        notes @(rf/subscribe [::sub/narrator-info])
        players @(rf/subscribe [::sub/players])
        nm #(or (get-in players [% :name]) (str "#" %))
        rows (cond-> []
               (seq (:werewolf-votes info))
               (conj [(t :narrator/wolf-votes)
                      (str/join ", " (for [[w v] (sort-by key (:werewolf-votes info))]
                                       (str (nm w) " → " (nm v))))])
               (contains? info :pending-deaths)
               (conj [(t :narrator/dying)
                      (if (seq (:pending-deaths info))
                        (str/join ", " (map (comp nm :player) (:pending-deaths info)))
                        (t :narrator/nobody))])
               (contains? info :protected)
               (conj [(t :narrator/protected) (nm (:protected info))])
               (seq (:lovers notes))
               (conj [(t :narrator/lovers) (str/join " ♥ " (map nm (:lovers notes)))])
               (seq (:centre notes))
               (conj [(t :narrator/centre)
                      (str/join ", " (map #(str (i18n/icon-of %) " " (role-name %))
                                          (:centre notes)))]))]
    (when (seq rows)
      [:div {:class "rounded-xl border border-violet-800/60 bg-violet-950/20 px-4 py-3 space-y-1"}
       [:div {:class "text-sm font-bold uppercase tracking-wide text-violet-200"}
        (str "📜 " (t :narrator/night))]
       (for [[label text] rows]
         ^{:key label}
         [:div {:class "text-sm"}
          [:span {:class "text-violet-300/80"} (str label ": ")]
          [:span {:class "text-slate-100"} text]])])))

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
        open? @(rf/subscribe [::sub/chronik-open?])
        rows (i18n/chronicle (lc) players log {:show-all? show-all?})
        hidden (count (remove #(i18n/loud? (i18n/log-tone %)) log))]
    [:div {:class "rounded-xl border border-slate-700 bg-slate-900/80 overflow-hidden"}
     [:div {:class "px-4 py-2 bg-slate-800 flex items-center justify-between gap-2"}
      ;; The fold. Its label always contains the word Chronik, open or shut, so
      ;; nobody has to guess what the bar is.
      [:button {:class "text-sm font-bold uppercase tracking-wide text-left hover:text-white"
                :aria-expanded open?
                :on-click #(rf/dispatch [::ev/toggle-chronik])}
       (str (if open? "▾ " "▸ ")
            (if open? (t :game/log) (t :log/open (count rows))))]
      (when (and open? (or show-all? (pos? hidden)))
        [:button {:class "text-xs text-slate-400 hover:text-slate-100 underline"
                  :on-click #(rf/dispatch [::ev/toggle-log])}
         (if show-all?
           (t :log/show-less)
           (str "+" hidden " " (t :log/hidden)))])]
     (when open?
      [:div {:class "max-h-[28rem] overflow-y-auto divide-y divide-slate-800"}
      (for [[i {:keys [text tone]}] (map-indexed vector rows)]
        ^{:key i}
        [:div {:class (str "px-4 py-2 text-sm flex gap-2 "
                           (when (zero? i) "bg-slate-800/60 ")
                           (log-classes tone))}
         [:span {:class "shrink-0 opacity-60"} (log-icon tone)]
         [:span text]])])]))

(defn- winner-line
  "Who won, in a sentence. Shared by the stage and nothing else on purpose — the
   game-over panel used to carry its own copy, which forgot the Weißer Werwolf
   and congratulated the village on his solo win."
  [winner]
  (t (case winner
       :werewolf        :log/win-werewolf
       :lovers          :log/win-lovers
       :weisser-werwolf :log/win-weisser
       :log/win-village)))

(def ^:private phase-icon
  {:setup "🃏" :night "🌙" :day-reveal "🌅" :election "🎖" :discussion "💬"
   :day-resolve "⚖" :reaction "⚡" :game-over "🏆"})

(defn- stage-classes
  "The stage takes the room's light, a shade stronger. Literal class strings,
   because Tailwind only generates what it can find spelled out in a .cljs file."
  [phase ambience]
  (cond
    (= :reaction phase)   "border-rose-700/70 bg-rose-950/40"
    (= :night ambience)   "border-indigo-700/70 bg-indigo-950/50"
    (= :day ambience)     "border-amber-700/50 bg-amber-950/25"
    (= :end ambience)     "border-amber-500/70 bg-amber-900/30"
    :else                 "border-slate-700 bg-slate-900/70"))

(defn- round-strip
  "Where in the day-night cycle the table is. Icons only on a phone, except the
   current step, which is the one anybody is looking for; every label from `sm`
   up. Screen readers get every name either way."
  []
  (when-let [steps @(rf/subscribe [::sub/round-steps])]
    [:ol {:class "flex items-center gap-1" :aria-label (t :stage/progress)}
     (for [{:keys [step state interrupted?]} steps]
       ^{:key (str step)}
       [:li {:class "flex items-center gap-1 min-w-0"
             :aria-current (when (= :current state) "step")}
        [:span {:class (str "flex items-center gap-1 rounded-full px-2 py-1 text-xs whitespace-nowrap "
                            (case state
                              :current (if interrupted?
                                         "bg-rose-600 text-white font-bold ring-2 ring-rose-300/60 "
                                         "bg-slate-100 text-slate-900 font-bold ")
                              :done    "text-slate-500 "
                              "text-slate-400 bg-slate-800/60 "))}
         [:span {:aria-hidden true} (phase-icon step)]
         [:span {:class (if (= :current state) "" "sr-only sm:not-sr-only")}
          (t (keyword "step" (name step)))
          (when interrupted? (str " · " (t :stage/interrupted)))]]
        ;; The connector, except after the last step.
        (when-not (= step (:step (peek steps)))
          [:span {:aria-hidden true
                  :class (str "h-px w-2 sm:w-4 shrink-0 "
                              (if (= :done state) "bg-slate-500" "bg-slate-700"))}])])]))

(defn- host-absent-banner
  "The host has been gone past his grace period, said where everybody looks.

   Without it the table sees only its consequences — a clock that came on by
   itself, a button offering somebody's seat — and neither explains itself. The
   button is drawn from `:actions` like every other: the server offers the seat
   only where taking it reveals nothing, and only to who is still here."
  []
  (when-let [{:keys [name clock? claim?]} @(rf/subscribe [::sub/host-absence])]
    [:div {:class "rounded-xl border-2 border-amber-600/70 bg-amber-950/40 px-4 py-3 space-y-2"
           :data-testid "host-absent"}
     [:div {:class "font-semibold text-amber-200"} (str "⚠ " (t :absence/title name))]
     [:p {:class "text-sm text-amber-100/80"}
      (t (if clock? :absence/clock-hint :absence/claim-hint))]
     (when claim?
       [btn (action-name :claim-host) #(rf/dispatch [::ev/act :claim-host])
        {:testid "claim-host"}])]))

(defn- latest-news
  "The last thing that mattered, so folding the Chronik hides nothing anyone
   needs. Rendered through the Chronik's own sentences, so it can never say it
   differently."
  []
  (when-let [entries @(rf/subscribe [::sub/latest-news])]
    (let [players @(rf/subscribe [::sub/players])
          rows (i18n/chronicle (lc) players entries {:show-all? false})]
      (when (seq rows)
        [:div {:class "text-sm flex gap-2 items-baseline"}
         [:span {:class "shrink-0 text-xs uppercase tracking-wide text-slate-400"}
          (t :stage/latest)]
         [:span {:class "text-slate-100"}
          (str/join " · " (map :text (reverse rows)))]]))))

(defn- night-strip
  "The night as the narrator would run it: every role it calls tonight, in
   order, the one being called lit up and the finished ones ticked. Only the
   role used to be shown, so nobody could tell how far the night had come or
   that the Hexe was still to wake. Built by the server from public facts
   alone — see view/night-plan."
  [narrator?]
  (let [plan @(rf/subscribe [::sub/night-plan])]
    [:div {:class "space-y-1" :data-testid "night-strip"}
     ;; The Spielleiter reads it as his cue, everybody else as news.
     [:div {:class "text-xs text-slate-400"} (t (if narrator? :stage/call-label :stage/called-label))]
     [:ol {:class "flex flex-wrap items-center gap-1.5"}
      (for [{:keys [role state]} plan]
        ^{:key (str role)}
        [:li {:class (str "inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-sm "
                          (case state
                            :current "bg-indigo-900/80 border border-indigo-500 text-indigo-50 font-semibold"
                            :done    "text-slate-500 line-through decoration-slate-600"
                            "text-slate-300 border border-slate-700"))
              :aria-current (when (= :current state) "step")
              :data-state (name state)}
         (when (= :done state) [:span {:aria-hidden true} "✓"])
         [:span {:aria-hidden true} (i18n/icon-of role)]
         (role-name role)])]]))

(defn phase-stage
  "Where are we, what is happening, and what just happened — one panel, first on
   every screen.

   It replaced a bare phase name in the corner. A first-time player read 'Anklage'
   and learned nothing from it, and nothing on screen said it would be followed by
   a vote; the Spielleiter narrates that out loud, but only to people who were
   listening. So the stage says it in a sentence, shows the whole cycle with this
   step lit, and names the role being called at night — public, since the narrator
   says it aloud anyway, while who holds it is not.

   The turn banner lives at its foot rather than in a box of its own, because
   'what is happening' and 'is it me' are read in the same glance.

   Exactly one heading, holding exactly the phase name: browser tests and screen
   readers both find the phase that way."
  []
  (let [phase @(rf/subscribe [::sub/phase])
        view @(rf/subscribe [::sub/view])
        secs @(rf/subscribe [::sub/seconds-left])
        me @(rf/subscribe [::sub/me])
        cursor @(rf/subscribe [::sub/night-cursor])
        narrator? @(rf/subscribe [::sub/narrator?])
        ambience @(rf/subscribe [::sub/ambience])]
    [:section {:class (str "rounded-2xl border-2 p-4 space-y-3 transition-colors duration-700 "
                           (stage-classes phase ambience))}
     [:div {:class "flex items-start justify-between gap-3"}
      [:div {:class "flex items-start gap-3 min-w-0"}
       [:div {:class "text-4xl leading-none pt-0.5" :aria-hidden true}
        (phase-icon phase "🃏")]
       [:div {:class "min-w-0"}
        [:h2 {:class "text-2xl font-bold leading-tight"} (phase-name phase)]
        [:div {:class "flex flex-wrap items-center gap-x-2 gap-y-1 mt-1 text-xs text-slate-400"}
         (when (pos? (:night view 0))
           [:span (t :stage/round (:night view))])
         [:span {:class "font-mono"} (:room-id me)]
         [language-switch]
         [sound-switch]
         ;; Off the board, so named here: everybody should know who is calling
         ;; the night.
         (when-let [n (:narrator view)]
           [pill (t :lobby/narrator-is (get-in view [:players n :name])) :host])
         ;; Next to his name, so "who is calling the night" and "nobody is"
         ;; are read in one glance.
         (when (:host-absent? view)
           [pill (t :game/absent) :absent])]]]
      [:div {:class "text-right shrink-0"}
       (cond
         ;; A paused clock must SAY it is paused. Simply freezing the digits, or
         ;; hiding them, both read as a broken client.
         (:paused? view)
         [:div {:class "text-lg font-bold text-amber-400"} (str "⏸ " (t :game/paused))]

         secs
         [:div {:class (str "text-3xl font-mono leading-none "
                            (if (< secs 11) "text-rose-400" "text-slate-200"))}
          (str (quot secs 60) ":" (when (< (mod secs 60) 10) "0") (mod secs 60))])]]

     [:p {:class "text-sm sm:text-base text-slate-200"}
      (if (= :game-over phase)
        [:span {:class "text-xl font-bold text-amber-300"} (winner-line (:winner view))]
        ;; The first day has no trial — see phases/trial-day? — and says so,
        ;; or the table waits for an accusation step that never comes.
        (cond
          (and (= :discussion phase) (:trial? view))
          (t :explain/trial (:votes-needed view))
          (= :discussion phase) (t :explain/first-discussion)
          :else (t (keyword "explain" (name (or phase :setup))))))]

     [host-absent-banner]

     (when (and (= :night phase) cursor)
       [night-strip narrator?])

     [:div {:class "overflow-x-auto -mx-1 px-1"} [round-strip]]

     (when-not (= :game-over phase) [latest-news])

     [turn-banner]]))

;; -------------------------------------------------------------------- chat ---

(defn chat-panel []
  (let [msgs @(rf/subscribe [::sub/chat])
        actions @(rf/subscribe [::sub/actions])
        me @(rf/subscribe [::sub/me-view])
        phase @(rf/subscribe [::sub/phase])
        allowed? (contains? actions :chat)
        ;; Mirrors `room/chat-audience`, which is what actually routes — this
        ;; only labels it, and it labels the Spielleiter first because he stands
        ;; outside both of the other channels.
        channel (cond (and (:narrator? me) (not= :lobby phase)) :narrator
                      (false? (:alive? me)) :dead
                      (= :night phase) :wolves)]
    [:div {:class "flex flex-col h-64 rounded-xl border border-slate-700 bg-slate-900/80 overflow-hidden"}
     [:div {:class "px-4 py-2 bg-slate-800 text-sm font-bold uppercase tracking-wide"}
      (t :game/chat)]
     [:div {:class "flex-1 overflow-y-auto px-4 py-2 space-y-1 text-sm"}
      (for [[i m] (map-indexed vector msgs)]
        ^{:key i}
        [:div
         [:span {:class (case (:channel m)
                          :wolves   "text-rose-400 font-semibold"
                          :dead     "text-slate-500 font-semibold"
                          :narrator "text-violet-300 font-semibold"
                          "text-indigo-300 font-semibold")}
          (:name m) ": "]
         [:span {:class (when (= :dead (:channel m)) "text-slate-500")} (:text m)]])]
     [:div {:class "border-t border-slate-700 p-2 space-y-1"}
      (when (and allowed? channel)
        ;; The label is derived from the same state the server routes on, so it
        ;; cannot promise the wrong audience.
        [:div {:class (case channel
                        :wolves   "text-xs text-rose-400"
                        :narrator "text-xs text-violet-300"
                        "text-xs text-slate-500")}
         (t (case channel
              :wolves   :game/wolves-channel
              :narrator :game/narrator-channel
              :game/dead-channel))])
      [:div {:class "flex gap-2"}
       [:div {:class "flex-1"}
        [field :text (t :game/chat-hint) {:on-enter [::ev/send-chat] :maxlength 500}]]
       [btn "→" #(rf/dispatch [::ev/send-chat]) {:disabled? (not allowed?)}]]]]))

;; -------------------------------------------------------------------- lobby ---

(defn- leave-button
  "Two taps, like the GM's public overrides, and for a reason of the same kind:
   the first tap is already final. Leaving gives up the seat and hangs up the
   call, and on a phone this button sits a thumb away from 'start' — a seat
   lost by brushing it means asking the table for the code again.

   Inline rather than `js/confirm`: a browser dialog on a phone is a modal in
   the wrong typeface that looks like the page broke."
  []
  (let [armed? @(rf/subscribe [::sub/leave-armed?])
        phase (:phase @(rf/subscribe [::sub/view]))]
    (if armed?
      [:<>
       [btn (t :lobby/leave-confirm) #(rf/dispatch [::ev/leave]) {:tone :danger}]
       [btn (t :lobby/leave-cancel) #(rf/dispatch [::ev/form-set :leave nil]) {:tone :ghost}]]
      [btn (t :lobby/leave) #(rf/dispatch [::ev/form-set :leave phase]) {:tone :quiet}])))

(defn- table-settings
  "How this table will play: with a Spielleiter or without, with a clock or
   without. Everybody reads it — joining a game whose host will not be playing
   is worth knowing before the cards are dealt — and only the host gets the
   switches, because only the host is offered the actions."
  []
  (let [actions   @(rf/subscribe [::sub/actions])
        narrated? @(rf/subscribe [::sub/narrated?])
        timer?    @(rf/subscribe [::sub/timer?])
        narrator? @(rf/subscribe [::sub/narrator?])
        host?     @(rf/subscribe [::sub/host?])
        locked?   @(rf/subscribe [::sub/locked?])
        row (fn [label text switch]
              [:div {:class "flex flex-wrap items-center justify-between gap-2"}
               [:div {:class "min-w-0"}
                [:span {:class "font-semibold text-violet-200"} (str label ": ")]
                [:span {:class "text-slate-300"} text]]
               switch])]
    [:div {:class "rounded-xl border border-violet-800/60 bg-violet-950/20 p-3 space-y-2 text-sm"}
     ;; Said to the Spielleiter in so many words — including the one who just
     ;; inherited the seat because the previous host walked out.
     (when narrator?
       [:div {:class "font-semibold text-violet-100" :data-testid "you-narrate"}
        (str "📜 " (t :lobby/you-narrate))])
     [row (t :lobby/mode)
      (t (if narrated? :lobby/mode-narrated :lobby/mode-players))
      (when (contains? actions :gm/toggle-narrated)
        [btn (t (if narrated? :lobby/play-along :lobby/narrate))
         #(rf/dispatch [::ev/act :gm/toggle-narrated])
         {:tone :quiet :testid "toggle-narrated"}])]
     [row (t :lobby/timer)
      (t (if timer? :lobby/timer-on :lobby/timer-off))
      (when (contains? actions :gm/toggle-timer)
        [btn (t (if timer? :lobby/timer-switch-off :lobby/timer-switch-on))
         #(rf/dispatch [::ev/act :gm/toggle-timer])
         {:tone :quiet :testid "toggle-timer"}])]
     ;; The lock. Everybody reads whether there is one; only the host gets the
     ;; box, and the server checks that again, since the host is the one thing
     ;; here that is not an engine action the view could offer.
     [row (t :lobby/password)
      (str (when locked? "🔒 ") (t (if locked? :lobby/password-on :lobby/password-off)))
      (when (and host? locked?)
        [btn (t :lobby/password-clear) #(rf/dispatch [::ev/clear-password])
         {:tone :quiet :testid "clear-password"}])]
     (when host?
       [:div {:class "flex gap-2"}
        [:div {:class "flex-1 min-w-0"}
         [field :lobby-password (t :lobby/password-new)
          {:maxlength 64 :testid "lobby-password" :on-enter [::ev/set-password]}]]
        [btn (t (if locked? :lobby/password-change :lobby/password-set))
         #(rf/dispatch [::ev/set-password])
         {:tone :quiet :testid "set-password"}]])]))

(defn- deck-chips
  "A composition as a row of cards: icon, name, and a count when there is more
   than one. Wraps rather than scrolls — a phone at 360px fits three or four to
   a line, and a deck that runs off the side is a deck somebody misreads."
  [entries testid]
  [:div {:class "flex flex-wrap gap-1.5" :data-testid testid}
   (for [[role n] entries]
     ^{:key (str role)}
     [:span {:class "inline-flex items-center gap-1 rounded-full bg-slate-800 border border-slate-700
                     px-2 py-0.5 text-xs text-slate-200"
             :data-role (name role) :data-count n}
      [:span {:aria-hidden true} (i18n/icon-of role)]
      [:span (role-name role)]
      (when (> n 1) [:span {:class "font-bold text-slate-100"} (str "×" n)])])])

(defn- deck-stepper
  "One special in the host's deck: what it is, what it does, and how many.

   The description is small text under the name rather than behind a tap. The
   host choosing between the Beschützer and the Hexe is exactly the person who
   needs to read both, and a tooltip does not exist on a phone."
  [{:keys [role n can-add? can-remove?]} limit]
  [:div {:class "flex items-center gap-2 py-1.5" :data-testid (str "deck-row-" (name role))}
   [:span {:class "text-xl w-7 shrink-0 text-center" :aria-hidden true} (i18n/icon-of role)]
   [:div {:class "min-w-0 flex-1"}
    [:div {:class (str "font-semibold " (if (pos? n) "text-slate-100" "text-slate-400"))}
     (role-name role)]
    [:div {:class "text-xs text-slate-400 leading-snug"} (role-desc role)]]
   [:div {:class "flex items-center shrink-0"}
    [:button {:class "h-9 w-9 rounded-l-lg bg-slate-700 hover:bg-slate-600 text-lg font-bold
                      disabled:opacity-30 disabled:cursor-not-allowed"
              :disabled (not can-remove?)
              :aria-label (t :deck/less (role-name role))
              :data-testid (str "deck-minus-" (name role))
              :on-click #(rf/dispatch [::ev/deck-step role -1 limit])}
     "−"]
    [:span {:class "h-9 w-8 flex items-center justify-center bg-slate-800 tabular-nums font-bold"
            :data-testid (str "deck-count-" (name role))}
     n]
    [:button {:class "h-9 w-9 rounded-r-lg bg-slate-700 hover:bg-slate-600 text-lg font-bold
                      disabled:opacity-30 disabled:cursor-not-allowed"
              :disabled (not can-add?)
              :aria-label (t :deck/more (role-name role))
              :data-testid (str "deck-plus-" (name role))
              :on-click #(rf/dispatch [::ev/deck-step role 1 limit])}
     "+"]]])

(defn- deck-problem-line [{:keys [problem specials table]}]
  (when problem
    [:p {:class "text-sm font-semibold text-amber-300" :data-testid "deck-problem"}
     (str "⚠ " (i18n/deck-problem-text (lc) problem specials table))]))

(defn- roles-panel
  "Which roles are in the game. Everyone reads it — at a real table the deck is
   said out loud before anybody looks at a card — and the host gets the switch
   and the steppers, because only the host is offered `:gm/set-deck`.

   Every tap sends the whole deck and the server answers with the next view.
   The one thing shown ahead of that answer is the count under the host's
   thumb — see `events/deck-step-fx` for why. A deck the table cannot play is still a deck the
   host may hold on the way to one it can, so the steppers never refuse for
   THAT reason — the server's `:deck-problem` says what is missing instead."
  []
  (let [actions @(rf/subscribe [::sub/actions])
        view    @(rf/subscribe [::sub/view])
        {:keys [custom? rows dealt fill centre villager] :as panel}
        @(rf/subscribe [::sub/deck-panel])
        edit?   (contains? actions :gm/set-deck)
        seg     (fn [label on? testid on-click]
                  [:button {:class (str "px-3 py-1.5 text-xs font-semibold transition "
                                        (if on?
                                          "bg-violet-600 text-white "
                                          "bg-slate-800 text-slate-300 hover:bg-slate-700 "))
                            :aria-pressed on?
                            :data-testid testid
                            :on-click (when-not on? on-click)}
                   label])]
    [:div {:class "rounded-xl border border-violet-800/60 bg-violet-950/20 p-3 space-y-3 text-sm"
           :data-testid "deck-panel"}
     [:div {:class "flex flex-wrap items-center justify-between gap-2"}
      [:span {:class "font-semibold text-violet-200"} (str "🃏 " (t :deck/title))]
      (if edit?
        [:div {:class "flex rounded-lg overflow-hidden border border-slate-600"}
         [seg (t :deck/auto) (not custom?) "deck-auto"
          #(rf/dispatch [::ev/deck-auto])]
         [seg (t :deck/custom) custom? "deck-custom"
          #(rf/dispatch [::ev/act-with :gm/set-deck
                         {:deck (sub/deck-from-preview (:deck-preview view))}])]]
        [:span {:class "text-xs text-slate-400"} (t (if custom? :deck/custom :deck/auto))])]
     [:p {:class "text-xs text-slate-400"} (t (if custom? :deck/custom-hint :deck/auto-hint))]
     (if (and edit? custom?)
       [:div
        [:div {:class "divide-y divide-slate-800"}
         (for [row rows]
           ^{:key (str (:role row))}
           [deck-stepper row (:max row)])]
        ;; The filler is a consequence, not a choice — so it has no steppers,
        ;; and says what it does instead. Not when the specials take every
        ;; seat: 'Dorfbewohner ×0 fill the remaining seats' names a card nobody
        ;; gets, for seats that do not exist.
        (when (pos? fill)
          [:div {:class "flex items-center gap-2 pt-2 border-t border-slate-800"
                 :data-testid "deck-fill"}
           [:span {:class "text-xl w-7 shrink-0 text-center" :aria-hidden true}
            (i18n/icon-of villager)]
           [:span {:class "flex-1 min-w-0 text-slate-300"}
            (str (role-name villager) " ×" fill " · " (t :deck/fill))]])]
       [deck-chips dealt "deck-dealt"])
     (when (seq centre)
       [:div {:class "space-y-1"}
        [:div {:class "text-xs uppercase tracking-wide text-slate-400"} (t :deck/centre)]
        [deck-chips centre "deck-centre"]])
     [deck-problem-line panel]]))

(defn- deck-strip
  "The composition during the game, in one line. What was SHUFFLED, not what
   anybody holds now — the server builds it from the deck for exactly that
   reason, so neither the Dieb's swap nor the Spielleiter's setup changes show."
  []
  (let [{:keys [dealt centre]} @(rf/subscribe [::sub/deck-panel])]
    (when (seq dealt)
      [:div {:class "text-xs text-slate-400 leading-relaxed" :data-testid "deck-strip"}
       [:span {:class "uppercase tracking-wide"} (str "🃏 " (t :deck/in-game) ": ")]
       [:span {:class "text-slate-300"} (i18n/deck-summary (lc) (into {} dealt))]
       (when (seq centre)
         [:span (str " · " (t :deck/centre) ": "
                     (i18n/deck-summary (lc) (into {} centre)))])])))

(defn lobby []
  (let [view @(rf/subscribe [::sub/view])
        me @(rf/subscribe [::sub/me])
        actions @(rf/subscribe [::sub/actions])
        host? @(rf/subscribe [::sub/host?])
        ;; The people who will be dealt a card. The Spielleiter is in the room
        ;; but not at the table, and the server counts the same way when it
        ;; decides whether the game may start.
        table (count (remove #(= % (:narrator view)) (keys (:players view))))]
    [:div {:class "space-y-4"}
     [:div {:class "flex flex-wrap items-baseline justify-between gap-2"}
      [:h2 {:class "text-2xl font-bold"} (t :lobby/title)]
      [:div {:class "text-right"}
       [:div {:class "text-xs uppercase tracking-wide text-slate-400"} (t :lobby/share)]
       [:div {:class "font-mono text-3xl tracking-[0.3em] text-indigo-300"} (:room-id me)]
       ;; Next to the code, because the two are handed out together.
       (when (:locked? view)
         [:div {:class "text-xs text-slate-400" :data-testid "lobby-locked"}
          (str "🔒 " (t :landing/locked))])
       [:div {:class "flex justify-end mt-1"} [language-switch]]]]
     ;; The count against the CEILING, with the recommendation beside it. It was
     ;; "2 / 5", the minimum in the place where a maximum goes, and was read as
     ;; a five-player cap on an eighteen-seat table.
     [:div {:class "text-sm text-slate-400"}
      (t :lobby/count table (:max-players view 18) (:min-players view 5))]
     [table-settings]
     [host-absent-banner]
     [roles-panel]
     [player-grid]
     [:div {:class "space-y-2"}
      [:div {:class "flex flex-wrap items-center gap-3"}
       (cond
         (contains? actions :start-game)
         [btn (t :lobby/start) #(rf/dispatch [::ev/act :start-game])]

         ;; The deck, not the head count, is what stops this table. Saying
         ;; "waiting for more players" here would send the host to wait for
         ;; people when what he has to do is take a role out. The reason itself
         ;; is said once, at the foot of the Rollen panel: right under the
         ;; steppers that caused it and fix it, where every player reads it too.
         ;; Here it was a second copy, a whole player grid further down.
         (and host? (:deck-problem view))
         nil

         :else
         [:span {:class "text-sm text-slate-500"}
          (if host? (t :lobby/need-more) (t :game/waiting))])
       [leave-button]]
      ;; The host may start under the recommended size, so say so rather than
      ;; letting a start button next to "3 / 5" look like a bug. Derived from
      ;; the offer, not from a rule of its own: if the server did not offer
      ;; :start-game, the table is below the deck's floor and there is nothing
      ;; to recommend.
      (when (and (contains? actions :start-game)
                 (< table (:min-players view 5)))
        [:p {:class "text-xs text-amber-400/90"}
         (t :lobby/below-recommended (:min-players view))])]
     ;; The lobby is where a call gets joined in practice — everyone is arriving,
     ;; nobody is mid-decision, and sorting out microphones before the deal beats
     ;; doing it during the first night.
     [voice-panel]
     [chat-panel]]))

;; --------------------------------------------------------------------- game ---

(defn- recap-cast
  "Who was who: one row per player, icon and card first, fate underneath. One
   column on purpose — at 390px a table of name, role and fate side by side
   wraps every cell, and a list reads top to bottom like the cards being turned."
  [players roster]
  (let [loc (lc)]
    [:ul {:class "divide-y divide-slate-800"}
     (for [{:keys [id role alive?] :as p} roster]
       ^{:key id}
       [:li {:class "py-1.5 flex items-start gap-2 text-sm" :data-testid "recap-player"}
        [:span {:class "text-xl leading-none shrink-0 pt-0.5" :aria-hidden true} (i18n/icon-of role)]
        [:div {:class "min-w-0 flex-1"}
         [:div {:class "flex flex-wrap items-baseline gap-x-2"}
          [:span {:class "font-semibold text-slate-100 break-words"}
           (or (get-in players [id :name]) (str "#" id))]
          [:span {:class "text-slate-300"} (role-name role)]
          (for [tag (recap/cast-tags loc p)]
            ^{:key tag} [:span {:class "text-xs text-slate-400"} tag])]
         [:div {:class (if alive? "text-xs text-emerald-300/90" "text-xs text-rose-300/90")}
          (str (if alive? "✓ " "† ") (recap/fate-text loc p))]]])]))

(defn- recap-round
  "One night or day, folded. Native <details> rather than app state: it needs no
   event, survives every re-render, and a screen reader already knows what it is."
  [players round]
  (let [loc (lc)
        rows (recap/round-lines loc players round)]
    [:details {:class "rounded-lg border border-slate-800 bg-slate-950/40"
               :data-testid "recap-round"}
     [:summary {:class "cursor-pointer select-none px-3 py-2 text-sm font-semibold text-slate-200"}
      (str (recap/round-icon round) " " (recap/round-title loc round))]
     [:ul {:class "px-3 pb-2 space-y-1.5"}
      (for [[i {:keys [icon tone text votes]}] (map-indexed vector rows)]
        ^{:key i}
        [:li {:class (str "text-sm flex gap-2 " (log-classes tone))}
         [:span {:class "shrink-0" :aria-hidden true} icon]
         [:div {:class "min-w-0 break-words"}
          [:div text]
          (when votes [:div {:class "text-xs text-slate-400 font-normal"} votes])]])]]))

(defn- recap-panel
  "The Rückblick: every card turned over and every night told, once the game is
   decided. Rendered only from what the server sent as facts — `:recap` arrives
   with the view at :game-over and never before (see room/with-recap) — through
   the reader's own dictionary. Nothing here knows a rule of the game."
  []
  (let [view @(rf/subscribe [::sub/view])
        {:keys [rounds] roster :players} (:recap view)]
    (when (and (= :game-over (:phase view)) (seq roster))
      [:section {:class "rounded-xl border border-slate-700 bg-slate-900/80 p-3 sm:p-4 space-y-3"
                 :data-testid "recap"}
       [:div
        [:h3 {:class "text-sm font-bold uppercase tracking-wide text-amber-200"}
         (str "📜 " (t :recap/title))]
        [:p {:class "text-xs text-slate-400 mt-0.5"} (t :recap/hint)]]
       [:div
        [:div {:class "text-xs uppercase tracking-wide text-slate-400"} (t :recap/cast)]
        [recap-cast (:players view) roster]]
       (when (seq rounds)
         [:div {:class "space-y-2"}
          (for [[i round] (map-indexed vector rounds)]
            ^{:key i} [recap-round (:players view) round])])])))

(defn- table-record
  "How the table has fared since it sat down: from the second game of a room
   on, who won how often and which cards they held."
  []
  (when-let [{:keys [games rows]} @(rf/subscribe [::sub/table-record])]
    [:div {:class "rounded-lg border border-slate-700 bg-slate-900/60 p-3 space-y-2"
           :data-testid "table-record"}
     [:div {:class "text-sm font-bold text-slate-200"} (str "🏆 " (t :record/title games))]
     [:ul {:class "divide-y divide-slate-800"}
      (for [{:keys [id name played won roles]} rows]
        ^{:key id}
        [:li {:class "py-1.5 flex items-center justify-between gap-3 text-sm"}
         [:div {:class "min-w-0"}
          [:div {:class "font-semibold truncate"} name]
          [:div {:class "text-xs text-slate-400"}
           (str/join " · " (for [[r n] roles]
                             (str (i18n/icon-of r) " " (role-name r) (when (> n 1) (str " ×" n)))))]]
         [:div {:class "shrink-0 text-right"}
          [:div {:class "font-mono"} (str won " / " played)]
          [:div {:class "text-xs text-slate-500"} (t :record/won)]]])]]))

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
       ;; Who won is the stage's headline now, one screen-height up; saying it
       ;; twice made the end of the game read like a stutter.
       ;; Every role is public now — view-for reveals them all once the game is
       ;; decided — so the table can finally read who everyone was.
       [:p {:class "text-sm text-amber-100/80"} (t :game/over-hint)]
       ;; The host's rematch: everyone stays seated and the cards are dealt
       ;; again. The others are told it is coming, so leaving is not the only
       ;; thing the screen seems to offer them.
       (if (contains? actions :play-again)
         [btn (t :action/play-again) #(rf/dispatch [::ev/act :play-again])
          {:testid "play-again"}]
         [:p {:class "text-sm text-amber-100/80"} (t :game/play-again-wait)])
       ;; One tap, unlike the lobby's `leave-button`. Nothing is in progress
       ;; to lose any more, and this is the move the screen exists to offer —
       ;; asking 'really?' of the only way forward is friction, not safety.
       (when (contains? actions :leave)
         [btn (t :game/new-round) #(rf/dispatch [::ev/leave])
          {:tone (if (contains? actions :play-again) :quiet :primary)}])
       [table-record]
       ;; Below the way out rather than above it: the story is for reading at
       ;; leisure, the button for the people who already want the next round.
       [recap-panel]])))

(defn- game-leave
  "Walking out of a game under way. The engine offers `:leave` in every phase —
   a seat nobody could leave once the cards were dealt kept people in games
   they had already abandoned, and reloading only resumed it. Not at the end,
   where the game-over panel has its own one-tap way out."
  []
  (let [actions @(rf/subscribe [::sub/actions])
        phase   @(rf/subscribe [::sub/phase])]
    (when (and (contains? actions :leave) (not= :game-over phase))
      [:div {:class "flex flex-wrap gap-2" :data-testid "game-leave"}
       [leave-button]])))

(defn- dawn-card
  "The morning, told once and large: who did not survive the night and what
   they were, or that the night was quiet. A line under the stage was easy to
   read past while everybody started talking. Gone with \"Weiter\", and for
   good that day; the stage's \"Zuletzt\" line keeps saying it."
  []
  (when-let [entries @(rf/subscribe [::sub/dawn-news])]
    (let [players @(rf/subscribe [::sub/players])
          id      (sub/dawn-id @(rf/subscribe [::sub/view]))
          rows    (i18n/chronicle (lc) players entries {:show-all? false})
          death?  (some #(= :death (:tone %)) rows)]
      (when (seq rows)
        [:section {:class (str "rounded-xl border-2 p-4 space-y-3 text-center "
                               (if death? "border-rose-700 bg-rose-950/40" "border-sky-800 bg-sky-950/30"))
                   :data-testid "dawn"}
         [:div {:class "text-3xl" :aria-hidden true} "🌅"]
         [:h3 {:class "text-xs font-bold uppercase tracking-wide text-slate-300"} (t :dawn/title)]
         (for [[i {:keys [text]}] (map-indexed vector (reverse rows))]
           ^{:key i}
           [:p {:class (str "text-lg font-semibold " (if death? "text-rose-100" "text-sky-100"))} text])
         ;; The card each of them held, which a death turns over for everybody.
         (when-let [dead (seq (filter #(and (= :player/died (:type %)) (:role %)) entries))]
           [:div {:class "flex flex-wrap justify-center gap-2"}
            (for [{:keys [player role]} dead]
              ^{:key player}
              [:span {:class "text-sm text-rose-200"}
               (str (get-in players [player :name]) ": " (i18n/icon-of role) " " (role-name role))])])
         [btn (t :dawn/ok) #(rf/dispatch [::ev/dawn-seen id]) {:tone :ghost :testid "dawn-ok"}]]))))

(defn- turn-dock
  "\"Du bist dran\", stuck to the bottom of a phone screen while it is true.
   Whoever has scrolled down to the board or the chat still sees it, under the
   thumb, and one tap brings the task back. Not on a wide screen, where the
   task is never out of sight."
  []
  (let [my-turn? @(rf/subscribe [::sub/can-act?])
        phase    @(rf/subscribe [::sub/phase])
        secs     @(rf/subscribe [::sub/seconds-left])]
    (when (and my-turn? (not (#{:lobby :game-over} phase)))
      [:button {:class "fixed inset-x-0 bottom-0 z-40 lg:hidden flex items-center justify-between gap-3
                        px-4 py-3 bg-emerald-600 text-white font-bold text-left
                        border-t-2 border-emerald-300"
                :style {:padding-bottom "max(0.75rem, env(safe-area-inset-bottom))"}
                :data-testid "turn-dock"
                :on-click #(some-> (.querySelector js/document "[data-testid=my-task]")
                                   (.scrollIntoView #js {:behavior "smooth" :block "center"}))}
       [:span (str "👉 " (t :turn/yours))]
       (when secs
         [:span {:class "font-mono"} (str (quot secs 60) ":" (when (< (mod secs 60) 10) "0") (mod secs 60))])])))

(defn- ballot-panel
  "The day's vote at a glance: a bar for everybody with votes, filling towards
   the majority it takes, so the table sees who is close and how close. The
   card badges said \"3 Stimmen\" and left the reader to know the threshold."
  []
  (when-let [{:keys [needed rows abstained]} @(rf/subscribe [::sub/ballot])]
    [:section {:class "rounded-xl border border-amber-800/60 bg-amber-950/20 p-3 space-y-2"
               :data-testid "ballot"}
     [:div {:class "flex items-baseline justify-between gap-2"}
      [:h3 {:class "text-sm font-bold text-amber-200"} (str "⚖️ " (t :ballot/title))]
      [:span {:class "text-xs text-amber-200/80"} (t :ballot/needed needed)]]
     (if (empty? rows)
       [:p {:class "text-sm text-slate-400"} (t :ballot/none)]
       [:ul {:class "space-y-1.5"}
        (for [{:keys [id name count mayor?]} rows
              :let [enough? (>= count needed)]]
          ^{:key id}
          [:li {:data-testid (str "ballot-" id)}
           [:div {:class "flex justify-between text-sm"}
            [:span {:class "truncate"} name (when mayor? [:span {:title (t :ballot/mayor)} " 👑"])]
            [:span {:class (str "font-mono " (if enough? "text-rose-300" "text-slate-300"))}
             (str count " / " needed)]]
           [:div {:class "h-2 rounded-full bg-slate-800 overflow-hidden"}
            [:div {:class (str "h-full rounded-full " (if enough? "bg-rose-500" "bg-amber-500"))
                   :style {:width (str (min 100 (* 100 (/ count needed))) "%")}}]]])])
     (when (pos? abstained)
       [:p {:class "text-xs text-slate-400"} (t :ballot/abstained abstained)])]))

(defn game []
  ;; On a phone this is one column, and its ORDER is a design decision rather
  ;; than a side effect of the desktop grid. It used to be: role, actions, GM
  ;; console, then the whole player grid — which on a phone is one full-width
  ;; card per seat, so at nine players the Chronik sat two or three screens down,
  ;; below the board. The only record of what the night did was the last thing
  ;; you could reach, and the chat was easier to find than the story.
  ;;
  ;; Now it reads: where are we and is it my turn (the stage), who am I, what
  ;; can I do, the call, the board to pick from, the Spielleiter's tools, the
  ;; folded Chronik, then talk. The stage's 'Zuletzt' line is what keeps the
  ;; story near the top now that the Chronik starts folded.
  ;;
  ;; `contents` is what makes that possible without touching the desktop layout.
  ;; Below `lg` the two column wrappers are display:contents, so their children
  ;; become direct flex items of this container and can be ordered individually;
  ;; at `lg` they turn back into real columns, each with its own independent
  ;; height, which is the two-column layout that was already there.
  ;;
  ;; If `display: contents` were ever not honoured, the wrappers stay ordinary
  ;; boxes and the phone falls back to plain DOM order — which is the layout that
  ;; shipped before this. Worth knowing: the failure mode is the old arrangement,
  ;; not a broken one.
  ;;
  ;; `empty:hidden` is load-bearing, not decoration. Two of these panels render
  ;; nothing most of the time — game-over except at the end, the GM console for
  ;; everyone who is not host — and an empty flex item still consumes a `gap-4`.
  ;; Without it every phone screen carried two mystery gaps.
  (let [my-turn? @(rf/subscribe [::sub/can-act?])]
  ;; Room at the foot for the dock, so it never covers the last thing on the page.
  [:div {:class (str "space-y-4 " (when my-turn? "pb-20 lg:pb-0"))}
   [phase-stage]
   [turn-dock]
   [target-prompt]
   [:div {:class "flex flex-col gap-4 lg:grid lg:grid-cols-5 lg:gap-4"}
    [:div {:class "contents lg:block lg:col-span-3 lg:space-y-4"}
     [:div {:class "order-1 empty:hidden"} [game-over-panel]]
     [:div {:class "order-1 empty:hidden"} [dawn-card]]
     ;; First on a phone during the vote: it is what everybody is watching.
     [:div {:class "order-1 empty:hidden"} [ballot-panel]]
     ;; On your turn the task jumps above your role card, straight under the
     ;; stage that says it is your turn: the answer to "what do I do" should not
     ;; be a scroll away from the question. `order` does it on a phone; on a
     ;; wide screen the column is a plain block, so the DOM order has to say it.
     (if my-turn?
       [:<>
        [:div {:class "order-2 empty:hidden"} [action-bar]]
        [:div {:class "order-3 empty:hidden"} [me-panel]]]
       [:<>
        [:div {:class "order-3 empty:hidden"} [me-panel]]
        [:div {:class "order-4 empty:hidden"} [action-bar]]])
     ;; Above the board on a phone: joining the call is something you do once, at
     ;; the start, and hunting for it under nine player cards is how people end up
     ;; playing in silence.
     [:div {:class "order-5 empty:hidden"} [voice-panel]]
     ;; His notes before his switches: the Spielleiter reads the night far more
     ;; often than he overrides it.
     ;; The board straight after the call: picking a player is what an armed
     ;; action asks for next, and it used to wait below the Spielleiter's
     ;; panels and the Chronik, a long scroll away from the button that asked.
     [:div {:class "order-6 empty:hidden"} [player-grid]]
     ;; Under the board: the table reads it when it is reasoning about who
     ;; could be what, which is when it is looking at the board anyway.
     [:div {:class "order-6 empty:hidden"} [deck-strip]]
     [:div {:class "order-7 empty:hidden"} [narrator-panel]]
     [:div {:class "order-7 empty:hidden"} [gm-panel]]]
    [:div {:class "contents lg:block lg:col-span-2 lg:space-y-4"}
     [:div {:class "order-8 empty:hidden"} [log-panel]]
     [:div {:class "order-9 empty:hidden"} [chat-panel]]
     ;; Last, and on purpose: the way out of a game under way, as far from the
     ;; moves as the screen allows. Two taps, like the lobby's.
     [:div {:class "order-10 empty:hidden flex justify-end"} [game-leave]]]]]))

;; ---------------------------------------------------------------------- app ---

(def ^:private room-light
  "The room's light per ambience: a base colour and a glow from above. Night is a
   deep blue, the day a warmer dusk — still dark, because the text is light and
   the game is played in dim rooms — and the end is gold. Inline styles rather
   than classes so the colours can fade into each other."
  {:neutral {:base "#0f172a" :glow "rgba(100,116,139,0.10)"}
   :night   {:base "#070b1f" :glow "rgba(99,102,241,0.22)"}
   :day     {:base "#1b1a22" :glow "rgba(251,191,36,0.14)"}
   :end     {:base "#17130b" :glow "rgba(245,158,11,0.24)"}})

(defn- room-glow
  "One glow layer per light, cross-faded on opacity: a background-image cannot be
   transitioned, an opacity can."
  [current]
  [:div {:class "pointer-events-none fixed inset-0" :aria-hidden true}
   (for [[k {:keys [glow]}] room-light]
     ^{:key (str k)}
     [:div {:class "absolute inset-0"
            :style {:background-image (str "radial-gradient(ellipse 120% 60% at 50% -10%, "
                                           glow ", transparent 70%)")
                    :opacity (if (= k current) 1 0)
                    :transition "opacity 1.2s ease"}}])])

(defn app []
  (let [screen @(rf/subscribe [::sub/screen])
        phase @(rf/subscribe [::sub/phase])
        light (if (= :room screen) @(rf/subscribe [::sub/ambience]) :neutral)]
    [:div {:class "relative min-h-screen text-slate-100 font-sans"
           :style {:background-color (get-in room-light [light :base])
                   :transition "background-color 1.2s ease"}}
     [room-glow light]
     [connection-banner]
     [error-banner]
     [:div {:class "relative max-w-6xl mx-auto p-4 sm:p-6"}
      (case screen
        :landing [landing]
        :room (if (or (nil? phase) (= :lobby phase)) [lobby] [game])
        [landing])]]))
