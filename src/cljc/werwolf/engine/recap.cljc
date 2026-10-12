(ns werwolf.engine.recap
  "The Rückblick: what really happened, told once the game is over.

   Built from the FULL event log, not from `:log`. The public log is exactly
   what this is not: it says that Amor chose two lovers and not which two, that
   somebody died and not who chose them. Everything that was private during the
   game — the Seherin's looks, the pack's votes, the Hexe's potions, the
   Beschützer's guard, the Dieb's swap, the Spielleiter's rewritten cards — is in
   the event stream and nowhere else, so the event stream is what this folds.

   Three rules, each load-bearing:

   * **Nothing before the end.** `recap` returns nil unless the log folds into a
     finished game. The room only asks at :game-over as well, but a builder that
     will happily narrate a game in progress is one careless call away from
     handing the table every card mid-night. Two fences, either of which holds.

   * **Facts, never prose, and no strings at all.** Every entry is ids, roles
     and keywords; the client renders them through i18n in the reader's own
     language, like the Chronik. Names are not copied in either — the view
     already carries them — so the recap contains no string whatsoever, and
     there is a test that says so. That is what makes the next rule checkable.

   * **The door stays shut.** The log also holds `:room/password-set`, whose
     payload is a password hash. Those events are dropped before the fold even
     sees them, and the builder is a whitelist besides: each entry is assembled
     from named fields, so an event that grows a new field cannot carry it in.

   Pure and .cljc, so a recap is a function of the log: the same game recaps the
   same way on the JVM and in the browser, after any number of restarts."
  (:require [werwolf.engine.events :as e]))

;; ---------------------------------------------------------------- inputs ---

(def ^:private never-read
  "Events the recap must not even fold. The password events are server-authored
   and carry a hash; the recap has no use for the door, and a builder that never
   holds the hash cannot leak it through some later field."
  #{:room/password-set :room/password-cleared})

;; -------------------------------------------------------------- segments ---
;;
;; A recap is a list of rounds — night 1, day 1, night 2, … — each a list of
;; entries in the order they happened. A night opens with `:night/began` and
;; keeps everything up to the village gathering, because the deaths found at dawn
;; and a Jäger's shot in the morning are that night's consequences, not the day's.
;; The day opens with the election on day 1 and with `:day/began` otherwise.

(defn- current [acc] (peek (:rounds acc)))

(defn- open-round [acc kind n]
  (let [c (current acc)]
    (if (and (= kind (:kind c)) (= n (:n c)))
      acc
      (update acc :rounds conj {:kind kind :n n :entries []}))))

(defn- add-entries
  "Append to the round in progress. Before the first night there is none, and
   nothing then belongs in a recap: the lobby and the Spielleiter arranging the
   deal are not part of the story, and the cast list says how the cards fell."
  [acc entries]
  (if (and (seq entries) (current acc))
    (update-in acc [:rounds (dec (count (:rounds acc))) :entries] into entries)
    acc))

(defn- day-starts? [ev]
  (or (= :day/began (:type ev))
      (and (= :phase/changed (:type ev)) (= :election (:phase ev)))))

;; ----------------------------------------------------------------- facts ---

(defn- role-of [state pid] (get-in state [:players pid :role]))

(defn- gm-entry
  "The overrides worth a line of their own. Mirrors the Chronik's choice: a
   forced kill and a revive already have their death and revival entries, a
   reassignment is told by `:player/role-changed` with both cards, and the clock
   (pause, resume, more time) changed nothing about who did what."
  [before {:keys [action detail]}]
  (case action
    :skip-role     [{:type :gm/forced :action :skip-role
                     :skipped (or (:role detail) (:night-cursor before))}]
    :set-phase     [{:type :gm/forced :action :set-phase :phase (:phase detail)}]
    :advance-phase [{:type :gm/forced :action :advance-phase :phase (:phase detail)}]
    nil))

(defn- facts
  "The recap entries one event contributes, read against the state just BEFORE
   it — which is where the things the event does not carry are found: the role
   the Seherin saw, the card the Dieb gave up, the votes a resolution counted.

   Historical roles are keyed `:saw`, `:took`, `:from`, `:to`, never `:role`.
   The only `:role` in a recap is each player's final card in the cast list,
   which is exactly what `view/revealed-roles` grants everybody at :game-over —
   so the leak detector, which reads `:role` as 'this player holds this card',
   keeps meaning precisely that."
  [before ev]
  (let [{:keys [player target]} ev]
    (case (:type ev)
      :dieb/took   [{:type :dieb/took :player player :took (:role ev)
                     :from (role-of before player)}]
      :dieb/kept   [{:type :dieb/kept :player player}]
      ;; The event names the pair and not Amor; the cast list says who Amor was.
      :amor/linked [{:type :amor/linked :lovers (vec (sort [(:a ev) (:b ev)]))}]
      :seherin/inspected [{:type :seherin/inspected :player player :target target
                           :saw (role-of before target)}]
      :beschuetzer/protected [{:type :beschuetzer/protected :player player :target target}]

      ;; The pack's turn ends before its choice is recorded — both arrive in one
      ;; batch, completion first — so the entry is opened here with the ballots
      ;; and given its victim by `:werewolf/chose` just after. A turn the
      ;; Spielleiter cut short, or a split pack, keeps a nil victim, which is
      ;; what happened. A pack that never voted (the quiet first night) says
      ;; nothing at all.
      :night/role-completed
      (let [votes (get-in before [:night-actions :werewolf-votes])]
        (when (and (= :werwolf (:role ev)) (seq votes))
          [{:type :wolves/chose :votes votes :target nil}]))

      :weisser/killed [{:type :weisser/killed :player player :target target}]
      :weisser/passed [{:type :weisser/passed :player player}]
      :hexe/healed    [{:type :hexe/healed :player player :target target}]
      :hexe/poisoned  [{:type :hexe/poisoned :player player :target target}]

      ;; Only a death that happened. `apply-event` lets a second death of the
      ;; same player through as a no-op, and so does this.
      :player/died (when (get-in before [:players player :alive?])
                     [{:type :player/died :player player :cause (:cause ev)}])
      :player/revived [{:type :player/revived :player player}]
      :night/quiet    [{:type :night/quiet}]
      :jaeger/shot    [{:type :jaeger/shot :player player :target target}]
      :jaeger/held-fire [{:type :jaeger/held-fire :player player}]

      ;; The ballots were never published while the election ran, so nobody
      ;; could follow the crowd. Afterwards that reason is gone.
      :election/resolved [{:type :election/resolved :mayor (:mayor ev)
                           :tie? (boolean (:tie? ev))
                           :votes (or (:election-votes before) {})}]
      :mayor/appointed [{:type :mayor/appointed :player player :by (:by ev)}]
      :mayor/vacated   [{:type :mayor/vacated :by (:by ev)}]
      :player/nominated [{:type :player/nominated :by (:by ev) :target target}]
      :day/no-accusation [{:type :day/no-accusation}]
      :vote/resolved [{:type :vote/resolved :target target :tie? (boolean (:tie? ev))
                       :votes (or (:votes before) {})}]

      ;; The last line of a game nobody finished: the story stops where they
      ;; left it, and the recap says so rather than end mid-sentence.
      :game/abandoned [{:type :game/abandoned}]

      :gm/forced (gm-entry before ev)
      ;; In :setup this is the Spielleiter still arranging the deal, which the
      ;; cast list already shows the result of. Afterwards it is an override,
      ;; and now both cards can finally be said.
      :player/role-changed
      (when (and (not= :setup (:phase before)) (get-in before [:players player]))
        [{:type :gm/reassigned :target player
          :from (role-of before player) :to (:role ev)}])

      nil)))

(defn- give-wolves-their-victim
  "`:werewolf/chose` fills in the entry its own turn's completion opened. Should
   it ever arrive without one, it stands alone rather than vanish."
  [acc before {:keys [target]}]
  (let [guarded? (= target (get-in before [:night-actions :protected]))
        entry    (cond-> {:type :wolves/chose :target target
                          :votes (get-in before [:night-actions :werewolf-votes] {})}
                   guarded? (assoc :guarded? true))
        r        (current acc)
        i        (dec (count (:entries r)))]
    (if (and r (>= i 0)
             (= :wolves/chose (:type (get-in r [:entries i])))
             (nil? (:target (get-in r [:entries i]))))
      (update-in acc [:rounds (dec (count (:rounds acc))) :entries i]
                 #(cond-> (assoc % :target target) guarded? (assoc :guarded? true)))
      (add-entries acc [entry]))))

;; ------------------------------------------------------------------ fold ---

(defn- fold
  "One event into the state the recap reads alongside.

   `:game/abandoned` is the one event folded here rather than by the reducer: a
   game the server retired because nobody came back ends with no winner, and
   that — over, nobody won — is all the recap needs to know of it. Saying so
   here keeps the recap's own ending independent of whatever else the reducer
   does with that event."
  [state ev]
  (if (= :game/abandoned (:type ev))
    (assoc state :phase :game-over :winner nil)
    (e/apply-event state ev)))

(defn- step [acc ev]
  (let [before (:state acc)
        after  (fold before ev)
        acc    (assoc acc :state after)
        ;; What everybody held when the first night fell: the deal, as the
        ;; Spielleiter finally left it.
        acc    (if (and (= :night/began (:type ev)) (nil? (:dealt acc)))
                 (assoc acc :dealt (into {} (map (fn [pid] [pid (role-of after pid)]))
                                         (e/participant-ids after)))
                 acc)
        acc    (cond
                 (= :night/began (:type ev)) (open-round acc :night (:night ev))
                 (and (day-starts? ev) (current acc)) (open-round acc :day (:night before))
                 :else acc)
        acc    (case (:type ev)
                 :player/died (if (get-in before [:players (:player ev) :alive?])
                                (assoc-in acc [:deaths (:player ev)]
                                          {:cause (:cause ev)
                                           :kind  (:kind (current acc))
                                           :n     (:n (current acc))})
                                acc)
                 :player/revived (update acc :deaths dissoc (:player ev))
                 acc)]
    (if (= :werewolf/chose (:type ev))
      (if (current acc) (give-wolves-their-victim acc before ev) acc)
      (add-entries acc (facts before ev)))))

(defn- cast-list
  "Who was who: every player of the game in seat order, with the card they ended
   on, the card they started with when that differs, and how it ended for them.
   The Spielleiter is not in it — he held no card."
  [{:keys [state dealt deaths]}]
  (vec (for [pid (e/participant-ids state)
             :let [p (get-in state [:players pid])
                   d (get dealt pid)]]
         (cond-> {:id pid :role (:role p) :alive? (boolean (:alive? p))}
           (and d (not= d (:role p)))                  (assoc :dealt d)
           (contains? (set (:lovers state)) pid)       (assoc :lover? true)
           (= pid (:mayor state))                      (assoc :mayor? true)
           (and (not (:alive? p)) (get deaths pid))    (assoc :death (get deaths pid))))))

(defn recap
  "Events in, recap out — or nil when the log is not a finished game.

   {:winner  team, or nil for a game abandoned before anybody won
    :players [{:id :role :alive? :dealt? :lover? :mayor? :death?} ...]
    :rounds  [{:kind :night|:day :n 1 :entries [{:type ..} ...]} ...]}

   Entries hold ids, roles and keywords only; see the namespace docstring."
  [events]
  (let [acc (reduce step
                    {:state nil :rounds [] :dealt nil :deaths {}}
                    (remove #(never-read (:type %)) events))]
    (when (= :game-over (:phase (:state acc)))
      {:winner  (:winner (:state acc))
       :players (cast-list acc)
       :rounds  (vec (remove #(empty? (:entries %)) (:rounds acc)))})))
