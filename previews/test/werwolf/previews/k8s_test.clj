(ns werwolf.previews.k8s-test
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.previews.k8s :as k8s]))

(def Mi (* 1024 1024))

(deftest quantities
  (is (= (* 384 Mi) (k8s/parse-quantity "384Mi")))
  (is (= (* 2 1024 Mi) (k8s/parse-quantity "2Gi")))
  (is (= 123904 (k8s/parse-quantity "121Ki")))
  (is (= 1000000 (k8s/parse-quantity "1M")))
  (is (= 4096 (k8s/parse-quantity "4096")))
  (is (thrown? Exception (k8s/parse-quantity "lots"))))

(deftest meminfo
  (is (= (* 1234 1024)
         (k8s/mem-available "MemTotal:  4000000 kB\nMemFree: 1 kB\nMemAvailable:    1234 kB\n")))
  (is (thrown? Exception (k8s/mem-available "MemTotal: 4000000 kB\n"))))

(def labels {:app.kubernetes.io/managed-by "werwolf-previews"})

(deftest a-snapshot-of-the-namespace
  (let [snap (k8s/parse-snapshot
              {:items [{:kind "Deployment"
                        :metadata {:name "pr-42"
                                   :labels (assoc labels :werwolf-previews/pr "42")
                                   :annotations {:werwolf-previews/image "nix:0/x"
                                                 :werwolf-previews/sha "abc"
                                                 :werwolf-previews/last-activity "t"}}
                        :spec {:replicas 1}}
                       {:kind "Secret" :metadata {:name "pr-42" :labels (assoc labels :werwolf-previews/pr "42")}}
                       {:kind "Secret" :metadata {:name "livekit-preview" :labels labels}}
                       {:kind "PersistentVolumeClaim"
                        :metadata {:name "pr-7" :labels (assoc labels :werwolf-previews/pr "7")}}]})]
    (is (= {42 {:image "nix:0/x" :sha "abc" :last-activity "t" :replicas 1}}
           (:deployments snap)))
    (is (= #{42} (:secrets snap)))
    (testing "anything labelled with a PR counts as present, so leftovers get deleted"
      (is (= #{42 7} (:present snap))))))

(deftest memory-per-preview-from-the-metrics-api
  (is (= {42 (* 300 Mi) 7 (* 10 Mi)}
         (k8s/parse-usage
          {:items [{:metadata {:labels {:werwolf-previews/pr "42"}}
                    :containers [{:usage {:memory "300Mi"}}]}
                   {:metadata {:labels {:werwolf-previews/pr "7"}}
                    :containers [{:usage {:memory "4Mi"}} {:usage {:memory "6Mi"}}]}
                   {:metadata {:labels {}} :containers [{:usage {:memory "1Gi"}}]}]}))))

(def config {:namespace "werwolf-previews"
             :domain "werwolf-chrz.duckdns.org"
             :pod-memory (* 200 1024 1024)
             :pod-memory-quantity "200Mi"
             :livekit {:url "wss://werwolf-chrz.duckdns.org/livekit"}})

(def preview {:pr 42 :image "nix:0/nix/store/x.tar" :sha "abc"
              :last-activity "2026-10-12T10:00:00Z" :replicas 1})

(defn- env [deployment]
  (into {} (map (juxt :name identity))
        (get-in deployment [:spec :template :spec :containers 0 :env])))

(deftest a-preview-is-a-pvc-a-service-and-a-deployment
  (let [objs (k8s/manifests config preview)
        by-kind (into {} (map (juxt :kind identity)) objs)
        d (by-kind "Deployment")
        c (get-in d [:spec :template :spec :containers 0])]
    (is (= #{"PersistentVolumeClaim" "Service" "Deployment"} (set (keys by-kind))))
    (is (every? #(= "pr-42" (get-in % [:metadata :name])) objs))
    (is (every? #(= "42" (get-in % [:metadata :labels :werwolf-previews/pr])) objs))
    (is (= 1 (get-in d [:spec :replicas])))
    (testing "Recreate: the PVC is ReadWriteOnce, two pods cannot share it"
      (is (= "Recreate" (get-in d [:spec :strategy :type]))))
    (is (= "nix:0/nix/store/x.tar" (:image c)))
    (is (= {:memory "200Mi"} (get-in c [:resources :limits])))
    (testing "the heap leaves room for the JVM's own memory under the limit"
      (is (= "-Xmx88m" (get-in (env d) ["JDK_JAVA_OPTIONS" :value]))))
    (is (= "abc" (get-in d [:metadata :annotations :werwolf-previews/sha])))
    (testing "the commit goes in as the environment: the image carries none"
      (is (= "abc" (get-in (env d) ["WERWOLF_COMMIT" :value])))
      (is (= "0123456" (get-in (env (some #(when (= "Deployment" (:kind %)) %)
                                          (k8s/manifests config (assoc preview :sha "0123456789abcdef"))))
                               ["WERWOLF_COMMIT" :value]))
          "shortened as git shortens it"))
    (testing "rooms of its own on the shared LiveKit"
      (is (= "pr-42-" (get-in (env d) ["LIVEKIT_ROOM_PREFIX" :value])))
      (is (= "livekit" (get-in (env d) ["MEDIA_PROVIDER" :value]))))
    (is (= {:name "pr-42" :key "token-secret"}
           (get-in (env d) ["TOKEN_SECRET" :valueFrom :secretKeyRef])))))

(deftest without-livekit-a-preview-is-a-text-game
  (let [d (->> (k8s/manifests (dissoc config :livekit) preview)
               (filter #(= "Deployment" (:kind %))) first)]
    (is (= "noop" (get-in (env d) ["MEDIA_PROVIDER" :value])))
    (is (nil? (get (env d) "LIVEKIT_API_KEY")))))

(deftest the-heap-follows-the-limit
  (let [Mi (* 1024 1024)]
    (is (= 88 (k8s/heap-mib (* 200 Mi))))
    (is (= 272 (k8s/heap-mib (* 384 Mi))))
    (testing "never below what the app lives in"
      (is (= 32 (k8s/heap-mib (* 64 Mi)))))))
