(ns werwolf.engine.roles
  "The role registry — pure data.

   Adding a role must be additive: the phase machine reads `night-order`,
   `night-action` and `on-death` from this map and never mentions a role by name.
   If you find yourself writing `(= role :hexe)` in phases.cljc, put the
   distinguishing fact in this registry instead.

   Terminology is Düsterwald German (the canonical edition); i18n keys are
   derived as :role/<id> and :role/<id>.desc — see werwolf.i18n.")

;; --------------------------------------------------------------- teams ---

(def teams
  "A team defines a win condition family. :lovers is not a team you are dealt —
   Amor creates it at runtime, and it overrides the dealt team."
  #{:village :werewolf})

;; --------------------------------------------------------------- roles ---

(def registry
  "id -> role definition.

   :team          which win condition this role plays for when dealt
   :night-order   nil = no night action. Lower runs earlier. Sparse on purpose,
                  so inserting a role between two existing ones needs no renumbering.
   :night-action  the action spec the server offers this role at night:
                    :target/one     pick exactly one other player
                    :target/two     pick exactly two players (Amor)
                    :target/collective  the whole team agrees on one target (wolves)
                    :target/optional-two-choice  Hexe: heal and/or poison, or pass
   :first-night-only?  action happens only on night 1 (Amor)
   :skips-first-night? this role has NO turn on night 1 by default. Re-enabled
                       per room by the :first-night-kill? rule flag in state.
   :knows-teammates?   at deal time, does this player learn who shares their team?
   :on-death      reaction pushed onto the reaction stack when this role dies
   :resources     per-game one-shot resources, consumed by events
   :reveals-role-on-death? classic Werwolf reveals the lynched player's role"
  {:werwolf
   {:id                :werwolf
    :team              :werewolf
    :night-order       200
    :night-action      :target/collective
    :knows-teammates?  true
    ;; No kill on the first night. A player eliminated before they have said a
    ;; single word has not played a game, they have watched one — and on night 1
    ;; the wolves would be choosing at random anyway, so the kill costs the
    ;; village a player and teaches nobody anything.
    ;;
    ;; They still recognise each other: teammates are known from the deal
    ;; (:knows-teammates?), not from meeting at night, so skipping the turn
    ;; loses only their night-1 whisper. Set FIRST_NIGHT_KILL=true for the
    ;; classic rule.
    :skips-first-night? true
    :max-count         nil}

   :dorfbewohner
   {:id                :dorfbewohner
    :team              :village
    :night-order       nil
    :night-action      nil
    :knows-teammates?  false
    :max-count         nil}

   :seherin
   {:id                :seherin
    :team              :village
    :night-order       100
    :night-action      :target/one
    :knows-teammates?  false
    ;; The result is private and accumulates in the player's own view.
    :learns            :role-of-target
    :max-count         1}

   :hexe
   {:id                :hexe
    :team              :village
    :night-order       300 ; after the wolves — she must see who is dying
    :night-action      :target/optional-two-choice
    :knows-teammates?  false
    :resources         {:heiltrank 1 :gifttrank 1}
    ;; She may see the pending werewolf victim in order to decide on the heal.
    :sees-pending-deaths? true
    :max-count         1}

   :jaeger
   {:id                :jaeger
    :team              :village
    :night-order       nil
    :night-action      nil
    :knows-teammates?  false
    ;; Dies -> immediately shoots. Can chain (a Jäger can shoot a Jäger).
    :on-death          :reaction/jaeger-shot
    :max-count         1}

   :weisser-werwolf
   {:id                :weisser-werwolf
    ;; DEALT as a werewolf, and that is not a compromise — it is how he works. He
    ;; wakes with the pack, they count him as one of their own, they cannot eat
    ;; him, and the village does not win while he lives. His betrayal is a
    ;; separate WIN CONDITION rather than a separate team; see phases/winner.
    :team              :werewolf
    ;; After the pack, so he is choosing which of them to remove having already
    ;; seen their victim — and before the Hexe, so she still sees the pack's kill
    ;; and can decide about her heal. His own kill is not hers to cancel.
    :night-order       250
    :night-action      :target/one
    :knows-teammates?  true
    ;; Every SECOND night, from the second. A wolf-killer who acted nightly would
    ;; simply dismantle his own team before the village noticed anything.
    :acts-on-even-nights? true
    ;; What the OTHER wolves see when they look at him: an ordinary werewolf.
    ;;
    ;; They must know he hunts with them — he shares their chat and their turn, so
    ;; pretending otherwise would be transparent — and must NOT know he is the one
    ;; who will turn on them. That asymmetry IS the role. He sees their true roles;
    ;; `:appears-as` only masks what they see of him.
    :appears-as        :werwolf
    :max-count         1}

   :beschuetzer
   {:id                :beschuetzer
    :team              :village
    ;; Between the Seherin and the wolves, and that ordering IS the rule: he
    ;; commits to a target before knowing who the wolves will choose. Guessing
    ;; is the whole game he plays — a Beschützer who acted after them would
    ;; simply cancel the night.
    :night-order       150
    :night-action      :target/one
    :knows-teammates?  false
    ;; He may guard himself, but never the same player two nights running. The
    ;; repeat ban is what stops one player being permanently immune, which would
    ;; otherwise be the strongest position in the game.
    :no-repeat-target? true
    :max-count         1}

   :dieb
   {:id                :dieb
    ;; Village while he holds the card, and that is all he is: a Dieb who keeps it
    ;; has no power at all. What he plays for depends on what he takes.
    :team              :village
    ;; First of all, ahead of Amor — he must know what he IS before anybody acts
    ;; on it, and Amor may well link him.
    :night-order       25
    ;; Neither a player nor a pair: he points at one of two CARDS. The choice
    ;; travels under :role, which `proto/choice-key` already knows how to carry —
    ;; the same machinery the GM overrides use.
    :night-action      :choice/one-card
    :first-night-only? true
    :knows-teammates?  false
    ;; Two extra cards go into the deck when he is dealt, and the two nobody gets
    ;; are what he chooses between.
    :extra-cards       2
    :max-count         1}

   :amor
   {:id                :amor
    :team              :village
    :night-order       50 ; first of all
    :night-action      :target/two
    :first-night-only? true
    :knows-teammates?  false
    :creates           :lovers
    :max-count         1}})

(def all-ids (set (keys registry)))

(def assignable-ids
  "Every role a GM may hand out, in a STABLE order.

   Sorted, not the set: set iteration order differs between Clojure and
   ClojureScript, and this list reaches the client through `view-for`. An
   unsorted one would make two players' GM panels disagree about the order of
   the menu, and — worse, since the same rule already bit `:nominations` once —
   would make a replay platform-dependent if anything downstream ever indexed
   into it."
  (vec (sort all-ids)))

(defn role [id] (get registry id))

(defn team-of [id] (:team (role id)))

(defn night-acting-ids
  "Role ids that have a night action, in resolution order."
  []
  (->> registry
       vals
       (filter :night-order)
       (sort-by :night-order)
       (mapv :id)))

(defn on-death-reaction [id] (:on-death (role id)))

(defn initial-resources [id] (:resources (role id)))

(defn i18n-key [id] (keyword "role" (name id)))

;; ------------------------------------------------------- role set setup ---

(def ^:const absolute-min-players
  "The smallest table `compose-roles` makes a real game out of: one wolf, a
   Seherin and a villager.

   A hard floor, not a preference. Below three the game is decided before it
   starts — two players is a wolf and his dinner — so this is the one size rule
   nobody may override, the host included. `:min-players` is the room's
   RECOMMENDED size and the host may start under it; this is where that stops."
  3)

(def default-composition
  "Number of werewolves by player count, following common Düsterwald practice.
   The remaining seats are filled with the special village roles (in this
   priority order) and then plain Dorfbewohner."
  {5 1, 6 1, 7 2, 8 2, 9 2, 10 2, 11 3, 12 3, 13 3, 14 3, 15 4, 16 4, 17 4, 18 4})

(def ^:const dieb-from
  "Table size at which a Dieb is dealt.

   Ten, because his two spare cards make the deck n+2 and the composition has to
   have room to spare them without thinning the village to nothing."
  10)

(def special-village-priority
  "Which unique village roles to include as the table grows.
   Seherin first — the village is close to unplayable without information.

   The Beschützer is LAST on purpose. Appending rather than inserting means every
   table small enough to have been capped before deals exactly the deck it dealt
   before, so the change is confined to the larger tables it was added for. He is
   also the strongest of the specials — a nightly veto on the wolves' only move —
   and putting him in a six-player game would tilt it."
  [:seherin :hexe :jaeger :amor :beschuetzer])

(def ^:const weisser-werwolf-from
  "Table size at which one of the werewolves is the Weißer Werwolf instead.

   He REPLACES a wolf rather than joining as an extra, so the pack stays the size
   the composition table says — what changes is that one of them is playing for
   himself. Twelve, because he needs a pack big enough that removing one of them
   every other night is a strategy rather than immediate suicide: at two wolves he
   is simply killing the only ally he has."
  12)

(defn compose-roles
  "Return a vector of role ids of length n: the deck to be dealt.
   Pure and deterministic — shuffling happens elsewhere with a seeded RNG."
  [n]
  (let [wolves   (get default-composition n (max 1 (quot n 4)))
        white?   (and (>= n weisser-werwolf-from) (>= wolves 2))
        pack     (if white?
                   (into [:weisser-werwolf] (repeat (dec wolves) :werwolf))
                   (vec (repeat wolves :werwolf)))
        thief?   (>= n dieb-from)
        specials (cond-> (vec (take (max 0 (min (count special-village-priority)
                                                (- n wolves 1)))
                                    special-village-priority))
                   thief? (conj :dieb))
        villagers (- n wolves (count specials))]
    (into (into pack specials)
          (repeat villagers :dorfbewohner))))

(def ^:const centre-extras
  "The two cards that go into the middle when a Dieb is dealt.

   One wolf and one villager, fixed rather than drawn from the deck. Drawing the
   leftovers at random reads more like the cardboard game, but it can put the
   Dieb himself — or the only Seherin — in the middle, which produces a table
   nobody asked for and is very hard to explain to the people playing it.
   This way the choice is always the interesting one: join the wolves, or stay
   what you are.

   A consequence worth naming: because the two are never both wolves, the
   Düsterwald clause obliging him to take a card when they are is unreachable,
   and is therefore not implemented. He may always decline."
  [:werwolf :dorfbewohner])

(defn centre-for
  "The spare cards a table holding these roles needs: the Dieb's two, or none.

   A function of the cards in play rather than of the table size, so the
   Spielleiter's setup step can ask it again after rearranging the deal — making
   somebody the Dieb has to put his two cards on the table, and taking the card
   away has to clear them."
  [dealt-roles]
  (if (some #{:dieb} dealt-roles) (vec centre-extras) []))

;; ------------------------------------------------- the Spielleiter's deck ---
;;
;; A table that has played a few rounds wants to choose: two wolves tonight, no
;; Amor, the Hexe but not the Jäger. The room then holds a CUSTOM deck — a map
;; of special role -> count — and the plain villagers fill whatever seats it
;; leaves. Absent means the automatic composition above, which is how every log
;; written before this existed replays.
;;
;; Only the specials are chosen, never the villagers. The number of villagers is
;; not a decision anybody makes: it is the table size minus everything else, and
;; letting the host set it as well would make a deck that fits six players
;; wrong the moment a seventh sits down.

(def villager
  "The card that fills the deck. Named once, here, so nothing downstream has to
   know which role plays that part."
  :dorfbewohner)

(def special-ids
  "Every role a custom deck may name, in the order a Spielleiter reads them.

   The pack first, then the village in the order the night calls it — a narrator
   thinks of his deck as the night he will have to run — and the roles who never
   wake last. Derived from the registry rather than listed, so a new role turns
   up in the lobby's menu without anybody remembering to add it; sorted with a
   total order, so the menu is the same on every client (see `assignable-ids`)."
  (->> (vals registry)
       (remove #(= villager (:id %)))
       (sort-by (juxt #(if (= :werewolf (:team %)) 0 1)
                      #(or (:night-order %) 1000)
                      :id))
       (mapv :id)))

(defn max-count
  "How many of a role one deck may hold; nil means as many as there are seats."
  [id]
  (:max-count (role id)))

(defn deck-errors
  "Why `deck` is not a deck at all, or nil. Structural only: whether it FITS a
   table is `deck-problem`'s question, and the answer to that one changes as
   people join and leave — this one does not.

   Returns the first problem as `[code detail]`, in the shape the command layer
   reports errors."
  [deck max-players]
  (or (some (fn [[id n]]
              (cond
                (not (some #{id} special-ids)) [:unknown-role {:role id}]
                (not (int? n))                 [:invalid-count {:role id}]
                (neg? n)                       [:invalid-count {:role id}]
                (and (max-count id) (> n (max-count id)))
                [:too-many-of-role {:role id :max (max-count id)}]))
            ;; Sorted, so the error a client sees for a deck with two problems
            ;; is the same on every platform.
            (sort-by (comp str key) deck))
      ;; No deck holds more cards than the room has seats. Checked here rather
      ;; than left to `deck-problem`, which would only say "too many for this
      ;; table": a deck of forty wolves is not a deck waiting for players.
      (when (> (reduce + 0 (vals deck)) max-players)
        [:deck-too-large {:max max-players}])))

(defn normalise-deck
  "Drop the zeros. A role at nought is a role not in the deck, and keeping the
   key would make two identical decks unequal — and a replay compare them."
  [deck]
  (into {} (remove (comp zero? val)) deck))

(defn compose-custom
  "The deal for a custom deck at a table of n: its specials, in `special-ids`
   order, then villagers up to n.

   Never shorter than the specials. A deck that does not fit is not dealt —
   `deck-problem` sees to that — but the lobby still previews it, and showing
   the host fewer cards than he chose would hide the very thing he has to fix."
  [deck n]
  (let [specials (into [] (mapcat #(repeat (get deck % 0) %)) special-ids)]
    (into specials (repeat (max 0 (- n (count specials))) villager))))

(defn hunts?
  "Does this card kill for the werewolves at night? The pack's collective turn
   is the threat a village game is built around."
  [id]
  (and (= :werewolf (team-of id))
       (= :target/collective (:night-action (role id)))))

(defn deck-problem
  "Why a custom deck cannot be dealt to a table of n, or nil when it can.

     :too-many-roles  more specials than seats
     :no-werewolf     nobody hunts the village
     :no-villager     nobody but the wolves — the game is over before it starts

   :no-werewolf asks for a card that HUNTS, not merely one on the werewolf team.
   The Weißer Werwolf alone is on that team and kills only wolves; with no pack
   to belong to he wakes every second night to find nobody to turn on, and the
   village cannot lose. That is not a game anybody chose, it is a deck missing
   its wolves.

   An automatic deck (nil) is never a problem: `compose-roles` is built to fit
   any table from `absolute-min-players` up, and that floor is checked
   separately by the start rule."
  [deck n]
  (when deck
    (let [cards (compose-custom deck n)]
      (cond
        (> (count cards) n)                          :too-many-roles
        (not (some hunts? cards))                    :no-werewolf
        (every? #(= :werewolf (team-of %)) cards)    :no-villager))))

(defn compose-deck
  "The full deal: `{:deck [n roles] :centre [roles]}`.

   The centre is empty unless a Dieb is in the deck, in which case it holds the
   two cards he chooses between. Separating them here means `deal` shuffles only
   what players receive — the centre is not a lottery, so a game is not decided by
   which two cards happened to fall out of it.

   `custom`, when given, is the Spielleiter's deck; nil is the automatic one. The
   centre follows the cards either way — a Dieb the host chose brings his two
   spare cards with him exactly as a Dieb the table size dealt does."
  ([n] (compose-deck n nil))
  ([n custom]
   (let [deck (if custom (compose-custom custom n) (compose-roles n))]
     {:deck   deck
      :centre (centre-for deck)})))

(defn composition
  "What a deck holds, as counts: `{:dealt {role n} :centre {role n}}`.

   The shape the lobby shows and the table may know. Counts rather than the
   deal itself, because the deal is ordered and the order is nobody's business —
   it is only ever shuffled."
  [n custom]
  (let [{:keys [deck centre]} (compose-deck n custom)]
    {:dealt  (frequencies deck)
     :centre (frequencies centre)}))
