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
   Only roles flagged :knows-teammates? (i.e. werewolves) learn each other."
  [state pid]
  (let [role-id (get-in state [:players pid :role])]
    (if (and role-id (:knows-teammates? (roles/role role-id)))
      (->> (:players state)
           (filter (fn [[other p]]
                     (and (not= other pid)
                          (= role-id (:role p)))))
           (map key)
           set)
      #{})))

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

      ;; Opt-in ghost view only — see the namespace docstring for why this is
      ;; off by default.
      (and dead? (:reveal-to-dead? state)) (all-roles)

      :else
      (let [own      (when (:role me) {pid (:role me)})
            mates    (into {} (map (fn [id] [id (get-in state [:players id :role])]))
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
   Nobody else sees any of it."
  [state pid]
  (let [role-id (get-in state [:players pid :role])
        alive?  (get-in state [:players pid :alive?])
        cursor  (:night-cursor state)]
    (cond-> {}
      ;; Wolves coordinate openly among themselves.
      (and alive? (= :werwolf role-id))
      (assoc :werewolf-votes (get-in state [:night-actions :werewolf-votes] {}))

      ;; The Hexe must see the pending victim to decide on the heal.
      (and alive? (= :hexe role-id) (= :hexe cursor)
           (:sees-pending-deaths? (roles/role :hexe)))
      (assoc :pending-deaths (mapv #(select-keys % [:player :cause])
                                   (:pending-deaths state))))))

;; --------------------------------------------------------------- view ---

(defn view-for
  "Build the redacted state that `pid` is allowed to receive.

   Constructed from scratch — see the namespace docstring. Do not refactor this
   into `(dissoc state ...)`."
  [state pid]
  (let [me       (get-in state [:players pid])
        roles-in (revealed-roles state pid)
        reaction (peek (:reactions state))]
    {;; ---- public ----
     :room-id     (:room-id state)
     :phase       (:phase state)
     :night       (:night state)
     :host        (:host state)
     :deadline    (:deadline state)
     :winner      (:winner state)
     :log         (vec (:log state))
     :votes       (or (:votes state) {})
     :nominations (or (:nominations state) #{})
     :min-players (:min-players state)
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
                     :in-love?  (boolean (lover-of state pid))})

     :lovers-exist? (boolean (seq (:lovers state)))

     ;; ---- affordances, so the client need not re-derive the rules ----
     :actions     (phases/legal-actions state pid)
     :targets     (into {} (map (fn [a] [a (phases/legal-targets state pid a)]))
                        (phases/legal-actions state pid))

     ;; Only surfaced when this player is the one who owes a reaction.
     :awaiting-reaction (when (and reaction (= pid (:player reaction)))
                          (:type reaction))

     ;; ---- night bookkeeping, narrowly scoped ----
     :night-info  (night-view state pid)}))

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
                  ;; {:player id :role r} / {:id id :role r}
                  (when (contains? x :role)
                    (note! (or (:id x) (:player x)) (:role x)))
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
   never be allowed to fail."
  [state pid]
  (let [view      (view-for state pid)
        permitted (set (revealed-roles state pid))
        actual    (foreign-roles-in-view view pid)
        leaked    (into #{} (remove permitted) actual)]
    (when (seq leaked) leaked)))
