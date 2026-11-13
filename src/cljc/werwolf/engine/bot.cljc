(ns werwolf.engine.bot
  "Seats the server plays.

   A bot is an ordinary seat with nobody behind it, there so one person can
   play and test a whole game alone. Its moves live here, in .cljc, because
   they are tested on both platforms like the rest of the engine.

   `decide` takes the seat's REDACTED VIEW and a seed, never state. That is
   the whole fairness guarantee, and it is structural: whatever the view does
   not carry, the bot cannot know. A bot that knew the deal would make the
   human's own role untestable: a human wolf would be condemned on day one,
   every time.

   Plausible rather than clever. What a bot knows for certain it acts on: the
   Seherin votes the wolf she found, the pack never names its own and settles
   on one victim. The rest it reasons from what the whole table saw, see
   `suspicion`: a seat that voted to condemn somebody whose card then turned
   out to be a villager's looks guilty, a seat that voted against a wolf looks
   honest. By day every bot votes its own suspect, the seed deciding where
   suspicion says nothing, and changes its vote only when that accusation
   has become hopeless: two votes behind another, or out of reach of a
   majority with the ballots still to come. Then it moves to a bigger one,
   so the day still reaches a verdict, but the table no longer runs after
   whoever voted first (see `hopeless?`). At night the same suspicion
   says whom the Seherin looks at, whom the Hexe poisons and whom the wolves
   eat first: the villager the table would trust. Nobody bluffs, talks, or
   plans. The one thing the policy must never do is stall: whenever the engine
   offers a bot something, `decide` returns a legal command; the all-bot games
   in the tests are the proof.

   When a bot moves is the server's business (werwolf.bots). `day-order` says
   only in which order the bots take the floor: those who know a wolf first,
   so that knowledge is on the tally before the crowd forms around it."
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
   exactly how the join rule compares them, so a human who called themselves
   'agnes (bot)' pushes the bot to the next name rather than tripping that rule."
  [taken]
  (first (remove #(contains? taken (str/lower-case %))
                 (map #(str % suffix) names))))

;; -------------------------------------------------------------- knowledge ---

(defn- me-id [view] (get-in view [:me :id]))

(defn- wolf-card? [role] (= :werewolf (roles/team-of role)))

(defn- wolf? [view] (wolf-card? (get-in view [:me :role])))

(defn- known-wolves
  "Living seats this bot knows to be werewolves, itself excluded. For a wolf
   that is its pack; for the Seherin, what she found; the dead are not among
   them because they are not living."
  [view]
  (let [me (me-id view)]
    (into #{}
          (keep (fn [[id role]]
                  (when (and (not= id me)
                             (wolf-card? role)
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

(defn- pack-lead
  "The victim the pack is settling on: what the lowest human wolf who has
   voted named, else what the lowest wolf did. A human's choice before a
   bot's, because the human is the one playing; the lowest seat among bots,
   so the pack never chases two leads. nil before anybody has voted."
  [view]
  (let [votes  (get-in view [:night-info :werewolf-votes] {})
        human? (fn [id] (not (get-in view [:players id :bot?])))
        voters (sort (keys votes))]
    (when-let [lead (or (first (filter human? voters)) (first voters))]
      (get votes lead))))

;; ----------------------------------------------------------------- offers ---

(def never-issued
  "What a bot never does, however legal: it does not talk, it does not leave,
   it holds no opinion on language, and it never takes the host's seat, since a
   bot host could not advance a single phase. GM overrides are excluded by
   namespace below, as the simulation harness excludes them, so a new override
   cannot slip through a hand-kept list.

   The host's own buttons are here too. A bot is never host, so in a room they
   are never offered to one; they are excluded so that `decide` asked about a
   host's view, which the tests do, still answers like a bot."
  #{:chat :leave :set-locale :claim-host
    :start-game :begin-night :advance-phase :play-again})

(defn- excluded? [action]
  (or (contains? never-issued action) (= "gm" (namespace action))))

(declare moves)

(defn offers
  "The actions a bot would consider right now: what the engine offers, minus
   what a bot never does, minus a ballot already cast. The open ballot stays
   offered to everyone so a human may change their mind; a bot that voted again
   on every tick would be a vote that never settles. So a bot that has voted
   holds the ballot only while it has somewhere to move (`moves`), and every
   move goes to an accusation with strictly more weight than the one it
   leaves, so the moving ends.

   The pack's vote is the same, once the engine keeps it open until the pack
   agrees (the Düsterwald rule): a wolf whose vote is the pack's lead is
   settled and holds no offer, however long the vote stays open; one whose
   vote differs still has the offer, and `decide` moves it to the lead. So
   the bots converge on the human wolf's choice, and among themselves on the
   lowest seat's, and nobody re-votes a vote that already agrees. A lead the
   engine would no longer accept, a seat that has left since, settles the
   bot as well: it cannot follow, and a vote it cast again on every tick
   would never let the night end."
  [view]
  (let [me         (me-id view)
        voted?     (contains? (:votes view) me)
        pack-votes (get-in view [:night-info :werewolf-votes] {})
        lead       (pack-lead view)
        settled?   (and (contains? pack-votes me)
                        (or (= (get pack-votes me) lead)
                            (not (some #{lead} (get-in view [:targets :werewolf-vote])))))]
    (into #{}
          (remove (fn [a] (or (excluded? a)
                              (and voted? (= :vote-abstain a))
                              (and voted? (= :vote a) (empty? (moves view)))
                              (and settled? (= :werewolf-vote a)))))
          (:actions view))))

(defn seed-for
  "A seed for one decision, from the game's seed, the turn and the seat: a
   bot's choice is a function of the log, and two bots deciding at the same
   moment do not draw the same numbers."
  [game-seed turn pid]
  (rng/next-seed (+ game-seed (* 7919 turn) (* 104729 pid))))

(defn share
  "A seeded number in [0, 1) for one seat and one turn, drawn like `seed-for`:
   the scheduler spreads the bots' moves with it, so WHEN a bot moves is a
   function of the game as much as what it does, and never of the wall clock."
  [game-seed turn pid]
  (/ (second (rng/rand-int (seed-for game-seed turn pid) 65536)) 65536.0))

;; -------------------------------------------------------------- suspicion ---

(defn- days
  "The public log day by day, oldest first: each day's ballot as it stood,
   a voter's last ballot of the day counting, and its `:verdict` once the day
   is decided (nil for a tie or no majority; absent while the ballot is open).
   Read from the log rather than from `:votes`, which by night still shows
   the ballot of the day just ended and never the days before it."
  [log]
  (reduce (fn [days {:keys [type voter target]}]
            (let [i (dec (count days))]
              (case type
                :day/began     (conj days {:votes {}})
                :vote/cast     (if (neg? i) days (update-in days [i :votes] assoc voter target))
                :vote/resolved (if (neg? i) days (assoc-in days [i :verdict] target))
                days)))
          []
          log))

(defn suspicion
  "How much each living seat looks like a wolf to this one, {pid score}, from
   what the whole table saw. The ballots everybody cast against the cards
   this seat knows, the ones turned over on death, what the Seherin found and
   the pack: every day a seat voted to condemn a villager counts one against
   it, every day it voted against a wolf counts one in its favour. And on a
   day that ended undecided, every seat that cast no ballot at all, not even
   an abstention, counts one against: a table that cannot agree turns on the
   one who sits it out. A night victim's own ballots score nobody, the wolves
   eat whom they please, and the dead hold no vote. Everything else is zero,
   and the seed breaks the ties.

   A villager's own card is not among the cards it reasons from, though it
   knows it best: a bot that suspected whoever voted against it saw a wolf in
   every accuser, and two such bots at a table of three voted each other for
   ever while the third seat slept. The silence rule is what ends that table
   with the sleeper condemned, the simulation's absent host above all. A
   wolf's own card is: whoever voted against it is after the pack, and that
   is who the pack eats first."
  [view]
  (let [me     (me-id view)
        roles  (if (wolf? view) (:known-roles view) (dissoc (:known-roles view) me))
        living (into #{} (keep (fn [[id p]] (when (:alive? p) id))) (:players view))]
    (reduce (fn [score {:keys [votes verdict] :as day}]
              (let [score (reduce (fn [score [voter target]]
                                    (let [role (get roles target)]
                                      (cond
                                        (not (contains? score voter)) score
                                        (nil? role)                   score
                                        (wolf-card? role)             (update score voter dec)
                                        :else                         (update score voter inc))))
                                  score
                                  votes)]
                (if (and (contains? day :verdict) (nil? verdict))
                  (reduce (fn [score id] (if (contains? votes id) score (update score id inc)))
                          score
                          (keys score))
                  score)))
            (zipmap living (repeat 0))
            (days (:log view)))))

(defn- pick
  "The candidate with the highest `key-fn`, the seed choosing among equals.
   The ties are sorted first so both platforms draw the same seat."
  [seed key-fn cands]
  (when (seq cands)
    (let [keyed (map (juxt key-fn identity) cands)
          best  (reduce (fn [a b] (if (pos? (compare (first b) (first a))) b a)) keyed)
          tied  (map second (filter #(= (first best) (first %)) keyed))]
      (second (rng/rand-nth seed (vec (sort tied)))))))

(defn- suspect
  "This seat's own top suspect among `cands`: the highest suspicion, and among
   equals the seat with most votes on the board today, so that one accusation
   already made outweighs none at all, then the seed. nil without candidates."
  [view seed cands]
  (let [score (suspicion view)
        tally (frequencies (filter some? (vals (:votes view))))]
    (pick seed (fn [id] [(get score id 0) (get tally id 0)]) cands)))

(defn- trusted
  "Whom the pack would rather eat: the seat the village has most reason to
   trust, which is the lowest suspicion, so whoever voted against a wolf, the
   one turned over since or the one still at the table, goes first; then the
   seed. nil without candidates."
  [view seed cands]
  (let [score (suspicion view)]
    (pick seed (fn [id] (- (get score id 0))) cands)))

;; ----------------------------------------------------------------- ballot ---

(defn- ballot
  "Today's ballot as the verdict will count it, with this seat's own ballot
   taken out: `:on`, the weight on each accused, `:open`, the weight of the
   living who have not voted, and `:mine`, what this seat's vote weighs.
   Taking its own ballot out is what lets a seat ask the same question
   before its first vote and after it: where would my vote count?

   The sum is the one the view publishes, `phases/count-ballot`; only this
   seat's own share comes off here. A sum of its own had drifted from the
   verdict's once already: in a narrated room it counted the Spielleiter as
   a vote still to come."
  [view]
  (let [me   (me-id view)
        mine (phases/vote-weight (:mayor view) me)
        cast (get (:votes view) me ::none)
        {:keys [on open] :or {on {} open 0}} (:ballot view)]
    {:on   (if (contains? on cast)
             (let [left (- (get on cast) mine)]
               (if (pos? left) (assoc on cast left) (dissoc on cast)))
             on)
     :open (if (= ::none cast) (- open mine) open)
     :mine mine}))

(defn- hopeless?
  "Would this seat's vote on `t` be wasted? When another accusation is two
   votes ahead of it, this vote counted; when it cannot reach `:votes-needed`
   even if every ballot still to come went to it; or when it is level with
   or behind another and only every ballot still to come would carry it.
   That last one is the tie at the end of the day: three against three with
   one seat left to vote, the absent host as often as not. Without it a
   fifth of the simulation's nine-seat days ended undecided, where people
   would have had one side give way.

   Two votes, not one: one is two seats agreeing by chance, and following
   that would rebuild the herd one voter later. The herd is what the
   staggered bots still were: on a day with nothing to go on, whoever
   voted first, a human wolf too, had every bot without a find behind
   that seat by the end of the window."
  [view {:keys [on open mine]} t]
  (let [here  (+ mine (get on t 0))
        ahead (reduce max 0 (vals (dissoc on t)))]
    (or (<= 2 (- ahead here))
        (< (+ here open) (:votes-needed view))
        (and (<= here ahead) (<= (+ here open) (:votes-needed view))))))

(defn- candidates
  "The seats this one would vote to condemn: `allowed`, and for the village
   never one it knows to be no wolf, the Seherin's finds, a card it was
   shown. A wolf keeps them: a villager it knows is one it wants gone."
  [view]
  (let [known (if (wolf? view) {} (:known-roles view))]
    (remove (fn [id] (and (contains? known id) (not (wolf-card? (get known id)))))
            (allowed view :vote))))

(defn moves
  "Where this seat's vote could go instead of where it is: the candidates with
   strictly more weight on them than its current target, other votes counted,
   that are not hopeless themselves. Empty while the current target is not
   hopeless, or is a wolf the seat knows of, or there is nowhere better.

   Strictly more is what ends the moving: every move shifts the same weight
   onto a bigger pile, so the sum of the piles' squares only ever grows, and
   a table of bots stops switching after a few moves however they are
   ordered. Public, because `offers` asks it and the tests do."
  [view]
  (let [me   (me-id view)
        mine (get (:votes view) me ::none)
        b    (ballot view)]
    (if (or (= ::none mine)
            (contains? (known-wolves view) mine)
            (not (hopeless? view b mine)))
      []
      (let [here (get-in b [:on mine] 0)]
        (vec (filter (fn [id] (and (not= id mine)
                                   (< here (get-in b [:on id] 0))
                                   (not (hopeless? view b id))))
                     (candidates view)))))))

(defn- biggest
  "The heaviest pile among `cands`, then this seat's own suspicion, then the
   seed."
  [view seed cands]
  (let [on    (:on (ballot view))
        score (suspicion view)]
    (pick seed (fn [id] [(get on id 0) (get score id 0)]) cands)))

(def ^:private follow-percent
  "How often a seat with no reason to prefer anybody prefers the accusation
   already on the board. Always was the herd. Never cost the village its best
   weapon, the Seherin taking the floor first with her find: over 120 seeds
   it won 65 games in 120 at nine seats instead of 82, and 74 instead of 99
   at twelve. Half the time keeps it within about ten games of where it was,
   still the favourite, while the first ballot spreads over more seats."
  50)

(defn- day-vote
  "Whom this seat votes by day. A wolf it knows of, first. Otherwise, before
   its first vote, its own suspect, and among equals the seed, or half the
   time the accusation already made (`follow-percent`), so a table with
   nothing to go on spreads its votes the way people do. That suspect is
   passed over only when it is already hopeless, for the biggest pile it
   could still help. After its first vote, the biggest of its `moves`."
  [view seed]
  (let [me    (me-id view)
        cands (vec (candidates view))
        cands (if (seq cands) cands (allowed view :vote))
        known (first (filter (set cands) (sort (known-wolves view))))
        score (suspicion view)]
    (cond
      known known

      (contains? (:votes view) me)
      (biggest view seed (moves view))

      :else
      (let [b         (ballot view)
            [s1 roll] (rng/rand-int seed 100)
            follow?   (< roll follow-percent)
            own       (pick s1 (fn [id] [(get score id 0) (if follow? (get-in b [:on id] 0) 0)]) cands)]
        (if (and own (hopeless? view b own))
          (or (biggest view seed (filter (fn [id] (and (pos? (get-in b [:on id] 0))
                                                       (not (hopeless? view b id))))
                                         cands))
              own)
          own)))))

;; -------------------------------------------------------------- the order ---

(defn informed?
  "Does this seat know a wolf it may vote: the Seherin with a find. Not the
   pack, though it knows every wolf: wolves seated first steered the crowd
   onto a villager every day, and over the simulation's seeds the village won
   fewer games than with no order at all. A villager whose lover is a wolf
   knows one too, but may not name them, and has nothing to bring to the
   tally."
  [view]
  (boolean (and (not (wolf? view))
                (some (set (allowed view :vote)) (known-wolves view)))))

(defn- day-draw
  "Two seeded numbers in [0, 1) for one seat and one day: its place among the
   bots without knowledge, and its step into its slot."
  [game-seed day pid]
  (let [[s1 a] (rng/rand-int (seed-for game-seed day pid) 65536)
        [_  b] (rng/rand-int s1 65536)]
    [(/ a 65536.0) (/ b 65536.0)]))

(defn day-order
  "The order in which the bots take the floor by day: those who know a wolf
   first, the rest shuffled by the seed, so the tally the crowd follows shows
   knowledge before chance. `informed` maps each living bot to `informed?`.
   A function of the game's seed, the day and the seats, so every recompute
   within a day gives the same order."
  [game-seed day informed]
  (vec (sort-by (fn [pid] [(if (informed pid) 0 1) (first (day-draw game-seed day pid)) pid])
                (keys informed))))

(defn day-shares
  "Where in the day's window each bot's turn falls, {pid share} with
   0 <= share < 1: the i-th of n bots in `day-order` gets the i-th n-th of
   the window, and a seeded step into the first half of it. So the first is
   at the window's start, the last before its end, no two at the same
   instant, and the ballots land one by one rather than as a stampede."
  [game-seed day informed]
  (let [order (day-order game-seed day informed)
        n     (count order)]
    (into {}
          (map-indexed (fn [i pid]
                         [pid (/ (+ i (/ (second (day-draw game-seed day pid)) 2)) n)]))
          order)))

;; ----------------------------------------------------------------- decide ---

(defn decide
  "One legal command for the seat whose view this is, or nil when there is
   nothing a bot would do.

   The day's vote is the one place chance is not enough: a table voting at
   random almost never reaches the majority a condemnation needs, and the
   simulation's answer, everybody votes the lowest seat, would condemn the
   human host on day one. Following the leading accusation reached the
   verdict but made the table a herd. So the votes spread first and gather
   later: see `day-vote` and `moves`. Known wolves are also what a wolf
   never names, which is the same list read the other way round.

   Where `allowed` leaves nothing, a wolf whose lover is the last villager,
   the bot names a legal target anyway: a stalled table is worse than a
   heartbroken bot."
  [view seed]
  (let [me      (me-id view)
        offs    (offers view)
        cmd     (fn [type & kvs] (let [base {:type type :player me}]
                                   (if (seq kvs) (apply assoc base kvs) base)))
        draw    (fn [coll] (second (rng/rand-nth seed (vec coll))))
        target  (fn [action]
                  (let [ts (allowed view action)
                        ts (if (seq ts) ts (vec (get-in view [:targets action])))]
                    (when (seq ts) (draw ts))))
        top     (fn [action]
                  (or (suspect view seed (allowed view action)) (target action)))]
    (cond
      (empty? offs) nil

      ;; ---- night ----
      ;; The pack follows its lead (`pack-lead`); the tally is theirs to see.
      ;; The first to vote names the villager the table trusts most. The
      ;; lead's choice is taken even over `allowed`: a pack that cannot agree
      ;; is a night that never ends.
      (offs :werewolf-vote)
      (let [lead (pack-lead view)
            ts   (set (get-in view [:targets :werewolf-vote]))
            t    (if (and lead (ts lead))
                   lead
                   (or (trusted view seed (allowed view :werewolf-vote))
                       (target :werewolf-vote)))]
        (cmd :werewolf-vote :target t))

      (offs :weisser-kill)        (cmd :weisser-kill :target (target :weisser-kill))
      ;; The seat she suspects most among those she has not looked at yet.
      (offs :seherin-inspect)     (cmd :seherin-inspect :target (top :seherin-inspect))
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
      ;; When she does poison, it is her top suspect.
      (some offs [:hexe-heal :hexe-poison :hexe-pass])
      (let [dear    (set (remove nil? [me (get-in view [:me :lover])]))
            heal-t  (first (filter dear (get-in view [:targets :hexe-heal])))
            [s1 r1] (rng/rand-int seed 3)
            [_ r2]  (rng/rand-int s1 4)]
        (cond
          (and (offs :hexe-heal) heal-t)      (cmd :hexe-heal :target heal-t)
          (and (offs :hexe-heal) (zero? r1))  (cmd :hexe-heal :target (target :hexe-heal))
          (and (offs :hexe-poison) (>= (:night view 1) 2) (zero? r2))
          (cmd :hexe-poison :target (top :hexe-poison))
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
      ;; A seat changing its vote names one of its `moves` or nothing, never
      ;; a random seat: a move to anything but a bigger pile could undo
      ;; another bot's and keep the ballot from settling.
      (offs :vote)
      (if (contains? (:votes view) me)
        (when-let [t (day-vote view seed)] (cmd :vote :target t))
        (cmd :vote :target (or (day-vote view seed) (target :vote))))

      ;; Anybody, itself included: so do people.
      (offs :elect) (cmd :elect :target (draw (get-in view [:targets :elect])))

      (offs :ready) (cmd :ready)

      ;; A dying Jäger shoots a wolf he knows of, else his top suspect, else
      ;; somebody; he holds fire only when there is nobody he would shoot.
      (some offs [:jaeger-shoot :jaeger-pass])
      (let [ok    (set (allowed view :jaeger-shoot))
            known (first (filter ok (sort (known-wolves view))))
            t     (or known (suspect view seed ok))]
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
      ;; offer stands: the engine promises every offer has something to point
      ;; at (see `legal-actions`).
      :else
      (let [a (first (sort offs))]
        (cond
          (contains? phases/no-target-actions a) (cmd a)
          (proto/choice-key a)
          (when-let [c (first (get-in view [:choices a]))] (cmd a (proto/choice-key a) c))
          :else
          (when-let [t (first (get-in view [:targets a]))] (cmd a :target t)))))))
