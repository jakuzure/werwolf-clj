(ns werwolf.previews.k8s
  "Everything the controller knows about k3s, and everything it does there.

   No state of its own: what it needs is on the objects themselves, as labels
   (which PR) and annotations (which image, which commit, last activity), so a
   restarted controller picks up exactly where the last one stopped.

   kubectl rather than a client library: babashka has no good one, and
   `kubectl apply` makes every write idempotent for free."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def managed-by "werwolf-previews")

;; ----------------------------------------------------------------- parsing ---

(def ^:private suffixes
  {"" 1 "k" 1000 "M" 1000000 "G" 1000000000
   "Ki" 1024 "Mi" (* 1024 1024) "Gi" (* 1024 1024 1024)})

(defn parse-quantity
  "A Kubernetes memory quantity (`384Mi`, `1Gi`, `4096`) in bytes."
  [s]
  (if-let [[_ n unit] (re-matches #"(\d+)([kMG]i?|Ki)?" (str s))]
    (* (parse-long n) (suffixes (or unit "")))
    (throw (ex-info (str "not a memory quantity: " s) {:quantity s}))))

(defn mem-available
  "MemAvailable from /proc/meminfo's text, in bytes."
  [meminfo]
  (if-let [[_ kb] (re-find #"(?m)^MemAvailable:\s+(\d+) kB" meminfo)]
    (* 1024 (parse-long kb))
    (throw (ex-info "no MemAvailable in meminfo" {}))))

(defn- pr-of [obj]
  (some-> (get-in obj [:metadata :labels :werwolf-previews/pr]) parse-long))

(defn parse-snapshot
  "`kubectl get … -o json` of the namespace as
   {:deployments {pr {:image :sha :last-activity :replicas}}
    :secrets #{pr} :present #{pr}}."
  [{:keys [items]}]
  (reduce
   (fn [acc {:keys [kind] :as obj}]
     (if-let [n (pr-of obj)]
       (let [a (get-in obj [:metadata :annotations])
             acc (update acc :present conj n)]
         (case kind
           "Deployment" (assoc-in acc [:deployments n]
                                  {:image (:werwolf-previews/image a)
                                   :sha (:werwolf-previews/sha a)
                                   :last-activity (:werwolf-previews/last-activity a)
                                   :replicas (get-in obj [:spec :replicas])})
           "Secret" (update acc :secrets conj n)
           acc))
       acc))
   {:deployments {} :secrets #{} :present #{}}
   items))

(defn parse-usage
  "The metrics API's PodMetrics list as {pr bytes}."
  [{:keys [items]}]
  (reduce (fn [acc obj]
            (if-let [n (pr-of obj)]
              (update acc n (fnil + 0)
                      (reduce + (map #(parse-quantity (get-in % [:usage :memory]))
                                     (:containers obj))))
              acc))
          {} items))

;; --------------------------------------------------------------- manifests ---

(defn- object-name [n] (str "pr-" n))

(defn- meta-for [n & [annotations]]
  (cond-> {:name (object-name n)
           :labels {:app.kubernetes.io/managed-by managed-by
                    :werwolf-previews/pr (str n)}}
    annotations (assoc :annotations annotations)))

(defn- secret-env [env-name secret key]
  {:name env-name :valueFrom {:secretKeyRef {:name secret :key key}}})

(def non-heap
  "What a preview's JVM needs besides its heap, in bytes. Measured at about
   85 MiB (metaspace, JIT code, the CDS archive, threads) under SerialGC, which
   is what the JVM picks in a container this small; the rest is margin for
   malloc, sqlite and what the kernel counts against the pod."
  (* 112 1024 1024))

(defn heap-mib
  "The -Xmx for a pod limited to `pod-memory` bytes. The launcher's
   MaxRAMPercentage=75 would leave a fixed ~85 MiB of non-heap 25% of the
   limit, which at 200Mi is 50: the heap would grow into an OOM kill. The live
   heap is about 25 MiB, so 32 is the floor."
  [pod-memory]
  (max 32 (quot (- pod-memory non-heap) (* 1024 1024))))

(defn- env [{:keys [livekit pod-memory]} n sha]
  (into [(secret-env "TOKEN_SECRET" (object-name n) "token-secret")
         {:name "JDK_JAVA_OPTIONS" :value (str "-Xmx" (heap-mib pod-memory) "m")}
         ;; Which commit this preview is, for its start page: the image carries
         ;; none, so the same code is the same image on every commit.
         {:name "WERWOLF_COMMIT" :value (subs (str sha) 0 (min 7 (count (str sha))))}]
        (if livekit
          [{:name "MEDIA_PROVIDER" :value "livekit"}
           {:name "LIVEKIT_URL" :value (:url livekit)}
           {:name "LIVEKIT_ROOM_PREFIX" :value (str "pr-" n "-")}
           (secret-env "LIVEKIT_API_KEY" "livekit-preview" "api-key")
           (secret-env "LIVEKIT_API_SECRET" "livekit-preview" "api-secret")]
          [{:name "MEDIA_PROVIDER" :value "noop"}])))

(defn manifests
  "The PVC, Service and Deployment of one preview."
  [{:keys [pod-memory-quantity] :as config}
   {:keys [pr image sha last-activity replicas]}]
  (let [selector {:werwolf-previews/pr (str pr)}]
    [{:apiVersion "v1" :kind "PersistentVolumeClaim"
      :metadata (meta-for pr)
      :spec {:accessModes ["ReadWriteOnce"]
             :storageClassName "local-path"
             :resources {:requests {:storage "256Mi"}}}}
     {:apiVersion "v1" :kind "Service"
      :metadata (meta-for pr)
      :spec {:selector selector
             :ports [{:name "http" :port 8080 :targetPort 8080}]}}
     {:apiVersion "apps/v1" :kind "Deployment"
      :metadata (meta-for pr {:werwolf-previews/image image
                              :werwolf-previews/sha sha
                              :werwolf-previews/last-activity last-activity})
      :spec {:replicas replicas
             :strategy {:type "Recreate"}
             :selector {:matchLabels selector}
             :template
             {:metadata {:labels (:labels (meta-for pr))}
              :spec {:automountServiceAccountToken false
                     :enableServiceLinks false
                     :containers
                     [{:name "werwolf"
                       :image image
                       :imagePullPolicy "IfNotPresent"
                       :env (env config pr sha)
                       :ports [{:containerPort 8080}]
                       :resources {:requests {:memory pod-memory-quantity}
                                   :limits {:memory pod-memory-quantity}}
                       :readinessProbe {:httpGet {:path "/health" :port 8080}
                                        :periodSeconds 5}
                       :volumeMounts [{:name "data" :mountPath "/data"}
                                      {:name "tmp" :mountPath "/tmp"}]}]
                     :volumes [{:name "data"
                                :persistentVolumeClaim {:claimName (object-name pr)}}
                               {:name "tmp" :emptyDir {}}]}}}}]))

(defn livekit-secret
  "The preview LiveKit key every preview shares. Never production's."
  [{:keys [livekit]}]
  {:apiVersion "v1" :kind "Secret"
   :metadata {:name "livekit-preview"
              :labels {:app.kubernetes.io/managed-by managed-by}}
   :stringData {:api-key (:key livekit) :api-secret (:secret livekit)}})

;; -------------------------------------------------------------------- I/O ---

(defn- kubectl [{:keys [kubectl namespace]} args & [input]]
  (let [res (apply p/shell (cond-> {:out :string :err :string :continue true}
                             input (assoc :in input))
                   kubectl "-n" namespace args)]
    (when-not (zero? (:exit res))
      (throw (ex-info (str "kubectl " (str/join " " args) ": " (str/trim (:err res)))
                      {:args args})))
    (:out res)))

(defn snapshot [config]
  (parse-snapshot
   (json/parse-string
    (kubectl config ["get" "deployment,secret,service,pvc"
                     "-l" (str "app.kubernetes.io/managed-by=" managed-by)
                     "-o" "json"])
    true)))

(defn usage
  "Current memory per preview. Missing metrics (metrics-server restarting, a pod
   too new to have any) count as nothing in use: that only underestimates free
   memory, and the grace rounds absorb it."
  [config]
  (try
    (parse-usage
     (json/parse-string
      (kubectl config ["get" "--raw"
                       (str "/apis/metrics.k8s.io/v1beta1/namespaces/"
                            (:namespace config) "/pods")])
      true))
    (catch Exception e
      (println "metrics unavailable, counting no usage:" (ex-message e))
      {})))

(defn- random-hex [bytes]
  (let [buf (byte-array bytes)]
    (.nextBytes (java.security.SecureRandom.) buf)
    (apply str (map #(format "%02x" %) buf))))

(defn create-secret!
  "TOKEN_SECRET for one preview. Created once and never rotated, so logins
   survive redeploys of the same PR. `create`, not `apply`: if it exists after
   all, failing beats overwriting it."
  [config n]
  (kubectl config ["create" "-f" "-"]
           (json/generate-string
            {:apiVersion "v1" :kind "Secret"
             :metadata (meta-for n)
             :stringData {:token-secret (random-hex 32)}})))

(defn delete! [config n]
  (kubectl config ["delete" "deployment,service,secret,pvc"
                   "-l" (str "werwolf-previews/pr=" n)
                   "--ignore-not-found" "--wait=false"]))

(defn apply! [config objects]
  (when (seq objects)
    (kubectl config ["apply" "-f" "-"]
             (json/generate-string {:apiVersion "v1" :kind "List" :items objects}))))
