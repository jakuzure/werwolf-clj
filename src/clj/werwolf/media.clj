(ns werwolf.media
  "The seam between the game and whatever carries voice.

   v1 shipped no audio at all, and this namespace existed in the plan precisely
   so that adding it later would not be a rewrite. `NoopMedia` is still the
   default: a room with no media provider is a text game, which is exactly what
   it was before.

   ## One method, on purpose

   The plan sketched three — `join-token`, `set-can-publish`, `move-to-room` —
   because it assumed the permission table: mics revoked at night, wolves moved
   to a room of their own, the dead demoted to listeners. That table is NOT being
   enforced. The decision was taken deliberately: every player can talk, always,
   and the game's secrecy lives where it already lived — in `view-for` and in
   `room/chat-audience`. Audio is a group call held next to the game.

   So there is one method, because there is one caller. Shipping
   `set-can-publish` with nobody calling it would be worse than not having it:
   an unused security control reads as an enforced one, and the next person to
   touch this would reasonably assume the night is muted when it is not.

   Adding the table later is additive, which was the point of the seam. It needs
   a method here, a pure state->permissions function, and a reconciler on
   `room/registry`'s `:after-commit` hook — the same slot the clock already uses.
   Two things learned while probing LiveKit 1.13.5 that whoever does it will want:

   * `MoveParticipant` and `UpdateSubscriptions` both exist on the Twirp
     `RoomService`, so both designs are available server-side.
   * Prefer subscriptions to moving. If the wolves are moved to their own room,
     a villager's client can see they are ABSENT from the main one, which
     identifies them — the leak is in the participant roster, not in the audio.
     Controlling who SUBSCRIBES to whom keeps everyone in one room with nothing
     to observe.

   ## What this is not

   Not authorisation. Like `werwolf.token`, a media credential says who you are
   and which room you may enter. It grants nothing in the game: every capability
   question is still answered by the engine from state."
  (:require [clojure.string :as str]))

(defprotocol MediaProvider
  (join
    [this {:keys [room identity display-name now-ms]}]
    "Credentials for one participant, as `{:url .. :token .. :room ..}`, or nil
     when this provider carries no audio.

     `identity` must be STABLE for a player across reconnects: an SFU replaces a
     participant that rejoins under the same identity, and a player who reloads
     twice should not leave two ghosts in the room holding a microphone."))

(defrecord NoopMedia []
  MediaProvider
  (join [_ _] nil))

(defn noop [] (->NoopMedia))

(def default-room-prefix "werwolf-")

(defn room-name
  "The media room for a game room.

   Prefixed rather than bare, because an SFU is not necessarily dedicated to this
   game: `ABCD` is a plausible name for somebody else's room, `werwolf-ABCD` is
   not. Lower-cased for the same reason a URL is.

   The prefix is configurable for the same reason one SFU is shared: a PR
   preview joins production's LiveKit, and its `ABCD` must not be production's."
  ([room-id] (room-name default-room-prefix room-id))
  ([prefix room-id]
   (str prefix (str/lower-case (str room-id)))))

(defn identity-for
  "A participant identity that is stable per seat and unique per room.

   Seat number rather than display name: names are not unique at a werwolf table
   — two Annas is a normal evening — and an SFU treats a repeated identity as the
   same participant reconnecting, so two Annas would evict each other."
  ([room-id player-id] (identity-for default-room-prefix room-id player-id))
  ([prefix room-id player-id]
   (str (room-name prefix room-id) "-p" player-id)))

(defn credentials
  "What a seated player needs in order to join the call, or nil if audio is off.

   Takes the provider rather than reaching for a global, so a test can hand in a
   recording fake and the protocol layer needs no knowledge of LiveKit at all.
   A provider may carry a `:room-prefix`; without one, rooms are production's."
  [provider {:keys [room-id player-id display-name now-ms]}]
  (let [prefix (or (:room-prefix provider) default-room-prefix)]
    (join provider {:room         (room-name prefix room-id)
                    :identity     (identity-for prefix room-id player-id)
                    :display-name display-name
                    :now-ms       now-ms})))
