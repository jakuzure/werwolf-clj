(ns werwolf.i18n-test
  "The dictionaries, checked against each other and against the engine.

   Two failure modes this suite exists to prevent. A key that exists in one
   language and not the other, which shows up as raw `|missing key|` text in
   somebody's UI. And a log event, phase, role or action the engine can produce
   that no dictionary has a line for — which is worse, because it only appears
   when that thing actually happens in a real game."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]
            [werwolf.i18n.core :as i18n]
            [werwolf.i18n.de :as de]
            [werwolf.i18n.en :as en]
            [werwolf.proto :as proto]
            [werwolf.sim.harness :as h]))

(def ^:private players
  {0 {:name "Anna"} 1 {:name "Bob"} 2 {:name "Cem"}})

(defn- untranslated?
  "tongue renders a missing key as a marker string rather than throwing."
  [s]
  (or (str/blank? s)
      (str/includes? s "missing")
      (str/starts-with? s ":")))

;; ------------------------------------------------------------ completeness ---

(deftest the-dictionaries-have-identical-key-sets
  (testing "a key in one language and not the other is |missing key| in
            somebody's UI, and nobody tests in the language they do not read"
    (is (empty? (remove (set (keys en/dict)) (keys de/dict)))
        (str "German has keys English lacks: " (pr-str en/missing-keys)))
    (is (empty? (remove (set (keys de/dict)) (keys en/dict)))
        (str "English has keys German lacks: "
             (pr-str (sort (remove (set (keys de/dict)) (keys en/dict))))))))

(deftest translations-have-matching-shapes
  (testing "a key that interpolates in one language must interpolate in the
            other, or the argument silently vanishes for half the table"
    (doseq [k (sort (keys de/dict))]
      (is (= (fn? (get de/dict k)) (fn? (get en/dict k)))
          (str k " is a function in one language and a literal in the other")))))

;; ------------------------------------------------- coverage of the engine ---

(deftest every-phase-role-and-action-is-named-in-both-languages
  (doseq [locale i18n/locales]
    (testing (str locale)
      (doseq [p (concat (keys phases/next-phase)
                        [:lobby :setup :night :day-reveal :reaction :game-over])]
        (is (not (untranslated? (i18n/phase-name locale p))) (str p)))
      (doseq [r (keys roles/registry)]
        (is (not (untranslated? (i18n/role-name locale r))) (str r))
        (is (not (untranslated? (i18n/role-desc locale r))) (str r " description"))
        (is (some? (i18n/role-icon r)) (str r " icon")))
      (testing "every action the protocol will carry has a label"
        (doseq [a (conj proto/command-types :chat)]
          (is (not (untranslated? (i18n/action-name locale a))) (str a)))))))

(deftest every-event-a-real-game-logs-has-a-sentence
  (testing "generated from played games rather than from a list somebody
            maintains by hand — the same trick as the protocol suite, and for
            the same reason: a hand-kept list is a list that drifts"
    ;; `(:log state)`, not `(:log result)`. The latter is the full event stream,
    ;; which includes internals like :phase/changed and :hexe/healed that are
    ;; deliberately never published — the public projection is what a player
    ;; reads, and it is the only thing that needs a sentence.
    (let [logged (into #{}
                       (comp (map (comp :log :state)) cat (map :type))
                       (concat
                        (for [[n seed] [[5 11] [7 22] [9 33] [12 44] [18 55]]]
                          (h/play-game n seed))
                        ;; And narrated ones: the setup step, the clock
                        ;; switched off, and the Spielleiter ending the phases
                        ;; it used to end each log something of their own.
                        (for [[n seed] [[6 66] [10 77]]]
                          (h/play-game n seed {:narrated? true}))))]
      (is (> (count logged) 10) "the corpus is real games")
      (doseq [locale i18n/locales
              type logged]
        (let [line (i18n/log-line locale players
                                  {:type type :player 0 :target 1 :voter 0
                                   :host 0 :role :seherin :winner :village
                                   :phase :vote :cause :werwolf :night 2})]
          (is (not (untranslated? line))
              (str type " has no line in " locale)))))))

(deftest every-death-cause-reads-as-a-whole-sentence
  (testing "German and English put the pieces in different orders, so a cause
            must be a complete sentence rather than a suffix"
    (doseq [locale i18n/locales
            cause [:werwolf :gift :liebe :lynch :jaeger :gm nil]]
      (let [line (i18n/log-line locale players
                                {:type :player/died :player 0 :cause cause})]
        (is (str/includes? line "Anna") (str cause " in " locale))
        (is (not (untranslated? line)))))))

;; ------------------------------------------------------- the GM's overrides ---

(deftest a-gm-override-says-what-it-did
  (testing "'the game master intervenes' audits nothing. The overrides that
            publish no other line have to name their own effect"
    (doseq [locale i18n/locales]
      (let [line #(i18n/log-line locale players %)]
        (is (str/includes? (line {:type :gm/forced :action :skip-role
                                  :detail {:role :seherin}})
                           "Seherin"))
        (is (not (untranslated? (line {:type :gm/forced :action :set-phase
                                       :detail {:phase :vote}}))))
        (testing "and one of them names a player while deliberately NOT naming
                  the role, because the whole table reads this"
          (let [l (line {:type :gm/forced :action :reassign-role :detail {:target 1}})]
            (is (str/includes? l "Bob"))
            (is (not (untranslated? l)))
            (doseq [r (keys roles/registry)]
              (is (not (str/includes? l (i18n/role-name locale r)))
                  (str r " must not appear in a public reassignment line")))))
        (testing "the Spielleiter ending a phase the clock used to end names it"
          (is (str/includes? (line {:type :gm/forced :action :advance-phase
                                    :detail {:phase :night}})
                             (i18n/phase-name locale :night))))
        (testing "an override with no line of its own still reads as something"
          (is (not (untranslated? (line {:type :gm/forced :action :pause :detail {}})))))))))

;; ------------------------------------------------------- actually bilingual ---

(deftest the-same-event-reads-differently-in-each-language
  (testing "the whole point: one structured event, two languages"
    (let [ev {:type :player/died :player 0 :cause :werwolf}
          de-line (i18n/log-line :de players ev)
          en-line (i18n/log-line :en players ev)]
      (is (not= de-line en-line))
      (is (str/includes? de-line "Werwölfen"))
      (is (str/includes? en-line "werewolves"))
      (testing "and the player's name is identical in both — names are data,
                not language"
        (is (str/includes? de-line "Anna"))
        (is (str/includes? en-line "Anna"))))))

(deftest role-names-stay-german-in-english
  (testing "deliberate: a mixed table has to be able to say a role out loud and
            be understood. 'Seer' and 'Seherin' in one conversation is a bug in
            the game, not a translation"
    (doseq [r (keys roles/registry)]
      (is (= (i18n/role-name :de r) (i18n/role-name :en r))))
    (testing "while the descriptions really are translated"
      (is (not= (i18n/role-desc :de :seherin) (i18n/role-desc :en :seherin))))))

(deftest an-unknown-locale-falls-back-rather-than-exploding
  (testing "it arrives from a client and from a database; neither is trustworthy
            enough to index a map with"
    (is (= (i18n/t :de :app/title) (i18n/t :klingon :app/title)))
    (is (= (i18n/t :de :app/title) (i18n/t nil :app/title)))))

;; ------------------------------------------------------------- the chronicle ---

(deftest the-chronicle-groups-in-both-languages
  (let [log [{:type :player/ready :player 0}
             {:type :player/ready :player 1}
             {:type :player/ready :player 2}]
        de-rows (i18n/chronicle :de players log {:show-all? true})
        en-rows (i18n/chronicle :en players log {:show-all? true})]
    (is (= 1 (count de-rows) (count en-rows)) "collapsed in both")
    (is (= 3 (:count (first de-rows))))
    (is (= "Anna, Bob, Cem sind fertig" (:text (first de-rows))))
    (is (= "Anna, Bob, Cem are done" (:text (first en-rows))))
    (testing "and the verb agrees with the subject, which a count spliced into
              the singular form could not do"
      (is (not (str/includes? (:text (first de-rows)) "ist fertig"))))))

(deftest the-table-switches-read-both-ways
  (testing "each switch logs its new position, and both positions need a line"
    (doseq [locale i18n/locales
            entry [{:type :game/narrated-set :narrated? true}
                   {:type :game/narrated-set :narrated? false}
                   {:type :clock/timer-set :on? true}
                   {:type :clock/timer-set :on? false}
                   {:type :room/locked}
                   {:type :room/unlocked}
                   {:type :game/started :players 5 :narrated? true}]]
      (is (not (untranslated? (i18n/log-line locale players entry)))
          (str entry " in " locale)))
    (is (not= (i18n/log-line :de players {:type :game/started :players 5})
              (i18n/log-line :de players {:type :game/started :players 5 :narrated? true}))
        "a narrated deal does not claim the night has fallen")))

(deftest an-unknown-event-type-is-visible-not-blank
  (testing "a newer server may log something this client has never heard of;
            showing the raw type is debuggable, a blank line is not"
    (is (str/includes? (i18n/log-line :de players {:type :something/new})
                       "something/new"))))

(deftest an-absent-host-reads-in-the-chronik
  (testing "the host going, coming back, his seat taken, and the clock taking
            over each have a line — and say which they are"
    (doseq [locale i18n/locales]
      (let [line #(i18n/log-line locale players %)]
        (doseq [entry [{:type :host/absent :host 0}
                       {:type :host/returned :host 0}
                       {:type :host/changed :host 1 :claimed? true}
                       {:type :clock/timer-set :on? true :cause :host-absent}]]
          (is (not (untranslated? (line entry))) (str entry " in " locale))
          (is (= :gm (i18n/log-tone entry)) (str entry " is a table-level fact")))
        (is (str/includes? (line {:type :host/absent :host 0}) "Anna"))
        (is (not= (line {:type :host/changed :host 1 :claimed? true})
                  (line {:type :host/changed :host 1}))
            "a seat taken is not a seat handed over")
        (is (not= (line {:type :clock/timer-set :on? true :cause :host-absent})
                  (line {:type :clock/timer-set :on? true}))
            "nor is a clock nobody switched on")))))
