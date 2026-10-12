(ns werwolf.protocol
  "The server side of the wire protocol: one function per inbound message.

   This is the only place that turns a socket into a player. It does three
   things and delegates everything else:

   1. **Validate.** Every message is checked against `werwolf.proto` before it
      reaches a room. A frame that does not typecheck never touches game state.
   2. **Authenticate.** A socket's identity comes from a signed token or from
      the create/join that seated it — never from a field in the payload.
   3. **Answer the one socket.** State broadcasts belong to the room layer;
      what happens here is the reply to the sender.

   Deliberately free of transport details: `send!` arrives in `deps`, and the
   socket is an opaque value. That is what lets the whole protocol be tested
   with atoms for sockets, asserting exactly what a client would have received."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [werwolf.media :as media]
            [werwolf.proto :as proto]
            [werwolf.room :as room]
            [werwolf.token :as token]))

(defn- reply! [{:keys [send!]} sock msg]
  (send! sock msg))

(defn- error! [deps sock code detail]
  (reply! deps sock (cond-> {:type :server/error :code code}
                      (seq detail) (assoc :detail detail))))

(defn- issue-token [{:keys [tokens]} room-id player-id host? now-ms]
  (token/issue tokens {:room-id room-id :player-id player-id
                       :host? host? :now-ms now-ms}))

(defn- welcome!
  "Confirm a seat and hand over the reconnect token.

   Sent BEFORE the socket is attached, so the client knows which player it is
   before the first view arrives and never has to guess from the payload."
  [deps sock room-id player-id]
  (let [host? (= player-id (:host (room/state-of (:rooms deps) room-id)))]
    (reply! deps sock {:type      :server/welcome
                       :protocol  proto/protocol-version
                       :room-id   room-id
                       :player-id player-id
                       :host?     host?
                       :token     (issue-token deps room-id player-id host?
                                               (System/currentTimeMillis))})))

;; ---------------------------------------------------------------- dispatch ---

(defmulti ^:private handle
  "Handle one validated message. Dispatches on :type."
  (fn [_deps _sock msg] (:type msg)))

(defmethod handle :default
  [deps sock msg]
  (error! deps sock :unknown-message {:type (:type msg)}))

(defmethod handle :client/ping
  [deps sock _]
  ;; Application-level, on top of the protocol's own pings: a proxy that idles
  ;; out an otherwise silent connection is the usual reason a lobby goes quiet.
  (reply! deps sock {:type :server/pong}))

(defmethod handle :client/rooms
  ;; The only question an unseated socket may ask. Answered to the asker alone
  ;; and never pushed, so a room list cannot become a side channel that tells a
  ;; seated player something about a game they are not in.
  [{:keys [rooms] :as deps} sock _]
  (reply! deps sock {:type :server/rooms :rooms (room/open-rooms rooms)}))

(defmethod handle :client/create
  [{:keys [rooms] :as deps} sock {:keys [name locale]}]
  (let [res (room/create! rooms {:name name :locale (or locale :de)})]
    (if-let [code (:error res)]
      (error! deps sock code (:detail res))
      (let [{:keys [room-id player-id]} res]
        (welcome! deps sock room-id player-id)
        (room/attach! rooms room-id player-id sock)))))

(defmethod handle :client/join
  [{:keys [rooms] :as deps} sock {:keys [room-id name locale]}]
  (let [res (room/join! rooms room-id {:name name :locale (or locale :de)})]
    (if-let [code (:error res)]
      (error! deps sock code (:detail res))
      (let [pid (:player-id res)]
        (welcome! deps sock room-id pid)
        (room/attach! rooms room-id pid sock)))))

(defmethod handle :client/resume
  [{:keys [rooms tokens] :as deps} sock {:keys [room-id token]}]
  (let [claim (token/verify tokens token {:expect-room room-id
                                          :now-ms (System/currentTimeMillis)})]
    (cond
      (:error claim)
      (error! deps sock (:error claim) {})

      ;; The token is genuine but the game it names is gone — finished and
      ;; swept, or from a previous life of this database.
      (not (room/seated? rooms room-id (:player-id claim)))
      (error! deps sock :seat-gone {:room-id room-id})

      :else
      (let [pid (:player-id claim)]
        ;; A fresh token, so a long game does not end with everyone holding an
        ;; expired one at the moment their wifi drops.
        (welcome! deps sock room-id pid)
        (room/attach! rooms room-id pid sock)))))

(defmethod handle :client/command
  [{:keys [rooms] :as deps} sock {:keys [cmd]}]
  (if-let [[room-id pid] (room/whereis rooms sock)]
    (let [res (room/submit! rooms room-id pid cmd)]
      (when-let [code (:error res)]
        ;; Rejections go to the offender alone. Everyone else learns nothing —
        ;; an illegal action is often an accidental confession of a role.
        (error! deps sock code (:detail res))))
    (error! deps sock :not-in-room {})))

(defmethod handle :client/chat
  [{:keys [rooms] :as deps} sock {:keys [text]}]
  (if-let [[room-id pid] (room/whereis rooms sock)]
    (let [res (room/chat! rooms room-id pid text)]
      (when-let [code (:error res)]
        (error! deps sock code (:detail res))))
    (error! deps sock :not-in-room {})))

(defmethod handle :client/media
  ;; Hand this socket the credentials for its own seat's call, or say that audio
  ;; is off. Answered to the asker alone and never broadcast: a join token is a
  ;; bearer credential for a microphone, so it belongs to exactly one socket.
  ;;
  ;; The message carries no room and no identity — both are read from the
  ;; registry's socket index — so there is nothing a client could put in it to
  ;; obtain a token for a game it is not in.
  ;;
  ;; `:enabled? false` rather than an error when there is no provider: a text-only
  ;; server is a supported configuration, not a failure, and a client that
  ;; treated it as an error would show a banner on every correctly-running v1.
  [{:keys [rooms media] :as deps} sock _]
  (if-let [[room-id pid] (room/whereis rooms sock)]
    (let [nm (get-in (room/state-of rooms room-id) [:players pid :name])
          cr (media/credentials (or media (media/noop))
                                {:room-id room-id :player-id pid
                                 :display-name nm
                                 :now-ms (System/currentTimeMillis)})]
      (reply! deps sock (if cr
                          (assoc cr :type :server/media :enabled? true)
                          {:type :server/media :enabled? false})))
    (error! deps sock :not-in-room {})))

;; ------------------------------------------------------------- entry points ---

(defn on-open
  "Greet the socket. The client needs the protocol version before it decides
   whether it can talk to this server at all."
  [deps sock]
  (reply! deps sock {:type :server/hello :protocol proto/protocol-version}))

(defn- normalise
  "Nobody types a room code the way it was printed. Done BEFORE validation, so
   the schema can insist on the canonical form without punishing a human."
  [msg]
  (cond-> msg
    (string? (:room-id msg)) (update :room-id str/upper-case)))

(defn- asking-again?
  "Is a seated socket's create or join only asking for the seat it already has?

   A double tap does this, and so can a join that waited in the client's outbox
   through a reconnect, behind the resume of the very seat it asks for: the first
   copy seats the player and the second arrives at a socket already bound. Refusing it put an :already-in-room banner
   over the room the player had just walked into. A create names no room, so any
   create from a seated socket is the same tap twice; a join is a repeat only
   when it names the room this socket is already in."
  [msg room-id]
  (case (:type msg)
    :client/create true
    :client/join   (= room-id (:room-id msg))
    false))

(defn dispatch
  "Handle one decoded message. Total: nil, junk and hostile input all produce a
   reply rather than an exception, because this runs on the socket's read loop
   and a throw there takes the connection with it.

   A socket that is already seated may not create or join again. Without that
   check one connection could seat itself repeatedly, and every seat but the
   last would be a ghost nothing can ever reconnect to.

   What it gets instead depends on what it asked. The same request twice is
   answered with the seat it already has — welcomed and repainted exactly as a
   resume would be, and no new seat — because the answer to a repeat is the
   answer to the first. Asking for a DIFFERENT room while seated is a real
   conflict, and is still refused."
  [{:keys [rooms] :as deps} sock raw-msg]
  (try
    (let [msg (when (map? raw-msg) (normalise raw-msg))
          [room-id pid] (room/whereis rooms sock)]
      (cond
        (not (proto/valid-client-message? msg))
        (error! deps sock :malformed {:errors (proto/explain-client-message msg)})

        (and room-id (#{:client/create :client/join :client/resume} (:type msg)))
        (if (asking-again? msg room-id)
          (do (welcome! deps sock room-id pid)
              (room/attach! rooms room-id pid sock))
          (error! deps sock :already-in-room {}))

        :else (handle deps sock msg)))
    (catch Throwable t
      ;; A rule bug must cost one client its message, not the whole room its
      ;; server. `locking` releases the room's lock on the way out.
      (log/error t "protocol failure handling" (:type raw-msg))
      (error! deps sock :internal-error {}))))

(defn on-close
  "The socket is gone. The room decides what that means for the player."
  [{:keys [rooms]} sock]
  (room/detach! rooms sock))
