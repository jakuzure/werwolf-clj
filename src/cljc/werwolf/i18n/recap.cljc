(ns werwolf.i18n.recap
  "The Rückblick in sentences: the recap's facts in, display rows out.

   The server sends ids, roles and keywords only (see werwolf.engine.recap), so
   the same ending reads in German at one phone and in English at the next, and
   names come from the view like everywhere else. Wherever the Chronik already
   has a sentence for a fact — a death and its cause, the Jäger's shot, the lynch
   — that sentence is reused, so the two can never tell one event two ways.

   Kept apart from i18n.core because it is one screen's worth of rendering, and
   pure .cljc so it is tested on both platforms without a DOM."
  (:require [clojure.string :as str]
            [werwolf.i18n.core :as i18n]))

(defn- who [players pid]
  (or (get-in players [pid :name]) (str "#" pid)))

(defn round-title
  "'1. Nacht' / 'Night 1'."
  [locale {:keys [kind n]}]
  (i18n/t locale (if (= :night kind) :recap/night :recap/day) n))

(defn round-icon [{:keys [kind]}] (if (= :night kind) "🌙" "☀"))

(defn votes-text
  "Every ballot of one count, 'Anna → Bob, Cem enthält sich', in seat order. In
   the Chronik's own words for a single vote, so a ballot reads the same in both."
  [locale players votes]
  (str/join ", "
            (for [[voter target] (sort-by key votes)]
              (if (some? target)
                (i18n/t locale :log/vote-cast (who players voter) (who players target))
                (i18n/t locale :log/vote-abstained (who players voter))))))

(defn- line
  "The sentence for one entry, plus the icon and tone it is shown with. The
   tones are the Chronik's, so the panel can borrow its colours."
  [locale players {:keys [type player target] :as entry} round]
  (let [nm  #(who players %)
        log #(i18n/log-line locale players %)
        rn  #(i18n/role-name locale %)]
    (case type
      :dieb/took   ["🃏" :plain (i18n/t locale :recap/dieb-took (nm player) (rn (:took entry)))]
      :dieb/kept   ["🃏" :plain (i18n/t locale :recap/dieb-kept (nm player))]
      :amor/linked (let [[a b] (:lovers entry)]
                     ["💘" :plain (i18n/t locale :recap/amor-linked (nm a) (nm b))])
      :seherin/inspected
      ["🔮" :plain (i18n/t locale :recap/seherin (nm player) (nm target) (rn (:saw entry)))]
      :beschuetzer/protected ["🛡" :plain (i18n/t locale :recap/beschuetzer (nm player) (nm target))]
      :wolves/chose
      ["🐺" :plain (cond
                     (nil? target)       (i18n/t locale :recap/wolves-none)
                     (:guarded? entry)   (i18n/t locale :recap/wolves-guarded (nm target))
                     :else               (i18n/t locale :recap/wolves-chose (nm target)))]
      :weisser/killed ["🐺" :plain (i18n/t locale :recap/weisser-killed (nm player) (nm target))]
      :weisser/passed ["🐺" :muted (i18n/t locale :recap/weisser-passed (nm player))]
      :hexe/healed    ["🧪" :plain (i18n/t locale :recap/hexe-healed (nm player) (nm target))]
      :hexe/poisoned  ["🧪" :plain (i18n/t locale :recap/hexe-poisoned (nm player) (nm target))]
      :player/died    ["☠" :death (log entry)]
      :player/revived ["✨" :plain (log entry)]
      :night/quiet    ["🌙" :verdict (log (assoc entry :night (:n round)))]
      :jaeger/shot    ["🎯" :death (log entry)]
      :jaeger/held-fire ["🎯" :plain (log entry)]
      :election/resolved ["🎖" :verdict (log entry)]
      :mayor/appointed ["🎖" :plain (log entry)]
      :mayor/vacated   ["🎖" :plain (log entry)]
      :player/nominated ["👉" :plain (i18n/t locale :recap/nominated (nm (:by entry)) (nm target))]
      :day/no-accusation ["⚖" :verdict (log entry)]
      :day/no-trial      ["⚖" :verdict (log entry)]
      :vote/resolved   ["⚖" :verdict (log entry)]
      :game/abandoned  ["🚪" :verdict (i18n/t locale :recap/abandoned)]
      :gm/reassigned
      ["🛠" :gm (i18n/t locale :recap/gm-reassigned (nm target) (rn (:from entry)) (rn (:to entry)))]
      :gm/forced
      ["🛠" :gm (log {:type :gm/forced :action (:action entry)
                      :detail {:role (:skipped entry) :phase (:phase entry)}})]
      ;; A newer server may recap something this client does not know. Visible
      ;; beats blank, as in the Chronik.
      ["·" :plain (str type)])))

(defn round-lines
  "One round as display rows: {:icon :tone :text :votes?}. `:votes` is the
   ballot line under the pack's choice, the election and the lynch — the fact
   the public log never carried for the first two, and the one a table most
   wants to argue about afterwards."
  [locale players round]
  (vec (for [entry (:entries round)
             :let [[icon tone text] (line locale players entry round)]]
         (cond-> {:icon icon :tone tone :text text}
           (seq (:votes entry))
           (assoc :votes (i18n/t locale :recap/votes (votes-text locale players (:votes entry))))))))

(defn when-text
  "'2. Nacht' / 'Day 3' for a death's round."
  [locale {:keys [kind n]}]
  (when (and kind n) (round-title locale {:kind kind :n n})))

(def ^:private known-causes
  "Causes with a phrase of their own. Anything else — a cause a newer engine
   invented — still reads as a death rather than as a missing key."
  #{:werwolf :gift :liebe :lynch :jaeger :weisser :gm})

(defn fate-text
  "How it ended for one player: survived, or the cause and the round."
  [locale {:keys [alive? death]}]
  (if alive?
    (i18n/t locale :recap/survived)
    (let [cause (i18n/t locale (keyword "recap" (str "cause-" (name (get known-causes (:cause death) :other)))))]
      (str/join " · " (remove nil? [(when-text locale death) cause])))))

(defn cast-tags
  "The small print after a role: the card a player started with when it is not
   the one they ended on, the lover's heart, the mayor's sash."
  [locale {:keys [dealt lover? mayor?]}]
  (cond-> []
    dealt  (conj (i18n/t locale :recap/dealt (i18n/role-name locale dealt)))
    lover? (conj (str "♥ " (i18n/t locale :recap/lover)))
    mayor? (conj (str "🎖 " (i18n/t locale :recap/mayor)))))
