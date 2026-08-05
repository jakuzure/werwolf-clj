(ns werwolf.token
  "Reconnect tokens: HS256-signed proof of 'I am player N in room R'.

   A player's identity cannot live in a WebSocket connection, because the
   connection is the thing that keeps breaking — a closed laptop lid, a train
   tunnel, a server restart. So identity is a bearer token the client keeps in
   localStorage and presents on every `:client/hello`. The socket is then bound
   to the player it names.

   Signed rather than stored: the server holds no session table, so a restart
   invalidates nothing. The log rehydrates the game, the token rehydrates the
   player, and the two meet again with no extra bookkeeping.

   Three properties are load-bearing:

   - **Bound to a room.** A token for room ABC presented to room XYZ is
     rejected even though its signature is perfectly valid. Without this check
     a player could carry a seat number into a game they were never dealt into.
   - **Expiring.** Tokens outlive a disconnect, not a friendship. The TTL is a
     config knob (default 12h — long enough that nobody is logged out
     mid-game).
   - **Never trusted for authorisation.** The token says *who* you are, not
     *what you may do*. Every capability question is answered by the engine
     from game state (`legal-actions`, `host-actions`). `:host?` rides along
     purely as a hint for the client's first paint; the server re-derives it.

   Verification returns `{:error ...}` rather than throwing. A bad token is an
   ordinary thing to receive over a public socket, not an exceptional one, and
   an exception escaping the read loop would take the connection down."
  (:require [buddy.sign.jwt :as jwt])
  (:import (clojure.lang ExceptionInfo)))

(def default-secret
  "The value shipped in config.edn. Present so the server can refuse to run
   with it in production rather than silently signing forgeable tokens."
  "dev-only-insecure-secret-change-me")

(defn insecure-secret?
  [{:keys [secret]}]
  (or (nil? secret)
      (= default-secret secret)
      (< (count (str secret)) 16)))

(defn- ->secs [ms] (quot ms 1000))

(defn issue
  "Mint a token for `player-id` in `room-id`, valid for the configured TTL.

   `now-ms` is passed in rather than read from a clock: this namespace stays
   testable without sleeping, and the caller already has the timestamp it is
   writing into the event log."
  [{:keys [secret ttl-mins] :as _cfg} {:keys [room-id player-id host? now-ms]}]
  (let [iat (->secs now-ms)]
    (jwt/sign {:room room-id
               :pid  player-id
               :host (boolean host?)
               :iat  iat
               :exp  (+ iat (* 60 (or ttl-mins 720)))}
              secret
              {:alg :hs256})))

(defn verify
  "Check `token` and return `{:room-id .. :player-id .. :host? ..}`, or
   `{:error :malformed | :bad-signature | :expired | :wrong-room}`.

   `expect-room` is required — a caller that does not know which room it is
   admitting the token to cannot meaningfully verify it."
  [{:keys [secret] :as _cfg} token {:keys [expect-room now-ms]}]
  (if-not (string? token)
    {:error :malformed}
    (let [claims (try
                   (jwt/unsign token secret {:alg :hs256 :now (->secs now-ms)})
                   (catch ExceptionInfo e
                     ;; buddy reports :cause :exp for expiry and :signature (or
                     ;; a decode failure) for everything else.
                     {::error (case (:cause (ex-data e))
                                :exp :expired
                                :signature :bad-signature
                                :bad-signature)})
                   (catch Exception _
                     {::error :malformed}))]
      (cond
        (::error claims) {:error (::error claims)}

        (not (and (string? (:room claims)) (int? (:pid claims))))
        {:error :malformed}

        (not= expect-room (:room claims))
        {:error :wrong-room}

        :else {:room-id   (:room claims)
               :player-id (:pid claims)
               :host?     (boolean (:host claims))}))))
