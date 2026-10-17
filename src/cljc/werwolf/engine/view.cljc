(ns werwolf.engine.view
  "Per-player redaction — the security boundary of the whole system.

   THE RULE: this namespace BUILDS UP a view from nothing. It never takes full
   state and removes secrets.

   That direction is deliberate and non-negotiable. A blacklist (dissoc the
   secrets) fails silently and dangerously the moment someone adds a field to
   the state map: the new field is exposed by default and nobody notices until a
   player reads it out of the WebSocket frame. A whitelist fails safe — a new
   field is invisible until someone deliberately publishes it.

   So: if you add state that players should see, you must edit this file. That
   friction is the point.

   Digital Werwolf clones leak roles by sending everything and hiding it in the
   UI. DevTools defeats that in seconds. Everything here happens server-side,
   and `redaction-leaks?` below is enforced by a property test over generated
   games.

   What each player legitimately learns:
     * their own role, resources and accumulated private knowledge
     * fellow werewolves, if their role grants it (:knows-teammates?)
     * their lover, if Amor linked them
     * every player's id, name, alive/connected status  (public)
     * the public log, phase, night number, votes, nominations  (public)
     * once the game is over, all roles are revealed
     * the Spielleiter, who plays no card, sees every card — see below

   Note on the Spielleiter: in a narrated room the host does not play, and his
   job is the one a human narrator does at a real table, which is impossible
   without knowing who holds what. He is entitled to every role from the deal
   on, and that entitlement goes through `revealed-roles` like every other, so
   the leak detector holds him to exactly it. He can never be the source of a
   leak through the game itself: he has no vote, no target and no night turn,
   and nothing he sees is routed to anybody else.

   Note on dead players: they do NOT get a full role reveal by default. Many
   digital clones grant the dead an all-seeing 'ghost view', but that turns any
   bug in chat routing from embarrassing into catastrophic — a dead player who
   can see every role and reach a living player hands over the whole game. The
   dead keep what they learned while alive plus whatever the log made public.
   Set :reveal-to-dead? on the game to opt in."
  (:require [werwolf.engine.events :as e]
            [werwolf.engine.phases :as phases]
            [werwolf.engine.roles :as roles]))

;; ------------------------------------------------------- who sees whom ---

(defn- teammates
  "Players whose role pid is entitled to know, excluding pid.

   Only roles flagged :knows-teammates? learn each other, and they learn everyone
   on their TEAM rather than everyone with their exact role. That distinction is
   the Weißer Werwolf: he is dealt a different role but hunts with the pack, and
   comparing roles left him unable to see them and them unable to see him — while
   he sat in their chat channel, which routes by team."
  [state pid]
  (let [role-id (get-in state [:players pid :role])
        team    (roles/team-of role-id)]
    (if (and role-id team (:knows-teammates? (roles/role role-id)))
      (->> (:players state)
           (filter (fn [[other p]]
                     (and (not= other pid)
                          (:role p)
                          (= team (roles/team-of (:role p))))))
           (map key)
           set)
      #{})))

(defn- role-as-seen-by-teammates
  "What a teammate is shown as. Usually simply their role.

   `:appears-as` in the registry is the exception, and exists for exactly one
   reason: the pack must know the Weißer Werwolf hunts with them without learning
   that he is the one who will turn on them. Reading it here rather than naming
   the role keeps view.cljc free of rules about specific roles."
  [role-id]
  (or (:appears-as (roles/role role-id)) role-id))

(defn- lover-of [state pid]
  (when-let [ls (:lovers state)]
    (when (contains? (set ls) pid)
      (first (disj (set ls) pid)))))

(defn revealed-roles
  "Map of player-id -> role that `pid` is entitled to know.

   This is the ONE function that decides role visibility. Everything else in
   this namespace defers to it, so the rule lives in a single place."
  [state pid]
  (let [game-over? (= :game-over (:phase state))
        me         (get-in state [:players pid])
        dead?      (and me (not (:alive? me)))
        all-roles  #(into {} (keep (fn [[id p]] (when (:role p) [id (:role p)])))
                          (:players state))]
    (cond
      ;; The game is finished: full reveal for everyone.
      game-over? (all-roles)

      ;; The Spielleiter narrates; he cannot do it blind. `some?` because a nil
      ;; pid must never compare equal to the nil narrator of an un-narrated room.
      (and (some? pid) (= pid (e/narrator state))) (all-roles)

      ;; The deal is not final until the night falls: the Spielleiter may still
      ;; move any card. So a player sees his OWN card and nothing it implies — a
      ;; wolf who met the pack during setup and was then made a villager would
      ;; carry the pack's names into the game.
      (= :setup (:phase state)) (if (:role me) {pid (:role me)} {})

      ;; Opt-in ghost view only — see the namespace docstring for why this is
      ;; off by default.
      (and dead? (:reveal-to-dead? state)) (all-roles)

      :else
      (let [own      (when (:role me) {pid (:role me)})
            mates    (into {} (map (fn [id]
                                     [id (role-as-seen-by-teammates
                                          (get-in state [:players id :role]))]))
                           (teammates state pid))
            ;; Roles the player has actively learned (Seherin's inspections).
            learned  (into {} (:knows me {}))
            ;; A lover knows who they are bound to, and thus their role.
            lover    (when-let [l (lover-of state pid)]
                       {l (get-in state [:players l :role])})
            ;; The public log reveals the role of anyone who has died.
            revealed (into {} (keep (fn [entry]
                                      (when (and (= :player/died (:type entry))
                                                 (:role entry))
                                        [(:player entry) (:role entry)]))
                                    (:log state)))]
        (merge own mates learned lover revealed)))))

;; -------------------------------------------------------- public pieces ---

(defn- deck-view
  "What the table knows about the deck. Public, all of it, in every phase.

   At a real table the composition is said out loud before anybody looks at a
   card — „zwei Werwölfe, Seherin, Hexe, der Rest Dorfbewohner“ — and the
   whole game is reasoned from it. Hiding it would make the digital table know
   less than the cardboard one. It is also safe by construction: role COUNTS
   keyed by role, and not one player id in it.

   `:deck-preview` is computed from the DECK, never from the deal. That is the
   load-bearing choice, and it is why the composition can stay up during the
   game: it says what was shuffled, not who holds what now. Built from the deal
   instead, it would publish everything that has moved since — the Spielleiter
   rewriting a card in setup would show up as a Hexe appearing, and the Dieb's
   swap as a wolf leaving the middle, which is his secret and nobody else's.
   The two cards in the middle are listed as such: that a Dieb chooses between
   a wolf and a villager is the rule, not a secret (see `roles/centre-extras`).

   The menu and the problem are the lobby's alone — nothing is chosen after it.
   `:deck-roles` is every special in the order the menu shows it, with the
   registry's limit, so the steppers can stop where the server would and the
   client keeps no copy of the rule."
  [state]
  (let [n     (count (e/participant-ids state))
        lobby? (= :lobby (:phase state))]
    (cond-> {:deck         (:deck state)
             :deck-preview (roles/composition n (:deck state))}
      lobby? (assoc :deck-problem (phases/deck-problem state)
                    :deck-roles (mapv (fn [id] {:role id :max (roles/max-count id)})
                                      roles/special-ids)))))

(defn- public-player
  "The part of a player that everyone may see. No role, ever."
  [p]
  {:id         (:id p)
   :name       (:name p)
   :alive?     (:alive? p)
   :connected? (:connected? p)})

(defn- night-view
  "Night bookkeeping a player may see.

   Werewolves see their own kill vote tally, because they must coordinate.
   The Hexe sees who is currently dying, because that is her whole decision.
   Nobody else sees any of it — except the Spielleiter, who calls the night."
  [state pid]
  (let [role-id (get-in state [:players pid :role])
        alive?  (get-in state [:players pid :alive?])
        cursor  (:night-cursor state)]
    (if (and (some? pid) (= pid (e/narrator state)))
      ;; The Spielleiter sees the whole night as it happens: whom the pack is
      ;; leaning towards, who is dying, whom the Beschützer stood in front of.
      ;; Calling "Hexe, wach auf — diese Person wird sterben" is his job, so he
      ;; has to know who it is. Ids and causes only; roles reach him through
      ;; `revealed-roles` like everyone else's.
      (if (= :night (:phase state))
        (cond-> {:werewolf-votes (get-in state [:night-actions :werewolf-votes] {})
                 :pending-deaths (mapv #(select-keys % [:player :cause])
                                       (:pending-deaths state))}
          (some? (get-in state [:night-actions :protected]))
          (assoc :protected (get-in state [:night-actions :protected])))
        {})
      (cond-> {}
        ;; Wolves coordinate openly among themselves.
        (and alive? (= :werwolf role-id))
        (assoc :werewolf-votes (get-in state [:night-actions :werewolf-votes] {}))

        ;; The Hexe must see the pending victim to decide on the heal.
        (and alive? (= :hexe role-id) (= :hexe cursor)
             (:sees-pending-deaths? (roles/role :hexe)))
        (assoc :pending-deaths (mapv #(select-keys % [:player :cause])
                                     (:pending-deaths state)))))))

;; --------------------------------------------------------------- view ---

(defn view-for
  "Build the redacted state that `pid` is allowed to receive.

   Constructed from scratch — see the namespace docstring. Do not refactor this
   into `(dissoc state ...)`."
  [state pid]
  (let [me       (get-in state [:players pid])
        roles-in (revealed-roles state pid)
        reaction (peek (:reactions state))
        narrator (e/narrator state)
        narrator? (and (some? pid) (= pid narrator))]
    (merge
     ;; ---- the deck: public, see `deck-view` ----
     (deck-view state)
     {;; ---- public ----
     :room-id     (:room-id state)
     :phase       (:phase state)
     :night       (:night state)
     :host        (:host state)
     ;; Who narrates, if anybody. Public by nature: the table can see who is
     ;; not playing, and must know whose seat is the Spielleitung's.
     :narrated?   (boolean (:narrated? state))
     :narrator    narrator
     ;; The host has been gone past his grace period. Public: the whole table is
     ;; the one left waiting, and it is what explains a clock that has come on
     ;; by itself or a button offering his seat. WHEN he left is not published;
     ;; his card already says he is away, and that is all anyone needs.
     :host-absent? (boolean (:host-absent? state))
     ;; Whether this room runs a clock at all, so the lobby switch and the GM
     ;; console can say which way it is set.
     :timer?      (phases/timed? state)
     :deadline    (:deadline state)
     ;; So the countdown can say "PAUSIERT" rather than silently freeze, which
     ;; reads as a broken client.
     :paused?     (boolean (:paused? state))
     ;; Whether joining needs a password — never the hash itself, which stays
     ;; in state for the room layer to check guesses against. Public, so the
     ;; lobby can show the 🔒 and the table knows a newcomer will have to ask.
     :locked?     (contains? state :password-hash)
     :winner      (:winner state)
     :log         (vec (:log state))
     :votes       (or (:votes state) {})
     ;; Whether today ends in a vote, and what a condemnation takes. Rules, so
     ;; the engine says them and the client does not count for itself.
     :trial?      (phases/trial-day? state)
     :votes-needed (phases/votes-needed state)
     ;; Public, and necessarily so: the whole point of the office is that the
     ;; table knows whose vote counts twice. Election ballots in progress are
     ;; NOT published — see :election-votes, which is deliberately absent.
     :mayor       (:mayor state)
     ;; Who has already spoken. Public — seeing that the table is waiting on two
     ;; specific people is most of what makes a timed phase bearable — and safe:
     ;; these are player ids, and no role can be inferred from having decided.
     :ready           (or (:ready state) #{})
     :min-players (:min-players state)
     ;; Published so the lobby can show the real ceiling. Showing only the
     ;; minimum, as "2 / 5", read as a five-player cap.
     :max-players (:max-players state)
     :players     (into {} (map (fn [[id p]] [id (public-player p)])) (:players state))
     ;; Which ROLE is currently being called is public — the human narrator says
     ;; it out loud ("Werwölfe, wacht auf"). Which PLAYER holds it is not.
     :night-cursor (:night-cursor state)

     ;; ---- role knowledge, decided solely by revealed-roles ----
     :known-roles roles-in

     ;; ---- strictly personal ----
     :me          (when me
                    {:id        pid
                     :name      (:name me)
                     :role      (:role me)
                     :alive?    (:alive? me)
                     :locale    (:locale me)
                     :resources (:resources me)
                     :knows     (:knows me {})
                     :lover     (lover-of state pid)
                     ;; Whether lovers exist at all is public; who they are is not.
                     :in-love?  (boolean (lover-of state pid))
                     :narrator? narrator?})

     :lovers-exist? (boolean (seq (:lovers state)))

     ;; ---- affordances, so the client need not re-derive the rules ----
     :actions     (phases/legal-actions state pid)
     :targets     (into {} (map (fn [a] [a (phases/legal-targets state pid a)]))
                        (phases/legal-actions state pid))
     ;; Non-player options: the phases and roles a GM override may name. Sent
     ;; for the same reason as :targets — so the client offers what the engine
     ;; permits and invents nothing — and safe to send because a menu of every
     ;; role in the registry says nothing about who holds one.
     :choices     (into {} (keep (fn [a] (when-let [cs (seq (phases/legal-choices state a))]
                                           [a (vec cs)])))
                        (phases/legal-actions state pid))
     ;; How many players each offered action wants the client to pick. Sent so
     ;; the UI can handle Amor's pair without hard-coding a rule about Amor.
     :arity       (into {} (keep (fn [a] (when-let [n (phases/target-arity a)] [a n])))
                        (phases/legal-actions state pid))

     ;; Only surfaced when this player is the one who owes a reaction.
     :awaiting-reaction (when (and reaction (= pid (:player reaction)))
                          (:type reaction))

     ;; ---- night bookkeeping, narrowly scoped ----
     :night-info  (night-view state pid)

     ;; ---- the Spielleiter's notes, built for him and nobody else ----
     ;; Who Amor bound together and which two cards lie in the middle for the
     ;; Dieb: things the narrator has to know to call the night, that no
     ;; player's own knowledge would ever hand him.
     :narrator-info (when narrator?
                      {:lovers (vec (sort (:lovers state)))
                       :centre (vec (:centre state []))
                       ;; Who the game is still waiting on. At night that names
                       ;; the holders of the role being called, which is why it
                       ;; lives here and nowhere public.
                       :owing  (phases/owing state)
                       ;; Why his deal in :setup cannot begin. His alone: "no
                       ;; werewolf" said to the table would be a role leak.
                       :deal-problem (phases/deal-problem state)})})))

;; ------------------------------------------------------- leak detector ---

(defn foreign-roles-in-view
  "Every (player-id, role) pair present anywhere in `view` that refers to a
   player OTHER than `pid`.

   Walks the whole view structure rather than checking known keys, so a leak
   introduced through a new field is still caught."
  [view pid]
  (let [found (atom #{})
        role? #(and (keyword? %) (contains? roles/all-ids %))
        note! (fn [owner r]
                (when (and (int? owner) (not= owner pid) (role? r))
                  (swap! found conj [owner r])))]
    (letfn [(walk [x]
              (cond
                (map? x)
                (do
                  ;; {player-id role-id}
                  (doseq [[k v] x] (note! k v))
                  ;; {:player id :role r} / {:id id :role r} / {:target id :role r}
                  ;;
                  ;; :target is checked because a GM override's :detail is shaped
                  ;; that way, and that detail is published to the log. Naming
                  ;; both the player and the role a GM just gave them would
                  ;; broadcast a card to the whole table — see
                  ;; `handle :gm/reassign-role`, which omits the role for exactly
                  ;; this reason. Without :target here the detector would have
                  ;; watched it happen and said nothing.
                  (when (contains? x :role)
                    (note! (or (:id x) (:player x) (:target x)) (:role x)))
                  (doseq [v (vals x)] (walk v)))

                ;; [player-id role-id] pairs — a leak shaped as a vector of
                ;; tuples would otherwise slip past the map checks above.
                (and (sequential? x) (= 2 (count x)))
                (do (note! (first x) (second x))
                    (doseq [v x] (walk v)))

                (coll? x) (doseq [v x] (walk v))
                :else nil))]
      (walk view))
    @found))

(defn redaction-leaks?
  "Does `pid`'s view expose a role they are NOT entitled to know?
   Returns the offending pairs, or nil when the view is clean.

   This is the assertion the property test drives over generated games. It must
   never be allowed to fail.

   The 3-arity takes an explicit `view`, which lets the server audit what it
   actually put on a socket rather than re-deriving what it should have put
   there. A check that only ever inspects `view-for`'s own output cannot catch a
   transport layer that sends something else."
  ([state pid] (redaction-leaks? state pid (view-for state pid)))
  ([state pid view]
   (let [permitted (set (revealed-roles state pid))
         actual    (foreign-roles-in-view view pid)
         leaked    (into #{} (remove permitted) actual)]
     (when (seq leaked) leaked))))
