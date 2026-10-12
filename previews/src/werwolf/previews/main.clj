(ns werwolf.previews.main
  "The PR preview controller: one round every `INTERVAL_SECS`, plus the
   endpoint Caddy asks before it gets a certificate for a preview's host.

   A round only ever acts on complete data. If GitHub, k3s or meminfo cannot be
   read, the round is skipped and nothing changes; the next one tries again."
  (:require [clojure.string :as str]
            [org.httpkit.server :as server]
            [werwolf.previews.github :as github]
            [werwolf.previews.k8s :as k8s]
            [werwolf.previews.plan :as plan]))

(defn config
  "Settings from the environment. The NixOS module sets every one of them."
  [env]
  (let [get-env (fn [k default] (or (not-empty (get env k)) default))
        pod-memory (get-env "POD_MEMORY" "384Mi")]
    {:repo (get-env "PREVIEWS_REPO" "jakuzure/werwolf-clj")
     :domain (get-env "PREVIEWS_DOMAIN" nil)
     :namespace (get-env "PREVIEWS_NAMESPACE" "werwolf-previews")
     :graphql-url (get-env "GITHUB_GRAPHQL_URL" "https://api.github.com/graphql")
     :github-token-file (get-env "GITHUB_TOKEN_FILE" nil)
     :livekit-key-file (get-env "LIVEKIT_KEY_FILE" nil)
     :livekit-key-name (get-env "LIVEKIT_KEY_NAME" "werwolf-preview")
     :livekit-url (get-env "LIVEKIT_URL" nil)
     :pod-memory (k8s/parse-quantity pod-memory)
     :pod-memory-quantity pod-memory
     :memory-reserve (k8s/parse-quantity (get-env "MEMORY_RESERVE" "1Gi"))
     :max-previews (parse-long (get-env "MAX_PREVIEWS" "4"))
     :grace-rounds 3
     :interval-secs (parse-long (get-env "INTERVAL_SECS" "60"))
     :ask-port (parse-long (get-env "ASK_PORT" "8341"))
     :meminfo (get-env "MEMINFO_PATH" "/proc/meminfo")
     :kubectl (get-env "KUBECTL" "kubectl")}))

(defn livekit-key
  "The `name: secret` line for `key-name` out of a LiveKit key file."
  [text key-name]
  (or (some (fn [line]
              (let [[k v] (map str/trim (str/split line #":" 2))]
                (when (and (= k key-name) (not (str/blank? v)))
                  {:key k :secret v})))
            (str/split-lines text))
      (throw (ex-info (str "no key " key-name " in the LiveKit key file") {}))))

(defn allowed?
  "Whether Caddy may get a certificate for `host`: exactly pr-<n>.<domain> for
   a PR that has a preview."
  [domain hosts host]
  (boolean
   (when-let [[_ n] (and host (re-matches #"pr-([1-9][0-9]*)\.(.+)" host))]
     (and (str/ends-with? host (str "." domain))
          (= host (str "pr-" n "." domain))
          (contains? hosts (parse-long n))))))

(defn- secrets
  "Read per round, so a renewed token or key needs no restart."
  [{:keys [github-token-file livekit-key-file livekit-key-name livekit-url] :as cfg}]
  (cond-> (assoc cfg :github-token (str/trim (slurp github-token-file)))
    livekit-key-file (assoc :livekit (assoc (livekit-key (slurp livekit-key-file)
                                                         livekit-key-name)
                                            :url livekit-url))))

(defn read-proc
  "A file's text, procfs included. babashka's `slurp` of a path fails on
   /proc/meminfo with \"Invalid argument\"; through a stream it works."
  [path]
  (with-open [in (java.io.FileInputStream. (str path))]
    (slurp in)))

(defn- attempt! [f]
  (try (f) (catch Exception e (println "failed:" (ex-message e)))))

(defn round!
  "One reconcile. Returns the next round's short-rounds count and the hosts
   Caddy may ask for; throws (and changes nothing) when a snapshot fails."
  [cfg short-rounds]
  (let [cfg (secrets cfg)
        prs (github/fetch-prs cfg)
        k8s-state (k8s/snapshot cfg)
        memory {:available (k8s/mem-available (read-proc (:meminfo cfg)))
                :usage (k8s/usage cfg)}
        out (plan/desired-state {:prs prs :k8s k8s-state :memory memory
                                 :config cfg :short-rounds short-rounds})]
    ;; One failing PR must not hold up the others: log it, the next round
    ;; retries, and every action is idempotent.
    (doseq [n (sort (:delete out))]
      (println "PR" n "closed, deleting its preview")
      (attempt! #(k8s/delete! cfg n)))
    (doseq [n (sort (:create-secrets out))]
      (attempt! #(k8s/create-secret! cfg n)))
    (k8s/apply! cfg (cond-> (vec (mapcat #(k8s/manifests cfg %) (:previews out)))
                      (:livekit cfg) (conj (k8s/livekit-secret cfg))))
    (println (str "previews: "
                  (str/join ", " (for [{:keys [pr replicas]} (:previews out)]
                                   (str "pr-" pr (when (zero? replicas) " (asleep)"))))
                  (when (pos? (:short-rounds out))
                    (str " · short on memory for " (:short-rounds out) " round(s)"))))
    out))

(defn- ask-handler [domain hosts]
  (fn [{:keys [query-string]}]
    (let [host (some->> query-string
                        (re-find #"(?:^|&)domain=([^&]*)")
                        second
                        java.net.URLDecoder/decode)]
      {:status (if (allowed? domain @hosts host) 200 404)})))

(defn -main [& _]
  (let [cfg (config (System/getenv))
        hosts (atom #{})]
    (when-not (and (:domain cfg) (:github-token-file cfg))
      (binding [*out* *err*]
        (println "PREVIEWS_DOMAIN and GITHUB_TOKEN_FILE are required"))
      (System/exit 1))
    (server/run-server (ask-handler (:domain cfg) hosts)
                       {:ip "127.0.0.1" :port (:ask-port cfg)})
    (println "werwolf-previews: asking GitHub about" (:repo cfg)
             "every" (:interval-secs cfg) "s, ask endpoint on 127.0.0.1:" (:ask-port cfg))
    (loop [short-rounds 0]
      (let [next-short
            (try
              (let [out (round! cfg short-rounds)]
                (reset! hosts (:hosts out))
                (:short-rounds out))
              (catch Exception e
                (println "round skipped:" (ex-message e))
                short-rounds))]
        (Thread/sleep (* 1000 (:interval-secs cfg)))
        (recur next-short)))))
