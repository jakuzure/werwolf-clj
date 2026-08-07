(ns werwolf.proto
  "The wire contract, shared by server and client.

   Two properties this file exists to protect:

   **Nothing prose ever crosses the wire.** Messages carry structured facts —
   `{:type :player/died :player 3 :cause :werwolf}` — and each client renders
   them through its own dictionary. That is the entire reason a German and an
   English player can sit in the same room, and it only stays true if the
   protocol never grows a `:message` string.

   **Client input is validated before it reaches the engine.** Every inbound
   message is checked against a schema here, so a hand-crafted frame is rejected
   at the door rather than probed against `handle` for interesting failures. The
   command whitelist below is the load-bearing part: it is a list of what a
   player may ask for, not a filter of what they may not.

   Being `.cljc` is the point — the client validates outgoing messages against
   the same schemas, so a protocol mistake is a compile-time-ish error during
   development rather than a mystery at runtime."
  (:require [malli.core :as m]))

(def protocol-version 1)

;; --------------------------------------------------------------- vocabulary ---

(def command-types
  "Everything a player may ask the engine to do, as a WHITELIST.

   Deliberately enumerated rather than derived from the engine's `handle`
   multimethod: what is reachable from a socket is a protocol decision, and
   adding an engine command should not silently expose it to the network."
  #{:leave :set-locale :start-game
    :amor-link :seherin-inspect :werewolf-vote
    :hexe-heal :hexe-poison :hexe-pass
    :jaeger-shoot :jaeger-pass
    :ready :nominate :nominate-pass :vote :vote-abstain :advance-phase
    :gm/force-kill :gm/revive :gm/skip-role :gm/set-phase :gm/reassign-role
    :gm/pause :gm/resume :gm/add-time})

(def choice-key
  "Which command key carries an action's NON-PLAYER choice.

   `:target` always means a player id, everywhere in the protocol. Three GM
   overrides point at something that is not a player — a phase, a role — and the
   client has to know which key to put it under. That is wire vocabulary rather
   than a game rule, so it belongs here and not in the UI: a client that hard-
   coded it would have to be edited every time an override learns a new noun.

   Actions absent from this map take no choice. An action may take BOTH a target
   and a choice; `:gm/reassign-role` names a player and the role to give them."
  {:gm/skip-role     :role
   :gm/set-phase     :phase
   :gm/reassign-role :role})

(def client-message-types
  #{:client/create :client/join :client/resume :client/command :client/chat
    :client/ping :client/rooms :client/media})

(def server-message-types
  #{:server/hello :server/welcome :server/error :server/pong :server/rooms
    :server/media
    :room/view :room/chat :room/chat-history})

;; ------------------------------------------------------------------ schemas ---

(def Locale [:enum :de :en])

(def PlayerName
  ;; Bounded because it is rendered in everyone else's UI, and trimmed by the
  ;; engine. 24 is long enough for a real name and short enough not to be a
  ;; layout attack.
  [:string {:min 1 :max 24}])

(def RoomId
  ;; Case-sensitive on the wire; the transport upper-cases before it gets here,
  ;; because nobody types a room code the way it was printed.
  [:re #"^[A-Z2-9]{4}$"])

(def Command
  "A player's intent. Closed, and notably WITHOUT :player.

   The server overwrites the actor with the authenticated seat anyway, so
   accepting the key would mean quietly ignoring something the client believes
   it said. Rejecting it is the honest answer."
  [:map {:closed true}
   [:type (into [:enum] (sort-by str command-types))]
   [:target {:optional true} :int]
   ;; Amor points at two players at once. These were missing once, and because
   ;; the map is closed that did not degrade Amor — it made the role unplayable,
   ;; with every link rejected as malformed. See `commands-are-expressible` in
   ;; the protocol tests, which now derives its cases from the simulation rather
   ;; than from anyone's memory of which keys exist.
   [:a      {:optional true} :int]
   [:b      {:optional true} :int]
   [:locale {:optional true} Locale]
   [:role   {:optional true} :keyword]
   [:phase  {:optional true} :keyword]
   ;; Seconds for :gm/add-time. Bounded here and clamped again in the handler:
   ;; the schema stops nonsense, the handler stops a slip.
   [:secs   {:optional true} [:int {:min 5 :max 600}]]])

(def ClientMessage
  [:multi {:dispatch :type}
   [:client/create
    [:map {:closed true}
     [:type [:= :client/create]]
     [:name PlayerName]
     [:locale {:optional true} Locale]]]

   [:client/join
    [:map {:closed true}
     [:type [:= :client/join]]
     [:room-id RoomId]
     [:name PlayerName]
     [:locale {:optional true} Locale]]]

   ;; The reconnect path: a token names the seat, the room names the game.
   [:client/resume
    [:map {:closed true}
     [:type [:= :client/resume]]
     [:room-id RoomId]
     [:token [:string {:min 1 :max 4096}]]]]

   [:client/command
    [:map {:closed true}
     [:type [:= :client/command]]
     [:cmd Command]]]

   [:client/chat
    [:map {:closed true}
     [:type [:= :client/chat]]
     [:text [:string {:min 1 :max 2000}]]]]

   [:client/ping
    [:map {:closed true}
     [:type [:= :client/ping]]]]

   ;; "What can I join?" — answered with the rooms still in their lobby, so
   ;; nobody has to read a code off someone else's screen. Takes no arguments
   ;; and needs no seat: it is the one thing an unseated socket may ask for.
   [:client/rooms
    [:map {:closed true}
     [:type [:= :client/rooms]]]]

   ;; "Where do I join the call?" — answered with a short-lived SFU token for
   ;; THIS socket's seat, or with audio-disabled when there is no provider.
   ;;
   ;; Takes no arguments, and that is the security property: a client cannot ask
   ;; for a token naming a room or a participant, so it cannot mint itself a
   ;; microphone in somebody else's game. The server reads the seat from its own
   ;; socket index, the same way :client/chat learns who is speaking.
   [:client/media
    [:map {:closed true}
     [:type [:= :client/media]]]]])

(def ^:private client-validator (m/validator ClientMessage))
(def ^:private client-explainer (m/explainer ClientMessage))

(defn valid-client-message? [msg]
  (and (map? msg) (client-validator msg)))

(defn explain-client-message
  "Why a message was rejected. Sent back to the client, so it must not echo the
   payload — a token could be in there."
  [msg]
  (when (map? msg)
    (some-> (client-explainer msg)
            :errors
            (->> (mapv (fn [e] {:in (vec (:in e)) :schema (pr-str (m/form (:schema e)))}))))))
