(ns werwolf.i18n.core
  "Rendering: structured events in, sentences out, in the reader's own language.

   This is where the protocol's central decision pays off. The server sends
   `{:type :player/died :player 3 :cause :werwolf}` and never a string, so two
   people in the same room can read the same game in different languages — and a
   German and an English player at one table is the reason any of this exists.

   `.cljc` because the dictionaries are data worth testing on both platforms,
   even though only the client renders today. The one guarantee that matters is
   that the two dictionaries have identical key sets; there is a test.

   Icons and tones live here rather than in the dictionaries: an emoji and a
   colour are not language."
  (:require [clojure.string :as str]
            [tongue.core :as tongue]
            [werwolf.engine.roles :as roles]
            [werwolf.i18n.de :as de]
            [werwolf.i18n.en :as en]))

(def locales [:de :en])

(def dicts
  {:de de/dict
   :en en/dict
   ;; German is the fallback, not English: the terminology is Düsterwald's and a
   ;; missing key is likelier to be a German string somebody forgot to translate.
   :tongue/fallback :de})

(def translate (tongue/build-translate dicts))

(defn t
  "Look up a key in `locale`, applying any arguments.

   An unknown locale falls back rather than blowing up: it arrives from a client
   and from a database, and neither is trustworthy enough to index a map with."
  [locale k & args]
  (apply translate (if (some #{locale} locales) locale :de) k args))

(defn locale-name [locale] (t locale (keyword "locale" (name (or locale :de)))))

;; ------------------------------------------------------------------- names ---

(defn phase-name [locale phase] (t locale (keyword "phase" (name (or phase :lobby)))))
(defn role-name  [locale role]  (if role (t locale (keyword "role" (name role))) "?"))
(defn role-desc  [locale role]  (when role (t locale (keyword "role-desc" (name role)))))

(defn action-name
  "Label for an action. `:gm/force-kill` reads as `:action/gm-force-kill`, so a
   namespaced command needs no special case in the dictionaries."
  [locale action]
  (t locale (keyword "action" (str/replace (subs (str action) 1) "/" "-"))))

(def role-icon
  "Not language. Kept out of the dictionaries so they cannot drift apart on it."
  {:werwolf "🐺" :dorfbewohner "🏠" :seherin "🔮"
   :hexe "🧪" :jaeger "🎯" :amor "💘" :beschuetzer "🛡"
   :weisser-werwolf "🐺" :dieb "🃏"})

(defn icon-of [role] (get role-icon role "❓"))

;; ------------------------------------------------------------------- tones ---

(def ^:private tone-by-type
  "How prominent each kind of entry is. A log where a death and a reconnect look
   identical is a log nobody reads."
  {:player/died      :death
   :jaeger/shot      :death
   :vote/resolved    :verdict
   :day/no-accusation :verdict
   :night/quiet      :verdict
   :game/ended       :verdict
   :game/abandoned   :verdict
   :game/reset       :night
   :night/began      :night
   :game/started     :night
   :night/ended      :day
   :day/began        :day
   :role/revealed    :reveal
   :player/nominated :vote
   :gm/forced        :gm
   :host/changed     :gm
   :host/absent      :gm
   :host/returned    :gm
   :clock/paused     :gm
   :clock/resumed    :gm
   :clock/timer-set  :gm
   :game/narrated-set :gm
   :room/locked      :gm
   :room/unlocked    :gm
   ;; Unlike somebody leaving, which is bookkeeping: the host decided this,
   ;; and a decision about who sits at the table is what the Chronik audits.
   :player/kicked    :gm
   ;; Loud: what is in the deck is the first thing a table reasons from.
   :deck/set          :gm
   :deck/auto         :gm
   ;; Bookkeeping. Real, auditable, and not what anyone opens the Chronik for.
   :phase/timed-out  :muted
   :player/ready     :muted
   :nominate/passed  :muted
   :vote/cast        :muted
   :player/joined    :muted
   :player/left      :muted
   :player/disconnected :muted
   :player/reconnected  :muted
   :jaeger/held-fire :muted})

(defn log-tone
  "Tone for one entry. A function rather than a lookup, because prominence
   sometimes depends on the payload: a tie is worth reading, while 'the vote
   resolved and nothing happened' merely repeats the line above it."
  [{:keys [type target tie?]}]
  (if (and (= :vote/resolved type) (nil? target) (not tie?))
    :muted
    (get tone-by-type type :plain)))

(def loud?
  "Entries worth showing when the Chronicle is filtered to consequences."
  (complement #{:muted}))

;; -------------------------------------------------------------------- deck ---

(def deck-order
  "The order a composition is read in: the specials as the lobby's menu lists
   them, then the villagers who fill the rest. From the registry, so a new role
   needs no line here."
  (conj roles/special-ids roles/villager))

(defn deck-entries
  "A composition `{role n}` as `[[role n] ...]` in `deck-order`, zeros dropped.
   Never iterate the map itself: its order differs between Clojure and
   ClojureScript, and two players would read the deck in two orders."
  [counts]
  (vec (keep (fn [r] (let [n (get counts r 0)] (when (pos? n) [r n]))) deck-order)))

(defn deck-summary
  "'2× Werwolf, Seherin, Hexe'. A single card goes without its count — 'Seherin',
   not '1× Seherin', because nobody at a table says it that way."
  [locale counts]
  (if-let [es (seq (deck-entries counts))]
    (str/join ", " (map (fn [[r n]] (str (when (> n 1) (str n "× ")) (role-name locale r))) es))
    (t locale :deck/only-villagers)))

(defn deck-problem-text
  "Why the deck cannot be dealt, in a sentence. `specials` and `players` only
   matter for the one problem that is about numbers."
  [locale problem specials players]
  (case problem
    :too-many-roles (t locale :deck/problem-too-many-roles specials players)
    :no-werewolf    (t locale :deck/problem-no-werewolf)
    :no-villager    (t locale :deck/problem-no-villager)
    nil))

;; ---------------------------------------------------------------- log lines ---

(defn- who [players pid]
  (or (get-in players [pid :name]) (str "#" pid)))

(def ^:private death-keys
  {:werwolf :log/died-werwolf
   :gift    :log/died-gift
   :liebe   :log/died-liebe
   :lynch   :log/died-lynch
   :jaeger  :log/died-jaeger
   :weisser :log/died-weisser
   :gm      :log/died-gm})

(def ^:private gm-action-keys
  "GM overrides that need a line of their own.

   Only the ones that would otherwise leave no trace. A forced kill already logs
   :player/died with cause :gm, and a revive logs :player/revived, so giving
   those a second sentence makes the Chronik stutter — the same volume problem
   the grouping in `chronicle` exists to solve. Skipping a role, setting a phase
   and reassigning a role publish nothing else at all, and an override nobody
   can see is exactly what logging them was supposed to prevent."
  {:skip-role     :log/gm-skip-role
   :set-phase     :log/gm-set-phase
   :reassign-role :log/gm-reassign-role
   ;; The Spielleiter ending a phase only the clock used to end. Publishes
   ;; nothing else of its own, and closes a turn somebody was owed.
   :advance-phase :log/gm-advance-phase})

(defn- gm-line
  "One GM override, named. The argument depends on which noun the override
   points at: a role, a phase, or a player."
  [locale players {:keys [action detail]}]
  (if-let [k (gm-action-keys action)]
    (case action
      :skip-role     (t locale k (role-name locale (:role detail)))
      :set-phase     (t locale k (phase-name locale (:phase detail)))
      :advance-phase (t locale k (phase-name locale (:phase detail)))
      ;; The player, never the role. `:detail` does not carry one — see
      ;; `handle :gm/reassign-role` — and this is the reader's half of that
      ;; decision: the table learns whose card changed, not what it now says.
      :reassign-role (t locale k (who players (:target detail))))
    (t locale :log/gm-forced)))

(defn log-line
  "Render one public log entry. `players` supplies names, because the log holds
   ids — renaming yourself must not rewrite history."
  [locale players {:keys [type player target role winner phase host cause night voter tie?]
                   :as entry}]
  (let [nm #(who players %)]
    (case type
      :player/joined       (t locale :log/joined (nm player))
      :player/left         (t locale :log/left (nm player))
      ;; By the name the entry carries: the seat is gone, so looking it up
      ;; would print a number. See apply-event :player/kicked.
      :player/kicked       (t locale :log/kicked (or (:name entry) (nm player)))
      :player/disconnected (t locale :log/disconnected (nm player))
      :player/reconnected  (t locale :log/reconnected (nm player))
      ;; Taken from a host who had gone missing, or handed over — the table
      ;; should know which.
      :host/changed        (t locale (if (:claimed? entry) :log/host-claimed :log/host-changed)
                              (nm host))
      :host/absent         (t locale :log/host-absent (nm host))
      :host/returned       (t locale :log/host-returned (nm host))
      ;; In a narrated room the night does not fall with the deal — the
      ;; Spielleiter still has the cards in hand.
      :game/started        (t locale (if (:narrated? entry)
                                       :log/game-started-setup
                                       :log/game-started))
      :game/narrated-set   (t locale (if (:narrated? entry) :log/narrated-on :log/narrated-off))
      ;; Switched by nobody: the clock taking over from a missing Spielleiter.
      :clock/timer-set     (t locale (cond (= :host-absent (:cause entry)) :log/timer-on-absent
                                           (:on? entry) :log/timer-on
                                           :else :log/timer-off))
      ;; That the door changed, never what opens it: the entry carries nothing
      ;; else, by construction — see apply-event :room/password-set.
      :room/locked         (t locale :log/room-locked)
      :room/unlocked       (t locale :log/room-unlocked)
      :deck/set            (t locale :log/deck-set (deck-summary locale (:deck entry)))
      :deck/auto           (t locale :log/deck-auto)
      :night/began         (t locale :log/night-began)
      ;; Published without the ids on purpose: THAT there are lovers is public,
      ;; WHO they are is not. See apply-event :amor/linked.
      :amor/linked         (t locale :log/amor-linked)
      :night/quiet         (t locale (if (= 1 night) :log/night-quiet-first :log/night-quiet))
      :night/ended         (t locale :log/night-ended)
      :day/began           (t locale :log/day-began)
      :player/ready        (t locale :log/ready (nm player))
      :nominate/passed     (t locale :log/nominate-passed (nm player))
      :day/no-accusation   (t locale :log/no-accusation)
      :jaeger/held-fire    (t locale :log/jaeger-held-fire (nm player))
      :vote/cast           (if target
                             (t locale :log/vote-cast (nm voter) (nm target))
                             (t locale :log/vote-abstained (nm voter)))
      :player/died         (t locale (get death-keys cause :log/died) (nm player))
      :player/revived      (t locale :log/revived (nm player))
      :role/revealed       (t locale :log/role-revealed (nm player) (role-name locale role))
      :jaeger/shot         (t locale :log/jaeger-shot (nm player) (nm target))
      :vote/resolved       (if target
                             (t locale :log/lynched (nm target))
                             (t locale :log/tie))
      :player/nominated    (t locale :log/nominated (nm target))
      :phase/timed-out     (t locale :log/timed-out (phase-name locale phase))
      :gm/forced           (gm-line locale players entry)
      :election/resolved   (if (:mayor entry)
                             (t locale :log/mayor-elected (nm (:mayor entry)))
                             (t locale :log/mayor-none))
      :mayor/appointed     (t locale :log/mayor-appointed (nm player))
      :mayor/vacated       (t locale :log/mayor-vacated)
      :clock/paused        (t locale :log/clock-paused)
      :clock/resumed       (t locale :log/clock-resumed)
      :game/ended          (t locale (case winner
                                       :werewolf :log/win-werewolf
                                       :lovers   :log/win-lovers
                                       :weisser-werwolf :log/win-weisser
                                       :log/win-village))
      :game/abandoned      (t locale :log/game-abandoned)
      :game/reset          (t locale :log/game-reset)
      ;; A newer server may log something this client has never heard of. Show
      ;; the raw type rather than a blank line: visible is debuggable.
      (str type))))

;; ---------------------------------------------------------------- chronicle ---

(def ^:private group-keys
  {:player/ready        :log/group-ready
   :nominate/passed     :log/group-passed
   :vote/cast           :log/group-voted
   :player/joined       :log/group-joined
   :player/disconnected :log/group-disconnected
   :player/reconnected  :log/group-reconnected})

(defn- actor-of [e] (or (:player e) (:voter e)))

(def ^:private superseding
  "Entry types where only the last of a run still means anything. A host
   building a deck one stepper at a time logs every step, and every step is the
   whole deck — so a run of them is one decision, and it says what the last one
   says. Collapsing them by count would name the FIRST deck, which is the one
   nobody is going to play."
  #{:deck/set})

(defn- group-line
  "One line for a run of same-type entries, naming everyone in it.

   Naming beats counting: '5 × P5 is done' is worse than the noise it replaces,
   because it reads as the log stuttering on one player. Where no plural phrasing
   exists, fall back to the singular with a count rather than invent grammar."
  [locale players run]
  (let [names (str/join ", " (keep #(some->> (actor-of %) (who players)) run))]
    (if-let [k (group-keys (:type (first run)))]
      (t locale k names)
      (str (count run) "× " (log-line locale players (first run))))))

(defn chronicle
  "The raw log as display rows, newest first: {:text :tone :count}.

   Two jobs, both learned from a playtest. Runs of the same type collapse into
   one row — a quiet six-player day emits ten near-identical lines, sixteen at
   nine players, which reads as the log repeating itself. And bookkeeping is
   filtered out unless asked for, because showing every ready/pass/vote by
   default buried the one line that said somebody had died."
  [locale players log {:keys [show-all?]}]
  (->> log
       (map (fn [e] (assoc e ::tone (log-tone e))))
       (filter (fn [e] (or show-all? (loud? (::tone e)))))
       (partition-by :type)
       (mapcat (fn [run]
                 (if (or (< (count run) 2) (superseding (:type (first run))))
                   [{:tone (::tone (last run))
                     :text (log-line locale players (last run))}]
                   [{:tone (::tone (first run))
                     :text (group-line locale players run)
                     :count (count run)}])))
       reverse
       vec))
