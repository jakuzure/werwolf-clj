(ns werwolf.media-test
  "The media seam and the LiveKit token.

   Audio is a plain group call: every player may talk, always. So there is no
   permission table to test — what IS worth testing is that a token grants
   exactly the room it should, that a half-configured provider degrades to
   silence rather than to broken audio, and that identities cannot collide."
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [werwolf.livekit :as livekit]
            [werwolf.media :as media]))

(def cfg {:provider :livekit
          :url "ws://localhost:7880"
          :api-key "devkey"
          :api-secret "secret"})

(def now 1786000000000)

(defn- claims [token]
  (jwt/unsign token (:api-secret cfg) {:alg :hs256 :now (quot now 1000)}))

;; ------------------------------------------------------------------- naming ---

(deftest a-media-room-is-namespaced-to-this-game
  (testing "an SFU is not necessarily ours alone, and ABCD is a plausible name
            for somebody else's room"
    (is (= "werwolf-abcd" (media/room-name "ABCD")))))

(deftest identities-are-per-seat-not-per-name
  (testing "two players called Anna is a normal evening, and an SFU treats a
            repeated identity as the same participant reconnecting — so
            name-based identities would have them evicting each other"
    (is (not= (media/identity-for "ABCD" 0) (media/identity-for "ABCD" 1))))
  (testing "and the same seat in two rooms is two participants"
    (is (not= (media/identity-for "ABCD" 0) (media/identity-for "WXYZ" 0))))
  (testing "but a seat's identity is STABLE, or a reload leaves a ghost holding
            a microphone"
    (is (= (media/identity-for "ABCD" 3) (media/identity-for "ABCD" 3)))))

;; -------------------------------------------------------------------- noop ---

(deftest without-a-provider-there-is-no-audio-and-that-is-not-an-error
  (is (nil? (media/credentials (media/noop) {:room-id "ABCD" :player-id 0
                                             :now-ms now}))))

(deftest a-half-configured-livekit-degrades-to-silence
  (testing "MEDIA_PROVIDER=livekit with the keys forgotten is the likeliest
            mistake, and it must not mint tokens an SFU will reject — a game that
            plays without audio beats one that appears to have it"
    (doseq [missing [:url :api-key :api-secret]]
      (let [{:keys [provider reason]} (livekit/provider (assoc cfg missing ""))]
        (is (= :incomplete-config reason) (str "missing " missing))
        (is (nil? (media/credentials provider {:room-id "ABCD" :player-id 0
                                               :now-ms now}))))))
  (testing "and an absent provider is simply disabled, not misconfigured"
    (is (= :disabled (:reason (livekit/provider {:provider :noop}))))))

;; ------------------------------------------------------------------- tokens ---

(deftest a-join-token-grants-one-room-and-a-microphone
  (let [{:keys [provider reason]} (livekit/provider cfg)
        cr (media/credentials provider {:room-id "ABCD" :player-id 2
                                        :display-name "Anna" :now-ms now})
        c  (claims (:token cr))]
    (is (= :ok reason))
    (is (= "ws://localhost:7880" (:url cr)))
    (is (= "werwolf-abcd" (:room cr)))

    (testing "the grant names exactly this room"
      (is (= "werwolf-abcd" (get-in c [:video :room])))
      (is (true? (get-in c [:video :roomJoin]))))

    (testing "everyone may talk and everyone may listen — the decision this
              whole feature was scoped around"
      (is (true? (get-in c [:video :canPublish])))
      (is (true? (get-in c [:video :canSubscribe]))))

    (testing "but not publish data: game state travels on our own socket, where
              the server is the authority, and a peer-to-peer data channel would
              route around it"
      (is (false? (get-in c [:video :canPublishData]))))

    (testing "identity is the seat, and the display name is only a label"
      (is (= "werwolf-abcd-p2" (:sub c)))
      (is (= "Anna" (:name c))))

    (testing "signed by the api key as issuer, which is how LiveKit selects the
              secret to verify with"
      (is (= "devkey" (:iss c))))

    (testing "and it expires"
      (is (> (:exp c) (:iat c))))))

(deftest a-room-prefix-keeps-preview-rooms-apart
  (testing "a PR preview shares production's LiveKit, so its rooms must not be
            able to land in production's: ABCD in pr-42 is not ABCD in prod"
    (let [{:keys [provider]} (livekit/provider (assoc cfg :room-prefix "pr-42-"))
          cr (media/credentials provider {:room-id "ABCD" :player-id 2
                                          :now-ms now})
          c  (claims (:token cr))]
      (is (= "pr-42-abcd" (:room cr)))
      (is (= "pr-42-abcd" (get-in c [:video :room])))
      (is (= "pr-42-abcd-p2" (:sub c)))))
  (testing "and an unset or blank prefix is production's"
    (doseq [prefix [nil ""]]
      (let [{:keys [provider]} (livekit/provider (assoc cfg :room-prefix prefix))]
        (is (= "werwolf-abcd"
               (:room (media/credentials provider {:room-id "ABCD" :player-id 0
                                                   :now-ms now}))))))))

(deftest a-token-is-signed-and-not-merely-encoded
  (let [{:keys [provider]} (livekit/provider cfg)
        token (:token (media/credentials provider {:room-id "ABCD" :player-id 0
                                                   :now-ms now}))]
    (testing "the wrong secret must not verify — otherwise anyone who can read
              the room code can mint themselves a microphone"
      (is (thrown? Exception (jwt/unsign token "not-the-secret" {:alg :hs256}))))
    (testing "and a token is three dot-separated segments, not a bare payload"
      (is (= 3 (count (str/split token #"\.")))))))

(deftest a-nameless-player-still-gets-a-label
  (let [{:keys [provider]} (livekit/provider cfg)
        c (claims (:token (media/credentials provider {:room-id "ABCD" :player-id 1
                                                       :now-ms now})))]
    (is (= "werwolf-abcd-p1" (:name c))
        "falling back to the identity, because LiveKit shows the name in its UI
         and an empty one renders as a blank participant")))
