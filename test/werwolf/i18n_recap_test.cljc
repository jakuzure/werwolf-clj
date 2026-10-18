(ns werwolf.i18n-recap-test
  "The Rückblick's sentences, in both languages, from facts alone.

   The recap's wire format is ids and keywords; everything a player reads is made
   here. So the failures worth catching are the ones a player would see: a fact
   with no sentence, a key missing in one language, a name not substituted, a
   ballot that loses its abstentions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.engine.recap :as engine-recap]
            [werwolf.i18n.core :as i18n]
            [werwolf.i18n.recap :as recap]
            [werwolf.sim.harness :as h]))

(def ^:private players
  (into {} (map (fn [i] [i {:name (str "Name" i)}])) (range 10)))

(def ^:private every-kind-of-entry
  "One of each fact the builder produces — the shapes are pinned by
   werwolf.engine.recap-test against a played game."
  {:kind :night :n 2
   :entries [{:type :dieb/took :player 8 :took :werwolf :from :dieb}
             {:type :dieb/kept :player 8}
             {:type :amor/linked :lovers [4 9]}
             {:type :seherin/inspected :player 3 :target 2 :saw :weisser-werwolf}
             {:type :beschuetzer/protected :player 7 :target 4}
             {:type :wolves/chose :votes {2 9 8 9} :target 9}
             {:type :wolves/chose :votes {1 3} :target 3 :guarded? true}
             {:type :wolves/chose :votes {1 3 2 4} :target nil}
             {:type :weisser/killed :player 2 :target 8}
             {:type :weisser/passed :player 2}
             {:type :hexe/healed :player 4 :target 9}
             {:type :hexe/poisoned :player 4 :target 1}
             {:type :player/died :player 8 :cause :weisser}
             {:type :player/revived :player 8}
             {:type :night/quiet}
             {:type :jaeger/shot :player 5 :target 2}
             {:type :jaeger/held-fire :player 5}
             {:type :election/resolved :mayor 5 :tie? false :votes {1 5 2 nil}}
             {:type :election/resolved :mayor nil :tie? true :votes {1 5 2 6}}
             {:type :mayor/appointed :player 3 :by 5}
             {:type :mayor/vacated :by 5}
             {:type :player/nominated :by 3 :target 1}
             {:type :day/no-accusation}
             {:type :vote/resolved :target 1 :tie? false :votes {1 nil 2 1}}
             {:type :vote/resolved :target nil :tie? true :votes {1 2 2 1}}
             {:type :vote/resolved :target nil :tie? false :no-majority? true :votes {1 2 2 nil}}
             {:type :gm/reassigned :target 7 :from :beschuetzer :to :dorfbewohner}
             {:type :gm/forced :action :skip-role :skipped :seherin}
             {:type :gm/forced :action :set-phase :phase :vote}
             {:type :gm/forced :action :advance-phase :phase :night}
             {:type :game/abandoned}]})

(defn- untranslated? [s]
  (or (str/blank? s) (str/includes? s "missing") (str/starts-with? s ":")
      (str/includes? s "#")))

(deftest every-fact-has-a-sentence-in-both-languages
  (doseq [locale i18n/locales
          {:keys [text icon tone] :as row} (recap/round-lines locale players every-kind-of-entry)]
    (is (not (untranslated? text)) (str locale " " (pr-str row)))
    (is (seq icon))
    (is (keyword? tone))))

(deftest the-sentences-name-the-players-and-the-cards
  (let [[took _ linked seen] (recap/round-lines :de players every-kind-of-entry)]
    (is (= "Name8 nimmt sich die Karte Werwolf" (:text took)))
    (is (= "Amor verliebt Name4 und Name9" (:text linked)))
    (is (= "Name3 sieht nach: Name2 ist Weißer Werwolf" (:text seen))))
  (let [rows (recap/round-lines :en players every-kind-of-entry)]
    (is (= "The werewolves choose Name3 — but the Beschützer stands guard" (:text (nth rows 6))))
    (is (= "The werewolves agree on no victim" (:text (nth rows 7))))
    (is (= "The game master swaps Name7's card: Beschützer → Dorfbewohner"
           (:text (nth rows 26))))
    (is (= "The game master skips the Seherin" (:text (nth rows 27))))
    (is (= "No majority — the village condemns nobody" (:text (nth rows 25))))))

(deftest an-abandoned-game-ends-without-a-winner
  (let [row (last (recap/round-lines :de players every-kind-of-entry))]
    (is (= "Niemand ist zurückgekommen — die Runde wurde ohne Sieger beendet" (:text row)))
    (is (not (str/includes? (:text row) "gewonnen")) "nobody is congratulated")))

(deftest a-death-reads-the-way-the-chronik-reads-it
  (testing "one event, one sentence: the recap borrows the Chronik's"
    (let [row (nth (recap/round-lines :de players every-kind-of-entry) 12)]
      (is (= (i18n/log-line :de players {:type :player/died :player 8 :cause :weisser})
             (:text row)))
      (is (= :death (:tone row))))))

(deftest ballots-are-listed-with-their-abstentions
  (let [rows (recap/round-lines :de players every-kind-of-entry)]
    (is (= "Stimmen: Name2 → Name9, Name8 → Name9" (:votes (nth rows 5))))
    (is (= "Stimmen: Name1 enthält sich, Name2 → Name1" (:votes (nth rows 23))))
    (is (nil? (:votes (first rows))) "only counts carry ballots")))

(deftest a-quiet-night-reads-the-same-on-the-first
  ;; The first night had its own sentence, "bleibt ruhig — niemand stirbt",
  ;; which read like a rule even when the Hexe had just saved the wolves' victim.
  (is (= "In dieser Nacht ist niemand gestorben"
         (:text (first (recap/round-lines :de players {:kind :night :n 1
                                                       :entries [{:type :night/quiet}]})))))
  (is (= "In dieser Nacht ist niemand gestorben"
         (:text (first (recap/round-lines :de players {:kind :night :n 3
                                                       :entries [{:type :night/quiet}]}))))))

(deftest rounds-and-fates-read-in-both-languages
  (is (= "2. Nacht" (recap/round-title :de {:kind :night :n 2})))
  (is (= "Day 3" (recap/round-title :en {:kind :day :n 3})))
  (is (= "überlebt" (recap/fate-text :de {:alive? true})))
  (is (= "1. Tag · vom Dorf gerichtet"
         (recap/fate-text :de {:alive? false :death {:cause :lynch :kind :day :n 1}})))
  (is (= "Night 2 · torn apart by the werewolves"
         (recap/fate-text :en {:alive? false :death {:cause :werwolf :kind :night :n 2}})))
  (testing "a cause this client has never heard of is still a death, not a missing key"
    (is (= "gestorben" (recap/fate-text :de {:alive? false :death {:cause :meteor}})))))

(deftest the-small-print-after-a-role
  (is (= ["anfangs Dieb" "♥ verliebt" "🎖 Bürgermeister"]
         (recap/cast-tags :de {:dealt :dieb :lover? true :mayor? true})))
  (is (= [] (recap/cast-tags :en {:role :hexe}))))

(deftest an-unknown-fact-is-visible-not-blank
  (is (= ":meteor/struck"
         (:text (first (recap/round-lines :de players {:kind :night :n 1
                                                       :entries [{:type :meteor/struck}]}))))))

(deftest every-simulated-recap-renders-in-both-languages
  (testing "generated from played games, so a fact the builder learns to produce
            cannot ship without a sentence"
    (doseq [{:keys [log state]} [(h/play-game 9 33) (h/play-game 12 44)
                                 (h/play-game 7 22 {:narrated? true})
                                 (h/play-game 10 5 {:random-deck? true})]
            :let [rc (engine-recap/recap log)
                  names (into {} (map (fn [[id p]] [id {:name (:name p)}])) (:players state))]
            locale i18n/locales
            round (:rounds rc)
            row (recap/round-lines locale names round)]
      (is (not (untranslated? (:text row))) (str locale " " (pr-str row))))))

(deftest a-death-already-told-is-not-told-again
  (let [round {:kind :day :n 2
               :entries [{:type :vote/resolved :target 1 :tie? false :votes {2 1 3 1}}
                         {:type :player/died :player 1 :cause :lynch}
                         {:type :jaeger/shot :player 1 :target 3}
                         {:type :player/died :player 3 :cause :jaeger}]}
        rows (recap/round-lines :de players round)]
    (is (= 2 (count rows)) "the verdict and the shot each name their dead")
    (is (= [:verdict :death] (map :tone rows)))))
