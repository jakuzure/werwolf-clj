(ns werwolf.livekit
  "A `MediaProvider` backed by LiveKit.

   All this does is mint an access token. LiveKit's server API is not called at
   all, because nothing needs it: every player may talk, so there are no
   permissions to change after the fact and no participants to move. See
   `werwolf.media` for that decision and for what adding the permission table
   would involve.

   A LiveKit access token is an ordinary HS256 JWT whose `video` claim carries the
   grant, so `buddy-sign` — already a dependency, already minting reconnect
   tokens — is the whole implementation. No LiveKit SDK, which is worth noting
   because the Java one pulls in a protobuf stack to do this same thing.

   Claim shapes are LiveKit's, not ours, and they are camelCase. Keywords are
   used verbatim as JSON keys, so `:canPublish` is deliberate and
   `:can-publish` would silently grant nothing — the server would see an unknown
   field and default it to false."
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [werwolf.media :as media]))

(def ^:const default-ttl-mins
  "How long a join token stays valid.

   Only needs to outlive the moment of joining — the SFU checks it at connect
   time and the session then lives on its own — but a reconnect mints a fresh one
   anyway, so this is generous rather than tight. It is NOT the reconnect token's
   TTL and should not be confused with it: this one grants a microphone, that one
   grants a seat."
  360)

(defn- ->secs [ms] (quot ms 1000))

(defn access-token
  "Mint a LiveKit join token. Pure given `now-ms`, so the claims are testable
   without a clock or a server."
  [{:keys [api-key api-secret ttl-mins]} {:keys [room identity display-name now-ms]}]
  (let [iat (->secs now-ms)]
    (jwt/sign {:iss api-key
               ;; `sub` IS the participant identity as far as LiveKit is
               ;; concerned; there is no separate identity claim.
               :sub identity
               :name (or display-name identity)
               :iat iat
               :nbf iat
               :exp (+ iat (* 60 (or ttl-mins default-ttl-mins)))
               :video {:room room
                       :roomJoin true
                       :canPublish true
                       :canSubscribe true
                       ;; Data messages travel on our own WebSocket, which is
                       ;; where game state belongs — the server is the authority
                       ;; and a peer-to-peer data channel routes around it.
                       :canPublishData false}}
              api-secret
              {:alg :hs256})))

(defrecord LiveKitMedia [config]
  media/MediaProvider
  (join [_ params]
    {:url   (:url config)
     :room  (:room params)
     :token (access-token config params)}))

(defn configured?
  "Is there enough here to mint a usable token? A half-filled config is the
   likeliest failure — `MEDIA_PROVIDER=livekit` set and the keys forgotten — and
   it must not produce tokens that an SFU will silently reject."
  [{:keys [url api-key api-secret]}]
  (every? #(and (string? %) (not (str/blank? %))) [url api-key api-secret]))

(defn provider
  "Build a provider from the `:media` section of config.

   Falls back to `NoopMedia` — a text game, which is what v1 was — rather than
   throwing, and the caller logs the reason. Refusing to boot because voice is
   misconfigured would take the whole game down for a feature it can play
   without."
  [{:keys [provider] :as cfg}]
  (cond
    (not= :livekit provider) {:provider (media/noop) :reason :disabled}
    (not (configured? cfg))  {:provider (media/noop) :reason :incomplete-config}
    :else                    {:provider (->LiveKitMedia cfg) :reason :ok}))
