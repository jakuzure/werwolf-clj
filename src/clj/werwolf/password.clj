(ns werwolf.password
  "Room passwords: hashed on the way in, checked in constant time, never kept.

   A room password is not an account credential. It is a word the host reads
   out at the table so that the open-room list stops being an open door — the
   same trust level as the room code, one notch up. That shapes two decisions:

   * **It is hashed anyway.** The event log is a SQLite file that outlives the
     evening, gets copied into backups and is opened by hand when something
     goes wrong. People reuse words, and a word sitting in that file in plain
     text would be read by whoever opens it next. PBKDF2-HMAC-SHA256 because the
     JDK has it — no new dependency for a feature this small.

   * **It is forgiving about how it was typed.** Trimmed, case-folded and
     Unicode-normalised before hashing and before checking. A phone keyboard
     capitalises the first letter, adds a space after an accepted suggestion and
     may compose `ü` as `u` plus a combining mark; a password said aloud carries
     none of those, so none of them should be able to lock a friend out. The
     cost is a smaller space to guess in, which `werwolf.room`'s attempt limit
     and the server being a private one are what answer.

   The encoded form names its own scheme and work factor
   (`pbkdf2-sha256$<iterations>$<salt>$<hash>`, base64), so the factor can be
   raised later without orphaning a room that already has a password.

   JVM-only on purpose. The engine stores the result as an opaque string and
   never needs to know what it is."
  (:refer-clojure :exclude [hash])
  (:require [clojure.string :as str])
  (:import (java.security MessageDigest SecureRandom)
           (java.text Normalizer Normalizer$Form)
           (java.util Base64 Locale)
           (javax.crypto SecretKeyFactory)
           (javax.crypto.spec PBEKeySpec)))

(def ^:private scheme "pbkdf2-sha256")

(def ^:const iterations
  "The work factor for NEW hashes. Old ones carry their own.

   Well above the 100k floor while keeping one check in the tens of
   milliseconds on a small host: a join waits for it, and so does everybody
   else's join to the same room if it ever ran under the room's lock — which is
   why `werwolf.room` does the hashing outside it."
  210000)

(def ^:private salt-bytes 16)
(def ^:private hash-bits 256)

;; Bounds on a work factor READ from a hash. Ours are all `iterations`; anything
;; wildly outside these is not a hash this code wrote, and verifying a crafted
;; two-billion-round one would pin a CPU for minutes.
(def ^:private min-iterations 1000)
(def ^:private max-iterations 10000000)

(defonce ^:private ^SecureRandom rnd (SecureRandom.))

(defn blank?
  "Is this not a password at all? Blank after trimming counts: a field holding
   only the space a keyboard appended is an empty field."
  [s]
  (str/blank? s))

(defn- normalise ^String [^String s]
  (-> (Normalizer/normalize (str/trim s) Normalizer$Form/NFC)
      (.toLowerCase Locale/ROOT)))

(defn- pbkdf2 ^bytes [^String password ^bytes salt iters]
  (let [spec (PBEKeySpec. (.toCharArray password) salt (int iters) (int hash-bits))]
    (try
      (.getEncoded (.generateSecret (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256") spec))
      (finally (.clearPassword spec)))))

(defn- b64 ^String [^bytes b] (.encodeToString (Base64/getEncoder) b))
(defn- unb64 ^bytes [^String s] (.decode (Base64/getDecoder) s))

(defn hash-password
  "Hash a password for storage. Throws on a blank one: callers decide what a
   blank field means (no password at all), and must do so before getting here."
  ^String [password]
  (when (blank? password)
    (throw (IllegalArgumentException. "blank password")))
  (let [salt (byte-array salt-bytes)]
    (.nextBytes rnd salt)
    (str/join "$" [scheme iterations (b64 salt) (b64 (pbkdf2 (normalise password) salt iterations))])))

(defn- parse
  "The pieces of an encoded hash, or nil if it is not one this code could have
   written."
  [encoded]
  (when (string? encoded)
    (let [[s iters salt hash :as parts] (str/split encoded #"\$" -1)]
      (when (and (= 4 (count parts)) (= scheme s))
        (let [n (parse-long iters)
              salt (try (unb64 salt) (catch IllegalArgumentException _ nil))
              hash (try (unb64 hash) (catch IllegalArgumentException _ nil))]
          (when (and n (<= min-iterations n max-iterations)
                     (seq salt) (seq hash))
            {:iterations n :salt salt :hash hash}))))))

(defn verify
  "Does `password` match `encoded`? Never throws: a blank attempt, a nil and a
   malformed hash are all simply false.

   Compared with `MessageDigest/isEqual`, which takes the same time however many
   leading bytes agree, so the reply's timing says nothing about how close a
   guess came."
  [password encoded]
  (boolean
   (when-not (blank? password)
     (when-let [{:keys [iterations salt hash]} (parse encoded)]
       (MessageDigest/isEqual ^bytes hash
                              ^bytes (pbkdf2 (normalise password) salt iterations))))))
