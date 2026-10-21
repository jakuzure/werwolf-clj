(ns werwolf.engine.bot
  "Seats the server plays.

   A bot is an ordinary seat with nobody behind it, there so one person can
   play and test a whole game alone. Its moves live here, in .cljc, because
   they are tested on both platforms like the rest of the engine.

   `decide` takes the seat's REDACTED VIEW and a seed — never state. That is
   the whole fairness guarantee, and it is structural: whatever the view does
   not carry, the bot cannot know. A bot that knew the deal would make the
   human's own role untestable — a human wolf would be condemned on day one,
   every time.

   Plausible rather than clever. The wolves coordinate, the Seherin votes what
   she found, the rest follow the leading suspect so the day reaches a verdict;
   nobody bluffs, talks, or plans. The one thing the policy must never do is
   stall: whenever the engine offers a bot something, `decide` returns a legal
   command — the all-bot games in the tests are the proof."
  (:require [clojure.string :as str]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.rng :as rng]
            [werwolf.engine.roles :as roles]
            [werwolf.proto :as proto]))

;; ------------------------------------------------------------------ names ---

(def names
  "German first names for bot seats, in the order they are handed out. Twenty,
   so a full table of eighteen never runs dry. First names rather than 'Bot 1':
   the chronicle and the recap read like a table, not like a log."
  ["Agnes" "Bruno" "Clara" "Dieter" "Elke" "Falk" "Greta" "Hanno" "Ida" "Jonas"
   "Käthe" "Lutz" "Mia" "Nils" "Ottilie" "Paul" "Rosa" "Sven" "Thea" "Ulf"])

(def ^:private suffix
  "Same word in both languages, so the name needs no translation and the
   chronicle says who is a bot without any client knowing."
  " (Bot)")

(defn next-name
  "The first bot name not at the table, or nil when all twenty are.

   `taken` holds the names already seated, lower-cased and trimmed, which is
   exactly how the join rule compares them — so a human who called themselves
   'agnes (bot)' pushes the bot to the next name rather than tripping that rule."
  [taken]
  (first (remove #(contains? taken (str/lower-case %))
                 (map #(str % suffix) names))))

;; ----------------------------------------------------------------- offers ---

(def never-issued
  "What a bot never does, however legal: it does not talk, it does not leave,
   it holds no opinion on language, and it never takes the host's seat — a bot
   host could not advance a single phase. GM overrides are excluded by
   namespace below, as the simulation harness excludes them, so a new override
   cannot slip through a hand-kept list.

   The host's own buttons are here too. A bot is never host, so in a room they
   are never offered to one; they are excluded so that `decide` asked about a
   host's view — which the tests do — still answers like a bot."
  #{:chat :leave :set-locale :claim-host
    :start-game :begin-night :advance-phase :play-again})

(defn- excluded? [action]
  (or (contains? never-issued action) (= "gm" (namespace action))))

(defn offers
  "The actions a bot would consider right now: what the engine offers, minus
   what a bot never does, minus a ballot already cast. The open ballot stays
   offered to everyone so a human may change their mind; a bot that voted again
   on every tick would be a vote that never settles."
  [view]
  (let [me     (get-in view [:me :id])
        voted? (contains? (:votes view) me)]
    (into #{}
          (remove (fn [a] (or (excluded? a)
                              (and voted? (contains? #{:vote :vote-abstain} a)))))
          (:actions view))))

(defn seed-for
  "A seed for one decision, from the game's seed, the turn and the seat: a
   bot's choice is a function of the log, and two bots deciding at the same
   moment do not draw the same numbers."
  [game-seed turn pid]
  (rng/next-seed (+ game-seed (* 7919 turn) (* 104729 pid))))

;; -------------------------------------------------------------- knowledge ---

(defn- me-id [view] (get-in view [:me :id]))

(defn- wolf? [view]
  (= :werewolf (roles/team-of (get-in view [:me :role]))))

(defn- known-wolves
  "Living seats this bot knows to be werewolves, itself excluded. For a wolf
   that is its pack; for the Seherin, what she found; the dead are not among
   them because they are not living."
  [view]
  (let [me (me-id view)]
    (into #{}
          (keep (fn [[id role]]
                  (when (and (not= id me)
                             (= :werewolf (roles/team-of role))
                             (get-in view [:players id :alive?]))
                    id)))
          (:known-roles view))))

(defn- allowed
  "Legal targets of `action` a bot is willing to name: never itself, never its
   lover, and for a wolf never a fellow wolf."
  [view action]
  (let [me    (me-id view)
        lover (get-in view [:me :lover])
        pack  (if (wolf? view) (known-wolves view) #{})]
    (vec (remove (fn [t] (or (= t me) (= t lover) (contains? pack t)))
                 (get-in view [:targets action])))))

(defn- leader
  "The seat with the most votes cast today, lowest seat on a tie; nil when
   nobody has voted for anybody. Abstentions are nil targets and do not count."
  [view]
  (let [tally (frequencies (filter some? (vals (:votes view))))]
    (when (seq tally)
      (ffirst (sort-by (fn [[id n]] [(- n) id]) tally)))))

;; ----------------------------------------------------------------- decide ---

(defn decide
  "One legal command for the seat whose view this is, or nil when there is
   nothing a bot would do.

   The day's vote is the one place chance is not enough: a table voting at
   random almost never reaches the majority a condemnation needs, and the
   simulation's answer — everybody votes the lowest seat — would condemn the
   human host on day one. So: a wolf the seat knows of, else the leading
   suspect, else chance. Known wolves are also what a wolf never names, which
   is the same list read the other way round.

   Where `allowed` leaves nothing — a wolf whose lover is the last villager —
   the bot names a legal target anyway: a stalled table is worse than a
   heartbroken bot."
  [view seed]
  (let [me     (me-id view)
        offs   (offers view)
        cmd    (fn [type & kvs] (let [base {:type type :player me}]
                                  (if (seq kvs) (apply assoc base kvs) base)))
        draw   (fn [coll] (second (rng/rand-nth seed (vec coll))))
        target (fn [action]
                 (let [ts (allowed view action)
                       ts (if (seq ts) ts (vec (get-in view [:targets action])))]
                   (when (seq ts) (draw ts))))]
    (cond
      (empty? offs) nil

      ;; ---- night ----
      ;; The pack follows whoever of them voted first; the tally is theirs to
      ;; see. Lowest seat first, so both platforms read the same vote.
      (offs :werewolf-vote)
      (let [others (sort (dissoc (get-in view [:night-info :werewolf-votes]) me))
            t      (or (second (first others)) (target :werewolf-vote))]
        (cmd :werewolf-vote :target t))

      (offs :weisser-kill)        (cmd :weisser-kill :target (target :weisser-kill))
      (offs :seherin-inspect)     (cmd :seherin-inspect :target (target :seherin-inspect))
      (offs :beschuetzer-protect) (cmd :beschuetzer-protect :target (target :beschuetzer-protect))

      ;; Amor may link himself, so the raw targets are the right pool.
      (offs :amor-link)
      (let [ts     (vec (get-in view [:targets :amor-link]))
            [s1 a] (rng/rand-nth seed ts)
            [_ b]  (rng/rand-nth s1 (vec (remove #{a} ts)))]
        (cmd :amor-link :a a :b b))

      ;; Her own life and her lover's, always; a stranger's about a third of
      ;; the time; the poison from night 2 on, a quarter of the nights, so she
      ;; is neither spent on night 1 nor hoarding both potions to the end.
      (some offs [:hexe-heal :hexe-poison :hexe-pass])
      (let [dear    (set (remove nil? [me (get-in view [:me :lover])]))
            heal-t  (first (filter dear (get-in view [:targets :hexe-heal])))
            [s1 r1] (rng/rand-int seed 3)
            [_ r2]  (rng/rand-int s1 4)]
        (cond
          (and (offs :hexe-heal) heal-t)      (cmd :hexe-heal :target heal-t)
          (and (offs :hexe-heal) (zero? r1))  (cmd :hexe-heal :target (target :hexe-heal))
          (and (offs :hexe-poison) (>= (:night view 1) 2) (zero? r2))
          (cmd :hexe-poison :target (target :hexe-poison))
          (offs :hexe-pass)                   (cmd :hexe-pass)
          :else nil))

      ;; A card or his own, at random. The choice travels under the key the
      ;; protocol names for it, like the client's does.
      (some offs [:dieb-take :dieb-keep])
      (let [opts (cond-> (mapv (fn [c] (cmd :dieb-take (proto/choice-key :dieb-take) c))
                               (get-in view [:choices :dieb-take] []))
                   (offs :dieb-keep) (conj (cmd :dieb-keep)))]
        (when (seq opts) (draw opts)))

      ;; ---- day ----
      (offs :vote)
      (let [ok    (set (allowed view :vote))
            known (first (filter ok (sort (known-wolves view))))
            lead  (leader view)
            t     (or known (when (ok lead) lead) (target :vote))]
        (cmd :vote :target t))

      ;; Anybody, itself included: so do people.
      (offs :elect) (cmd :elect :target (draw (get-in view [:targets :elect])))

      (offs :ready) (cmd :ready)

      ;; A dying Jäger shoots a wolf he knows of, else today's suspect, else
      ;; somebody; he holds fire only when there is nobody he would shoot.
      (some offs [:jaeger-shoot :jaeger-pass])
      (let [ok    (set (allowed view :jaeger-shoot))
            known (first (filter ok (sort (known-wolves view))))
            lead  (leader view)
            t     (or known (when (ok lead) lead) (when (seq ok) (draw ok)))]
        (cond
          t                   (cmd :jaeger-shoot :target t)
          (offs :jaeger-pass) (cmd :jaeger-pass)
          :else               (cmd :jaeger-shoot :target (target :jaeger-shoot))))

      (some offs [:mayor-appoint :mayor-decline])
      (let [ok (allowed view :mayor-appoint)]
        (cond
          (seq ok)              (cmd :mayor-appoint :target (draw ok))
          (offs :mayor-decline) (cmd :mayor-decline)
          :else                 (cmd :mayor-appoint :target (target :mayor-appoint))))

      ;; Anything the engine offers that nothing above knows: the first of it,
      ;; with the first seat or the first choice it takes. Never nil while an
      ;; offer stands — the engine promises every offer has something to point
      ;; at (see `legal-actions`).
      :else
      (let [a (first (sort offs))]
        (cond
          (contains? phases/no-target-actions a) (cmd a)
          (proto/choice-key a)
          (when-let [c (first (get-in view [:choices a]))] (cmd a (proto/choice-key a) c))
          :else
          (when-let [t (first (get-in view [:targets a]))] (cmd a :target t)))))))
