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

(def default-composition
  "Number of werewolves by player count, following common Düsterwald practice.
   The remaining seats are filled with the special village roles (in this
   priority order) and then plain Dorfbewohner."
  {5 1, 6 1, 7 2, 8 2, 9 2, 10 2, 11 3, 12 3, 13 3, 14 3, 15 4, 16 4, 17 4, 18 4})

(def special-village-priority
  "Which unique village roles to include as the table grows.
   Seherin first — the village is close to unplayable without information."
  [:seherin :hexe :jaeger :amor])

(defn compose-roles
  "Return a vector of role ids of length n: the deck to be dealt.
   Pure and deterministic — shuffling happens elsewhere with a seeded RNG."
  [n]
  (let [wolves   (get default-composition n (max 1 (quot n 4)))
        specials (vec (take (max 0 (min (count special-village-priority)
                                        (- n wolves 1)))
                            special-village-priority))
        villagers (- n wolves (count specials))]
    (into (into (vec (repeat wolves :werwolf)) specials)
          (repeat villagers :dorfbewohner))))
